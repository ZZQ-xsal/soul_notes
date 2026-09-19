package kurvcygnus.soulnotes.utils.filter;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import kurvcygnus.soulnotes.utils.constants.RedisKeyConstants;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * 语音上传限流过滤器.
 * <ul>
 *     <li>限制 {@code /api/v1/voice/upload} 的请求频率, 以验签后的 userId 为限流维度
 *         (语音转录涉及本地 native 计算, 单请求成本远高于文本接口, 故上限更收紧)</li>
 *     <li>使用 Redis 响应式计数器, 上限由 {@code rate.limit.voice.max-per-minute} 配置 (默认 10 次/分钟)</li>
 *     <li>超限返回 {@code 429 Too Many Requests} (业务码 429003)</li>
 * </ul>
 *
 * <p>采用 {@code @ServerRequestFilter} + {@code Uni<Response>} 响应式实现,
 * 全程无阻塞, 不会占用事件循环.</p>
 * @since 1.1.0
 */
@ApplicationScoped
@SuppressWarnings("unused")//! @ServerRequestFilter 由 RESTEasy Reactive 注解扫描发现, IDE 静态分析误报类与方法未使用;
//! ReactiveRedisDataSource 为 quarkus-redis-client 生成的 Bean, IDE 静态分析误报未满足依赖 (与 ChatRateLimitFilter 一致).
public final class VoiceRateLimitFilter
{
    private static final Logger LOG = LoggerFactory.getLogger(VoiceRateLimitFilter.class);

    //* 窗口固定 60 秒, 属于结构性常量; 每分钟上限已改为配置注入 (对齐"No Hard-coded Configuration"指南).
    private static final int WINDOW_SECONDS = 60;

    //* 仅对语音上传端点生效 (复用常量避免路径漂移).
    private static final String VOICE_UPLOAD_PATH = ApiEndpointConstants.VOICE_BASE + "/upload";

    private final @NotNull JWTParser jwtParser;
    private final @NotNull String jwtSecret;
    private final int maxRequestsPerMinute;

    private final @NotNull ReactiveValueCommands<String, String> redisValues;
    private final @NotNull ReactiveKeyCommands<String> redisKeys;

    public VoiceRateLimitFilter(
        @NotNull ReactiveRedisDataSource redisDS,
        @NotNull JWTParser jwtParser,
        @ConfigProperty(name = "jwt.secret") @NotNull String jwtSecret,
        @ConfigProperty(name = "rate.limit.voice.max-per-minute", defaultValue = "10") int maxRequestsPerMinute
    )
    {
        this.redisValues = redisDS.value(String.class);
        this.redisKeys   = redisDS.key(String.class);
        this.jwtParser   = jwtParser;
        this.jwtSecret   = jwtSecret;
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }

    /**
     * 响应式限流过滤: 非语音上传端点或无法确定 userId 时直接放行, 命中端点则按固定窗口计数.
     * <p>返回的 {@link Response} 为 {@code null} 时放行; 非 {@code null} (429) 时中止请求;
     * Redis 操作失败时降级放行 (限流精度让位于可用性).</p>
     * @param uriInfo     请求路径信息, 用于端点匹配
     * @param httpHeaders 请求头, 用于提取并验签 JWT
     * @return 携带 429 响应 (中止) 或 {@code null} (放行) 的 {@code Uni}, 永不为 {@code null}
     * @implNote Redis {@code INCR} 计数, 首次计数时设置 60 秒 TTL 构成固定窗口; 过滤器优先级为
     *           {@code Priorities.AUTHORIZATION - 10}, 先于标准鉴权链执行, 故此处自行完成 JWT 验签.
     * @since 1.1.0
     */
    @ServerRequestFilter(priority = Priorities.AUTHORIZATION - 10)
    public @NotNull Uni<Response> filter(@NotNull UriInfo uriInfo, @NotNull HttpHeaders httpHeaders)
    {
        //* 仅拦截语音上传端点.
        final var path = uriInfo.getPath();
        if(!path.equals(VOICE_UPLOAD_PATH))
            return Uni.createFrom().nullItem();

        //* 从 Authorization header 提取 userId (验签后的 JWT subject, 作为限流维度).
        final var userId = extractUserId(httpHeaders);
        if(userId == null)
            return Uni.createFrom().nullItem();

        final var key = RedisKeyConstants.RATE_LIMIT.formatted("voice", userId);

        //* 使用 Redis INCR 计数, 首次调用时设置 TTL.
        return redisValues.incr(key).
            flatMap(
                count ->
                {
                    if(count == 1)
                        return redisKeys.expire(key, Duration.ofSeconds(WINDOW_SECONDS)).replaceWith(count);
                    return Uni.createFrom().item(count);
                }
            ).
            map(
                count ->
                {
                    if(count > maxRequestsPerMinute)
                    {
                        LOG.warn("语音上传限流触发: userId={}, count={}", userId, count);
                        return Response.status(Response.Status.TOO_MANY_REQUESTS).
                            entity("{\"code\":429003,\"message\":\"语音上传过于频繁, 请稍后再试\"}").
                            type("application/json").
                            build();
                    }
                    return null;
                }
            ).
            onFailure().recoverWithItem(
                t ->
                {
                    //! Redis 不可用时降级, 允许请求通过 (限流精度让位于可用性).
                    LOG.warn("限流 Redis 操作失败, 降级放行: {}", t.getMessage());
                    return null;
                }
            );
    }

    //region 辅助方法

    /**
     * 从 JWT Authorization header 中提取用户 ID.
     * <p>先验签再取 {@code sub} claim 作为用户 ID; header 缺失、格式不符或验签失败时返回 {@code null}
     * (调用方按放行处理, 而非拒绝请求).</p>
     */
    private @Nullable String extractUserId(@NotNull HttpHeaders headers)
    {
        try
        {
            final var auth = headers.getHeaderString("Authorization");
            if(auth == null || !auth.startsWith("Bearer "))
                return null;
            //! 必须先验签再取 sub: 未验签解析 payload 可被伪造 sub 绕过限流维度.
            final var jwt = jwtParser.verify(auth.substring(7), jwtSecret);
            return jwt.getSubject();
        }
        catch(Exception e)
        {
            LOG.warn("从 JWT 提取 userId 失败: {}", e.getMessage());
            return null;
        }
    }

    //endregion
}
