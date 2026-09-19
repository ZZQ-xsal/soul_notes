package kurvcygnus.soulnotes.utils.filter;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import io.vertx.ext.web.RoutingContext;
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
 * 登录 API 限流过滤器.
 * <ul>
 *     <li>限制 {@code /api/v1/auth/login} 的请求频率 (IP 维度, 防止暴力破解)</li>
 *     <li>使用 Redis 响应式计数器, 上限由 {@code rate.limit.login.max-per-minute} 配置 (默认 10 次/分钟)</li>
 *     <li>超限返回 {@code 429 Too Many Requests} (业务码 429002)</li>
 * </ul>
 *
 * <p>采用 {@code @ServerRequestFilter} + {@code Uni<Response>} 响应式实现,
 * 全程无阻塞, 不会占用事件循环.</p>
 * @since 1.0
 */
@ApplicationScoped
@SuppressWarnings("unused")//! @ServerRequestFilter 由 RESTEasy Reactive 注解扫描发现, IDE 静态分析误报类与方法未使用;
//! ReactiveRedisDataSource 为 quarkus-redis-client 生成的 Bean, IDE 静态分析误报未满足依赖 (与 TokenService/RedisStartupConfig 一致).
public final class LoginRateLimitFilter
{
    private static final Logger LOG = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    //* 窗口固定 60 秒, 属于结构性常量; 每分钟上限已改为配置注入 (对齐"No Hard-coded Configuration"指南).
    private static final int WINDOW_SECONDS = 60;

    //* 仅对登录端点生效 (复用常量避免路径漂移).
    private static final String LOGIN_PATH = ApiEndpointConstants.AUTH_BASE + "/login";

    private final @NotNull ReactiveValueCommands<String, String> redisValues;
    private final @NotNull ReactiveKeyCommands<String> redisKeys;
    private final int maxRequestsPerMinute;

    public LoginRateLimitFilter(
        @NotNull ReactiveRedisDataSource redisDS,
        @ConfigProperty(name = "rate.limit.login.max-per-minute", defaultValue = "10") int maxRequestsPerMinute
    )
    {
        this.redisValues = redisDS.value(String.class);
        this.redisKeys   = redisDS.key(String.class);
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }

    /**
     * 响应式登录限流过滤: 非登录端点或无法确定客户端 IP 时直接放行, 命中端点则按固定窗口计数.
     * <p>返回的 {@link Response} 为 {@code null} 时放行; 非 {@code null} (429) 时中止请求;
     * Redis 操作失败时降级放行, 保证登录可用.</p>
     * @param routingContext Vert.x 请求上下文, 用于提取客户端 IP
     * @param uriInfo        请求路径信息, 用于端点匹配
     * @return 携带 429 响应 (中止) 或 {@code null} (放行) 的 {@code Uni}, 永不为 {@code null}
     * @implNote Redis {@code INCR} 计数, 首次计数时设置 60 秒 TTL 构成固定窗口; 过滤器优先级为
     *           {@code Priorities.AUTHORIZATION - 10}, 先于标准鉴权链执行.
     * @since 1.0
     */
    @ServerRequestFilter(priority = Priorities.AUTHORIZATION - 10)
    public @NotNull Uni<Response> filter(@NotNull RoutingContext routingContext, @NotNull UriInfo uriInfo)
    {
        //* 仅拦截登录端点.
        if(!LOGIN_PATH.equals(uriInfo.getPath()))
            return Uni.createFrom().nullItem();

        //* 以客户端 IP 作为限流维度.
        final var clientIp = extractClientIp(routingContext);
        if(clientIp == null)
            return Uni.createFrom().nullItem();

        //* 已确认模板 "ratelimit:%s:%s", 与聊天限流同构.
        final var key = RedisKeyConstants.RATE_LIMIT.formatted("login", clientIp);

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
                        LOG.warn("登录限流触发: ip={}, count={}", clientIp, count);
                        return Response.status(Response.Status.TOO_MANY_REQUESTS).
                            entity("{\"code\":429002,\"message\":\"登录尝试过于频繁, 请稍后再试\"}").
                            type("application/json").
                            build();
                    }
                    return null;
                }
            ).
            onFailure().recoverWithItem(
                t ->
                {
                    //! Redis 不可用时降级放行, 保证登录可用.
                    LOG.warn("限流 Redis 操作失败, 降级放行: {}", t.getMessage());
                    return null;
                }
            );
    }

    //region 辅助方法

    /**
     * 提取客户端 IP.
     * <p>优先取 {@code X-Forwarded-For} 首个值 (反向代理场景), 否则取直连地址;
     * 两者均不可得时返回 {@code null} (调用方按放行处理).</p>
     */
    private static @Nullable String extractClientIp(@NotNull RoutingContext ctx)
    {
        final var forwarded = ctx.request().getHeader("X-Forwarded-For");
        if(forwarded != null && !forwarded.isBlank())
            return forwarded.split(",")[0].trim();
        final var remote = ctx.request().remoteAddress();
        return remote == null ? null : remote.host();
    }

    //endregion
}
