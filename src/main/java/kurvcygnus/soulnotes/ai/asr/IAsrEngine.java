package kurvcygnus.soulnotes.ai.asr;

import io.smallrye.mutiny.Uni;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * <b>ASR 引擎抽象</b>
 * <p>语音 -> 文本 管道的引擎侧契约. 引擎实现必须是可插拔的:
 * 上层服务只依赖本接口, 具体引擎 (当前为 {@link VoskAsrEngine} 对应的 vosk) 由配置选择.</p>
 *
 * <p>语音先转文本再进 LLM 管道 (Voice -> Text -> LLM -> Sentiment), 本接口即 "Voice -> Text" 一环.
 * 引擎失败不抛异常, 一律装进 {@link AsrResult#error()} 走降级分支 (Offline Safety Net 红线:
 * 本地兜底热线逻辑不得依赖 ASR 成功).</p>
 * @since 1.0
 */
@SuppressWarnings("NullableProblems")//! Mock实现都位于测试模块下; 测试模块无法使用 JetBrains Annotations.
public interface IAsrEngine
{
    /**
     * <span style="color: 95cc6d">转录一个 WAV 文件.</span>
     * <p>阻塞的 native 调用须在 worker 线程池执行, 不阻塞事件循环.</p>
     *
     * @param wavFile 16kHz 单声道 PCM16 WAV 文件路径
     * @return 转录结果 (永不抛异常; 失败时 text = null 且 error 非空)
     */
    @NotNull Uni<@NotNull AsrResult> transcribe(@NotNull Path wavFile);

    /**
     * <span style="color: 95cc6d">引擎标识.</span>
     * @return 例如 "vosk"
     */
    @NotNull String name(); //* "vosk"
}
