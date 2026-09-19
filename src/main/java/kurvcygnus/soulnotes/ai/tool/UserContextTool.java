package kurvcygnus.soulnotes.ai.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import io.quarkus.hibernate.reactive.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.domain.diary.entity.MoodDiary;
import kurvcygnus.soulnotes.utils.JsonUtils;
import kurvcygnus.soulnotes.utils.PrintUtils;
import kurvcygnus.soulnotes.utils.TimeUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 用户上下文工具.
 * <p>AI Agent 可调用此工具获取用户近期的情绪状态摘要,
 * 以便在对话中提供更有针对性的共情回应.</p>
 * @since 1.0
 */
@ApplicationScoped
public final class UserContextTool
{
    private static final Logger LOG = LoggerFactory.getLogger(UserContextTool.class);

    private static final @NotNull TypeReference<Map<String, Object>> ANALYSIS_MAP_TYPE = new TypeReference<>() {};

    //* 用户上下文工具回溯天数.
    private final int recentDays;

    /**
     * CDI 构造入口.
     *
     * @param recentDays 摘要回溯天数 (ai.context.recent-days, 默认 7)
     * @since 1.1.0
     */
    public UserContextTool(@ConfigProperty(name = "ai.context.recent-days", defaultValue = "7") int recentDays) { this.recentDays = recentDays; }

    /**
     * 获取用户近期情绪摘要.
     * <p>查询最近 N 天的日记分析结果, 汇总为自然语言摘要 (篇数 + 情绪倾向 + 焦虑提示).</p>
     *
     * @param userId 用户 ID ({@code @ToolMemoryId} 透传)
     * @return 情绪摘要文本; 任何失败形态 (非法 ID/查询超时/解析异常) 都降级为固定提示文本, 绝不抛出
     */
    @Tool("获取用户近期情绪状态摘要, 以便提供更贴近用户当前心境的回应")
    @SuppressWarnings("unused")
    public @NotNull String getRecentMoodSummary(@ToolMemoryId String userId)
    {
        try
        {
            final var uuid    = java.util.UUID.fromString(userId);
            final var end     = LocalDate.now(TimeUtils.ZONE_ASIA_SHANGHAI);
            final var start   = end.minusDays(recentDays);
            final var startTs = start.atStartOfDay(TimeUtils.ZONE_ASIA_SHANGHAI).toInstant();
            final var endTs   = end.plusDays(1).atStartOfDay(TimeUtils.ZONE_ASIA_SHANGHAI).toInstant();

            //* @Tool 方法运行在 LLM 工具调用线程, 无现成 Hibernate 上下文,
            //! 必须用 Panache.withTransaction 显式开启 Session, 否则响应式查询会因缺上下文而失败.
            final var diaries = Panache.withTransaction(() -> MoodDiary.findByUserAndDateRange(uuid, startTs, endTs).list()).
                await().
                atMost(Duration.ofSeconds(5));

            if(diaries.isEmpty())
                return PrintUtils.quickFormat("用户在过去{}天内没有日记记录。", recentDays);

            return buildSummary(diaries);
        }
        catch(Exception e)
        {
            LOG.warn("获取用户情绪摘要失败: {}", e.getMessage());
            return "暂时无法获取用户近期情绪状态。";
        }
    }

    //region 摘要构建
    //* 非 static: 摘要文案需引用构造器注入的配置字段 recentDays.
    private @NotNull String buildSummary(@NotNull List<MoodDiary> diaries)
    {
        var totalPositive = .0;
        var totalNegative = .0;
        var totalAnxiety  = .0;
        var parsedCount   = 0;

        for(final var diary: diaries)
        {
            if(diary.analysisResult == null || diary.analysisResult.isBlank())
                continue;

            try
            {
                final var map = JsonUtils.parseJson(diary.analysisResult, ANALYSIS_MAP_TYPE);
                totalPositive += asDouble(map.get("positive"));
                totalNegative += asDouble(map.get("negative"));
                totalAnxiety  += asDouble(map.get("anxiety"));
                parsedCount++;
            }
            catch(Exception e) { LOG.warn("解析日记分析结果失败: {}", e.getMessage()); }
        }

        if(parsedCount == 0)
        {
            return PrintUtils.quickFormat("用户最近有 {} 条日记记录，但暂无情感分析结果。", diaries.size());
        }

        final var avgPositive = totalPositive / parsedCount;
        final var avgNegative = totalNegative / parsedCount;
        final var avgAnxiety  = totalAnxiety  / parsedCount;

        final var sb = new StringBuilder();
        sb.append(PrintUtils.quickFormat("用户近 {} 天共记录了 {} 篇日记，其中 {} 篇已分析。", recentDays, diaries.size(), parsedCount));

        if(avgPositive > avgNegative)
            sb.append("整体情绪偏向积极。");
        else if(avgNegative > .6)
            sb.append("近期负向情绪较为明显，需要更多关注和支持。");
        else
            sb.append("情绪状态整体平稳，有一定程度的波动。");

        if(avgAnxiety > .6)
            sb.append("焦虑水平偏高，值得关注。");

        return sb.toString();
    }

    private static double asDouble(Object value)
    {
        if(value instanceof Number n)
            return n.doubleValue();
        if(value instanceof String s) { try { return Double.parseDouble(s); } catch(NumberFormatException e) { return .0; } }
        return .0;
    }
    //endregion
}
