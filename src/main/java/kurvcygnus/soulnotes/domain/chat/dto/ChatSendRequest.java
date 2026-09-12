package kurvcygnus.soulnotes.domain.chat.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * <b>发送消息请求体</b>
 *
 * @param sessionId 会话 ID (可选, 新会话则不传)
 * @param content   消息内容
 * @since 1.0
 */
public record ChatSendRequest(
    @Nullable UUID   sessionId,
    @NotNull String  content
) {}