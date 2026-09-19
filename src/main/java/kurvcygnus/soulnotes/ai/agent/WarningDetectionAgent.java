package kurvcygnus.soulnotes.ai.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import kurvcygnus.soulnotes.ai.dto.WarningDetectionResult;

/**
 * 预警检测 Agent.
 * <p>声明式 {@code @RegisterAiService} 接口, 分析文本中是否存在自我伤害、自杀倾向等高风险信号.</p>
 *
 * <p>当结果为 {@code RED} 时, 系统必须触发危机干预流程 (前端弹窗 + 热线推送).</p>
 * @since 1.0
 */
@RegisterAiService
public interface WarningDetectionAgent
{
    /**
     * 检测文本中的高风险信号.
     *
     * @param systemPrompt 系统提示词 ({@code @V} 注入, 提示词配置化 ai.prompt.* 的接线点)
     * @param content 待检测文本 (用户消息/日记)
     * @return 结构化检测结果 ({@code RED} 触发危机干预流程)
     */
    //* {@code @V("systemPrompt")} 可在 {@code @SystemMessage} 模板内解析: 提示词配置化 (ai.prompt.*) 的接线点,
    //* 内置默认人设由 PromptProvider 回退保证, Agent 侧不再硬编码常量.
    @SystemMessage("{{systemPrompt}}")
    @UserMessage("{{content}}")
    WarningDetectionResult detect(@V("systemPrompt") String systemPrompt, @V("content") String content);
}
