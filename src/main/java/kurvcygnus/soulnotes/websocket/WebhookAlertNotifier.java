package kurvcygnus.soulnotes.websocket;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.config.RedisStartupConfig;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * RED 预警 Webhook 渠道, 面向机构服务端, 与 WebSocket 在线推送互为冗余.
 * <p>RED 预警时 POST JSON 负载 {@code {type, userId, level, reason, hotline}} 至配置地址;
 * {@code SOULNOTES_ALERT_WEBHOOK_URL} 空 = 渠道禁用;
 * {@code SOULNOTES_ALERT_WEBHOOK_TOKEN} 非空 = 请求携带 {@code Authorization: Bearer <token>}.</p>
 *
 * @implNote fire-and-forget 安全边界: 超时 3s, 网络失败/非 2xx/JSON 序列化/热线解析失败一律仅记 WARN 日志,
 *           绝不抛出 — 机构侧服务不可用不允许影响主预警链路.
 * @since 1.1.0
 */
@ApplicationScoped
public final class WebhookAlertNotifier implements IAlertNotifier
{
    private static final Logger LOG = LoggerFactory.getLogger(WebhookAlertNotifier.class);

    //* 单例复用 (HttpModelCatalog 先例): 连接池/线程复用; 预警低频, 单客户端足够.
    private static final HttpClient CLIENT = HttpClient.newBuilder().
        connectTimeout(Duration.ofSeconds(3)).
        build();

    //* 请求级 3s 超时: Webhook 面向机构服务端, 绝不允许长时间挂起主预警链路.
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    //region 注入
    //* 空 = 渠道禁用; token 空 = 不带鉴权头.
    private final @NotNull String webhookUrl;
    private final @NotNull String webhookToken;
    private final @NotNull Duration requestTimeout;
    //* 热线源以 Supplier 缝隙注入: 直构测试无法伪造 RedisStartupConfig (ReactiveRedisDataSource).
    private final @NotNull Supplier<Uni<String>> hotlineSource;

    //* CDI 构造: 热线经 RedisStartupConfig.getHotline() 解析, 与 WS 推送同源.
    //* Optional 接住 "定义但为空" 的键 (properties 侧 ${ENV:} 空默认): plain String 注入遇空值会被
    //! 内置 Converter 判为 null, 启动即 ConfigurationException; Optional 语义等价于空串 = 渠道禁用.
    @Inject
    public WebhookAlertNotifier(
        @ConfigProperty(name = "alert.webhook.url") @NotNull Optional<String> webhookUrl,
        @ConfigProperty(name = "alert.webhook.token") @NotNull Optional<String> webhookToken,
        @NotNull RedisStartupConfig redisConfig
    ) { this(webhookUrl.orElse(""), webhookToken.orElse(""), REQUEST_TIMEOUT, redisConfig::getHotline); }

    //* 测试缝: 直供热线源与请求超时, 避开 CDI 与 Redis 依赖.
    WebhookAlertNotifier(
        @NotNull String webhookUrl,
        @NotNull String webhookToken,
        @NotNull Duration requestTimeout,
        @NotNull Supplier<Uni<String>> hotlineSource
    )
    {
        this.webhookUrl = webhookUrl;
        this.webhookToken = webhookToken;
        this.requestTimeout = requestTimeout;
        this.hotlineSource = hotlineSource;
    }
    //endregion

    //region 渠道实现

    /**
     * {@inheritDoc}
     *
     * @return 固定 {@code "webhook"}
     */
    @Override public @NotNull String channel() { return "webhook"; }

    /**
     * {@inheritDoc} 地址空白 (渠道禁用) 时静默跳过, 不触碰热线解析也不发请求.
     */
    @Override public @NotNull Uni<Void> notify(@NotNull UUID userId, @NotNull String level, @NotNull String reason)
    {
        //* 空白地址 = 渠道禁用: 静默跳过, 不触碰热线解析也不发请求.
        if(webhookUrl.isBlank())
        {
            LOG.debug("Webhook 渠道未配置, 预警推送跳过: userId={}", userId);
            return Uni.createFrom().voidItem();
        }
        //! 渠道安全网: 链上任何同步异常 (URI 解析/JSON 序列化) 与异步失败 (超时/连接拒绝/热线解析)
        //! 都被收口为 WARN 日志, notify 恒成功完成 — 失败绝不逃逸到主预警链路.
        return hotlineSource.get().
            chain(hotline -> dispatch(userId, level, reason, hotline)).
            onFailure().invoke(t -> LOG.warn("Webhook 预警推送失败 (仅记录, 不影响主链路): userId={}, {}", userId, t.getMessage())).
            onFailure().recoverWithUni(() -> Uni.createFrom().voidItem());
    }

    //* 组装预警负载并以 POST 提交; 非 2xx 仅 WARN (负载已被接收端拒绝, 无重试语义 — 与 WS 推送静默跳过同级).
    private @NotNull Uni<Void> dispatch(@NotNull UUID userId, @NotNull String level, @NotNull String reason, @NotNull String hotline)
    {
        final var payload = new LinkedHashMap<String, String>();
        payload.put("type", "RED_ALERT");
        payload.put("userId", userId.toString());
        payload.put("level", level);
        payload.put("reason", reason);
        payload.put("hotline", IAlertNotifier.primaryHotlineOf(hotline));  //* 主号码解析与 WS 推送同源 (单一来源).

        //* token 非空才带鉴权头: 空头与缺失头语义不同, 不得发送空 Bearer (机构侧鉴权约定).
        final var builder = HttpRequest.newBuilder(URI.create(webhookUrl)).
            timeout(requestTimeout).
            header("Content-Type", "application/json");
        if(!webhookToken.isBlank())
            builder.header("Authorization", "Bearer " + webhookToken);

        final var request = builder.POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(payload))).build();

        return Uni.createFrom().completionStage(CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())).
            invoke(
                response ->
                {
                    final var status = response.statusCode();
                    if(status < 200 || status >= 300)
                        LOG.warn("Webhook 预警推送被服务端拒绝: userId={}, status={}", userId, status);
                }
            ).
            replaceWithVoid();
    }

    //endregion
}
