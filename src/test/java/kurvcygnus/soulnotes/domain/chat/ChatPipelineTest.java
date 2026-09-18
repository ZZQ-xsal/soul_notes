package kurvcygnus.soulnotes.domain.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.domain.chat.entity.AiChatSession;
import kurvcygnus.soulnotes.support.MockLlmProfile;
import kurvcygnus.soulnotes.support.MockLlmServer;
import kurvcygnus.soulnotes.support.PipelineUsers;
import kurvcygnus.soulnotes.utils.PrintUtils;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;

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
 * @since 2.0
 */
@QuarkusTest
@TestProfile(MockLlmProfile.class)
class ChatPipelineTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Duration AWAIT = Duration.ofSeconds(20);

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
            joined.get(20, TimeUnit.SECONDS);
        }
        catch(java.util.concurrent.TimeoutException e)
        {
            fail(PrintUtils.quickFormat("20s 内未集齐流式帧; 已收: {}; mock 请求数: {}; 末请求片段: {}",
                received, MockLlmProfile.server().requests().size(),
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
        final var frameDeadline = System.currentTimeMillis() + 20000;
        while(System.currentTimeMillis() < frameDeadline && received.length() < bothStreams.length())
            Thread.sleep(100);
        //* 两条流并发执行, 帧交错序不固定: 以字符多重集等价断言内容完整 (既不少帧也不重复帧).
        assertEquals(bothStreams.length(), received.length(), PrintUtils.quickFormat("两条流的帧应全部到达; 已收: {}", received));
        assertEquals(sortedChars(bothStreams), sortedChars(received.toString()), PrintUtils.quickFormat("帧内容应恰为两条流的 chunk 多重集; 已收: {}", received));

        //* 帧到齐后轮询等落库 (持久化在各自流完成时异步执行): 2 个会话 x [user, assistant] 共 4 条消息.
        final var dbDeadline = System.currentTimeMillis() + 15000;
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
