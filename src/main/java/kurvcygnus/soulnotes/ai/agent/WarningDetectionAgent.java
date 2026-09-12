package kurvcygnus.soulnotes.ai.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import kurvcygnus.soulnotes.ai.dto.WarningDetectionResult;
import kurvcygnus.soulnotes.utils.constants.AiPromptConstants;

/**
 * <b>预警检测 Agent</b>
 * <p>声明式 {@code @RegisterAiService} 接口, 分析文本中是否存在自我伤害、自杀倾向等高风险信号.</p>
 *
 * <span style="color: f84b4b">当结果为 {@code RED} 时, 系统必须触发危机干预流程.</span>
 * @since 2.0
 */
@RegisterAiService
public interface WarningDetectionAgent
{
    @SystemMessage(AiPromptConstants.WARNING_DETECTION_SYSTEM_PROMPT)
    @UserMessage("{{content}}")
    WarningDetectionResult detect(@V("content") String content);
}
