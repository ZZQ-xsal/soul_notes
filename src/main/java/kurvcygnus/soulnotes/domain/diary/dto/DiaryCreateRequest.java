package kurvcygnus.soulnotes.domain.diary.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * <b>创建日记请求体</b>
 * <p>支持文本和语音两种来源, 至少提供 {@code content} 或 {@code audioData} 一项.</p>
 *
 * @param content    文字内容 (可选, 与 audioData 至少提供一个)
 * @param audioData  语音数据 Base64 (可选, 与 content 至少提供一个)
 * @param sourceType 来源类型: VOICE / TEXT
 * @since 1.0
 */
public record DiaryCreateRequest(
    @Nullable String content,
    @Nullable String audioData,
    @NotNull SourceType sourceType
)
{
    /**
     * <b>日记来源类型</b>
     */
    public enum SourceType { VOICE, TEXT }
}