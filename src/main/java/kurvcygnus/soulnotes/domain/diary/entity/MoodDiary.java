package kurvcygnus.soulnotes.domain.diary.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.quarkus.hibernate.reactive.panache.PanacheQuery;
import jakarta.persistence.*;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * <b>情绪日记实体</b>
 * <p>对应 {@code mood_diaries} 表, 使用自增 BigInt 作为主键.</p>
 * <p>{@code analysisResult} 字段存储 JSONB, 包含情感分析和预警检测结果.</p>
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

    @Column(name = "audio_url")
    public String audioUrl;

    @Column(name = "analysis_result", columnDefinition = "JSONB")
    public String analysisResult;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    //endregion

    //region 静态查询
    /**
     * <span style="color: 95cc6d">按用户与时间范围分页查询日记.</span>
     *
     * @param userId 用户 ID
     * @param start  开始时间 (含)
     * @param end    结束时间 (含)
     * @return 分页查询对象
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
     * <span style="color: 95cc6d">按用户分页查询所有日记 (倒序).</span>
     *
     * @param userId 用户 ID
     * @return 分页查询对象
     */
    public static @NotNull PanacheQuery<MoodDiary> findByUserId(@NotNull UUID userId) { return find("userId = ?1 ORDER BY createdAt DESC", userId); }
    //endregion
}