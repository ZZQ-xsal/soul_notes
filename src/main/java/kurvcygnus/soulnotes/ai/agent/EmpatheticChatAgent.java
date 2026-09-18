package kurvcygnus.soulnotes.ai.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import kurvcygnus.soulnotes.ai.tool.CrisisInterventionTool;
import kurvcygnus.soulnotes.ai.tool.UserContextTool;

/**
 * 共情对话 Agent.
 * <p>声明式 {@code @RegisterAiService} 接口, 实现「心声树洞」共情对话.</p>
 *
 * <ul>
 *     <li>{@link #chatSync(String, String, String, String)} — 非流式回复</li>
 *     <li>{@link #chat(String, String, String, String)} — 流式回复 ({@code TokenStream})</li>
 * </ul>
 *
 * <p>所有配置 (model, temperature 等) 由 {@code application.properties} 中的
 * {@code quarkus.langchain4j.openai.*} 统一管理.</p>
 *
 * @implNote 挂载 {@link UserContextTool} 与 {@link CrisisInterventionTool} 两个工具:
 *           前者按 {@code @MemoryId} 拉取用户上下文, 后者供 RED 预警场景的危机干预取数.
 * @since 1.0
 */
@RegisterAiService(tools = {UserContextTool.class, CrisisInterventionTool.class})
public interface EmpatheticChatAgent
{
    /**
     * 非流式共情回复.
     *
     * @param systemPrompt 系统提示词 (调用方传入 {@code PromptProvider} 解析后的生效提示词, 支持配置覆盖)
     * @param userId       用户 ID ({@code @MemoryId} 仅作为工具身份透传: 本项目未注册 ChatMemoryProvider,
     *                     不启用记忆累积, 但取值会传入工具执行上下文; 缺失时工具收到非 UUID 的默认值,
     *                     {@code UserContextTool} 解析用户 ID 必然失败)
     * @param history      对话历史 (JSON 格式的消息列表)
     * @param content      用户最新消息
     * @return AI 回复文本
     */
    //* {@code @V("systemPrompt")} 可在 {@code @SystemMessage} 模板内解析: 提示词配置化 (ai.prompt.*) 的接线点,
    //* 内置默认人设由 PromptProvider 回退保证, Agent 侧不再硬编码常量.
    @SystemMessage("{{systemPrompt}}")
    @UserMessage(
        """
        对话历史:
        {{history}}

        用户最新消息:
        {{content}}
        """
    )
    String chatSync(@V("systemPrompt") String systemPrompt, @MemoryId String userId, @V("history") String history, @V("content") String content);

    /**
     * 流式共情回复.
     *
     * @param systemPrompt 系统提示词 (用途同 {@link #chatSync})
     * @param userId       用户 ID ({@code @MemoryId}, 用途同 {@link #chatSync})
     * @param history      对话历史 (JSON 格式的消息列表)
     * @param content      用户最新消息
     * @return 流式 {@code TokenStream}, 打字机输出由消费方驱动
     */
    @SystemMessage("{{systemPrompt}}")
    @UserMessage("""
        对话历史:
        {{history}}

        用户最新消息:
        {{content}}
        """)
    TokenStream chat(@V("systemPrompt") String systemPrompt, @MemoryId String userId, @V("history") String history, @V("content") String content);
}
