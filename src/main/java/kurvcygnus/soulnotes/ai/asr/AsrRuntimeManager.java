package kurvcygnus.soulnotes.ai.asr;

import kurvcygnus.soulnotes.utils.PrintUtils;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * ASR 运行时管理器: 就绪检测 + 模型与原生库自动下载.
 * <p>运行时目录布局的单一权威 (引擎 {@link VoskAsrEngine} 的路径/就绪态判定全部委托本类):
 * <pre>
 *   &lt;runtime.dir&gt;/
 *   ├── lib/   libvosk.(dll|so|dylib) + Windows MinGW 伴生 DLL (libstdc++-6 等, 须同目录)
 *   └── model/ &lt;模型目录&gt;/ (含 am/ 或 conf/ 即视为完整)
 * </pre></p>
 *
 * <p>布局契约: 模型 zip (顶层一层 vosk-model-small-cn-0.22/) 解压到
 * {@code <runtime.dir>/model/} 之下, 引擎既有的 model/&lt;子目录&gt; 解析无需改变.</p>
 *
 * <p>libvosk 来源为 Maven Central 官方 JAR (repo1.maven.org 境内直连超时, 默认走 aliyun 镜像):
 * 该 URL 是 "JAR 地址" 而非动态库直链, 下载后按平台 entry (win32-x86-64/libvosk.dll 等) 提取;
 * {@code SOULNOTES_ASR_LIB_URL} 可整体覆盖 JAR 地址 (如指向含 arm 构建的更新版本 JAR).</p>
 *
 * <p>下载/解压全部在 worker 池执行, 失败包装为 Uni 失败由调用方决定 UI 呈现;
 * 半成品清理策略: 临时文件先落 {@code .part}, 成功后原子 move, 任何失败即刻删除临时物;
 * 模型先解压到 model/ 下暂存目录再落位, 防止半解压目录被 am/ 标志误判为完整模型.</p>
 * @since 1.1.0
 */
@ApplicationScoped
public final class AsrRuntimeManager implements IAsrRuntimeControl
{
    private static final Logger LOG = LoggerFactory.getLogger(AsrRuntimeManager.class);

    //* 官方 small 中文模型 (16kHz, ~42MB, zip 顶层一层 vosk-model-small-cn-0.22/).
    public static final String DEFAULT_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip";

    //* libvosk 0.3.45 官方 JAR (aliyun 镜像默认; repo1.maven.org 境内直连超时).
    public static final String DEFAULT_LIB_JAR_URL = "https://maven.aliyun.com/repository/central/com/alphacephei/vosk/0.3.45/vosk-0.3.45.jar";

    //* 与 DEFAULT_LIB_JAR_URL 的 0.3.45 版本一致的模型 API 版本 (JAR 内动态库即此版本).
    private static final String VOSK_VERSION = "0.3.45";

    //* HttpClient 仅握手限时 30s; 不设整体 request 超时: 官方源对单连接限速可达 ~15KB/s,
    //* 42MB 模型串行下载可达小时级, 整体超时会误杀合法慢下载.
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().
        connectTimeout(Duration.ofSeconds(30)).
        followRedirects(HttpClient.Redirect.NORMAL).
        build();

    //* 下载流拷贝缓冲: 16KB 粒度回调一次, 42MB 约 2700 次回调, 足够向导逐行刷新又不至于刷屏.
    private static final int DOWNLOAD_BUFFER_BYTES = 16 * 1024;

    //* Windows 版 libvosk.dll 为 MinGW 构建, 以下伴生运行时 DLL 必须与其同目录.
    private static final String MIN_GW_ENTRY_DIR = "win32-x86-64";

    //region 状态

    private final @NotNull Path runtimeDir;
    private final @NotNull String modelUrl;
    private final @NotNull String libJarUrl;
    private final @NotNull HttpClient httpClient; //* 测试注入 fake 以观测响应体关闭; 生产用 HTTP_CLIENT

    //* 下载互斥: 并发第二路直接失败而非排队, 由向导层保证串行触发 (阻断下载到 worker 池排队会占死池线程).
    private final @NotNull AtomicBoolean downloading = new AtomicBoolean(false);

    //endregion

    /**
     * CDI 构造入口.
     * @param runtimeDir 运行时目录 (SOULNOTES_ASR_RUNTIME_DIR)
     * @param libUrl     libvosk JAR 下载地址 (SOULNOTES_ASR_LIB_URL, 注意是 JAR 地址而非动态库直链)
     */
    @Inject
    public AsrRuntimeManager(
        @ConfigProperty(name = "asr.runtime.dir", defaultValue = "asr-model") @NotNull String runtimeDir,
        @ConfigProperty(name = "asr.lib.url", defaultValue = DEFAULT_LIB_JAR_URL) @NotNull Optional<String> libUrl
    ) { this(Path.of(runtimeDir), DEFAULT_MODEL_URL, resolveLibUrl(libUrl.orElse("")), HTTP_CLIENT); }

    /**
     * libvosk JAR 地址归一: 空/空白回落到内置默认 (阿里云镜像 0.3.45).
     * <p>properties 侧 asr.lib.url 默认值为空串 (向导展示友好): CDI 侧以 Optional 接住 "定义但为空"
     * 的键 (plain String 注入遇空值启动即 ConfigurationException), 空值归一在此兜底,
     * 兑现向导 "留空则默认" 的语义; Pre-Launch 装配 (Entrance) 与 CDI 构造共用本方法, 保证单一来源.</p>
     * @param configured 显式配置值 (SOULNOTES_ASR_LIB_URL / 向导落盘值 / @ConfigProperty 注入值)
     * @return 可直接用于下载的 JAR 地址
     */
    public static @NotNull String resolveLibUrl(@NotNull String configured) { return configured.isBlank() ? DEFAULT_LIB_JAR_URL : configured; }

    /**
     * 纯构造入口 (测试/引擎内嵌/Pre-Launch).
     * <p>模型地址作为参数注入以支持回环服务器用例; Pre-Launch 阶段 (Entrance) CDI 容器尚未启动,
     * 校验任务与向导只能以本构造纯装配一个实例, 生产常量 {@link #DEFAULT_MODEL_URL}.</p>
     */
    public AsrRuntimeManager(@NotNull Path runtimeDir, @NotNull String modelUrl, @NotNull String libUrl) { this(runtimeDir, modelUrl, libUrl, HTTP_CLIENT); }

    /**
     * 全参数测试构造入口.
     * <p>HttpClient 可注入: 单元测试以 fake 观测响应体关闭等资源契约, 无需真实网络栈.</p>
     *
     * @param runtimeDir 运行时目录
     * @param modelUrl 模型 zip 下载地址
     * @param libUrl libvosk JAR 下载地址
     * @param httpClient 下载用客户端
     */
    AsrRuntimeManager(
        @NotNull Path runtimeDir,
        @NotNull String modelUrl,
        @NotNull String libUrl,
        @NotNull HttpClient httpClient
    )
    {
        Objects.requireNonNull(runtimeDir, "Param \"runtimeDir\" must not be null!");
        Objects.requireNonNull(modelUrl, "Param \"modelUrl\" must not be null!");
        Objects.requireNonNull(libUrl, "Param \"libUrl\" must not be null!");
        Objects.requireNonNull(httpClient, "Param \"httpClient\" must not be null!");
        this.runtimeDir = runtimeDir;
        this.modelUrl = modelUrl;
        this.libJarUrl = libUrl;
        this.httpClient = httpClient;
    }

    //region 就绪态与路径

    /**
     * 运行时是否就绪 (模型完整 + 平台动态库存在).
     * <p>阻塞 IO, 引擎侧已在 worker 池内调用; 模型完整性以任一子目录含 am/ 或 conf/ 标志目录判定.</p>
     * @return true = 可直接进入识别
     */
    @Override public boolean ready() { return hasModelMarker() && Files.isRegularFile(nativeLib()); }

    /**
     * 解析完整模型目录 (model/ 下第一个含标志目录的子目录, sorted).
     * @return 模型目录绝对路径 (相对 cwd 解析)
     * @throws IllegalStateException 无完整模型子目录或读取失败
     */
    public @NotNull Path modelDir()
    {
        final var modelRoot = runtimeDir.resolve("model");
        try(Stream<Path> entries = Files.list(modelRoot))
        {
            final var found = entries.
                filter(Files::isDirectory).
                filter(AsrRuntimeManager::hasMarker).
                sorted().
                findFirst().
                orElse(null);
            if(found != null)
                return found;
        }
        catch(IOException e) { throw new IllegalStateException(PrintUtils.quickFormat("读取模型目录失败: {} ({})", modelRoot, e.getMessage()), e); }
        throw new IllegalStateException(PrintUtils.quickFormat("模型目录下没有完整模型子目录 (需含 am/ 或 conf/): {}", modelRoot));
    }

    /**
     * 平台动态库路径 (lib/libvosk.(dll|so|dylib), 按 os.name 三态).
     *
     * @return 动态库期望路径; 文件未必已存在, 存在性由 {@link #ready()} 判定
     */
    public @NotNull Path nativeLib() { return runtimeDir.resolve("lib").resolve(nativeLibFileName(System.getProperty("os.name", ""))); }

    /**
     * 平台动态库文件名解析 (按 os.name 三态: 含 mac/darwin -> .dylib, 含 win -> .dll, 其余 -> .so).
     * <p>包内单一来源: 生产 {@link #nativeLib()} 与测试夹具共用, 夹具据此生成期望文件名,
     * 禁止测试另行写死平台名; 写死 dll 曾使 Linux CI 上夹具与生产解析错位, ready() 误判引发连环失败.</p>
     *
     * @param osName 操作系统名 (System.getProperty("os.name") 语义)
     * @return 动态库文件名 (libvosk.dll / libvosk.dylib / libvosk.so)
     */
    static @NotNull String nativeLibFileName(@NotNull String osName)
    {
        final var os = osName.toLowerCase(Locale.ROOT);
        //! "darwin" 字面含 "win": win 判定若前置, darwin 分支永不可达且 darwin 平台会误取 .dll.
        return os.contains("mac") || os.contains("darwin") ? "libvosk.dylib" :
               os.contains("win") ? "libvosk.dll" :
               "libvosk.so";
    }

    /**
     * 运行时目录 (引擎未就绪文案的诊断输出用).
     *
     * @return 构造时给定的运行时目录
     */
    public @NotNull Path runtimeDir() { return runtimeDir; }

    /**
     * libvosk JAR 下载地址 (下载源诊断与 Pre-Launch 装配断言用).
     *
     * @return 已归一的 JAR 地址 (空白配置经 {@link #resolveLibUrl} 回落默认)
     */
    public @NotNull String libJarUrl() { return libJarUrl; }

    //endregion

    //region 下载与安装

    /**
     * 确保运行时就绪: 缺模型补模型 zip, 缺动态库补 JAR 提取.
     * <p>就绪即直接完成, 不发任何请求; 进度回调按资产分阶段触发 (模型 zip 下载与 JAR 下载各一段),
     * 参数为 (已接收字节, 总字节, Content-Length 缺失时为 -1), 在 worker 线程回调, 须快速返回.</p>
     * <p>并发调用: 已有下载进行中时第二路 Uni 直接失败 (IllegalStateException), 不排队.</p>
     * @param progress 进度回调 (可为 null)
     * @return 完成信号; 失败时 Uni 以 IOException/IllegalStateException 失败, 半成品已清理
     */
    @Override public @NotNull Uni<Void> ensureDownloaded(@Nullable BiConsumer<Integer, Integer> progress)
    {
        //* 先 runSubscriptionOn 再 chain: 阻塞下载体在 worker 池执行, 订阅者线程零 IO;
        //* IOException 经 Uni.createFrom().failure 原样透传 (不二次包装), 调用方可按类型分支.
        return Uni.createFrom().voidItem().
            runSubscriptionOn(Infrastructure.getDefaultWorkerPool()).
            chain(
                () ->
                {
                    try
                    {
                        ensureDownloadedBlocking(progress);
                        return Uni.createFrom().voidItem();
                    }
                    catch(IOException e) { return Uni.createFrom().failure(e); }
                }
            );
    }

    private void ensureDownloadedBlocking(@Nullable BiConsumer<Integer, Integer> progress) throws IOException
    {
        if(!downloading.compareAndSet(false, true))
            throw new IllegalStateException("已有 ASR 运行时下载任务进行中, 请等待其完成");
        try
        {
            if(ready())
                return;
            ensureModelInstalled(progress);
            ensureLibInstalled(progress);
            if(!ready())
                throw new IllegalStateException(PrintUtils.quickFormat("下载完成但运行时仍不就绪 (布局异常): {}", runtimeDir));
        }
        finally { downloading.set(false); }
    }

    private void ensureModelInstalled(@Nullable BiConsumer<Integer, Integer> progress) throws IOException
    {
        if(hasModelMarker())
            return;
        final var modelRoot = runtimeDir.resolve("model");
        Files.createDirectories(modelRoot);
        final var zipFile = downloadToTemp(modelUrl, progress);
        final var staging = Files.createTempDirectory(modelRoot, ".staging-");
        try
        {
            extractZip(zipFile, staging);
            //* 暂存区整体落位: 走到这里的运行时必然无任何完整模型 (hasModelMarker 为 false),
            //* 故同名旧目录只可能是上次失败残留, 先删后移即为安全的替换语义.
            try(Stream<Path> staged = Files.list(staging))
            {
                for(final var child: staged.filter(Files::isDirectory).sorted().toList())
                {
                    final var target = modelRoot.resolve(child.getFileName());
                    deleteRecursively(target);
                    Files.move(child, target);
                }
            }
            LOG.info("ASR 模型安装完成: {}", modelRoot);
        }
        finally
        {
            deleteRecursively(staging);
            Files.deleteIfExists(zipFile);
        }
    }

    private void ensureLibInstalled(@Nullable BiConsumer<Integer, Integer> progress) throws IOException
    {
        final var target = nativeLib();
        if(Files.isRegularFile(target))
            return;
        final var libDir = runtimeDir.resolve("lib");
        Files.createDirectories(libDir);
        final var libRoot = libDir.normalize();
        final var jarFile = downloadToTemp(libJarUrl, progress);
        try(var zip = new ZipFile(jarFile.toFile()))
        {
            final var entryDir = platformEntryDir(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
            if(entryDir == null)
                throw new IllegalStateException(PrintUtils.quickFormat(
                    "当前平台 ({}/{}) 在 vosk {} JAR 中无官方动态库构建, 可用 SOULNOTES_ASR_LIB_URL 指向含该平台构建的 JAR",
                    System.getProperty("os.name"), System.getProperty("os.arch"), VOSK_VERSION));
            var extracted = false;
            final var entries = zip.entries();
            while(entries.hasMoreElements())
            {
                final var entry = entries.nextElement();
                if(!entry.getName().replace('\\', '/').startsWith(entryDir + "/"))
                    continue;
                final var fileName = entry.getName().replace('\\', '/').substring(entryDir.length() + 1);
                //* 只提取动态库本体: .keep-me/empty 等占位文件无意义, 不落盘.
                if(!(fileName.endsWith(".dll") || fileName.endsWith(".so") || fileName.endsWith(".dylib")))
                    continue;
                final Path entryTarget;
                try { entryTarget = libRoot.resolve(fileName).normalize(); }
                catch(Exception e) { throw new IOException(PrintUtils.quickFormat("拒绝非法 JAR entry 名: \"{}\"", entry.getName()), e); }
                //! zip-slip 防护 (与 extractZip 同规): .dll 后缀过滤挡不住 "win32-x86-64/../../evil.dll"
                //! 式穿越, entry 规范化路径逃出 lib/ 即整体拒绝, 不落任何盘.
                if(!entryTarget.startsWith(libRoot))
                    throw new IOException(PrintUtils.quickFormat("拒绝 zip-slip 条目: \"{}\" (逃出 {})", entry.getName(), libRoot));
                extractLibEntry(zip, entry, entryTarget);
                extracted = extracted || fileName.equals(target.getFileName().toString());
            }
            if(!extracted)
                throw new IllegalStateException(PrintUtils.quickFormat("JAR 内无平台动态库 ({}/libvosk.*): {}", entryDir, libJarUrl));
            LOG.info("ASR 动态库安装完成: {}", libDir);
        }
        finally { Files.deleteIfExists(jarFile); }
    }

    //* 单个动态库 entry: 先写 .part 临时文件再原子 move, 失败不污染 lib/ 目录.
    private static void extractLibEntry(@NotNull ZipFile zip, @NotNull ZipEntry entry, @NotNull Path target) throws IOException
    {
        final var temp = Files.createTempFile(target.getParent(), ".lib-", ".part");
        try(var in = zip.getInputStream(entry))
        {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        finally { Files.deleteIfExists(temp); }
    }

    //* 下载到 runtime 目录内 .part 临时文件 (同卷保证后续 move 原子性), 失败即删.
    private @NotNull Path downloadToTemp(@NotNull String url, @Nullable BiConsumer<Integer, Integer> progress) throws IOException
    {
        Objects.requireNonNull(url, "Param \"url\" must not be null!");
        final var temp = Files.createTempFile(runtimeDir, ".download-", ".part");
        try
        {
            final var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            //! 响应体先于状态码检查进入 try-with-resources: ofInputStream 的 body 不关闭会占住
            //! 连接, 下载反复失败时泄漏堆积 (ofInputStream 的流由 BodySubscriber 异步灌入, 必须显式关闭).
            try(var in = response.body())
            {
                if(response.statusCode() != 200)
                    throw new IOException(PrintUtils.quickFormat("下载失败 HTTP {}: {}", response.statusCode(), url));
                final var total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                try(var out = Files.newOutputStream(temp))
                {
                    final var buffer = new byte[DOWNLOAD_BUFFER_BYTES];
                    int received = 0;
                    int read;
                    while((read = in.read(buffer)) != -1)
                    {
                        out.write(buffer, 0, read);
                        received += read;
                        if(progress != null)
                            progress.accept(received, (int) Math.min(total, Integer.MAX_VALUE));
                    }
                }
            }
            return temp;
        }
        catch(IOException | InterruptedException | IllegalArgumentException e)
        {
            deleteRecursively(temp);
            if(e instanceof InterruptedException)
                Thread.currentThread().interrupt();
            throw new IOException(PrintUtils.quickFormat("下载失败: {} ({})", url, e.getMessage()), e);
        }
    }

    //endregion

    //region zip 解压 (zip-slip 防护)

    /**
     * 逐 entry 流式解压到目标目录.
     * <p>CRC 完整性由 ZipInputStream 在每个 entry 读尽时自动校验, 不符抛 ZipException, 无需手工核验.</p>
     * <p>zip-slip 防护: entry 规范化路径必须落在目标目录内, "../" 穿越与绝对路径 entry 一律整体拒绝.</p>
     */
    private static void extractZip(@NotNull Path zipFile, @NotNull Path targetDir) throws IOException
    {
        try(var in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile))))
        {
            ZipEntry entry;
            while((entry = in.getNextEntry()) != null)
            {
                final Path target;
                try { target = targetDir.resolve(entry.getName()).normalize(); }
                catch(Exception e) { throw new IOException(PrintUtils.quickFormat("拒绝非法 zip entry 名: \"{}\"", entry.getName()), e); }
                if(!target.startsWith(targetDir))
                    throw new IOException(PrintUtils.quickFormat("拒绝 zip-slip 条目: \"{}\" (逃出 {})", entry.getName(), targetDir));
                if(entry.isDirectory())
                {
                    Files.createDirectories(target);
                    continue;
                }
                if(target.getParent() != null)
                    Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                in.closeEntry();
            }
        }
    }

    //endregion

    //region 平台探测

    /**
     * vosk 官方 JAR 内平台 entry 目录名 (0.3.45 实测布局).
     * <p>纯函数便于单测钉死映射表; 0.3.45 仅含 win32-x86-64 / linux-x86-64 / darwin, 无 arm 构建.</p>
     * @return entry 目录名 (如 "win32-x86-64"); 平台无官方构建时为 null
     */
    static @Nullable String platformEntryDir(@NotNull String osName, @NotNull String osArch)
    {
        final var os = osName.toLowerCase(Locale.ROOT);
        final var arch = normalizeArch(osArch);
        //! 与 nativeLibFileName 同理: "darwin" 字面含 "win", mac/darwin 判定必须前置, 否则 darwin 平台误入 win 分支.
        if(os.contains("mac") || os.contains("darwin"))
            return "x86-64".equals(arch) ? "darwin" : null;
        if(os.contains("win"))
            return "x86-64".equals(arch) ? MIN_GW_ENTRY_DIR : null;
        if(os.contains("linux"))
            return "x86-64".equals(arch) ? "linux-x86-64" : null;
        return null;
    }

    //* os.arch 归一化: Hotspot 历史值 amd64 与标准写法 x86_64 同指 x86-64, arm64 同指 aarch64.
    private static @NotNull String normalizeArch(@NotNull String osArch)
    {
        final var arch = osArch.toLowerCase(Locale.ROOT);
        return switch(arch)
        {
            case "amd64", "x86_64", "x86-64" -> "x86-64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
    }

    //endregion

    //region 内部工具

    //* 模型完整性: model/ 下任一子目录含 am/ 或 conf/ 即完整 (与真实模型布局 am/conf/graph/ivector 对齐).
    private boolean hasModelMarker()
    {
        final var modelRoot = runtimeDir.resolve("model");
        if(!Files.isDirectory(modelRoot))
            return false;
        try(Stream<Path> entries = Files.list(modelRoot)) { return entries.filter(Files::isDirectory).anyMatch(AsrRuntimeManager::hasMarker); }
        catch(IOException e) { return false; }
    }

    private static boolean hasMarker(@NotNull Path modelDir)
        { return Files.isDirectory(modelDir.resolve("am")) || Files.isDirectory(modelDir.resolve("conf")); }

    //* best-effort 清理: 仅用于暂存区/临时文件, 单文件失败不中断清理循环, 最终只告警.
    private static void deleteRecursively(@Nullable Path root)
    {
        if(root == null || !Files.exists(root))
            return;
        try(Stream<Path> stream = Files.walk(root))
        {
            for(final var path: stream.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
        catch(IOException e) { LOG.warn("ASR 运行时清理失败: {} ({})", root, e.getMessage()); }
    }

    //endregion
}
