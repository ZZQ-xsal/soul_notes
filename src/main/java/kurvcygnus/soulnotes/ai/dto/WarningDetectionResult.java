package kurvcygnus.soulnotes.ai.dto;

/**
 * 预警检测结果.
 *
 * @param warningLevel 预警等级 ({@code NONE} / {@code YELLOW} / {@code RED})
 * @param reason 触发预警的具体原因描述
 * @param suggestedAction 建议的干预/操作
 * @implNote 当 {@code warningLevel} 为 {@code RED} 时, 系统必须立即触发弹窗并推送热线 (红线).
 * @since 1.0
 */
public record WarningDetectionResult(
    String warningLevel,
    String reason,
    String suggestedAction
)
{}
