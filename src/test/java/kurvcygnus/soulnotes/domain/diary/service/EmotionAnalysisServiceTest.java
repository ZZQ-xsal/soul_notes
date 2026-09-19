package kurvcygnus.soulnotes.domain.diary.service;

import io.smallrye.mutiny.Uni;
import kurvcygnus.soulnotes.ai.dto.MoodAnalysisResult;
import kurvcygnus.soulnotes.ai.dto.WarningDetectionResult;
import kurvcygnus.soulnotes.websocket.IAlertNotifier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link EmotionAnalysisService} 单元测试</b>
 * <p>覆盖两块: 反射测试私有静态方法 {@code mergeResults} 的 JSON 合并逻辑;
 * 反射驱动私有 {@code pushRedAlert} (private, 不便 {@code @link} 引用), 以 fake 渠道替身断言
 * 日记来源 RED 预警逐渠道 fan-out (websocket + webhook 双渠道 fire-and-forget, 与 ChatService 同构).</p>
 *
 * @author Claude Code
 * @since 2.0
 */
class EmotionAnalysisServiceTest
{
    //region mergeResults 合并逻辑

    private static String invokeMergeResults(MoodAnalysisResult mood, WarningDetectionResult warning) throws Exception
    {
        final var method = EmotionAnalysisService.class.getDeclaredMethod("mergeResults",
            MoodAnalysisResult.class, WarningDetectionResult.class);
        method.setAccessible(true);
        return (String) method.invoke(null, mood, warning);
    }

    @Test void mergeResults_ShouldReturnValidJson() throws Exception
    {
        final var mood = new MoodAnalysisResult(0.7, 0.2, 0.1, "sunny", "今天心情不错");
        final var warning = new WarningDetectionResult("NONE", "", "");
        final var json = invokeMergeResults(mood, warning);

        assertNotNull(json);
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
    }

    @Test void mergeResults_ShouldContainAllFields() throws Exception
    {
        final var mood = new MoodAnalysisResult(0.5, 0.3, 0.2, "cloudy", "一般");
        final var warning = new WarningDetectionResult("YELLOW", "some reason", "some action");
        final var json = invokeMergeResults(mood, warning);

        assertTrue(json.contains("\"positive\""));
        assertTrue(json.contains("\"negative\""));
        assertTrue(json.contains("\"anxiety\""));
        assertTrue(json.contains("\"weather\""));
        assertTrue(json.contains("\"summary\""));
        assertTrue(json.contains("\"warningLevel\""));
        assertTrue(json.contains("\"warningReason\""));
        assertTrue(json.contains("\"suggestedAction\""));
    }

    @Test void mergeResults_ShouldPreserveValues() throws Exception
    {
        final var mood = new MoodAnalysisResult(0.8, 0.1, 0.05, "sunny", "很棒的一天");
        final var warning = new WarningDetectionResult("NONE", "", "无需干预");
        final var json = invokeMergeResults(mood, warning);

        assertTrue(json.contains("\"positive\":0.8") || json.contains("\"positive\":0.80"));
        assertTrue(json.contains("\"weather\":\"sunny\""));
        assertTrue(json.contains("\"summary\":\"很棒的一天\""));
        assertTrue(json.contains("\"warningLevel\":\"NONE\""));
    }

    @Test void mergeResults_AllExtremeValues() throws Exception
    {
        final var mood = new MoodAnalysisResult(1.0, 1.0, 1.0, "thunderstorm", "非常糟糕");
        final var warning = new WarningDetectionResult("RED", "极端情绪", "立即干预");
        final var json = invokeMergeResults(mood, warning);

        assertTrue(json.contains("\"positive\":1.0") || json.contains("\"positive\":1"));
        assertTrue(json.contains("\"warningLevel\":\"RED\""));
        assertTrue(json.contains("\"suggestedAction\":\"立即干预\""));
    }

    @Test void mergeResults_EmptySummary() throws Exception
    {
        final var mood = new MoodAnalysisResult(0.5, 0.5, 0.5, "rainy", "");
        final var warning = new WarningDetectionResult("YELLOW", "test", "test");
        final var json = invokeMergeResults(mood, warning);

        assertTrue(json.contains("\"summary\":\"\""));
    }

    //endregion

    //region pushRedAlert 渠道 fan-out

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

    //! pushRedAlert 仅触碰 alertNotifiers, 其余依赖 (Agent/PromptProvider/Vertx) 在该测试路径
    //! 不可达, 置 null 安全; RED 载荷断言以 WS 渠道替身为观测点 (载荷新增 webhook 渠道时同构可见).
    @SuppressWarnings("ConstantConditions")//! 测试缝: 未用依赖置 null 是纯单测构造服务实例的唯一途径.
    private static EmotionAnalysisService newService(List<IAlertNotifier> notifiers)
    {
        return new EmotionAnalysisService(null, null, null, notifiers, null);
    }

    private static void invokePushRedAlert(EmotionAnalysisService service, UUID userId, String reason) throws InvocationTargetException, NoSuchMethodException, IllegalAccessException
    {
        final var method = EmotionAnalysisService.class.getDeclaredMethod("pushRedAlert", UUID.class, String.class);
        method.setAccessible(true);
        method.invoke(service, userId, reason);
    }

    @Test void pushRedAlert_DispatchesToAllChannelsWithRedPayload() throws Exception
    {
        final var websocket = new RecordingNotifier("websocket");
        final var webhook = new RecordingNotifier("webhook");
        final var userId = UUID.randomUUID();

        invokePushRedAlert(newService(List.of(websocket, webhook)), userId, "日记内容检测到自伤倾向");

        assertEquals(List.of(userId), websocket.userIds, "WS 渠道必须被推送 (日记来源 RED 与聊天同构 fan-out)");
        assertEquals(List.of("RED"), websocket.levels, "渠道载荷必须携带 RED 等级");
        assertEquals(List.of("日记内容检测到自伤倾向"), websocket.reasons, "WS 载荷 message 须为日记 RED 原因");
        assertEquals(List.of(userId), webhook.userIds, "Webhook 渠道必须同步被推送 (双渠道互为冗余)");
        assertEquals(List.of("RED"), webhook.levels, "各渠道必须收到同一 RED 等级");
        assertEquals(List.of("日记内容检测到自伤倾向"), webhook.reasons, "各渠道必须收到同一 reason");
    }

    @Test void pushRedAlert_EmptyChannels_StaysSilent() throws Exception
    {
        final var userId = UUID.randomUUID();

        assertDoesNotThrow(() -> invokePushRedAlert(newService(List.of()), userId, "无渠道也不得抛出"),
            "渠道集合为空必须静默返回, 不允许预警分发炸掉分析主流程");
    }

    //endregion
}
