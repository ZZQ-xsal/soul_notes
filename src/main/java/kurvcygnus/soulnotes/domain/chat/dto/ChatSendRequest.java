package kurvcygnus.soulnotes.domain.chat.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 发送消息请求体.
 *
 * @param sessionId 会话 ID; {@code null} 表示开启新会话, 非法/空白值在流式路径同样回退为新会话
 * @param content   消息正文
 * @since 1.0
 */
public record ChatSendRequest(
    @Nullable UUID   sessionId,
    @NotNull String  content
) {}