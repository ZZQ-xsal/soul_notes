package kurvcygnus.soulnotes.domain.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.domain.auth.entity.User;
import kurvcygnus.soulnotes.utils.JsonUtils;
import kurvcygnus.soulnotes.utils.constants.JwtConstants;
import kurvcygnus.soulnotes.utils.constants.RedisKeyConstants;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * <b>JWT 令牌服务</b>
 * <ul>
 *     <li>签发 JWT、校验 JWT、将 Token 加入 Redis 黑名单以实现登出</li>
 * </ul>
 * @since 1.0
 */
@ApplicationScoped
@SuppressWarnings("unused")//! ReactiveRedisDataSource 为 quarkus-redis-client 生成的 Bean, IDE 静态分析误报未满足依赖.
public final class TokenService
{
    private static final Logger LOG = LoggerFactory.getLogger(TokenService.class);

    //region 注入
    private final @NotNull ReactiveValueCommands<String, String> redisValues;
    private final @NotNull String jwtSecret;

    //* Token TTL (秒), 默认 7 天.
    private final long ttlSeconds;

    //* 使用 [[ReactiveRedisDataSource]] 获取响应式 Redis 操作接口.
    public TokenService(
        @NotNull ReactiveRedisDataSource redisDS,
        @ConfigProperty(name = "jwt.secret") @NotNull String jwtSecret,
        @ConfigProperty(name = "jwt.ttl-seconds", defaultValue = "604800") long ttlSeconds
    )
    {
        //! 启动 fail-fast: 未配置或强度不足的密钥直接拒绝启动, 防止生产环境静默使用弱密钥.
        if(jwtSecret.isBlank() || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("jwt.secret 未配置或强度不足: 请通过 HASH_KEY 环境变量提供至少 32 字节的签名密钥");

        this.redisValues = redisDS.value(String.class);
        this.jwtSecret  = jwtSecret;
        this.ttlSeconds = ttlSeconds;
    }
    //endregion

    //region 核心方法
    /**
     * <span style="color: 95cc6d">为指定用户生成 JWT.</span>
     *
     * @param user 用户实体
     * @return 签名后的 JWT 字符串
     */
    public @NotNull String generateToken(@NotNull User user)
    {
        final var now        = Instant.now();
        final var expiration = now.plus(Duration.ofSeconds(ttlSeconds));

        //! 不设置 upn: JsonWebToken.getName() 优先返回 upn, 而资源层用 getPrincipal().getName() 解析 userId (sub, UUID 格式),
        //! 若设置 upn=username 会导致 UUID.fromString(username) 抛异常.
        return Jwt.issuer(JwtConstants.ISSUER).
            subject(user.id.toString()).
            groups(Set.of(user.role.name())).
            claim(org.eclipse.microprofile.jwt.Claims.jti, UUID.randomUUID().toString()).//* 必须设置 jti, 否则黑名单无法与登出时的 key 对齐.
            issuedAt(now).
            expiresAt(expiration).
            signWithSecret(jwtSecret);
    }

    /**
     * <span style="color: 95cc6d">将 Token 加入黑名单 (直到其原始过期时间).</span>
     * <p>黑名单 Key 格式: {@code jwt:blacklist:{jti}}</p>
     *
     * @param token 待注销的 JWT
     * @return {@link Uni<Void>}
     */
    public @NotNull Uni<Void> invalidateToken(@NotNull String token)
    {
        //* 从 JWT 中提取 jti 并计算剩余有效期.
        final var parts = token.split("\\.");
        if(parts.length < 2)
            return Uni.createFrom().voidItem();

        final var jti    = extractJti(token);
        final var ttl    = extractRemainingSeconds(token);

        return redisValues.
            setex(RedisKeyConstants.TOKEN_BLACKLIST.formatted(jti), ttl, "true").
            replaceWithVoid();
    }

    /**
     * <span style="color: f84b4b">检查 Token 是否已被列入黑名单.</span>
     *
     * @param jti JWT 的 jti 声明
     * @return {@code true} 若该 Token 已被注销
     */
    public @NotNull Uni<Boolean> isBlacklisted(@NotNull String jti) { return redisValues.get(RedisKeyConstants.TOKEN_BLACKLIST.formatted(jti)).map(Objects::nonNull); }
    //endregion

    //region 辅助方法
    private static @NotNull String extractJti(@NotNull String token)
    {
        //* 优先解析 payload 的 jti claim, 与 JwtAuthenticationMechanism 的 jwt.getTokenID() 对齐.
        try
        {
            final var parts = token.split("\\.");
            if(parts.length >= 2)
            {
                final var decoded = Base64.getUrlDecoder().decode(parts[1]);
                final var payload = JsonUtils.parseJson(new String(decoded), JsonNode.class);
                final var jti     = payload.get("jti");
                if(jti != null && !jti.asText().isBlank())
                    return jti.asText();
            }
        }
        catch(Exception e)
        {
            //! 解析失败时回退到 payload 哈希, 保证登出操作不抛异常.
            LOG.warn("解析 JWT jti 失败, 回退到 payload 哈希: {}", e.getMessage());
        }
        return sha256Hex(token);
    }

    private static @NotNull String sha256Hex(@NotNull String input)
    {
        try
        {
            final var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes()));
        }
        catch(NoSuchAlgorithmException e) { throw new RuntimeException("SHA-256 不可用", e); }
    }

    private long extractRemainingSeconds(@NotNull String token)
    {
        //* 解析 JWT payload 的 exp 声明, 计算精确剩余秒数.
        try
        {
            final var parts = token.split("\\.");
            if(parts.length < 2)
                return ttlSeconds;

            final var decoded  = Base64.getUrlDecoder().decode(parts[1]);
            final var payload  = JsonUtils.parseJson(new String(decoded), JsonNode.class);
            final var exp      = payload.get("exp").asLong();
            final var remaining = exp - Instant.now().getEpochSecond();
            return Math.max(1, remaining);
        }
        catch(Exception e)
        {
            //* 解析失败时回退到配置的完整 TTL.
            return ttlSeconds;
        }
    }
    //endregion
}