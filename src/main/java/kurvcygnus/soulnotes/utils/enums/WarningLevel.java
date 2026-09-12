package kurvcygnus.soulnotes.utils.enums;

/**
 * <b>预警等级枚举</b>
 * <ul>
 *     <li>{@link #NONE} — 正常, 无需干预</li>
 *     <li>{@link #YELLOW} — 需关注, 持续低落或消极言语</li>
 *     <li>{@link #RED} — 立即干预, 检测到自残/自杀意念等高危信号</li>
 * </ul>
 * @since 1.0
 */
public enum WarningLevel
{
    NONE,
    YELLOW,
    RED
}