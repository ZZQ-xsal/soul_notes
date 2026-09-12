package kurvcygnus.soulnotes.domain.chat.dto;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * <b>单条消息 VO</b>
 *
 * @param role      角色: "user" / "assistant"
 * @param content   消息内容
 * @param timestamp 消息时间戳
 * @since 1.0
 */
public record ChatMessageVo(
    @NotNull String  role,
    @NotNull String  content,
    @NotNull Instant timestamp
) {}