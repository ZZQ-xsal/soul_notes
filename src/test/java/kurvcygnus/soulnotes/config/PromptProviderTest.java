package kurvcygnus.soulnotes.config;

import kurvcygnus.soulnotes.utils.constants.AiPromptConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptProviderTest
{
    @SuppressWarnings("ConstantConditions")//! IDE 数据流分析可静态判定 normalize(null) 恒为 null, 但该断言本身正是契约 (null 透传), 保留以守护语义.
    @Test void normalizeConvertsLiteralNewlines()
    {
        assertEquals("a\nb", PromptProvider.normalize("a\\nb"));
        assertNull(PromptProvider.normalize(null));
    }

    @Test void fallbackToConstantWhenBlank() //* 构造器注入 Optional 空值场景
    {
        final var p = new PromptProvider(java.util.Optional.empty(), java.util.Optional.of("  "), java.util.Optional.of("x\\ny"), java.util.Optional.empty());
        assertEquals(AiPromptConstants.EMPATHETIC_CHAT_SYSTEM_PROMPT, p.empatheticChat());
        assertEquals(AiPromptConstants.WARNING_DETECTION_SYSTEM_PROMPT, p.warningDetection());
        assertEquals("x\ny", p.moodAnalysis());
    }

    //region clinicalSchema
    @Test void clinicalSchema_FallsBackToCanonicalDefaultWhenBlank()
    {
        final var p = new PromptProvider(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of("   "));
        assertEquals(AiPromptConstants.CLINICAL_OUTPUT_SCHEMA_DEFAULT, p.clinicalSchema());
    }

    @Test void clinicalSchema_CustomOverrideWins()
    {
        final var p = new PromptProvider(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of("输出 gad7 分数与风险等级"));
        assertEquals("输出 gad7 分数与风险等级", p.clinicalSchema());
    }

    @Test void clinicalSchema_RestoresLiteralNewlines()
    {
        final var p = new PromptProvider(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of("行1\\n行2"));
        assertEquals("行1\n行2", p.clinicalSchema());
    }
    //endregion
}
