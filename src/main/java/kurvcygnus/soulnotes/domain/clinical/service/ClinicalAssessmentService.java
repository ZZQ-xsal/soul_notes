package kurvcygnus.soulnotes.domain.clinical.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.domain.auth.entity.User;
import kurvcygnus.soulnotes.domain.clinical.RevealPolicy;
import kurvcygnus.soulnotes.domain.clinical.dto.AssessmentVo;
import kurvcygnus.soulnotes.domain.clinical.dto.StatsSummary;
import kurvcygnus.soulnotes.domain.clinical.entity.ClinicalAssessment;
import kurvcygnus.soulnotes.dto.PageRequest;
import kurvcygnus.soulnotes.utils.JsonUtils;
import kurvcygnus.soulnotes.utils.PrintUtils;
import kurvcygnus.soulnotes.websocket.ClinicalFeedHub;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 临床评估服务: 落库 (best-effort) + 咨询员三视图查询 + 实时推送.
 *
 * @implNote recordAsync 的 NONE 跳过与失败仅 WARN 由 {@code ChatService} 挂点的 fire-and-forget
 *           订阅保证 — 对话可用性优先于评估完整性 (Spec §7).
 *           列表查询经 {@code RevealPolicy} 统一脱敏出口, REST 与 WS 共用, 杜绝旁路.
 * @since 1.2.0
 */
@ApplicationScoped
public class ClinicalAssessmentService
{
    private static final Logger LOG = LoggerFactory.getLogger(ClinicalAssessmentService.class);

    //* 业务时区统一 Asia/Shanghai (TimeUtils 同源), 按日聚合的日期边界据此划分.
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    //* 测试观测探针: 非 null 即 persist 被真实调用 (仅包内可见, 生产路径仅写不读).
    static @Nullable UUID lastPersistedId;

    //region 注入
    private final @NotNull RevealPolicy.RevealLevel revealLevel;
    private final @NotNull ClinicalFeedHub feedHub;

    public ClinicalAssessmentService(
        @ConfigProperty(name = "clinical.reveal-level", defaultValue = "RED") @NotNull String revealLevel,
        @NotNull ClinicalFeedHub feedHub
    )
    {
        this.revealLevel = RevealPolicy.RevealLevel.parse(revealLevel);
        this.feedHub = feedHub;
    }
    //endregion

    //region 落库
    /**
     * 异步落库一条评估并广播给在线咨询员.
     *
     * @param userId     被评估学生 ID
     * @param sessionId  来源会话 ID
     * @param payload    拆流结构化载荷 (含 riskLevel/tags/summary)
     * @param schemaHash 下发契约的归一化指纹 (canonical 为 null)
     * @return 完成信号; NONE 直接完成; 失败由调用方 WARN 兜底 (best-effort, 不拖垮对话)
     */
    public @NotNull Uni<Void> recordAsync(
        @NotNull UUID userId, @NotNull UUID sessionId, @NotNull JsonNode payload, @Nullable String schemaHash
    )
    {
        Objects.requireNonNull(userId, "Param \"userId\" must not be null!");
        Objects.requireNonNull(sessionId, "Param \"sessionId\" must not be null!");
        Objects.requireNonNull(payload, "Param \"payload\" must not be null!");
        final var riskLevel = payload.path("riskLevel").asText("NONE");
        //* 域校验唯一写入口: 白名单仅放行 YELLOW/RED — LLM 违约输出越域值 (如 "PURPLE") 原样落库会成
        //* 幽灵行 (工作台队列查不到), statsSummary 的 else 分支还会把它误计入 YELLOW 桶.
        if(!"YELLOW".equals(riskLevel) && !"RED".equals(riskLevel))
        {
            //* 越域值与 NONE 同短路跳过, 但必须 WARN 留痕把违约值带进日志; NONE 是正常静默路径, 不告警.
            if(!"NONE".equals(riskLevel))
                LOG.warn(PrintUtils.quickFormat("临床评估 riskLevel 越域, 已跳过落库: riskLevel={}", riskLevel));
            return Uni.createFrom().voidItem();//* 工作台是处置视图, 无风险记录不入库 (Spec §4).
        }

        final var assessment = new ClinicalAssessment();
        assessment.id = UUID.randomUUID();
        assessment.userId = userId;
        assessment.sessionId = sessionId;
        assessment.riskLevel = riskLevel;
        assessment.tags = payload.toString();
        assessment.summary = extractSummary(payload);
        assessment.schemaHash = schemaHash;
        assessment.createdAt = Instant.now();

        return Panache.withTransaction(() ->
            User.<User>findById(userId).onItem().ifNull().continueWith(() -> null).
                flatMap(user -> assessment.persist().replaceWith(user))
        ).
            invoke(user ->
            {
                lastPersistedId = assessment.id;
                broadcastVo(toVo(assessment, user == null ? null : user.username));
            }).
            replaceWithVoid();
    }

    //* canonical 摘要宽容提取: 自定义结构字段名漂移时取不到 → 空串, 不抛 (Spec §4).
    static @NotNull String extractSummary(@NotNull JsonNode payload) { return payload.path("summary").asText(""); }

    //* 落库成功后广播 VO (脱敏后); 失败仅 WARN — 推送不拖垮落库结果.
    private void broadcastVo(@NotNull AssessmentVo vo)
    {
        feedHub.broadcast(JsonUtils.toJson(Map.of("type", "NEW_ASSESSMENT", "assessment", vo))).
            subscribe().with(v -> {}, f -> LOG.warn("工作台评估推送失败: {}", f.getMessage()));
    }
    //endregion

    //region 查询
    //* 三视图显式绑定事务 (DiaryService 同款): REST 自动开 session 的装配在测试构建
    //* (quarkus.hibernate-orm.active=false) 下缺席, 服务自身保证 session 存在才可独立复用.
    /**
     * 风险队列: 按等级与时间窗倒序分页.
     *
     * @param level 等级过滤 ("YELLOW" / "RED" / null = 全部)
     * @param days  时间窗天数 (<=0 不限)
     * @param page  分页参数
     * @return 当前页评估视图
     */
    @WithTransaction
    public @NotNull Uni<List<AssessmentVo>> listAssessments(@Nullable String level, int days, @NotNull PageRequest page)
    {
        final var since = days > 0 ? Instant.now().minus(Duration.ofDays(days)) : null;
        return ClinicalAssessment.findRecent(level, since, page.getSize(), page.getOffset()).
            flatMap(this::attachIdentities);
    }

    /**
     * 学生时间线: 单学生评估倒序分页.
     */
    @WithTransaction
    public @NotNull Uni<List<AssessmentVo>> listByStudent(@NotNull UUID studentId, @NotNull PageRequest page)
    {
        return ClinicalAssessment.findByStudent(studentId, page.getSize(), page.getOffset()).
            flatMap(this::attachIdentities);
    }

    /**
     * 聚合统计: 等级分布 + 按日分组 + 去重学生数.
     *
     * @param days 时间窗天数 (<=0 不限)
     * @return 统计摘要
     */
    @WithTransaction
    public @NotNull Uni<StatsSummary> statsSummary(int days)
    {
        final var since = days > 0 ? Instant.now().minus(Duration.ofDays(days)) : null;
        return ClinicalAssessment.listSince(since).map(assessments ->
        {
            final var byLevel = assessments.stream().
                collect(Collectors.groupingBy(a -> a.riskLevel, Collectors.counting()));
            final var byDay = new LinkedHashMap<String, long[]>();
            for(final var a : assessments)
            {
                final var date = LocalDate.ofInstant(a.createdAt, ZONE).toString();
                final var counters = byDay.computeIfAbsent(date, k -> new long[2]);
                if("RED".equals(a.riskLevel)) counters[1]++;
                else counters[0]++;
            }
            final var daily = byDay.entrySet().stream().
                sorted(Map.Entry.comparingByKey()).//* listSince 为 DESC / listAll 无序, 按 DTO 契约统一升序输出.
                map(e -> new StatsSummary.DailyCount(e.getKey(), e.getValue()[0], e.getValue()[1])).
                toList();
            final var totalStudents = assessments.stream().map(a -> a.userId).distinct().count();
            return new StatsSummary(byLevel, daily, totalStudents);
        });
    }

    //* 批量身份解析: 一次 IN 查询取 username 映射, 免逐条 N+1; 已删除用户按掩码兜底.
    private @NotNull Uni<List<AssessmentVo>> attachIdentities(@NotNull List<ClinicalAssessment> assessments)
    {
        if(assessments.isEmpty())
            return Uni.createFrom().item(List.of());
        final var ids = assessments.stream().map(a -> a.userId).distinct().toList();
        return User.<User>find("id in ?1", ids).list().map(users ->
            {
                final var names = users.stream().
                    collect(Collectors.toMap(u -> u.id, u -> u.username, (a, b) -> a));
                return assessments.stream().map(a -> toVo(a, names.get(a.userId))).toList();
            }
        );
    }

    //* 单条 VO 组装: 统一经 RevealPolicy 出口 (REST 与 WS 共用, 杜绝旁路).
    private @NotNull AssessmentVo toVo(@NotNull ClinicalAssessment a, @Nullable String username)
    {
        final var identity = RevealPolicy.identity(revealLevel, a.riskLevel, a.userId, username);
        return new AssessmentVo(
            a.id, identity.userId(), identity.displayName(), a.riskLevel, a.summary,
            JsonUtils.parseJson(a.tags, JsonNode.class), a.sessionId, a.createdAt
        );
    }
    //endregion

    //region 保留期清理
    //* //! 单事务包裹 (recordAsync 同款): 本方法的唯一生产消费方 ClinicalRetentionCleaner 在无请求上下文的
    //* duplicated context 上执行 (跳转先例 ChatWebSocket#onMessage), 静态 delete 虽会在当前 context 上
    //* 惰性开会话, 但变更操作缺显式事务边界时批量删除的提交语义不可靠 — 故统一收口到 withTransaction.
    /**
     * 启动时保留期清理: 删除早于保留天数的评估.
     *
     * @param retentionDays 保留天数 (<=0 禁用)
     * @return 删除行数; 禁用时为 0
     */
    public @NotNull Uni<Long> cleanupOlderThan(int retentionDays)
    {
        if(retentionDays <= 0)
            return Uni.createFrom().item(0L);
        final var cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        return Panache.withTransaction(() -> ClinicalAssessment.deleteOlderThan(cutoff)).
            invoke(deleted -> { if(deleted > 0) LOG.info(PrintUtils.quickFormat("临床评估保留期清理完成: 删除 {} 条", deleted)); });
    }
    //endregion
}
