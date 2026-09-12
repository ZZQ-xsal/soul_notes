package kurvcygnus.soulnotes.domain.voice.dto;

import kurvcygnus.soulnotes.utils.enums.VoiceStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * <b>ASR 服务回调请求体</b>
 *
 * @param fileId          文件唯一标识
 * @param transcribedText 转录文本 (失败时为 null)
 * @param status          处理状态 (PROCESSED / FAILED)
 * @since 1.0
 */
public record AsrCallbackRequest(
    @NotNull String       fileId,
    @Nullable String      transcribedText,
    @NotNull VoiceStatus  status
) {}
