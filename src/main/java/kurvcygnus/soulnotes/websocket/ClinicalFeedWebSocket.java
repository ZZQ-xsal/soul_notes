package kurvcygnus.soulnotes.websocket;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 咨询员工作台实时推送端点 ({@code /ws/clinical/feed}).
 * <p>仅做生命周期注册: 连接身份来自升级期 {@link WebSocketAuthUpgradeCheck} (角色断言 COUNSELOR/ADMIN
 * 在升级阶段完成), 推送语义全部收口于 {@link ClinicalFeedHub#broadcast}.</p>
 * @since 1.2.0
 */
@WebSocket(path = "/ws/clinical/feed")
public class ClinicalFeedWebSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(ClinicalFeedWebSocket.class);

    private final @NotNull ClinicalFeedHub hub;

    @Inject
    public ClinicalFeedWebSocket(@NotNull ClinicalFeedHub hub) { this.hub = hub; }

    //! 生命周期回调由框架反射调用, IDE 静态分析误报为未使用.
    @OnOpen @SuppressWarnings("unused")
    public void onOpen(@NotNull WebSocketConnection connection)
    {
        final var userIdStr = connection.userData().get(WebSocketAuthUpgradeCheck.USER_ID_KEY);
        if(userIdStr == null)
            return;
        hub.register(UUID.fromString(userIdStr), connection);
    }

    @OnClose @SuppressWarnings("unused")
    public void onClose(@NotNull WebSocketConnection connection)
    {
        final var userIdStr = connection.userData().get(WebSocketAuthUpgradeCheck.USER_ID_KEY);
        if(userIdStr == null)
            return;
        hub.unregister(UUID.fromString(userIdStr), connection);
        LOG.info("工作台连接已关闭: counselorId={}", userIdStr);
    }
}
