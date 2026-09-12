package kurvcygnus.soulnotes.config;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import kurvcygnus.soulnotes.utils.PrintUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Redis 启动配置</b>
 * <p>应用启动时确保 Redis 中的关键 Key 存在初始值.</p>
 * <ul>
 *     <li>{@code crisis:hotline} — 心理危机热线信息, 供离线兜底使用</li>
 * </ul>
 * @since 2.0
 */
@ApplicationScoped
@SuppressWarnings("unused")//! ReactiveRedisDataSource 为 quarkus-redis-client 生成的 Bean, IDE 静态分析误报未满足依赖.
public final class RedisStartupConfig
{
    private static final Logger LOG = LoggerFactory.getLogger(RedisStartupConfig.class);

    private final @NotNull ReactiveValueCommands<String, String> redisValues;
    private final @NotNull String defaultHotline;

    //! Redis 不可用时, 使用配置中的默认值, 确保离线兜底.
    //* crisis:hotline 存储格式: "热线名称|电话号码"
    private static final @NotNull String CRISIS_HOTLINE_KEY = "crisis:hotline";

    public RedisStartupConfig(
        @NotNull ReactiveRedisDataSource redisDS,
        @ConfigProperty(name = "crisis.hotline.primary", defaultValue = "400-161-9995") @NotNull String primary,
        @ConfigProperty(name = "crisis.hotline.backup", defaultValue = "12355") @NotNull String backup,
        @ConfigProperty(name = "crisis.hotline.name", defaultValue = "全国心理援助热线") @NotNull String name
    )
    {
        this.redisValues = redisDS.value(String.class);
        this.defaultHotline = PrintUtils.quickFormat("{}|{}|{}", name, primary, backup);
    }

    /**
     * <span style="color: 95cc6d">应用启动时初始化 Redis Key.</span>
     */
    void onStart(@Observes @NotNull StartupEvent ev)
    {
        //* 若 Redis 中无 crisis:hotline, 写入配置/默认值.
        redisValues.setnx(CRISIS_HOTLINE_KEY, defaultHotline).
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
     * <span style="color: 95cc6d">获取心理援助热线字符串.</span>
     * <p>优先返回 Redis 中的值, 不可用时回退到配置默认值.</p>
     *
     * @return 热线字符串 "名称|主号码|备用号码" 的 {@link Uni}
     */
    public @NotNull Uni<String> getHotline()
    {
        //* 响应式读取, 无阻塞; Redis 故障时降级到默认值.
        return redisValues.get(CRISIS_HOTLINE_KEY).
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
}
