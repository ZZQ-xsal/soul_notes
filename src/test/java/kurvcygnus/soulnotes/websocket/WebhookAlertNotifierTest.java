package kurvcygnus.soulnotes.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link WebhookAlertNotifier} 行为单元测试</b>
 * <p>经 JDK 内置 HttpServer 回环接收 RED 预警负载, 覆盖负载字段形状与 hotline 主号码解析,
 * Bearer 鉴权头的携带与省略, 禁用态 (url 空) 零请求, 以及 5xx/读超时/连接拒绝/热线解析失败/
 * 非法地址五类故障全部降级为日志绝不抛出 (主预警链路安全, Spec §7.2), 不触真实网络.</p>
 * @since 2.0
 */
class WebhookAlertNotifierTest
{
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    //* 生产实现固定 3s 请求超时 (Spec §7.2); 测试经构造缝下发更短超时, 避免套件被真实超时拖慢.
    private static final Duration PROD_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration AWAIT = Duration.ofSeconds(5);

    @BeforeAll
    @SuppressWarnings("InstantiationOfUtilityClass")//! JsonUtils 为 final 全静态成员类, IDE 误报实例化; 构造器正是静态桥的测试初始化入口.
    static void initJsonMapper()
    {
        //* 纯单元测试无 CDI 容器, 手动构造与生产等价的 mapper (含 JavaTimeModule), 与 JsonUtilsTest 同一初始化方式.
        new JsonUtils(new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    //region 回环 fixture

    /**
     * <b>回环 Webhook 接收端</b>
     * <p>//* 任意路径均伺服同一状态码 (无响应体), 请求方法/头/体逐次记录供断言;
     * hold 非 null 时 handler 先挂住再响应 (读超时用例), close 时放行以免线程滞留.</p>
     */
    private static final class HookServer implements AutoCloseable
    {
        private final HttpServer server;
        private final List<String> methods = new ArrayList<>();
        private final List<String> authorizations = new ArrayList<>();
        private final List<String> contentTypes = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private final int status;
        private final CountDownLatch hold;

        HookServer(int status, CountDownLatch hold) throws IOException
        {
            this.status = status;
            this.hold = hold;
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", this::respond);
            server.start();
        }

        private void respond(HttpExchange exchange) throws IOException
        {
            methods.add(exchange.getRequestMethod());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final var gate = hold;
            if(gate != null)
                try { gate.await(); }  //* 挂住直至 close() 放行: 触发客户端请求级读超时.
                catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            try(exchange) { exchange.sendResponseHeaders(status, -1); }  //* 无响应体, 仅状态码.
        }

        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        List<String> methods() { return List.copyOf(methods); }

        String lastAuthorization() { return authorizations.getLast(); }

        String lastContentType() { return contentTypes.getLast(); }

        String lastBody() { return bodies.getLast(); }

        @Override public void close()
        {
            if(hold != null) hold.countDown();  //* 放行挂住的 handler, 避免线程滞留到测试进程之外.
            server.stop(0);
        }
    }

    //* 热线源替身: 与 RedisStartupConfig.getHotline() 同构, 返回 "名称|主号码|备用号码" 原始串.
    private static Supplier<Uni<String>> hotline()
    {
        return () -> Uni.createFrom().item("全国心理援助热线|400-161-9995|12355");
    }

    //endregion

    //region 负载形状与鉴权

    //* 实现必须为 CDI Bean: ChatService 的 List<IAlertNotifier> fan-out 依赖全部实现可被发现.
    @Test void class_IsApplicationScopedBean()
    {
        assertNotNull(WebhookAlertNotifier.class.getAnnotation(ApplicationScoped.class));
    }

    //* RED 预警负载必须携带 Spec §7.2 全部字段, hotline 为主号码 (与 WS 推送同源解析).
    @Test void notifyPostsFullPayloadAndParsesPrimaryHotline() throws Exception
    {
        try(var server = new HookServer(200, null))
        {
            final var notifier = new WebhookAlertNotifier(server.endpoint(), "", PROD_TIMEOUT, hotline());

            notifier.notify(USER_ID, "RED", "检测到自伤倾向").await().atMost(AWAIT);

            assertEquals(List.of("POST"), server.methods(), "预警负载必须以 POST 提交");
            final var json = new ObjectMapper().readTree(server.lastBody());
            assertEquals("RED_ALERT", json.path("type").asText());
            assertEquals(USER_ID.toString(), json.path("userId").asText());
            assertEquals("RED", json.path("level").asText());
            assertEquals("检测到自伤倾向", json.path("reason").asText());
            assertEquals("400-161-9995", json.path("hotline").asText(), "hotline 必须是主号码而非原始串");
            assertEquals("application/json", server.lastContentType());
        }
    }

    //* token 非空 → 请求必须携带 Authorization: Bearer <token> (机构侧鉴权).
    @Test void tokenPresentSendsBearerHeader() throws Exception
    {
        try(var server = new HookServer(200, null))
        {
            final var notifier = new WebhookAlertNotifier(server.endpoint(), "inst-token", PROD_TIMEOUT, hotline());

            notifier.notify(USER_ID, "RED", "r").await().atMost(AWAIT);

            assertEquals("Bearer inst-token", server.lastAuthorization(), "非空 token 必须以 Bearer 头携带");
        }
    }

    //* token 为空 (未配置) → 不得携带任何鉴权头.
    @Test void blankTokenOmitsAuthorizationHeader() throws Exception
    {
        try(var server = new HookServer(200, null))
        {
            final var notifier = new WebhookAlertNotifier(server.endpoint(), "", PROD_TIMEOUT, hotline());

            notifier.notify(USER_ID, "RED", "r").await().atMost(AWAIT);

            assertNull(server.lastAuthorization(), "空 token 不得携带鉴权头");
        }
    }

    //endregion

    //region 禁用态

    //* url 空/空白 = 渠道禁用: 不发请求, notify 恒成功完成 (Spec §7.2).
    @Test void blankUrlDisablesChannelSilently() throws Exception
    {
        try(var server = new HookServer(200, null))
        {
            final var notifier = new WebhookAlertNotifier("   ", "", PROD_TIMEOUT, hotline());

            assertDoesNotThrow(() -> notifier.notify(USER_ID, "RED", "r").await().atMost(AWAIT));
            assertTrue(server.methods().isEmpty(), "禁用态不得发出任何请求");
        }
    }

    //endregion

    //region 故障降级 (失败仅日志, 绝不抛出)

    //* 5xx: 请求已发出且被服务端拒绝, 仍须降级为日志.
    @Test void serverErrorCompletesQuietlyWithoutThrowing() throws Exception
    {
        try(var server = new HookServer(500, null))
        {
            final var notifier = new WebhookAlertNotifier(server.endpoint(), "", PROD_TIMEOUT, hotline());

            assertDoesNotThrow(() -> notifier.notify(USER_ID, "RED", "r").await().atMost(AWAIT), "5xx 只允许记日志, 绝不抛出");
            assertEquals(List.of("POST"), server.methods(), "故障用例也必须真实发出请求 (验证降级而非未触发)");
        }
    }

    //* 服务端挂住不响应 → 请求级读超时触发, 仍须降级为日志.
    @Test void readTimeoutCompletesQuietlyWithoutThrowing() throws Exception
    {
        try(var server = new HookServer(200, new CountDownLatch(1)))
        {
            final var notifier = new WebhookAlertNotifier(server.endpoint(), "", Duration.ofMillis(200), hotline());

            assertDoesNotThrow(() -> notifier.notify(USER_ID, "RED", "r").await().atMost(AWAIT), "超时只允许记日志, 绝不抛出");
            assertEquals(List.of("POST"), server.methods(), "超时用例也必须真实发出请求 (验证降级而非未触发)");
        }
    }

    //* 端口无监听 → 连接被拒, 仍须降级为日志.
    @Test void connectionRefusedCompletesQuietlyWithoutThrowing() throws Exception
    {
        final int deadPort;
        try(var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { deadPort = socket.getLocalPort(); }
        final var notifier = new WebhookAlertNotifier("http://127.0.0.1:" + deadPort, "", PROD_TIMEOUT, hotline());

        assertDoesNotThrow(() -> notifier.notify(USER_ID, "RED", "r").await().atMost(AWAIT));
    }

    //* 热线解析失败 (Redis 不可用等) → 仍须降级为日志, 不允许渠道故障逃逸.
    @Test void hotlineLookupFailureCompletesQuietlyWithoutThrowing() throws Exception
    {
        try(var server = new HookServer(200, null))
        {
            final var notifier = new WebhookAlertNotifier(server.endpoint(), "", PROD_TIMEOUT,
                () -> Uni.createFrom().failure(new IllegalStateException("redis down")));

            assertDoesNotThrow(() -> notifier.notify(USER_ID, "RED", "r").await().atMost(AWAIT));
        }
    }

    //* 配置了非法地址 → URI 解析失败, 仍须降级为日志 (渠道安全网兜住同步异常).
    @Test void malformedUrlCompletesQuietlyWithoutThrowing()
    {
        final var notifier = new WebhookAlertNotifier("::not a url::", "", PROD_TIMEOUT, hotline());

        assertDoesNotThrow(() -> notifier.notify(USER_ID, "RED", "r").await().atMost(AWAIT));
    }

    //endregion
}
