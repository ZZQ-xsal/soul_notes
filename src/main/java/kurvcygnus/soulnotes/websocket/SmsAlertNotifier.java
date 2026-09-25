package kurvcygnus.soulnotes.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.config.RedisStartupConfig;
import kurvcygnus.soulnotes.domain.auth.entity.User;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * RED 预警短信渠道 (阿里云 SendSms 直连, 零 SDK).
 * <p>收件人为向导配置的值班咨询员手机号列表 (spec §4 裁决 2): 学生端已有在线弹窗,
 * 短信的增量价值是学生离线时干预者仍被触达. 群发逐号独立发送, 单号失败仅 WARN 不影响其余.</p>
 *
 * @implNote fire-and-forget 安全边界与 WebhookAlertNotifier 同款: 3s 超时, 任何失败仅 WARN 恒成功完成.
 *           静态 HttpClient 持有者必须加入 native 的 --initialize-at-run-time 名单 (构建期固化教训).
 *           username 经端口缝隙解析 (生产 = Panache 查询, 测试可注入), 值班视角需要定位到人.
 * @since 1.3.0
 */
@ApplicationScoped
public final class SmsAlertNotifier implements IAlertNotifier
{
    private static final Logger LOG = LoggerFactory.getLogger(SmsAlertNotifier.class);

    //* 单例复用 (AsrRuntimeManager 先例); native 构建期不得初始化本类 (Task 5 名单落实).
    private static final HttpClient CLIENT = HttpClient.newBuilder().
        connectTimeout(Duration.ofSeconds(3)).
        build();

    //* 学生显示名兜底: username 缺失 (用户已删/解析失败) 时的模板占位 — 报文仍送达, 值班可经工作台按热线时段定位.
    private static final String UNKNOWN_STUDENT = "未知学生";

    /**
     * 短信渠道配置聚合.
     *
     * @param accessKey    阿里云 AccessKey ID
     * @param secretKey    阿里云 AccessKey Secret
     * @param signName     短信签名名称 (报审件)
     * @param templateCode 模板 Code (报审件)
     * @param phones       值班手机号列表 (逗号分隔)
     * @param endpoint     SendSms API 端点 (测试回环可覆盖)
     */
    public record SmsConfig(
        @NotNull String accessKey,
        @NotNull String secretKey,
        @NotNull String signName,
        @NotNull String templateCode,
        @NotNull String phones,
        @NotNull String endpoint
    )
    {
        /**
         * 渠道启用判定: 五值全部非空白才启用 — 部分配置视为配置不完整, 整渠道禁用
         * (启动校验侧另有 WARN 提示, spec §4).
         */
        public boolean enabled() { return !accessKey.isBlank() && !secretKey.isBlank() && !signName.isBlank() && !templateCode.isBlank() && !phones.isBlank(); }
    }

    //region 注入
    private final @NotNull SmsConfig config;
    //* 热线源以 Supplier 缝隙注入: 直构测试无法伪造 RedisStartupConfig (WebhookAlertNotifier 同款).
    private final @NotNull Supplier<Uni<String>> hotlineSource;
    //* 学生显示名解析缝隙: 生产 = Panache 查询 username; 测试可注入替身.
    private final @NotNull Function<UUID, Uni<String>> usernameResolver;

    //* CDI 构造: 五业务键 + 端点经配置注入, 热线与既有渠道同源 (RedisStartupConfig).
    //* Optional 接住 "定义但为空" 的键: plain String 注入遇空值会启动即失败 (WebhookAlertNotifier 同款).
    @Inject
    public SmsAlertNotifier(
        @ConfigProperty(name = "alert.sms.access-key") @NotNull Optional<String> accessKey,
        @ConfigProperty(name = "alert.sms.secret-key") @NotNull Optional<String> secretKey,
        @ConfigProperty(name = "alert.sms.sign-name") @NotNull Optional<String> signName,
        @ConfigProperty(name = "alert.sms.template-code") @NotNull Optional<String> templateCode,
        @ConfigProperty(name = "alert.sms.phones") @NotNull Optional<String> phones,
        @ConfigProperty(name = "alert.sms.endpoint", defaultValue = "https://dysmsapi.aliyuncs.com") @NotNull Optional<String> endpoint,
        @NotNull RedisStartupConfig redisConfig
    )
    {
        this(
            new SmsConfig(
                accessKey.orElse(""), secretKey.orElse(""), signName.orElse(""),
                templateCode.orElse(""), phones.orElse(""),
                endpoint.filter(e -> !e.isBlank()).orElse("https://dysmsapi.aliyuncs.com")
            ),
            redisConfig::getHotline,
            id -> User.<User>findById(id).map(u -> u == null ? "" : u.username)
        );
    }

    //* 测试缝: 配置与解析缝隙直供, 避开 CDI 与 Redis 依赖 (WebhookAlertNotifier 同款).
    SmsAlertNotifier(
        @NotNull SmsConfig config,
        @NotNull Supplier<Uni<String>> hotlineSource,
        @NotNull Function<UUID, Uni<String>> usernameResolver
    )
    {
        this.config = config;
        this.hotlineSource = hotlineSource;
        this.usernameResolver = usernameResolver;
    }
    //endregion

    //region 渠道实现

    /**
     * {@inheritDoc}
     *
     * @return 固定 {@code "sms"}
     */
    @Override public @NotNull String channel() { return "sms"; }

    /**
     * {@inheritDoc} 配置不完整 (渠道禁用) 时零请求直通.
     */
    @Override public @NotNull Uni<Void> notify(@NotNull UUID userId, @NotNull String level, @NotNull String reason)
    {
        if(!config.enabled())
        {
            LOG.debug("短信渠道未配置, 预警推送跳过: userId={}", userId);
            return Uni.createFrom().voidItem();
        }
        //* 逐号 collect 等全量发送完结才完成: notify 完成即代表每个号码都已送达或已降级记录,
        //! 不得用 "首个 item 即完成" 的收缩算子, 否则其余号码的发送会被取消 (逐号群发语义).
        return hotlineSource.get().
            chain(hotline -> usernameResolver.apply(userId).
                map(username -> buildTemplateParam(level, hotline, username))).
            chain(param -> Multi.createFrom().items(config.phones().split("\\s*,\\s*")).
                onItem().transformToUniAndMerge(phone -> sendOne(phone, param)).
                collect().asList().
                replaceWithVoid()).
        onFailure().invoke(t -> LOG.warn("短信预警推送失败 (仅记录, 不影响主链路): userId={}, {}", userId, t.getMessage())).
            onFailure().recoverWithUni(() -> Uni.createFrom().voidItem());
    }

    //* 逐号发送: 签名 URL → GET → Code=OK 判定; 业务失败/网络失败一律 WARN 后恒成功 (渠道安全网).
    private @NotNull Uni<Void> sendOne(@NotNull String phone, @NotNull String templateParam)
    {
        try
        {
            final var url = AliyunSmsSigner.buildSignedUrl(
                config.secretKey(),
                new AliyunSmsSigner.SendSmsParams(config.accessKey(), config.signName(), config.templateCode(), templateParam, phone, config.endpoint()),
                Instant.now(),
                UUID.randomUUID().toString()
            );
            final var request = HttpRequest.newBuilder(URI.create(url)).
                timeout(Duration.ofSeconds(3)).
                GET().
                build();
            return Uni.createFrom().completionStage(CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())).
                invoke(
                    response ->
                    {
                        final var code = extractCode(response.body());
                        if(!"OK".equals(code))
                            LOG.warn("短信发送被服务端拒绝: phone={}, code={}", maskPhone(phone), code);
                    }
                ).
                onFailure().invoke(t -> LOG.warn("短信发送失败: phone={}, {}", maskPhone(phone), t.getMessage())).
                //* recoverWithNull 必须显式收口失败: onFailure().invoke 只是旁路回调 (失败继续向下游传播),
                //* replaceWithVoid 只替换成功项不吞失败 — 若失败逃逸进 transformToUniAndMerge, 合并流以 failure
                //* 收场并取消其余在途号码, 违背 "单号失败仅 WARN 不影响其余" 的逐号群发契约.
                onFailure().recoverWithNull().
                replaceWithVoid();
        }
        //! 签名构造/URI 解析等同步异常同样收口: 渠道任何故障都不允许向主预警链路逃逸.
        catch(Exception e)
        {
            LOG.warn("短信请求构造失败: phone={}, {}", maskPhone(phone), e.getMessage());
            return Uni.createFrom().voidItem();
        }
    }

    //endregion

    //region 内部

    //* 模板参数: 值班视角需要定位到人 (RED 实名语义与咨询员工作台一致); username 缺失以占位兜底.
    private static @NotNull String buildTemplateParam(@NotNull String level, @NotNull String hotline, @Nullable String username)
    {
        final var root = new LinkedHashMap<String, String>();
        root.put("level", level);
        root.put("hotline", IAlertNotifier.primaryHotlineOf(hotline));
        root.put("student", username == null || username.isBlank() ? UNKNOWN_STUDENT : username);
        return JsonUtils.toJson(root);
    }

    //* 响应 Code 提取: Code != "OK" (限流/模板未报审等) 视为业务失败, 仅 WARN.
    private static @NotNull String extractCode(@NotNull String body)
    {
        try { return JsonUtils.parseJson(body, JsonNode.class).path("Code").asText(""); }
        catch(RuntimeException e) { return ""; }
    }

    //* 日志脱敏: 手机号只留前 3 后 4 位.
    private static @NotNull String maskPhone(@NotNull String phone) { return phone.length() == 11 ? phone.substring(0, 3) + "****" + phone.substring(7) : phone; }

    //endregion
}
