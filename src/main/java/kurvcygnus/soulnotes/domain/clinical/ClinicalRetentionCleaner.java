package kurvcygnus.soulnotes.domain.clinical;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.vertx.VertxContext;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.domain.clinical.service.ClinicalAssessmentService;
import kurvcygnus.soulnotes.utils.PrintUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 临床评估保留期启动清理: 启动时异步删除早于保留天数的评估, 一次执行不重试.
 *
 * @implNote 零依赖政策下无 quarkus-scheduler, 定时清理不可用 (Spec §4) — 启动时一次清理 +
 *           部署侧按需重跑是本期的取舍; 清理经 Vert.x duplicated context 异步执行, 不阻塞启动.
 * @since 1.2.0
 */
@ApplicationScoped
public class ClinicalRetentionCleaner
{
    private static final Logger LOG = LoggerFactory.getLogger(ClinicalRetentionCleaner.class);

    private final @NotNull ClinicalAssessmentService service;
    private final @NotNull Vertx vertx;
    private final int retentionDays;

    @Inject
    public ClinicalRetentionCleaner(
        @NotNull ClinicalAssessmentService service,
        @NotNull Vertx vertx,
        @ConfigProperty(name = "clinical.retention-days", defaultValue = "90") int retentionDays
    )
    {
        this.service = service;
        this.vertx = vertx;
        this.retentionDays = retentionDays;
    }

    /**
     * 启动钩子: 异步执行一次清理, 失败仅 WARN (清理失败不影响服务可用).
     */
    //! 生命周期回调由框架通过反射调用, IDE 静态分析误报 ev 形参为未使用 (ChatWebSocket 生命周期回调同款).
    @SuppressWarnings("unused")
    void onStart(@Observes @NotNull StartupEvent ev)
    {
        CompletableFuture.runAsync(() ->
            cleanOnce(retentionDays).subscribe().with(
                deleted -> { if(retentionDays > 0) LOG.info(PrintUtils.quickFormat("临床评估启动清理完成 (保留 {} 天)", retentionDays)); },
                f -> LOG.warn("临床评估启动清理失败: {}", f.getMessage())
            )
        );
    }

    /**
     * 执行一次清理.
     * @implNote 静态 Panache delete 解析会话强依赖 "安全" Vert.x context
     *           ({@code SessionOperations#vertxContext} 无上下文即同步抛 "No current Vertx context found"),
     *           而本方法的生产消费方 {@link #onStart} 链路运行在 commonPool (无任何 Vert.x context) —
     *           直接委托会同步抛出并被 {@link CompletableFuture} 静默吞掉, 清理永久失效且无 WARN.
     *           故仿 ChatWebSocket#onMessage 先例逐次建立 duplicated context 跳转执行
     *           (与 RESTEasy Reactive 逐请求建上下文同机制, 会话槽位天然隔离), 测试线程 (JUnit 直调) 同样受益;
     *           days &lt;= 0 禁用路径在触碰 Panache 之前短路, 无上下文要求, 保持任意线程可调.
     * @implNote ChatWebSocket 先例的调用线程位于 Quarkus 已标记安全的请求 context, duplicate 继承安全标记;
     *           本类调用线程 (commonPool/JUnit) 无 context, 新建 duplicated context 默认不被标记, Panache 直接
     *           抛 "hasn't been flagged as such" — 须按 Quarkus 建请求上下文同款显式 {@code setContextSafe(true)}
     *           (RESTEasy Reactive 每请求即如此标记). 另: runOnContext 回调内的同步异常不会流经 emitter,
     *           以 try-catch 兜底转为失败信号, 避免 await 侧超时掩藏真实错误.
     *
     * @param days 保留天数 (<=0 禁用, 直接返回 0)
     * @return 删除行数
     */
    public @NotNull Uni<Long> cleanOnce(int days)
    {
        if(days <= 0)
            return service.cleanupOlderThan(days);//* 禁用短路 (Task 3 契约): 不触碰 Panache, 任意线程安全.
        final var duplicated = VertxContext.getOrCreateDuplicatedContext(vertx.getDelegate());
        io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle.setContextSafe(duplicated, true);
        //? 已知副作用: 无上下文线程上的 getOrCreateDuplicatedContext 会新建 event loop root context 并写入
        //? 调用线程的 thread-local; onStart 经 runAsync 落在 JVM 全域共享的 commonPool, 清理完成后该线程
        //? 残留 Vertx.currentContext() != null, 后续复用该线程的无关任务 (如 ClinicalSchemaNormalizer 的
        //? runAsync) 都会看到该 context — 跨组件隐蔽状态残留. 本仓库 commonPool 消费面仅 normalizer 与
        //? 本类, 均无合法的 commonPool Panache 使用, 残留暂无实害; 若未来 commonPool 出现 Panache 消费方须重新评估.
        return Uni.createFrom().emitter(emitter ->
            duplicated.runOnContext((Void ignored) ->
            {
                try { service.cleanupOlderThan(days).subscribe().with(emitter::complete, emitter::fail); }
                catch(RuntimeException e) { emitter.fail(e); }//* 同步建链异常 (上下文/配置解析) 转为失败信号.
            })
        );
    }
}
