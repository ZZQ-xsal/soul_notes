package kurvcygnus.soulnotes.support;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <b>Mock-LLM 全链路测试 Profile</b>
 * <p>应用启动前把 LangChain4j base-url 指向 {@link MockLlmServer#shared()} (JVM 内跨类加载器域唯一),
 * 密钥给哑值, 并重开测试资源默认关闭的 Hibernate Reactive + 数据源 (指向本机开发容器, 需 Redis 同样在跑).</p>
 * <p>{@code clinical.tagging=true}: 结构化输出契约段注入 + 落库拆流链路在本 profile 下恒开.</p>
 * @since 2.0
 */
public final class MockLlmProfile implements QuarkusTestProfile
{
    //* 经 shared() 获取: profile 类可能被测试域与运行域各加载一次, 静态单例字段跨域不可见,
    //* 端口唯一性由 JVM 全局 System property 仲裁 (见 MockLlmServer 双 realm 共享契约).
    private static final MockLlmServer SERVER = MockLlmServer.shared();

    public static MockLlmServer server() { return SERVER; }

    @Override
    public Map<String, String> getConfigOverrides()
    {
        final var overrides = new HashMap<String, String>();
        overrides.put("quarkus.langchain4j.openai.base-url", SERVER.baseUrl() + "/v1");
        overrides.put("quarkus.langchain4j.openai.api-key", "mock-llm-key");
        overrides.put("clinical.tagging", "true");
        overrides.put("quarkus.datasource.active", "true");
        overrides.put("quarkus.hibernate-orm.active", "true");
        overrides.put("quarkus.datasource.reactive.url", "postgresql://localhost:5432/soulnotes");
        overrides.put("quarkus.datasource.username", "kurv");
        //* 数据源口令经环境变量注入, 兜底为公开占位符: 本机口令曾随仓库泄露并已全历史去敏, 硬编码真值不允许回归.
        overrides.put("quarkus.datasource.password",
            Objects.requireNonNullElse(System.getenv("SOULNOTES_DB_PASSWORD"), "soulnotes_dev"));
        //* 随机测试端口: 避免 8081 固定端口被占用时应用启动直接失败; @TestHTTPResource 注入实际端口.
        overrides.put("quarkus.http.test-port", "0");
        return Map.copyOf(overrides);
    }

    //* ASR 替身随 profile 装配: 本 profile 的语音全链路用例不依赖本地模型与动态库.
    @Override
    public Set<Class<?>> getEnabledAlternatives() { return Set.of(FixedAsrEngine.class); }
}
