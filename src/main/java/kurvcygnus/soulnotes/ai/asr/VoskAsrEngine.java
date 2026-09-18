package kurvcygnus.soulnotes.ai.asr;

import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.utils.JsonUtils;
import kurvcygnus.soulnotes.utils.PrintUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * <b>Vosk 本地 ASR 引擎</b>
 * <p>语音 -> 文本 管道的本地实现: 经 {@link AsrRuntimeManager} 取 libvosk 动态库与模型目录,
 * 每请求新建/销毁 Recognizer 完成整段 WAV 转录.</p>
 *
 * <p>生命周期契约: 模型进程内单例 (加载昂贵, 双重检查锁), Recognizer 非线程安全故每请求新建;
 * 阻塞 FFM 调用全部经 worker 池执行; 任何失败不抛异常, 一律装进 {@link AsrResult#error()} 走降级分支
 * (Offline Safety Net: 本地兜底热线逻辑不得依赖 ASR 成功).</p>
 *
 * <p>运行时布局与就绪判定以 {@link AsrRuntimeManager} 为单一权威 (lib/ 三态平台探测 + model/
 * 含 am/ 或 conf/ 标志目录即完整), 引擎只消费其 ready/modelDir/nativeLib 视图, 不自带第二份布局逻辑;
 * 运行时缺失时由向导 (AsrRuntimeManager#ensureDownloaded) 补齐, 引擎侧仅给出未就绪文案.</p>
 *
 * <p>{@code @Startup} 急切实例化: ArC 客户端代理默认惰性创建 Bean, 若无此注解,
 * 引擎名配置错误会被推迟到首次 transcribe 才以 CreationException 暴露, 不满足
 * "SOULNOTES_ASR_ENGINE 配错 -> 启动期失败" 的快败契约.</p>
 * @since 1.0
 */
@Startup
@ApplicationScoped
public final class VoskAsrEngine implements IAsrEngine
{
    private static final Logger LOG = LoggerFactory.getLogger(VoskAsrEngine.class);

    //* 与前端语音管道约定的采样率 (16kHz 单声道 PCM16).
    private static final float SAMPLE_RATE = 16000.0f;

    //* 每次喂 8000 字节 = 0.25s (PCM16), 与 websocket 推流节奏同构.
    private static final int CHUNK_BYTES = 8000;

    private static final String ENGINE_NAME = "vosk";
    private static final String NOT_READY_MESSAGE = "ASR 运行时未就绪: 缺少本地模型或动态库";

    //region 状态

    //* 运行时布局权威: 就绪态与模型/动态库路径的唯一来源, 引擎不再自带解析.
    private final @NotNull AsrRuntimeManager runtime;
    private final @Nullable VoskFFM injectedFfm; //* 测试注入的 fake; 生产为 null, 走懒加载
    private final @NotNull Object modelLock = new Object();

    //* 双重检查锁的两侧: volatile 保证跨线程可见 (downcall 句柄与模型指针都只能运行时初始化).
    private volatile @Nullable VoskFFM activeFfm;
    private volatile @Nullable MemorySegment model; //* 进程内单例模型

    //endregion

    /**
     * <span style="color: 95cc6d">CDI 构造入口.</span>
     * @param engineName 引擎选择 (SOULNOTES_ASR_ENGINE), 非 vosk 即拒绝启动
     * @param runtime    运行时管理器 (SOULNOTES_ASR_RUNTIME_DIR / SOULNOTES_ASR_LIB_URL)
     */
    @Inject
    public VoskAsrEngine(
        @ConfigProperty(name = "asr.engine", defaultValue = "vosk") @NotNull String engineName,
        @NotNull AsrRuntimeManager runtime
    ) { this(engineName, runtime, null); }

    /**
     * <span style="color: 95cc6d">测试构造入口 (与既有引擎测试签名兼容).</span>
     * <p>以给定运行时目录内嵌一个 manager 实例, 保持测试无需感知 manager 装配.</p>
     */
    VoskAsrEngine(@NotNull Path runtimeDir, @NotNull String engineName, @Nullable VoskFFM ffm)
    {
        this(
            engineName,
            new AsrRuntimeManager(runtimeDir, AsrRuntimeManager.DEFAULT_MODEL_URL, AsrRuntimeManager.DEFAULT_LIB_JAR_URL),
            ffm
        );
    }

    private VoskAsrEngine(@NotNull String engineName, @NotNull AsrRuntimeManager runtime, @Nullable VoskFFM ffm)
    {
        Objects.requireNonNull(engineName, "Param \"engineName\" must not be null!");
        Objects.requireNonNull(runtime, "Param \"runtime\" must not be null!");
        if(!ENGINE_NAME.equals(engineName))
            throw new IllegalStateException(PrintUtils.quickFormat("不支持的 ASR 引擎: \"{}\" (当前仅支持 vosk)", engineName));
        this.runtime = runtime;
        this.injectedFfm = ffm;
    }

    //region IAsrEngine

    @Override
    public @NotNull String name() { return ENGINE_NAME; }

    @Override
    public @NotNull Uni<@NotNull AsrResult> transcribe(@NotNull Path wavFile)
    {
        Objects.requireNonNull(wavFile, "Param \"wavFile\" must not be null!");
        //* 就绪探测 (Files.list) 与文件存在性检查同属阻塞 IO, 一并放进 worker 池;
        //* 订阅者线程只做参数校验, 不做任何文件系统访问.
        return Uni.createFrom().item(
            () ->
                {
                    if(!Files.isRegularFile(wavFile))
                        return AsrResult.ofError(PrintUtils.quickFormat("音频文件不存在: {}", wavFile));
                    if(!runtime.ready())
                        return AsrResult.ofError(PrintUtils.quickFormat("{} (runtimeDir={})", NOT_READY_MESSAGE, runtime.runtimeDir()));
                    return doTranscribe(wavFile);
                }
            ).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    //endregion

    //region 生命周期

    /**
     * <span style="color: 95cc6d">释放进程内单例模型 (容器关闭时回调).</span>
     */
    @PreDestroy
    void close()
    {
        final var current = model;
        if(current == null)
            return;
        model = null;
        final var target = currentFfm();
        if(target != null)
            target.modelClose(current);
        LOG.info("Vosk 模型已释放");
    }

    //endregion

    //region 识别流程 (worker 池内执行)

    private @NotNull AsrResult doTranscribe(@NotNull Path wavFile)
    {
        try
        {
            final var ffm = ensureFfm();
            final var model = ensureModel(ffm);
            final var pcm = readWavPcm(wavFile);

            //* Arena per-call + try-with-resources: native 音频缓冲随单次识别结束即释放.
            try(var arena = Arena.ofShared())
            {
                final var recognizer = ffm.recognizerNew(model, SAMPLE_RATE);
                if(recognizer.address() == 0)
                    return AsrResult.ofError(PrintUtils.quickFormat("Vosk recognizer 创建失败 (NULL, sample_rate={})", SAMPLE_RATE));

                try
                {
                    final var pcmSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, pcm);
                    for(int offset = 0; offset < pcm.length; offset += CHUNK_BYTES)
                    {
                        final var chunkLength = Math.min(CHUNK_BYTES, pcm.length - offset);
                        if(!ffm.acceptWaveform(recognizer, pcmSegment.asSlice(offset, chunkLength), chunkLength))
                            return AsrResult.ofError(PrintUtils.quickFormat("Vosk 处理音频块失败 (offset={}, vosk_api.h 返回 -1)", offset));
                    }
                    return AsrResult.ofText(extractText(ffm.finalResult(recognizer)));
                }
                finally
                {
                    //* 失败路径同样必须销毁 Recognizer (非线程安全且持有音频缓冲).
                    ffm.recognizerFree(recognizer);
                }
            }
        }
        catch(Throwable throwable)
        {
            LOG.warn("ASR 识别失败: file={}, reason={}", wavFile, throwable.getMessage());
            return AsrResult.ofError(PrintUtils.quickFormat("ASR 识别失败: {}", throwable.getMessage()));
        }
    }

    //* final_result JSON 极简 ({\"text\": \"...\"}), 复用项目统一 JSON 入口解析, 不引正则.
    private static @NotNull String extractText(@Nullable String resultJson)
    {
        if(resultJson == null || resultJson.isBlank())
            throw new IllegalStateException("Vosk final_result 为空");
        final var payload = JsonUtils.parseJson(resultJson, new TypeReference<Map<String, String>>() {});
        //* text 缺失/null 按空文本处理: 静音是合法成功形态, 与失败走不同分支.
        return Objects.requireNonNullElse(payload.get("text"), "");
    }

    //endregion

    //region 懒加载 (双重检查锁)

    private @NotNull VoskFFM ensureFfm()
    {
        if(injectedFfm != null)
            return injectedFfm;

        final var current = activeFfm;
        if(current != null)
            return current;

        synchronized(modelLock)
        {
            if(activeFfm == null)
            {
                final var libPath = runtime.nativeLib();
                final var loaded = VoskFFM.load(libPath);
                VoskFFM.setLog(false); //* 生产默认静默 Vosk 自身日志 (best-effort)
                LOG.info("Vosk 动态库已加载: {}", libPath);
                activeFfm = loaded;
            }
            return Objects.requireNonNull(activeFfm, "activeFfm 应已初始化");
        }
    }

    private @NotNull MemorySegment ensureModel(@NotNull VoskFFM ffm)
    {
        final var current = model;
        if(current != null)
            return current;

        synchronized(modelLock)
        {
            if(model == null)
            {
                final var modelDir = runtime.modelDir();
                final var opened = ffm.modelOpen(modelDir.toString());
                if(opened.address() == 0)
                    throw new IllegalStateException(PrintUtils.quickFormat("Vosk 模型打开失败 (NULL): {}", modelDir));
                model = opened;
                LOG.info("Vosk 模型已加载 (进程内单例): {}", modelDir);
            }
            return Objects.requireNonNull(model, "model 应已初始化");
        }
    }

    //endregion

    //region 运行时布局 (委托 AsrRuntimeManager)

    //* 就绪判定与路径解析已收编进 AsrRuntimeManager (单一权威); 引擎未就绪只报错不下载,
    //* 下载安装入口由向导侧按需调用 (AsrRuntimeManager#ensureDownloaded).

    private @Nullable VoskFFM currentFfm()
    {
        if(injectedFfm != null)
            return injectedFfm;
        return activeFfm;
    }

    //endregion

    //region WAV 解析

    //* 最小 RIFF 遍历: 定位 data chunk 取原始 PCM16 载荷, 不依赖 javax.sound 的格式转换行为.
    //* 包内可见: VoskFFMTest 真机用例复用同一解析器, 避免测试副本持有第二份可死循环的解析逻辑.
    static byte[] readWavPcm(@NotNull Path wavFile) throws IOException
    {
        final var all = Files.readAllBytes(wavFile);
        if(
            all.length < 12 ||
            !"RIFF".equals(new String(all, 0, 4, StandardCharsets.US_ASCII)) ||
            !"WAVE".equals(new String(all, 8, 4, StandardCharsets.US_ASCII))
        ) throw new IOException(PrintUtils.quickFormat("非 RIFF/WAVE 文件: {}", wavFile));

        int offset = 12;
        while(offset + 8 <= all.length)
        {
            final var chunkId = new String(all, offset, 4, StandardCharsets.US_ASCII);
            final var chunkSize = (all[offset + 4] & 0xFF) |
                                  (all[offset + 5] & 0xFF) << 8 |
                                  (all[offset + 6] & 0xFF) << 16 |
                                  (all[offset + 7] & 0xFF) << 24;
            //! 对抗性输入防护: 原始 int 可为负, 负长度叠加 2 字节对齐补 1 可令 offset 增量为 0,
            //! while 永不前进 -> worker 线程死循环空转, Uni 永不完成 (transcribe 无超时), 故立即拒绝.
            if(chunkSize < 0)
                throw new IOException(PrintUtils.quickFormat("WAV chunk 长度非法 ({}): {}", chunkSize, wavFile));
            if("data".equals(chunkId))
            {
                final var payloadStart = offset + 8;
                final var payloadEnd = Math.min(payloadStart + chunkSize, all.length); //* chunkSize 已保证非负
                if(payloadEnd <= payloadStart)
                    throw new IOException(PrintUtils.quickFormat("WAV data chunk 为空: {}", wavFile));
                return Arrays.copyOfRange(all, payloadStart, payloadEnd);
            }
            offset += 8 + chunkSize + (chunkSize & 1); //* RIFF chunk 按 2 字节对齐, 奇数长度补 1.
        }
        throw new IOException(PrintUtils.quickFormat("WAV 中未找到 data chunk: {}", wavFile));
    }

    //endregion
}
