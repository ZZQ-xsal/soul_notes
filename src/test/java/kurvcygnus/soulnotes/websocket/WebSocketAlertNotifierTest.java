package kurvcygnus.soulnotes.websocket;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link WebSocketAlertNotifier} 行为单元测试</b>
 * <p>覆盖渠道标识与 {@code pushAlert} 委托 (参数逐字透传), 以及离线用户路径静默完成
 * (AlertWebSocket 连接注册表为空, Spec §7.2 在线推送语义保持不变).</p>
 * @since 2.0
 */
class WebSocketAlertNotifierTest
{
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Duration AWAIT = Duration.ofSeconds(5);

    //* 测试替身: 记录 pushAlert 调用参数; AlertWebSocket 非 final 保证可覆写 (既有结构测试已钉死该约定).
    private static final class RecordingAlertWebSocket extends AlertWebSocket
    {
        private UUID pushedUserId;
        private String pushedMessage;

        @SuppressWarnings("ConstantConditions")//! 测试缝: pushAlert 已被覆写, 热线依赖不会被触达, 置 null 安全.
        RecordingAlertWebSocket() { super(null); }

        @Override public Uni<Void> pushAlert(UUID userId, String message)
        {
            pushedUserId = userId;
            pushedMessage = message;
            return Uni.createFrom().voidItem();
        }
    }

    //* 实现必须为 CDI Bean: ChatService 的 List<IAlertNotifier> fan-out 依赖全部实现可被发现.
    @Test void class_IsApplicationScopedBean()
    {
        assertNotNull(WebSocketAlertNotifier.class.getAnnotation(ApplicationScoped.class));
    }

    @Test void channel_IsWebsocket()
    {
        assertEquals("websocket", new WebSocketAlertNotifier(new RecordingAlertWebSocket()).channel());
    }

    @Test void notify_DelegatesToPushAlertWithReason()
    {
        final var ws = new RecordingAlertWebSocket();

        new WebSocketAlertNotifier(ws).notify(USER_ID, "RED", "检测到自伤倾向").await().atMost(AWAIT);

        assertEquals(USER_ID, ws.pushedUserId, "notify 必须委托 pushAlert 并透传 userId");
        assertEquals("检测到自伤倾向", ws.pushedMessage, "reason 必须作为 pushAlert 的 message 透传");
    }

    //* 离线用户: AlertWebSocket 注册表为空 → pushAlert 内部静默跳过, notify 恒成功完成.
    @SuppressWarnings("ConstantConditions")//! 测试缝: redisConfig 为 null, 但离线分支在热线解析前即返回, 不会触达该依赖.
    @Test void notify_OfflineUserCompletesQuietly()
    {
        final var notifier = new WebSocketAlertNotifier(new AlertWebSocket(null));

        assertDoesNotThrow(() -> notifier.notify(USER_ID, "RED", "r").await().atMost(AWAIT));
    }
}
