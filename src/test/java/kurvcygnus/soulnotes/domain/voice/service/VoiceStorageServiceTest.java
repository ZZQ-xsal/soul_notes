package kurvcygnus.soulnotes.domain.voice.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link VoiceStorageService} 行为单元测试</b>
 * <p>@TempDir 落盘驱动 store/load/delete 全流程, 并钉死 store 返回 StoredVoice (fileId + 实际路径)
 * 供上传链路同步转录使用.</p>
 *
 * @since 1.0
 */
class VoiceStorageServiceTest
{
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    //region 结构契约

    @Test void class_ShouldBeFinal()
    {
        assertTrue(Modifier.isFinal(VoiceStorageService.class.getModifiers()));
    }

    @Test void class_ShouldBeApplicationScoped()
    {
        assertTrue(VoiceStorageService.class.isAnnotationPresent(ApplicationScoped.class));
    }

    @Test void constructor_TakesStringParam() throws Exception
    {
        final var constructor = VoiceStorageService.class.getDeclaredConstructor(String.class);
        assertNotNull(constructor);
    }

    //endregion

    //region 行为

    //* store 返回 StoredVoice: fileId 为 UUID, path 指向真实落盘文件 — 上传链路据此直接同步转录.
    @Test void store_ShouldPersistFileAndReturnIdWithPath(@TempDir Path tempDir) throws Exception
    {
        final var storage = new VoiceStorageService(tempDir.resolve("voice").toString());
        final var content = new byte[] {1, 0, 2, 0, 3, 0};

        final var stored = storage.store("hello.wav", new ByteArrayInputStream(content)).await().atMost(AWAIT_TIMEOUT);

        assertTrue(stored.fileId().matches("[0-9a-f-]{36}"), "fileId 应为 UUID: " + stored.fileId());
        assertTrue(Files.exists(stored.path()), "path 应指向已落盘文件");
        assertArrayEquals(content, Files.readAllBytes(stored.path()));
        assertTrue(stored.path().toString().contains(stored.fileId()), "落盘路径应位于 fileId 目录内");
    }

    //* 文件名净化: 非法字符被替换, 落盘文件不得逃出 fileId 目录 (路径穿越防护).
    @Test void store_ShouldSanitizeHostileFileName(@TempDir Path tempDir)
    {
        final var storage = new VoiceStorageService(tempDir.toString());

        final var stored = storage.store("../..\\evil name.wav", new ByteArrayInputStream(new byte[] {1})).await().atMost(AWAIT_TIMEOUT);

        assertEquals(tempDir.resolve(stored.fileId()), stored.path().getParent(), "落盘目录必须是 fileId 目录本身");
        assertEquals(".._.._evil_name.wav", stored.path().getFileName().toString(), "文件名非法字符应被替换为下划线 (点号保留)");
    }

    @Test void load_AfterStore_ShouldReturnContent(@TempDir Path tempDir)
    {
        final var storage = new VoiceStorageService(tempDir.toString());
        final var content = new byte[] {9, 8, 7};
        final var stored = storage.store("a.wav", new ByteArrayInputStream(content)).await().atMost(AWAIT_TIMEOUT);

        final var loaded = storage.load(stored.fileId()).await().atMost(AWAIT_TIMEOUT);

        assertArrayEquals(content, loaded);
    }

    @Test void load_MissingFile_ShouldReturnNull(@TempDir Path tempDir)
    {
        final var storage = new VoiceStorageService(tempDir.toString());

        final var loaded = storage.load("00000000-0000-0000-0000-000000000000").await().atMost(AWAIT_TIMEOUT);

        assertNull(loaded, "文件不存在应返回 null (资源层映射 404)");
    }

    @Test void delete_AfterStore_ShouldRemoveDirectory(@TempDir Path tempDir)
    {
        final var storage = new VoiceStorageService(tempDir.toString());
        final var stored = storage.store("b.wav", new ByteArrayInputStream(new byte[] {1})).await().atMost(AWAIT_TIMEOUT);

        storage.delete(stored.fileId()).await().atMost(AWAIT_TIMEOUT);

        assertFalse(Files.exists(stored.path().getParent()), "删除后 fileId 目录应整体移除");
    }

    //endregion
}
