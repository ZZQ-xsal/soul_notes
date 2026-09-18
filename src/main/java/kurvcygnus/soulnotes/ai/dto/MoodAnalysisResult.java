package kurvcygnus.soulnotes.ai.dto;

/**
 * 情感分析结果.
 * <p>由 {@code MoodAnalysisAgent} 以声明式方式返回, 自动反序列化.</p>
 *
 * @param positive 正向情感得分 (0.0~1.0)
 * @param negative 负向情感得分 (0.0~1.0)
 * @param anxiety 焦虑程度得分 (0.0~1.0)
 * @param weather 映射的天气类型标识 ({@code sunny}, {@code cloudy}, {@code overcast}, {@code rainy}, {@code thunderstorm})
 * @param summary 共情风格的一句话总结
 * @implNote 字段命名与 LLM 输出的 JSON key 严格对应, 改名即破坏 langchain4j 结构化输出反序列化.
 * @since 1.0
 */
public record MoodAnalysisResult(
    double positive,
    double negative,
    double anxiety,
    String weather,
    String summary
)
{}
