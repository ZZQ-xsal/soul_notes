package kurvcygnus.soulnotes.domain.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.TokenStream;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import kurvcygnus.soulnotes.ai.agent.EmpatheticChatAgent;
import kurvcygnus.soulnotes.ai.agent.WarningDetectionAgent;
import kurvcygnus.soulnotes.ai.dto.WarningDetectionResult;
import kurvcygnus.soulnotes.config.PromptProvider;
import kurvcygnus.soulnotes.domain.chat.entity.AiChatSession;
import kurvcygnus.soulnotes.utils.JsonUtils;
import kurvcygnus.soulnotes.utils.constants.AiPromptConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link ChatService} 反射单元测试</b>
 * <p>通过反射验证私有辅助方法的正确性, 并以 fake Agent 替身驱动 {@code callAiAndRespond}
 * 主链路, 验证结构化输出管线的契约组装与拆流落库时序 (Spec §7.5).</p>
 *
 * @author Claude Code
 * @since 1.0
 */
class ChatServiceTest
{
    //region 结构化输出管线测试基建
    private static final Vertx VERTX = Vertx.vertx();

    @AfterAll static void closeVertx() { VERTX.closeAndAwait(); }

    @BeforeAll
    @SuppressWarnings("InstantiationOfUtilityClass")//! JsonUtils 为 final 全静态成员类, IDE 误报实例化; 构造器正是 CDI 桥接注入入口.
    static void initMapper()
    {
        //* 纯单元测试无 CDI 容器, 手动构造与生产等价的 mapper (addMessage/历史构建均依赖 JsonUtils).
        new JsonUtils(new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    }

    //* 共情 Agent 替身: 记录 systemPrompt 入参供契约组装断言, chatSync 返回预置回复.
    private static final class RecordingChatAgent implements EmpatheticChatAgent
    {
        final List<String> systemPrompts = new java.util.ArrayList<>();
        private final String cannedReply;

        RecordingChatAgent(String cannedReply) { this.cannedReply = cannedReply; }

        @Override public String chatSync(String systemPrompt, String userId, String history, String content)
        {
            systemPrompts.add(systemPrompt);
            return cannedReply;
        }

        @Override public TokenStream chat(String systemPrompt, String userId, String history, String content)
        { throw new UnsupportedOperationException("本组测试只驱动同步回复路径"); }
    }

    //* 预警 Agent 替身: 恒返回 NONE, 使 applyWarning 完全静默, 排除预警推送对断言的干扰.
    private static final class StubWarningAgent implements WarningDetectionAgent
    {
        @Override public WarningDetectionResult detect(String systemPrompt, String content)
        { return new WarningDetectionResult("NONE", "", ""); }
    }

    @SuppressWarnings("ConstantConditions")//! 测试缝: Vertx 为类级共享实例, 其余未用依赖置 null 是纯单测构造服务实例的唯一途径.
    private static ChatService newService(boolean taggingOn, PromptProvider promptProvider)
    { return new ChatService(new RecordingChatAgent(""), new StubWarningAgent(), promptProvider, List.of(), VERTX, 50, taggingOn); }

    //* 主链路替身: buildSystemPrompt 在 executeBlocking 内执行, 必须注入可用的 PromptProvider (空配置 = 内置默认).
    private static ChatService newService(boolean taggingOn, EmpatheticChatAgent chatAgent)
    {
        return new ChatService(chatAgent, new StubWarningAgent(), new PromptProvider(Optional.empty(), Optional.empty(), Optional.empty()), List.of(), VERTX, 50, taggingOn);
    }

    private static AiChatSession newSession()
    {
        final var session = new AiChatSession();
        session.id = UUID.randomUUID();
        session.userId = UUID.randomUUID();
        session.messages = "[]";
        session.warningTriggered = false;
        session.updatedAt = Instant.now();
        return session;
    }

    private static String invokeBuildSystemPrompt(ChatService service) throws Exception
    {
        final var method = ChatService.class.getDeclaredMethod("buildSystemPrompt");
        method.setAccessible(true);
        return (String) method.invoke(service);
    }

    @SuppressWarnings("unchecked")//! Method.invoke 返回 Object, 泛型擦除下强转回 Uni<String> 不可避免.
    private static Uni<String> invokeCallAiAndRespond(ChatService service, AiChatSession session) throws Exception
    {
        final var method = ChatService.class.getDeclaredMethod("callAiAndRespond", AiChatSession.class, String.class);
        method.setAccessible(true);
        return (Uni<String>) method.invoke(service, session, "我睡不着");
    }

    private static List<String> assistantContents(AiChatSession session)
    {
        final var messages = JsonUtils.parseJson(session.messages, new TypeReference<List<Map<String, String>>>() {});
        return messages.stream().filter(m -> "assistant".equals(m.get("role"))).map(m -> m.get("content")).toList();
    }
    //endregion

    //region 结构化输出管线: 契约组装 (Spec §7.5)
    @Test void buildSystemPrompt_Off_ReturnsBasePromptOnly() throws Exception
    {
        assertEquals(AiPromptConstants.EMPATHETIC_CHAT_SYSTEM_PROMPT, invokeBuildSystemPrompt(newService(false, new PromptProvider(Optional.empty(), Optional.empty(), Optional.empty()))));
    }

    @Test void buildSystemPrompt_On_AppendsContractAfterBase() throws Exception
    {
        final var expected = AiPromptConstants.EMPATHETIC_CHAT_SYSTEM_PROMPT + "\n\n" + AiPromptConstants.CLINICAL_OUTPUT_CONTRACT;
        assertEquals(expected, invokeBuildSystemPrompt(newService(true, new PromptProvider(Optional.empty(), Optional.empty(), Optional.empty()))));
    }

    @Test void buildSystemPrompt_On_InstitutionalPromptStaysFirst() throws Exception
    {
        //* 合并规则 (Spec §7.5 钉死): 机构提示词在前, 功能契约段在后 — 契约首行的最高优先级声明兜底机构指令冲突.
        final var provider = new PromptProvider(Optional.of("机构自定义人设"), Optional.empty(), Optional.empty());
        assertEquals("机构自定义人设\n\n" + AiPromptConstants.CLINICAL_OUTPUT_CONTRACT, invokeBuildSystemPrompt(newService(true, provider)));
    }
    //endregion

    //region 结构化输出管线: 拆流落库时序 (Spec §7.5)
    //! 纯单测无 Hibernate 上下文, persist 必然失败并触发兜底消息路径 — 但拆流与 addMessage 发生在 persist 之前,
    //! 断言只关注 persist 前已完成的落库文本与 systemPrompt 入参, Uni 失败属预期环境限制.
    @Test void callAiAndRespond_On_SplitsBlockBeforePersist() throws Exception
    {
        final var reply = "今天辛苦了<!--soulnotes {\"tags\": [\"疲惫\"], \"riskLevel\": \"NONE\", \"summary\": \"ok\"}-->";
        final var agent = new RecordingChatAgent(reply);
        final var session = newSession();

        invokeCallAiAndRespond(newService(true, agent), session).
            onFailure().recoverWithItem(() -> null).await().atMost(Duration.ofSeconds(10));

        assertEquals(AiPromptConstants.EMPATHETIC_CHAT_SYSTEM_PROMPT + "\n\n" + AiPromptConstants.CLINICAL_OUTPUT_CONTRACT, agent.systemPrompts.getFirst(), "契约开启时 systemPrompt 必须携带契约段");
        final var stored = assistantContents(session);
        assertEquals("今天辛苦了", stored.getFirst(), "落库文本必须是剥离契约块后的正文 (历史回喂不再携带契约块)");
        assertFalse(stored.getFirst().contains("soulnotes"));
    }

    @Test void callAiAndRespond_Off_KeepsByteIdenticalBehavior() throws Exception
    {
        final var reply = "正文<!--soulnotes {\"riskLevel\": \"NONE\"}-->";
        final var agent = new RecordingChatAgent(reply);
        final var session = newSession();

        invokeCallAiAndRespond(newService(false, agent), session).
            onFailure().recoverWithItem(() -> null).await().atMost(Duration.ofSeconds(10));

        assertEquals(AiPromptConstants.EMPATHETIC_CHAT_SYSTEM_PROMPT, agent.systemPrompts.getFirst(), "契约关闭时 systemPrompt 不得附加契约段");
        assertEquals(reply, assistantContents(session).getFirst(), "off 时即使模型异常输出块也原样落库 — 与现状逐字节一致");
    }
    //endregion

    //region countMessages
    @Test void countMessages_NullInput_ShouldReturnZero() throws Exception
    {
        final var method = getStaticMethod("countMessages");
        assertEquals(0, method.invoke(null, (String) null));
    }

    @Test void countMessages_BlankInput_ShouldReturnZero() throws Exception
    {
        final var method = getStaticMethod("countMessages");
        assertEquals(0, method.invoke(null, ""));
    }

    @Test void countMessages_EmptyArray_ShouldReturnZero() throws Exception
    {
        final var method = getStaticMethod("countMessages");
        assertEquals(0, method.invoke(null, "[]"));
    }

    @Test void countMessages_SingleMessage_ShouldReturnOne() throws Exception
    {
        final var method = getStaticMethod("countMessages");
        final var json    = "[{\"role\":\"user\",\"content\":\"hello\"}]";
        assertEquals(1, method.invoke(null, json));
    }

    @Test void countMessages_MultipleMessages_ShouldReturnCount() throws Exception
    {
        final var method = getStaticMethod("countMessages");
        final var json   = "[{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\",\"content\":\"b\"}]";
        assertEquals(2, method.invoke(null, json));
    }

    @Test void countMessages_InvalidJson_ShouldReturnZero() throws Exception
    {
        final var method = getStaticMethod("countMessages");
        assertEquals(0, method.invoke(null, "{invalid}"));
    }
    //endregion

    //region getPreview
    @Test void getPreview_NullInput_ShouldReturnEmpty() throws Exception
    {
        final var method = getStaticMethod("getPreview");
        assertEquals("", method.invoke(null, (String) null));
    }

    @Test void getPreview_BlankInput_ShouldReturnEmpty() throws Exception
    {
        final var method = getStaticMethod("getPreview");
        assertEquals("", method.invoke(null, ""));
    }

    @Test void getPreview_EmptyArray_ShouldReturnEmpty() throws Exception
    {
        final var method = getStaticMethod("getPreview");
        assertEquals("", method.invoke(null, "[]"));
    }

    @Test void getPreview_ShortContent_ShouldReturnFull() throws Exception
    {
        final var method = getStaticMethod("getPreview");
        final var json   = "[{\"role\":\"user\",\"content\":\"今天心情不错\"}]";
        assertEquals("今天心情不错", method.invoke(null, json));
    }

    @Test void getPreview_LongContent_ShouldTruncate() throws Exception
    {
        final var method = getStaticMethod("getPreview");
        final var content = "a".repeat(100);
        final var json    = "[{\"role\":\"user\",\"content\":\"" + content + "\"}]";
        final var result  = (String) method.invoke(null, json);
        assertTrue(result.endsWith("..."));
        assertEquals(53, result.length()); //* 50 + "..."
    }

    @Test void getPreview_InvalidJson_ShouldReturnEmpty() throws Exception
    {
        final var method = getStaticMethod("getPreview");
        assertEquals("", method.invoke(null, "{broken"));
    }
    //endregion

    //region 反射工具
    //* 既有用例全部针对单 String 参的静态方法, 参数类型收敛在 helper 内, 免去逐用例重复传参.
    private static Method getStaticMethod(String name) throws NoSuchMethodException
    {
        final var method = ChatService.class.getDeclaredMethod(name, String.class);
        assertTrue(Modifier.isStatic(method.getModifiers()), "方法 " + name + " 应为 static");
        assertTrue(Modifier.isPrivate(method.getModifiers()), "方法 " + name + " 应为 private");
        method.setAccessible(true);
        return method;
    }
    //endregion
}
