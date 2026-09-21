package kurvcygnus.soulnotes.domain.clinical.dto;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * 聚合统计视图 (管理驾驶舱).
 * @param byLevel       等级 → 条数 (仅含出现的等级)
 * @param byDay         按日分组 (升序)
 * @param totalStudents 窗口内出现过的去重学生数
 * @since 1.2.0
 */
public record StatsSummary(
    @NotNull Map<String, Long> byLevel,
    @NotNull List<DailyCount> byDay,
    long totalStudents
)
{
    /**
     * 单日计数.
     * @param date 日期 (ISO-8601, yyyy-MM-dd)
     * @param yellow YELLOW 条数
     * @param red   RED 条数
     */
    public record DailyCount(@NotNull String date, long yellow, long red) {}
}
