package kurvcygnus.soulnotes.domain.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.ai.ClinicalOutputSplitter;
import kurvcygnus.soulnotes.ai.agent.EmpatheticChatAgent;
import kurvcygnus.soulnotes.ai.agent.SessionTitleAgent;
import kurvcygnus.soulnotes.ai.agent.WarningDetectionAgent;
import kurvcygnus.soulnotes.ai.dto.WarningDetectionResult;
import kurvcygnus.soulnotes.config.ClinicalSchemaNormalizer;
import kurvcygnus.soulnotes.config.PromptProvider;
import kurvcygnus.soulnotes.domain.chat.dto.ChatHistoryMessage;
import kurvcygnus.soulnotes.domain.chat.dto.ChatMessageVo;
import kurvcygnus.soulnotes.domain.chat.dto.ChatSendRequest;
import kurvcygnus.soulnotes.domain.chat.dto.ChatSessionVo;
import kurvcygnus.soulnotes.domain.chat.entity.AiChatSession;
import kurvcygnus.soulnotes.domain.clinical.service.ClinicalAssessmentService;
import kurvcygnus.soulnotes.exception.ErrorCode;
import kurvcygnus.soulnotes.exception.IBusinessException;
import kurvcygnus.soulnotes.utils.JsonUtils;
import kurvcygnus.soulnotes.utils.PrintUtils;
import kurvcygnus.soulnotes.utils.constants.AiPromptConstants;
import kurvcygnus.soulnotes.websocket.AlertDispatchService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 对话服务, 承载树洞对话的完整链路: 消息收发 (同步 + SSE 流式)、会话历史管理、
 * 预警检测与 RED 预警分发 (统一收口至 AlertDispatchService: 冷却闸门 + 多渠道推送)、
 * 会话标题生成 (首条用户消息并发触发 + 列表页为存量会话补全), 以及结构化输出管线
 * ("副医生"预埋: 契约提示词组装 + soulnotes 块拆流 + 评估落库 best-effort 挂点).
 *
 * @implNote LLM 与预警检测均为阻塞调用, 一律经 {@code vertx.executeBlocking} 在 worker 线程池执行,
 *           结果回到事件循环后再操作 Hibernate reactive Session (规避 HR000068/069).
 *           LLM 失败不向外抛错: send 与 stream 两条路径对称地补发固定兜底文案并正常收尾 (离线安全网).
 * @since 1.0
 */
@SuppressWarnings("JavadocDeclaration") @ApplicationScoped
public final class ChatService
{
    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

    //* LLM 失败时的兜底文案: send 与 stream 两条路径必须使用同一份, 保证降级语义对称.
    private static final @NotNull String FALLBACK_REPLY = "我似乎有些走神了，你能再说一遍吗？";

    //* 会话标题长度上限 (字符): 提示词已约束 4~12 字, 此处为模型不听话时的硬截断.
    private static final int TITLE_MAX_CHARS = 16;

    //* 标题种子 (首条用户消息) 送入模型的长度上限: 首条消息可能是一整篇长文, 标题不需要全文, 避免无谓 token 开销.
    private static final int TITLE_SEED_MAX_CHARS = 200;

    //* 存量会话补标题的静默期: 新会话的标题与首轮回复并行生成, updatedAt 尚新时先不补,
    //! 否则每次拉列表都会与该任务重复外呼 (标题写库不改 updatedAt, 静默期内以会话活跃时间判定).
    private static final @NotNull Duration TITLE_BACKFILL_QUIET = Duration.ofSeconds(60);

    //region 注入
    private final @NotNull EmpatheticChatAgent empatheticChatAgent;
    private final @NotNull WarningDetectionAgent warningDetectionAgent;
    //* 会话标题 Agent: 仅用于历史会话列表的标题生成, 与对话主链路无耦合 (失败只丢标题, 不影响回复).
    private final @NotNull SessionTitleAgent sessionTitleAgent;
    private final @NotNull PromptProvider promptProvider;
    //* 临床结构归一化缓存: 自定义结构只有归一化产物才允许进入对话契约.
    private final @NotNull ClinicalSchemaNormalizer schemaNormalizer;
    //* 临床评估落库: 拆流挂点的 best-effort 消费方 — 失败仅 WARN, 对话可用性 > 评估完整性 (Spec §7).
    private final @NotNull ClinicalAssessmentService clinicalAssessmentService;
    //* RED 预警统一分发收口: per-user 冷却闸门 + 渠道 fan-out + "渠道全空"WARN 哨兵均内含于分发器,
    //* 本服务只负责触发 — 冷却只闸门外呼, 落库标记 warningTriggered 仍在本服务.
    private final @NotNull AlertDispatchService alertDispatchService;
    private final @NotNull Vertx vertx;
    //* 会话历史最多保留的消息条数, 防止 JSONB 无限增长与 Token 超限.
    private final int maxHistoryMessages;
    //* 结构化输出契约开关 ("副医生"预埋): on 时共情提示词追加契约段, 回复落库前拆流.
    private final boolean clinicalTagging;
    //* 结构增强暂禁告警只发一次: 未命中缓存的每条消息都 WARN 会把启动期降级放大成告警噪音.
    private final @NotNull AtomicBoolean schemaSuspendedWarned = new AtomicBoolean();

    public ChatService(
        @NotNull EmpatheticChatAgent empatheticChatAgent,
        @NotNull WarningDetectionAgent warningDetectionAgent,
        @NotNull SessionTitleAgent sessionTitleAgent,
        @NotNull PromptProvider promptProvider,
        @NotNull ClinicalSchemaNormalizer schemaNormalizer,
        @NotNull ClinicalAssessmentService clinicalAssessmentService,
        @NotNull AlertDispatchService alertDispatchService,
        @NotNull Vertx vertx,
        @ConfigProperty(name = "chat.history.max-messages", defaultValue = "50") int maxHistoryMessages,
        @ConfigProperty(name = "clinical.tagging", defaultValue = "false") boolean clinicalTagging
    )
    {
        this.empatheticChatAgent = empatheticChatAgent;
        this.warningDetectionAgent = warningDetectionAgent;
        this.sessionTitleAgent = sessionTitleAgent;
        this.promptProvider = promptProvider;
        this.schemaNormalizer = schemaNormalizer;
        this.clinicalAssessmentService = clinicalAssessmentService;
        this.alertDispatchService = alertDispatchService;
        this.vertx = vertx;
        this.maxHistoryMessages = maxHistoryMessages;
        this.clinicalTagging = clinicalTagging;
    }
    //endregion

    //region 核心业务
    /**
     * 发送用户消息并返回 AI 回复: 主链路收拢为单个 programmatic 事务 (Session 加载/创建经
     * {@code getOrCreateSession} 的自带事务并入, 与旧 {@code @WithTransaction} 语义等价),
     * 评估落库在主事务提交后的事件循环链尾 fire (best-effort, 失败仅 WARN).
     * <p>流程: 加载/创建 Session → 追加并截断用户消息 → worker 线程执行 LLM 调用 →
     * 预警检测 → 持久化 AI 回复 → 事务提交后 fire 评估落库.</p>
     *
     * <p>LLM 调用失败时不抛出错误: 返回固定兜底文案 (同样落库),
     * 保证前端永远收到可展示的回复 — 离线安全网语义的一部分.</p>
     *
     * @param req    发送请求 (含可选会话 ID 与消息内容)
     * @param userId 当前认证用户 ID
     * @return 助手角色的回复消息; LLM 不可用时为兜底文案
     * @throws IBusinessException 会话 ID 不存在时 (SESSION_NOT_FOUND)
     */
    public @NotNull Uni<ChatMessageVo> sendMessage(@NotNull ChatSendRequest req, @NotNull UUID userId)
    {
        //* 挂点在事务外链尾 fire, 而 Session 实体出事务即脱离持久化上下文 (分离实体再 persist 会退化为
        //* 同 id 重 INSERT, 集成测试实证 duplicate key 23505) — 加载与持久化必须同事务, 实体经原子引用
        //* 带出事务供挂点取 id/userId (单链顺序写读, 无并发竞争).
        final var sessionRef = new AtomicReference<AiChatSession>();
        return Panache.withTransaction(
            () ->
            getOrCreateSession(req.sessionId(), userId).
                flatMap(
                    session ->
                    {
                        sessionRef.set(session);
                        session.addMessage("user", req.content());
                        session.truncate(maxHistoryMessages);
                        return callAiAndRespond(session, req.content());
                    }
                )
            ).
            invoke(outcome ->
            {
                final var session = Objects.requireNonNull(
                    sessionRef.get(),
                    "Param \"session\" must not be null!"
                );
                fireClinicalRecord(session, outcome.clinicalPayload());
                //* 本路径的用户消息与 AI 回复同处一个事务, 标题只能等事务提交后再 fire — 事务内再开事务
                //! 会抢占同一 Hibernate reactive session; 代价是标题晚于回复落库, 由列表页的补标题任务兜底.
                fireTitleIfFirstUserMessage(session, req.content());
            }).
            map(outcome -> new ChatMessageVo("assistant", outcome.visible(), Instant.now())
        );
    }

    /**
     * SSE 流式回复: 先独立事务持久化用户消息, 再逐 token 推送 AI 回复,
     * 流式完成后以独立事务持久化完整回复并执行预警检测.
     *
     * @param sessionId 会话 ID; {@code null}/空白/非法 UUID 一律回退为新会话 (不报错)
     * @param content   用户消息
     * @param userId    当前认证用户 ID
     * @return AI 回复的流式块; LLM 中途失败时补发兜底文案后正常收流 (不向下游发失败信号),
     *         会话不存在时以失败 Uni 发出 SESSION_NOT_FOUND
     * @implNote Multi 返回类型无法使用 {@code @WithTransaction} (长事务会长时间占用 Hibernate session),
     *           因此每个持久化操作都通过 {@code Panache.withTransaction} 独立事务完成.
     *           emit 给前端的 token 保持原文, 结构化契约块只在落库文本上剥离 —
     *           若缓冲到流结束再拆流须扣留全部 token, 既破坏逐字渲染, 流中断时还会整段丢失已扣留内容.
     */
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
            //* 用户消息事务已提交, 此刻 fire 标题生成可与随后的流式回复并行 — 回复结束前标题通常已落库,
            //* 前端首轮回查会话列表时即可拿到标题.
            onItem().invoke(session -> fireTitleIfFirstUserMessage(session, content)).
            onItem().transformToMulti(session -> streamAiReply(session, content));
    }

    /**
     * 查询当前用户的会话概览列表 (按最近活跃排序).
     * <p>列表事务提交后, 为缺标题的存量会话 fire 标题补全任务 (best-effort, 失败仅 WARN);
     * 本次响应仍以末条消息预览兜底, 标题在下一次拉取时呈现.</p>
     *
     * @param userId 当前认证用户 ID
     * @return 会话概览列表 (可能为空, 恒非 null); 预览超 50 字截断, 消息 JSON 损坏时该条计为 0 条/空预览,
     *         标题未生成时为空串 (展示方以预览兜底)
     * @implNote 事务由 {@code Panache.withTransaction} 显式绑定而非 {@code @WithTransaction}:
     *           补标题挂点必须落在事务提交之后, 注解式事务会把挂点一并圈进事务内.
     */
    public @NotNull Uni<List<ChatSessionVo>> listSessions(@NotNull UUID userId)
    {
        return Panache.withTransaction(
            () ->
            AiChatSession.findByUserId(userId).
                map(ChatService::toListView)
        ).
        invoke(view -> view.titleJobs().forEach(this::fireTitleGeneration)).
        map(SessionListView::items);
    }

    /**
     * 拉取指定会话的完整消息历史 (按对话顺序, 含 LLM 回复).
     *
     * @param sessionId 会话 ID
     * @param userId    当前认证用户 ID (归属校验依据)
     * @return 消息列表 (role/content 按对话顺序; 空会话为空列表, 恒非 null)
     * @throws IBusinessException 会话不存在或不属于当前用户时 (SESSION_NOT_FOUND, 同码同文案防枚举)
     * @implNote 消息 JSONB 仅存 role/content, 历史回放不带时间戳; 损坏 JSON 按空列表降级 (与预览/计数同款容错).
     * @since 1.2.1
     */
    @WithTransaction
    public @NotNull Uni<List<ChatHistoryMessage>> listMessages(@NotNull UUID sessionId, @NotNull UUID userId)
        { return loadOwnedSession(sessionId, userId).map(session -> parseHistory(session.messages)); }

    /**
     * 删除指定会话 (硬删除, 含全部消息历史).
     *
     * @param sessionId 会话 ID
     * @param userId    当前认证用户 ID (归属校验依据)
     * @return 完成信号
     * @throws IBusinessException 会话不存在或不属于当前用户时 (SESSION_NOT_FOUND, 同码同文案防枚举)
     * @implNote 硬删除与日记域先例一致; 关联的 RED 预警已推送记录不受影响 (预警渠道为 fire-and-forget 外呼, 无会话外键).
     * @since 1.2.1
     */
    @WithTransaction
    public @NotNull Uni<Void> deleteSession(@NotNull UUID sessionId, @NotNull UUID userId) { return loadOwnedSession(sessionId, userId).chain(AiChatSession::delete); }
    //endregion

    //region 辅助方法
    /**
     * 加载会话并校验归属.
     *
     * @implNote 不存在与越权一律以 "不存在" 同码同文案回应, 不泄露资源存在性, 防会话枚举越权.
     */
    private static @NotNull Uni<AiChatSession> loadOwnedSession(@NotNull UUID sessionId, @NotNull UUID userId)
    {
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
            ).
            flatMap(
                session ->
                session.userId.equals(userId) ?
                    Uni.createFrom().item(session) :
                    Uni.createFrom().failure(
                        IBusinessException.of(
                            ErrorCode.SESSION_NOT_FOUND,
                            "会话不存在",
                            NoSuchElementException::new,
                            "CHAT_SESSION_FOREIGN_ACCESS"
                        ).asException()
                    )
            );
    }

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
        return loadOwnedSession(sessionId, userId);
    }

    //* 在独立事务中追加并持久化用户消息, 返回受管的 Session (含最新历史).
    //* 非 static: 历史截断需引用构造器注入的配置字段 maxHistoryMessages.
    private @NotNull Uni<AiChatSession> appendUserMessage(@NotNull UUID sessionId, @NotNull String content)
    {
        return Panache.withTransaction(
            () ->
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
                        s.addMessage("user", content);
                        s.truncate(maxHistoryMessages);
                        return s.persistAndFlush().replaceWith(s);
                    }
                )
        );
    }

    //* 在独立事务中追加并持久化 AI 回复, 同时执行预警检测与推送.
    //! 预警检测 (外部 AI 调用, 秒级耗时) 在事务外先行完成, 结果传入事务内落库,
    //! 避免长时间占用 Hibernate reactive Session (与 streamMessage 不使用 @WithTransaction 的理由一致).
    //! 非 static: 内部调用实例方法 applyWarning (依赖 alertDispatchService 注入).
    private @NotNull Uni<Void> appendAssistantReply(@NotNull UUID sessionId, @NotNull String userContent, @NotNull String reply)
    {
        return detectWarning(userContent).flatMap(
            detection ->
            Panache.withTransaction(
                () ->
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
                            s.truncate(maxHistoryMessages);
                            //* 预警检测在持久化前应用, 确保 warningTriggered 被一并落库.
                            applyWarning(s, detection);
                            return s.persistAndFlush().replaceWithVoid();
                        }
                    )
            )
        );
    }

    //* 在 worker 线程池启动 TokenStream, 桥接为 Multi 逐块推送.
    //! 流式路径裁定: emit 给前端的 token 保持原文, 拆流只作用于落库文本 (经 splitForStore) —
    //* 契约块是 HTML 注释, 前端 markdown 渲染下天然不可见, 与后端剥离构成双保险; 若缓冲到流结束再拆流,
    //* 须扣留全部 token, 既破坏逐字渲染体验, 流中断时已扣留内容还会整段丢失, 权衡后不采纳.
    private @NotNull Multi<String> streamAiReply(@NotNull AiChatSession session, @NotNull String content)
    {
        final var history = buildConversationHistory(session);
        return Multi.createFrom().<String>emitter(
            emitter ->
            {
                final var fullReply = new StringBuilder();
                empatheticChatAgent.chat(buildSystemPrompt(), session.userId.toString(), history, content).
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
                        _ ->
                        //* 回调线程为 langchain4j 流式线程, 无 Vertx 上下文, 直接执行响应式事务会失败;
                        //* 经 executeBlocking 切至 Vertx worker 线程 (与 callAiAndRespond 同一模式) 阻塞等待持久化完成.
                        {
                            final var split = splitForStore(fullReply.toString());
                            vertx.executeBlocking(
                                () ->
                                {
                                    appendAssistantReply(session.id, content, split.text()).await().atMost(Duration.ofSeconds(60));
                                    //* 评估落库与持久化同 worker 上下文串联 (前一事务已完成, 无嵌套冲突);
                                    //* 失败已内部归一为正常完成 (仅 WARN), await 仅保证上下文存活.
                                    recordClinicalAssessment(session, split.payload()).await().atMost(Duration.ofSeconds(60));
                                    return null;
                                },
                                false
                            ).subscribe().with(
                                _ -> emitter.complete(),
                                t ->
                                {
                                    LOG.warn("流式回复持久化失败: {}", t.getMessage());
                                    emitter.complete();
                                }
                            );
                        }
                    ).
                    onError(
                        error ->
                        {
                            LOG.warn("流式对话失败: {}", error.getMessage());
                            //* 降级与 send 路径对称 (冒烟发现: 原实现 fail 流导致前端收到 200 + 空流黑洞):
                            //* LLM 失败仍补发兜底文案并正常收流; 兜底文案同样落库, 持久化失败也照发 (与 send 语义一致).
                            vertx.executeBlocking(
                                () -> appendAssistantReply(session.id, content, FALLBACK_REPLY).await().atMost(Duration.ofSeconds(60)),
                                false
                            ).subscribe().with(
                                _ ->
                                {
                                    emitter.emit(FALLBACK_REPLY);
                                    emitter.complete();
                                },
                                t ->
                                {
                                    LOG.warn("流式兜底持久化失败: {}", t.getMessage());
                                    emitter.emit(FALLBACK_REPLY);
                                    emitter.complete();
                                }
                            );
                        }
                    ).start();
            }
        ).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    //* 单轮 AI 回复的完整产物: visible 供前端/历史, clinicalPayload 供拆流挂点评估落库 (null 即直通).
    private record AiReplyOutcome(@NotNull String visible, @Nullable JsonNode clinicalPayload) {}

    //* 调用 EmpatheticChatAgent 获取 AI 回复, 持久化并检测预警.
    //! HR000068/069: vertx.executeBlocking 在 worker 线程执行阻塞 AI 调用, 结果在事件循环回调,
    //! 之后的 session.persist() 才能安全使用请求上下文中的 Hibernate reactive Session.
    private @NotNull Uni<AiReplyOutcome> callAiAndRespond(@NotNull AiChatSession session, @NotNull String content)
    {
        final var history = buildConversationHistory(session);
        return vertx.executeBlocking(() -> empatheticChatAgent.chatSync(buildSystemPrompt(), session.userId.toString(), history, content), false).
            onItem().transformToUni(
                reply ->
                {
                    final var split = splitForStore(reply);
                    session.addMessage("assistant", split.text());
                    session.truncate(maxHistoryMessages);
                    return detectWarning(content).
                        onItem().invoke(detection -> applyWarning(session, detection)).
                        flatMap(v -> session.persist().replaceWith(new AiReplyOutcome(split.text(), split.payload())));
                }
            ).
            onFailure().recoverWithUni(
                failure ->
                {
                    LOG.warn("AI 对话失败: {}", failure.getMessage());
                    session.addMessage("assistant", FALLBACK_REPLY);
                    session.truncate(maxHistoryMessages);
                    return session.persist().replaceWith(new AiReplyOutcome(FALLBACK_REPLY, null));
                }
            );
    }

    /**
     * 组装共情对话 systemPrompt: 机构/内置提示词在前, 功能契约壳在后.
     *
     * @return 合并后的 systemPrompt; {@code clinical.tagging=off} 时不追加契约段,
     *         提示词与 token 成本同 1.0 行为逐字节一致
     * @since 1.1.0
     */
    //* 合并规则: 契约壳首行声明最高优先级, 兜底机构提示词中"不要输出 JSON"之类指令对输出格式的破坏.
    private @NotNull String buildSystemPrompt()
    {
        final var base = promptProvider.empatheticChat();
        if(!clinicalTagging)
            return base;
        final var schema = resolveSchemaForContract();
        if(schema == null)
            return base;//* 自定义结构无归一化缓存: 结构化增强暂禁, 仅发基础提示词.
        return PrintUtils.quickFormat("{}\n\n{}", base, PrintUtils.quickFormat(AiPromptConstants.CLINICAL_OUTPUT_CONTRACT, schema));
    }

    /**
     * 解析可下发的结构化契约结构定义.
     *
     * @return 默认 canonical 结构免归一化零成本直用 (回滚即永久稳定); 自定义结构必须命中归一化缓存
     *         才允许上线 — 未经归一化的自由文本绝不下发; 未命中 (启动期归一化未成功) 时为 {@code null},
     *         一次性 WARN 留痕后等下次启动重试 (缓存查询不触发 LLM, 请求路径零外呼)
     * @since 1.1.0
     */
    private @Nullable String resolveSchemaForContract()
    {
        final var effective = promptProvider.clinicalSchema();
        if(AiPromptConstants.CLINICAL_OUTPUT_SCHEMA_DEFAULT.equals(effective))
            return effective;
        final var normalized = schemaNormalizer.cachedFor(ClinicalSchemaNormalizer.sha256Hex(effective));
        if(normalized == null && schemaSuspendedWarned.compareAndSet(false, true))
            LOG.warn("自定义临床结构定义尚无归一化缓存, 结构化输出增强暂禁 (仅发基础提示词), 等下次启动重试归一化");
        return normalized;
    }

    /**
     * 落库前拆流: {@code clinical.tagging=on} 时剥离回复末尾的 soulnotes 结构化块,
     * 防止契约块在多轮历史间重复累积 (省 token); off 时原样透传不拆 (payload 恒 null).
     *
     * @param reply LLM 原始回复
     * @return 剥离后正文与结构化载荷; payload 为 null 表示 tagging off / 无块 / 解析失败,
     *         评估落库挂点对 null 零开销直通
     * @since 1.1.0
     */
    private @NotNull ClinicalOutputSplitter.SplitResult splitForStore(@NotNull String reply)
    {
        if(!clinicalTagging)
            return new ClinicalOutputSplitter.SplitResult(reply, null);
        final var result = ClinicalOutputSplitter.split(reply);
        if(result.payload() != null)
            LOG.debug("soulnotes 结构化负载: {}", result.payload());
        return result;
    }

    //* fire-and-forget 落库: 拆流 payload 为 null (tagging off / 无块 / 解析失败) 时零开销直通;
    //* 失败仅 WARN — 对话可用性 > 评估完整性 (Spec §7 best-effort 边界).
    //* send 挂点经此出口订阅即弃 (响应映射不等落库); stream 挂点直接 await 记录 Uni 保活 worker 上下文.
    private void fireClinicalRecord(@NotNull AiChatSession session, @Nullable JsonNode payload) { recordClinicalAssessment(session, payload).subscribe().with(v -> {}); }

    //* 落库 Uni 构造 (两挂点共享, 各自恰好订阅一次): 失败在内部归一为正常完成 — 订阅方无需失败分支.
    private @NotNull Uni<Void> recordClinicalAssessment(@NotNull AiChatSession session, @Nullable JsonNode payload)
    {
        if(payload == null)
            return Uni.createFrom().voidItem();
        return clinicalAssessmentService.recordAsync(session.userId, session.id, payload, currentSchemaHash()).
            onFailure().invoke(f -> LOG.warn("临床评估落库失败: userId={}, session={}, {}", session.userId, session.id, f.getMessage())).
            onFailure().recoverWithItem(() -> null);
    }

    //* 当前下发契约的归一化指纹: 语义必须与 buildSystemPrompt 的下发判定严格一致 —
    //* canonical 或 "结构增强暂禁" (未命中缓存) 均未下发自定义结构 → null; 仅已下发的自定义结构才有指纹.
    private @Nullable String currentSchemaHash()
    {
        if(!clinicalTagging)
            return null;
        final var effective = promptProvider.clinicalSchema();
        if(AiPromptConstants.CLINICAL_OUTPUT_SCHEMA_DEFAULT.equals(effective))
            return null;
        //* 指纹只算一次: 三目两侧各调一次 sha256Hex 是纯重复计算 (与 resolveSchemaForContract 下发判定的重复不同, 那处跨方法边界).
        final var hash = ClinicalSchemaNormalizer.sha256Hex(effective);
        return schemaNormalizer.cachedFor(hash) == null ? null : hash;
    }

    //* 对用户最新消息执行预警等级检测.
    //! WarningDetectionAgent 的 detect 是同步阻塞调用, 直接在事件循环线程调用会触发
    //! BlockingNotAllowedException 被静默吞掉, 导致 warningTriggered 永远为 false —
    //! 必须经 vertx.executeBlocking 在 worker 线程池执行.
    //! 检测对象为用户消息而非 AI 回复: 共情回复会复述用户的痛苦内容, 以回复为对象会产生误报.
    private @NotNull Uni<@Nullable WarningDetectionResult> detectWarning(@NotNull String userContent)
    {
        return vertx.executeBlocking(() -> warningDetectionAgent.detect(promptProvider.warningDetection(), userContent), false).
            onFailure().invoke(t -> LOG.warn("预警检测失败: {}", t.getMessage())).
            //* 预警检测失败不阻塞主对话流程, 降级为无预警 (null).
            onFailure().recoverWithItem(() -> null);
    }

    //* 依据检测结果标记会话预警位, RED 等级经 AlertDispatchService 统一分发 (per-user 冷却闸门
    //* + 逐渠道 fire-and-forget 推送热线) — 冷却只闸门外呼, 落库标记不受影响.
    //! 必须在持久化前调用 (受管 Session), 确保 warningTriggered 随消息一并落库.
    private void applyWarning(@NotNull AiChatSession session, @Nullable WarningDetectionResult detection)
    {
        if(detection == null)
            return;
        if("RED".equals(detection.warningLevel()))
        {
            session.warningTriggered = true;
            //* Uni 是惰性的, 必须订阅才真正触发分发; dispatchRed 契约恒成功完成 (冷却判定/渠道推送失败
            //! 全部内部收口 WARN), 订阅级兜底仅防意外实现缺陷, 不允许预警分发拖垮会话主流程.
            alertDispatchService.dispatchRed(session.userId, detection.reason()).
                subscribe().with(
                    v -> {},
                    t -> LOG.warn("RED 预警分发执行失败: userId={}, {}", session.userId, t.getMessage())
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
    //* 非 static: 滚动上限来自构造器注入的配置字段 maxHistoryMessages.
    private @NotNull String buildConversationHistory(@NotNull AiChatSession session)
    {
        if(session.messages == null || session.messages.isBlank())
            return "";
        try
        {
            final var messages = JsonUtils.parseJson(session.messages, new TypeReference<List<Map<String, String>>>() {});
            final var sb       = new StringBuilder();
            final var start    = Math.max(0, messages.size() - maxHistoryMessages);
            for(var i = start; i < messages.size(); i++)
            {
                final var msg     = messages.get(i);
                final var role    = msg.getOrDefault("role", "unknown");
                final var content = msg.getOrDefault("content", "");
                sb.append(PrintUtils.quickFormat("{}: {}\n", role, content));
            }
            return sb.toString();
        }
        catch(Exception e)
        {
            LOG.warn("构建对话历史失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 解析会话消息 JSON 为历史条目列表 (按对话顺序).
     *
     * @param messagesJson 会话消息 JSONB 原文
     * @return 消息条目列表; 空白/损坏 JSON 按空列表降级 (与预览/计数同款容错)
     * @since 1.2.1
     */
    private static @NotNull List<ChatHistoryMessage> parseHistory(@Nullable String messagesJson)
    {
        if(messagesJson == null || messagesJson.isBlank())
            return List.of();
        try
        {
            return JsonUtils.parseJson(messagesJson, new TypeReference<List<Map<String, String>>>() {}).
                stream().
                map(m -> new ChatHistoryMessage(m.getOrDefault("role", "unknown"), m.getOrDefault("content", ""))).
                toList();
        }
        catch(Exception e)
        {
            LOG.warn("解析会话消息历史失败: {}", e.getMessage());
            return List.of();
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
            return lastContent.length() > 50 ? PrintUtils.quickFormat("{}...", lastContent.substring(0, 50)) : lastContent;
        }
        catch(Exception e) { LOG.warn("解析 messages JSON 获取预览失败: {}", e.getMessage()); return ""; }
    }

    //endregion

    //region 会话标题
    //* 会话列表连同待补标题任务一并带出事务: 补标题要另开事务, 必须在列表事务提交后 fire.
    private record SessionListView(@NotNull List<ChatSessionVo> items, @NotNull List<TitleJob> titleJobs) {}

    //* 待补标题任务: 会话 ID + 生成用的种子文本 (该会话的首条用户消息).
    private record TitleJob(@NotNull UUID sessionId, @NotNull String seed) {}

    //* 实体列表 -> 概览视图 (含待补标题任务); 纯内存映射, 不含任何外呼.
    private static @NotNull SessionListView toListView(@NotNull List<AiChatSession> sessions)
    {
        final var cutoff = Instant.now().minus(TITLE_BACKFILL_QUIET);
        final var items  = new ArrayList<ChatSessionVo>(sessions.size());
        final var jobs   = new ArrayList<TitleJob>();
        for(final var session: sessions)
        {
            items.add(
                new ChatSessionVo(
                    session.id,
                    countMessages(session.messages),
                    session.updatedAt,
                    getPreview(session.messages),
                    titleOf(session)
                )
            );
            final var seed = pendingTitleSeed(session, cutoff);
            if(seed != null)
                jobs.add(new TitleJob(session.id, seed));
        }
        return new SessionListView(List.copyOf(items), List.copyOf(jobs));
    }

    //* 待补标题的种子文本: 已有标题 / 无用户消息 / 仍在静默期内一律返回 null (该会话本轮不补).
    private static @Nullable String pendingTitleSeed(@NotNull AiChatSession session, @NotNull Instant cutoff)
    {
        if(!isUntitled(session) || session.updatedAt == null || session.updatedAt.isAfter(cutoff))
            return null;
        return firstUserMessage(session.messages);
    }

    //* 首条用户消息: 标题生成的种子; 无用户消息或 JSON 损坏时返回 null.
    //! 截断到 TITLE_SEED_MAX_CHARS: 种子只用于概括主题, 首条消息过长时无谓消耗 token.
    private static @Nullable String firstUserMessage(@Nullable String messagesJson)
    {
        if(messagesJson == null || messagesJson.isBlank())
            return null;
        try
        {
            final var messages = JsonUtils.parseJson(messagesJson, new TypeReference<List<Map<String, String>>>() { });
            for(final var message: messages)
            {
                if(!"user".equals(message.get("role")))
                    continue;
                final var content = message.get("content");
                if(content == null || content.isBlank())
                    continue;
                return content.length() > TITLE_SEED_MAX_CHARS ? content.substring(0, TITLE_SEED_MAX_CHARS) : content;
            }
            return null;
        }
        catch(Exception e) { LOG.warn("解析 messages JSON 提取首条用户消息失败: {}", e.getMessage()); return null; }
    }

    //* 统计指定 role 的消息条数 (JSON 损坏时按 0 降级, 与 countMessages/getPreview 同款容错).
    private static int countRole(@Nullable String messagesJson, @NotNull String role)
    {
        if(messagesJson == null || messagesJson.isBlank())
            return 0;
        try
        {
            return (int) JsonUtils.parseJson(messagesJson, new TypeReference<List<Map<String, String>>>() { }).
                stream().
                filter(message -> role.equals(message.get("role"))).
                count();
        }
        catch(Exception e) { LOG.warn("解析 messages JSON 统计角色消息数失败: {}", e.getMessage()); return 0; }
    }

    private static boolean isUntitled(@NotNull AiChatSession session) { return session.title == null || session.title.isBlank(); }

    private static @NotNull String titleOf(@NotNull AiChatSession session) { return session.title == null ? "" : session.title; }

    //* 首条用户消息触发生成会话标题: 与 AI 回复并行外呼, 不占用对话主链路的延迟预算.
    //! 判定用"用户消息恰有一条"而非"本次是首轮": send 与 stream 的落库时机不同, 以消息构成判定对两条链路都成立,
    //! 且天然幂等 — 已生成标题或会话已有后续消息时直接跳过.
    private void fireTitleIfFirstUserMessage(@NotNull AiChatSession session, @NotNull String content)
    {
        if(!isUntitled(session) || countRole(session.messages, "user") != 1)
            return;
        fireTitleGeneration(new TitleJob(session.id, content));
    }

    //* 标题生成 fire-and-forget: worker 线程执行阻塞 AI 调用, 结果回到调用方上下文落库.
    //* 失败仅 WARN 后吞掉 — 标题是展示增强, 不允许影响对话可用性 (与预警/评估同款 best-effort 边界).
    private void fireTitleGeneration(@NotNull TitleJob job)
    {
        vertx.executeBlocking(() -> sessionTitleAgent.generate(promptProvider.sessionTitle(), job.seed()), false).
            onItem().transformToUni(title -> storeTitle(job.sessionId(), title)).
            onFailure().invoke(f -> LOG.warn("会话标题生成失败: session={}, {}", job.sessionId(), f.getMessage())).
            //* 订阅级兜底: 失败在此归一为正常完成, 不需要调用方处理.
            onFailure().recoverWithItem(() -> null).
            subscribe().with(v -> {});
    }

    //* 标题落库 (独立事务): 会话已被删除时静默跳过 — 删除与生成并发属正常竞态, 不是错误.
    private static @NotNull Uni<Void> storeTitle(@NotNull UUID sessionId, @NotNull String rawTitle)
    {
        final var title = normalizeTitle(rawTitle);
        //* 归一化后为空 (模型只回了标点或空串): 不落库, 保留"未生成"语义供下次补全.
        if(title.isEmpty())
            return Uni.createFrom().voidItem();
        return Panache.withTransaction(
            () ->
            AiChatSession.
                <AiChatSession>findById(sessionId).
                onItem().
                ifNull().
                continueWith(() -> null).
                flatMap(
                    session ->
                    {
                        if(session == null)
                            return Uni.createFrom().voidItem();
                        session.title = title;
                        return session.persistAndFlush().replaceWithVoid();
                    }
                )
        );
    }

    //* 标题归一化: 模型输出不可全信 — 去首尾空白与换行, 剥掉引号与「标题:」类前缀, 压成单行, 超长硬截断.
    //! 不要为空的判空加在这里: 空串是"本次没拿到可用标题"的合法信号, 由调用方决定是否落库.
    private static @NotNull String normalizeTitle(@NotNull String raw)
    {
        var title = raw.strip().replaceAll("\\s+", " ");
        title = title.replaceFirst("^(标题|题目|主题)\\s*[:：]\\s*", "");
        title = title.replaceAll("^[\"'“”『「【]+", "").replaceAll("[\"'“”』」】。，.,]+$", "").strip();
        return title.length() > TITLE_MAX_CHARS ? title.substring(0, TITLE_MAX_CHARS) : title;
    }
    //endregion
}
