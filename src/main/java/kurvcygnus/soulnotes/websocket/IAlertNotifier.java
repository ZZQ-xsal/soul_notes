package kurvcygnus.soulnotes.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import kurvcygnus.soulnotes.utils.JsonUtils;
import kurvcygnus.soulnotes.utils.constants.ConfigDefaults;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * RED 预警通知渠道端口, 是预警 fan-out 的可插拔抽象.
 * <p>WebSocket 渠道面向在线前端, Webhook 渠道面向机构服务端, 互为冗余;
 * 未来短信/IM 渠道同构接入.消费方 ({@code ChatService#applyWarning}) 注入
 * {@code List<IAlertNotifier>} 逐渠道 fire-and-forget, 依赖端口而非具体渠道.</p>
 *
 * <p>不做 sealed: 渠道替身需在跨包测试源伪造 (sealed permits 无法覆盖 test 源集),
 * 与 {@code IModelCatalog} 同一定位, 允许可替换实现.</p>
 *
 * @implNote 实现契约: {@link #notify} 必须失败安全 (内部兜底, 恒成功完成), 且惰性 —
 *           由调用方订阅才真正触发推送.
 * @since 1.1.0
 */
@SuppressWarnings("NullableProblems") public interface IAlertNotifier
{
    //region 渠道契约

    /**
     * 渠道标识.
     *
     * @return 渠道名 (如 {@code "websocket"} / {@code "webhook"}), 供日志定位与测试断言
     */
    @NotNull String channel();

    /**
     * 向目标用户推送 RED 预警, 热线号码等渠道资源由实现内部解析 (与 WS 推送同源, 见 {@link #primaryHotlineOf}).
     *
     * @param userId 目标用户 ID
     * @param level  预警等级 (当前仅 {@code "RED"} 会触发渠道推送)
     * @param reason 触发预警的原因描述
     * @return 失败安全的 {@link Uni<Void>} (内部已兜底, 恒成功完成), 由调用方订阅触发实际推送
     * @implNote 失败仅记 WARN 日志, 绝不向调用方抛出 — 本方法是主预警链路的安全边界,
     *            任何渠道故障 (网络/配置/热线解析) 都不允许影响会话主流程.
     */
    @NotNull Uni<Void> notify(@NotNull UUID userId, @NotNull String level, @NotNull String reason);

    //endregion

    //region 热线解析 (单一来源)

    /**
     * 从热线原始串解析主号码, 是所有渠道热线推送的单一来源.
     *
     * @param raw 热线原始串
     * @return 主号码; 原始串空白/缺段/主号码段空白时回退 {@link ConfigDefaults#HOTLINE_PRIMARY}
     * @implNote Redis 存储格式为 {@code "{名称}|{主号码}|{备用号码}"}; 兜底热线必须与配置默认值同源,
     *           不可再写字面量 (离线安全网).静态置于端口而非实现: WS 与 Webhook 渠道必须推送同一主号码,
     *           共用此解析避免两处漂移.
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

    //region 机器人业务失败判定 (钉钉/企微共用)

    /**
     * 群机器人业务失败判定: 两家机器人 API 同以 HTTP 200 + {@code {"errcode":非0,"errmsg":..}} 表达业务故障
     * (加签错 errcode=310000 / key 失效 errcode=40001 / 限流), 仅凭状态码判定会让渠道静默死亡.
     * errcode = 0 视为成功; 报文不可解析 (代理/网关垃圾响应) 同样按失败告警.
     *
     * @param body 响应报文
     * @return 业务失败时的告警文本 (含 errcode/errmsg); 成功返回 {@code null}
     * @implNote 静态置于端口而非实现: 钉钉与企微的失败语义判定完全同构, 共用一份避免双处漂移
     *           (裁决 6 禁的是抽基类, 端口静态助手与 {@link #primaryHotlineOf} 同一先例).
     */
    static @Nullable String businessFailureOf(@NotNull String body)
    {
        try
        {
            final var root = JsonUtils.parseJson(body, JsonNode.class);
            final var errcode = root.path("errcode").asLong(-1);
            return errcode == 0 ? null : "errcode=" + errcode + ", errmsg=" + root.path("errmsg").asText("");
        }
        catch(RuntimeException e) { return "errcode=unparsable, body=" + truncate(body); }
    }

    //* 报文截断: 不可解析响应原文进告警日志时防爆量 (告警是留痕不是档案, 与 IM 渠道 REASON_MAX 纪律同源).
    private static @NotNull String truncate(@NotNull String body) { return body.length() > 200 ? body.substring(0, 200) + "..." : body; }

    //endregion
}
