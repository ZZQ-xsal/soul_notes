package kurvcygnus.soulnotes.config.prelaunch;

import kurvcygnus.soulnotes.utils.PrintUtils;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * <b>PostgreSQL 探测网关</b> (生产实现).
 * <p>Pre-Launch 阶段无容器无事件循环, 每次操作手工构建独立 Vertx + maxSize=1 小池, 用毕即毁 —
 * 不与运行期 Quarkus 管理的数据源共享任何资源. 响应式 API 经 {@code toCompletionStage} 阻塞等待,
 * 这在 Pre-Launch 上下文是安全且有意的 (主线程阻塞发生在 Quarkus 启动之前).
 * 池构建用 {@link PgBuilder}: {@code PgPool} 工厂在 Vert.x 4.5 起弃用, 零弃用政策下禁用.</p>
 *
 * <p>错误码映射: SQLSTATE 28P01 (invalid_password) / 28000 (invalid_authorization_specification) → AUTH_FAILED;
 * 3D000 (invalid_catalog_name) → DB_MISSING; 连接拒绝/超时及其余未映射失败 → UNREACHABLE.</p>
 * @since 2.0
 */
public final class PgGateway implements IDatabaseGateway
{
    //* 连接超时 5s; await 上限给足建连 + 慢查询余量, 兜底防止极端情况下调用线程永久挂起.
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int AWAIT_TIMEOUT_MS = CONNECT_TIMEOUT_MS * 4;

    private static final @NotNull Set<String> AUTH_SQLSTATES = Set.of("28P01", "28000");
    private static final @NotNull String DB_MISSING_SQLSTATE = "3D000";
    private static final @NotNull String DUPLICATE_DB_SQLSTATE = "42P04";
    private static final @NotNull String PRIVILEGE_SQLSTATE = "42501";

    //* createDatabase 经主机默认维护库 postgres 落地.
    private static final @NotNull String POSTGRES_DB = "postgres";

    //* SCHEMA_MISSING 判定的期望表集: 三张业务表 + 版本表, 与 db/schema 脚本一一对应.
    private static final @NotNull List<String> EXPECTED_TABLES =
        List.of("users", "mood_diaries", "ai_chat_sessions", "platform_schema_version");

    //* 脚本清单显式排序而非 classpath 目录枚举: jar/fast-jar/native 内目录 URL 不可枚举, 显式清单在所有打包形态下行为一致;
    //* 新增脚本 = 追加清单项 + 资源文件, 漏配在 readScript 处快速失败.
    private static final @NotNull String SCHEMA_DIR = "db/schema/";
    private static final @NotNull List<String> SCHEMA_SCRIPTS =
        List.of("01_users.sql", "02_mood_diaries.sql", "03_ai_chat_sessions.sql", "04_platform_schema_version.sql");

    //region IDatabaseGateway

    @Override public @NotNull ProbeResult probe(@NotNull DbTarget target)
    {
        Objects.requireNonNull(target, "Param \"target\" must not be null!");
        final var vertx = Vertx.vertx();
        try
        {
            final var pool = buildPool(vertx, connectOptions(target));
            try
            {
                //? IDE 的 "未配置 SQL 方言" 告警不响应 @SuppressWarnings, 属项目未配置方言的环境噪音 (与 IAsrEngine 覆写注解告警同为既有基线).
                awaitState(pool.query("SELECT 1").execute());//* 连通性 + 鉴权 + 库存在性: 失败经 GatewayFailure 携带五态抛出.
                final var found = new HashSet<String>();
                awaitState(pool.query(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'").execute()).
                    forEach(row -> found.add(row.getString(0).toLowerCase(Locale.ROOT)));
                final var missing = EXPECTED_TABLES.stream().filter(t -> !found.contains(t)).toList();
                return missing.isEmpty() ? new ProbeResult(ProbeResult.State.OK, List.of())
                    : new ProbeResult(ProbeResult.State.SCHEMA_MISSING, missing);
            }
            finally { closeQuietly(pool); }
        }
        catch(GatewayFailure failure) { return failure.toResult(); }
        finally { closeVertx(vertx); }
    }

    @SuppressWarnings("SqlSourceToSinkFlow")//! IDE 注入告警为误报 — 库名经 quotedIdentifier 标准双引号转义, 且 CREATE DATABASE 属 DDL 无法参数化.
    @Override public void createDatabase(@NotNull DbTarget target)
    {
        Objects.requireNonNull(target, "Param \"target\" must not be null!");
        final var maintenance = new DbTarget(target.host(), target.port(), POSTGRES_DB, target.user(), target.password());
        final var vertx = Vertx.vertx();
        try
        {
            final var pool = buildPool(vertx, connectOptions(maintenance));
            try
            {
                awaitPlain(pool.query("CREATE DATABASE " + quotedIdentifier(target.database())).execute());
            }
            catch(IllegalStateException e)
            {
                final var cause = e.getCause();
                if(cause instanceof PgException pe)
                {
                    if(DUPLICATE_DB_SQLSTATE.equals(pe.getSqlState()))
                        throw new IllegalStateException(PrintUtils.quickFormat("数据库 {} 已存在 (或并发创建竞争), 请重试探测", target.database()), e);
                    if(PRIVILEGE_SQLSTATE.equals(pe.getSqlState()))
                        throw new IllegalStateException(PrintUtils.quickFormat("账号 {} 无 CREATEDB 权限, 无法自动建库", target.user()), e);
                }
                throw e;
            }
            finally { closeQuietly(pool); }
        }
        finally { closeVertx(vertx); }
    }

    @SuppressWarnings("unused")//! 单参便利委托的进度丢弃端点: 空 lambda 是 no-op 消费方的标准写法, 未使用形参告警不适用.
    @Override public void applySchema(@NotNull DbTarget target) { applySchema(target, script -> {}); }

    @SuppressWarnings("SqlSourceToSinkFlow")//! 脚本为版本库内嵌资源非外部输入, 注入告警不适用.
    @Override public void applySchema(@NotNull DbTarget target, @NotNull Consumer<String> scriptProgress)
    {
        Objects.requireNonNull(target, "Param \"target\" must not be null!");
        Objects.requireNonNull(scriptProgress, "Param \"scriptProgress\" must not be null!");
        final var vertx = Vertx.vertx();
        try
        {
            final var pool = buildPool(vertx, connectOptions(target));
            try
            {
                for(final var script : SCHEMA_SCRIPTS)
                {
                    try { awaitPlain(pool.query(readScript(script)).execute()); }
                    catch(IllegalStateException e)
                        { throw new IllegalStateException(PrintUtils.quickFormat("schema 脚本执行失败: {}", script), e); }//! 单脚本失败即中止并携带脚本名; 简单查询协议保证整脚本语句处于同一隐式事务批.
                    scriptProgress.accept(script);  //* 逐脚本进度回传 (向导逐行回显), 执行成功后才回报, 失败脚本不产生进度行.
                }
            }
            finally { closeQuietly(pool); }
        }
        finally { closeVertx(vertx); }
    }

    //endregion

    //region 内部工具

    //* 池构建收口: PgPool 静态工厂已弃用 (零弃用政策), PgBuilder 为 Vert.x 4.5+ 官方替代; 显式挂载探测专用 Vertx 实例.
    private static @NotNull Pool buildPool(@NotNull Vertx vertx, @NotNull PgConnectOptions options)
    {
        return PgBuilder.pool(builder -> builder.using(vertx).connectingTo(options).with(poolOptions()));
    }

    //* 建库/套脚本共用探测路径的建连参数: 超时与池规格全链路一致.
    private static @NotNull PgConnectOptions connectOptions(@NotNull DbTarget target)
    {
        final var options = new PgConnectOptions().
            setHost(target.host()).
            setPort(target.port()).
            setDatabase(target.database()).
            setConnectTimeout(CONNECT_TIMEOUT_MS);
        if(target.user() != null) options.setUser(target.user());
        if(target.password() != null) options.setPassword(target.password());
        return options;
    }

    private static @NotNull PoolOptions poolOptions() { return new PoolOptions().setMaxSize(1); }

    /**
     * <span style="color: 95cc6d">阻塞等待探测类查询, 失败按 SQLSTATE 归入五态.</span>
     * @throws GatewayFailure 携带归入后的状态与原始原因
     */
    private static <T> T awaitState(@NotNull Future<T> future)
    {
        try { return future.toCompletionStage().toCompletableFuture().get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();//* 恢复中断标记, 不吞中断语义.
            throw new IllegalStateException("数据库探测被中断", e);
        }
        catch(TimeoutException e) { throw new GatewayFailure(ProbeResult.State.UNREACHABLE, e); }
        catch(ExecutionException e)
        {
            final var cause = e.getCause() != null ? e.getCause() : e;
            if(cause instanceof PgException pe)
            {
                if(AUTH_SQLSTATES.contains(pe.getSqlState())) throw new GatewayFailure(ProbeResult.State.AUTH_FAILED, pe);
                if(DB_MISSING_SQLSTATE.equals(pe.getSqlState())) throw new GatewayFailure(ProbeResult.State.DB_MISSING, pe);
            }
            //! 连接拒绝/未知主机等非 SQLSTATE 失败与未映射 SQLSTATE 一律保守归入 UNREACHABLE:
            //! 非 OK 即 BLOCK, 拒绝启动优于运行期 500.
            throw new GatewayFailure(ProbeResult.State.UNREACHABLE, cause);
        }
    }

    //* 建库/套脚本的阻塞等待 (结果一律不消费, 故为 void): 失败以 IllegalStateException 呈现并保留原因链, 调用方 (向导) 据此反馈.
    private static void awaitPlain(@NotNull Future<?> future)
    {
        try { future.toCompletionStage().toCompletableFuture().get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("数据库操作被中断", e);
        }
        catch(TimeoutException e) { throw new IllegalStateException(PrintUtils.quickFormat("数据库操作超时 (上限 {}ms)", AWAIT_TIMEOUT_MS), e); }
        catch(ExecutionException e) { throw new IllegalStateException(e.getCause() != null ? e.getCause() : e); }
    }

    //* SQL 标识符安全引用: 引号内唯一需转义的是双引号自身 (标准加倍写法), 杜绝经库名注入 DDL 的可能.
    private static @NotNull String quotedIdentifier(@NotNull String identifier)
    {
        if(identifier.isEmpty())
            throw new IllegalStateException("数据库标识符不可为空");
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static @NotNull String readScript(@NotNull String name)
    {
        try(InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(SCHEMA_DIR + name))
        {
            if(in == null)
                throw new IllegalStateException(PrintUtils.quickFormat(
                    "classpath 缺少 schema 脚本 {}{} (构建资源配置错误, 快速失败)", SCHEMA_DIR, name));//! 漏配属构建错误而非用户可修复项, 快速失败并指名脚本.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch(IOException e) { throw new IllegalStateException(PrintUtils.quickFormat("读取 schema 脚本失败: {}", name), e); }
    }

    //* 池关闭失败不应掩盖主流程异常/结论 — 探测池为一次性对象, 最坏情形仅是池线程存活至进程退出.
    private static void closeQuietly(@NotNull Pool pool)
    {
        try { pool.close().toCompletionStage().toCompletableFuture().get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        catch(ExecutionException | TimeoutException e)
        {
            //! 有意吞掉: 关闭失败不改变探测/写入结论, 上抛反而掩盖原始异常.
        }
    }

    //* Vertx 非 AutoCloseable, 统一在此收口关闭; 探测线程池为非守护线程, 不关闭会拖住 JVM 退出.
    private static void closeVertx(@NotNull Vertx vertx)
    {
        try { vertx.close().toCompletionStage().toCompletableFuture().get(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        catch(ExecutionException | TimeoutException e)
        {
            //! 有意吞掉: 理由同 closeQuietly, 此处失败不改变业务结论.
        }
    }

    //endregion

    //* 探测内部失败的载体: 把 "异常 + 五态归类" 一并传出 try 块, probe 顶层统一转 ProbeResult.
    private static final class GatewayFailure extends RuntimeException
    {
        private final @NotNull ProbeResult.State state;

        GatewayFailure(@NotNull ProbeResult.State state, @NotNull Throwable cause)
        {
            super(cause);
            this.state = Objects.requireNonNull(state, "Param \"state\" must not be null!");
        }

        ProbeResult toResult() { return new ProbeResult(state, List.of()); }
    }
}
