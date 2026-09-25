package kurvcygnus.soulnotes.domain.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.domain.chat.entity.AiChatSession;
import kurvcygnus.soulnotes.support.InfraProbes;
import kurvcygnus.soulnotes.support.MockLlmProfile;
import kurvcygnus.soulnotes.support.MockLlmServer;
import kurvcygnus.soulnotes.support.PipelineUsers;
import kurvcygnus.soulnotes.utils.PrintUtils;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>Mock-LLM 全链路集成测试</b>
 * <p>真实应用栈 (HTTP/SSE/WebSocket + Hibernate Reactive + Redis + LangChain4j OpenAI 客户端)
 * 对接进程内 {@link MockLlmServer}, 覆盖五条链路:</p>
 * <ul>
 *     <li>① {@code /chat/send} 非流式: 回复透传 + 契约块拆流 (前端与落库均不含 soulnotes 块) + systemPrompt 组装</li>
 *     <li>② RED 预警: 关键词触发预警检测, {@code warning_triggered} 落库且主对话链路不中断</li>
 *     <li>③ 工具调用: mock 首轮发起 tool_calls, 次轮回显工具结果 (证明 @MemoryId 透传与工具执行回流)</li>
 *     <li>④ {@code /chat/stream} SSE: 多 chunk 到达且拼接等于 mock 文本</li>
 *     <li>⑤ {@code /ws/chat} WebSocket: 流式帧拼接等于 mock 文本 (token 鉴权升级)</li>
 * </ul>
 * <p>基建守卫: 强依赖本机 postgres + redis (CI 由 service 容器提供), 缺席时应用启动即失败,
 * {@code assumeTrue} 来不及救 — 以 {@code @EnabledIf} 在 JUnit 执行条件层整类跳过 (本地开发者双保险).</p>
 * @since 1.1.0
 */
@QuarkusTest
@TestProfile(MockLlmProfile.class)
@EnabledIf(value = "pipelineInfraReachable", disabledReason = "本机 postgres/redis 未运行, 跳过 Mock-LLM 全链路用例")
class ChatPipelineTest
{
    //* @EnabledIf 的引用方法必须落在被注解类内: QuarkusTest 类加载器下跨类全限定字符串解析失败 (实测),
    //* 故逐类以同名静态方法委托公共探测 [[InfraProbes#pipelineInfraReachable]], 判定逻辑单一来源不变.
    static boolean pipelineInfraReachable() { return InfraProbes.pipelineInfraReachable(); }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Duration AWAIT = Duration.ofSeconds(20);
    //* WS 帧集合/落库轮询的 CI 加固窗口: CI runner 冷链路 (langchain4j 流式客户端首调初始化 + 双流并发)
    //! 曾在 20s 窗口内帧未到齐 (v1.3.0 tag 构建实测), 本机秒级完成 — 窗口提到 60s/30s 只影响慢环境的等待上限.
    private static final long FRAME_DEADLINE_MS  = 60_000;
    private static final long DB_POLL_DEADLINE_MS = 30_000;

    @Inject Mutiny.SessionFactory sessionFactory;

    @TestHTTPResource(ApiEndpointConstants.CHAT_BASE + "/stream") URI streamUri;
    @TestHTTPResource("/ws/chat") URI wsUri;

    //* 用例隔离: 清空 mock 录制与编程状态, 防止跨用例的请求累积干扰断言.
    @org.junit.jupiter.api.BeforeEach
    void rearm() { MockLlmProfile.server().reset(); }

    //region ① /chat/send 非流式
    @Test
    void chatSend_PlainText_ShouldReturnMockReplyAndAssembleSystemPrompt()
    {
        final var account = PipelineUsers.register();
        MockLlmProfile.server().respondWithText("此刻愿意说出来, 已经很有勇气了。");

        final var body = chatSend(account.token(), "今天有点累");

        final var root = readTree(body, "chat/send 响应");
        assertEquals(0, root.path("code").asInt(), PrintUtils.quickFormat("业务码应为 0: {}", body));
        assertEquals("assistant", root.path("data").path("role").asText());
        assertEquals("此刻愿意说出来, 已经很有勇气了。", root.path("data").path("content").asText());

        //* systemPrompt 组装断言: 取携带工具定义的共情对话请求 (预警检测请求无工具且随后到达).
        final var chatRequest = MockLlmProfile.server().requests().stream().
            filter(r -> r.contains("\"tools\"")).
            reduce((first, second) -> second).
            orElseThrow(() -> new AssertionError("mock 应收到共情对话请求"));
        final var messages = readTree(chatRequest, "mock 收到的请求").path("messages");
        assertEquals("system", messages.path(0).path("role").asText());
        assertTrue(messages.path(0).path("content").asText().contains("[输出契约]"), "契约段应随 clinical.tagging 注入 systemPrompt");
        assertTrue(messages.path(1).path("content").asText().contains("今天有点累"), "用户消息应出现在 user 消息中");
    }

    @Test
    void chatSend_ContractBlock_ShouldStripSoulnotesFromFrontendAndStorage()
    {
        final var account = PipelineUsers.register();
        MockLlmProfile.server().respondWithText("我在这里陪着你。\n<!--soulnotes {\"tags\":[\"疲惫\"],\"riskLevel\":\"NONE\",\"summary\":\"倾听与回应\"}-->");

        final var body = chatSend(account.token(), "最近压力很大");

        assertFalse(body.contains("<!--soulnotes"), PrintUtils.quickFormat("前端响应不得携带契约块: {}", body));
        assertEquals("我在这里陪着你。", readTree(body, "chat/send 响应").path("data").path("content").asText(), "前端应仅见剥离后正文");

        final var session = latestSession(account.userId());
        assertNotNull(session, "会话应已落库");
        assertFalse(session.messages.contains("<!--soulnotes"), PrintUtils.quickFormat("落库文本不得携带契约块: {}", session.messages));
        final var messages = readTree(session.messages, "messages JSONB");
        final var lastMessage = messages.path(messages.size() - 1);
        assertEquals("assistant", lastMessage.path("role").asText());
        assertEquals("我在这里陪着你。", lastMessage.path("content").asText(), "落库应为剥离后正文");
        assertEquals("array", messagesJsonbType(session.id), PrintUtils.quickFormat("messages 必须落为真 JSON 数组而非双重编码字符串标量, 实际 jsonb_typeof = {}", messagesJsonbType(session.id)));
    }
    //endregion

    //region ② RED 预警落库
    @Test
    void chatSend_RedKeyword_ShouldPersistWarningTriggeredWithoutBreakingChat()
    {
        final var account = PipelineUsers.register();
        MockLlmProfile.server().respondWithText("我听到了你的痛苦。");

        final var body = chatSend(account.token(), MockLlmServer.RED_KEYWORD + " 我真的撑不下去了");

        assertEquals(0, readTree(body, "chat/send 响应").path("code").asInt(), "预警发生时主对话链路不得中断");
        final var session = latestSession(account.userId());
        assertNotNull(session, "会话应已落库");
        assertTrue(session.warningTriggered, "RED 预警必须落库 warning_triggered = true");
    }
    //endregion

    //region ③ 工具调用轮
    @Test
    void chatSend_ToolCallRound_ShouldExecuteLocalToolAndEchoResult()
    {
        final var account = PipelineUsers.register();
        MockLlmProfile.server().respondWithToolCall("getCrisisMessage");

        final var body = chatSend(account.token(), "帮我找一个可以立刻打电话的人");

        final var content = readTree(body, "chat/send 响应").path("data").path("content").asText();
        assertTrue(content.contains("工具结果回显确认"), PrintUtils.quickFormat("最终回复应携带工具结果回显: {}", content));
        assertTrue(content.contains("400-161-9995"), PrintUtils.quickFormat("回显必须包含真实工具输出 (热线号码, 证明本地工具确被执行): {}", content));

        //* 工具链路请求 = 携带 tools 定义的请求 (预警检测请求无工具且随后到达, 不计入轮次).
        final var toolRequests = MockLlmProfile.server().requests().stream().
            filter(r -> r.contains("\"tools\"")).
            toList();
        assertEquals(2, toolRequests.size(), PrintUtils.quickFormat("工具链路应恰好两轮: 发起 + 回流, 实际全部请求: {}", MockLlmProfile.server().requests().size()));
        final var firstRound = readTree(toolRequests.getFirst(), "首轮请求");
        assertTrue(firstRound.path("tools").isArray() && !firstRound.path("tools").isEmpty(), "首轮应携带工具定义");
        assertFalse(hasToolMessage(firstRound), "首轮不得携带 role=tool 消息");
        assertTrue(hasToolMessage(readTree(toolRequests.getLast(), "次轮请求")), "次轮请求应携带 role=tool 消息 (工具结果回流)");
    }
    //endregion

    //region ④ /chat/stream SSE
    @Test
    void chatStream_ShouldDeliverMultipleChunksConcatenatingMockText() throws Exception
    {
        final var account = PipelineUsers.register();
        MockLlmProfile.server().respondWithChunks("夜色", "很温柔", ", 我在这里。");

        final var request = HttpRequest.newBuilder(streamUri).
            header("Authorization", PipelineUsers.bearer(account.token())).
            header("Content-Type", "application/json").
            header("Accept", "text/event-stream").
            timeout(Duration.ofSeconds(30)).
            POST(HttpRequest.BodyPublishers.ofString(PrintUtils.quickFormat("{\"content\":\"{}\"}", "给我讲点什么吧"))).
            build();

        final var payloads = new ArrayList<String>();
        final var response = HTTP.send(request, HttpResponse.BodyHandlers.ofLines());
        response.body().forEach(line -> { if(line.startsWith("data:")) payloads.add(line.substring("data:".length()).stripLeading()); });

        assertEquals(200, response.statusCode(), "SSE 端点应返回 200");
        assertTrue(payloads.size() >= 2, PrintUtils.quickFormat("应到达多个 SSE chunk, 实际: {}", payloads));
        assertEquals("夜色很温柔, 我在这里。", String.join("", payloads), "chunk 拼接应等于 mock 文本 (mock 的 [DONE] 由 OpenAI 客户端消费, 不透传前端)");
    }
    //endregion

    //region ⑤ /ws/chat WebSocket
    @Test
    void chatWebSocket_ShouldStreamTokensAsFrames() throws Exception
    {
        final var account = PipelineUsers.register();
        MockLlmProfile.server().respondWithChunks("慢慢说", ", 我在听。", "不着急。");
        final var expected = "慢慢说, 我在听。不着急。";

        //* http(s) 升级为 ws(s), token 走查询参数 (服务端 HttpUpgradeCheck 同时支持 header 与 query param).
        final var scheme = "https".equals(wsUri.getScheme()) ? "wss" : "ws";
        final var endpoint = URI.create(PrintUtils.quickFormat("{}://{}:{}/ws/chat?token={}", scheme, wsUri.getHost(), wsUri.getPort(), account.token()));

        final var received = new StringBuilder();
        final var joined = new CompletableFuture<String>();
        final var listener = new WebSocket.Listener()
        {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
            {
                received.append(data);
                if(expected.equals(received.toString()))
                    joined.complete(received.toString());
                return WebSocket.Listener.super.onText(webSocket, data, last);
            }
        };
        final var webSocket = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(endpoint, listener).join();
        webSocket.request(1);
        webSocket.sendText(PrintUtils.quickFormat("{\"content\":\"{}\"}", "今天想找人聊聊"), true).join();

        try
        {
            joined.get(FRAME_DEADLINE_MS, TimeUnit.MILLISECONDS);
        }
        catch(java.util.concurrent.TimeoutException e)
        {
            fail(PrintUtils.quickFormat("{}ms 内未集齐流式帧; 已收: {}; mock 请求数: {}; 末请求片段: {}",
                FRAME_DEADLINE_MS, received, MockLlmProfile.server().requests().size(),
                MockLlmProfile.server().requests().isEmpty() ? "-" : MockLlmProfile.server().requests().getLast().substring(0, Math.min(300, MockLlmProfile.server().requests().getLast().length()))));
        }
        assertEquals(expected, received.toString(), "流式帧拼接应等于 mock 文本");
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    //* 并发加固: 第二条消息在第一条 LLM 流式窗口内即入队 (SERIAL 只保序回调启动, 链路并发执行),
    //* 两条流的 token 帧按时间交错到达; 逐消息 duplicated context 修复后各自独立 HR session 槽位,
    //* 双双完整落库. 修复前共享主 context 槽位会静默丢失其中一条的持久化 (仅 WARN 吞掉).
    @Test
    void chatWebSocket_BackToBackMessages_ShouldPersistBothStreamsUnderConcurrency() throws Exception
    {
        final var account = PipelineUsers.register();
        MockLlmProfile.server().respondWithChunks("慢慢说", ", 我在听。");
        final var singleStream = "慢慢说, 我在听。";
        final var bothStreams  = singleStream + singleStream;

        final var scheme = "https".equals(wsUri.getScheme()) ? "wss" : "ws";
        final var endpoint = URI.create(PrintUtils.quickFormat("{}://{}:{}/ws/chat?token={}", scheme, wsUri.getHost(), wsUri.getPort(), account.token()));

        final var received = new StringBuilder();
        final var listener = new WebSocket.Listener()
        {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
            {
                received.append(data);
                return WebSocket.Listener.super.onText(webSocket, data, last);
            }
        };
        final var webSocket = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(endpoint, listener).join();
        webSocket.request(1);
        webSocket.sendText(PrintUtils.quickFormat("{\"content\":\"{}\"}", "第一条消息"), true).join();
        webSocket.sendText(PrintUtils.quickFormat("{\"content\":\"{}\"}", "第二条消息"), true).join();

        //* 等两条流的帧全部到齐 (字符数守恒), 超时视为丢帧.
        final var frameDeadline = System.currentTimeMillis() + FRAME_DEADLINE_MS;
        while(System.currentTimeMillis() < frameDeadline && received.length() < bothStreams.length())
            Thread.sleep(100);
        //* 两条流并发执行, 帧交错序不固定: 以字符多重集等价断言内容完整 (既不少帧也不重复帧).
        assertEquals(bothStreams.length(), received.length(), PrintUtils.quickFormat("两条流的帧应全部到达; 已收: {}", received));
        assertEquals(sortedChars(bothStreams), sortedChars(received.toString()), PrintUtils.quickFormat("帧内容应恰为两条流的 chunk 多重集; 已收: {}", received));

        //* 帧到齐后轮询等落库 (持久化在各自流完成时异步执行): 2 个会话 x [user, assistant] 共 4 条消息.
        final var dbDeadline = System.currentTimeMillis() + DB_POLL_DEADLINE_MS;
        var sessionCount = 0;
        var totalMessages = 0;
        while(System.currentTimeMillis() < dbDeadline)
        {
            final var sessions = sessionFactory.withTransaction((session, tx) ->
                session.createQuery("from AiChatSession s where s.userId = ?1", AiChatSession.class).
                    setParameter(1, UUID.fromString(account.userId())).
                    getResultList()
            ).await().atMost(AWAIT);
            sessionCount = sessions.size();
            totalMessages = sessions.stream().mapToInt(s -> readTree(s.messages, "messages JSONB").size()).sum();
            if(sessionCount == 2 && totalMessages == 4)
                break;
            Thread.sleep(200);
        }
        assertEquals(2, sessionCount, "两条消息应各落一个会话");
        assertEquals(4, totalMessages, PrintUtils.quickFormat("并发消息的会话历史必须双双完整落库 (无静默丢失), 实际总消息数: {}", totalMessages));
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    //region ⑥ 会话归属校验
    @Test
    void chatSend_ForeignSession_ShouldReturnNotFoundWithoutLeaking()
    {
        final var owner = PipelineUsers.register();
        final var intruder = PipelineUsers.register();
        MockLlmProfile.server().respondWithText("只说给主人听。");

        chatSend(owner.token(), "主人与 AI 的私聊内容");

        final var sessionId = RestAssured.
            given().
            header("Authorization", PipelineUsers.bearer(owner.token())).
            when().
            get(ApiEndpointConstants.CHAT_BASE + "/sessions").
            then().
            statusCode(200).
            extract().path("data[0].sessionId");

        final var body = RestAssured.
            given().
            header("Authorization", PipelineUsers.bearer(intruder.token())).
            contentType("application/json").
            body(PrintUtils.quickFormat("{\"sessionId\":\"{}\",\"content\":\"越权尝试\"}", sessionId)).
            when().
            post(ApiEndpointConstants.CHAT_BASE + "/send").
            then().
            statusCode(404).
            extract().asString();

        assertTrue(body.contains("404020"), PrintUtils.quickFormat("业务码应为 404020 (会话不存在): {}", body));
        assertTrue(body.contains("会话不存在"), PrintUtils.quickFormat("响应必须以'不存在'回应, 不泄露资源存在性: {}", body));
    }
    //endregion

    //region ⑦ 会话消息历史与删除
    //* 回归 (前端对接反馈, 1.2.1): GET /chat/sessions 仅返回概览 (preview 为末条 50 字截断), 完整 LLM
    //! 回复无处可取 — 补 /sessions/{id}/messages 端点, 本组用例钉死消息序列/越权同码/非法 ID 同码/删除闭环.
    @Test
    void chatHistory_ShouldReturnFullMessagesInOrder()
    {
        final var account = PipelineUsers.register();
        final var sessionId = seedSession(account.userId(),
            "[{\"role\":\"user\",\"content\":\"今天有点累\"},{\"role\":\"assistant\",\"content\":\"愿意说出来, 已经很有勇气了。\"}]");

        RestAssured.
            given().
            header("Authorization", PipelineUsers.bearer(account.token())).
            when().
            get(ApiEndpointConstants.CHAT_BASE + "/sessions/" + sessionId + "/messages").
            then().
            statusCode(200).
            body("code", equalTo(0)).
            body("data.size()", equalTo(2)).
            body("data[0].role", equalTo("user")).
            body("data[0].content", equalTo("今天有点累")).
            body("data[1].role", equalTo("assistant")).
            body("data[1].content", equalTo("愿意说出来, 已经很有勇气了。"));
    }

    @Test
    void chatHistory_ForeignSession_ShouldRejectWithSameCodeAsMissing()
    {
        final var owner = PipelineUsers.register();
        final var sessionId = seedSession(owner.userId(), "[{\"role\":\"user\",\"content\":\"主人的私聊\"}]");
        final var intruder = PipelineUsers.register();

        final var body = RestAssured.
            given().
            header("Authorization", PipelineUsers.bearer(intruder.token())).
            when().
            get(ApiEndpointConstants.CHAT_BASE + "/sessions/" + sessionId + "/messages").
            then().
            statusCode(404).
            extract().asString();

        assertTrue(body.contains("404020"), PrintUtils.quickFormat("越权拉取历史应与缺失同码 (防枚举): {}", body));
    }

    @Test
    void chatHistory_MalformedSessionId_ShouldRejectAsNotFound()
    {
        final var account = PipelineUsers.register();

        final var body = RestAssured.
            given().
            header("Authorization", PipelineUsers.bearer(account.token())).
            when().
            get(ApiEndpointConstants.CHAT_BASE + "/sessions/not-a-uuid/messages").
            then().
            statusCode(404).
            extract().asString();

        assertTrue(body.contains("404020"), PrintUtils.quickFormat("非法 UUID 应与缺失同码 (防枚举): {}", body));
    }

    @Test
    void deleteSession_ShouldRemoveHistoryAndSubsequentReadsReturnNotFound()
    {
        final var account = PipelineUsers.register();
        final var sessionId = seedSession(account.userId(),
            "[{\"role\":\"user\",\"content\":\"先记一笔\"},{\"role\":\"assistant\",\"content\":\"收到。\"}]");

        RestAssured.
            given().
            header("Authorization", PipelineUsers.bearer(account.token())).
            when().
            delete(ApiEndpointConstants.CHAT_BASE + "/sessions/" + sessionId).
            then().
            statusCode(200).
            body("code", equalTo(0));

        RestAssured.
            given().
            header("Authorization", PipelineUsers.bearer(account.token())).
            when().
            get(ApiEndpointConstants.CHAT_BASE + "/sessions/" + sessionId + "/messages").
            then().
            statusCode(404);

        RestAssured.
            given().
            header("Authorization", PipelineUsers.bearer(account.token())).
            when().
            delete(ApiEndpointConstants.CHAT_BASE + "/sessions/" + sessionId).
            then().
            statusCode(404);
    }

    //* 真库直插会话 (指定 messages JSONB): 不经对话链路 (AI 依赖与本题无关), 与 DiaryListContractTest 造数同款取舍.
    private UUID seedSession(String userId, String messagesJson)
    {
        final var session = new AiChatSession();
        session.id               = UUID.randomUUID();
        session.userId           = UUID.fromString(userId);
        session.messages         = messagesJson;
        session.warningTriggered = false;
        session.updatedAt        = java.time.Instant.now();
        sessionFactory.withTransaction((s, tx) -> session.persist()).await().atMost(AWAIT);
        return session.id;
    }
    //endregion

    //* 字符多重集规范化 (排序拼接): 用于并发交错帧的内容等价比对, 不依赖到达顺序.
    private static String sortedChars(String s)
    {
        return s.chars().sorted().mapToObj(c -> String.valueOf((char) c)).reduce("", String::concat);
    }
    //endregion

    //region 测试脚手架
    private static String chatSend(String token, String content)
    {
        return Objects.requireNonNull(
            RestAssured.
                given().
                header("Authorization", PipelineUsers.bearer(token)).
                contentType("application/json").
                body(PrintUtils.quickFormat("{\"content\":\"{}\"}", content)).
                when().
                post(ApiEndpointConstants.CHAT_BASE + "/send").
                then().
                statusCode(200).
                extract().asString(),
            "chat/send 响应不得为 null"
        );
    }

    //* 独立事务新开 session 查询该用户最新会话: 读已提交数据, 不受任何一级缓存干扰.
    private AiChatSession latestSession(String userId)
    {
        return sessionFactory.withTransaction((session, tx) ->
            session.createQuery("from AiChatSession s where s.userId = ?1 order by s.updatedAt desc", AiChatSession.class).
                setParameter(1, UUID.fromString(userId)).
                setMaxResults(1).
                getSingleResultOrNull()
        ).await().atMost(AWAIT);
    }

    //* 原生查询直探 messages 列真实存储形态 (jsonb_typeof): 钉死新写入落为真 JSON 数组,
    //* 而非字符串标量双重编码 (权威契约同源: JsonbPersistenceFormTest).
    private String messagesJsonbType(UUID sessionId)
    {
        return sessionFactory.withTransaction((session, tx) ->
            session.createNativeQuery("select jsonb_typeof(messages) from ai_chat_sessions where id = ?1", String.class).
                setParameter(1, sessionId).
                getSingleResultOrNull()
        ).await().atMost(AWAIT);
    }

    private static boolean hasToolMessage(JsonNode request)
    {
        for(final var message: request.path("messages"))
        {
            if("tool".equals(message.path("role").asText()))
                return true;
        }
        return false;
    }

    private static JsonNode readTree(String json, String what)
    {
        try { return MAPPER.readTree(json); }
        catch(Exception e) { throw new AssertionError(PrintUtils.quickFormat("{} 不是合法 JSON: {}", what, json), e); }
    }
    //endregion
}
