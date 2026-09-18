package kurvcygnus.soulnotes.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import kurvcygnus.soulnotes.utils.constants.AiPromptConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link ClinicalSchemaNormalizer} 归一化与指纹缓存单元测试</b>
 * <p>LLM 调用经构造注入的替身函数注入, 缓存文件路径指向临时目录, 全程无网络依赖.</p>
 * @since 2.0
 */
class ClinicalSchemaNormalizerTest
{
    private static final String VALID_SCHEMA =
        "{\"type\": \"object\", \"properties\": {\"gad7\": {\"type\": \"integer\", \"description\": \"GAD-7 分数\"}}, \"required\": [\"gad7\"]}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path cacheFile;
    private final AtomicInteger llmCalls = new AtomicInteger();
    private Function<String, String> llmCall = prompt ->
    {
        Objects.requireNonNull(prompt);
        throw new AssertionError("不应触发 LLM 归一化调用");
    };

    @BeforeEach void setUp()
    {
        cacheFile = tempDir.resolve("clinical-schema-cache.json");
        llmCalls.set(0);
    }

    //region 测试基建
    private static PromptProvider newProvider(String schemaOverride)
    {
        return new PromptProvider(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.ofNullable(schemaOverride)
        );
    }

    //* 记录调用次数的固定应答替身: 全部归一化成功场景共用同一合法 JSON Schema 产物.
    private void stubValidSchemaResponse()
    {
        llmCall = prompt ->
        {
            Objects.requireNonNull(prompt);
            return VALID_SCHEMA;
        };
    }

    private ClinicalSchemaNormalizer newNormalizer(PromptProvider provider)
    {
        return new ClinicalSchemaNormalizer(provider, "http://localhost:0/v1", "test-key", "test-model", cacheFile, prompt ->
        {
            llmCalls.incrementAndGet();
            return llmCall.apply(prompt);
        });
    }
    //endregion

    //region 默认旁路
    @Test void ensureNormalized_DefaultSchema_BypassesLlmAndCache()
    {
        final var normalizer = newNormalizer(newProvider(null));

        assertEquals(AiPromptConstants.CLINICAL_OUTPUT_SCHEMA_DEFAULT, normalizer.ensureNormalized());
        assertEquals(0, llmCalls.get(), "默认 canonical 结构必须免归一, 零 LLM 调用");
        assertFalse(Files.exists(cacheFile), "默认旁路不得产生缓存文件");
    }
    //endregion

    //region 归一化成功与缓存
    @Test void ensureNormalized_LlmSuccess_PersistsCacheFileAndSeedsL1() throws Exception
    {
        stubValidSchemaResponse();
        final var normalizer = newNormalizer(newProvider("输出 gad7 分数"));
        final var hash = ClinicalSchemaNormalizer.sha256Hex("输出 gad7 分数");

        final var schema = normalizer.ensureNormalized();

        assertNotNull(schema);
        assertTrue(schema.contains("gad7"), "归一化产物必须来自 LLM 输出");
        assertTrue(Files.exists(cacheFile), "成功归一化必须落盘缓存文件");
        final var fileJson = Files.readString(cacheFile);
        assertTrue(fileJson.contains(hash), "缓存文件必须携带 SHA-256 指纹");
        assertTrue(fileJson.contains("test-model"), "缓存文件必须记录归一化所用模型");
        assertEquals(schema, normalizer.cachedFor(hash), "L1 必须命中同指纹");
        assertEquals(1, llmCalls.get());
    }

    @Test void ensureNormalized_SecondCallSameSchema_ZeroExtraLlmCalls()
    {
        stubValidSchemaResponse();
        final var normalizer = newNormalizer(newProvider("输出 gad7 分数"));

        normalizer.ensureNormalized();
        final var second = normalizer.ensureNormalized();

        assertNotNull(second);
        assertTrue(second.contains("gad7"));
        assertEquals(1, llmCalls.get(), "同指纹二次归一化必须命中缓存, 不得重复调用 LLM");
    }

    @Test void ensureNormalized_DifferentSchema_CallsLlmAgain()
    {
        stubValidSchemaResponse();
        final var normalizer = newNormalizer(newProvider("输出 gad7 分数"));
        normalizer.ensureNormalized();

        llmCalls.set(0);
        final var normalizerB = newNormalizer(newProvider("输出 phq9 分数"));
        normalizerB.ensureNormalized();

        assertEquals(1, llmCalls.get(), "不同指纹必须再次调用 LLM");
    }

    @Test void ensureNormalized_RollbackToCustomSchema_HitsCacheWithZeroLlmCalls()
    {
        stubValidSchemaResponse();
        final var schemaA = "输出 gad7 分数";

        final var normalizerA = newNormalizer(newProvider(schemaA));
        normalizerA.ensureNormalized();
        assertEquals(1, llmCalls.get());

        //* 回滚到默认: 免归一旁路, 不产生任何调用.
        final var normalizerDefault = newNormalizer(newProvider(null));
        assertEquals(AiPromptConstants.CLINICAL_OUTPUT_SCHEMA_DEFAULT, normalizerDefault.ensureNormalized());

        //* 再切回自定义 A: 命中 A 的指纹缓存, 零 LLM 调用.
        final var normalizerAgain = newNormalizer(newProvider(schemaA));
        final var again = normalizerAgain.ensureNormalized();

        assertNotNull(again);
        assertTrue(again.contains("gad7"));
        assertEquals(1, llmCalls.get(), "自定义 A -> 默认 -> 自定义 A 必须命中 A 缓存, 全程仅一次 LLM 调用");
    }
    //endregion

    //region 验证与重试
    @Test void ensureNormalized_InvalidLlmOutput_RetriesOnceThenDegradesToNull()
    {
        llmCall = prompt ->
        {
            Objects.requireNonNull(prompt);
            return "抱歉, 我无法输出 JSON";
        };
        final var normalizer = newNormalizer(newProvider("输出 gad7 分数"));

        assertNull(normalizer.ensureNormalized(), "两次归一化失败必须返回 null 交由调用方降级");
        assertEquals(2, llmCalls.get(), "非法输出必须恰好重试一次");
        assertFalse(Files.exists(cacheFile), "失败路径不得落盘");
    }

    @Test void ensureNormalized_LlmCallThrows_RetriesOnceThenDegradesToNull()
    {
        llmCall = prompt ->
        {
            Objects.requireNonNull(prompt);
            throw new RuntimeException("连接被拒");
        };
        final var normalizer = newNormalizer(newProvider("输出 gad7 分数"));

        assertNull(normalizer.ensureNormalized());
        assertEquals(2, llmCalls.get());
    }

    @Test void ensureNormalized_NonObjectJson_RejectedAsInvalid()
    {
        llmCall = prompt ->
        {
            Objects.requireNonNull(prompt);
            return "[1, 2, 3]";
        };
        final var normalizer = newNormalizer(newProvider("输出 gad7 分数"));

        assertNull(normalizer.ensureNormalized(), "JSON Schema 形态校验: 非顶层对象必须判为非法");
        assertEquals(2, llmCalls.get());
    }

    @Test void ensureNormalized_MissingProperties_RejectedAsInvalid()
    {
        llmCall = prompt ->
        {
            Objects.requireNonNull(prompt);
            return "{\"type\": \"object\"}";
        };
        final var normalizer = newNormalizer(newProvider("输出 gad7 分数"));

        assertNull(normalizer.ensureNormalized(), "缺少 properties 的产物不构成结构定义, 必须判为非法");
        assertEquals(2, llmCalls.get());
    }

    @Test void ensureNormalized_CodeFencedOutput_StrippedAndAccepted()
    {
        llmCall = prompt ->
        {
            Objects.requireNonNull(prompt);
            return kurvcygnus.soulnotes.utils.PrintUtils.quickFormat("```json\n{}\n```", VALID_SCHEMA);
        };
        final var normalizer = newNormalizer(newProvider("输出 gad7 分数"));

        final var schema = normalizer.ensureNormalized();

        assertNotNull(schema);
        assertTrue(schema.contains("gad7"), "合法 JSON 被 Markdown 围栏包裹时必须剥围栏后采纳");
        assertEquals(1, llmCalls.get());
    }
    //endregion

    //region 文件持久化
    @Test void cachedFor_RestartFromCacheFile_LoadsWithoutLlm()
    {
        stubValidSchemaResponse();
        final var first = newNormalizer(newProvider("输出 gad7 分数"));
        first.ensureNormalized();
        final var hash = ClinicalSchemaNormalizer.sha256Hex("输出 gad7 分数");
        llmCalls.set(0);

        //* 模拟重启: 全新实例 (零 LLM 调用替身) 仅从缓存文件恢复.
        final var restarted = newNormalizer(newProvider("输出 gad7 分数"));

        assertEquals(first.cachedFor(hash), restarted.cachedFor(hash), "重启后必须从缓存文件恢复同指纹条目");
        assertEquals(0, llmCalls.get(), "缓存文件加载不得触发 LLM");
        final var warmed = restarted.ensureNormalized();
        assertNotNull(warmed);
        assertTrue(warmed.contains("gad7"));
        assertEquals(0, llmCalls.get(), "重启后 ensureNormalized 同指纹必须纯缓存命中");
    }

    @Test void ensureNormalized_MoreThanFiveEntries_TrimsOldestToFive() throws Exception
    {
        stubValidSchemaResponse();
        final var hashes = new ArrayList<String>();
        for(int i = 0; i < 6; i++)
        {
            final var promptText = kurvcygnus.soulnotes.utils.PrintUtils.quickFormat("结构描述{}", i);
            hashes.add(ClinicalSchemaNormalizer.sha256Hex(promptText));
            newNormalizer(newProvider(promptText)).ensureNormalized();
        }

        final var restarted = newNormalizer(newProvider(null));
        assertNull(restarted.cachedFor(hashes.getFirst()), "超出容量最老的条目必须被裁剪");
        final var newest = restarted.cachedFor(hashes.getLast());
        assertNotNull(newest, "最新条目必须保留");
        assertTrue(newest.contains("gad7"), "保留的条目必须是归一化产物本体");
        assertEquals(5, MAPPER.readTree(cacheFile.toFile()).path("entries").size(), "缓存文件必须按归一时间保留最近 5 条");
    }
    //endregion

    //region 启动钩子跨线程 TCCL 传播
    //* 复现运行时 SRCFG00015 根因: SmallRye Config 按 TCCL (线程上下文类加载器) 解析配置, dev 模式
    //* 配置挂在 Quarkus 运行时类加载器上; 启动钩子若把归一化直接丢 commonPool, 工作线程丢失启动线程
    //* TCCL, 懒实例化 bean 的配置注入在工作线程上首查配置即失败, 缓存文件永不生成.
    //* 用独立标记类加载器钉死传播契约: 标记加载器不可能恰好是 commonPool 工作线程的 TCCL, 修复前必红.
    @SuppressWarnings("BusyWait")//! fire-and-forget 钩子无完成句柄 (替身在落盘前即放行闩锁), 有界轮询是等待落盘落 L1 的最直接方式.
    @Test void onStart_PropagatesStartupThreadTcclToAsyncNormalization() throws Exception
    {
        stubValidSchemaResponse();
        final var normalizer = newNormalizer(newProvider("输出 gad7 分数"));
        final var markerLoader = new URLClassLoader(new URL[0], Thread.currentThread().getContextClassLoader());
        final var observed = new AtomicReference<ClassLoader>();
        final var done = new CountDownLatch(1);
        llmCall = prompt ->
        {
            observed.set(Thread.currentThread().getContextClassLoader());
            Objects.requireNonNull(prompt);
            done.countDown();
            return VALID_SCHEMA;
        };

        final var original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(markerLoader);
        try { normalizer.onStart(new StartupEvent()); }
        finally { Thread.currentThread().setContextClassLoader(original); }

        assertTrue(done.await(10, TimeUnit.SECONDS), "启动钩子必须异步完成归一化");
        assertEquals(markerLoader, observed.get(), "异步归一化必须携带启动线程的 TCCL, 否则运行时配置查找按工作线程类加载器解析即失败");
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while(!Files.exists(cacheFile) && System.nanoTime() < deadline)
            Thread.sleep(50);
        assertTrue(Files.exists(cacheFile), "修复后异步归一化必须成功生成缓存文件");
    }
    //endregion
}