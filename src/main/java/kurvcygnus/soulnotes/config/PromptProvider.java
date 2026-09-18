package kurvcygnus.soulnotes.config;

import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.utils.constants.AiPromptConstants;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * <b>提示词提供者</b>
 * <p>读 ai.prompt.* 配置覆盖, 空白回退 {@link AiPromptConstants} 内置默认 (机构自定义 AI 人设钩子, Spec §4).</p>
 * @since 2.0
 */
@ApplicationScoped
public final class PromptProvider
{
    private final @NotNull Optional<String> empathetic;
    private final @NotNull Optional<String> warning;
    private final @NotNull Optional<String> mood;

    public PromptProvider(
        @ConfigProperty(name = "ai.prompt.empathetic-chat") @NotNull Optional<String> empathetic,
        @ConfigProperty(name = "ai.prompt.warning-detection") @NotNull Optional<String> warning,
        @ConfigProperty(name = "ai.prompt.mood-analysis") @NotNull Optional<String> mood
    )
    {
        this.empathetic = empathetic;
        this.warning = warning;
        this.mood = mood;
    }

    public @NotNull String empatheticChat() { return effective(empathetic, AiPromptConstants.EMPATHETIC_CHAT_SYSTEM_PROMPT); }
    public @NotNull String warningDetection() { return effective(warning, AiPromptConstants.WARNING_DETECTION_SYSTEM_PROMPT); }
    public @NotNull String moodAnalysis() { return effective(mood, AiPromptConstants.MOOD_ANALYSIS_SYSTEM_PROMPT); }

    private static @NotNull String effective(@NotNull Optional<String> override, @NotNull String fallback)
    {
        final var v = override.map(PromptProvider::normalize).map(String::strip).filter(s -> !s.isEmpty());
        return v.orElse(fallback);
    }

    //* 环境变量单行, 值中字面 \n 还原为换行 (Spec §4 多行约定).
    public static @Nullable String normalize(@Nullable String v) { return v == null ? null : v.replace("\\n", "\n"); }
}
