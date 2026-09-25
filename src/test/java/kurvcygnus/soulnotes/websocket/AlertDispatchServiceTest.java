package kurvcygnus.soulnotes.websocket;

import io.smallrye.mutiny.Uni;
import kurvcygnus.soulnotes.utils.constants.RedisKeyConstants;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code AlertDispatchService} RED 预警统一分发单元测试</b>
 * <p>纯单测, 双缝隔离外部世界: 渠道侧以 record 渠道替身记录 {@code notify} 入参 (恒 voidItem,
 * subscribe 同步完成); Redis 侧以测试子类覆写包级 {@code cooldownActive} 冷却缝隙, 以三态
 * (命中/放行/故障 fail-open) 钉住 dispatch 判定分支.冷却判定的真实 Redis 读写路径需 Redis 环境,
 * 由缝隙隔离, 不在本测面.</p>
 * <p>WARN 哨兵文案是 fire-and-forget 契约里分发器侧失败收口的唯一观测面, 经
 * {@link WarningLogCapture} 确定性断言 (SLF4J 后端 = jboss-logmanager, 直挂 LogContext 捕获).</p>
 * @since 1.4.0
 */
class AlertDispatchServiceTest
{
    private static final Duration AWAIT = Duration.ofSeconds(5);

    //region 渠道替身与冷却缝隙

    //* 渠道替身: 记录 notify 入参供 fan-out 断言; Uni 恒为已解析 voidItem, subscribe 同步完成.
    private record RecordingChannel(String name, List<UUID> userIds, List<String> levels, List<String> reasons) implements IAlertNotifier
    {
        RecordingChannel(String name) { this(name, new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>()); }

        @Override public String channel() { return name; }

        @Override public Uni<Void> notify(UUID userId, String level, String reason)
        {
            userIds.add(userId);
            levels.add(level);
            reasons.add(reason);
            return Uni.createFrom().voidItem();
        }
    }

    /**
     * 冷却缝隙替身: 覆写 {@code cooldownActive} 隔离真实 Redis, 以 verdict 三态驱动 dispatch 判定分支.
     * <p>cooldownQueries 留痕缝隙是否被触达, 供 "冷却禁用直通不得触碰冷却判定" 断言.</p>
     */
    private static final class SeamedDispatchService extends AlertDispatchService
    {
        final List<UUID> cooldownQueries = new CopyOnWriteArrayList<>();
        private final Uni<Boolean> verdict;

        @SuppressWarnings("ConstantConditions")//! 测试缝: 纯单测无 Redis 环境, redisDS 置 null 是构造实例的唯一途径 (缝隙被覆写时永不触达).
        SeamedDispatchService(List<IAlertNotifier> channels, int cooldownMinutes, Uni<Boolean> verdict)
        {
            super(channels, null, cooldownMinutes);
            this.verdict = verdict;
        }

        @Override Uni<Boolean> cooldownActive(UUID userId)
        {
            cooldownQueries.add(userId);
            return verdict;
        }
    }

    //endregion

    //region 冷却三态分支

    @Test void dispatchRed_CooldownHit_SuppressesAllChannelsWithSingleWarn()
    {
        final var ws = new RecordingChannel("websocket");
        final var webhook = new RecordingChannel("webhook");
        final var service = new SeamedDispatchService(List.of(ws, webhook), 60, Uni.createFrom().item(true));
        final var userId = UUID.randomUUID();

        final var capture = WarningLogCapture.attach(AlertDispatchService.class);
        try
        {
            assertDoesNotThrow(() -> service.dispatchRed(userId, "检测到自伤倾向").await().atMost(AWAIT),
                "抑制路径同样必须恒成功完成 (fire-and-forget 契约)");
        }
        finally { capture.detach(); }

        assertTrue(ws.userIds.isEmpty(), "冷却命中必须抑制 websocket 渠道外呼");
        assertTrue(webhook.userIds.isEmpty(), "冷却命中必须抑制 webhook 渠道外呼");
        assertEquals(List.of(userId), service.cooldownQueries, "冷却判定必须以 dispatch 的 userId 为维度");
        assertEquals(1, capture.messages().size(), "抑制路径仅允许一条 WARN");
        final var warn = capture.messages().getFirst();
        assertTrue(warn.contains("冷却窗口内抑制重复外呼"), "抑制 WARN 必须携带抑制文案");
        assertTrue(warn.contains(userId.toString()), "抑制 WARN 必须携带 userId");
    }

    @Test void dispatchRed_CooldownMiss_FansOutAllChannelsWithReason()
    {
        final var ws = new RecordingChannel("websocket");
        final var webhook = new RecordingChannel("webhook");
        final var service = new SeamedDispatchService(List.of(ws, webhook), 60, Uni.createFrom().item(false));
        final var userId = UUID.randomUUID();

        service.dispatchRed(userId, "检测到自伤倾向, 建议立即干预").await().atMost(AWAIT);

        assertEquals(List.of(userId), ws.userIds, "放行时每个渠道必须收到同一 userId");
        assertEquals(List.of(userId), webhook.userIds, "放行时逐渠道 fan-out 不得遗漏");
        assertEquals("RED", ws.levels.getFirst());
        assertEquals("RED", webhook.levels.getFirst(), "预警等级恒为 RED");
        assertEquals("检测到自伤倾向, 建议立即干预", ws.reasons.getFirst(), "reason 必须原样透传");
        assertEquals("检测到自伤倾向, 建议立即干预", webhook.reasons.getFirst(), "各渠道必须收到同一 reason");
    }

    @Test void dispatchRed_CooldownCheckFails_FailOpensToFanOutWithoutPropagating()
    {
        final var ws = new RecordingChannel("websocket");
        //* 模拟 Redis 故障形态: 冷却缝隙返回失败 Uni.
        final var service = new SeamedDispatchService(List.of(ws), 60, Uni.createFrom().failure(new IllegalStateException("Redis 不可用")));
        final var userId = UUID.randomUUID();

        assertDoesNotThrow(() -> service.dispatchRed(userId, "检测到自伤倾向").await().atMost(AWAIT),
            "冷却判定故障不得向调用方传播失败");
        assertEquals(List.of(userId), ws.userIds, "冷却判定故障必须 fail-open 放行 fan-out (安全网优先)");
    }

    //* 冷却禁用: minutes<=0 是运维逃生门, 必须完全旁路冷却判定 (不触碰 Redis) 直接 fan-out;
    //* verdict 恒命中 — 若禁用路径仍询问冷却, 渠道必零调用, 断言组即失败, 借此钉住旁路语义.
    @Test void dispatchRed_CooldownDisabled_SkipsCooldownCheckAndFansOutDirectly()
    {
        final var ws = new RecordingChannel("websocket");
        final var service = new SeamedDispatchService(List.of(ws), 0, Uni.createFrom().item(true));
        final var userId = UUID.randomUUID();

        service.dispatchRed(userId, "检测到自伤倾向").await().atMost(AWAIT);

        assertTrue(service.cooldownQueries.isEmpty(), "冷却禁用 (minutes<=0) 不得触达冷却判定");
        assertEquals(List.of(userId), ws.userIds, "冷却禁用必须直接 fan-out");
    }

    //endregion

    //region 哨兵与 Key 模板

    //* 空渠道哨兵: 渠道矩阵配置全丢时 RED 分发退化为空转, WARN 留痕防静默退化;
    //* 钉住的是可观测副作用的一半 (哨兵 WARN + 主流程存活), 与 ChatServiceWarningTest 同一纪律.
    @Test void dispatchRed_NoChannels_WarnsSentinelAndStillCompletes()
    {
        final var service = new SeamedDispatchService(List.of(), 60, Uni.createFrom().item(false));
        final var userId = UUID.randomUUID();

        final var capture = WarningLogCapture.attach(AlertDispatchService.class);
        try
        {
            assertDoesNotThrow(() -> service.dispatchRed(userId, "检测到自伤倾向").await().atMost(AWAIT),
                "渠道全空时预警分发必须静默存活, 不允许炸掉调用方主流程");
        }
        finally { capture.detach(); }

        final var joined = String.join("\n", capture.messages());
        assertTrue(joined.contains("无任何通知渠道可用"), "渠道全空必须留 WARN 哨兵, 防 RED 分发静默退化");
        assertTrue(joined.contains(userId.toString()), "哨兵日志必须携带 userId");
    }

    //* 冷却 Key 模板钉形: 单 %s 占位 (per-user 维度), 防后续重构漂移成全局共享窗口 (共享 = 一次预警抑制全站).
    @Test void redCooldownKeyTemplate_IsPerUser()
    {
        final var userId = UUID.randomUUID();
        assertEquals("alert:red-cooldown:" + userId, RedisKeyConstants.RED_COOLDOWN.formatted(userId));
    }

    //endregion
}
