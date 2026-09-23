package kurvcygnus.soulnotes.domain.voice;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import kurvcygnus.soulnotes.support.FixedAsrEngine;
import kurvcygnus.soulnotes.support.InfraProbes;
import kurvcygnus.soulnotes.support.MockLlmProfile;
import kurvcygnus.soulnotes.support.PipelineUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * <b>语音上传 HTTP 全链路测试</b>
 * <p>ASR 引擎由 {@link FixedAsrEngine} 替身 (MockLlmProfile 启用的 CDI Alternative) 承担,
 * 走真实 HTTP multipart 面: 认证 → multipart 解析 → 文件落盘 → 同步转录 → 响应携带 transcribedText.
 * 转录五路径 (成功/静音/引擎失败/非 WAV/超限) 已由 {@code VoiceResourceTest} 在资源层覆盖, 此处不重复.</p>
 * <p>基建守卫: 强依赖本机 postgres + redis (CI 由 service 容器提供), 缺席时应用启动即失败,
 * {@code assumeTrue} 来不及救 — 以 {@code @EnabledIf} 在 JUnit 执行条件层整类跳过 (本地开发者双保险).</p>
 * @since 1.1.0
 */
@QuarkusTest
@TestProfile(MockLlmProfile.class)
@EnabledIf(value = "pipelineInfraReachable", disabledReason = "本机 postgres/redis 未运行, 跳过语音上传全链路用例")
class VoiceUploadPipelineTest
{
    //* @EnabledIf 的引用方法必须落在被注解类内: QuarkusTest 类加载器下跨类全限定字符串解析失败 (实测),
    //* 故以同名静态方法委托公共探测 [[InfraProbes#pipelineInfraReachable]], 判定逻辑单一来源不变.
    static boolean pipelineInfraReachable() { return InfraProbes.pipelineInfraReachable(); }

    @Test
    void voiceUpload_HttpMultipart_ShouldTranscribeAndReturnTextInResponse()
    {
        final var account = PipelineUsers.register();

        RestAssured.
            given().
            header("Authorization", PipelineUsers.bearer(account.token())).
            multiPart("file", "audio.wav", wavBytes(), "audio/wav").
            when().
            post("/api/v1/voice/upload").
            then().
            statusCode(200).
            body("code", equalTo(0)).
            body("data.status", equalTo("TRANSCRIBED")).
            body("data.transcribedText", equalTo(FixedAsrEngine.FIXED_TEXT)).
            body("data.message", nullValue());
    }

    //region 测试脚手架
    //* 最小 RIFF/WAVE 构造器: 资源层只校验前 12 字节头 (与 VoiceResourceTest 同源形状).
    private static byte[] wavBytes()
    {
        final byte[] pcm = {1, 0, 2, 0};
        final var buffer = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + pcm.length);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);          //* PCM
        buffer.putShort((short) 1);          //* 单声道
        buffer.putInt(16000);                //* 采样率
        buffer.putInt(32000);                //* 字节率
        buffer.putShort((short) 2);          //* 块对齐
        buffer.putShort((short) 16);         //* 位深
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(pcm.length);
        buffer.put(pcm);
        return buffer.array();
    }
    //endregion
}
