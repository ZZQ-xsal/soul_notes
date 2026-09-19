package kurvcygnus.soulnotes.domain.voice.resource;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import kurvcygnus.soulnotes.ai.asr.AsrResult;
import kurvcygnus.soulnotes.ai.asr.IAsrEngine;
import kurvcygnus.soulnotes.domain.voice.dto.VoiceUploadResponse;
import kurvcygnus.soulnotes.domain.voice.service.VoiceStorageService;
import kurvcygnus.soulnotes.dto.ApiResponse;
import kurvcygnus.soulnotes.exception.ErrorCode;
import kurvcygnus.soulnotes.exception.IBusinessException;
import kurvcygnus.soulnotes.utils.PrintUtils;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import kurvcygnus.soulnotes.utils.enums.UserRole;
import kurvcygnus.soulnotes.utils.enums.VoiceStatus;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.UUID;

/**
 * 语音处理 REST 资源, 面向 STUDENT 角色提供语音上传同步转录与已存语音文件回访端点.
 * <ul>
 *     <li>{@code POST /api/v1/voice/upload} — 上传语音文件并同步本地转录 (响应直接携带 transcribedText)</li>
 *     <li>{@code GET  /api/v1/voice/files/{fileId}} — 获取已存储的语音文件</li>
 * </ul>
 * <p>同步转录链路: 大小校验 → RIFF/WAVE 头校验 → 存储 → 引擎转录 → 响应.转录失败不回 5xx,
 * 而是 status=FAILED + message 透传原因 (离线安全网: 文字链路与应急热线兜底不因语音失败而崩溃).</p>
 * @since 1.0
 */
@Path(ApiEndpointConstants.VOICE_BASE)
@RolesAllowed(UserRole.ROLE_STUDENT)
public final class VoiceResource
{
    private static final Logger LOG = LoggerFactory.getLogger(VoiceResource.class);

    private final @NotNull VoiceStorageService voiceStorageService;
    private final @NotNull IAsrEngine asrEngine;
    private final long maxSize;

    /**
     * CDI 构造入口 (构造注入, 便于以 fake 引擎做单元测试).
     *
     * @param voiceStorageService 语音存储服务
     * @param asrEngine           本地 ASR 引擎 (SOULNOTES_ASR_ENGINE 选择, 可插拔)
     * @param maxSize             上传大小上限 (字节)
     */
    @Inject
    public VoiceResource(
        @NotNull VoiceStorageService voiceStorageService,
        @NotNull IAsrEngine asrEngine,
        @ConfigProperty(name = "voice.storage.max-size", defaultValue = "10485760") long maxSize
    )
    {
        this.voiceStorageService = Objects.requireNonNull(voiceStorageService, "Param \"voiceStorageService\" must not be null!");
        this.asrEngine = Objects.requireNonNull(asrEngine, "Param \"asrEngine\" must not be null!");
        this.maxSize = maxSize;
    }

    /**
     * 上传语音文件并同步转录, 文件 I/O 与转录调度整体移交 worker 线程池, 避免阻塞事件循环.
     * <p>链路: 大小校验 (事件循环上, 仅数值比较) → RIFF/WAVE 头校验 (落盘前拒绝非 WAV) →
     * 落盘存储 → 本地引擎转录 → 组装响应.</p>
     *
     * @param file 上传的语音文件 (16kHz 单声道 PCM16 WAV, 由前端 Web Audio 产出)
     * @return 上传响应: 成功为 TRANSCRIBED + 转录文本 (静音为空串); 失败为 FAILED + message (HTTP 仍 200,
     *         离线安全网语义)
     * @throws IBusinessException 文件超过大小上限 (BAD_REQUEST) 或非 WAV 格式 (BAD_REQUEST) 时
     */
    @POST @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public @NotNull Uni<ApiResponse<VoiceUploadResponse>> upload(@RestForm("file") @NotNull FileUpload file)
    {
        final var fileName = file.fileName();
        final var tempPath = file.uploadedFile();

        //! 事件循环上仅做数值比较, 不涉及 I/O, 不会阻塞; 超限提前拒绝, 避免无谓的落盘与转录消耗.
        if(file.size() > maxSize)
            return Uni.createFrom().failure(
                IBusinessException.of(
                    ErrorCode.BAD_REQUEST,
                    PrintUtils.quickFormat("语音文件大小超过限制 (最大 {} 字节)", maxSize),
                    IllegalArgumentException::new,
                    "VOICE_UPLOAD_SIZE_EXCEEDED"
                ).asException()
            );

        return Uni.createFrom().item(
                Unchecked.supplier(() ->
                    {
                        //! 存储前先做 RIFF/WAVE 头校验: 非 WAV 在落盘前即拒绝, 避免垃圾文件占用存储.
                        try
                        {
                            assertWavHeader(tempPath);
                            return Files.newInputStream(tempPath);
                        }
                        catch(IOException e) { throw new RuntimeException("无法读取上传文件", e); }
                    }
                )).
            flatMap(input -> voiceStorageService.store(fileName, input)).
            flatMap(stored -> asrEngine.transcribe(stored.path()).map(result -> toResponse(stored, result))).
            map(ApiResponse::success).
            runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * 获取已存储的语音文件内容.
     * <p>fileId 由服务端生成 (UUID), 通过 {@link UUID#fromString} 校验防止路径穿越.</p>
     *
     * @param fileId 文件唯一标识
     * @return 文件内容 (application/octet-stream); fileId 非 UUID 或文件不存在时均返回 404
     */
    @GET @Path("/files/{fileId}")
    public @NotNull Uni<Response> getFile(@PathParam("fileId") @NotNull String fileId)
    {
        //! fileId 非 UUID 时拒绝, 防止路径穿越到 storagePath 之外.
        try { UUID.fromString(fileId); }
        catch(IllegalArgumentException e) { return Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND).build()); }

        return voiceStorageService.load(fileId).map(
            bytes ->
            bytes == null ?
                Response.status(Response.Status.NOT_FOUND).build() :
                Response.ok(bytes, MediaType.APPLICATION_OCTET_STREAM).build()
        );
    }

    //region 辅助方法

    /**
     * 最小 RIFF/WAVE 头校验 (前 12 字节, 与引擎侧解析器同源).
     *
     * @param file 待校验的本地文件路径
     * @throws IOException        文件不可读时
     * @throws IBusinessException 非 WAV 头时 (BAD_REQUEST; 仅支持 16kHz 单声道 PCM16, 前端 Web Audio 产出约束)
     * @since 1.1.0
     */
    //* java.nio.file.Path 以全限定名书写: 与 jakarta.ws.rs.Path (JAX-RS 注解) 简名冲突, 后者在本文件注解中出现频次更高.
    private static void assertWavHeader(@NotNull java.nio.file.Path file) throws IOException
    {
        try(var in = Files.newInputStream(file))
        {
            final var header = in.readNBytes(12);
            final var isWav = header.length == 12
                && "RIFF".equals(new String(header, 0, 4, StandardCharsets.US_ASCII))
                && "WAVE".equals(new String(header, 8, 4, StandardCharsets.US_ASCII));
            if(!isWav)
                throw IBusinessException.of(
                    ErrorCode.BAD_REQUEST,
                    "语音格式不支持: 仅接受 16kHz 单声道 PCM16 编码的 WAV 文件 (前端需经 Web Audio 编码后上传)",
                    IllegalArgumentException::new,
                    "VOICE_FORMAT_UNSUPPORTED"
                ).asException();
        }
    }

    /**
     * 转录结果映射为响应体: error 非空 → FAILED + message (HTTP 200, 离线安全网);
     * 否则 TRANSCRIBED + 文本 (静音空串属合法成功).
     *
     * @param stored 已落盘的语音文件
     * @param result 引擎转录结果
     * @return 上传响应 (恒成功形态, 不产生 5xx)
     * @since 1.1.0
     */
    private static @NotNull VoiceUploadResponse toResponse(@NotNull VoiceStorageService.StoredVoice stored, @NotNull AsrResult result)
    {
        final var audioUrl = "/api/v1/voice/files/" + stored.fileId();
        if(result.error() != null)
        {
            LOG.warn("语音转录失败: fileId={}, reason={}", stored.fileId(), result.error());
            return new VoiceUploadResponse(audioUrl, stored.fileId(), VoiceStatus.FAILED, null, result.error());
        }
        return new VoiceUploadResponse(audioUrl, stored.fileId(), VoiceStatus.TRANSCRIBED, Objects.requireNonNullElse(result.text(), ""), null);
    }

    //endregion
}
