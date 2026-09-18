package kurvcygnus.soulnotes.domain.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.jwt.auth.principal.DefaultJWTParser;
import io.smallrye.jwt.auth.principal.JWTAuthContextInfo;
import io.smallrye.jwt.auth.principal.ParseException;
import io.smallrye.jwt.algorithm.SignatureAlgorithm;
import kurvcygnus.soulnotes.domain.auth.entity.User;
import kurvcygnus.soulnotes.utils.JsonUtils;
import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link TokenService} 反射单元测试</b>
 * <p>通过反射验证辅助方法的行为.</p>
 *
 * @author Claude Code
 * @since 1.0
 */
class TokenServiceTest
{
    @BeforeAll
    @SuppressWarnings("InstantiationOfUtilityClass")//! JsonUtils 为 final 全静态成员类, IDE 误报实例化; 构造器正是 CDI 桥接注入入口.
    static void initMapper()
    {
        //* 纯单元测试无 CDI 容器, 手动构造与生产等价的 mapper (含 JavaTimeModule);
        //! TokenService.extractJti 静态调用 JsonUtils, 必须预初始化静态桥接否则回退哈希.
        new JsonUtils(new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    //region extractJti
    @Test void extractJti_ShouldReturnJtiClaimFromPayload() throws Exception
    {
        final var method = getPrivateStaticMethod("extractJti", String.class);
        final var payload = Base64.getUrlEncoder().withoutPadding().
            encodeToString("{\"jti\":\"unique-token-id\"}".getBytes());
        final var token = "eyJhbGciOiJIUzI1NiJ9." + payload + ".signature";

        final var jti = method.invoke(null, token);
        assertNotNull(jti);
        assertEquals("unique-token-id", jti);
    }

    @Test void extractJti_ShortToken_ShouldHashEntireToken() throws Exception
    {
        final var method = getPrivateStaticMethod("extractJti", String.class);
        final var jti    = method.invoke(null, "not-a-jwt");
        assertNotNull(jti);
        assertEquals(64, ((String) jti).length());
    }

    @Test void extractJti_InvalidPayload_ShouldFallbackToHash() throws Exception
    {
        final var method = getPrivateStaticMethod("extractJti", String.class);
        //* payload 不是合法 JSON, 应回退到 SHA-256 哈希.
        final var jti = method.invoke(null, "eyJhbGciOiJIUzI1NiJ9.not-valid-payload.signature");
        assertNotNull(jti);
        assertEquals(64, ((String) jti).length());
    }

    @Test void extractJti_ConsistentInput_ShouldReturnConsistentResult() throws Exception
    {
        final var method = getPrivateStaticMethod("extractJti", String.class);
        final var token  = "aaa.bbb.ccc";
        final var jti1   = method.invoke(null, token);
        final var jti2   = method.invoke(null, token);
        assertEquals(jti1, jti2);
    }
    //endregion

    //region sha256Hex
    @Test void sha256Hex_ShouldReturn64CharHex() throws Exception
    {
        final var method = getPrivateStaticMethod("sha256Hex", String.class);
        final var hash   = method.invoke(null, "test-input");
        assertNotNull(hash);
        assertEquals(64, ((String) hash).length());
    }

    @Test void sha256Hex_EmptyInput_ShouldReturnValidHash() throws Exception
    {
        final var method = getPrivateStaticMethod("sha256Hex", String.class);
        final var hash   = method.invoke(null, "");
        assertNotNull(hash);
        assertEquals(64, ((String) hash).length());
    }
    //endregion

    //region issuer 一致性: 签发键与验签键必须同源

    //* 单元测试用的最小安全密钥: 满足构造器 32 字节 fail-fast 阈值即可.
    private static final String TEST_SECRET = "unit-test-secret-0123456789abcdef-0123456789abcdef";

    @Test void generateToken_InjectedIssuer_ShouldCarryIssuerClaim()
    {
        final var service = new TokenService(fakeRedisSource(), TEST_SECRET, 604800L, "unit-test-issuer");
        final var token   = service.generateToken(User.create("issuer-user", "hash", UserRole.STUDENT));

        assertEquals("unit-test-issuer", payloadIss(token), "iss claim 必须等于注入的 issuer");
    }

    //* 生产验签回路复刻: DefaultJWTParser + JWTAuthContextInfo 即 CDI 装配的生产行为, issuedBy 载体就是 mp.jwt.verify.issuer.
    @Test void generateToken_VerifierWithSameIssuer_ShouldAccept() throws Exception
    {
        final var service = new TokenService(fakeRedisSource(), TEST_SECRET, 604800L, "unit-test-issuer");
        final var token   = service.generateToken(User.create("issuer-user", "hash", UserRole.STUDENT));

        final var jwt = parserExpecting("unit-test-issuer").verify(token, TEST_SECRET);
        assertEquals("unit-test-issuer", jwt.getIssuer());
    }

    //! 不同 issuer 的 Token 必须被验签拒绝: 这是 SOULNOTES_JWT_ISSUER 中途更换后全部已发 Token 失效的语义保证.
    @Test void generateToken_VerifierWithDifferentIssuer_ShouldReject()
    {
        final var service = new TokenService(fakeRedisSource(), TEST_SECRET, 604800L, "unit-test-issuer");
        final var token   = service.generateToken(User.create("issuer-user", "hash", UserRole.STUDENT));

        assertThrows(ParseException.class, () -> parserExpecting("other-issuer").verify(token, TEST_SECRET), "issuer 不匹配的 Token 必须验签失败");
    }

    //! 空白 issuer 会使签发 (iss="") 与验签 (框架回退默认值) 双侧行为分叉, 与 jwt.secret 同级启动 fail-fast.
    @Test void blankIssuer_ShouldFailFast()
    {
        assertThrows(IllegalStateException.class, () -> new TokenService(fakeRedisSource(), TEST_SECRET, 604800L, " "));
    }

    //* 复刻生产 JWTParser 验签上下文: HS256 对称密钥 + 期望 issuer, 与 WebSocketAuthUpgradeCheck 的 verify(token, secret) 同路.
    private static DefaultJWTParser parserExpecting(String issuer)
    {
        final var info = new JWTAuthContextInfo();
        info.setSecretVerificationKey(new javax.crypto.spec.SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        info.setSignatureAlgorithm(Set.of(SignatureAlgorithm.HS256));
        info.setIssuedBy(issuer);
        return new DefaultJWTParser(info);
    }

    //* 本组用例只关心 iss claim, 无需通用取值.
    private static String payloadIss(String token)
    {
        final var payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        return JsonUtils.parseJson(payload, JsonNode.class).get("iss").asText();
    }

    //* 与 VoiceRateLimitFilterTest 相同的 Proxy 假体模式: 纯单元测试无 Redis 容器, 仅实现构造器触达的 value().
    private static ReactiveRedisDataSource fakeRedisSource()
    {
        final var values = proxy(ReactiveValueCommands.class,
            (_, m, _) -> { throw new UnsupportedOperationException("fake 未实现该方法: " + m.getName()); });
        return proxy(ReactiveRedisDataSource.class,
            (_, m, _) ->
            {
                if("value".equals(m.getName()))
                    return values;
                return unhandled(m);
            });
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
    private static Object unhandled(Method m)
    { throw new UnsupportedOperationException("fake 未实现该方法: " + m.getName()); }

    //endregion

    //region 反射工具
    private static Method getPrivateStaticMethod(String name, Class<?>... paramTypes) throws NoSuchMethodException
    {
        final var method = TokenService.class.getDeclaredMethod(name, paramTypes);
        assertTrue(Modifier.isStatic(method.getModifiers()), "方法 " + name + " 应为 static");
        assertTrue(Modifier.isPrivate(method.getModifiers()), "方法 " + name + " 应为 private");
        method.setAccessible(true);
        return method;
    }
    //endregion
}
