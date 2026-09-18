package kurvcygnus.soulnotes.websocket;

import io.smallrye.mutiny.Uni;
import kurvcygnus.soulnotes.utils.constants.ConfigDefaults;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * <b>RED 预警通知渠道端口</b>
 * <p>通知渠道可插拔 (Spec §7.2): WebSocket 渠道面向在线前端, Webhook 渠道面向机构服务端, 互为冗余;
 * 未来短信/IM 渠道同构接入. 消费方 ({@code ChatService#applyWarning}) 注入
 * {@code List<IAlertNotifier>} 逐渠道 fire-and-forget, 依赖端口而非具体渠道.</p>
 *
 * <p>//* 不做 sealed: 渠道替身需在跨包测试源伪造 (sealed permits 无法覆盖 test 源集),
 * 与 {@code IModelCatalog} 同一定位, 允许可替换实现.</p>
 * @since 2.0
 */
public interface IAlertNotifier
{
    //region 渠道契约

    /**
     * <span style="color: 95cc6d">渠道标识.</span>
     *
     * @return 渠道名 (如 {@code "websocket"} / {@code "webhook"}), 供日志定位与测试断言
     */
    @NotNull String channel();

    /**
     * <span style="color: f84b4b">向目标用户推送 RED 预警.</span>
     * <p>热线号码等渠道资源由实现内部解析 (与 WS 推送同源, 见 {@link #primaryHotlineOf}).</p>
     * <p>//! 失败仅记 WARN 日志, 绝不向调用方抛出 — 本方法是主预警链路的安全边界,
     * 任何渠道故障 (网络/配置/热线解析) 都不允许影响会话主流程; 返回的 {@link Uni}
     * 必须失败安全 (内部已兜底, 恒成功完成).</p>
     *
     * @param userId 目标用户 ID
     * @param level  预警等级 (当前仅 {@code "RED"} 会触发渠道推送)
     * @param reason 触发预警的原因描述
     * @return 失败安全的 {@link Uni<Void>}, 由调用方订阅触发实际推送
     */
    @NotNull Uni<Void> notify(@NotNull UUID userId, @NotNull String level, @NotNull String reason);

    //endregion

    //region 热线解析 (单一来源)

    /**
     * <span style="color: 95cc6d">从热线原始串解析主号码.</span>
     * <p>Redis 存储格式 {@code "{名称}|{主号码}|{备用号码}"}; 原始串空白/缺段/主号码段空白时
     * 回退 {@link ConfigDefaults#HOTLINE_PRIMARY}
     * (离线安全网: 兜底热线必须与配置默认值同源, 不可再写字面量).</p>
     * <p>//* 静态置于端口而非实现: WS 与 Webhook 渠道必须推送同一主号码 (Spec §7.2 同源),
     * 共用此解析避免两处漂移.</p>
     *
     * @param raw 热线原始串
     * @return 主号码
     */
    static @NotNull String primaryHotlineOf(@NotNull String raw)
    {
        if(!raw.isBlank())
        {
            final var parts = raw.split("\\|");
            if(parts.length >= 2 && !parts[1].isBlank())
                return parts[1];
        }
        return ConfigDefaults.HOTLINE_PRIMARY;
    }

    //endregion
}
