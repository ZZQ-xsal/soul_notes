package kurvcygnus.soulnotes.websocket;

import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 咨询员工作台实时推送枢纽: 端口缝隙设计 — {@code ClinicalFeedWebSocket} 只做生命周期注册,
 * 业务侧 ({@code ClinicalAssessmentService}) 依赖本类广播, 测试以子类替身注入.
 * @implNote 与 {@code AlertWebSocket} 的 {@code pushAlert} 同款 fire-and-forget 语义:
 *           无在线咨询员静默跳过, 单连接发送失败仅 WARN — 推送绝不拖垮落库主链路.
 * @since 1.2.0
 */
@ApplicationScoped
public class ClinicalFeedHub
{
    private static final Logger LOG = LoggerFactory.getLogger(ClinicalFeedHub.class);

    //* counselorId → 连接映射 (同款 ConcurrentHashMap 模式, 见 AlertWebSocket).
    private final @NotNull Map<UUID, WebSocketConnection> connections = new ConcurrentHashMap<>();

    /**
     * 注册咨询员连接 (升级握手成功后由端点回调).
     */
    public void register(@NotNull UUID counselorId, @NotNull WebSocketConnection connection)
    {
        Objects.requireNonNull(counselorId, "Param \"counselorId\" must not be null!");
        connections.put(counselorId, connection);
        LOG.info("工作台连接已建立: counselorId={}", counselorId);
    }

    /**
     * 移除咨询员连接 (条件移除: 仅当映射中的存活连接正是本次关闭的那条时才清除).
     * <p>//! 咨询员重连竞态: 旧 socket 的 close 回调晚于新连接 register 到达时, 若无条件 remove
     * 会把刚注册的新连接一并踢掉 — 携带 connection 比对后旧连接的关闭不再误伤新连接.</p>
     */
    public void unregister(@NotNull UUID counselorId, @NotNull WebSocketConnection connection)
    {
        Objects.requireNonNull(counselorId, "Param \"counselorId\" must not be null!");
        Objects.requireNonNull(connection, "Param \"connection\" must not be null!");
        connections.remove(counselorId, connection);
    }

    /**
     * 向全部在线咨询员广播 JSON 负载.
     *
     * @param json 序列化后的推送负载
     * @return 恒成功完成 (发送失败仅 WARN — fire-and-forget 渠道安全网)
     */
    public @NotNull Uni<Void> broadcast(@NotNull String json)
    {
        if(connections.isEmpty())
            return Uni.createFrom().voidItem();
        Uni<Void> chain = Uni.createFrom().voidItem();
        for(final var conn : connections.values())
            chain = chain.chain(v ->
                conn.sendText(json).
                    onFailure().invoke(t -> LOG.warn("工作台推送失败: counselorId={}, {}", conn.userData().get(WebSocketAuthUpgradeCheck.USER_ID_KEY), t.getMessage())).
                    onFailure().recoverWithUni(() -> Uni.createFrom().voidItem())//! brief 原文 recoverWithVoid() 在 Mutiny 3.2.0 不存在, 改用 WebhookAlertNotifier 同款 voidItem 恢复.
            );
        return chain;
    }
}
