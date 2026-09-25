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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * RED 预警企业微信群机器人渠道 (webhook markdown 报文).
 * <p>企微机器人的凭据即 webhook URL 自带的 {@code key=} 参数, 无钉钉式加签环节 — webhook 原样使用;
 * 报文精简纪律: 学生标识 / 热线 / 截断 120 字的事由, 不含 summary 全文 — 群机器人是提醒不是档案 (spec §6.1).</p>
 *
 * @implNote fire-and-forget 安全边界与钉钉渠道同款; 静态 HttpClient 单例复用
 *           (SmsAlertNotifier 同款), native 构建期不得初始化本类 — 持有者须加入 native 的
 *           --initialize-at-run-time 名单 (Task 5 名单落实).
 * @since 1.3.0
 */
@ApplicationScoped
public final class WeComAlertNotifier implements IAlertNotifier
{
    private static final Logger LOG = LoggerFactory.getLogger(WeComAlertNotifier.class);

    //* 事由截断上限: 群消息是提醒不是档案 (spec 消息体精简纪律).
    private static final int REASON_MAX = 120;

    private static final HttpClient CLIENT = HttpClient.newBuilder().
        connectTimeout(Duration.ofSeconds(3)).
        build();

    //region 注入
    private final @NotNull String webhook;
    private final @NotNull Supplier<Uni<String>> hotlineSource;
    //* 学生显示名解析缝隙: 生产 = Panache 查询 username (钉钉渠道同款); 群消息带 username 即可定位到人.
    private final @NotNull Function<UUID, Uni<String>> usernameResolver;

    //* CDI 构造: webhook 经 Optional 注入接住 "定义但为空" 的键 (钉钉渠道同款); 企微凭据即 URL key 参数, 故仅此一个配置键.
    @Inject
    public WeComAlertNotifier(
        @ConfigProperty(name = "alert.wecom.webhook") @NotNull Optional<String> webhook,
        @NotNull RedisStartupConfig redisConfig
    )
    {
        this(
            webhook.orElse(""),
            redisConfig::getHotline,
            id -> User.<User>findById(id).map(u -> u == null ? "" : u.username)
        );
    }

    //* 测试缝 (钉钉渠道同款).
    WeComAlertNotifier(
        @NotNull String webhook,
        @NotNull Supplier<Uni<String>> hotlineSource,
        @NotNull Function<UUID, Uni<String>> usernameResolver
    )
    {
        this.webhook = webhook;
        this.hotlineSource = hotlineSource;
        this.usernameResolver = usernameResolver;
    }
    //endregion

    /**
     * {@inheritDoc}
     *
     * @return 固定 {@code "wecom"}
     */
    @Override public @NotNull String channel() { return "wecom"; }

    /**
     * {@inheritDoc} webhook 空 (渠道禁用) 时零请求直通.
     */
    @Override public @NotNull Uni<Void> notify(@NotNull UUID userId, @NotNull String level, @NotNull String reason)
    {
        if(webhook.isBlank())
        {
            LOG.debug("企微渠道未配置, 预警推送跳过: userId={}", userId);
            return Uni.createFrom().voidItem();
        }
        //* 响应式链取 username (事件循环无阻塞红线): 热线与显示名并行解析, 组装报文后发送.
        return Uni.combine().all().
            unis(hotlineSource.get(), usernameResolver.apply(userId)).
            asTuple().
            chain(t -> send(markdown(IAlertNotifier.primaryHotlineOf(t.getItem1()), t.getItem2() == null || t.getItem2().isBlank() ? "未知学生" : t.getItem2(), reason))).
            onFailure().invoke(t -> LOG.warn("企微预警推送失败 (仅记录, 不影响主链路): {}", t.getMessage())).
            onFailure().recoverWithUni(() -> Uni.createFrom().voidItem());
    }

    /**
     * markdown 报文文本段: 学生 / 热线 / 截断事由.
     */
    static @NotNull String markdown(@NotNull String hotline, @NotNull String username, @NotNull String reason)
    {
        final var truncated = reason.length() > REASON_MAX ? reason.substring(0, REASON_MAX) + "..." : reason;
        return "**RED 预警**\n>学生: " + username + "\n>热线: " + hotline + "\n>事由: " + truncated;
    }

    //* 企微报文形态: markdown 消息体只有 content 单字段 (钉钉是 title/text 双字段); 键序 LinkedHash 固定, 与钉钉报文组装纪律同款.
    private static @NotNull Map<String, Object> payload(@NotNull String text)
    {
        final var payload = new LinkedHashMap<String, Object>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", Map.of("content", text));
        return payload;
    }

    //* POST 报文并发送; 非 2xx / 网络失败 / 业务级失败 (200 + errcode 非 0) 一律 WARN 后恒成功.
    private @NotNull Uni<Void> send(@NotNull String text)
    {
        //* 企微凭据即 webhook URL 自带 key= 参数, 无加签环节 — 原样使用 (与钉钉唯一链路差异).
        final var request = HttpRequest.newBuilder(URI.create(webhook)).
            timeout(Duration.ofSeconds(3)).
            header("Content-Type", "application/json").
            POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(payload(text)))).
            build();
        //* ofString 必须保留响应体: 机器人业务失败 (key 失效/机器人被移除/限流) 以 HTTP 200 + errcode 非 0 返回,
        //! discarding 会把这类失败吞成 "成功", 渠道静默死亡 (I1).
        return Uni.createFrom().completionStage(CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())).
            invoke(
                response ->
                {
                    final var status = response.statusCode();
                    if(status < 200 || status >= 300)
                        LOG.warn("企微预警推送被服务端拒绝: status={}", status);
                    else
                    {
                        final var failure = IAlertNotifier.businessFailureOf(response.body());
                        if(failure != null)
                            LOG.warn("企微机器人业务失败 (HTTP 200): {}", failure);
                    }
                }
            ).
            replaceWithVoid();
    }
}
