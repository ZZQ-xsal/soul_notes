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
 * <b>钉钉机器人渠道回环验真</b>: 报文形态 / 加签参数 / 截断纪律 / 禁用短路 (spec §6.1).
 * <p>测试源不使用 JetBrains Annotations, 故无 NullableProblems 检查面, 不设抑制注解.</p>
 * @since 1.3.0
 */
class DingTalkAlertNotifierTest
{
    private static final HookServer SERVER = new HookServer();

    @BeforeAll
    @SuppressWarnings("InstantiationOfUtilityClass")//! JsonUtils 为 final 全静态成员类, IDE 误报实例化; 构造器正是静态桥的测试初始化入口.
    static void initJsonMapper()
    {
        //* 纯单元测试无 CDI 容器, 手动构造与生产等价的 mapper (含 JavaTimeModule) — 报文序列化经 JsonUtils 静态桥 (Webhook/Sms 测试同一初始化方式).
        new JsonUtils(new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @BeforeAll
    static void startServer() { SERVER.start(); }

    @AfterAll
    static void stopServer() { SERVER.close(); }

    private static final class HookServer implements AutoCloseable
    {
        private HttpServer server;
        //* 并发集合 (Sms/企微 fixture 同款): HttpServer 回调线程写, 断言线程读, 裸 ArrayList 跨线程不可见.
        private final List<String> paths = new CopyOnWriteArrayList<>();
        private final List<String> bodies = new CopyOnWriteArrayList<>();
        //* 响应报文队列逐请求 poll 消费: 业务失败用例可编程下发 200 + errcode 非 0 的机器人报文.
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

    //* 显示名解析缝隙替身: 群消息定位到人靠 username, 替身恒回 "alice"; 替身语义就是任何输入同输出, id 故意不消费.
    @SuppressWarnings("unused")//! 替身不入消费入参是替身的本质, 非疏漏.
    private static Function<UUID, Uni<String>> resolver() { return id -> Uni.createFrom().item("alice"); }

    private DingTalkAlertNotifier notifier(String secret)
    {
        return new DingTalkAlertNotifier(SERVER.endpoint(), secret, DingTalkAlertNotifierTest::hotline, DingTalkAlertNotifierTest.resolver());
    }

    //* 实现必须为 CDI Bean: ChatService 的 List<IAlertNotifier> fan-out 依赖全部实现可被发现 (Sms/企微测试同款完备面).
    @Test void class_IsApplicationScopedBean()
    {
        assertNotNull(DingTalkAlertNotifier.class.getAnnotation(jakarta.enterprise.context.ApplicationScoped.class));
    }

    @Test void channel_IsDingtalk() { assertEquals("dingtalk", notifier("").channel()); }

    @Test void sign_IsDeterministicAndUrlEncoded()
    {
        final var first = DingTalkAlertNotifier.sign(1700000000000L, "secret-a");
        final var second = DingTalkAlertNotifier.sign(1700000000000L, "secret-a");
        assertEquals(first, second, "同 timestamp/secret 恒同签名");
        assertNotEquals(first, DingTalkAlertNotifier.sign(1700000000001L, "secret-a"), "timestamp 参与签名");
        assertFalse(first.contains("+"), "URL 编码后不得含 + (空格形态)");
    }

    //* I2 黄金向量: 官方加签算法约定 (HmacSHA256(secret), 待签串 = "<timestamp>\n<secret>", Base64 后 URLEncode)
    //* 钉死于固定输入 — 官方文档当前版本仅给代码示例无固定期望值, 期望值按预案以独立 JDK 脚本离线复算生成
    //* (不经过生产代码, 与 Task 1 的 Python 交叉验证同法); 待签串拼装/字符集/编码任一环出错都会破坏该向量.
    @Test void sign_GoldenVector()
    {
        assertEquals("78QOmmluX8lhTeMwhe2Rnm0D8roHdDrsd0Wu1r%2BLy1w%3D",
            DingTalkAlertNotifier.sign(1589045546223L, "SEC790e9c46ae19de8e5a59a58a9a7ba5018a8d0dbf1a4ee4e51b5436a0f8b7b9cd"),
            "加签黄金向量 (独立脚本复算, 含 Base64 尾垫 = 与 + 的 URL 编码形态)");
    }

    @Test void markdown_TruncatesReasonAt120()
    {
        final var longReason = "x".repeat(200);
        final var text = DingTalkAlertNotifier.markdown("400-161-9995", "alice", longReason);
        assertTrue(text.contains("x".repeat(120)));
        assertFalse(text.contains("x".repeat(121)), "reason 必须截断 120 字");
        assertTrue(text.contains("400-161-9995"));
    }

    @Test void notify_PostsMarkdownPayload_WithSignatureWhenSecretPresent()
    {
        SERVER.reset();
        notifier("secret-a").notify(UUID.randomUUID(), "RED", "检测到自伤倾向").
            await().atMost(Duration.ofSeconds(5));
        final var body = SERVER.bodies().getFirst();
        assertTrue(body.contains("\"msgtype\":\"markdown\""));
        assertTrue(body.contains("RED 预警"));
        assertTrue(body.contains("400-161-9995"));
        assertTrue(body.contains("alice"));
        final var path = SERVER.paths().getFirst();
        assertTrue(path.contains("timestamp=") && path.contains("sign="), "secret 非空必须附加加签参数");
    }

    @Test void notify_WithoutSecret_PostsBareWebhook()
    {
        SERVER.reset();
        notifier("").notify(UUID.randomUUID(), "RED", "x").
            await().atMost(Duration.ofSeconds(5));
        assertFalse(SERVER.paths().getFirst().contains("sign="), "无 secret 不得附加加签参数");
    }

    @Test void blankWebhook_DisablesChannelSilently()
    {
        SERVER.reset();
        new DingTalkAlertNotifier("", "", DingTalkAlertNotifierTest::hotline, DingTalkAlertNotifierTest.resolver()).
            notify(UUID.randomUUID(), "RED", "x").
            await().atMost(Duration.ofSeconds(5));
        assertTrue(SERVER.bodies().isEmpty(), "禁用渠道零请求");
    }

    //* I1 回归: 机器人业务失败 (加签错 errcode=310000/密钥失效/限流) 以 HTTP 200 + errcode 非 0 返回,
    //* 仅凭状态码判定会让渠道静默死亡 — 必须 WARN 留痕 (含 errcode) 且不向调用方抛错.
    @Test void notify_Http200WithBusinessErrorCode_LogsWarnQuietly()
    {
        SERVER.reset();
        SERVER.responseBodies.add("{\"errcode\":310000,\"errmsg\":\"sign not match\"}");
        final var capture = WarningLogCapture.attach(DingTalkAlertNotifier.class);
        try
        {
            assertDoesNotThrow(() -> notifier("secret-a").notify(UUID.randomUUID(), "RED", "检测到自伤倾向").
                await().atMost(Duration.ofSeconds(5)));
            assertEquals(1, SERVER.bodies().size(), "请求必须真实发出");
            assertTrue(capture.messages().stream().anyMatch(m -> m.contains("钉钉机器人业务失败") && m.contains("310000")),
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
        assertDoesNotThrow(() -> new DingTalkAlertNotifier("http://127.0.0.1:" + deadPort, "", DingTalkAlertNotifierTest::hotline, DingTalkAlertNotifierTest.resolver()).
            notify(UUID.randomUUID(), "RED", "x").
            await().atMost(Duration.ofSeconds(10)));
    }
}
