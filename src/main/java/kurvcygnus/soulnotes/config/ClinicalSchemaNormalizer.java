package kurvcygnus.soulnotes.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.utils.PrintUtils;
import kurvcygnus.soulnotes.utils.constants.AiPromptConstants;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * <b>临床结构定义归一化器 (LLM 归一化 + 指纹缓存)</b>
 * <p>用户可配置的自然语言结构描述结构不稳定, 不允许直接进入对话契约; 本类在启动时异步将其
 * 归一化为严格 JSON Schema 并按 SHA-256 指纹持久缓存, 之后对话契约一律使用缓存内的标准结构.</p>
 * <ul>
 *     <li>默认结构 (canonical) 免归一: 零 LLM 调用, 回滚即永久稳定</li>
 *     <li>缓存: L1 {@link ConcurrentHashMap} + 工作目录 {@code config/clinical-schema-cache.json}
 *         多条目 (按归一时间保留最近 5 条), 自定义 A -> 默认 -> A 回滚可命中 A 缓存</li>
 *     <li>降级: 归一化失败 (LLM 异常或产物非法) 时不让不稳定结构上线 — 缓存未命中期间
 *         结构化输出增强暂禁, 仅发基础提示词, 等下次启动重试</li>
 * </ul>
 * @since 2.0
 */
@ApplicationScoped
@SuppressWarnings("unused")//! @Inject 构造器由 Quarkus CDI 容器在运行时调用, IDE 静态分析误报未使用 (ChatService 同款处理).
public final class ClinicalSchemaNormalizer
{
    private static final Logger LOG = LoggerFactory.getLogger(ClinicalSchemaNormalizer.class);

    //* 单例复用 (HttpModelCatalog 先例): 连接池/线程复用; 归一化为启动期一次性调用, 读超时放宽到 60s.
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    //* Jackson 直用而非 JsonUtils 静态桥: 归一化器可能先于 JsonUtils 的 @Startup 初始化被单测直接构造.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    //* 缓存容量: 指纹多条目设计支持配置来回切换, 5 条足够覆盖常用组合且文件保持可读.
    private static final int MAX_ENTRIES = 5;

    //* 归一化系统提示词: 要求只输出 JSON, 形态校验 (顶层 object + properties) 与该约束配套.
    private static final @NotNull String NORMALIZATION_SYSTEM_PROMPT = """
        你是 JSON Schema 转换器. 将用户提供的自然语言结构描述转换为严格的 JSON Schema (draft 2020-12 形态).
        要求:
        1. 顶层必须为 type = object 的对象, 并包含 properties 与 required.
        2. properties 中每个属性给出 type 与 description, description 忠实转写用户描述中的语义, 不得增删或曲解.
        3. 只输出 JSON 本身, 不要输出任何解释文字, 也不要使用 Markdown 代码围栏.
        """;

    //region 缓存文件模型
    private record CacheEntry(String promptHash, String schema, String normalizedAt, String model)
    {}

    private record CacheFile(List<CacheEntry> entries)
    {}
    //endregion

    //region 注入
    private final @NotNull PromptProvider promptProvider;
    private final @NotNull String baseUrl;
    private final @NotNull String apiKey;
    private final @NotNull String modelName;
    private final @NotNull Path cacheFile;
    //* LLM 调用替身: 生产为 null (走内置 HTTP), 单测注入可控函数; 形参为 String (归一化提示词), 返回 LLM 原文.
    private final @Nullable Function<String, String> llmCallStub;
    private final @NotNull ConcurrentHashMap<String, String> l1 = new ConcurrentHashMap<>();

    //* 三个 LangChain4j 桥接键可能空串或缺省: 一律 Optional, 空缺时归一化调用自然失败走降级, 不阻断启动.
    @Inject
    public ClinicalSchemaNormalizer(
        @NotNull PromptProvider promptProvider,
        @ConfigProperty(name = "quarkus.langchain4j.openai.base-url") @NotNull Optional<String> baseUrl,
        @ConfigProperty(name = "quarkus.langchain4j.openai.api-key") @NotNull Optional<String> apiKey,
        @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name") @NotNull Optional<String> modelName
    )
    {
        this(promptProvider, baseUrl.orElse(""), apiKey.orElse(""), modelName.orElse(""), Path.of("config", "clinical-schema-cache.json"), null);
    }

    //* 可测性构造: 缓存文件路径与 LLM 替身均可注入 (ChatServiceTest 跨包使用, 故为 public).
    public ClinicalSchemaNormalizer(
        @NotNull PromptProvider promptProvider,
        @NotNull String baseUrl,
        @NotNull String apiKey,
        @NotNull String modelName,
        @NotNull Path cacheFile,
        @Nullable Function<String, String> llmCall
    )
    {
        this.promptProvider = promptProvider;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.cacheFile = cacheFile;
        this.llmCallStub = llmCall;
        loadCacheFromFile();
    }

    //* 启动钩子: 异步预归一化, 不阻塞启动; 失败仅 WARN, 契约侧经缓存未命中兜底.
    //! SmallRye Config 按 TCCL (线程上下文类加载器) 解析: dev 模式配置挂在 Quarkus 运行时类加载器上,
    //! commonPool 工作线程的 TCCL 是系统类加载器 — 归一化链路上懒实例化 bean 的配置注入在工作线程
    //! 首查配置即抛 SRCFG00015, 故必须捕获启动线程 TCCL 换装到异步体, finally 恢复防污染池化线程.
    void onStart(@Observes @NotNull StartupEvent ev)
    {
        final var startupClassLoader = Thread.currentThread().getContextClassLoader();
        CompletableFuture.runAsync(() ->
        {
            final var worker = Thread.currentThread();
            final var previous = worker.getContextClassLoader();
            worker.setContextClassLoader(startupClassLoader);
            try
            {
                if(ensureNormalized() == null)
                    LOG.warn("启动期临床结构归一化未成功 (LLM 失败且无缓存), 结构化输出增强暂禁, 等下次启动重试");
            }
            catch(RuntimeException e)
            {
                LOG.warn("启动期临床结构归一化异常: {}", e.getMessage());
            }
            finally
            {
                worker.setContextClassLoader(previous);
            }
        });
    }
    //endregion

    //region 公开 API
    /**
     * <span style="color: 95cc6d">确保有效结构定义已归一化并返回标准结构文本.</span>
     * <p>默认 canonical 结构免归一直接返回 (零 LLM 调用); 自定义结构按 SHA-256 指纹查缓存,
     * 未命中时调用 LLM 归一化, 产物经形态校验 (顶层 object 且含 properties), 失败重试一次,
     * 两次失败返回 {@code null} 交由调用方降级.</p>
     *
     * @return 标准结构文本 (DEFAULT 常量, 归一化 JSON Schema 或缓存命中值); 归一化失败时为 {@code null}
     */
    public synchronized @Nullable String ensureNormalized()
    {
        final var effective = promptProvider.clinicalSchema();
        if(AiPromptConstants.CLINICAL_OUTPUT_SCHEMA_DEFAULT.equals(effective))
            return effective;//* canonical 免归一: 永久稳定, 零成本.
        final var hash = sha256Hex(effective);
        final var cached = l1.get(hash);
        if(cached != null)
            return cached;
        for(int attempt = 1; attempt <= 2; attempt++)
        {
            try
            {
                final var schema = validateSchemaText(callLlm(buildNormalizationPrompt(effective)));
                if(schema != null)
                {
                    persistEntry(hash, schema);
                    return schema;
                }
                LOG.warn("归一化产物形态校验未通过 (第 {} 次): 顶层必须是含 properties 的 JSON 对象", attempt);
            }
            catch(RuntimeException e)
            {
                LOG.warn("归一化 LLM 调用失败 (第 {} 次): {}", attempt, e.getMessage());
            }
        }
        return null;
    }

    /**
     * <span style="color: 95cc6d">按指纹查询缓存 (不触发 LLM).</span>
     * <p>对话契约侧的唯一取数入口: 仅读 L1 (构造时已从缓存文件加载), 保证不稳定结构永不直接上线.</p>
     *
     * @param hash 有效结构描述的 SHA-256 指纹
     * @return 归一化后的结构文本; 未命中为 {@code null}
     */
    public @Nullable String cachedFor(@NotNull String hash) { return l1.get(hash); }

    /**
     * <span style="color: 95cc6d">计算 SHA-256 指纹 (小写十六进制).</span>
     *
     * @param text 待指纹化文本
     * @return 64 位十六进制指纹
     */
    public static @NotNull String sha256Hex(@NotNull String text)
    {
        Objects.requireNonNull(text, "Param \"text\" must not be null!");
        try
        {
            final var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch(NoSuchAlgorithmException e)
        {
            //* JDK 规范强制每个 Java 平台实现支持 SHA-256, 此分支理论不可达; 抛出以兑现 @NotNull 契约.
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }
    //endregion

    //region 归一化流水线
    private @NotNull String callLlm(@NotNull String prompt)
    {
        if(llmCallStub != null)
            return llmCallStub.apply(prompt);
        return callLlmHttp(prompt);
    }

    //* OpenAI 兼容 chat/completions 直调 (HttpModelCatalog 先例): 归一化是一次性启动期调用,
    //* 不引入 langchain4j 编程模型, 失败统一以 RuntimeException 收场交重试/降级路径.
    private @NotNull String callLlmHttp(@NotNull String prompt)
    {
        final var request = HttpRequest.newBuilder(URI.create(stripTrailingSlash(baseUrl) + "/chat/completions"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(prompt), StandardCharsets.UTF_8))
            .build();
        final HttpResponse<String> response;
        try { response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString()); }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();  //* 恢复中断标记后按调用失败收场, 交重试/降级路径.
            throw new IllegalStateException("归一化请求被中断", e);
        }
        catch(IOException e) { throw new IllegalStateException(PrintUtils.quickFormat("归一化请求失败: {}", e.getMessage()), e); }
        if(response.statusCode() != 200)
            throw new IllegalStateException(PrintUtils.quickFormat("归一化请求 HTTP {}", response.statusCode()));
        final var content = extractContent(response.body());
        if(content.isBlank())
            throw new IllegalStateException("归一化响应缺少正文内容");
        return content;
    }

    private @NotNull String buildRequestBody(@NotNull String userPrompt)
    {
        final var root = MAPPER.createObjectNode();
        root.put("model", modelName);
        root.put("temperature", 0.0);
        final var messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", NORMALIZATION_SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", userPrompt);
        return root.toString();
    }

    private static @NotNull String extractContent(@NotNull String body)
    {
        try { return MAPPER.readTree(body).path("choices").path(0).path("message").path("content").asText(""); }
        catch(JsonProcessingException e) { throw new IllegalStateException("归一化响应不是合法 JSON", e); }
    }

    private static @NotNull String buildNormalizationPrompt(@NotNull String effective)
    {
        return NORMALIZATION_SYSTEM_PROMPT + "\n用户提供的结构描述如下:\n" + effective;
    }

    //* 形态校验: 必须解析为 JSON 对象且含 properties (JSON Schema 形态的最低门槛);
    //* 宽容剥除 LLM 惯性附带的 Markdown 代码围栏后再校验, 围栏内仍是合法对象即采纳.
    private static @Nullable String validateSchemaText(@NotNull String raw)
    {
        final var text = stripCodeFence(raw.strip());
        try
        {
            final var node = MAPPER.readTree(text);
            if(node instanceof ObjectNode object && object.has("properties"))
                return object.toString();//* 统一为 Jackson 序列化形态, 同一产物多次落盘字节稳定.
        }
        catch(JsonProcessingException e)
        {
            LOG.debug("归一化产物 JSON 解析失败: {}", e.getMessage());
        }
        return null;
    }

    //* 仅当整体被围栏包裹时剥壳: 首行 ``` 开头 + 末尾 ``` 收尾, 中段原样保留交 JSON 解析判定.
    private static @NotNull String stripCodeFence(@NotNull String text)
    {
        if(!text.startsWith("```"))
            return text;
        final var firstNewline = text.indexOf('\n');
        if(firstNewline < 0)
            return text;
        final var body = text.substring(firstNewline + 1);
        final var fenceEnd = body.lastIndexOf("```");
        return fenceEnd >= 0 ? body.substring(0, fenceEnd) : body;
    }

    private static @NotNull String stripTrailingSlash(@NotNull String url)
    {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
    //endregion

    //region 缓存文件
    //* 构造期从工作目录缓存文件恢复 L1: 重启与回滚场景的命中前提.
    private void loadCacheFromFile()
    {
        for(final var entry : readEntries())
        {
            if(entry.promptHash() == null || entry.promptHash().isBlank() || entry.schema() == null || entry.schema().isBlank())
                continue;//* 从宽: 单条目字段残缺只跳过该条, 不废整个缓存.
            l1.put(entry.promptHash(), entry.schema());
        }
    }

    private @NotNull List<CacheEntry> readEntries()
    {
        if(!Files.isRegularFile(cacheFile))
            return List.of();
        try
        {
            final var file = MAPPER.readValue(cacheFile.toFile(), CacheFile.class);
            return file.entries() == null ? List.of() : file.entries();
        }
        catch(IOException e)
        {
            LOG.warn("临床结构缓存文件损坏, 忽略并从头累积: {}", e.getMessage());
            return List.of();
        }
    }

    //* L1 先行更新 (读路径立即可见), 文件写入失败仅 WARN — 缓存是性能优化, 丢失只影响下次启动的命中率.
    private void persistEntry(@NotNull String hash, @NotNull String schema)
    {
        l1.put(hash, schema);
        try
        {
            final var entries = new ArrayList<>(readEntries());
            entries.removeIf(entry -> hash.equals(entry.promptHash()));
            entries.add(new CacheEntry(hash, schema, Instant.now().toString(), modelName));
            entries.sort(Comparator.comparing(ClinicalSchemaNormalizer.CacheEntry::normalizedAt));
            while(entries.size() > MAX_ENTRIES)
                entries.removeFirst();
            final var parent = cacheFile.toAbsolutePath().getParent();
            if(parent != null)
                Files.createDirectories(parent);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), new CacheFile(List.copyOf(entries)));
        }
        catch(IOException e)
        {
            LOG.warn("临床结构缓存文件写入失败 (L1 已更新): {}", e.getMessage());
        }
    }
    //endregion
}
