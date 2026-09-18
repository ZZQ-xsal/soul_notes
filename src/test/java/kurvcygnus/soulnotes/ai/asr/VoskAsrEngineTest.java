package kurvcygnus.soulnotes.ai.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link VoskAsrEngine} 行为单元测试</b>
 * <p>以可覆写的 fake {@link VoskFFM} 注入, 覆盖 transcribe 成功/失败/未就绪三态,
 * 以及 worker 池执行, 模型单例, 每请求 Recognizer 生命周期等引擎行为.</p>
 *
 * @since 1.0
 */
class VoskAsrEngineTest
{
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    //* 引擎对 final_result 返回的 JSON 只取 "text" 字段, 静音时 Vosk 给 {"text" : ""}.
    private static final String FAKE_RESULT_JSON = "{\"text\" : \"你好世界\"}";

    @BeforeAll
    @SuppressWarnings("InstantiationOfUtilityClass")//! JsonUtils 为 final 全静态成员类, IDE 误报实例化; 构造器正是 CDI 桥接注入入口.
    static void initJsonBridge()
    {
        //* 引擎解析 final_result JSON 走 JsonUtils 静态桥接, 纯单元测试需手动初始化.
        new JsonUtils(new ObjectMapper());
    }

    //region 成功路径

    @Test void name_ShouldReturnVosk()
    {
        //* 构造器不触文件系统, name() 也无 IO, 任意路径即可.
        assertEquals("vosk", engine(Path.of("unused"), null).name());
    }

    @Test void transcribe_Success_ShouldExtractTextAndDriveFfm(@TempDir Path tempDir) throws Exception
    {
        final var runtime = tempRuntimeAt(tempDir);
        final var fake = new RecordingFfm(FAKE_RESULT_JSON, true);
        final var engine = engine(runtime, fake);
        final var wav = writeWav(tempDir.resolve("audio.wav"), new byte[] {1, 0, 2, 0, 3, 0});

        final var result = engine.transcribe(wav).await().atMost(AWAIT_TIMEOUT);

        assertTrue(result.success(), "期望成功结果: " + result);
        assertEquals("你好世界", result.text());
        assertNull(result.error());
        assertEquals("vosk", engine.name());
        assertEquals(16000f, fake.recognizerSampleRate, "采样率须为 16kHz (与前端语音管道约定一致)");
        assertEquals(6, fake.acceptedBytes, "PCM16 载荷应全量喂给引擎");
        assertTrue(fake.recognizerFreed, "每请求 Recognizer 必须销毁");
    }

    @Test void transcribe_ModelIsProcessSingleton_RecognizerIsPerRequest(@TempDir Path tempDir) throws Exception
    {
        final var fake = new RecordingFfm(FAKE_RESULT_JSON, true);
        final var engine = engine(tempRuntimeAt(tempDir), fake);
        final var wav = writeWav(tempDir.resolve("audio.wav"), new byte[] {1, 0, 2, 0});

        engine.transcribe(wav).await().atMost(AWAIT_TIMEOUT);
        engine.transcribe(wav).await().atMost(AWAIT_TIMEOUT);

        assertEquals(1, fake.modelOpenCount, "模型须进程内单例 (双重检查锁)");
        assertEquals(2, fake.recognizerNewCount, "Recognizer 非线程安全, 须每请求新建");
        assertTrue(fake.recognizerFreed);
    }

    @Test void transcribe_ShouldRunOnWorkerPool(@TempDir Path tempDir) throws Exception
    {
        final var callerThread = Thread.currentThread().getName();
        final var fake = new RecordingFfm(FAKE_RESULT_JSON, true);
        final var engine = engine(tempRuntimeAt(tempDir), fake);
        final var wav = writeWav(tempDir.resolve("audio.wav"), new byte[] {1, 0});

        engine.transcribe(wav).await().atMost(AWAIT_TIMEOUT);

        assertNotEquals(callerThread, fake.modelOpenThread, "阻塞 FFM 调用不得在订阅者线程执行");
    }

    @Test void close_ShouldFreeSingletonModel(@TempDir Path tempDir) throws Exception
    {
        final var fake = new RecordingFfm(FAKE_RESULT_JSON, true);
        final var engine = engine(tempRuntimeAt(tempDir), fake);
        final var wav = writeWav(tempDir.resolve("audio.wav"), new byte[] {1, 0});

        engine.transcribe(wav).await().atMost(AWAIT_TIMEOUT);
        engine.close();

        assertTrue(fake.modelClosed, "@PreDestroy 应释放模型");
    }

    //endregion

    //region 未就绪 / 失败路径

    @Test void transcribe_NotReady_ShouldReturnErrorResult(@TempDir Path tempDir) throws Exception
    {
        final var engine = engine(tempDir, null);
        final var wav = writeWav(tempDir.resolve("audio.wav"), new byte[] {1, 0});

        final var result = engine.transcribe(wav).await().atMost(AWAIT_TIMEOUT);

        assertFalse(result.success());
        assertNull(result.text());
        assertNotNull(result.error());
        assertTrue(result.error().contains("未就绪"), "未就绪文案应可识别: " + result.error());
    }

    @Test void transcribe_FfmFailure_ShouldReturnErrorResultInsteadOfThrowing(@TempDir Path tempDir) throws Exception
    {
        final var fake = new RecordingFfm(FAKE_RESULT_JSON, true)
        {
            @SuppressWarnings("NullableProblems")//! 测试源集不引 JetBrains 注解 (compileOnly), 以抑制覆写签名告警.
            @Override public MemorySegment modelOpen(String modelDir) { throw new IllegalStateException("native boom"); }
        };
        final var engine = engine(tempRuntimeAt(tempDir), fake);
        final var wav = writeWav(tempDir.resolve("audio.wav"), new byte[] {1, 0});

        final var result = engine.transcribe(wav).await().atMost(AWAIT_TIMEOUT);

        assertFalse(result.success());
        assertNull(result.text());
        assertNotNull(result.error());
        assertTrue(result.error().contains("native boom"), "失败原因应装进 AsrResult: " + result.error());
    }

    @Test void transcribe_AcceptWaveformRejected_ShouldReturnErrorResult(@TempDir Path tempDir) throws Exception
    {
        final var fake = new RecordingFfm(FAKE_RESULT_JSON, false);
        final var engine = engine(tempRuntimeAt(tempDir), fake);
        final var wav = writeWav(tempDir.resolve("audio.wav"), new byte[] {1, 0});

        final var result = engine.transcribe(wav).await().atMost(AWAIT_TIMEOUT);

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(fake.recognizerFreed, "失败路径也必须销毁 Recognizer");
    }

    @Test void transcribe_NullFinalResult_ShouldReturnErrorResult(@TempDir Path tempDir) throws Exception
    {
        final var fake = new RecordingFfm(FAKE_RESULT_JSON, true)
        {
            @SuppressWarnings("NullableProblems")//! 刻意违反父类 @NotNull 契约返回 null, 验证引擎对越界 fake 的防御.
            @Override public String finalResult(MemorySegment recognizer) { return null; }
        };
        final var engine = engine(tempRuntimeAt(tempDir), fake);
        final var wav = writeWav(tempDir.resolve("audio.wav"), new byte[] {1, 0});

        final var result = engine.transcribe(wav).await().atMost(AWAIT_TIMEOUT);

        assertFalse(result.success());
        assertNotNull(result.error());
    }

    @Test void transcribe_CorruptWav_ShouldReturnErrorResult(@TempDir Path tempDir) throws Exception
    {
        final var engine = engine(tempRuntimeAt(tempDir), new RecordingFfm(FAKE_RESULT_JSON, true));
        final var wav = tempDir.resolve("garbage.wav");
        Files.write(wav, new byte[] {0, 1, 2, 3});

        final var result = engine.transcribe(wav).await().atMost(AWAIT_TIMEOUT);

        assertFalse(result.success());
        assertNotNull(result.error());
    }

    @Test void transcribe_MissingFile_ShouldReturnErrorResult(@TempDir Path tempDir) throws Exception
    {
        final var engine = engine(tempRuntimeAt(tempDir), new RecordingFfm(FAKE_RESULT_JSON, true));

        final var result = engine.transcribe(tempDir.resolve("nope.wav")).await().atMost(AWAIT_TIMEOUT);

        assertFalse(result.success());
        assertNotNull(result.error());
    }

    @Test void transcribe_NegativeChunkSizeWav_ShouldReturnErrorResult(@TempDir Path tempDir) throws Exception
    {
        //* 对抗性输入: chunkSize 为负时旧解析器 offset 增量为 0, worker 线程死循环, Uni 永不完成.
        final var engine = engine(tempRuntimeAt(tempDir), new RecordingFfm(FAKE_RESULT_JSON, true));
        final var wav = writeMaliciousWav(tempDir.resolve("hostile.wav"));

        final var result = engine.transcribe(wav).await().atMost(AWAIT_TIMEOUT);

        assertFalse(result.success());
        assertNull(result.text());
        assertNotNull(result.error());
    }

    @Test void readWavPcm_NegativeChunkSize_ShouldThrow()
    {
        //* 直接钉死解析器契约: 非法 chunk 长度必须快速抛 IOException, 而非死循环.
        assertThrows(IOException.class, () -> VoskAsrEngine.readWavPcm(writeMaliciousWav(tempRoot().resolve("hostile-direct.wav"))));
    }

    //* 直接解析器用例不走引擎/@TempDir, 手工建临时目录即可.
    private static Path tempRoot() throws Exception
    {
        return Files.createTempDirectory("asr-hostile");
    }

    //endregion

    //region 构造契约

    @Test void constructor_UnknownEngineName_ShouldThrowIllegalState()
    {
        assertThrows(IllegalStateException.class, () -> new VoskAsrEngine(Path.of("."), "whisper", null));
    }

    @Test void class_ShouldBeApplicationScoped()
    {
        assertTrue(VoskAsrEngine.class.isAnnotationPresent(ApplicationScoped.class));
    }

    @Test void class_ShouldBeStartupEager()
    {
        //* ArC 代理默认惰性实例化: 无 @Startup 时引擎名配置错误只会推迟到首次 transcribe 才暴露,
        //* 不满足 "SOULNOTES_ASR_ENGINE 配错 -> 启动期失败" 的契约.
        assertTrue(VoskAsrEngine.class.isAnnotationPresent(Startup.class));
    }

    //endregion

    //region 测试脚手架

    //* 就绪的最小运行时布局: lib/ 放占位文件 (引擎只探测存在性, 不真加载), model/ 下放含 am/+conf/ 标志目录的模型目录
    //* 就绪判定收编进 AsrRuntimeManager: 任一标志目录存在即视为完整模型).
    private static Path tempRuntimeAt(@TempDir Path tempDir) throws Exception
    {
        final var runtime = tempDir.resolve("runtime");
        Files.createDirectories(runtime.resolve("lib"));
        final var fakeModel = runtime.resolve("model").resolve("vosk-model-fake");
        Files.createDirectories(fakeModel.resolve("am"));
        Files.createDirectories(fakeModel.resolve("conf"));
        Files.write(runtime.resolve("lib").resolve("libvosk.dll"), new byte[] {1});
        return runtime;
    }

    private static VoskAsrEngine engine(Path runtimeDir, VoskFFM ffm)
    {
        return new VoskAsrEngine(runtimeDir, "vosk", ffm);
    }

    //* 最小 RIFF/WAVE 构造器: 引擎只依赖 RIFF 遍历取 data chunk, 头部数值不影响断言.
    private static Path writeWav(Path target, byte[] pcm) throws Exception
    {
        final var buffer = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + pcm.length);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short)1);          //* PCM
        buffer.putShort((short)1);          //* 单声道
        buffer.putInt(16000);               //* 采样率
        buffer.putInt(32000);               //* 字节率 = 16000 * 2
        buffer.putShort((short)2);          //* 块对齐
        buffer.putShort((short)16);         //* 位深
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(pcm.length);
        buffer.put(pcm);
        Files.write(target, buffer.array());
        return target;
    }

    //* 对抗性输入构造器: RIFF/WAVE 头后紧跟 chunkSize 为负的 JUNK chunk (旧解析器在此死循环).
    private static Path writeMaliciousWav(Path target) throws Exception
    {
        final var buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("JUNK".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(-9);                  //* 8 + (-9) + ((-9)&1) = 0 -> 旧解析器 offset 永不前进
        Files.write(target, buffer.array());
        return target;
    }

    /**
     * <b>可记录调用的 fake {@link VoskFFM}</b>
     * <p>借助 VoskFFM 的测试扩展构造器跳过句柄绑定, 全部实例方法用哑段替代真指针.</p>
     * <p>测试源集不引 JetBrains 注解 (compileOnly, main-only), 以类级 NullableProblems 抑制覆写签名告警.</p>
     */
    @SuppressWarnings("NullableProblems")
    private static class RecordingFfm extends VoskFFM
    {
        final Arena arena = Arena.ofAuto();
        final MemorySegment dummyModel = arena.allocate(16);
        final MemorySegment dummyRecognizer = arena.allocate(16);

        final String resultJson;
        final boolean acceptResult;

        int modelOpenCount;
        String modelOpenThread;
        String openedModelDir;
        float recognizerSampleRate = -1f;
        int recognizerNewCount;
        int acceptedBytes;
        boolean recognizerFreed;
        boolean modelClosed;

        RecordingFfm(String resultJson, boolean acceptResult)
        {
            this.resultJson = resultJson;
            this.acceptResult = acceptResult;
        }

        @Override public MemorySegment modelOpen(String modelDir)
        {
            modelOpenCount++;
            modelOpenThread = Thread.currentThread().getName();
            openedModelDir = modelDir;
            return dummyModel;
        }

        @Override public MemorySegment recognizerNew(MemorySegment model, float sampleRate)
        {
            recognizerNewCount++;
            recognizerSampleRate = sampleRate;
            return dummyRecognizer;
        }

        @Override public boolean acceptWaveform(MemorySegment recognizer, MemorySegment pcm, int length)
        {
            acceptedBytes += length;
            return acceptResult;
        }

        @Override public String finalResult(MemorySegment recognizer) { return resultJson; }

        @Override public void recognizerFree(MemorySegment recognizer) { recognizerFreed = true; }

        @Override public void modelClose(MemorySegment model) { modelClosed = true; }
    }

    //endregion
}
