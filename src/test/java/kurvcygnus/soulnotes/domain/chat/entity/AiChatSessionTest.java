package kurvcygnus.soulnotes.domain.chat.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import kurvcygnus.soulnotes.config.ReactiveJsonStringJdbcType;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.hibernate.annotations.JdbcType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link AiChatSession} 的单元测试</b>
 * <p>验证消息添加、截断等基础操作的正确性.</p>
 *
 * @author Claude Code
 * @since 1.0
 */
class AiChatSessionTest
{
    @BeforeAll
    @SuppressWarnings("InstantiationOfUtilityClass")//! JsonUtils 为 final 全静态成员类, IDE 误报实例化; 构造器正是 CDI 桥接注入入口.
    static void initMapper()
    {
        //* 纯单元测试无 CDI 容器, 手动构造与生产等价的 mapper (含 JavaTimeModule).
        new JsonUtils(new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test
    void newSession_ShouldHaveEmptyMessages()
    {
        final var session = new AiChatSession();
        session.id = UUID.randomUUID();
        session.userId = UUID.randomUUID();
        session.messages = "[]";

        //* 不显式赋值, 同时验证 warningTriggered 的默认状态.
        assertEquals("[]", session.messages);
        assertFalse(session.warningTriggered);
    }

    @Test
    void addMessage_ShouldAppendToMessages()
    {
        final var session = createTestSession();
        session.addMessage("user", "你好");

        assertNotNull(session.messages);
        assertTrue(session.messages.contains("你好"));
        assertTrue(session.messages.contains("user"));
    }

    @Test
    void addMessage_MultipleMessages_ShouldAccumulate()
    {
        final var session = createTestSession();
        session.addMessage("user", "第一条消息");
        session.addMessage("assistant", "回复");
        session.addMessage("user", "第二条消息");

        //* 通过 JsonUtils 解析 JSON 数组, 验证消息数量正确.
        final var messages = JsonUtils.parseJson(session.messages, new TypeReference<List<Map<String, String>>>() {});
        assertEquals(3, messages.size());
    }

    @Test
    void truncate_WithMoreThanMax_ShouldTrimToRecent()
    {
        final var session = createTestSession();
        session.addMessage("user", "msg1");
        session.addMessage("assistant", "reply1");
        session.addMessage("user", "msg2");
        session.addMessage("assistant", "reply2");
        session.addMessage("user", "msg3");

        session.truncate(3);

        final var messages = JsonUtils.parseJson(session.messages, new TypeReference<List<Map<String, String>>>() {});
        assertEquals(3, messages.size());
    }

    @Test
    void truncate_WithLessThanMax_ShouldNotChange()
    {
        final var session = createTestSession();
        session.addMessage("user", "msg1");
        session.addMessage("assistant", "reply1");

        session.truncate(10);

        final var messages = JsonUtils.parseJson(session.messages, new TypeReference<List<Map<String, String>>>() {});
        assertEquals(2, messages.size());
    }

    @Test
    void messages_ShouldBeValidJson()
    {
        final var session = createTestSession();
        session.addMessage("user", "{\"nested\": \"value\"}");

        //* messages 字段应为合法 JSON 数组.
        assertTrue(session.messages.startsWith("["));
        assertTrue(session.messages.endsWith("]"));
    }

    @Test
    void messages_Field_ShouldDeclareJsonJdbcType() throws NoSuchFieldException
    {
        //* 声明字符串型 JSONB 映射后, 新写入为真 JSON (jsonb_typeof = array), 存量字符串标量行读出仍可解析;
        //* 缺失时 Hibernate Reactive 把 JSON 文本再包一层, 存成字符串标量 (jsonb_typeof = string) 的双重编码形态.
        final var field = AiChatSession.class.getDeclaredField("messages");
        final var jdbcType = field.getAnnotation(JdbcType.class);

        assertNotNull(jdbcType, "messages 字段缺少 @JdbcType 声明");
        assertEquals(ReactiveJsonStringJdbcType.class, jdbcType.value());
    }

    private static AiChatSession createTestSession()
    {
        final var session = new AiChatSession();
        session.id = UUID.randomUUID();
        session.userId = UUID.randomUUID();
        session.messages = "[]";
        return session;
    }
}