package kurvcygnus.soulnotes.domain.diary.service;

import io.quarkus.arc.All;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.ai.agent.MoodAnalysisAgent;
import kurvcygnus.soulnotes.ai.agent.WarningDetectionAgent;
import kurvcygnus.soulnotes.ai.dto.MoodAnalysisResult;
import kurvcygnus.soulnotes.ai.dto.WarningDetectionResult;
import kurvcygnus.soulnotes.config.PromptProvider;
import kurvcygnus.soulnotes.domain.diary.entity.MoodDiary;
import kurvcygnus.soulnotes.utils.JsonUtils;
import kurvcygnus.soulnotes.utils.PrintUtils;
import kurvcygnus.soulnotes.websocket.IAlertNotifier;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * <b>情感分析服务</b>
 * <ul>
 *     <li>封装 AI {@code MoodAnalysisAgent} 与 {@code WarningDetectionAgent} 的调用编排</li>
 *     <li>分析结果回写 {@link MoodDiary#analysisResult} JSONB 字段</li>
 * </ul>
 * @since 2.0
 */
@ApplicationScoped
@SuppressWarnings("unused")//! AI Agent 为 Quarkus 运行时生成 Bean, IDE 静态分析误报未满足依赖.
public final class EmotionAnalysisService
{
    private static final @NotNull Logger LOG = PrintUtils.getLogger();

    private final @NotNull MoodAnalysisAgent moodAnalysisAgent;
    private final @NotNull WarningDetectionAgent warningDetectionAgent;
    private final @NotNull PromptProvider promptProvider;
    //* 预警渠道 fan-out (与 ChatService 同构): 日记来源 RED 与聊天共用 websocket/webhook 双渠道互为冗余.
    //* @All 是 Arc 集合注入的必要限定符: 缺失时注入点退化为对 List 类型 bean 的普通解析, 应用启动即
    //! UnsatisfiedResolutionException (渠道全部缺席时 @All 语义为注入空集合, 不阻断启动).
    private final @NotNull List<IAlertNotifier> alertNotifiers;
    private final @NotNull Vertx vertx;

    public EmotionAnalysisService(
        @NotNull MoodAnalysisAgent moodAnalysisAgent,
        @NotNull WarningDetectionAgent warningDetectionAgent,
        @NotNull PromptProvider promptProvider,
        @All @NotNull List<IAlertNotifier> alertNotifiers,
        @NotNull Vertx vertx
    )
    {
        this.moodAnalysisAgent = moodAnalysisAgent;
        this.warningDetectionAgent = warningDetectionAgent;
        this.promptProvider = promptProvider;
        this.alertNotifiers = alertNotifiers;
        this.vertx = vertx;
    }

    /**
     * <span style="color: 95cc6d">异步情感分析 (不阻塞主流程, 用户无需等待).</span>
     * <p>适用于创建日记等场景 — 先保存 Diary, 后台线程执行 AI 分析, 完成后再回写结果.</p>
     *
     * <span style="color: f84b4b">AI 调用是同步 HTTP 请求, 通过 {@code runSubscriptionOn} 移交 worker 线程池,
     * 避免阻塞事件循环; 持久化前必须 {@code emitOn} 回到事件循环上下文, 否则触发 HR000069.</span>
     *
     * @param diary 已持久化的日记实体
     * @return 更新后的日记实体 (含 analysisResult)
     */
    public @NotNull Uni<MoodDiary> analyzeAsync(@NotNull MoodDiary diary)
    {
        return analyzeAndDetect(diary).
            onFailure().invoke(t -> LOG.error("AI 分析失败, 跳过 analysisResult 回写", t)).
            onFailure().recoverWithItem(diary);
    }

    /**
     * <span style="color: f84b4b">同步情感分析与预警检测 (高优场景).</span>
     * <p>适用于需要立即返回分析结果的场景, 调用方需等待分析结果持久化完成.</p>
     *
     * @param diary 已持久化的日记实体
     * @return 更新后的日记实体 (含 analysisResult)
     */
    public @NotNull Uni<MoodDiary> analyzeAndDetect(@NotNull MoodDiary diary)
    {
        //! HR000068/069: Hibernate reactive Session 只能在打开它的 Vert.x 事件循环线程使用.
        //! 必须用 vertx.executeBlocking 执行阻塞 AI 调用 (worker 线程), 其结果在事件循环回调,
        //! 之后的 persistAndFlush 才能安全访问请求上下文中的 Session.
        return vertx.executeBlocking(
                () ->
                {
                    final var moodResult = moodAnalysisAgent.analyze(promptProvider.moodAnalysis(), diary.content);
                    final var warningResult = warningDetectionAgent.detect(promptProvider.warningDetection(), diary.content);
                    diary.analysisResult = mergeResults(moodResult, warningResult);

                    //* 日记场景在线 RED 预警: 逐渠道 fire-and-forget 推送 (websocket + webhook 互为冗余).
                    if("RED".equals(warningResult.warningLevel()))
                        pushRedAlert(diary.userId, warningResult.reason());
                    return diary;
                },
                false
            ).
            chain(d -> d.persistAndFlush().onItem().transform(v -> d));//! 持久化 AI 分析结果
    }

    //region 辅助方法

    //* 日记来源 RED 预警的渠道分发 (与 ChatService#applyWarning 同构): 逐渠道 fire-and-forget, 不回落具体渠道.
    //* Uni 是惰性的, 必须订阅才真正触发推送; 渠道实现保证失败仅日志 (接口契约),
    //! 订阅级兜底仅防渠道外的意外实现缺陷, 不允许预警分发拖垮分析主流程.
    private void pushRedAlert(@NotNull UUID userId, @NotNull String reason)
    {
        for(final var notifier : alertNotifiers)
            notifier.notify(userId, "RED", reason).
                subscribe().with(
                    v -> {},
                    t -> LOG.warn("日记 RED 预警推送执行失败: channel={}, userId={}, {}", notifier.channel(), userId, t.getMessage())
                );
    }

    private static @NotNull String mergeResults(
        @NotNull MoodAnalysisResult mood,
        @NotNull WarningDetectionResult warning
    )
    {
        final var map = new LinkedHashMap<String, Object>();
        map.put("positive", mood.positive());
        map.put("negative", mood.negative());
        map.put("anxiety", mood.anxiety());
        map.put("weather", mood.weather());
        map.put("summary", mood.summary());
        map.put("warningLevel", warning.warningLevel());
        map.put("warningReason", warning.reason());
        map.put("suggestedAction", warning.suggestedAction());
        return JsonUtils.toJson(map);
    }

    //endregion
}