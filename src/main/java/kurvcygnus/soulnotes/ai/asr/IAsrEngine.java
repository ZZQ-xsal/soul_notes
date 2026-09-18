package kurvcygnus.soulnotes.ai.asr;

import io.smallrye.mutiny.Uni;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * ASR 引擎抽象.
 * <p>语音 -> 文本 管道的引擎侧契约. 引擎实现必须是可插拔的:
 * 上层服务只依赖本接口, 具体引擎 (当前为 {@link VoskAsrEngine} 对应的 vosk) 由配置选择.</p>
 *
 * <p>语音先转文本再进 LLM 管道 (Voice -> Text -> LLM -> Sentiment), 本接口即 "Voice -> Text" 一环.
 * 引擎失败不抛异常, 一律装进 {@link AsrResult#error()} 走降级分支 (Offline Safety Net 红线:
 * 本地兜底热线逻辑不得依赖 ASR 成功).</p>
 * @since 1.1.0
 */
@SuppressWarnings("NullableProblems")//! Mock实现都位于测试模块下; 测试模块无法使用 JetBrains Annotations.
public interface IAsrEngine
{
    /**
     * 将 16kHz 单声道 PCM16 WAV 文件转录为文本.
     *
     * <p>实现自行调度到工作线程执行阻塞转录, 不阻塞事件循环; 任何失败形态 (文件损坏、引擎未就绪、转录中断)
     * 都不抛出异常, 统一以 {@link AsrResult#error} 非空返回, 调用方据此降级而非走异常路径.</p>
     *
     * @param wavFile 待转录文件, 须为 RIFF/WAVE 容器封装的 16kHz 单声道 PCM16 数据
     * @return 成功时 {@code text} 为识别文本 (静音可为空串); 失败时 {@code error} 为人类可读原因
     * @implNote 引擎应复用进程级共享模型、按请求创建识别器 — Vosk 识别器非线程安全,
     *           而模型加载开销大且可跨线程共享.
     */
    @NotNull Uni<@NotNull AsrResult> transcribe(@NotNull Path wavFile);

    /**
     * 引擎标识.
     *
     * @return 稳定的引擎名 (如 "vosk"), 用于日志与运行时目录定位
     */
    @NotNull String name(); //* "vosk"
}
