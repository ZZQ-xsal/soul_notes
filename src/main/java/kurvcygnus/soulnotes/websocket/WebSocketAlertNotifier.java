package kurvcygnus.soulnotes.websocket;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * <b>RED 预警 WebSocket 渠道</b>
 * <p>面向在线前端 (Spec §7.2): 包装既有 {@link AlertWebSocket#pushAlert(UUID, String)},
 * 连接注册表仍由 AlertWebSocket 持有, 本类仅作为 {@link IAlertNotifier} 渠道适配器;
 * 用户不在线时 pushAlert 内部静默跳过, 语义与收编前完全一致.</p>
 * <p>//! level 参数不参与 WS 负载: WS 推送仅由 RED 等级触发 (applyWarning 门控), 负载 type 恒为 RED_ALERT.</p>
 * @since 2.0
 */
@ApplicationScoped
@SuppressWarnings("unused")//! AlertWebSocket 为 Quarkus 运行时生成的 @WebSocket Bean, IDE 静态分析误报未满足依赖.
public final class WebSocketAlertNotifier implements IAlertNotifier
{
    private final @NotNull AlertWebSocket alertWebSocket;

    @Inject
    public WebSocketAlertNotifier(@NotNull AlertWebSocket alertWebSocket) { this.alertWebSocket = alertWebSocket; }

    @Override public @NotNull String channel() { return "websocket"; }

    @Override public @NotNull Uni<Void> notify(@NotNull UUID userId, @NotNull String level, @NotNull String reason)
    {
        //* reason 即预警描述, 作为 WS 负载的 message 字段透传; 失败仅日志的契约由 pushAlert 内部保证.
        return alertWebSocket.pushAlert(userId, reason);
    }
}
