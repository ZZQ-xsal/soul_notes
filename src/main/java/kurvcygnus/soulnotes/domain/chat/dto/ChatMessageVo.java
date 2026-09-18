package kurvcygnus.soulnotes.domain.chat.dto;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * 单条聊天消息 VO, 用于向前端回放对话内容.
 *
 * @param role      角色: "user" / "assistant"
 * @param content   消息正文 (已剥离结构化契约块)
 * @param timestamp 消息时间戳 (服务端生成)
 * @since 1.0
 */
public record ChatMessageVo(
    @NotNull String  role,
    @NotNull String  content,
    @NotNull Instant timestamp
) {}