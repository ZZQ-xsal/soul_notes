package kurvcygnus.soulnotes.domain.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.ai.agent.EmpatheticChatAgent;
import kurvcygnus.soulnotes.ai.agent.WarningDetectionAgent;
import kurvcygnus.soulnotes.ai.dto.WarningDetectionResult;
import kurvcygnus.soulnotes.domain.chat.dto.ChatMessageVo;
import kurvcygnus.soulnotes.domain.chat.dto.ChatSendRequest;
import kurvcygnus.soulnotes.domain.chat.dto.ChatSessionVo;
import kurvcygnus.soulnotes.domain.chat.entity.AiChatSession;
import kurvcygnus.soulnotes.exception.ErrorCode;
import kurvcygnus.soulnotes.exception.IBusinessException;
import kurvcygnus.soulnotes.utils.JsonUtils;
import kurvcygnus.soulnotes.websocket.AlertWebSocket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * <b>AI 对话服务</b>
 * <ul>
 *     <li>发送消息 (同步 + SSE 流式)</li>
 *     <li>会话历史管理</li>
 *     <li>预警检测与推送</li>
 * </ul>
 * @since 1.0
 */
@ApplicationScoped
@SuppressWarnings("unused")//! AI Agent / AlertWebSocket 为 Quarkus 运行时生成 Bean, IDE 静态分析误报未满足依赖; transformToMulti 返回的 Multi 即流, 非误用.
public final class ChatService
{
    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

    //* 会话历史最多保留的消息条数, 防止 JSONB 无限增长与 Token 超限.
    private static final int MAX_HISTORY_MESSAGES = 50;

    //region 注入
    private final @NotNull EmpatheticChatAgent empatheticChatAgent;
    private final @NotNull WarningDetectionAgent warningDetectionAgent;
    private final @NotNull AlertWebSocket alertWebSocket;
    private final @NotNull Vertx vertx;

    public ChatService(
        @NotNull EmpatheticChatAgent empatheticChatAgent,
        @NotNull WarningDetectionAgent warningDetectionAgent,
        @NotNull AlertWebSocket alertWebSocket,
        @NotNull Vertx vertx
    )
    {
        this.empatheticChatAgent = empatheticChatAgent;
        this.warningDetectionAgent = warningDetectionAgent;
        this.alertWebSocket = alertWebSocket;
        this.vertx = vertx;
    }
    //endregion

    //region 核心业务
    /**
     * <span style="color: 95cc6d">发送消息并获取完整回复 (非流式).</span>
     * <ul>
     *     <li>加载/创建 Session</li>
     *     <li>追加用户消息</li>
     *     <li>调用 {@link EmpatheticChatAgent} 获取 AI 回复 (worker 线程池执行, 不阻塞事件循环)</li>
     *     <li>检测预警等级</li>
     *     <li>保存 Session</li>
     * </ul>
     *
     * @param req    发送请求
     * @param userId 用户 ID
     * @return AI 回复消息
     */
    @WithTransaction
    public @NotNull Uni<ChatMessageVo> sendMessage(@NotNull ChatSendRequest req, @NotNull UUID userId)
    {
        return getOrCreateSession(req.sessionId(), userId).
            flatMap(session ->
                {
                    session.addMessage("user", req.content());
                    session.truncate(MAX_HISTORY_MESSAGES);
                    return callAiAndRespond(session, req.content());
                }
            ).
            map(reply -> new ChatMessageVo("assistant", reply, Instant.now()));
    }

    /**
     * <span style="color: 95cc6d">SSE 流式回复.</span>
     * <p>返回 {@link Multi<String>} 以支持前端的逐字渲染.</p>
     * <ul>
     *     <li>加载/创建 Session, 先持久化用户消息</li>
     *     <li>调用 {@link EmpatheticChatAgent#chat(String, String, String)} 获取 {@link dev.langchain4j.service.TokenStream}</li>
     *     <li>在 worker 线程池启动流式调用, 逐块推送至 {@link Multi}</li>
     *     <li>流式完成后以独立事务持久化 AI 回复</li>
     * </ul>
     *
     * @param sessionId 会话 ID
     * @param content   用户消息
     * @param userId    用户 ID
     * @return AI 回复的流式块
     */
    //! Multi 返回类型无法使用 @WithTransaction (长事务会长时间占用 Hibernate session),
    //! 因此每个持久化操作都通过 Panache.withTransaction 独立事务完成.
    @SuppressWarnings("unused")//! transformToMulti 返回的 Multi 即最终流, IDE 的 Mutiny 数据流分析误报为未使用发布者.
    public @NotNull Multi<String> streamMessage(
        @Nullable String sessionId,
        @NotNull String content,
        @NotNull UUID userId
    )
    {
        //! 该链路的返回值即最终流; IDE 数据流分析对 transformToMulti 误报"值从未被用作发布者".
        final var sid = parseSessionId(sessionId);
        return getOrCreateSession(sid, userId).
            chain(session -> appendUserMessage(session.id, content)).
            onItem().transformToMulti(session -> streamAiReply(session, content));
    }

    /**
     * <span style="color: 95cc6d">用户历史会话概览.</span>
     *
     * @param userId 用户 ID
     * @return 会话概览列表
     */
    @WithTransaction
    public @NotNull Uni<List<ChatSessionVo>> listSessions(@NotNull UUID userId)
    {
        return AiChatSession.findByUserId(userId).
            map(sessions -> sessions.stream().
                map(
                    s -> new ChatSessionVo(
                        s.id,
                        countMessages(s.messages),
                        s.updatedAt,
                        getPreview(s.messages)
                    )
                ).toList()
            );
    }
    //endregion

    //region 辅助方法
    //* 加载已有 Session, 或创建新的 Session.
    private static @NotNull Uni<AiChatSession> getOrCreateSession(@Nullable UUID sessionId, @NotNull UUID userId)
    {
        if(sessionId == null)
        {
            final var session   = new AiChatSession();
            session.id               = UUID.randomUUID();
            session.userId           = userId;
            session.messages         = "[]";
            session.warningTriggered = false;
            session.updatedAt        = Instant.now();
            return Panache.withTransaction(session::persist).replaceWith(session);
        }
        return AiChatSession.
            <AiChatSession>findById(sessionId).
            onItem().
            ifNull().
            failWith(
                () -> IBusinessException.of(
                    ErrorCode.SESSION_NOT_FOUND,
                    "会话不存在",
                    NoSuchElementException::new,
                    "CHAT_SESSION_LOOKUP_NOT_FOUND"
                ).asException()
            );
    }

    //* 在独立事务中追加并持久化用户消息, 返回受管的 Session (含最新历史).
    private static @NotNull Uni<AiChatSession> appendUserMessage(@NotNull UUID sessionId, @NotNull String content)
    {
        return Panache.withTransaction(() ->
            AiChatSession.
                <AiChatSession>findById(sessionId).
                onItem().
                ifNull().
                failWith(
                    () -> IBusinessException.of(
                        ErrorCode.SESSION_NOT_FOUND,
                        "会话不存在",
                        NoSuchElementException::new,
                        "CHAT_STREAM_SESSION_NOT_FOUND"
                    ).asException()
                ).
                flatMap(s ->
                    {
                        s.addMessage("user", content);
                        s.truncate(MAX_HISTORY_MESSAGES);
                        return s.persistAndFlush().replaceWith(s);
                    }
                )
        );
    }

    //* 在独立事务中追加并持久化 AI 回复, 同时执行预警检测与推送.
    //! 预警检测 (外部 AI 调用, 秒级耗时) 在事务外先行完成, 结果传入事务内落库,
    //! 避免长时间占用 Hibernate reactive Session (与 streamMessage 不使用 @WithTransaction 的理由一致).
    //! 非 static: 内部调用实例方法 applyWarning (依赖 alertWebSocket 注入).
    private @NotNull Uni<Void> appendAssistantReply(@NotNull UUID sessionId, @NotNull String userContent, @NotNull String reply)
    {
        return detectWarning(userContent).flatMap(detection ->
            Panache.withTransaction(() ->
                AiChatSession.
                    <AiChatSession>findById(sessionId).
                    onItem().
                    ifNull().
                    failWith(
                        () -> IBusinessException.of(
                            ErrorCode.SESSION_NOT_FOUND,
                            "会话不存在",
                            NoSuchElementException::new,
                            "CHAT_STREAM_SESSION_NOT_FOUND"
                        ).asException()
                    ).
                    flatMap(
                        s ->
                        {
                            s.addMessage("assistant", reply);
                            s.truncate(MAX_HISTORY_MESSAGES);
                            //* 预警检测在持久化前应用, 确保 warningTriggered 被一并落库.
                            applyWarning(s, detection);
                            return s.persistAndFlush().replaceWithVoid();
                        }
                    )
            )
        );
    }

    //* 在 worker 线程池启动 TokenStream, 桥接为 Multi 逐块推送.
    private @NotNull Multi<String> streamAiReply(@NotNull AiChatSession session, @NotNull String content)
    {
        final var history = buildConversationHistory(session);
        return Multi.createFrom().<String>emitter(emitter ->
            {
                final var fullReply = new StringBuilder();
                empatheticChatAgent.chat(session.userId.toString(), history, content).
                    onPartialResponse(
                        token ->
                        {
                            //* 累积与推送必须合并在同一回调内: TokenStream 每类回调仅允许注册一次,
                            //! 重复注册会在 start() 前抛 IllegalConfigurationException 导致流式端点静默断流.
                            fullReply.append(token);
                            emitter.emit(token);
                        }
                    ).
                    onCompleteResponse(
                        response ->
                        //* 回调线程为 langchain4j 流式线程, 无 Vertx 上下文, 直接执行响应式事务会失败;
                        //* 经 executeBlocking 切至 Vertx worker 线程 (与 callAiAndRespond 同一模式) 阻塞等待持久化完成.
                        vertx.executeBlocking(
                            () -> appendAssistantReply(session.id, content, fullReply.toString()).await().atMost(Duration.ofSeconds(60)),
                            false
                        ).subscribe().with(
                            v -> emitter.complete(),
                            t -> { LOG.warn("流式回复持久化失败: {}", t.getMessage()); emitter.complete(); }
                        )
                    ).
                    onError(
                        error ->
                        {
                            LOG.warn("流式对话失败: {}", error.getMessage());
                            emitter.fail(error);
                        }
                    ).start();
            }
        ).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    //* 调用 EmpatheticChatAgent 获取 AI 回复, 持久化并检测预警.
    //! HR000068/069: vertx.executeBlocking 在 worker 线程执行阻塞 AI 调用, 结果在事件循环回调,
    //! 之后的 session.persist() 才能安全使用请求上下文中的 Hibernate reactive Session.
    private @NotNull Uni<String> callAiAndRespond(@NotNull AiChatSession session, @NotNull String content)
    {
        final var history = buildConversationHistory(session);
        return vertx.executeBlocking(() -> empatheticChatAgent.chatSync(session.userId.toString(), history, content), false).
            onItem().transformToUni(
                reply ->
                {
                    session.addMessage("assistant", reply);
                    session.truncate(MAX_HISTORY_MESSAGES);
                    //* 预警检测在 worker 线程池异步执行, 完成后才持久化, 确保 warningTriggered 被一并落库.
                    return detectWarning(content).
                        onItem().invoke(detection -> applyWarning(session, detection)).
                        flatMap(v -> session.persist().replaceWith(reply));
                }
            ).
            onFailure().recoverWithUni(
                failure ->
                {
                    LOG.warn("AI 对话失败: {}", failure.getMessage());
                    //* 失败时返回友好兜底消息, 避免前端展示错误.
                    final var fallback = "我似乎有些走神了，你能再说一遍吗？";
                    session.addMessage("assistant", fallback);
                    session.truncate(MAX_HISTORY_MESSAGES);
                    return session.persist().replaceWith(fallback);
                }
            );
    }

    //* 对用户最新消息执行预警等级检测.
    //! WarningDetectionAgent 的 detect 是同步阻塞调用, 直接在事件循环线程调用会触发
    //! BlockingNotAllowedException 被静默吞掉, 导致 warningTriggered 永远为 false —
    //! 必须经 vertx.executeBlocking 在 worker 线程池执行.
    //! 检测对象为用户消息而非 AI 回复: 共情回复会复述用户的痛苦内容, 以回复为对象会产生误报.
    private @NotNull Uni<@Nullable WarningDetectionResult> detectWarning(@NotNull String userContent)
    {
        return vertx.executeBlocking(() -> warningDetectionAgent.detect(userContent), false).
            onFailure().invoke(t -> LOG.warn("预警检测失败: {}", t.getMessage())).
            //* 预警检测失败不阻塞主对话流程, 降级为无预警 (null).
            onFailure().recoverWithItem(() -> null);
    }

    //* 依据检测结果标记会话预警位, RED 等级立即经 WebSocket 推送热线.
    //! 必须在持久化前调用 (受管 Session), 确保 warningTriggered 随消息一并落库.
    private void applyWarning(@NotNull AiChatSession session, @Nullable WarningDetectionResult detection)
    {
        if(detection == null)
            return;
        if("RED".equals(detection.warningLevel()))
        {
            session.warningTriggered = true;
            //* Uni 是惰性的, 必须订阅才会真正发送推送.
            alertWebSocket.pushAlert(session.userId, detection.reason()).
                subscribe().with(
                    v -> {},
                    t -> LOG.warn("RED 预警推送执行失败: userId={}, {}", session.userId, t.getMessage())
                );
        }
        else if("YELLOW".equals(detection.warningLevel()))
            session.warningTriggered = true;
    }

    //* 非法/空白 sessionId 视为新会话.
    private static @Nullable UUID parseSessionId(@Nullable String sessionId)
    {
        if(sessionId == null || sessionId.isBlank())
            return null;
        try { return UUID.fromString(sessionId); }
        catch(IllegalArgumentException e) { return null; }//! 非法 UUID 回退为新会话, 避免入口崩溃.
    }

    //* 将 Session 中的消息列表格式化为对话历史文本 (用于 AI 输入).
    private static @NotNull String buildConversationHistory(@NotNull AiChatSession session)
    {
        if(session.messages == null || session.messages.isBlank())
            return "";
        try
        {
            final var messages = JsonUtils.parseJson(session.messages, new TypeReference<List<Map<String, String>>>() {});
            final var sb       = new StringBuilder();
            final var start    = Math.max(0, messages.size() - MAX_HISTORY_MESSAGES);
            for(int i = start; i < messages.size(); i++)
            {
                final var msg     = messages.get(i);
                final var role    = msg.getOrDefault("role", "unknown");
                final var content = msg.getOrDefault("content", "");
                sb.append(role).append(": ").append(content).append("\n");
            }
            return sb.toString();
        }
        catch(Exception e)
        {
            LOG.warn("构建对话历史失败: {}", e.getMessage());
            return "";
        }
    }

    //* 使用 JsonUtils 解析 messages JSON 数组, 返回消息条数.
    private static int countMessages(String messagesJson)
    {
        if(messagesJson == null || messagesJson.isBlank())
            return 0;
        try { return JsonUtils.parseJson(messagesJson, new TypeReference<List<Map<String, String>>>() { }).size(); }
        catch(Exception e) { LOG.warn("解析 messages JSON 获取消息数失败: {}", e.getMessage()); return 0; }
    }

    //* 使用 JsonUtils 解析 messages JSON 数组, 提取最后一条消息的 content 作为预览.
    //* 空字符串 "" 是合理选择, 用于 VO 展示前端, 表示"无预览内容".
    //! 不应使用 null (导致前端判空) 或 Optional (VO 字段不应包装 Optional).
    private static @NotNull String getPreview(String messagesJson)
    {
        if(messagesJson == null || messagesJson.isBlank())
            return "";
        try
        {
            final var messages = JsonUtils.parseJson(messagesJson, new TypeReference<List<Map<String, String>>>() { });
            if(messages.isEmpty())
                return "";
            final var lastContent = messages.getLast().get("content");
            if(lastContent == null || lastContent.isBlank())
                return "";
            return lastContent.length() > 50 ? lastContent.substring(0, 50) + "..." : lastContent;
        }
        catch(Exception e) { LOG.warn("解析 messages JSON 获取预览失败: {}", e.getMessage()); return ""; }
    }

    //endregion
}
