package kurvcygnus.soulnotes.domain.voice.dto;

import kurvcygnus.soulnotes.utils.enums.VoiceStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 语音上传响应, 同步转录契约的载体.
 * <p>转录失败不回 5xx, 而是 status=FAILED + message 透传原因 (离线安全网:
 * 前端凭 message 展示降级文案并回落文字链路, 语音失败不影响应急热线兜底).</p>
 *
 * @param audioUrl        语音文件访问 URL
 * @param fileId          文件唯一标识
 * @param status          转录状态 (TRANSCRIBED / FAILED)
 * @param transcribedText 转录文本 (失败时为 null; 静音时为空串); 1.1.0 新增字段
 * @param message         失败原因 (成功时为 null); 1.1.0 新增字段
 * @since 1.0
 */
public record VoiceUploadResponse(
    @NotNull String        audioUrl,
    @NotNull String        fileId,
    @NotNull VoiceStatus   status,
    @Nullable String       transcribedText,
    @Nullable String       message
)
{}
