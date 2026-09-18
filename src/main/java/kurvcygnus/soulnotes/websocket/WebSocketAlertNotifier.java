package kurvcygnus.soulnotes.websocket;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * RED 预警 WebSocket 渠道, 面向在线前端的 {@link IAlertNotifier} 适配器.
 * <p>包装既有 {@link AlertWebSocket#pushAlert(UUID, String)},
 * 连接注册表仍由 AlertWebSocket 持有; 用户不在线时 pushAlert 内部静默跳过,
 * 语义与收编前完全一致.</p>
 *
 * @implNote level 参数不参与 WS 负载: WS 推送仅由 RED 等级触发 (applyWarning 门控), 负载 type 恒为 RED_ALERT.
 * @since 1.1.0
 */
@ApplicationScoped
@SuppressWarnings("unused")//! AlertWebSocket 为 Quarkus 运行时生成的 @WebSocket Bean, IDE 静态分析误报未满足依赖.
public final class WebSocketAlertNotifier implements IAlertNotifier
{
    private final @NotNull AlertWebSocket alertWebSocket;

    @Inject
    public WebSocketAlertNotifier(@NotNull AlertWebSocket alertWebSocket) { this.alertWebSocket = alertWebSocket; }

    /**
     * {@inheritDoc}
     *
     * @return 固定 {@code "websocket"}
     */
    @Override public @NotNull String channel() { return "websocket"; }

    /**
     * {@inheritDoc} 将 reason 作为 WS 负载的 message 字段透传;
     * 失败仅日志的契约由 pushAlert 内部保证.
     */
    @Override public @NotNull Uni<Void> notify(@NotNull UUID userId, @NotNull String level, @NotNull String reason)
    {
        return alertWebSocket.pushAlert(userId, reason);
    }
}
