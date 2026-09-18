package kurvcygnus.soulnotes.ai.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link VoskFFM} 绑定测试</b>
 * <p>纯 JVM 用例 (库缺失行为) 全环境执行; 真机用例依赖 spike/ 下的 libvosk + 模型 + 静音 WAV,
 * 以 {@code assumeTrue} 保护, CI 等无库环境自动跳过.</p>
 *
 * @since 1.0
 */
class VoskFFMTest
{
    //* Spike 遗留资产 (gitignore, 仅本机存在): 与 docs/superpowers/notes/2026-09-14-asr-spike-notes.md 一致.
    private static final Path SPIKE_DIR = Path.of("spike");
    private static final Path SPIKE_LIB = SPIKE_DIR.resolve("lib").resolve("libvosk.dll");
    private static final Path SPIKE_MODEL_DIR = SPIKE_DIR.resolve("model").resolve("vosk-model-small-cn-0.22");
    private static final Path SPIKE_WAV = SPIKE_DIR.resolve("test_16k_mono.wav");

    //* 与 Spike 同参: 16kHz, 8000 字节一喂 (0.25s PCM16).
    private static final float SAMPLE_RATE = 16000.0f;
    private static final int CHUNK_BYTES = 8000;

    @BeforeAll
    @SuppressWarnings("InstantiationOfUtilityClass")//! JsonUtils 为 final 全静态成员类, IDE 误报实例化; 构造器正是 CDI 桥接注入入口.
    static void initJsonBridge()
    {
        new JsonUtils(new ObjectMapper());
    }

    //region 纯 JVM 用例 (全环境执行)

    @Test void load_MissingLib_ShouldThrow()
    {
        assertThrows(IllegalStateException.class, () -> VoskFFM.load(Path.of("definitely/not/exist/libvosk.so")));
    }

    @Test void setLog_BeforeLoad_ShouldNotThrow()
    {
        //* 未 load 前调用 setLog 是安全的 best-effort no-op: 引擎装配顺序不应被日志开关绑架.
        assertDoesNotThrow(() -> VoskFFM.setLog(false));
    }

    //endregion

    //region 真机用例 (有 lib 才执行)

    @Test void realLib_FullPipeline_ShouldReturnResultJson() throws Exception
    {
        assumeAssetsExist();
        final var ffm = VoskFFM.load(SPIKE_LIB);
        VoskFFM.setLog(false);

        final var model = ffm.modelOpen(SPIKE_MODEL_DIR.toString());
        assertNotEquals(0, model.address(), "vosk_model_new 返回 NULL");

        final var recognizer = ffm.recognizerNew(model, SAMPLE_RATE);
        assertNotEquals(0, recognizer.address(), "vosk_recognizer_new 返回 NULL");
        try
        {
            final var pcm = VoskAsrEngine.readWavPcm(SPIKE_WAV);
            try(var arena = Arena.ofConfined())
            {
                final var pcmSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, pcm);
                for(int offset = 0; offset < pcm.length; offset += CHUNK_BYTES)
                {
                    final var chunkLength = Math.min(CHUNK_BYTES, pcm.length - offset);
                    assertTrue(ffm.acceptWaveform(recognizer, pcmSegment.asSlice(offset, chunkLength), chunkLength),
                        "accept_waveform 在 offset=" + offset + " 处报异常 (vosk_api.h: -1)");
                }
            }

            final var resultJson = ffm.finalResult(recognizer);
            assertFalse(resultJson.isBlank(), "final_result 不应为空");
            assertTrue(resultJson.contains("text"), "final_result 应为含 text 字段的 JSON: " + resultJson);

            //* 静音 WAV 的预期成功形态: JSON 可解析且 text 字段存在 (空串亦合法).
            final var payload = JsonUtils.parseJson(resultJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() { });
            assertTrue(payload.containsKey("text"), "结果 JSON 缺少 text 字段: " + resultJson);
        }
        finally
        {
            ffm.recognizerFree(recognizer);
            ffm.modelClose(model);
        }
    }

    //endregion

    //region 脚手架

    private static void assumeAssetsExist()
    {
        Assumptions.assumeTrue(Files.isRegularFile(SPIKE_LIB), "spike lib 不存在, 跳过真机用例");
        Assumptions.assumeTrue(Files.isDirectory(SPIKE_MODEL_DIR), "spike model 不存在, 跳过真机用例");
        Assumptions.assumeTrue(Files.isRegularFile(SPIKE_WAV), "spike wav 不存在, 跳过真机用例");
    }

    //endregion
}
