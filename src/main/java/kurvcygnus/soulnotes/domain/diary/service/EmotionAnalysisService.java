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
 * 情感分析服务, 编排 AI {@code MoodAnalysisAgent} 与 {@code WarningDetectionAgent} 的调用,
 * 并将合并结果回写 {@link MoodDiary#analysisResult} JSONB 字段.
 * <p>检测到 RED 级预警时, 经通知渠道矩阵 fan-out 推送热线, 与聊天链路共用渠道.</p>
 *
 * @implNote 阻塞 AI 调用统一经 {@code vertx.executeBlocking} 在 worker 线程执行,
 *           结果回事件循环后再操作 Hibernate reactive Session (规避 HR000068/069).
 * @since 1.0
 */
@ApplicationScoped
@SuppressWarnings("unused")//! AI Agent 为 Quarkus 运行时生成 Bean, IDE 静态分析误报未满足依赖.
public final class EmotionAnalysisService
{
    private static final @NotNull Logger LOG = PrintUtils.getLogger();

    private final @NotNull MoodAnalysisAgent moodAnalysisAgent;
    private final @NotNull WarningDetectionAgent warningDetectionAgent;
    private final @NotNull PromptProvider promptProvider;
    //* 预警渠道 fan-out (与 ChatService 同构): 日记来源 RED 与聊天共用通知渠道矩阵 (配置即启用, 互为冗余).
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
     * 异步情感分析: AI 调用失败仅记 error 日志并原样返回日记, 不向调用方抛错.
     * <p>适用于创建日记等场景 — 用户无需等待分析结果, 分析失败仅表现为 analysisResult 未回写.</p>
     *
     * @param diary 已持久化的日记实体
     * @return 更新后的日记实体 (成功时含 analysisResult); 失败时为原实体 (结果字段保持不变)
     */
    public @NotNull Uni<MoodDiary> analyzeAsync(@NotNull MoodDiary diary)
    {
        return analyzeAndDetect(diary).
            onFailure().invoke(t -> LOG.error("AI 分析失败, 跳过 analysisResult 回写", t)).
            onFailure().recoverWithItem(diary);
    }

    /**
     * 同步情感分析与预警检测: 调用方需等待分析结果持久化完成后才继续.
     * <p>适用于需要立即得到分析结果的高优场景; 分析结果合并落库,
     * RED 级预警同时触发多渠道推送.</p>
     *
     * @param diary 已持久化的日记实体
     * @return 更新后的日记实体 (含 analysisResult)
     * @implNote HR000068/069: Hibernate reactive Session 只能在打开它的 Vert.x 事件循环线程使用,
     *           因此阻塞 AI 调用经 {@code vertx.executeBlocking} 在 worker 线程执行, 结果回事件循环回调,
     *           之后的 {@code persistAndFlush} 才能安全访问请求上下文中的 Session.
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

                    //* 日记场景在线 RED 预警: 逐渠道 fire-and-forget 推送 (通知渠道矩阵互为冗余).
                    if("RED".equals(warningResult.warningLevel()))
                        pushRedAlert(diary.userId, warningResult.reason());
                    return diary;
                },
                false
            ).
            chain(d -> d.persistAndFlush().onItem().transform(v -> d));//! 持久化 AI 分析结果
    }

    //region 辅助方法

    /**
     * 日记来源 RED 预警的渠道分发: 逐渠道 fire-and-forget 推送热线, 不回落具体渠道.
     *
     * @param userId 目标用户 ID
     * @param reason 触发预警的原因描述
     * @since 1.1.0
     */
    //* Uni 是惰性的, 必须订阅才真正触发推送; 渠道实现保证失败仅日志 (接口契约),
    //! 订阅级兜底仅防渠道外的意外实现缺陷, 不允许预警分发拖垮分析主流程.
    private void pushRedAlert(@NotNull UUID userId, @NotNull String reason)
    {
        for(final var notifier : alertNotifiers)
            notifier.notify(userId, "RED", reason).
                subscribe().with(
                    _ -> {},
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