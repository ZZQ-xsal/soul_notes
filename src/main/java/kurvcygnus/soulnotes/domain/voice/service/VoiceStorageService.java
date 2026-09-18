package kurvcygnus.soulnotes.domain.voice.service;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 语音文件存储服务: 将上传的语音文件写入本地文件系统, 并提供读取/删除能力.
 * <p>返回的 {@link StoredVoice} 同时携带 fileId 与实际落盘路径, 供上传链路同步转录直接取用.</p>
 *
 * @implNote 全部阻塞文件 I/O 经 {@code runSubscriptionOn} 移交 worker 线程池执行, 不阻塞事件循环.
 * @since 1.0
 */
@ApplicationScoped
public final class VoiceStorageService
{
    private static final Logger LOG = LoggerFactory.getLogger(VoiceStorageService.class);

    private final @NotNull Path storagePath;

    public VoiceStorageService(
        @ConfigProperty(name = "voice.storage.directory", defaultValue = "voice_uploads")
        @NotNull String storageDir
    ) { this.storagePath = Path.of(storageDir); }

    /**
     * 已存储语音的载体 record.
     * <p>fileId 供前端回访 {@code /voice/files} 端点, path 供引擎直读 —
     * 转录必须针对存储文件而非 resteasy 临时文件 (临时文件随请求结束被清理).</p>
     *
     * @param fileId 文件唯一标识 (UUID)
     * @param path   实际落盘路径
     * @since 1.1.0
     */
    public record StoredVoice(@NotNull String fileId, @NotNull Path path) {}

    //* audioUrl 的单一权威: 资源层 (upload 响应) 与 DiaryService (日记语音附件) 共用, 防路径漂移.
    /**
     * 由 fileId 构造语音文件访问 URL, 是 audioUrl 的单一权威来源.
     *
     * @param fileId 文件唯一标识
     * @return 形如 {@code /api/v1/voice/files/{fileId}} 的访问路径
     * @since 1.1.0
     */
    public static @NotNull String audioUrlOf(@NotNull String fileId)
    { return "/api/v1/voice/files/" + fileId; }

    /**
     * 存储语音文件: 以随机 UUID 为目录、过滤后的原始文件名落盘.
     *
     * @param fileName 原始文件名 (非法字符会被替换为下划线, 防路径穿越)
     * @param input    文件输入流
     * @return 存储结果 (fileId + 实际路径)
     * @implNote 底层 IOException 被包装为 {@link RuntimeException} 以失败 Uni 发出, 由调用方决定降级形态.
     */
    public @NotNull Uni<StoredVoice> store(@NotNull String fileName, @NotNull InputStream input)
    {
        return Uni.createFrom().item(
            Unchecked.supplier(() ->
            {
                try
                {
                    final var fileId  = UUID.randomUUID().toString();
                    final var dir     = storagePath.resolve(fileId);
                    Files.createDirectories(dir);
                    final var target  = dir.resolve(sanitize(fileName));
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);

                    LOG.info("语音文件已存储: fileId={}, path={}", fileId, target);
                    return new StoredVoice(fileId, target);
                }
                catch(IOException e)
                {
                    LOG.warn("语音文件存储失败: {}", e.getMessage());
                    throw new RuntimeException("语音文件存储失败", e);
                }
            })
        ).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * 删除语音文件及其目录, 尽力清理 (best-effort).
     *
     * @param fileId 文件唯一标识
     * @return 完成信号; 目录不存在时静默成功 (幂等), 单个文件/目录删除失败仅 WARN 不中断
     */
    public @NotNull Uni<Void> delete(@NotNull String fileId)
    {
        return Uni.createFrom().<Void>item(
            () ->
            {
                final var dir = storagePath.resolve(fileId);
                if(!Files.exists(dir))
                    return null;

                try(var files = Files.list(dir))
                {
                    //* 显式检查 deleteIfExists 结果, 避免静默失败.
                    files.forEach(file ->
                        {
                            try { Files.deleteIfExists(file); }
                            catch(IOException e) { LOG.warn("删除语音文件失败: {}", e.getMessage()); }
                        }
                    );
                }
                catch(IOException e) { LOG.warn("遍历语音目录失败: {}", e.getMessage()); }

                try { Files.deleteIfExists(dir); }
                catch(IOException e) { LOG.warn("删除语音目录失败: {}", e.getMessage()); }
                return null;
            }
        ).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * 读取语音文件内容.
     *
     * @param fileId 文件唯一标识
     * @return 文件字节内容; 文件不存在时以 {@code null} 项完成 (由资源层映射为 404)
     * @implNote 读取期 IOException 被包装为 {@link RuntimeException} 以失败 Uni 发出.
     */
    public @NotNull Uni<byte[]> load(@NotNull String fileId)
    {
        return Uni.createFrom().item(() ->
            {
                final var dir = storagePath.resolve(fileId);
                if(!Files.exists(dir))
                    return null;

                try(var files = Files.list(dir))
                {
                    final var file = files.findFirst().orElse(null);
                    if(file == null)
                        return null;
                    return Files.readAllBytes(file);
                }
                catch(IOException e)
                {
                    LOG.warn("读取语音文件失败: {}", e.getMessage());
                    throw new RuntimeException("读取语音文件失败", e);
                }
            }
        ).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    //region 辅助方法

    //* 过滤路径穿越与非法字符.
    private static @NotNull String sanitize(@NotNull String name)
    {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    //endregion
}
