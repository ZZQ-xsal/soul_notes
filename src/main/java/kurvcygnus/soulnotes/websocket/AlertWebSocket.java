package kurvcygnus.soulnotes.websocket;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.config.RedisStartupConfig;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>RED 预警推送 WebSocket</b>
 * <ul>
 *     <li>用户连接时注册到 {@link ConcurrentHashMap}, 断开时移除</li>
 *     <li>提供 {@link #pushAlert(UUID, String)} 方法供业务方触发预警推送</li>
 * </ul>
 * @since 1.0
 */
@WebSocket(path = "/ws/alert")
public class AlertWebSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(AlertWebSocket.class);

    //region 注入
    private final @NotNull RedisStartupConfig redisConfig;

    @Inject public AlertWebSocket(@NotNull RedisStartupConfig redisConfig) { this.redisConfig = redisConfig; }
    //endregion

    //region 连接追踪
    //* userId → [[WebSocketConnection]] 映射.
    private final @NotNull Map<UUID, WebSocketConnection> connections = new ConcurrentHashMap<>();

    //! 生命周期回调由框架通过反射调用, IDE 静态分析误报为未使用.
    @OnOpen @SuppressWarnings("unused")
    public void onOpen(@NotNull WebSocketConnection connection)
    {
        final var userIdStr = connection.userData().get(WebSocketAuthUpgradeCheck.USER_ID_KEY);
        if(userIdStr == null)
            return;
        final var userId = UUID.fromString(userIdStr);
        connections.put(userId, connection);
        LOG.info("预警连接已建立: userId={}", userId);
    }

    @OnClose @SuppressWarnings("unused")
    public void onClose(@NotNull WebSocketConnection connection)
    {
        final var userIdStr = connection.userData().get(WebSocketAuthUpgradeCheck.USER_ID_KEY);
        if(userIdStr == null)
            return;
        connections.remove(UUID.fromString(userIdStr));
        LOG.info("预警连接已关闭: userId={}", userIdStr);
    }
    //endregion

    //region 预警推送
    /**
     * <span style="color: f84b4b">向指定用户推送 RED 预警.</span>
     * <p>若用户不在线 (未建立 WebSocket 连接), 则静默跳过.</p>
     *
     * @param userId  目标用户 ID
     * @param message 预警消息
     * @return {@link Uni<Void>}
     */
    public @NotNull Uni<Void> pushAlert(@NotNull UUID userId, @NotNull String message)
    {
        final var conn = connections.get(userId);
        if(conn == null)
        {
            LOG.warn("用户不在线, 预警推送跳过: userId={}", userId);
            return Uni.createFrom().voidItem();
        }

        return resolveHotline().flatMap(hotline ->
            {
                final var payload = new LinkedHashMap<String, String>();
                payload.put("type", "RED_ALERT");
                payload.put("message", message);
                payload.put("hotline", hotline);

                try
                {
                    final var json = JsonUtils.toJson(payload);
                    return conn.sendText(json);
                }
                catch(Exception e)
                {
                    LOG.warn("预警推送失败: userId={}, {}", userId, e.getMessage());
                    return Uni.createFrom().voidItem();
                }
            }
        );
    }
    //endregion

    //region 热线解析
    /**
     * <span style="color: 95cc6d">从 Redis 或配置中解析热线主号码.</span>
     * <p>//* 解析逻辑收编至 {@link IAlertNotifier#primaryHotlineOf}, 与 Webhook 渠道共享同一来源, 防止两处漂移.</p>
     */
    private @NotNull Uni<String> resolveHotline()
    {
        return redisConfig.getHotline().map(IAlertNotifier::primaryHotlineOf);
    }

    //endregion
}
