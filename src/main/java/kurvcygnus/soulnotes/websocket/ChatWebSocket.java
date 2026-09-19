package kurvcygnus.soulnotes.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.websockets.next.InboundProcessingMode;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.mutiny.core.Vertx;
import kurvcygnus.soulnotes.domain.chat.service.ChatService;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * AI 对话流式 WebSocket 端点 ({@code /ws/chat}), 与 SSE REST 路径并行的逐字对话通道.
 * <ul>
 *     <li>接收 JSON 格式的用户消息 (含 {@code content} 和可选的 {@code sessionId})</li>
 *     <li>调用 {@link ChatService#streamMessage} 获取 AI 回复流</li>
 *     <li>通过 WebSocket 逐字推送回复 Token</li>
 * </ul>
 *
 * @implNote 入站处理模式为 SERIAL (回调按序启动, 但不保证链路完成时长);
 *           连接身份来自升级期 {@link WebSocketAuthUpgradeCheck} 写入 UserData 的 userId.
 * @since 1.0
 */
@WebSocket(path = "/ws/chat", inboundProcessingMode = InboundProcessingMode.SERIAL)
public class ChatWebSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(ChatWebSocket.class);

    //region 注入
    private final @NotNull ChatService chatService;
    private final @NotNull Vertx vertx;

    public ChatWebSocket(@NotNull ChatService chatService, @NotNull Vertx vertx)
    {
        this.chatService = chatService;
        this.vertx = vertx;
    }
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
     * 处理用户消息并经 {@link ChatService#streamMessage} 流式推送 AI 回复.
     * <p>入站 JSON 形如 {@code {"content": "...", "sessionId": "..."}} ({@code sessionId} 可选);
     * 身份缺失或内容为空时仅 WARN 并忽略该消息.</p>
     *
     * @param text       收到的 JSON 文本
     * @param connection 当前 WebSocket 连接
     * @implNote SERIAL 入站回调在无 Hibernate 会话上下文的 worker 线程执行, 直接启动响应式链会触发
     *           HR000068; 必须逐消息建 duplicated context (而非复用主 context) 跳转 —
     *           Hibernate 以 context 的 local 槽位存取会话, 并发消息共用主 context 槽位时,
     *           先完成者关闭 session 会导致后来者持久化静默失败.
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

            //* SERIAL 入站处理在无 Hibernate 会话上下文的 worker 线程执行, 直接启动响应式链会触发
            //! HR000068 (流式对话在会话持久化前即失败). 修法必须逐消息建 duplicated context 而非
            //* 直接 runOnContext: HR 以 Vertx.currentContext() 的 local 槽位存取会话 (withSession
            //* 复用槽内已开 session), 主 context 为全应用共享 — 并发消息会复用同一 session 槽位,
            //* 先完成者关闭 session 后, 后来者的持久化失败且仅 WARN 吞掉 (数据静默丢失).
            //* SSE REST 路径无此问题, 真因是 RESTEasy Reactive 为每个请求建独立 duplicated context,
            //* 与此处逐消息跳转同机制; SERIAL 仅保证回调启动顺序, 链路完成时长不受控, 不能依赖它隔离.
            final var messageContext = VertxContext.getOrCreateDuplicatedContext(vertx.getDelegate());
            messageContext.runOnContext((Void ignored) ->
                chatService.streamMessage(sessionId, content, userId)
                    .onItem().transformToUni(connection::sendText)
                    .concatenate()
                    .subscribe().with(
                        v -> {},
                        failure -> LOG.warn("流式对话发送失败: userId={}, {}", userId, failure.getMessage()),
                        () -> LOG.info("流式对话完成: userId={}", userId)
                    )
            );
        }
        catch(Exception e)
        {
            LOG.warn("对话消息处理失败: userId={}, {}", userId, e.getMessage());
        }
    }

    //endregion
}
