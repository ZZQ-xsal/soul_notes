package kurvcygnus.soulnotes.ai.dto;

/**
 * <b>预警检测结果</b>
 * <ul>
 *     <li>{@code warningLevel} — 预警等级 ({@code NONE} / {@code YELLOW} / {@code RED})</li>
 *     <li>{@code reason} — 触发预警的具体原因描述</li>
 *     <li>{@code suggestedAction} — 建议的干预/操作</li>
 * </ul>
 *
 * <span style="color: f84b4b">当 {@code warningLevel} 为 {@code RED} 时, 系统必须立即触发弹窗并推送热线.</span>
 * @since 2.0
 */
public record WarningDetectionResult(
    String warningLevel,
    String reason,
    String suggestedAction
)
{}
