package kurvcygnus.soulnotes.domain.voice.resource;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.MultivaluedHashMap;
import kurvcygnus.soulnotes.ai.asr.AsrResult;
import kurvcygnus.soulnotes.ai.asr.IAsrEngine;
import kurvcygnus.soulnotes.domain.voice.service.VoiceStorageService;
import kurvcygnus.soulnotes.exception.ErrorCode;
import kurvcygnus.soulnotes.exception.IBusinessException;
import kurvcygnus.soulnotes.exception.StructuredException;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import kurvcygnus.soulnotes.utils.enums.VoiceStatus;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link VoiceResource} 行为单元测试</b>
 * <p>以 fake {@link IAsrEngine} + 真实 {@link VoiceStorageService} (@TempDir 落盘) 驱动上传链路:
 * 同步转录成功/静音/引擎失败/非 WAV/超限五路径, 以及外部回调架构拆除的结构钉死.</p>
 *
 * @since 1.0
 */
class VoiceResourceTest
{
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    //region 结构契约

    @Test void class_ShouldBeFinal()
    {
        assertTrue(Modifier.isFinal(VoiceResource.class.getModifiers()));
    }

    @Test void class_ShouldHavePathAnnotation()
    {
        final var path = VoiceResource.class.getAnnotation(jakarta.ws.rs.Path.class);
        assertNotNull(path);
        assertEquals(ApiEndpointConstants.VOICE_BASE, path.value());
    }

    @Test void class_ShouldHaveRolesAllowed()
    {
        assertTrue(VoiceResource.class.isAnnotationPresent(RolesAllowed.class));
    }

    //* 裁定 #3: 同步本地转录落地后, 外部 ASR 回调端点必须整体消失 (一个都不留).
    @Test void asrCallbackEndpoint_ShouldBeRemoved()
    {
        assertTrue(
            Arrays.stream(VoiceResource.class.getDeclaredMethods()).
                noneMatch(m -> m.getName().toLowerCase().contains("callback")),
            "外部 ASR 回调架构已拆除, 不得残留回调端点"
        );
    }

    //endregion

    //region 上传同步转录

    @Test void upload_WavFile_ShouldTranscribeSynchronouslyAndReturnTranscribed(
        @TempDir Path storageDir, @TempDir Path uploadDir) throws Exception
    {
        final var engine = new FakeEngine(AsrResult.ofText("你好世界"));
        final var resource = resource(storageDir, engine, 10485760L);
        final var upload = fakeUpload(uploadDir, "audio.wav", wavBytes());

        final var resp = resource.upload(upload).await().atMost(AWAIT_TIMEOUT);

        assertEquals(0, resp.code, "上传请求本身应成功 (HTTP 200 语义)");
        final var data = resp.data;
        assertNotNull(data);
        assertEquals(VoiceStatus.TRANSCRIBED, data.status());
        assertEquals("你好世界", data.transcribedText());
        assertNull(data.message());
        assertNotNull(data.fileId());
        assertEquals("/api/v1/voice/files/" + data.fileId(), data.audioUrl());
        //* 转录必须针对已存储文件而非 resteasy 临时文件 (临时文件在请求结束后被清理, 不可作为转录源).
        assertEquals(1, engine.calls().size(), "引擎应被同步调用且仅一次");
        final var transcribed = engine.calls().getFirst();
        assertTrue(Files.exists(transcribed), "转录目标应为已落盘的存储文件");
        assertNotEquals(upload.filePath(), transcribed, "不得转录 resteasy 临时上传文件");
        assertTrue(transcribed.toString().contains(data.fileId()), "转录路径应位于该 fileId 的存储目录内");
    }

    //* 静音是合法成功形态 (AsrResult 契约): status=TRANSCRIBED + 空文本, 与失败降级路径区分.
    @Test void upload_SilentWav_ShouldReturnTranscribedWithEmptyText(
        @TempDir Path storageDir, @TempDir Path uploadDir) throws Exception
    {
        final var resource = resource(storageDir, new FakeEngine(AsrResult.ofText("")), 10485760L);
        final var upload = fakeUpload(uploadDir, "silence.wav", wavBytes());

        final var resp = resource.upload(upload).await().atMost(AWAIT_TIMEOUT);

        assertNotNull(resp.data);
        assertEquals(VoiceStatus.TRANSCRIBED, resp.data.status());
        assertEquals("", resp.data.transcribedText());
        assertNull(resp.data.message());
    }

    //* 离线安全网语义: 转录失败不回 500, 失败原因装进 data.status=FAILED + data.message, 文字链路不因语音失败而崩溃.
    @Test void upload_EngineFailure_ShouldReturnFailedWithMessageInsteadOf500(
        @TempDir Path storageDir, @TempDir Path uploadDir) throws Exception
    {
        final var resource = resource(storageDir, new FakeEngine(AsrResult.ofError("ASR 运行时未就绪: 缺少本地模型或动态库")), 10485760L);
        final var upload = fakeUpload(uploadDir, "audio.wav", wavBytes());

        final var resp = resource.upload(upload).await().atMost(AWAIT_TIMEOUT);

        assertEquals(0, resp.code, "转录失败不得转化为 5xx 异常响应");
        assertNotNull(resp.data);
        assertEquals(VoiceStatus.FAILED, resp.data.status());
        assertNull(resp.data.transcribedText());
        assertNotNull(resp.data.message());
        assertTrue(resp.data.message().contains("未就绪"), "失败原因应透传给前端: " + resp.data.message());
    }

    //* 非 WAV (RIFF/WAVE 头校验失败) 必须在落盘前拒绝: 400 + VOICE_FORMAT_UNSUPPORTED, 存储目录保持干净.
    @Test void upload_NonWav_ShouldRejectWithFormatUnsupportedBeforeStoring(
        @TempDir Path storageDir, @TempDir Path uploadDir) throws Exception
    {
        final var engine = new FakeEngine(AsrResult.ofText("不应被调用"));
        final var resource = resource(storageDir, engine, 10485760L);
        final var upload = fakeUpload(uploadDir, "fake.wav", "这不是音频, 只是文本".getBytes(StandardCharsets.UTF_8));

        final var e = assertBusiness(() -> resource.upload(upload).await().atMost(AWAIT_TIMEOUT));

        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        assertEquals("VOICE_FORMAT_UNSUPPORTED", e.asException().tag());
        assertTrue(e.asException().getMessage().contains("PCM16"), "错误消息应注明需 PCM16 16k WAV: " + e.asException().getMessage());
        assertEquals(0, engine.calls().size(), "校验失败时引擎不得被调用");
        try(var files = Files.list(storageDir))
        {
            assertEquals(0, files.count(), "非 WAV 文件不得落盘");
        }
    }

    @Test void upload_Oversize_ShouldRejectWithSizeExceeded(
        @TempDir Path storageDir, @TempDir Path uploadDir) throws Exception
    {
        final var resource = resource(storageDir, new FakeEngine(AsrResult.ofText("不应被调用")), 8L);
        final var upload = fakeUpload(uploadDir, "big.wav", wavBytes());

        final var e = assertBusiness(() -> resource.upload(upload).await().atMost(AWAIT_TIMEOUT));

        assertEquals("VOICE_UPLOAD_SIZE_EXCEEDED", e.asException().tag());
    }

    //endregion

    //region 测试脚手架

    private static VoiceResource resource(Path storageDir, FakeEngine engine, long maxSize)
    {
        return new VoiceResource(new VoiceStorageService(storageDir.toString()), engine, maxSize);
    }

    private static IBusinessException<?> assertBusiness(Executable executable)
    {
        try
        {
            executable.execute();
        }
        catch(StructuredException e)
        {
            if(e instanceof IBusinessException<?> biz) return biz;
            throw new AssertionError("应为业务异常 (携带 ErrorCode), 实际: " + e, e);
        }
        catch(Throwable t) { throw new AssertionError("应为业务异常 (StructuredException), 实际: " + t, t); }
        throw new AssertionError("应抛出业务异常");
    }

    //* 最小 RIFF/WAVE 构造器: 资源层只校验前 12 字节头, 头部数值不影响断言 (与引擎测试同源).
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

    private static FileUpload fakeUpload(Path dir, String name, byte[] content) throws IOException
    {
        final var file = dir.resolve(name);
        Files.write(file, content);
        final var headers = new MultivaluedHashMap<String, String>();
        return new FileUpload()
        {
            @Override public String name() { return "file"; }
            @Override public Path filePath() { return file; }
            @Override public String fileName() { return name; }
            @Override public long size() { return content.length; }
            @Override public String contentType() { return "audio/wav"; }
            @Override public String charSet() { return "UTF-8"; }
            @Override public MultivaluedHashMap<String, String> getHeaders() { return headers; }
        };
    }

    /**
     * <b>可记录调用的 fake {@link IAsrEngine}</b>
     * <p>固定返回构造时给定的 {@link AsrResult}, 并记录被转录的文件路径供断言.</p>
     * <p>测试源集不引 JetBrains 注解 (compileOnly, main-only), 以类级 NullableProblems 抑制覆写签名告警.</p>
     */
    @SuppressWarnings("NullableProblems")
    static final class FakeEngine implements IAsrEngine
    {
        private final AsrResult result;
        private final List<Path> transcribed = new ArrayList<>();

        FakeEngine(AsrResult result) { this.result = result; }

        List<Path> calls() { return transcribed; }

        @Override public Uni<AsrResult> transcribe(Path wavFile)
        {
            transcribed.add(wavFile);
            return Uni.createFrom().item(result);
        }

        @Override public String name() { return "fake"; }
    }

    //endregion
}
