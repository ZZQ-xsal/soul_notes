package kurvcygnus.soulnotes.domain.clinical.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import kurvcygnus.soulnotes.config.ReactiveJsonStringJdbcType;
import org.hibernate.annotations.JdbcType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 临床结构化评估实体 ("副医生"产出), 对应 {@code clinical_assessments} 表.
 * <p>咨询员工作台的唯一数据源: 风险队列 / 学生时间线 / 聚合统计 / WS 实时推送全部读取本表.</p>
 *
 * @implNote {@code tags} 以 JSONB 存拆流载荷原文 (机构自定义结构字段集可能漂移, 原文保真);
 *           {@code summary} 为 canonical 冗余列, 列表页免 JSONB 提取.
 *           {@code schemaHash} 为归一化指纹 (canonical 结构为 null), 前端渲染语义锚点.
 *           {@code riskLevel} 仅 YELLOW / RED (NONE 在落库入口即被过滤, 表中不存在).
 * @since 1.2.0
 */
@Entity
@Table(
    name = "clinical_assessments",
    indexes = {
        @Index(name = "idx_assess_level_created", columnList = "risk_level,created_at"),
        @Index(name = "idx_assess_user_created", columnList = "user_id,created_at")
    }
)
public final class ClinicalAssessment extends PanacheEntityBase
{
    //region 字段
    @Id
    public UUID id;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Column(name = "session_id", nullable = false)
    public UUID sessionId;

    //* 值域 YELLOW / RED (NONE 不落库): 工作台是处置视图, 无风险记录不入库.
    @Column(name = "risk_level", nullable = false, length = 16)
    public String riskLevel;

    //* 显式字符串型 JSONB 映射 (AiChatSession.messages 同款): 载荷原文保真.
    @JdbcType(ReactiveJsonStringJdbcType.class)
    @Column(nullable = false, columnDefinition = "JSONB")
    public String tags;

    @Column(nullable = false)
    public String summary;

    @Column(name = "schema_hash", length = 64)
    public String schemaHash;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    //endregion

    //region 静态查询
    /**
     * 风险队列查询: 按等级 (可空 = 全部) 与时间窗倒序分页.
     *
     * @param level  等级过滤 ("YELLOW" / "RED" / null = 全部)
     * @param since  时间窗起点 (null = 不限)
     * @param limit  每页条数 (>=1)
     * @param offset 偏移量 (>=0)
     * @return 评估列表 (可能为空, 恒非 null)
     */
    public static @NotNull Uni<List<ClinicalAssessment>> findRecent(
        @Nullable String level, @Nullable Instant since, int limit, int offset
    )
    {
        final var query = new StringBuilder("1 = 1");
        final var params = new java.util.ArrayList<Object>();
        if(level != null)
        {
            query.append(" AND riskLevel = ?").append(params.size() + 1);
            params.add(level);
        }
        if(since != null)
        {
            query.append(" AND createdAt >= ?").append(params.size() + 1);
            params.add(since);
        }
        query.append(" ORDER BY createdAt DESC");
        //* Page.of 首参为 index 次参为 size (io.quarkus.panache.common.Page 契约), 倒序会致 page=1 恒抛 "size must be > 0".
        return find(query.toString(), params.toArray()).page(io.quarkus.panache.common.Page.of(offset / limit, limit)).list();
    }

    /**
     * 风险队列计数 (与 {@link #findRecent} 同过滤条件, 供分页 total).
     */
    public static @NotNull Uni<Long> countRecent(@Nullable String level, @Nullable Instant since)
    {
        final var query = new StringBuilder("1 = 1");
        final var params = new java.util.ArrayList<Object>();
        if(level != null)
        {
            query.append(" AND riskLevel = ?").append(params.size() + 1);
            params.add(level);
        }
        if(since != null)
        {
            query.append(" AND createdAt >= ?").append(params.size() + 1);
            params.add(since);
        }
        return count(query.toString(), params.toArray());
    }

    /**
     * 学生时间线: 单学生评估倒序分页.
     */
    public static @NotNull Uni<List<ClinicalAssessment>> findByStudent(@NotNull UUID userId, int limit, int offset)
    {
        return find("userId = ?1 ORDER BY createdAt DESC", userId).
            page(io.quarkus.panache.common.Page.of(offset / limit, limit)).list();
    }

    /**
     * 学生时间线计数.
     */
    public static @NotNull Uni<Long> countByStudent(@NotNull UUID userId) { return count("userId = ?1", userId); }

    /**
     * 保留期清理: 删除早于截止时刻的全部评估.
     *
     * @return 删除行数
     */
    public static @NotNull Uni<Long> deleteOlderThan(@NotNull Instant cutoff) { return delete("createdAt < ?1", cutoff); }

    /**
     * 拉取时间窗内全部评估 (统计聚合的数据源: YELLOW/RED 量级小, Java 侧聚合免 HQL 投影复杂性).
     */
    public static @NotNull Uni<List<ClinicalAssessment>> listSince(@Nullable Instant since)
    {
        return since == null ? listAll() : list("createdAt >= ?1 ORDER BY createdAt DESC", since);
    }
    //endregion
}
