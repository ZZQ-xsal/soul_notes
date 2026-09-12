package kurvcygnus.soulnotes.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.websockets.next.InboundProcessingMode;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import kurvcygnus.soulnotes.domain.chat.service.ChatService;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * <b>AI 对话流式 WebSocket</b>
 * <ul>
 *     <li>接收 JSON 格式的用户消息 (含 {@code content} 和可选的 {@code sessionId})</li>
 *     <li>调用 {@link ChatService#streamMessage} 获取 AI 回复流</li>
 *     <li>通过 WebSocket 逐字推送回复 Token</li>
 * </ul>
 * @since 1.0
 */
@WebSocket(path = "/ws/chat", inboundProcessingMode = InboundProcessingMode.SERIAL)
public class ChatWebSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(ChatWebSocket.class);

    //region 注入
    private final @NotNull ChatService chatService;

    public ChatWebSocket(@NotNull ChatService chatService) { this.chatService = chatService; }
    //endregion

    //region 生命周期
    //! 生命周期回调由框架通过反射调用, IDE 静态分析误报为未使用.
    @OnOpen @SuppressWarnings("unused")
    public void onOpen(@NotNull WebSocketConnection connection)
    {
        final var userId = connection.userData().get(WebSocketAuthUpgradeCheck.USER_ID_KEY);
        LOG.info("对话连接已建立: endpointId={}, userId={}", connection.endpointId(), userId);
    }

    @OnClose @SuppressWarnings("unused")
    public void onClose(@NotNull WebSocketConnection connection)
    {
        final var userId = connection.userData().get(WebSocketAuthUpgradeCheck.USER_ID_KEY);
        LOG.info("对话连接已关闭: endpointId={}, userId={}", connection.endpointId(), userId);
    }

    @OnError @SuppressWarnings("unused")
    public void onError(@NotNull Throwable error, @NotNull WebSocketConnection connection)
    {
        final var userId = connection.userData().get(WebSocketAuthUpgradeCheck.USER_ID_KEY);
        LOG.warn("对话连接异常: endpointId={}, userId={}, {}", connection.endpointId(), userId, error.getMessage());
    }
    //endregion

    //region 消息处理

    /**
     * <span style="color: 95cc6d">处理用户消息并流式推送 AI 回复.</span>
     * <p>接收 JSON: {@code {"content": "...", "sessionId": "..."}} ({@code sessionId} 可选).</p>
     *
     * @param text       收到的 JSON 文本
     * @param connection 当前 WebSocket 连接
     */
    @OnTextMessage
    public void onMessage(@NotNull String text, @NotNull WebSocketConnection connection)
    {
        final var userIdStr = connection.userData().get(WebSocketAuthUpgradeCheck.USER_ID_KEY);
        if(userIdStr == null)
        {
            LOG.warn("对话消息缺少用户身份, 跳过处理");
            return;
        }
        final var userId = UUID.fromString(userIdStr);

        try
        {
            final var msg      = JsonUtils.parseJson(text, JsonNode.class);
            final var content  = msg.get("content").asText();
            final var sessionId = msg.has("sessionId") ? msg.get("sessionId").asText() : null;

            if(content == null || content.isBlank())
            {
                LOG.warn("对话消息内容为空, userId={}", userId);
                return;
            }

            //* 订阅 AI 回复流, 逐 Token 推送至客户端.
            chatService.streamMessage(sessionId, content, userId)
                .onItem().transformToUni(connection::sendText)
                .concatenate()
                .subscribe().with(
                    v -> {},
                    failure -> LOG.warn("流式对话发送失败: userId={}, {}", userId, failure.getMessage()),
                    () -> LOG.info("流式对话完成: userId={}", userId)
                );
        }
        catch(Exception e)
        {
            LOG.warn("对话消息处理失败: userId={}, {}", userId, e.getMessage());
        }
    }

    //endregion
}
