package kurvcygnus.soulnotes.config;

import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.utils.constants.AiPromptConstants;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 提示词提供者.
 * <p>读 ai.prompt.* 配置覆盖, 空白回退 {@link AiPromptConstants} 内置默认 (机构自定义 AI 人设钩子).</p>
 *
 * @implNote 构造期一次性注入四个 Optional 覆盖值, 生效值在调用期惰性求值 — 同一进程内配置不变, 无需重复读配置.
 * @since 1.1.0
 */
@ApplicationScoped
public final class PromptProvider
{
    private final @NotNull Optional<String> empathetic;
    private final @NotNull Optional<String> warning;
    private final @NotNull Optional<String> mood;
    //* 副医生结构定义 (自然语言): 空白回退 canonical 默认, 非空经 ClinicalSchemaNormalizer 归一化后上线.
    private final @NotNull Optional<String> clinicalSchema;

    /**
     * CDI 构造入口, 四个覆盖值均经 {@code @ConfigProperty} 注入.
     *
     * @param empathetic {@code ai.prompt.empathetic-chat} 覆盖; 缺失时为 empty, 由对应 getter 回退默认
     * @param warning {@code ai.prompt.warning-detection} 覆盖; 缺失时为 empty
     * @param mood {@code ai.prompt.mood-analysis} 覆盖; 缺失时为 empty
     * @param clinicalSchema {@code ai.prompt.clinical-schema} 覆盖 (副医生结构定义, 自然语言); 缺失时为 empty
     * @since 1.1.0
     */
    public PromptProvider(
        @ConfigProperty(name = "ai.prompt.empathetic-chat") @NotNull Optional<String> empathetic,
        @ConfigProperty(name = "ai.prompt.warning-detection") @NotNull Optional<String> warning,
        @ConfigProperty(name = "ai.prompt.mood-analysis") @NotNull Optional<String> mood,
        @ConfigProperty(name = "ai.prompt.clinical-schema") @NotNull Optional<String> clinicalSchema
    )
    {
        this.empathetic = empathetic;
        this.warning = warning;
        this.mood = mood;
        this.clinicalSchema = clinicalSchema;
    }

    /**
     * 共情倾听系统提示词.
     *
     * @return 配置覆盖优先, 空白/缺失回退内置默认; 永不为 null/空白
     * @since 1.1.0
     */
    public @NotNull String empatheticChat() { return effective(empathetic, AiPromptConstants.EMPATHETIC_CHAT_SYSTEM_PROMPT); }

    /**
     * 高危预警检测系统提示词.
     *
     * @return 配置覆盖优先, 空白/缺失回退内置默认; 永不为 null/空白
     * @since 1.1.0
     */
    public @NotNull String warningDetection() { return effective(warning, AiPromptConstants.WARNING_DETECTION_SYSTEM_PROMPT); }

    /**
     * 情绪分析系统提示词.
     *
     * @return 配置覆盖优先, 空白/缺失回退内置默认; 永不为 null/空白
     * @since 1.1.0
     */
    public @NotNull String moodAnalysis() { return effective(mood, AiPromptConstants.MOOD_ANALYSIS_SYSTEM_PROMPT); }

    /**
     * 副医生输出结构定义.
     *
     * @return 空白回退 canonical 默认, 非空值经 {@link ClinicalSchemaNormalizer} 归一化后上线; 永不为 null/空白
     * @since 1.1.0
     */
    public @NotNull String clinicalSchema() { return effective(clinicalSchema, AiPromptConstants.CLINICAL_OUTPUT_SCHEMA_DEFAULT); }

    /**
     * 求生效值: 归一化 (字面 \n 还原) + 去首尾空白后仍非空则采用, 否则回退默认.
     *
     * @param override 配置覆盖值
     * @param fallback 内置默认提示词
     * @return 生效提示词, 绝不为 null/空白
     * @since 1.1.0
     */
    private static @NotNull String effective(@NotNull Optional<String> override, @NotNull String fallback)
    {
        final var v = override.map(PromptProvider::normalize).map(String::strip).filter(s -> !s.isEmpty());
        return v.orElse(fallback);
    }

    /**
     * 环境变量单行约定的还原: 值中字面 {@code \n} 还原为真实换行 (多行提示词约定); null 恒等返回.
     *
     * @param v 原始配置值
     * @return 还原后的值, null 入 null 出
     * @since 1.1.0
     */
    public static @Nullable String normalize(@Nullable String v) { return v == null ? null : v.replace("\\n", "\n"); }
}
