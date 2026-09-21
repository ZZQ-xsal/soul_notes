package kurvcygnus.soulnotes.domain.clinical.dto;

import com.fasterxml.jackson.databind.JsonNode;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * 咨询员视角的评估视图对象 — REST 与 WS 推送共用的统一条目形态.
 * <p>{@code userId} 与 {@code displayName} 已过 {@code RevealPolicy} 脱敏 (短码或实名).</p>
 * @param id          评估 ID
 * @param userId      学生标识 (实名解锁为完整 UUID, 否则 8 位短码)
 * @param displayName 实名或 "学生 #短码"
 * @param riskLevel   风险等级 (YELLOW / RED)
 * @param summary     canonical 摘要
 * @param tags        结构化载荷 (JSON 对象透传)
 * @param sessionId   来源会话 ID
 * @param createdAt   评估时刻
 * @since 1.2.0
 */
public record AssessmentVo(
    @NotNull UUID id,
    @NotNull String userId,
    @NotNull String displayName,
    @NotNull String riskLevel,
    @NotNull String summary,
    @NotNull JsonNode tags,
    @NotNull UUID sessionId,
    @NotNull Instant createdAt
) {}
