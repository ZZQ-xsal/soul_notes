package kurvcygnus.soulnotes.config;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import kurvcygnus.soulnotes.utils.PrintUtils;
import kurvcygnus.soulnotes.utils.constants.ConfigDefaults;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 启动配置.
 * <p>应用启动时确保 Redis 中的关键 Key 存在初始值.</p>
 * <ul>
 *     <li>{@code crisis:hotline} — 心理危机热线信息, 供离线兜底使用</li>
 * </ul>
 * @since 1.0
 */
@ApplicationScoped
public final class RedisStartupConfig
{
    private static final Logger LOG = LoggerFactory.getLogger(RedisStartupConfig.class);

    private final @NotNull ReactiveValueCommands<String, String> redisValues;
    private final @NotNull String defaultHotline;
    private final @NotNull String appointmentUrl;

    //! Redis 不可用时, 使用配置中的默认值, 确保离线兜底.
    //* crisis:hotline 存储格式: "热线名称|电话号码"
    //* 常量命名为 HOTLINE_REDIS_KEY 而非 CRISIS_HOTLINE_KEY, 避免与 SOULNOTES_CRISIS_HOTLINE_* 环境变量族混淆.
    private static final @NotNull String HOTLINE_REDIS_KEY = "crisis:hotline";

    /**
     * CDI 构造入口, 从 crisis.hotline.* 配置组装默认热线串 (Redis 缺席时的最终兜底).
     *
     * @param redisDS 响应式 Redis 数据源
     * @param primary 主热线号码
     * @param backup 备用热线号码
     * @param name 热线名称
     * @param appointmentUrl 校内心理咨询预约入口地址 (Optional 接空: properties 空默认展开为空串,
     *                       plain String 注入遇空值启动即 SRCFG00040 — WebhookAlertNotifier 同坑先例)
     * @since 1.0
     */
    public RedisStartupConfig(
        @NotNull ReactiveRedisDataSource redisDS,
        @ConfigProperty(name = "crisis.hotline.primary", defaultValue = ConfigDefaults.HOTLINE_PRIMARY) @NotNull String primary,
        @ConfigProperty(name = "crisis.hotline.backup", defaultValue = ConfigDefaults.HOTLINE_BACKUP) @NotNull String backup,
        @ConfigProperty(name = "crisis.hotline.name", defaultValue = ConfigDefaults.HOTLINE_NAME) @NotNull String name,
        @ConfigProperty(name = "crisis.appointment.url") @NotNull java.util.Optional<String> appointmentUrl
    )
    {
        this.redisValues = redisDS.value(String.class);
        this.defaultHotline = PrintUtils.quickFormat("{}|{}|{}", name, primary, backup);
        this.appointmentUrl = appointmentUrl.orElse("");
    }

    /**
     * 应用启动时初始化 Redis Key: 仅当 {@code crisis:hotline} 不存在时写入配置/默认值 (setnx 语义).
     * 初始化失败只记 WARN 不阻断启动 — 后续读取侧仍有默认值兜底, 离线热线不可缺席.
     *
     * @implNote 订阅即发即弃, 不阻塞启动线程; 写入冲突 (key 已存在) 与故障都不视为错误路径.
     * @since 1.0
     */
    void onStart(@Observes @NotNull StartupEvent ev)
    {
        //* 若 Redis 中无 crisis:hotline, 写入配置/默认值.
        redisValues.setnx(HOTLINE_REDIS_KEY, defaultHotline).
            invoke(
                success ->
                {
                    if(Boolean.TRUE.equals(success))
                        LOG.info("已初始化 crisis:hotline = {}", defaultHotline);
                    else
                        LOG.info("crisis:hotline 已存在 Redis 中, 跳过初始化");
                }
            ).
            subscribe().with(
                ignored -> {},
                err -> LOG.warn("crisis:hotline 初始化失败 (Redis 可能不可用, 将使用默认值): {}", err.getMessage())
            );
    }

    //region 热线获取

    /**
     * 获取心理援助热线字符串.
     * <p>优先返回 Redis 中的值; Redis 故障或值为空时回退到配置默认值.</p>
     *
     * @return 热线字符串 "名称|主号码|备用号码" 的 {@link Uni}; 任何失败形态都不让订阅方收到失败信号 —
     *         离线兜底红线要求热线必须始终可得
     * @since 1.0
     */
    public @NotNull Uni<String> getHotline()
    {
        //* 响应式读取, 无阻塞; Redis 故障时降级到默认值.
        return redisValues.get(HOTLINE_REDIS_KEY).
            map(cached -> (cached != null && !cached.isBlank()) ? cached : defaultHotline).
            onFailure().recoverWithItem(
                t ->
                {
                    LOG.warn("从 Redis 获取 crisis:hotline 失败, 使用默认值: {}", t.getMessage());
                    return defaultHotline;
                }
            );
    }

    //endregion

    //region 预约入口

    /**
     * 获取校内心理咨询预约入口地址.
     *
     * @return 预约入口 URL; 机构未配置时为空串 (前端判空隐藏预约入口, 不抛错)
     * @implNote 纯配置注入不走 Redis: 预约地址无 "机构运行时更新" 语义 (热线有), 配置即终值.
     * @since 1.4.0
     */
    public @NotNull String getAppointmentUrl() { return appointmentUrl; }

    //endregion
}
