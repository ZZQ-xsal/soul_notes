package kurvcygnus.soulnotes.websocket;

import io.quarkus.arc.All;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.utils.constants.RedisKeyConstants;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * RED 预警统一分发收口: per-user Redis 冷却闸门 + 逐渠道 fan-out.
 * <p>预警渠道的 fan-out 自 {@code ChatService#applyWarning} 收口至此 (Task 2 拆除原处):
 * 同一 RED 短窗口内多次触发 (多轮对话连续命中自伤语义) 只应外呼一次, 由 Redis 冷却 Key
 * 去重, 抑制短信/IM 等外呼渠道被重复轰炸; 命中抑制仅留痕不外呼, 放行则逐渠道 fire-and-forget.</p>
 *
 * <p>失败语义全部内部收口: {@link #dispatchRed} 恒成功完成 (fire-and-forget 契约与渠道同款),
 * 冷却判定与渠道推送的任何故障仅 WARN, 绝不拖垮调用方 (对话主流程) — 与离线安全网红线一致,
 * Redis 故障时 fail-open 放行外呼, 预警触达优先于去重.</p>
 * @since 1.4.0
 */
@ApplicationScoped
public class AlertDispatchService
{
    private static final Logger LOG = LoggerFactory.getLogger(AlertDispatchService.class);

    private final @NotNull List<IAlertNotifier> channels;
    private final @NotNull ReactiveRedisDataSource redisDS;
    private final int cooldownMinutes;

    /**
     * CDI 构造入口.
     *
     * @param channels 预警渠道矩阵
     * @param redisDS 响应式 Redis 数据源
     * @param cooldownMinutes 冷却窗口分钟数, {@code <= 0} 视为禁用冷却 (直接 fan-out)
     * @since 1.4.0
     */
    //* 构造器不做 requireNonNull: 入参由 CDI 容器供给完全可靠, 且纯单测的测试缝允许 redisDS 置 null
    //* (ChatServiceWarningTest 同款构造缝).
    public AlertDispatchService(
        @All @NotNull List<IAlertNotifier> channels,
        @NotNull ReactiveRedisDataSource redisDS,
        @ConfigProperty(name = "alert.cooldown.minutes", defaultValue = "60") int cooldownMinutes
    )
    {
        this.channels = channels;
        this.redisDS = redisDS;
        this.cooldownMinutes = cooldownMinutes;
    }

    //region 分发入口

    /**
     * RED 预警统一分发: 冷却命中则抑制, 放行则逐渠道 fire-and-forget.
     *
     * @param userId 目标用户 ID (冷却判定维度)
     * @param reason 触发预警的原因描述 (原样透传各渠道)
     * @return 恒成功完成的 {@link Uni<Void>}: 冷却判定/渠道推送的任何失败均内部收口 WARN,
     *         不向调用方传播失败信号
     * @since 1.4.0
     */
    public @NotNull Uni<Void> dispatchRed(@NotNull UUID userId, @NotNull String reason)
    {
        Objects.requireNonNull(userId, "Param \"userId\" must not be null!");
        Objects.requireNonNull(reason, "Param \"reason\" must not be null!");

        //* 冷却禁用 (minutes<=0): 不触碰 Redis 直接 fan-out, 是运维逃生门 —
        //* Redis 故障叠加误配置时 RED 预警仍必须外呼 (离线安全网红线).
        if(cooldownMinutes <= 0)
        {
            fanOut(userId, reason);
            //* 契约钉死: 返回 Uni 预期仅单次订阅 (防未来误用) — 本分支 fan-out 已于装配期同步执行 (重复订阅不重放),
            //* 冷却启用分支重复订阅则可重复外呼 (fail-open 路径无窗口去重兜底).
            return Uni.createFrom().voidItem();
        }

        return cooldownActive(userId).
            onFailure().recoverWithItem(
                t ->
                {
                    LOG.warn("RED 预警冷却判定失败, fail-open 放行: userId={}, {}", userId, t.getMessage());
                    return Boolean.FALSE;
                }
            ).
            invoke(
                active ->
                {
                    if(active)
                        LOG.warn("RED 预警冷却窗口内抑制重复外呼: userId={}", userId);
                }
            ).
            flatMap(
                active ->
                {
                    if(!active)
                        fanOut(userId, reason);
                    return Uni.createFrom().voidItem();
                }
            );
    }

    //endregion

    //region 冷却闸门

    /**
     * 冷却判定缝隙: {@code true} = 命中冷却窗口 (抑制外呼), {@code false} = 放行.
     * <p>生产实现: Redis get 判活跃 (写入时已带 TTL, Redis 自动过期, 读取到值即视为窗口活跃),
     * 放行时 {@code setex} 写入窗口 (值为 ISO 时刻, 供人工排查); minutes&lt;=0 或 Redis 故障
     * 一律 {@code false} (fail-open), set 失败仅 WARN 仍放行.</p>
     *
     * @param userId 冷却维度用户 ID
     * @return 恒成功完成的判定结果 (故障已内部归一为放行), 由调用方订阅
     * @implNote 包级可见且类非 final 是刻意的测试缝隙: 纯单测无 Redis 环境, 测试子类覆写本方法
     *           三态驱动 dispatch 判定分支 — 与 {@code IAlertNotifier} 不做 sealed 的同一动因.
     * @since 1.4.0
     */
    //* 惰性解析 redisValues 而非构造期缓存: value() 只是轻量包装对象无 I/O, 冷路径 (仅 RED 触达)
    //* 每次构建成本可忽略, 换取测试缝下 redisDS 置 null 构造不 NPE.
    @NotNull Uni<Boolean> cooldownActive(@NotNull UUID userId)
    {
        //* dispatch 入口已短路, 此处兜底: 防缝隙被绕过时写出 TTL<=0 的非法 setex.
        if(cooldownMinutes <= 0)
            return Uni.createFrom().item(Boolean.FALSE);

        final var redisValues = redisDS.value(String.class);
        final var key = RedisKeyConstants.RED_COOLDOWN.formatted(userId);
        return redisValues.get(key).
            onFailure().recoverWithItem(
                t ->
                {
                    LOG.warn("RED 预警冷却读取失败, fail-open 放行: userId={}, {}", userId, t.getMessage());
                    return null;
                }
            ).
            flatMap(
                cached ->
                {
                    if(cached != null)
                        return Uni.createFrom().item(Boolean.TRUE);
                    //* 放行即占窗口: 冷却去重让位于预警触达, 写失败仅 WARN 不改判定.
                    return redisValues.setex(key, TimeUnit.MINUTES.toSeconds(cooldownMinutes), Instant.now().toString()).
                        onFailure().invoke(t -> LOG.warn("RED 预警冷却窗口写入失败 (仍放行本次外呼): userId={}, {}", userId, t.getMessage())).
                        replaceWith(Boolean.FALSE);
                }
            );
    }

    //endregion

    //region 渠道 fan-out

    /**
     * 逐渠道 fire-and-forget 外呼, 与 {@code ChatService#applyWarning} 既有链路同款.
     *
     * @implNote 渠道空时仅 WARN 哨兵留痕 (文案自 ChatService#applyWarning 迁入):
     *           渠道矩阵配置全丢时 RED 分发退化为空转, 必须留痕而非静默.
     * @since 1.4.0
     */
    private void fanOut(@NotNull UUID userId, @NotNull String reason)
    {
        if(channels.isEmpty())
            LOG.warn("RED 预警无任何通知渠道可用 (IAlertNotifier 实现缺失), 仅落库标记: userId={}", userId);

        //* Uni 是惰性的, 必须订阅才真正触发推送; 渠道实现保证失败仅日志 (接口契约),
        //! 订阅级兜底仅防渠道外的意外实现缺陷, 不允许预警分发拖垮调用方主流程.
        for(final var notifier : channels)
            notifier.notify(userId, "RED", reason).
                subscribe().with(
                    v -> {},
                    t -> LOG.warn("RED 预警推送执行失败: channel={}, userId={}, {}", notifier.channel(), userId, t.getMessage())
                );
    }

    //endregion
}
