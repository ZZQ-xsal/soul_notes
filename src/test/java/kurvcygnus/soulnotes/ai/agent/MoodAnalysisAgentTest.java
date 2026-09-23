package kurvcygnus.soulnotes.ai.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import kurvcygnus.soulnotes.ai.dto.MoodAnalysisResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link MoodAnalysisAgent} 接口签名验证</b>
 *
 * @author Claude Code
 * @since 1.1.0
 */
class MoodAnalysisAgentTest
{
    @Test void interface_ShouldBeAnnotatedWithRegisterAiService()
    {
        assertTrue(MoodAnalysisAgent.class.isAnnotationPresent(RegisterAiService.class));
    }

    //* 提示词配置化 后签名: analyze(systemPrompt, content), 首参 @V("systemPrompt") 由调用方传入生效提示词.
    @Test void method_analyze_ShouldHaveCorrectSignature() throws Exception
    {
        final var method = MoodAnalysisAgent.class.getDeclaredMethod("analyze", String.class, String.class);
        assertNotNull(method);
        assertEquals(MoodAnalysisResult.class, method.getReturnType());
        assertTrue(method.isAnnotationPresent(SystemMessage.class));
        assertTrue(method.isAnnotationPresent(UserMessage.class));
    }

    @Test void method_analyze_ShouldHaveVAnnotation() throws Exception
    {
        final var method = MoodAnalysisAgent.class.getDeclaredMethod("analyze", String.class, String.class);
        final var params = method.getParameters();
        assertEquals(2, params.length);
        assertEquals("systemPrompt", params[0].getAnnotation(V.class).value());
        assertEquals("content", params[1].getAnnotation(V.class).value());
    }

    //* @SystemMessage 必须委托给 {{systemPrompt}} 模板变量 (langchain4j 从所有 @V 参数解析), 否则配置覆盖不生效.
    @Test void method_analyze_SystemMessage_ShouldDelegateToSystemPromptVariable() throws Exception
    {
        final var method = MoodAnalysisAgent.class.getDeclaredMethod("analyze", String.class, String.class);
        assertArrayEquals(new String[] {"{{systemPrompt}}"}, method.getAnnotation(SystemMessage.class).value());
    }
}
