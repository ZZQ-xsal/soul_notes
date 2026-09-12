package kurvcygnus.soulnotes.domain.voice.dto;

import kurvcygnus.soulnotes.utils.enums.VoiceStatus;
import org.jetbrains.annotations.NotNull;

/**
 * <b>语音上传成功响应</b>
 *
 * @param audioUrl 语音文件访问 URL
 * @param fileId   文件唯一标识
 * @param status   处理状态 (初始为 PENDING)
 * @since 1.0
 */
public record VoiceUploadResponse(
    @NotNull String     audioUrl,
    @NotNull String     fileId,
    @NotNull VoiceStatus status
) {}
