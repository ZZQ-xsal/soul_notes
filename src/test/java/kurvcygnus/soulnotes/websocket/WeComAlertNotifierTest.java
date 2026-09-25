package kurvcygnus.soulnotes.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import io.smallrye.mutiny.Uni;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>企业微信群机器人渠道回环验真</b>: 报文形态 / 截断纪律 / 禁用短路 / 无加签直发 (spec §6.1).
 * <p>测试源不使用 JetBrains Annotations, 故无 NullableProblems 检查面, 不设抑制注解.</p>
 * @since 1.3.0
 */
class WeComAlertNotifierTest
{
    private static final HookServer SERVER = new HookServer();

    @BeforeAll
    @SuppressWarnings("InstantiationOfUtilityClass")//! JsonUtils 为 final 全静态成员类, IDE 误报实例化; 构造器正是静态桥的测试初始化入口.
    static void initJsonMapper()
    {
        //* 纯单元测试无 CDI 容器, 手动构造与生产等价的 mapper (含 JavaTimeModule) — 报文序列化经 JsonUtils 静态桥 (钉钉/Sms 测试同一初始化方式).
        new JsonUtils(new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @BeforeAll
    static void startServer() { SERVER.start(); }

    @AfterAll
    static void stopServer() { SERVER.close(); }

    private static final class HookServer implements AutoCloseable
    {
        private HttpServer server;
        //* 并发集合 (Sms fixture 同款): HttpServer 回调线程写, 断言线程读, 裸 ArrayList 跨线程不可见.
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<String> bodies = new CopyOnWriteArrayList<>();
        //* 响应报文队列逐请求 poll 消费: 业务失败用例可编程下发 200 + errcode 非 0 的机器人报文 (钉钉 fixture 同款).
        //* (字段直加而非参数化助手: 单调用点会触发 SameParameterValue 告警, Sms 测试 statuses 多值先例不同.)
        final Queue<String> responseBodies = new ConcurrentLinkedQueue<>();

        void start()
        {
            try
            {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                server.createContext("/", exchange ->
                {
                    paths.add(exchange.getRequestURI().toString());
                    final var body = exchange.getRequestBody().readAllBytes();
                    bodies.add(new String(body, StandardCharsets.UTF_8));
                    final var bytes = (responseBodies.isEmpty() ? "{\"errcode\":0,\"errmsg\":\"ok\"}" : responseBodies.poll()).
                        getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try(var out = exchange.getResponseBody()) { out.write(bytes); }
                    exchange.close();
                });
                server.start();
            }
            catch(IOException e) { throw new IllegalStateException(e); }
        }

        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        List<String> paths() { return List.copyOf(paths); }

        List<String> bodies() { return List.copyOf(bodies); }

        void reset()
        {
            paths.clear();
            bodies.clear();
            responseBodies.clear();
        }

        @Override public void close() { if(server != null) server.stop(0); }
    }

    private static Uni<String> hotline() { return Uni.createFrom().item("全国心理援助热线|400-161-9995|12355"); }

    //* 显示名解析缝隙替身 (钉钉测试同款): 群消息定位到人靠 username, 替身恒回 "alice"; 替身语义就是任何输入同输出, id 故意不消费.
    @SuppressWarnings("unused")//! 替身不入消费入参是替身的本质, 非疏漏.
    private static Function<UUID, Uni<String>> resolver() { return id -> Uni.createFrom().item("alice"); }

    private WeComAlertNotifier notifier()
    {
        return new WeComAlertNotifier(SERVER.endpoint(), WeComAlertNotifierTest::hotline, WeComAlertNotifierTest.resolver());
    }

    //* 实现必须为 CDI Bean: ChatService 的 List<IAlertNotifier> fan-out 依赖全部实现可被发现.
    @Test void class_IsApplicationScopedBean()
    {
        assertNotNull(WeComAlertNotifier.class.getAnnotation(jakarta.enterprise.context.ApplicationScoped.class));
    }

    @Test void channel_IsWecom() { assertEquals("wecom", notifier().channel()); }

    @Test void notify_PostsWeComMarkdownPayload()
    {
        SERVER.reset();
        notifier().notify(UUID.randomUUID(), "RED", "检测到自伤倾向").
            await().atMost(Duration.ofSeconds(5));
        final var body = SERVER.bodies().getFirst();
        assertTrue(body.contains("\"msgtype\":\"markdown\""));
        assertTrue(body.contains("\"content\""), "企微报文字段为 content (非钉钉的 title/text)");
        assertTrue(body.contains("**RED 预警**"));
        assertTrue(body.contains("400-161-9995"));
        assertTrue(body.contains("alice"));
        assertFalse(SERVER.paths().getFirst().contains("sign="), "企微凭据即 URL key, 无加签参数");
    }

    @Test void markdown_TruncatesReasonAt120()
    {
        final var text = WeComAlertNotifier.markdown("400-161-9995", "alice", "y".repeat(200));
        assertTrue(text.contains("y".repeat(120)));
        assertFalse(text.contains("y".repeat(121)), "reason 必须截断 120 字");
    }

    @Test void blankWebhook_DisablesChannelSilently()
    {
        SERVER.reset();
        new WeComAlertNotifier("", WeComAlertNotifierTest::hotline, WeComAlertNotifierTest.resolver()).
            notify(UUID.randomUUID(), "RED", "x").
            await().atMost(Duration.ofSeconds(5));
        assertTrue(SERVER.bodies().isEmpty(), "禁用渠道零请求");
    }

    //* I1 回归: 机器人业务失败 (key 失效 errcode=40001/机器人被移除/限流) 以 HTTP 200 + errcode 非 0 返回,
    //* 仅凭状态码判定会让渠道静默死亡 — 必须 WARN 留痕 (含 errcode) 且不向调用方抛错 (钉钉用例同款).
    @Test void notify_Http200WithBusinessErrorCode_LogsWarnQuietly()
    {
        SERVER.reset();
        SERVER.responseBodies.add("{\"errcode\":40001,\"errmsg\":\"invalid credential, hint: [xyz]\"}");
        final var capture = WarningLogCapture.attach(WeComAlertNotifier.class);
        try
        {
            assertDoesNotThrow(() -> notifier().notify(UUID.randomUUID(), "RED", "检测到自伤倾向").
                await().atMost(Duration.ofSeconds(5)));
            assertEquals(1, SERVER.bodies().size(), "请求必须真实发出");
            assertTrue(capture.messages().stream().anyMatch(m -> m.contains("企微机器人业务失败") && m.contains("40001")),
                "业务级失败必须 WARN 留痕 (含 errcode): " + capture.messages());
        }
        finally
        {
            capture.detach();
        }
    }

    @Test void serverError_CompletesQuietly() throws IOException
    {
        //* 拒连端口以 ServerSocket(0) 绑定即关动态生成 (SmsAlertNotifierTest 同款): 硬编码端口 1 在部分环境可能被占用或被策略拦截.
        final int deadPort;
        try(var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { deadPort = socket.getLocalPort(); }
        assertDoesNotThrow(() -> new WeComAlertNotifier("http://127.0.0.1:" + deadPort, WeComAlertNotifierTest::hotline, WeComAlertNotifierTest.resolver()).
            notify(UUID.randomUUID(), "RED", "x").
            await().atMost(Duration.ofSeconds(10)));
    }
}
