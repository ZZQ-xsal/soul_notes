package kurvcygnus.soulnotes.ai.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import kurvcygnus.soulnotes.ai.tool.CrisisInterventionTool;
import kurvcygnus.soulnotes.ai.tool.UserContextTool;
import kurvcygnus.soulnotes.utils.constants.AiPromptConstants;

/**
 * <b>共情对话 Agent</b>
 * <p>声明式 {@code @RegisterAiService} 接口, 实现「心声树洞」共情对话.</p>
 *
 * <ul>
 *     <li>{@link #chatSync(String, String)} — 非流式回复</li>
 *     <li>{@link #chat(String, String)} — 流式回复 ({@code TokenStream})</li>
 * </ul>
 *
 * <span style="color: 95cc6d">所有配置 (model, temperature 等) 由 {@code application.properties} 中的
 * {@code quarkus.langchain4j.openai.*} 统一管理.</span>
 *
 * @author Claude Code
 * @since 2.0
 */
@RegisterAiService(tools = {UserContextTool.class, CrisisInterventionTool.class})
public interface EmpatheticChatAgent
{
    /**
     * <span style="color: 95cc6d">非流式共情回复.</span>
     *
     * @param history 对话历史 (JSON 格式的消息列表)
     * @param content 用户最新消息
     * @return AI 回复文本
     */
    @SystemMessage(AiPromptConstants.EMPATHETIC_CHAT_SYSTEM_PROMPT)
    @UserMessage(
        """
        对话历史:
        {{history}}

        用户最新消息:
        {{content}}
        """
    )
    String chatSync(@V("history") String history, @V("content") String content);

    /**
     * <span style="color: 95cc6d">流式共情回复.</span>
     *
     * @param history 对话历史 (JSON 格式的消息列表)
     * @param content 用户最新消息
     * @return 流式 {@code TokenStream}
     */
    @SystemMessage(AiPromptConstants.EMPATHETIC_CHAT_SYSTEM_PROMPT)
    @UserMessage("""
        对话历史:
        {{history}}

        用户最新消息:
        {{content}}
        """)
    TokenStream chat(@V("history") String history, @V("content") String content);
}
