package kurvcygnus.soulnotes.domain.voice.resource;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import kurvcygnus.soulnotes.domain.voice.dto.AsrCallbackRequest;
import kurvcygnus.soulnotes.domain.voice.dto.VoiceUploadResponse;
import kurvcygnus.soulnotes.domain.voice.service.AsrTranscriptionService;
import kurvcygnus.soulnotes.domain.voice.service.VoiceStorageService;
import kurvcygnus.soulnotes.dto.ApiResponse;
import kurvcygnus.soulnotes.exception.ErrorCode;
import kurvcygnus.soulnotes.exception.IBusinessException;
import kurvcygnus.soulnotes.utils.PrintUtils;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>语音处理 REST 资源</b>
 * <ul>
 *     <li>{@code POST /api/v1/voice/upload} — 上传语音文件</li>
 *     <li>{@code GET  /api/v1/voice/files/{fileId}} — 获取已存储的语音文件</li>
 *     <li>{@code POST /api/v1/voice/asr-callback} — 接收 ASR 转录回调 (外部服务, 免认证)</li>
 * </ul>
 * @since 1.0
 */
@Path(ApiEndpointConstants.VOICE_BASE)
@RolesAllowed(UserRole.ROLE_STUDENT)
public final class VoiceResource
{
    private static final Logger LOG = LoggerFactory.getLogger(VoiceResource.class);

    @Inject VoiceStorageService voiceStorageService;
    @Inject AsrTranscriptionService asrTranscriptionService;

    //* 上传大小上限 (字节), 配置缺失时回退默认值, 保证默认行为可用.
    @ConfigProperty(name = "voice.storage.max-size", defaultValue = "10485760")
    //! 基元类型不可为空, @NotNull 无法作用于 long, 故不标注 (brief 原样代码会触发编译警告).
    long maxSize;

    //* ASR 回调密钥, 与文档保持一致; 未配置时 Optional 为空, 原型阶段放行.
    //! 不能使用非 Optional String + 空串默认值: SmallRye 将空串视为 null, 非 Optional 注入点会抛 SRCFG00040 导致启动失败.
    @ConfigProperty(name = "asr.callback.api-key")
    @NotNull Optional<String> asrCallbackApiKey;

    /**
     * <span style="color: 95cc6d">上传语音文件.</span>
     * <p>接收 multipart 文件, 存储后触发 ASR 转录 (异步).</p>
     * <p>文件 I/O 整体移交 worker 线程池, 避免阻塞事件循环.</p>
     *
     * @param file 上传的语音文件
     * @return 上传响应
     */
    @POST @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    //! item supplier 实际在 runSubscriptionOn(worker) 后的 worker 线程执行, 非事件循环.
    @SuppressWarnings("BlockingMethodInNonBlockingContext")
    public @NotNull Uni<ApiResponse<VoiceUploadResponse>> upload(@RestForm("file") @NotNull FileUpload file)
    {
        final var fileName = file.fileName();
        final var path     = file.uploadedFile();

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

        //! uploadedFile() returns a Path to the temp file; 延迟到 worker 池再打开, 避免事件循环做文件 I/O.
        return Uni.createFrom().item(() ->
                {
                    try { return voiceStorageService.store(fileName, java.nio.file.Files.newInputStream(path)); }
                    catch(IOException e) { throw new RuntimeException("无法读取上传文件", e); }
                }
            ).
            flatMap(u -> u).
            runSubscriptionOn(Infrastructure.getDefaultWorkerPool()).
            onItem().invoke(resp ->
                asrTranscriptionService.dispatchTranscription(resp.fileId(), resp.audioUrl()).
                    subscribe().with(
                        v -> {},
                        f -> LOG.warn("ASR 转录分发失败: {}", f.getMessage())
                    )
            ).
            map(ApiResponse::success);
    }

    /**
     * <span style="color: 95cc6d">获取已存储的语音文件.</span>
     * <p>fileId 由服务端生成 (UUID), 通过 {@link UUID#fromString} 校验防止路径穿越.</p>
     *
     * @param fileId 文件唯一标识
     * @return 文件内容, 不存在时返回 404
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

    /**
     * <span style="color: 95cc6d">接收 ASR 服务回调.</span>
     * <p>外部 ASR 服务在完成转录后调用此接口推送结果.</p>
     * <p>当前仅记录日志, 后续可扩展为自动创建 Diary.</p>
     *
     * @param apiKey ASR 回调密钥 (X-API-Key 请求头, 可省略)
     * @param req    ASR 回调请求体
     * @return 空响应
     */
    //? ASR 回调由外部服务发起, 生产环境应使用 API Key 或 IP 白名单鉴权, 故此处使用 @PermitAll 免认证.
    @POST @Path("/asr-callback") @PermitAll
    public @NotNull Uni<ApiResponse<Void>> handleAsrCallback(
        //! X-API-Key 为自定义请求头, 不在 IDE 内置标头表中, 该弱警告仅能通过 IDE 本地自定义标头设置消除, 无法用 @SuppressWarnings 抑制, 属预期的协议设计.
        @HeaderParam("X-API-Key") @Nullable String apiKey,
        @NotNull AsrCallbackRequest req
    )
    {
        //! 配置了回调密钥时必须校验, 防止未授权调用刷接口; 未配置 (Optional 为空) 仅限原型阶段放行 (与文档一致).
        if(asrCallbackApiKey.filter(k -> !k.isBlank()).isPresent() && !asrCallbackApiKey.orElse("").equals(apiKey))
            return Uni.createFrom().failure(
                IBusinessException.of(
                    ErrorCode.AUTH_UNAUTHORIZED,
                    "ASR 回调密钥无效",
                    SecurityException::new,
                    "VOICE_ASR_CALLBACK_UNAUTHORIZED"
                ).asException()
            );
        return asrTranscriptionService.handleResult(req.fileId(), req.transcribedText(), req.status()).
            map(v -> ApiResponse.success());
    }
}
