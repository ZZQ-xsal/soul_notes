package kurvcygnus.soulnotes.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kurvcygnus.soulnotes.utils.PrintUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link MockLlmServer} OpenAI 兼容契约测试</b>
 * <p>纯 JUnit 环境钉死 mock 服务器自身的行为契约: 非流式文本 / 流式 SSE / 工具调用两轮 /
 * 关键词预警 JSON / 请求录制. 集成链路测试 ({@code ChatPipelineTest}) 依赖这些契约成立.</p>
 * @since 1.1.0
 */
class MockLlmServerContractTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static MockLlmServer server;
    private static HttpClient http;

    @BeforeAll
    static void startServer()
    {
        server = MockLlmServer.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stopServer() { server.stop(); }

    @BeforeEach
    void rearm() { server.reset(); }

    //region ① 非流式纯文本
    @Test
    void chatCompletion_TextMode_ShouldReturnProgrammedTextAsMessageContent() throws Exception
    {
        server.respondWithText("夜色很温柔, 我在这里陪着你。");

        final var response = postChat(chatBody("有点累了", true, false));

        assertEquals(200, response.statusCode());
        final var content = readContent(response.body());
        assertEquals("夜色很温柔, 我在这里陪着你。", content, PrintUtils.quickFormat("应返回编程文本, 实际: {}", response.body()));
    }

    @Test
    void chatCompletion_TextMode_ShouldUseStopFinishReason() throws Exception
    {
        server.respondWithText("晚安");
        final var root = readRoot(postChat(chatBody("晚安", true, false)).body());
        assertEquals("stop", root.path("choices").path(0).path("finish_reason").asText());
        assertEquals("assistant", root.path("choices").path(0).path("message").path("role").asText());
    }
    //endregion

    //region ② 流式 SSE
    @Test
    void chatCompletion_StreamMode_ShouldEmitChunkSequenceThenDoneMarker() throws Exception
    {
        server.respondWithChunks("你好", ", 我在", "。");

        final var payloads = streamChat(chatBody("在吗", true, true));

        assertFalse(payloads.isEmpty(), "SSE 应至少下发一个事件");
        assertEquals("[DONE]", payloads.getLast(), PrintUtils.quickFormat("末事件应为完成标记, 实际: {}", payloads));
        final var contents = new ArrayList<String>();
        for(final var payload: payloads.subList(0, payloads.size() - 1))
            contents.add(readRoot(payload).path("choices").path(0).path("delta").path("content").asText());
        assertEquals(List.of("你好", ", 我在", "。"), contents, "chunk 序列必须保序");
    }

    @Test
    void chatCompletion_StreamMode_NonStreamingRequest_ShouldFallBackToJoinedText() throws Exception
    {
        //* 容错契约: SSE 模式下偶发的非流式请求 (如预警轮) 返回 chunk 拼接全文, 而非空响应.
        server.respondWithChunks("夜色", "很温柔");
        final var content = readContent(postChat(chatBody("在吗", true, false)).body());
        assertEquals("夜色很温柔", content);
    }
    //endregion

    //region ③ 工具调用
    @Test
    void chatCompletion_ToolMode_ShouldReturnToolCallsOnFirstRound() throws Exception
    {
        server.respondWithToolCall("getCrisisMessage");

        final var root = readRoot(postChat(chatBody("帮帮我", true, false)).body());
        final var toolCalls = root.path("choices").path(0).path("message").path("tool_calls");
        assertTrue(toolCalls.isArray() && !toolCalls.isEmpty(), PrintUtils.quickFormat("首轮应返回 tool_calls: {}", root));
        assertEquals("getCrisisMessage", toolCalls.path(0).path("function").path("name").asText());
        assertEquals("tool_calls", root.path("choices").path(0).path("finish_reason").asText());
    }

    @Test
    void chatCompletion_ToolMode_ShouldEchoToolResultOnSecondRound() throws Exception
    {
        server.respondWithToolCall("getCrisisMessage");
        postChat(chatBody("帮帮我", true, false));

        final var secondRound = postChat(chatBodyWithToolResult("热线 400-161-9995 请立即拨打"));
        final var content = readContent(secondRound.body());
        assertTrue(content.contains("工具结果回显确认"), PrintUtils.quickFormat("次轮应回显工具结果: {}", content));
        assertTrue(content.contains("400-161-9995"), PrintUtils.quickFormat("回显必须包含真实工具输出: {}", content));
        assertEquals(2, server.requests().size(), "工具链路应恰好产生两轮请求");
    }

    @Test
    void chatCompletion_StreamWithToolRound_ShouldRejectWith500InsteadOfUndefinedBehavior() throws Exception
    {
        server.respondWithToolCall("getCrisisMessage");

        final var response = postChat(chatBody("帮帮我", true, true));

        assertEquals(500, response.statusCode(), PrintUtils.quickFormat("stream=true 与工具轮组合应显式拒绝: {}", response.body()));
        assertTrue(response.body().contains("不受 mock 支持"), "拒绝消息应说明契约边界");
    }
    //endregion

    //region ④ 预警 JSON
    @Test
    void chatCompletion_RedKeywordWithoutTools_ShouldReturnRedLevelDetectionJson() throws Exception
    {
        final var response = postChat(chatBody(MockLlmServer.RED_KEYWORD + " 我撑不下去了", false, false));
        final var detection = readRoot(readContent(response.body()));
        assertEquals("RED", detection.path("warningLevel").asText(), PrintUtils.quickFormat("应返回 RED 预警: {}", detection));
    }

    @Test
    void chatCompletion_NoKeywordWithoutTools_ShouldReturnNoneLevelDetectionJson() throws Exception
    {
        final var response = postChat(chatBody("今天天气不错", false, false));
        final var detection = readRoot(readContent(response.body()));
        assertEquals("NONE", detection.path("warningLevel").asText(), "无关键词的结构化输出请求应返回 NONE, 保证生产侧解析零告警");
    }

    @Test
    void chatCompletion_RedKeywordWithTools_ShouldKeepChatReplyAsProgrammedText() throws Exception
    {
        //* 关键词只作用于无工具的结构化输出请求: 共情回复保持对话文本, 预警由检测 Agent 独立触发.
        server.respondWithText("我听到了你的痛苦。");
        final var content = readContent(postChat(chatBody(MockLlmServer.RED_KEYWORD + " 我撑不下去了", true, false)).body());
        assertEquals("我听到了你的痛苦。", content);
    }
    //endregion

    //region 请求录制与辅助端点
    @Test
    void requests_ShouldRecordBodiesInArrivalOrder() throws Exception
    {
        postChat(chatBody("第一条", true, false));
        postChat(chatBody("第二条", true, false));
        final var requests = server.requests();
        assertEquals(2, requests.size());
        assertTrue(requests.getFirst().contains("第一条"), "录制应保序");
        assertTrue(requests.getLast().contains("第二条"), "录制应保序");
    }

    @Test
    void models_ShouldReturnListPayload() throws Exception
    {
        final var request = HttpRequest.newBuilder(URI.create(PrintUtils.quickFormat("{}/v1/models", server.baseUrl()))).GET().build();
        final var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("list", readRoot(response.body()).path("object").asText());
    }
    //endregion

    //region 测试脚手架
    private HttpResponse<String> postChat(String jsonBody) throws Exception
    {
        final var request = HttpRequest.newBuilder(URI.create(PrintUtils.quickFormat("{}/v1/chat/completions", server.baseUrl()))).
            header("Content-Type", "application/json").
            timeout(Duration.ofSeconds(10)).
            POST(HttpRequest.BodyPublishers.ofString(jsonBody)).
            build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    //* 消费 SSE 行流, 提取全部 data: 载荷 (不含前缀).
    private List<String> streamChat(String jsonBody) throws Exception
    {
        final var request = HttpRequest.newBuilder(URI.create(PrintUtils.quickFormat("{}/v1/chat/completions", server.baseUrl()))).
            header("Content-Type", "application/json").
            timeout(Duration.ofSeconds(10)).
            POST(HttpRequest.BodyPublishers.ofString(jsonBody)).
            build();
        final var payloads = new ArrayList<String>();
        http.send(request, HttpResponse.BodyHandlers.ofLines()).body().
            forEach(line -> { if(line.startsWith("data:")) payloads.add(line.substring("data:".length()).stripLeading()); });
        return payloads;
    }

    //* 组装 OpenAI 兼容对话请求: withTools 模拟共情 Agent (携带工具定义), stream 模拟流式客户端.
    private String chatBody(String userContent, boolean withTools, boolean stream) throws Exception
    {
        final var root = MAPPER.createObjectNode();
        root.put("model", "soulnotes-mock-llm");
        root.put("stream", stream);
        root.putArray("messages").addObject().put("role", "user").put("content", userContent);
        if(withTools)
        {
            final var tool = root.putArray("tools").addObject();
            tool.put("type", "function");
            tool.putObject("function").put("name", "getCrisisMessage");
        }
        return MAPPER.writeValueAsString(root);
    }

    //* 次轮请求形状: 工具定义仍在 (与真实 LangChain4j 回喂一致) + assistant tool_calls + role=tool 结果消息.
    private String chatBodyWithToolResult(String toolContent) throws Exception
    {
        final var root = MAPPER.createObjectNode();
        root.put("model", "soulnotes-mock-llm");
        root.put("stream", false);
        final var tools = root.putArray("tools");
        final var toolDef = tools.addObject();
        toolDef.put("type", "function");
        toolDef.putObject("function").put("name", "getCrisisMessage");
        final var messages = root.putArray("messages");
        messages.addObject().put("role", "user").put("content", "帮帮我");
        final var assistant = messages.addObject();
        assistant.put("role", "assistant");
        final var toolCalls = assistant.putArray("tool_calls");
        final var call = toolCalls.addObject();
        call.put("id", "call-mock-1");
        call.put("type", "function");
        call.putObject("function").put("name", "getCrisisMessage").put("arguments", "{}");
        messages.addObject().put("role", "tool").put("tool_call_id", "call-mock-1").put("content", toolContent);
        return MAPPER.writeValueAsString(root);
    }

    private static JsonNode readRoot(String json)
    {
        try { return MAPPER.readTree(json); }
        catch(Exception e) { throw new AssertionError(PrintUtils.quickFormat("响应不是合法 JSON: {}", json), e); }
    }

    private static String readContent(String responseJson) { return readRoot(responseJson).path("choices").path(0).path("message").path("content").asText(); }
    //endregion
}
