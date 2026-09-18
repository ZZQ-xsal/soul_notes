package kurvcygnus.soulnotes.config;

import kurvcygnus.soulnotes.utils.constants.AiPromptConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptProviderTest
{
    @SuppressWarnings("ConstantConditions")//! IDE 数据流分析可静态判定 normalize(null) 恒为 null, 但该断言本身正是契约 (null 透传), 保留以守护语义.
    @Test void normalizeConvertsLiteralNewlines()
    { assertEquals("a\nb", PromptProvider.normalize("a\\nb")); assertNull(PromptProvider.normalize(null)); }

    @Test void fallbackToConstantWhenBlank() //* 构造器注入 Optional 空值场景
    {
        final var p = new PromptProvider(java.util.Optional.empty(), java.util.Optional.of("  "), java.util.Optional.of("x\\ny"));
        assertEquals(AiPromptConstants.EMPATHETIC_CHAT_SYSTEM_PROMPT, p.empatheticChat());
        assertEquals(AiPromptConstants.WARNING_DETECTION_SYSTEM_PROMPT, p.warningDetection());
        assertEquals("x\ny", p.moodAnalysis());
    }
}
