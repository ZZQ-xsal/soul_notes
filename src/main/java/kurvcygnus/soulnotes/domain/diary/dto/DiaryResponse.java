package kurvcygnus.soulnotes.domain.diary.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import kurvcygnus.soulnotes.domain.diary.entity.MoodDiary;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

/**
 * 日记响应体, 包含日记全部字段.
 * <p>{@code analysisResult} (JSONB 字符串) 在此解析为结构化对象 {@link OfAnalysisResult};
 * JSON 为空或解析失败时该字段为 {@code null} (降级不抛错, 日记本体仍可展示).</p>
 *
 * @param id             日记 ID
 * @param userId         所属用户 ID
 * @param content        文字内容 (纯语音日记为 {@code null})
 * @param audioUrl       语音文件 URL (纯文本日记为 {@code null})
 * @param analysisResult 解析后的分析结果 (情感评分/天气类型/预警等级等); 无分析或解析失败时为 {@code null}
 * @param createdAt      创建时间
 * @since 1.0
 */
public record DiaryResponse(
    @NotNull Long              id,
    @NotNull UUID              userId,
    @Nullable String           content,
    @Nullable String           audioUrl,
    @Nullable OfAnalysisResult analysisResult,
    @NotNull Instant           createdAt
)
{
    /**
     * 从 {@link MoodDiary} 实体构造响应, 附带 analysisResult JSON 的容错解析.
     *
     * @param diary 已持久化的日记实体
     * @return 面向前端的日记响应
     */
    public static @NotNull DiaryResponse fromEntity(@NotNull MoodDiary diary)
    {
        return new DiaryResponse(
            diary.id,
            diary.userId,
            diary.content,
            diary.audioUrl,
            parseAnalysisResult(diary.analysisResult),
            diary.createdAt
        );
    }

    private static final Logger LOG = LoggerFactory.getLogger(DiaryResponse.class);

    private static @Nullable OfAnalysisResult parseAnalysisResult(@Nullable String analysisResultJson)
    {
        if(analysisResultJson == null || analysisResultJson.isBlank())
            return null;
        try { return JsonUtils.parseJson(analysisResultJson, OfAnalysisResult.class); }
        catch(Exception e) { LOG.warn("解析 analysisResult JSON 失败: {}", e.getMessage()); return null; }
    }

    /**
     * 情感分析结果 DTO, 对应 {@code analysisResult} JSONB 字段的结构化映射.
     *
     * @param positive     正向情感评分 (0.0 ~ 1.0)
     * @param negative     负向情感评分 (0.0 ~ 1.0)
     * @param anxiety      焦虑程度评分 (0.0 ~ 1.0)
     * @param weather      情绪天气类型 (SUNNY / CLOUDY / OVERCAST / RAINY / THUNDERSTORM)
     * @param warningLevel 预警等级 (GREEN / YELLOW / RED)
     * @param summary      分析摘要 (可能为 {@code null})
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @RegisterForReflection//! native 下 Jackson 反序列化该 record 需要反射注册 (JsonUtils 手动 mapper 路径).
    public record OfAnalysisResult(
        double positive,
        double negative,
        double anxiety,
        @NotNull String weather,
        @NotNull String warningLevel,
        @Nullable String summary
    ) {}
}