package kurvcygnus.soulnotes.domain.voice.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.utils.enums.VoiceStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>ASR 转录服务</b>
 * <ul>
 *     <li>将语音文件发送至外部 ASR 服务 (桩实现, 目前仅记录日志)</li>
 *     <li>处理 ASR 回调结果</li>
 * </ul>
 * @since 1.0
 */
@ApplicationScoped
public final class AsrTranscriptionService
{
    private static final Logger LOG = LoggerFactory.getLogger(AsrTranscriptionService.class);

    /**
     * <span style="color: 95cc6d">提交 ASR 转录任务.</span>
     * <p>当前为桩实现, 仅记录日志. 后续接入外部 ASR 服务 (如 Whisper API) 时扩展.</p>
     *
     * @param fileId   文件唯一标识
     * @param audioUrl 语音文件访问 URL
     * @return Uni<Void>
     */
    public @NotNull Uni<Void> dispatchTranscription(@NotNull String fileId, @NotNull String audioUrl)
    {
        LOG.info("ASR 转录任务已提交: fileId={}, audioUrl={} (当前为桩实现, 待接入外部 ASR 服务)", fileId, audioUrl);
        return Uni.createFrom().voidItem();
    }

    /**
     * <span style="color: 95cc6d">处理 ASR 回调结果.</span>
     *
     * @param fileId          文件唯一标识
     * @param transcribedText 转录文本 (失败时为 null)
     * @param status          处理状态
     * @return Uni<Void>
     */
    public @NotNull Uni<Void> handleResult(
        @NotNull String fileId,
        @Nullable String transcribedText,
        @NotNull VoiceStatus status
    )
    {
        if(status == VoiceStatus.PROCESSED && transcribedText != null && !transcribedText.isBlank())
        {
            LOG.info("ASR 转录成功: fileId={}, 文本长度={}", fileId, transcribedText.length());
            //? 后续可在此自动创建 Diary 条目.
        }
        else
        {
            LOG.warn("ASR 转录失败: fileId={}, status={}", fileId, status);
        }
        return Uni.createFrom().voidItem();
    }
}
