package kurvcygnus.soulnotes.domain.diary.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.domain.diary.dto.EmotionWeatherVo;
import kurvcygnus.soulnotes.domain.diary.entity.MoodDiary;
import kurvcygnus.soulnotes.utils.JsonUtils;
import kurvcygnus.soulnotes.utils.TimeUtils;
import kurvcygnus.soulnotes.utils.enums.EmotionWeatherType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 情绪天气预报服务: 按时间维度聚合情感分析数据,
 * 生成前端"情绪天气预报"可视化所需的按日聚合数据集.
 *
 * @implNote 天气类型由情感均值对阈值映射而成, 阈值可经 {@code weather.threshold.*} 配置覆盖,
 *           判定边界统一使用 >= 保证行为确定; 日期边界统一按上海时区 ({@code TimeUtils}) 换算.
 * @since 1.0
 */
@ApplicationScoped
public final class EmotionWeatherService
{
    private static final Logger LOG = LoggerFactory.getLogger(EmotionWeatherService.class);

    //* 天气映射阈值 (可由 application.properties 的 weather.threshold.* 覆盖).
    private final double stormThreshold;
    private final double rainyThreshold;
    private final double overcastThreshold;
    private final double sunnyThreshold;

    public EmotionWeatherService(
        @ConfigProperty(name = "weather.threshold.storm", defaultValue = "0.8") double stormThreshold,
        @ConfigProperty(name = "weather.threshold.rainy", defaultValue = "0.6") double rainyThreshold,
        @ConfigProperty(name = "weather.threshold.overcast", defaultValue = "0.4") double overcastThreshold,
        @ConfigProperty(name = "weather.threshold.sunny", defaultValue = "0.6") double sunnyThreshold
    )
    {
        this.stormThreshold   = stormThreshold;
        this.rainyThreshold   = rainyThreshold;
        this.overcastThreshold = overcastThreshold;
        this.sunnyThreshold   = sunnyThreshold;
    }

    //* 统一使用 TimeUtils 中定义的上海时区, 避免多处硬编码.

    /**
     * 按日聚合用户指定日期范围内的情感数据.
     *
     * @param userId 用户 ID
     * @param start  开始日期 (含, ISO 格式)
     * @param end    结束日期 (含, ISO 格式; 实际取其次日零点前为查询上界)
     * @return 按天排列的情绪天气预报 VO 列表 (无日记或分析结果缺失的日期不产出条目)
     */
    @WithTransaction
    public @NotNull Uni<List<EmotionWeatherVo>> getWeatherData(
        @NotNull UUID userId,
        @NotNull LocalDate start,
        @NotNull LocalDate end
    )
    {
        final var startInstant = start.atStartOfDay(TimeUtils.ZONE_ASIA_SHANGHAI).toInstant();
        final var endInstant   = end.plusDays(1).atStartOfDay(TimeUtils.ZONE_ASIA_SHANGHAI).toInstant();

        return MoodDiary.findByUserAndDateRange(userId, startInstant, endInstant).
            list().
            map(this::aggregateByDay);
    }

    //region 聚合逻辑

    private static final @NotNull TypeReference<Map<String, Object>> ANALYSIS_MAP_TYPE = new TypeReference<>() {};

    /**
     * 将日记列表按日分组, 计算每日情感均值并映射为天气类型.
     *
     * @param diaries 日记实体列表
     * @return 按日聚合的情绪天气预报 VO 列表; 单条 analysisResult 解析失败仅 WARN 跳过, 不影响其余聚合
     */
    private @NotNull List<EmotionWeatherVo> aggregateByDay(@NotNull List<MoodDiary> diaries)
    {
        final var dayMap = new LinkedHashMap<LocalDate, Acc>(32);

        for(final var diary : diaries)
        {
            if(diary.analysisResult == null || diary.analysisResult.isBlank())
                continue;

            try
            {
                final var map  = JsonUtils.parseJson(diary.analysisResult, ANALYSIS_MAP_TYPE);
                final var date = diary.createdAt.atZone(TimeUtils.ZONE_ASIA_SHANGHAI).toLocalDate();

                final var acc = dayMap.computeIfAbsent(date, k -> new Acc());
                acc.positiveSum += asDouble(map.get("positive"));
                acc.negativeSum += asDouble(map.get("negative"));
                acc.anxietySum  += asDouble(map.get("anxiety"));
                acc.count++;
            }
            catch(Exception e)
            {
                LOG.warn("解析日记分析结果失败: {}", e.getMessage());
                //* 单条解析失败跳过, 不影响其他日记的聚合
            }
        }

        return dayMap.entrySet().stream().map(entry -> {
            final var date = entry.getKey();
            final var acc  = entry.getValue();
            final var avgPositive = acc.positiveSum / acc.count;
            final var avgNegative = acc.negativeSum / acc.count;
            final var avgAnxiety  = acc.anxietySum  / acc.count;
            return new EmotionWeatherVo(
                date,
                mapWeather(avgPositive, avgNegative, avgAnxiety),
                avgPositive,
                avgNegative,
                avgAnxiety,
                acc.count
            );
        }).toList();
    }

    //* 天气映射阈值外置, 边界统一使用 >= 确保行为确定.
    private @NotNull EmotionWeatherType mapWeather(double positive, double negative, double anxiety)
    {
        if(anxiety >= stormThreshold || negative >= stormThreshold)
            return EmotionWeatherType.THUNDERSTORM;
        if(negative >= rainyThreshold)
            return EmotionWeatherType.RAINY;
        if(positive >= sunnyThreshold)
            return EmotionWeatherType.SUNNY;
        if(negative >= overcastThreshold)
            return EmotionWeatherType.OVERCAST;
        return EmotionWeatherType.CLOUDY;
    }

    //region 内部聚合累加器

    private static final class Acc
    {
        private double positiveSum;
        private double negativeSum;
        private double anxietySum;
        private int    count;
    }

    //endregion

    //region 辅助方法

    private static double asDouble(@NotNull Object value)
    {
        if(value instanceof Number n)
            return n.doubleValue();
        if(value instanceof String s)
            try { return Double.parseDouble(s); } catch(NumberFormatException e) { return 0.0; }
        return 0.0;
    }

    //endregion
    //endregion
}