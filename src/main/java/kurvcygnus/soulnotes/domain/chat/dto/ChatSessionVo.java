package kurvcygnus.soulnotes.domain.chat.dto;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * <b>会话概览 VO</b>
 * <p>用于会话列表展示, 不包含完整消息内容.</p>
 *
 * @param sessionId     会话 ID
 * @param messageCount  消息总数
 * @param lastUpdateTime 最后更新时间
 * @param preview       最近一条消息的预览
 * @since 1.0
 */
public record ChatSessionVo(
    @NotNull UUID    sessionId,
    int              messageCount,
    @NotNull Instant lastUpdateTime,
    @NotNull String  preview
) {}