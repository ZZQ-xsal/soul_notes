package kurvcygnus.soulnotes.domain.diary.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 创建日记请求体, 支持文本和语音两种来源.
 * <p>{@code content} 与 {@code audioData} 至少提供一项, 两者皆空时创建被拒绝 (BAD_REQUEST).</p>
 *
 * @param content    文字内容 (可选, 与 audioData 至少提供一个)
 * @param audioData  语音数据 Base64 (可选, 与 content 至少提供一个; 非法 Base64 时创建被拒绝)
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
     * 日记来源类型: VOICE 表示语音转写录入, TEXT 表示直接文本录入.
     */
    public enum SourceType { VOICE, TEXT }
}