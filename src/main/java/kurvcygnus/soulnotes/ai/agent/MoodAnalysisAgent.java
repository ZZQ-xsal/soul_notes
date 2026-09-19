package kurvcygnus.soulnotes.ai.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import kurvcygnus.soulnotes.ai.dto.MoodAnalysisResult;

/**
 * 情感分析 Agent.
 * <p>声明式 {@code @RegisterAiService} 接口, 分析用户日记文本中的情感倾向.</p>
 *
 * <p>所有配置 (model, temperature 等) 由 {@code application.properties} 中的
 * {@code quarkus.langchain4j.openai.*} 统一管理.</p>
 *
 * @implNote 返回类型为结构化 {@link MoodAnalysisResult}: langchain4j 以 JSON 模式约束输出并反序列化,
 *           情绪 valence/anxiety 数值供 "Emotion Weather Forecast" 可视化消费.
 * @since 1.0
 */
@RegisterAiService
public interface MoodAnalysisAgent
{
    /**
     * 分析日记文本的情感倾向.
     *
     * @param systemPrompt 系统提示词 ({@code @V} 注入, 提示词配置化 ai.prompt.* 的接线点)
     * @param content 日记正文
     * @return 结构化情感分析结果 (情绪价/焦虑值等)
     */
    //* {@code @V("systemPrompt")} 可在 {@code @SystemMessage} 模板内解析: 提示词配置化 (ai.prompt.*) 的接线点,
    //* 内置默认人设由 PromptProvider 回退保证, Agent 侧不再硬编码常量.
    @SystemMessage("{{systemPrompt}}")
    @UserMessage("日记内容: {{content}}")
    MoodAnalysisResult analyze(@V("systemPrompt") String systemPrompt, @V("content") String content);
}
