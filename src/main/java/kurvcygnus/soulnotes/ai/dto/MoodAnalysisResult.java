package kurvcygnus.soulnotes.ai.dto;

/**
 * <b>情感分析结果</b>
 * <ul>
 *     <li>{@code positive} — 正向情感得分 (0.0~1.0)</li>
 *     <li>{@code negative} — 负向情感得分 (0.0~1.0)</li>
 *     <li>{@code anxiety} — 焦虑程度得分 (0.0~1.0)</li>
 *     <li>{@code weather} — 映射的天气类型标识 ({@code sunny}, {@code cloudy}, {@code overcast}, {@code rainy}, {@code thunderstorm})</li>
 *     <li>{@code summary} — 共情风格的一句话总结</li>
 * </ul>
 *
 * <span style="color: 95cc6d">由 {@code MoodAnalysisAgent} 以声明式方式返回, 自动反序列化.</span>
 * <span style="color: f84b4b">字段命名与 LLM 输出的 JSON key 严格对应.</span>
 * @since 2.0
 */
public record MoodAnalysisResult(
    double positive,
    double negative,
    double anxiety,
    String weather,
    String summary
) {}
