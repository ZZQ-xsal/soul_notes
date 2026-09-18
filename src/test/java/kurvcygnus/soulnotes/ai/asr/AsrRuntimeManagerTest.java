package kurvcygnus.soulnotes.ai.asr;

import com.sun.net.httpserver.HttpServer;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link AsrRuntimeManager} 行为单元测试</b>
 * <p>覆盖 ready() 三态 (空目录/仅模型/齐备, 模型完整以 am/ 或 conf/ 标志目录判定),
 * modelDir/nativeLib 路径解析, ensureDownloaded 的下载落位/进度回调/zip-slip 防护/失败清理等.
 * 下载用例经 JDK 内置 HttpServer 回环伺服本地 fixture zip, 不触真实网络.</p>
 *
 * <p>真实网络下载用例标注 {@code @Disabled} 手动执行, 不进 CI.</p>
 * @since 1.0
 */
class AsrRuntimeManagerTest
{
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    //* 与真实模型 zip 同构的最小布局: 顶层一层模型目录, 内含 am/ 与 conf/ 标志目录.
    private static final String MODEL_DIR_NAME = "vosk-model-fake";

    //region ready() 三态与路径解析

    @Test void class_ShouldBeApplicationScoped()
    {
        assertTrue(AsrRuntimeManager.class.isAnnotationPresent(ApplicationScoped.class));
    }

    @Test void ready_EmptyRuntime_ShouldReturnFalse(@TempDir Path tempDir)
    {
        assertFalse(manager(tempDir, "unused").ready());
    }

    @Test void ready_ModelWithoutMarker_ShouldReturnFalse(@TempDir Path tempDir) throws Exception
    {
        //* 升级语义: 仅有子目录不算完整模型, 必须含 am/ 或 conf/ 标志目录.
        final var runtime = tempDir.resolve("runtime");
        Files.createDirectories(runtime.resolve("model").resolve(MODEL_DIR_NAME).resolve("graph"));

        assertFalse(manager(runtime, "unused").ready());
    }

    @Test void ready_AmMarkerWithoutLib_ShouldReturnFalse(@TempDir Path tempDir) throws Exception
    {
        final var runtime = modelOnlyRuntime(tempDir, "am");

        assertFalse(manager(runtime, "unused").ready());
    }

    @Test void ready_ConfMarkerWithoutLib_ShouldReturnFalse(@TempDir Path tempDir) throws Exception
    {
        final var runtime = modelOnlyRuntime(tempDir, "conf");

        assertFalse(manager(runtime, "unused").ready());
    }

    @Test void ready_CompleteRuntime_ShouldReturnTrue(@TempDir Path tempDir) throws Exception
    {
        final var runtime = completeRuntime(tempDir);

        assertTrue(manager(runtime, "unused").ready());
    }

    @Test void modelDir_ShouldReturnFirstSortedMarkedSubdirectory(@TempDir Path tempDir) throws Exception
    {
        final var runtime = tempDir.resolve("runtime");
        final var modelRoot = runtime.resolve("model");
        Files.createDirectories(modelRoot.resolve("2-no-marker").resolve("graph"));
        Files.createDirectories(modelRoot.resolve("1-marked-later").resolve("conf"));
        Files.createDirectories(modelRoot.resolve("0-marked-first").resolve("am"));

        assertEquals("0-marked-first", manager(runtime, "unused").modelDir().getFileName().toString());
    }

    @Test void modelDir_NoMarkedModel_ShouldThrowIllegalState(@TempDir Path tempDir) throws Exception
    {
        final var runtime = tempDir.resolve("runtime");
        Files.createDirectories(runtime.resolve("model").resolve(MODEL_DIR_NAME));

        assertThrows(IllegalStateException.class, () -> manager(runtime, "unused").modelDir());
    }

    @Test void nativeLib_ShouldPointIntoLibDirWithVoskName(@TempDir Path tempDir)
    {
        final var runtime = tempDir.resolve("runtime");
        final var lib = manager(runtime, "unused").nativeLib();

        assertEquals(runtime.resolve("lib"), lib.getParent(), "动态库必须落在 <runtime>/lib/ 下");
        assertEquals(AsrRuntimeManager.nativeLibFileName(System.getProperty("os.name")), lib.getFileName().toString(),
            "文件名须与生产平台解析同源 (禁止写死任一平台名): " + lib);
    }

    @Test void nativeLibFileName_ShouldMapOsNameThreeWay()
    {
        //* 与生产 nativeLib 同源的三态映射钉死: 未知平台回落 .so, 不抛异常.
        assertEquals("libvosk.dll", AsrRuntimeManager.nativeLibFileName("Windows 11"));
        assertEquals("libvosk.dylib", AsrRuntimeManager.nativeLibFileName("Mac OS X"));
        assertEquals("libvosk.dylib", AsrRuntimeManager.nativeLibFileName("Darwin"));
        assertEquals("libvosk.so", AsrRuntimeManager.nativeLibFileName("Linux"));
        assertEquals("libvosk.so", AsrRuntimeManager.nativeLibFileName("SunOS"));
    }

    //endregion

    //region 平台探测表 (纯函数)

    @Test void platformEntryDir_ShouldMapVosk03045JarLayout()
    {
        //* vosk-0.3.45.jar 实测 entry 布局 (Maven Central 官方 JAR): win32-x86-64 / linux-x86-64 / darwin, 无 arm 构建.
        assertEquals("win32-x86-64", AsrRuntimeManager.platformEntryDir("Windows 11", "amd64"));
        assertEquals("win32-x86-64", AsrRuntimeManager.platformEntryDir("Windows Server 2022", "x86_64"));
        assertEquals("linux-x86-64", AsrRuntimeManager.platformEntryDir("Linux", "amd64"));
        assertEquals("darwin", AsrRuntimeManager.platformEntryDir("Mac OS X", "x86_64"));
        assertEquals("darwin", AsrRuntimeManager.platformEntryDir("Darwin", "x86_64"));
    }

    @Test void platformEntryDir_UnsupportedPlatform_ShouldReturnNull()
    {
        //* 0.3.45 JAR 无 arm 构建: 覆盖键由 SOULNOTES_ASR_LIB_URL 承担, 探测表如实返回 null.
        assertNull(AsrRuntimeManager.platformEntryDir("Linux", "aarch64"));
        assertNull(AsrRuntimeManager.platformEntryDir("Windows 11", "aarch64"));
        assertNull(AsrRuntimeManager.platformEntryDir("Mac OS X", "aarch64"));
        assertNull(AsrRuntimeManager.platformEntryDir("SunOS", "sparc"));
    }

    //endregion

    //region lib URL 解析 (properties 空默认的消费端归一, Pre-Launch 装配同源复用)

    @Test void resolveLibUrl_BlankFallsBackToBuiltinDefault()
    {
        //* properties 侧默认值为空串 (向导展示友好): "存在但为空" 不触发 @ConfigProperty defaultValue, 归一必须在此兜底.
        assertEquals(AsrRuntimeManager.DEFAULT_LIB_JAR_URL, AsrRuntimeManager.resolveLibUrl(""));
        assertEquals(AsrRuntimeManager.DEFAULT_LIB_JAR_URL, AsrRuntimeManager.resolveLibUrl("   "));
    }

    @Test void resolveLibUrl_ExplicitCustomPassthrough()
    {
        assertEquals("https://example.invalid/vosk-arm.jar", AsrRuntimeManager.resolveLibUrl("https://example.invalid/vosk-arm.jar"));
    }

    //endregion

    //region ensureDownloaded (本地 HttpServer fixture)

    @Test void ensureDownloaded_EmptyRuntime_ShouldInstallModelAndLibAndBecomeReady(@TempDir Path tempDir) throws Exception
    {
        final var runtime = tempDir.resolve("runtime");
        final var modelZip = zipOf(Map.of(
            MODEL_DIR_NAME + "/am/scanner", new byte[] {1},
            MODEL_DIR_NAME + "/conf/model.conf", new byte[] {2},
            MODEL_DIR_NAME + "/README", "fake model".getBytes()));
        final var libJar = zipOf(fullLibEntries());

        try(var modelServer = new ZipServer(modelZip, 200, null);
            var libServer = new ZipServer(libJar, 200, null))
        {
            final var manager = manager(runtime, modelServer.url(), libServer.url());

            manager.ensureDownloaded(null).await().atMost(AWAIT_TIMEOUT);

            assertTrue(manager.ready(), "下载完成后必须就绪");
            assertEquals(MODEL_DIR_NAME, manager.modelDir().getFileName().toString(),
                "模型 zip 顶层目录应解压落位于 <runtime>/model/ 下");
            assertTrue(Files.isRegularFile(runtime.resolve("model").resolve(MODEL_DIR_NAME).resolve("conf").resolve("model.conf")));
            assertArrayEquals(expectedPlatformLibBytes(), Files.readAllBytes(manager.nativeLib()),
                "动态库内容须从 JAR 内当前平台 entry 原样提取");
        }
    }

    @Test void ensureDownloaded_Windows_ShouldExtractMinGWCompanionDlls(@TempDir Path tempDir) throws Exception
    {
        //* Spike 坑清单: Windows 版 libvosk.dll 是 MinGW 构建, libstdc++/libgcc/libwinpthread 必须同目录.
        final var runtime = tempDir.resolve("runtime");
        final var libJar = zipOf(Map.of(
            "win32-x86-64/libvosk.dll", new byte[] {3},
            "win32-x86-64/libstdc++-6.dll", new byte[] {4},
            "win32-x86-64/libgcc_s_seh-1.dll", new byte[] {5},
            "win32-x86-64/libwinpthread-1.dll", new byte[] {6},
            "linux-x86-64/libvosk.so", new byte[] {7},
            "darwin/libvosk.dylib", new byte[] {8}));

        try(var modelServer = new ZipServer(zipOf(Map.of(MODEL_DIR_NAME + "/conf/x", new byte[] {1})), 200, null);
            var libServer = new ZipServer(libJar, 200, null))
        {
            final var manager = manager(runtime, modelServer.url(), libServer.url());
            manager.ensureDownloaded(null).await().atMost(AWAIT_TIMEOUT);

            final var expectedDir = AsrRuntimeManager.platformEntryDir(
                System.getProperty("os.name"), System.getProperty("os.arch"));
            if("win32-x86-64".equals(expectedDir))
            {
                final var libDir = runtime.resolve("lib");
                assertTrue(Files.isRegularFile(libDir.resolve("libstdc++-6.dll")), "MinGW 伴生 DLL 须同目录提取");
                assertTrue(Files.isRegularFile(libDir.resolve("libgcc_s_seh-1.dll")));
                assertTrue(Files.isRegularFile(libDir.resolve("libwinpthread-1.dll")));
            }
        }
    }

    @Test void ensureDownloaded_AlreadyReady_ShouldNotDownload(@TempDir Path tempDir) throws Exception
    {
        final var runtime = completeRuntime(tempDir);

        try(var server = new ZipServer(new byte[] {1}, 200, null))
        {
            final var manager = manager(runtime, server.url(), server.url());

            manager.ensureDownloaded(null).await().atMost(AWAIT_TIMEOUT);

            assertEquals(0, server.requests(), "就绪态不得发起任何下载请求");
            assertTrue(manager.ready());
        }
    }

    @Test void ensureDownloaded_ShouldReportProgressPerAsset(@TempDir Path tempDir) throws Exception
    {
        final var runtime = tempDir.resolve("runtime");
        final var modelZip = zipOf(Map.of(MODEL_DIR_NAME + "/conf/model.conf", new byte[64]));
        final var libJar = fullLibJar();
        final var calls = new CopyOnWriteArrayList<int[]>(); //* BiConsumer<Integer,Integer> 装箱回调记录

        try(var modelServer = new ZipServer(modelZip, 200, null);
            var libServer = new ZipServer(libJar, 200, null))
        {
            final var manager = manager(runtime, modelServer.url(), libServer.url());

            manager.ensureDownloaded((received, total) -> calls.add(new int[] {received, total}))
                .await().atMost(AWAIT_TIMEOUT);

            assertFalse(calls.isEmpty(), "进度回调必须被调用");
            assertTrue(calls.stream().anyMatch(p -> p[0] == modelZip.length && p[1] == modelZip.length),
                "模型 zip 下载须以 (total, total) 收尾: " + describe(calls));
            assertTrue(calls.stream().anyMatch(p -> p[0] == libJar.length && p[1] == libJar.length),
                "动态库 JAR 下载须以 (total, total) 收尾: " + describe(calls));
        }
    }

    @Test void ensureDownloaded_ShouldRunOnWorkerPool(@TempDir Path tempDir) throws Exception
    {
        final var runtime = tempDir.resolve("runtime");
        final var callerThread = Thread.currentThread().getName();
        final var seenThreads = new CopyOnWriteArrayList<String>();

        try(var modelServer = new ZipServer(zipOf(Map.of(MODEL_DIR_NAME + "/conf/x", new byte[] {1})), 200, null);
            var libServer = new ZipServer(fullLibJar(), 200, null))
        {
            final var manager = manager(runtime, modelServer.url(), libServer.url());

            manager.ensureDownloaded((received, total) -> seenThreads.add(Thread.currentThread().getName()))
                .await().atMost(AWAIT_TIMEOUT);

            assertFalse(seenThreads.isEmpty());
            assertTrue(seenThreads.stream().noneMatch(callerThread::equals), "下载须在 worker 池执行, 不占订阅者线程");
        }
    }

    @Test void ensureDownloaded_ZipSlipEntry_ShouldFailWithoutWritingOutsideTarget(@TempDir Path tempDir) throws Exception
    {
        //* 对抗性输入: entry 名 "../evil.txt" 解析后逃出解压目录, 必须整体拒绝而非静默写穿.
        final var runtime = tempDir.resolve("runtime");
        final var malicious = zipOf(Map.of(
            "../evil.txt", "pwned".getBytes(),
            MODEL_DIR_NAME + "/conf/model.conf", new byte[] {2}));

        try(var modelServer = new ZipServer(malicious, 200, null);
            var libServer = new ZipServer(zipOf(Map.of("linux-x86-64/libvosk.so", new byte[] {7})), 200, null))
        {
            final var manager = manager(runtime, modelServer.url(), libServer.url());

            var failure = assertThrows(Exception.class,
                () -> manager.ensureDownloaded(null).await().atMost(AWAIT_TIMEOUT),
                "zip-slip 条目必须使 Uni 失败");

            assertTrue(failureMessage(failure).contains("zip-slip"), "失败原因须指明 zip-slip: " + failure);
            assertFalse(Files.exists(runtime.resolve("model").resolve("evil.txt")), "不得写穿到 model/ 目录");
            assertFalse(Files.exists(runtime.resolve("evil.txt")), "不得写穿到 runtime 目录");
            assertFalse(manager.ready(), "拦截后不得进入就绪态");
        }
    }

    @Test void ensureDownloaded_HttpError_ShouldFailUniWithoutArtifacts(@TempDir Path tempDir) throws Exception
    {
        final var runtime = tempDir.resolve("runtime");
        Files.createDirectories(runtime); //* 预建目录: 断言的是"无残留文件", 而非目录本身存在与否

        try(var modelServer = new ZipServer(null, 404, null);
            var libServer = new ZipServer(new byte[] {1}, 200, null))
        {
            final var manager = manager(runtime, modelServer.url(), libServer.url());

            assertThrows(Exception.class, () -> manager.ensureDownloaded(null).await().atMost(AWAIT_TIMEOUT));

            assertFalse(manager.ready());
            try(var stream = Files.walk(runtime))
            {
                final var leftovers = stream.filter(Files::isRegularFile).toList();
                assertTrue(leftovers.isEmpty(), "失败后不得残留半成品文件: " + leftovers);
            }
        }
    }

    @Test void ensureDownloaded_JarWithoutPlatformLib_ShouldFailWithClearMessage(@TempDir Path tempDir) throws Exception
    {
        //* fixture 只带无平台对应的 entry: 任何测试平台都应得到 "JAR 内无平台动态库" 的明确失败.
        final var runtime = tempDir.resolve("runtime");

        try(var modelServer = new ZipServer(zipOf(Map.of(MODEL_DIR_NAME + "/conf/x", new byte[] {1})), 200, null);
            var libServer = new ZipServer(zipOf(Map.of("sparc-unknown/libvosk.so", new byte[] {3})), 200, null))
        {
            final var manager = manager(runtime, modelServer.url(), libServer.url());

            var failure = assertThrows(Exception.class,
                () -> manager.ensureDownloaded(null).await().atMost(AWAIT_TIMEOUT));

            assertTrue(failureMessage(failure).contains("平台"), "须给出无平台动态库的明确诊断: " + failure);
            assertFalse(manager.ready());
        }
    }

    @Test void ensureDownloaded_MaliciousJarEntry_ShouldFailWithoutWritingOutsideLib(@TempDir Path tempDir) throws Exception
    {
        //* 对抗性输入: JAR entry "<平台entry目录>/../../evil.dll" 的 .dll 后缀过滤挡不住路径穿越, 必须整体拒绝且零逃逸落盘.
        //* 恶意 entry 挂在当前平台 entry 目录下: 其余平台的 entry 目录会被平台过滤先跳过, 用例就测不到 zip-slip 防护本身.
        final var runtime = tempDir.resolve("runtime");
        final var hostileJar = zipOf(Map.of(
            AsrRuntimeManager.platformEntryDir(System.getProperty("os.name"), System.getProperty("os.arch")) + "/../../evil.dll",
            new byte[] {9}));

        try(var modelServer = new ZipServer(zipOf(Map.of(MODEL_DIR_NAME + "/conf/x", new byte[] {1})), 200, null);
            var libServer = new ZipServer(hostileJar, 200, null))
        {
            final var manager = manager(runtime, modelServer.url(), libServer.url());

            var failure = assertThrows(Exception.class,
                () -> manager.ensureDownloaded(null).await().atMost(AWAIT_TIMEOUT),
                "恶意 JAR entry 必须使 Uni 失败");

            assertTrue(failureMessage(failure).contains("zip-slip"), "失败原因须指明 zip-slip: " + failure);
            try(var stream = Files.walk(tempDir))
            {
                final var escapes = stream.filter(p -> p.getFileName().toString().equals("evil.dll")).toList();
                assertTrue(escapes.isEmpty(), "恶意 entry 不得在临时树内任何位置落盘: " + escapes);
            }
            assertFalse(manager.ready(), "拦截后不得进入就绪态");
        }
    }

    @Test void ensureDownloaded_HttpError_ShouldCloseResponseBody(@TempDir Path tempDir)
    {
        //* Minor 升格: 非 200 时 ofInputStream 的响应体必须关闭, 否则占住连接, 反复失败堆积泄漏.
        final var client = new RecordingClient(404, new byte[] {1});
        final var manager = new AsrRuntimeManager(tempDir.resolve("runtime"),
            AsrRuntimeManager.DEFAULT_MODEL_URL, AsrRuntimeManager.DEFAULT_LIB_JAR_URL, client);

        assertThrows(Exception.class, () -> manager.ensureDownloaded(null).await().atMost(AWAIT_TIMEOUT));

        assertNotNull(client.lastStream, "非 200 响应的 body 流须被实例化");
        assertTrue(client.lastStream.closed, "非 200 响应体必须关闭以释放连接");
    }

    @Test void ensureDownloaded_ConcurrentCall_ShouldRejectSecondAndResetAfterwards(@TempDir Path tempDir) throws Exception
    {
        final var runtime = tempDir.resolve("runtime");
        final var release = new CountDownLatch(1);

        try(var modelServer = new ZipServer(zipOf(Map.of(MODEL_DIR_NAME + "/conf/x", new byte[] {1})), 200, release);
            var libServer = new ZipServer(fullLibJar(), 200, null))
        {
            final var manager = manager(runtime, modelServer.url(), libServer.url());

            //* //! Mutiny Uni 惰性且可重复订阅: first.subscribe() 后再 await() 会二次执行下载管线,
            //* 故第一路经 latch 等待终态, 绝不复用同一 Uni 实例二次订阅.
            final var firstDone = new CountDownLatch(1);
            final var firstFailure = new AtomicReference<Throwable>();
            manager.ensureDownloaded(null).subscribe().with(
                ignored -> firstDone.countDown(),
                failure ->
                {
                    firstFailure.set(failure);
                    firstDone.countDown();
                });
            modelServer.awaitEntered(); //* 第一个请求已进入服务端 -> 下载中标志已置位

            var second = assertThrows(Exception.class,
                () -> manager.ensureDownloaded(null).await().atMost(AWAIT_TIMEOUT),
                "并发第二路下载须被拒绝");

            assertTrue(failureMessage(second).contains("进行中"), "拒绝文案须可识别: " + second);
            release.countDown();

            assertTrue(firstDone.await(10, TimeUnit.SECONDS), "第一路下载未在限时内完成");
            assertNull(firstFailure.get(), "第一路应成功: " + firstFailure.get());
            manager.ensureDownloaded(null).await().atMost(AWAIT_TIMEOUT); //* 标志复位: 新实例可用
        }
    }

    //endregion

    //region 真实网络下载 (手动执行, 不进 CI)

    @Test
    @Disabled("手动执行: 需要真实网络与 ~60MB 下载流量, 不进 CI")
    void ensureDownloaded_RealNetwork_ShouldInstallOfficialRuntime(@TempDir Path tempDir) throws Exception
    {
        //* 官方源: 模型 zip (alphacephei, ~42MB) + libvosk JAR (aliyun 镜像, ~25MB, 即 SOULNOTES_ASR_LIB_URL 默认值).
        final var manager = manager(tempDir.resolve("asr-model"),
            AsrRuntimeManager.DEFAULT_MODEL_URL, AsrRuntimeManager.DEFAULT_LIB_JAR_URL);

        manager.ensureDownloaded((received, total) -> System.out.printf("download %d/%d%n", received, total))
            .await().atMost(Duration.ofMinutes(50));

        assertTrue(manager.ready());
        assertEquals("vosk-model-small-cn-0.22", manager.modelDir().getFileName().toString());
    }

    //endregion

    //region 测试脚手架

    private static AsrRuntimeManager manager(Path runtimeDir, String libUrl)
    {
        return new AsrRuntimeManager(runtimeDir, AsrRuntimeManager.DEFAULT_MODEL_URL, libUrl);
    }

    private static AsrRuntimeManager manager(Path runtimeDir, String modelUrl, String libUrl)
    {
        return new AsrRuntimeManager(runtimeDir, modelUrl, libUrl);
    }

    //* 仅有模型侧 (含指定标志目录) 的运行时: 动态库缺失, 用于就绪态负例.
    private static Path modelOnlyRuntime(Path tempDir, String marker) throws IOException
    {
        final var runtime = tempDir.resolve("runtime");
        Files.createDirectories(runtime.resolve("model").resolve(MODEL_DIR_NAME).resolve(marker));
        return runtime;
    }

    //* 覆盖 0.3.45 JAR 全部平台 entry 的 fixture 内容: libvosk 本体三平台占位 + Windows MinGW 伴生 DLL.
    //* 内容值 {3}..{8} 供期望值按平台 entry 推导, 断言不得写死任一平台的内容.
    private static Map<String, byte[]> fullLibEntries()
    {
        final var entries = new LinkedHashMap<String, byte[]>();
        entries.put("win32-x86-64/libvosk.dll", new byte[] {3});
        entries.put("win32-x86-64/libstdc++-6.dll", new byte[] {4});
        entries.put("win32-x86-64/libgcc_s_seh-1.dll", new byte[] {5});
        entries.put("win32-x86-64/libwinpthread-1.dll", new byte[] {6});
        entries.put("linux-x86-64/libvosk.so", new byte[] {7});
        entries.put("darwin/libvosk.dylib", new byte[] {8});
        return entries;
    }

    //* libvosk JAR fixture (与真实 JAR 同构, MinGW 伴生 DLL 仅 win entry 携带).
    private static byte[] fullLibJar() throws IOException
    {
        return zipOf(fullLibEntries());
    }

    //* 当前平台在 fixture JAR 内的 libvosk 期望内容: entry 目录与文件名均经生产同源平台解析推导.
    private static byte[] expectedPlatformLibBytes()
    {
        final var entryKey = AsrRuntimeManager.platformEntryDir(System.getProperty("os.name"), System.getProperty("os.arch"))
            + "/" + AsrRuntimeManager.nativeLibFileName(System.getProperty("os.name"));
        return fullLibEntries().get(entryKey);
    }

    //* 完整运行时: am/ + conf/ + 平台动态库占位文件 (manager 只探测存在性, 不真加载).
    //* 库名经生产同源的 nativeLibFileName 推导: CI (Linux) 与本机 (Windows) 夹具与 ready() 判定永远对齐.
    private static Path completeRuntime(Path tempDir) throws IOException
    {
        final var runtime = modelOnlyRuntime(tempDir, "am");
        Files.createDirectories(runtime.resolve("model").resolve(MODEL_DIR_NAME).resolve("conf"));
        Files.createDirectories(runtime.resolve("lib"));
        Files.write(runtime.resolve("lib").resolve(AsrRuntimeManager.nativeLibFileName(System.getProperty("os.name"))), new byte[] {1});
        return runtime;
    }

    private static byte[] zipOf(Map<String, byte[]> entries) throws IOException
    {
        final var bytes = new ByteArrayOutputStream();
        try(var zip = new ZipOutputStream(bytes))
        {
            for(final var entry : entries.entrySet())
            {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    //* 穿透 CompletionException/RuntimeException 包装取根因消息.
    private static String failureMessage(Throwable failure)
    {
        var current = failure;
        while(current.getCause() != null && current.getCause() != current)
            current = current.getCause();
        return current.getMessage() == null ? "" : current.getMessage();
    }

    private static List<String> describe(List<int[]> calls)
    {
        final var out = new ArrayList<String>();
        for(final var pair : calls)
            out.add("(" + pair[0] + ", " + pair[1] + ")");
        return out;
    }

    /**
     * <b>回环 HTTP fixture 服务器</b>
     * <p>用 JDK 内置 HttpServer 以真实 HTTP 通路驱动 manager 的 HttpClient 下载路径
     * (HttpClient 不支持 file://, 回环服务器即可覆盖进度回调与状态码分支, 又不触外网).</p>
     */
    private static final class ZipServer implements AutoCloseable
    {
        private final HttpServer server;
        private final CountDownLatch hold;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final int status;
        private final byte[] payload;
        private volatile int requestCount;

        ZipServer(byte[] payload, int status, CountDownLatch hold) throws IOException
        {
            this.payload = payload;
            this.status = status;
            this.hold = hold;
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange ->
            {
                requestCount++;
                entered.countDown();
                try(exchange)
                {
                    if(status != 200)
                    {
                        exchange.sendResponseHeaders(status, -1);
                        return;
                    }
                    Objects.requireNonNull(payload, "200 响应必须携带载荷");
                    exchange.sendResponseHeaders(200, payload.length);
                    try(var out = exchange.getResponseBody())
                    {
                        out.write(payload);
                    }
                }
                finally
                {
                    final var gate = hold;
                    if(gate != null)
                        try
                        {
                            gate.await();
                        }
                        catch(InterruptedException e)
                        {
                            Thread.currentThread().interrupt();
                        }
                }
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/fixture"; }

        int requests() { return requestCount; }

        void awaitEntered() throws InterruptedException
        {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "服务器未收到请求");
        }

        @Override public void close() { server.stop(0); }
    }

    /**
     * <b>可记录响应体的 fake {@link HttpClient}</b>
     * <p>绕过真实网络栈直接回送固定状态码与载荷, 以 {@link RecordingInputStream} 观测
     * manager 是否关闭响应体 (资源契约无法从 HttpServer 侧可靠观测).</p>
     * <p>JDK 21 起 HttpClient 仅 send/sendAsync 为抽象方法, 其余访问器走默认实现.</p>
     */
    private static final class RecordingClient extends HttpClient
    {
        private final int status;
        private final byte[] payload;
        private RecordingInputStream lastStream;

        RecordingClient(int status, byte[] payload)
        {
            this.status = status;
            this.payload = payload;
        }

        <T> HttpResponse<T> response()
        {
            lastStream = new RecordingInputStream(payload);
            return new HttpResponse<>()
            {
                @Override public int statusCode() { return status; }
                @Override public HttpRequest request() { return HttpRequest.newBuilder(URI.create("http://fixture/")).build(); }
                @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
                @Override @SuppressWarnings("unchecked") public T body() { return (T) lastStream; }
                @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return URI.create("http://fixture/"); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }

        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        {
            return response();
        }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        {
            return CompletableFuture.completedFuture(response());
        }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushHandler)
        {
            return CompletableFuture.completedFuture(response());
        }

        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }

        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(5)); }

        @Override public Redirect followRedirects() { return Redirect.NORMAL; }

        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }

        @SuppressWarnings("ConstantConditions")//! fake 永不触达 TLS 层, 返回 null 即可.
        @Override public SSLContext sslContext() { return null; }

        @SuppressWarnings("ConstantConditions")//! fake 永不触达 TLS 层, 返回 null 即可.
        @Override public SSLParameters sslParameters() { return null; }

        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }

        @Override public Version version() { return Version.HTTP_1_1; }
    }

    //* 观测 close() 的响应体流: manager 泄漏时 closed 保持 false, 用例据此钉死资源契约.
    private static final class RecordingInputStream extends FilterInputStream
    {
        boolean closed;

        RecordingInputStream(byte[] data) { super(new ByteArrayInputStream(data)); }

        @Override public void close() throws IOException
        {
            closed = true;
            super.close();
        }
    }

    //endregion
}
