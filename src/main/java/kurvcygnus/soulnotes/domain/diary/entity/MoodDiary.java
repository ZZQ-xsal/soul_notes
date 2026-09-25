package kurvcygnus.soulnotes.domain.diary.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.quarkus.hibernate.reactive.panache.PanacheQuery;
import jakarta.persistence.*;
import kurvcygnus.soulnotes.config.ReactiveJsonStringJdbcType;
import org.hibernate.annotations.JdbcType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/**
 * 情绪日记实体, 对应 {@code mood_diaries} 表.
 * <p>主键为自增 BigInt (体积小, 适合日记高频写入的分页查询);
 * {@code analysisResult} 字段存储 JSONB, 包含情感分析与预警检测结果.</p>
 *
 * @since 1.0
 */
@Entity
@Table(
    name = "mood_diaries",
    indexes = @Index(name = "idx_mood_diaries_user_id", columnList = "user_id")
)
public final class MoodDiary extends PanacheEntityBase
{
    //region 字段
    //! 自增 BigInt: 体积小, 适合日记高频写入的分页查询.
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Column(columnDefinition = "TEXT")
    public String content;

    //* length 512 对齐权威 DDL (本地落盘路径含用户目录时可能超 255).
    @Column(name = "audio_url", length = 512)
    public String audioUrl;

    //* 显式字符串型 JSONB 映射: 新写入落为真 JSON (jsonb_typeof = object), 而非把 JSON 文本再包一层的字符串标量双重编码形态.
    //* 存量字符串标量行读出仍是可被 JsonUtils 解析的 JSON 文本, 迁移安全, 不做数据回填.
    @JdbcType(ReactiveJsonStringJdbcType.class)
    @Column(name = "analysis_result", columnDefinition = "JSONB")
    public String analysisResult;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    //endregion

    //region 静态查询
    /**
     * 按用户与创建时间范围构建分页查询 (创建时间倒序).
     *
     * @param userId 用户 ID
     * @param start  开始时间 (含)
     * @param end    结束时间 (含)
     * @return 分页查询对象, 由调用方继续指定 page/list 等终止操作
     */
    public static @NotNull PanacheQuery<MoodDiary> findByUserAndDateRange(
        @NotNull UUID userId,
        @NotNull Instant start,
        @NotNull Instant end
    )
    {
        return find(
            "userId = ?1 AND createdAt >= ?2 AND createdAt <= ?3 ORDER BY createdAt DESC",
            userId, start, end
        );
    }

    /**
     * 按用户与可选时间范围构建查询 (创建时间倒序).
     *
     * @param userId 用户 ID
     * @param start  开始时间 (含, 可空 = 不限起点)
     * @param end    结束时间 (含, 可空 = 不限终点)
     * @return 分页查询对象, 由调用方继续指定 page/list 等终止操作
     * @implNote 动态拼接 (ClinicalAssessment#findRecent 同款): 日记列表筛选的 startDate/endDate
     *           均为可选参数, 四种组合走单方法避免静态查询变体爆炸; 双参皆空即全量形态
     *           (原 {@code findByUserId} 已被本方法吸收, 零警告政策不留无消费方的查询变体).
     * @since 1.2.0
     */
    public static @NotNull PanacheQuery<MoodDiary> findByUserFiltered(
        @NotNull UUID userId,
        @Nullable Instant start,
        @Nullable Instant end
    )
    {
        final var query  = new StringBuilder("userId = ?1");
        final var params = new ArrayList<>();
        params.add(userId);
        if(start != null)
        {
            query.append(" AND createdAt >= ?").append(params.size() + 1);
            params.add(start);
        }
        if(end != null)
        {
            query.append(" AND createdAt <= ?").append(params.size() + 1);
            params.add(end);
        }
        query.append(" ORDER BY createdAt DESC");
        return find(query.toString(), params.toArray());
    }
    //endregion
}