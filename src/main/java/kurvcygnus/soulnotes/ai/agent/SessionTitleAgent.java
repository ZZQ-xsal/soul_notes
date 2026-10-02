package kurvcygnus.soulnotes.ai.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * 会话标题生成 Agent.
 * <p>声明式 {@code @RegisterAiService} 接口, 把用户开场白概括成历史会话列表展示的短标题.</p>
 *
 * <p>所有配置 (model, temperature 等) 由 {@code application.properties} 中的
 * {@code quarkus.langchain4j.openai.*} 统一管理, 与共情对话共用同一模型.</p>
 *
 * @implNote 不挂载工具也不注入 {@code @MemoryId}: 标题是一次性无状态调用, 与对话记忆无关;
 *           返回纯文本而非结构化 JSON — 标题本身就是模型直出的一句话, 清洗与截断由
 *           {@code ChatService} 在落库前完成.
 * @since 1.4.0
 */
@RegisterAiService
public interface SessionTitleAgent
{
    /**
     * 依据用户开场白生成会话标题.
     *
     * @param systemPrompt 系统提示词 (调用方传入 {@code PromptProvider} 解析后的生效提示词, 支持配置覆盖)
     * @param content      用户在本会话中的第一条消息
     * @return 模型直出的标题文本 (未清洗, 由调用方归一化)
     */
    //* {@code @V("systemPrompt")} 可在 {@code @SystemMessage} 模板内解析: 提示词配置化 (ai.prompt.*) 的接线点,
    //* 内置默认由 PromptProvider 回退保证, Agent 侧不再硬编码常量.
    @SystemMessage("{{systemPrompt}}")
    @UserMessage("用户开场白: {{content}}")
    String generate(@V("systemPrompt") String systemPrompt, @V("content") String content);
}
