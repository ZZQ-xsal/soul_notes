package kurvcygnus.soulnotes.websocket;

import io.quarkus.websockets.next.WebSocket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link ClinicalFeedWebSocket} 结构单元测试</b> (ChatWebSocketTest 同款基建).
 * @since 1.2.0
 */
class ClinicalFeedWebSocketTest
{
    @Test void class_ShouldHaveWebSocketAnnotationWithPath()
    {
        final var ws = ClinicalFeedWebSocket.class.getAnnotation(WebSocket.class);
        assertNotNull(ws);
        assertEquals("/ws/clinical/feed", ws.path());
    }

    @Test void lifecycle_MethodsExist() throws Exception
    {
        assertNotNull(ClinicalFeedWebSocket.class.getDeclaredMethod("onOpen", io.quarkus.websockets.next.WebSocketConnection.class));
        assertNotNull(ClinicalFeedWebSocket.class.getDeclaredMethod("onClose", io.quarkus.websockets.next.WebSocketConnection.class));
    }

    //* 测试缝: appliesTo 是纯字符串判定, 不触达验签链路, JWT 解析器与黑名单服务依赖置 null 安全.
    @Test @SuppressWarnings("ConstantConditions")
    void upgradeCheck_ClaimsClinicalEndpoint()
    {
        var check = new WebSocketAuthUpgradeCheck(null, null, "x");
        assertTrue(check.appliesTo("ClinicalFeedWebSocket"));
        assertFalse(check.appliesTo("SomeOtherEndpoint"));
    }
}
