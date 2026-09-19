package kurvcygnus.soulnotes.utils.filter;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link VoiceRateLimitFilter} 行为单元测试</b>
 * <p>以 {@link Proxy} 伪造 Redis 数据源 / JWTParser / JAX-RS 请求上下文 (大接口仅需少数方法),
 * 覆盖路径匹配 / 未超限放行 / 超限 429 / Redis 故障降级放行 / 无鉴权头放行 五路径.</p>
 *
 * @since 2.0
 */
class VoiceRateLimitFilterTest
{
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    //* 过滤器仅拦上传端点, 与生产路径同源 (常量拼接), 防路径漂移.
    private static final String VOICE_UPLOAD_PATH = ApiEndpointConstants.VOICE_BASE + "/upload";

    //region 行为

    @Test void filter_NonVoiceUploadPath_ShouldPassThrough()
    {
        final var filter = filter(new FakeRedis(Uni.createFrom().item(1L)));

        final var resp = filter.filter(uriInfo("/api/v1/chat/send"), headers("Bearer fake-token")).await().atMost(AWAIT_TIMEOUT);

        assertNull(resp, "非上传端点必须放行");
    }

    @Test void filter_UnderLimit_ShouldPassThrough()
    {
        final var filter = filter(new FakeRedis(Uni.createFrom().item(1L)));

        final var resp = filter.filter(uriInfo(VOICE_UPLOAD_PATH), headers("Bearer fake-token")).await().atMost(AWAIT_TIMEOUT);

        assertNull(resp, "未超限必须放行");
    }

    @Test void filter_OverLimit_ShouldReturn429()
    {
        final var filter = filter(new FakeRedis(Uni.createFrom().item(11L)));

        final var resp = filter.filter(uriInfo(VOICE_UPLOAD_PATH), headers("Bearer fake-token")).await().atMost(AWAIT_TIMEOUT);

        assertNotNull(resp, "超限必须中止请求");
        assertEquals(429, resp.getStatus());
        assertTrue(resp.getEntity().toString().contains("429003"), "429 载荷应携带语音限流业务码");
    }

    //* Redis 故障必须降级放行: 语音上传可用性优先于限流精度.
    @Test void filter_RedisFailure_ShouldDegradeToPassThrough()
    {
        final var filter = filter(new FakeRedis(Uni.createFrom().failure(new IllegalStateException("redis down"))));

        final var resp = filter.filter(uriInfo(VOICE_UPLOAD_PATH), headers("Bearer fake-token")).await().atMost(AWAIT_TIMEOUT);

        assertNull(resp, "Redis 不可用时必须降级放行");
    }

    @Test void filter_MissingAuthorization_ShouldPassThrough()
    {
        final var filter = filter(new FakeRedis(Uni.createFrom().item(1L)));

        final var resp = filter.filter(uriInfo(VOICE_UPLOAD_PATH), headers(null)).await().atMost(AWAIT_TIMEOUT);

        assertNull(resp, "无鉴权头时无从取限流维度, 放行交由后续认证层处理");
    }

    //endregion

    //region 测试脚手架

    //* 上限统一取默认值 10: 超限分支以计数 (11 > 10) 驱动, 无需差异化配置.
    private static VoiceRateLimitFilter filter(FakeRedis redis)
    {
        return new VoiceRateLimitFilter(redis.dataSource(), fakeJwtParser(), "test-secret", 10);
    }

    //* verify(String, String) 是过滤器验签取 sub 的唯一入口, 其余方法不会被调用.
    private static JWTParser fakeJwtParser()
    {
        return proxy(JWTParser.class, (_, m, _) ->
            "verify".equals(m.getName()) ?
                proxy(JsonWebToken.class, (_, m2, _) -> "getSubject".equals(m2.getName()) ? "user-1" : unhandled(m2)) :
                unhandled(m));
    }

    private static UriInfo uriInfo(String path)
    {
        return proxy(UriInfo.class, (_, m, args) ->
            "getPath".equals(m.getName()) && args == null ? path : unhandled(m)); //* 零参方法 InvocationHandler 收到 null args
    }

    private static HttpHeaders headers(String authorization)
    {
        return proxy(HttpHeaders.class, (_, m, _) ->
            "getHeaderString".equals(m.getName()) ? authorization : unhandled(m));
    }

    @SuppressWarnings("unchecked")//! Proxy 只能在运行时按接口动态伪造, 泛型无法静态表达; 调用点均为已知接口, 强转安全.
    private static <T> T proxy(Class<T> iface, InvocationHandler handler)
    {
        //* Object 方法 (equals/hashCode/toString) 必须就近应答, 业务 handler 只负责接口方法且未命中一律 fail-fast.
        InvocationHandler guarded = (p, m, args) ->
        {
            if(m.getDeclaringClass() == Object.class)
                return switch(m.getName())
                {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    case "toString" -> iface.getSimpleName() + "$Fake";
                    default -> unhandled(m);
                };
            return handler.invoke(p, m, args);
        };
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, guarded);
    }

    //* fake 未预期的调用必须响亮失败, 静默 null 会掩盖被测代码的越界行为.
    private static Object unhandled(java.lang.reflect.Method m)
    { throw new UnsupportedOperationException("fake 未实现该方法: " + m.getName()); }

    /**
     * <b>伪造 Redis 数据源</b>
     * <p>仅实现过滤器使用的 value().incr 与 key().expire; incr 结果由用例注入, 驱动超限/降级分支.</p>
     */
    private record FakeRedis(Uni<Long> incrResult)
    {
        //* expire 的应答为常量成功, 预先构造避免在反射调用点内联创建发布者.
        private static final Uni<Boolean> EXPIRE_TRUE = Uni.createFrom().item(true);

        ReactiveRedisDataSource dataSource()
        {
            final var values = proxy(ReactiveValueCommands.class, (_, m, _) -> "incr".equals(m.getName()) ? incrResult : unhandled(m));
            final var keys = proxy(ReactiveKeyCommands.class, (_, m, _) -> "expire".equals(m.getName()) ? EXPIRE_TRUE : unhandled(m));
            return proxy(ReactiveRedisDataSource.class, (_, m, _) ->
            {
                if("value".equals(m.getName()))
                    return values;
                if("key".equals(m.getName()))
                    return keys;
                return unhandled(m);
            });
        }
    }

    //endregion
}
