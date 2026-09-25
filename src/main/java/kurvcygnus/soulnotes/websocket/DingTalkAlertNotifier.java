package kurvcygnus.soulnotes.websocket;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.config.RedisStartupConfig;
import kurvcygnus.soulnotes.domain.auth.entity.User;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * RED 预警钉钉群机器人渠道 (webhook markdown 报文).
 * <p>secret 非空时按钉钉加签约定追加 {@code &timestamp=<ms>&sign=<URLEncode(Base64(HmacSHA256(secret, "<ms>\n<secret>")))>};
 * 报文精简纪律: 学生标识 / 热线 / 截断 120 字的事由, 不含 summary 全文 — 群机器人是提醒不是档案 (spec §6.1).</p>
 *
 * @implNote fire-and-forget 安全边界与 WebhookAlertNotifier 同款; 静态 HttpClient 单例复用
 *           (SmsAlertNotifier 同款), native 构建期不得初始化本类 — 持有者须加入 native 的
 *           --initialize-at-run-time 名单 (Task 5 名单落实).
 * @since 1.3.0
 */
@ApplicationScoped
public final class DingTalkAlertNotifier implements IAlertNotifier
{
    private static final Logger LOG = LoggerFactory.getLogger(DingTalkAlertNotifier.class);

    //* 事由截断上限: 群消息是提醒不是档案 (spec 消息体精简纪律).
    private static final int REASON_MAX = 120;

    private static final HttpClient CLIENT = HttpClient.newBuilder().
        connectTimeout(Duration.ofSeconds(3)).
        build();

    //region 注入
    private final @NotNull String webhook;
    private final @NotNull String secret;
    private final @NotNull Supplier<Uni<String>> hotlineSource;
    //* 学生显示名解析缝隙: 生产 = Panache 查询 username (SmsAlertNotifier 同款); 群消息带 username 即可定位到人.
    private final @NotNull Function<UUID, Uni<String>> usernameResolver;

    //* CDI 构造: webhook/secret 经 Optional 注入接住 "定义但为空" 的键 (WebhookAlertNotifier 同款), 热线与既有渠道同源.
    @Inject
    public DingTalkAlertNotifier(
        @ConfigProperty(name = "alert.ding.webhook") @NotNull Optional<String> webhook,
        @ConfigProperty(name = "alert.ding.secret") @NotNull Optional<String> secret,
        @NotNull RedisStartupConfig redisConfig
    )
    {
        this(
            webhook.orElse(""),
            secret.orElse(""),
            redisConfig::getHotline,
            id -> User.<User>findById(id).map(u -> u == null ? "" : u.username)
        );
    }

    //* 测试缝 (WebhookAlertNotifier 同款).
    DingTalkAlertNotifier(
        @NotNull String webhook,
        @NotNull String secret,
        @NotNull Supplier<Uni<String>> hotlineSource,
        @NotNull Function<UUID, Uni<String>> usernameResolver
    )
    {
        this.webhook = webhook;
        this.secret = secret;
        this.hotlineSource = hotlineSource;
        this.usernameResolver = usernameResolver;
    }
    //endregion

    /**
     * {@inheritDoc}
     *
     * @return 固定 {@code "dingtalk"}
     */
    @Override public @NotNull String channel() { return "dingtalk"; }

    /**
     * {@inheritDoc} webhook 空 (渠道禁用) 时零请求直通.
     */
    @Override public @NotNull Uni<Void> notify(@NotNull UUID userId, @NotNull String level, @NotNull String reason)
    {
        if(webhook.isBlank())
        {
            LOG.debug("钉钉渠道未配置, 预警推送跳过: userId={}", userId);
            return Uni.createFrom().voidItem();
        }
        //* 响应式链取 username (事件循环无阻塞红线): 热线与显示名并行解析, 组装报文后发送.
        return Uni.combine().all().
            unis(hotlineSource.get(), usernameResolver.apply(userId)).
            asTuple().
            chain(t -> send(markdown(IAlertNotifier.primaryHotlineOf(t.getItem1()), t.getItem2() == null || t.getItem2().isBlank() ? "未知学生" : t.getItem2(), reason))).
            onFailure().invoke(t -> LOG.warn("钉钉预警推送失败 (仅记录, 不影响主链路): {}", t.getMessage())).
            onFailure().recoverWithUni(() -> Uni.createFrom().voidItem());
    }

    /**
     * 钉钉加签: {@code URLEncode(Base64(HmacSHA256(secret, "<timestamp>\n<secret>")))}.
     *
     * @param timestampMs 毫秒时间戳
     * @param secret      加签密钥
     * @return URL 编码后的签名串
     */
    static @NotNull String sign(long timestampMs, @NotNull String secret)
    {
        try
        {
            final var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            final var stringToSign = timestampMs + "\n" + secret;
            final var signature = Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
            return URLEncoder.encode(signature, StandardCharsets.UTF_8);
        }
        catch(Exception e) { throw new IllegalStateException("HmacSHA256 不可用 (JDK 标准算法缺失?)", e); }
    }

    /**
     * markdown 报文文本段: 学生 / 热线 / 截断事由.
     */
    static @NotNull String markdown(@NotNull String hotline, @NotNull String username, @NotNull String reason)
    {
        final var truncated = reason.length() > REASON_MAX ? reason.substring(0, REASON_MAX) + "..." : reason;
        return "### RED 预警\n- 学生: " + username + "\n- 热线: " + hotline + "\n- 事由: " + truncated;
    }

    //* POST 报文并发送; 非 2xx / 网络失败 / 业务级失败 (200 + errcode 非 0) 一律 WARN 后恒成功.
    private @NotNull Uni<Void> send(@NotNull String text)
    {
        final var payload = new LinkedHashMap<String, Object>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", Map.of("title", "心灵札记 RED 预警", "text", text));
        final var request = HttpRequest.newBuilder(URI.create(signedUrl())).
            timeout(Duration.ofSeconds(3)).
            header("Content-Type", "application/json").
            POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(payload))).
            build();
        //* ofString 必须保留响应体: 机器人业务失败 (加签错/密钥失效/限流) 以 HTTP 200 + errcode 非 0 返回,
        //! discarding 会把这类失败吞成 "成功", 渠道静默死亡 (I1).
        return Uni.createFrom().completionStage(CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())).
            invoke(
                response ->
                {
                    final var status = response.statusCode();
                    if(status < 200 || status >= 300)
                        LOG.warn("钉钉预警推送被服务端拒绝: status={}", status);
                    else
                    {
                        final var failure = IAlertNotifier.businessFailureOf(response.body());
                        if(failure != null)
                            LOG.warn("钉钉机器人业务失败 (HTTP 200): {}", failure);
                    }
                }
            ).
            replaceWithVoid();
    }

    //* 加签 URL: secret 非空时追加 timestamp + sign (钉钉安全设置).
    private @NotNull String signedUrl()
    {
        if(secret.isBlank())
            return webhook;
        final var timestampMs = System.currentTimeMillis();
        return webhook + (webhook.contains("?") ? "&" : "?") + "timestamp=" + timestampMs + "&sign=" + sign(timestampMs, secret);
    }
}
