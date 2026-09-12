package kurvcygnus.soulnotes.ai.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import kurvcygnus.soulnotes.ai.dto.MoodAnalysisResult;
import kurvcygnus.soulnotes.utils.constants.AiPromptConstants;

/**
 * <b>情感分析 Agent</b>
 * <p>声明式 {@code @RegisterAiService} 接口, 分析用户日记文本中的情感倾向.</p>
 *
 * <span style="color: 95cc6d">所有配置 (model, temperature 等) 由 {@code application.properties} 中的
 * {@code quarkus.langchain4j.openai.*} 统一管理.</span>
 * @since 2.0
 */
@RegisterAiService
public interface MoodAnalysisAgent
{
    @SystemMessage(AiPromptConstants.MOOD_ANALYSIS_SYSTEM_PROMPT)
    @UserMessage("日记内容: {{content}}")
    MoodAnalysisResult analyze(@V("content") String content);
}
