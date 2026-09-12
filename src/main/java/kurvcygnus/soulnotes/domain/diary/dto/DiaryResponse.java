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
 * <b>日记响应体</b>
 * <p>包含日记全部字段, 并将 {@code analysisResult} (JSONB 字符串) 解析为结构化对象.</p>
 *
 * @param id             日记 ID
 * @param userId         用户 ID
 * @param content        文字内容
 * @param audioUrl      语音文件 URL
 * @param analysisResult 解析后的分析结果 (包含情感评分、天气类型、预警等级等)
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
     * <span style="color: 95cc6d">从 {@link MoodDiary} 实体构造响应.</span>
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

    @SuppressWarnings("unused")
    private static @Nullable OfAnalysisResult parseAnalysisResult(@Nullable String analysisResultJson)
    {
        if(analysisResultJson == null || analysisResultJson.isBlank())
            return null;
        try { return JsonUtils.parseJson(analysisResultJson, OfAnalysisResult.class); }
        catch(Exception e) { LOG.warn("解析 analysisResult JSON 失败: {}", e.getMessage()); return null; }
    }

    /**
     * <b>分析结果 DTO</b>
     * <p>对应 {@code analysisResult} JSONB 字段的结构化映射.</p>
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