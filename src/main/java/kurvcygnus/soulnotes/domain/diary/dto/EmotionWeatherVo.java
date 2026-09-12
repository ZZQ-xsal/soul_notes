package kurvcygnus.soulnotes.domain.diary.dto;

import kurvcygnus.soulnotes.utils.enums.EmotionWeatherType;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;

/**
 * <b>情绪天气预报 VO</b>
 * <p>按日聚合的情感数据, 用于前端"情绪天气预报"可视化图表渲染.</p>
 *
 * @param date         日期
 * @param weatherType  天气类型 (SUNNY / CLOUDY / OVERCAST / RAINY / THUNDERSTORM)
 * @param positiveAvg  正向情感均值 (0.0 ~ 1.0)
 * @param negativeAvg  负向情感均值 (0.0 ~ 1.0)
 * @param anxietyAvg   焦虑程度均值 (0.0 ~ 1.0)
 * @param entryCount   该日日记条数
 * @since 1.0
 */
public record EmotionWeatherVo(
    @NotNull LocalDate          date,
    @NotNull EmotionWeatherType weatherType,
    double                      positiveAvg,
    double                      negativeAvg,
    double                      anxietyAvg,
    int                         entryCount
) {}