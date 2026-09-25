package kurvcygnus.soulnotes.domain.chat.service;

import io.smallrye.mutiny.Uni;
import kurvcygnus.soulnotes.ai.dto.WarningDetectionResult;
import kurvcygnus.soulnotes.domain.chat.entity.AiChatSession;
import kurvcygnus.soulnotes.websocket.AlertDispatchService;
import kurvcygnus.soulnotes.websocket.IAlertNotifier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code ChatService#applyWarning} 预警分发单测</b>
 * <p>经反射驱动私有 {@code applyWarning} (private, 不便 {@code @link} 引用), RED 等级断言经
 * {@code AlertDispatchService} 统一分发后的逐渠道 fan-out (以 fake 渠道替身为观测点),
 * YELLOW 仅标记会话不触渠道, NONE/无检测结果完全静默.</p>
 * @since 1.1.0
 */
class ChatServiceWarningTest
{
    //* 渠道替身: 记录 notify 入参, 供 fan-out 断言; Uni 恒为已解析的 voidItem, subscribe 同步完成.
    private static final class RecordingNotifier implements IAlertNotifier
    {
        private final String name;
        final List<UUID> userIds = new ArrayList<>();
        final List<String> levels = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();

        RecordingNotifier(String name) { this.name = name; }

        @Override public String channel() { return name; }

        @Override public Uni<Void> notify(UUID userId, String level, String reason)
        {
            userIds.add(userId);
            levels.add(level);
            reasons.add(reason);
            return Uni.createFrom().voidItem();
        }
    }

    //! applyWarning 仅触碰 alertDispatchService 与 session, 其余依赖 (Agent/PromptProvider/归一化器/Vertx)
    //! 在该测试路径不可达, 置 null 安全 (构造器无 requireNonNull 校验); clinicalTagging 不参与该路径, 恒 false.
    //! dispatch 替身 = 真实 AlertDispatchService + 冷却逃生门 (minutes<=0 旁路冷却判定, 不触 Redis → redisDS
    //! 置 null 安全), 可观测行为与 "cooldownActive 恒放行" 等价; Task 1 测试的 "子类覆写 cooldownActive" 形态
    //! 在本包不可行 — 包级缝隙跨包不可被覆写 (Java 访问规则), 故以文档化的逃生门构造缝达成同等隔离.
    @SuppressWarnings("ConstantConditions")//! 测试缝: 未用依赖置 null 是纯单测构造服务实例的唯一途径.
    private static ChatService newService(List<IAlertNotifier> notifiers)
    {
        return new ChatService(null, null, null, null, null, new AlertDispatchService(notifiers, null, 0), null, 50, false);
    }

    private static void invokeApplyWarning(ChatService service, AiChatSession session, WarningDetectionResult detection) throws InvocationTargetException, NoSuchMethodException, IllegalAccessException
    {
        final var method = ChatService.class.getDeclaredMethod("applyWarning", AiChatSession.class, WarningDetectionResult.class);
        method.setAccessible(true);
        method.invoke(service, session, detection);
    }

    @Test void applyWarning_Red_DispatchesToAllChannelsAndFlagsSession() throws Exception
    {
        final var first = new RecordingNotifier("websocket");
        final var second = new RecordingNotifier("webhook");
        final var session = new AiChatSession();
        session.userId = UUID.randomUUID();

        invokeApplyWarning(newService(List.of(first, second)), session, new WarningDetectionResult("RED", "检测到自伤倾向", "立即干预"));

        assertEquals(List.of(session.userId), first.userIds, "渠道一必须被推送");
        assertEquals(List.of(session.userId), second.userIds, "渠道二必须被推送 (逐渠道 fan-out)");
        assertEquals("RED", first.levels.getFirst());
        assertEquals("检测到自伤倾向", first.reasons.getFirst());
        assertEquals("检测到自伤倾向", second.reasons.getFirst(), "各渠道必须收到同一 reason");
        assertTrue(session.warningTriggered, "RED 必须标记会话预警位");
    }

    @Test void applyWarning_Yellow_FlagsSessionButSkipsChannels() throws Exception
    {
        final var channel = new RecordingNotifier("websocket");
        final var session = new AiChatSession();
        session.userId = UUID.randomUUID();

        invokeApplyWarning(newService(List.of(channel)), session, new WarningDetectionResult("YELLOW", "情绪低落", "关注"));

        assertTrue(session.warningTriggered);
        assertTrue(channel.userIds.isEmpty(), "YELLOW 仅标记会话, 不得触发渠道推送");
    }

    @Test void applyWarning_NoneAndNullDetection_SkipEverything() throws Exception
    {
        final var channel = new RecordingNotifier("websocket");
        final var session = new AiChatSession();
        session.userId = UUID.randomUUID();

        invokeApplyWarning(newService(List.of(channel)), session, new WarningDetectionResult("NONE", "", ""));
        invokeApplyWarning(newService(List.of(channel)), session, null);

        assertFalse(session.warningTriggered);
        assertTrue(channel.userIds.isEmpty(), "NONE 与无检测结果均不得触发渠道推送");
    }

    //* 渠道全空: RED 仍须标记会话且不得抛出; "渠道全空"WARN 哨兵已收口于 AlertDispatchService#fanOut
    //* (AlertDispatchServiceTest 钉住), 此处只钉 ChatService 侧的可观测副作用: 会话标记与主流程存活.
    @Test void applyWarning_RedWithNoChannels_StillFlagsSessionWithoutThrowing() throws Exception
    {
        final var session = new AiChatSession();
        session.userId = UUID.randomUUID();

        assertDoesNotThrow(() -> invokeApplyWarning(newService(List.of()), session, new WarningDetectionResult("RED", "检测到自伤倾向", "立即干预")),
            "渠道全空时预警分发必须静默存活, 不允许炸掉会话主流程");
        assertTrue(session.warningTriggered, "无渠道可推也不得丢失会话预警位标记");
    }
}
