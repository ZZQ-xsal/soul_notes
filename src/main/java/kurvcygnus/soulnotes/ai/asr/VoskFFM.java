package kurvcygnus.soulnotes.ai.asr;

import kurvcygnus.soulnotes.utils.PrintUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * <b>Vosk C API FFM 直连绑定</b>
 * <p>以 JDK 25 Foreign Function &amp; Memory API 直连 libvosk, 不经 JNI/JNA (Spike 已验证 JVM 与
 * GraalVM Native Image 双侧可行, 见 docs/superpowers/notes/2026-09-14-asr-spike-notes.md).</p>
 *
 * <p>符号与签名以 alphacephei 官方 vosk_api.h 为准 (snake_case), 描述符已按其钉死:
 * 指针一律 {@code ADDRESS}, 采样率 {@code JAVA_FLOAT}, 布尔返回 {@code JAVA_INT}.</p>
 *
 * <p>downcall 句柄必须在运行时初始化 (GraalVM 硬约束): 本类通过 {@link #load(Path)} 在
 * 首次识别时才绑定句柄, 严禁类静态初始化期加载动态库.</p>
 *
 * <p>本类非 final 且提供无参测试扩展构造器: {@link VoskAsrEngine} 的单元测试以可覆写的
 * fake 子类注入, 绕过真实动态库; 无参构造器不绑定任何句柄, 生产代码一律经 {@link #load(Path)}.</p>
 * @since 1.0
 */
@SuppressWarnings("NullableProblems")//! Mock实现都位于测试模块下; 测试模块无法使用 JetBrains Annotations.
public class VoskFFM
{
    //region 句柄

    //* final_result 返回的 const char* 在 FFM 下是 0 长度段, getString 会因找不到结束符抛
    //* IndexOutOfBounds (Spike 坑清单 #1): 声明返回布局时用 64KB 序列布局开读取窗口, 覆盖 Vosk
    //* final JSON 的实际上限, getString 才有边界可依.
    private static final long RESULT_JSON_WINDOW_BYTES = 64 * 1024;

    private final @Nullable SymbolLookup lookup; //* 持有引用防止其绑定的 Arena 回收后动态库被卸载 (测试 fake 实例为 null)

    private final @NotNull MethodHandle modelNewHandle;
    private final @NotNull MethodHandle modelFreeHandle;
    private final @NotNull MethodHandle recognizerNewHandle;
    private final @NotNull MethodHandle recognizerFreeHandle;
    private final @NotNull MethodHandle acceptWaveformHandle;
    private final @NotNull MethodHandle finalResultHandle;

    //* vosk_set_log_level 是进程级全局设置: 绑定随最近一次 load() 固化在静态位, setLog 静态入口据此调用.
    private static volatile @Nullable MethodHandle setLogLevelHandle;

    //endregion

    /**
     * <span style="color: f84b4b">测试扩展构造器.</span>
     * <p>不绑定任何句柄, 仅供引擎测试的 fake 子类调用以绕开动态库; 所有实例方法必须被覆写,
     * 否则句柄字段为 null 直接 NPE.</p>
     */
    @SuppressWarnings("ConstantConditions")//! 测试扩展构造器刻意置空全部句柄: fake 子类必须覆写全部实例方法, 置空可让漏覆写处快速失败 (NPE).
    protected VoskFFM()
    {
        lookup = null;
        modelNewHandle = null;
        modelFreeHandle = null;
        recognizerNewHandle = null;
        recognizerFreeHandle = null;
        acceptWaveformHandle = null;
        finalResultHandle = null;
    }

    /**
     * <span style="color: 95cc6d">绑定动态库并创建全部 downcall 句柄.</span>
     * @param nativeLib libvosk 动态库路径 (win: libvosk.dll / linux: libvosk.so / mac: libvosk.dylib)
     */
    protected VoskFFM(@NotNull Path nativeLib)
    {
        Objects.requireNonNull(nativeLib, "Param \"nativeLib\" must not be null!");
        if(!Files.isRegularFile(nativeLib))
            throw new IllegalStateException(PrintUtils.quickFormat("Vosk 动态库不存在: {}", nativeLib));

        try
        {
            //* Arena.ofAuto() 由 GC 决定释放, 而 lookup 字段被本实例持有, 故库生命周期与实例一致.
            this.lookup = SymbolLookup.libraryLookup(nativeLib, Arena.ofAuto());
        }
        catch(IllegalArgumentException e)
        {
            //* libraryLookup 对无法加载的库抛 IAE, 包装成带路径的明确诊断.
            throw new IllegalStateException(PrintUtils.quickFormat("Vosk 动态库加载失败: {} ({})", nativeLib, e.getMessage()), e);
        }

        this.modelNewHandle = bind(lookup, "vosk_model_new", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.modelFreeHandle = bind(lookup, "vosk_model_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.recognizerNewHandle = bind(lookup, "vosk_recognizer_new", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT));
        this.recognizerFreeHandle = bind(lookup, "vosk_recognizer_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.acceptWaveformHandle = bind(lookup, "vosk_recognizer_accept_waveform", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.finalResultHandle = bind(lookup, "vosk_recognizer_final_result", FunctionDescriptor.of(ValueLayout.ADDRESS.withTargetLayout(MemoryLayout.sequenceLayout(RESULT_JSON_WINDOW_BYTES, ValueLayout.JAVA_BYTE)), ValueLayout.ADDRESS));
        setLogLevelHandle = bind(lookup, "vosk_set_log_level", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));
    }

    /**
     * <span style="color: 95cc6d">加载并绑定 libvosk.</span>
     * <p>生产入口: 句柄在调用时刻 (运行时) 绑定, 满足 GraalVM 对 downcall 的初始化时序约束.</p>
     * @param nativeLib 动态库路径
     * @return 已绑定的绑定实例
     */
    public static @NotNull VoskFFM load(@NotNull Path nativeLib)
    {
        Objects.requireNonNull(nativeLib, "Param \"nativeLib\" must not be null!");
        return new VoskFFM(nativeLib);
    }

    //region 调用

    /**
     * <span style="color: 95cc6d">打开识别模型.</span>
     * @param modelDir 含 am/ conf/ 的模型目录
     * @return VoskModel 指针段 (C API 以 NULL 表失败, 调用方须检查 {@code address() == 0})
     */
    public @NotNull MemorySegment modelOpen(@NotNull String modelDir)
    {
        Objects.requireNonNull(modelDir, "Param \"modelDir\" must not be null!");
        try(var arena = Arena.ofConfined())
        {
            final var pathSegment = arena.allocateFrom(modelDir);
            final var result = (MemorySegment) call(modelNewHandle, pathSegment);
            return Objects.requireNonNull(result, "vosk_model_new downcall 返回 null");
        }
    }

    /**
     * <span style="color: 95cc6d">释放模型.</span>
     */
    public void modelClose(@NotNull MemorySegment model)
    {
        Objects.requireNonNull(model, "Param \"model\" must not be null!");
        call(modelFreeHandle, model);
    }

    /**
     * <span style="color: 95cc6d">创建识别器.</span>
     * @param model      已打开的模型
     * @param sampleRate 采样率 (vosk_api.h 为 float, 与前端语音管道约定 16000f)
     * @return VoskRecognizer 指针段 (调用方须检查 NULL)
     */
    public @NotNull MemorySegment recognizerNew(@NotNull MemorySegment model, float sampleRate)
    {
        Objects.requireNonNull(model, "Param \"model\" must not be null!");
        final var result = (MemorySegment) call(recognizerNewHandle, model, sampleRate);
        return Objects.requireNonNull(result, "vosk_recognizer_new downcall 返回 null");
    }

    /**
     * <span style="color: 95cc6d">销毁识别器.</span>
     * <p>Recognizer 非线程安全且持有音频缓冲, 识别结束 (含失败路径) 必须销毁.</p>
     */
    public void recognizerFree(@NotNull MemorySegment recognizer)
    {
        Objects.requireNonNull(recognizer, "Param \"recognizer\" must not be null!");
        call(recognizerFreeHandle, recognizer);
    }

    /**
     * <span style="color: 95cc6d">喂入一帧 PCM16 音频.</span>
     * @param recognizer 识别器
     * @param pcm         PCM16 载荷片段 (native 或堆上段均可)
     * @param length      字节长度
     * @return true = 已接收; false = Vosk 内部异常
     */
    public boolean acceptWaveform(@NotNull MemorySegment recognizer, @NotNull MemorySegment pcm, int length)
    {
        Objects.requireNonNull(recognizer, "Param \"recognizer\" must not be null!");
        Objects.requireNonNull(pcm, "Param \"pcm\" must not be null!");
        //* vosk_api.h: 0 = 解码继续, 1 = 遇静音边界 (可取话语结果), -1 = Vosk 内部异常.
        //* 故仅 -1 视为失败; 1 是合法返回, 若按 "非 0 即失败" 处理会误杀静音段.
        final var accepted = (Integer) call(acceptWaveformHandle, recognizer, pcm, length);
        return Objects.requireNonNull(accepted, "accept_waveform downcall 返回 null") >= 0;
    }

    /**
     * <span style="color: 95cc6d">取最终识别结果 JSON (形如 {@code {"text" : "..."}}).</span>
     * <p>返回串所有权仍属 recognizer: 本方法立即拷贝为 Java String, 调用方随后可安全释放识别器.</p>
     * @return 结果 JSON 字符串 (永不为 null; Vosk 返回 NULL 时抛 IllegalStateException)
     */
    public @NotNull String finalResult(@NotNull MemorySegment recognizer)
    {
        Objects.requireNonNull(recognizer, "Param \"recognizer\" must not be null!");
        final var resultPtr = (MemorySegment) call(finalResultHandle, recognizer);
        if(resultPtr == null || resultPtr.address() == 0)
            throw new IllegalStateException("vosk_recognizer_final_result 返回 NULL");
        return resultPtr.getString(0, StandardCharsets.UTF_8);
    }

    /**
     * <span style="color: 95cc6d">开关 Vosk 自身日志.</span>
     * <p>best-effort: 未 load 前调用为安全 no-op, 不使未加载环境 (如单元测试) 抛错.
     * 另注意 Kaldi 层 LOG 走自有通道直写 stderr, 本开关压不掉模型加载期的日志 (Spike 坑清单 #3).</p>
     * @param on true = 开启 Vosk 日志
     */
    public static void setLog(boolean on)
    {
        final var handle = setLogLevelHandle;
        if(handle == null)
            return;
        call(handle, on ? 1 : 0);
    }

    //endregion

    //region 内部

    /**
     * <span style="color: 95cc6d">按符号名绑定 downcall 句柄.</span>
     * <p>找不到符号立即抛 UnsatisfiedLinkError 并指名, 便于区分 "库未加载" 与 "符号缺失".</p>
     */
    private static @NotNull MethodHandle bind(@NotNull SymbolLookup lookup, @NotNull String symbol, @NotNull FunctionDescriptor descriptor)
    {
        final var address = lookup.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError(PrintUtils.quickFormat("libvosk 缺少符号: {}", symbol)));
        return Linker.nativeLinker().downcallHandle(address, descriptor);
    }

    //* invokeWithArguments 装箱调用的性能损耗对 0.25s 一喂的节奏可忽略, 换取无签名多态陷阱的简洁性.
    private static @Nullable Object call(@NotNull MethodHandle handle, @Nullable Object... args)
    {
        try { return handle.invokeWithArguments(args); }
        catch(Throwable throwable) { throw new IllegalStateException(PrintUtils.quickFormat("Vosk downcall 调用失败: {}", throwable.getMessage()), throwable); }
    }

    //endregion
}
