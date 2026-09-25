package kurvcygnus.soulnotes.domain.chat.dto;

import org.jetbrains.annotations.NotNull;

/**
 * 历史消息条目, 用于按会话回放完整对话 (含 LLM 回复).
 *
 * @param role    角色: "user" / "assistant"
 * @param content 消息正文 (落库时已剥离结构化契约块)
 * @implNote 不含时间戳: 会话消息 JSONB 仅存 role/content, 历史回放按对话顺序排列.
 * @since 1.2.1
 */
public record ChatHistoryMessage(
    @NotNull String role,
    @NotNull String content
) {}
