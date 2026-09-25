package kurvcygnus.soulnotes.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import io.smallrye.mutiny.Uni;
import kurvcygnus.soulnotes.utils.JsonUtils;
import kurvcygnus.soulnotes.websocket.SmsAlertNotifier.SmsConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>短信渠道回环验真</b>: JDK HttpServer 冒充 dysmsapi 端点, 覆盖逐号群发 / 签名请求形态 /
 * 模板参数 / 禁用短路 / 单号失败不拖垮其余 (SOULNOTES_ALERT_SMS_ENDPOINT 可配是回环前提, spec §8).
 * <p>测试源不使用 JetBrains Annotations, 故无 NullableProblems 检查面, 不设抑制注解.</p>
 * @since 1.3.0
 */
class SmsAlertNotifierTest
{
    private static final HookServer SERVER = new HookServer();

    //* 与生产实现请求级超时同级: 拒连/业务失败用例都必须远快于该上限完成.
    private static final Duration AWAIT = Duration.ofSeconds(5);

    @BeforeAll
    @SuppressWarnings("InstantiationOfUtilityClass")//! JsonUtils 为 final 全静态成员类, IDE 误报实例化; 构造器正是静态桥的测试初始化入口.
    static void initJsonMapper()
    {
        //* 纯单元测试无 CDI 容器, 手动构造与生产等价的 mapper (含 JavaTimeModule), 与 WebhookAlertNotifierTest 同一初始化方式.
        new JsonUtils(new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @BeforeAll
    static void startServer() { SERVER.start(); }

    @AfterAll
    static void stopServer() { SERVER.close(); }

    //* 回环端点服务器: 记录全部请求 URL 与响应码可编程.
    private static final class HookServer implements AutoCloseable
    {
        private HttpServer server;
        //* 响应码队列逐请求 poll 消费: 业务失败用例可编程 [400, 200] 逐号下发, 使 "单号失败不拖垮其余" 具备逐号判别力.
        private final Queue<Integer> statuses = new ConcurrentLinkedQueue<>();
        //* 逐号剧本 (按请求 URL 中的号码路由): 渠道层 endpoint 全渠道共用一个, ServerSocket(0) 拒连端口的
        //* "一号死端口一号回环" 用例不可直接复用 — 以服务器对指定号码挂住不响应, 由客户端请求级 3s 超时
        //* 产生确定性异步网络失败 (超时事件由客户端定时器发出, 时机不受服务器/selector 活动干扰).
        private final List<String> urls = new CopyOnWriteArrayList<>();

        void start()
        {
            try
            {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                //* 默认 executor 是单线程: hold 剧本会阻塞 handler 线程, 其余号码的请求将无法被并发处理 — 换线程池.
                server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
                server.createContext("/", exchange ->
                {
                    final var uri = exchange.getRequestURI().toString();
                    urls.add(uri);
                    if(holdPhones.contains(phoneNumberOf(uri)))
                        try { Thread.sleep(9000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                    final var status = statuses.isEmpty() ? 200 : statuses.poll();
                    final var body = status == 200
                        ? "{\"Message\":\"OK\",\"RequestId\":\"x\",\"BizId\":\"y\",\"Code\":\"OK\"}"
                        : "{\"Message\":\"limit\",\"Code\":\"isv.BUSINESS_LIMIT_CONTROL\"}";
                    final var bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    try(var out = exchange.getResponseBody()) { out.write(bytes); }
                    exchange.close();
                });
                server.start();
            }
            catch(IOException e) { throw new IllegalStateException(e); }
        }

        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        List<String> urls() { return List.copyOf(urls); }

        //* 可编程 fixture: 状态码序列逐请求消费, 接口保持可下发任意序列供后续用例扩展 (调用点已多值, 无 SameParameterValue 前提).
        void nextStatus(int status) { statuses.add(status); }

        //* 指定号码模拟网络黑洞: 服务器收到请求后挂住 9s 不响应 — 客户端请求级 3s 超时确定性异步失败.
        //* (字段直加而非参数化助手: 单调用点会触发 SameParameterValue 告警, nextStatus 多值先例不同.)
        final Set<String> holdPhones = ConcurrentHashMap.newKeySet();

        void reset()
        {
            urls.clear();
            statuses.clear();
            holdPhones.clear();
        }

        @Override public void close() { if(server != null) server.stop(0); }
    }

    //* 从签名 URL 的 canonical 段提取 PhoneNumbers 参数值 (号码纯数字, 无编码字符).
    private static String phoneNumberOf(String url)
    {
        final var key = "PhoneNumbers=";
        final var start = url.indexOf(key) + key.length();
        return url.substring(start, url.indexOf('&', start));
    }

    //* 固定配置 (endpoint 指向回环) 与解析缝隙替身.
    private SmsConfig config()
    {
        return new SmsConfig("ak", "sk", "签名", "SMS_1", "13800138000,13900139000", SERVER.endpoint());
    }

    private static Uni<String> hotline() { return Uni.createFrom().item("全国心理援助热线|400-161-9995|12355"); }

    //* 热线源替身与 resolver 均无视入参: 替身语义就是 "任何输入同输出", id 故意不消费.
    @SuppressWarnings("unused")//! 替身不入消费入参是替身的本质, 非疏漏.
    private static Function<UUID, Uni<String>> resolver() { return id -> Uni.createFrom().item("alice"); }

    private SmsAlertNotifier notifier() { return new SmsAlertNotifier(config(), SmsAlertNotifierTest::hotline, SmsAlertNotifierTest.resolver()); }

    //* 实现必须为 CDI Bean: ChatService 的 List<IAlertNotifier> fan-out 依赖全部实现可被发现.
    @Test void class_IsApplicationScopedBean()
    {
        assertNotNull(SmsAlertNotifier.class.getAnnotation(jakarta.enterprise.context.ApplicationScoped.class));
    }

    @Test void channel_IsSms() { assertEquals("sms", notifier().channel()); }

    @Test void disabledConfig_CompletesWithoutAnyRequest()
    {
        SERVER.reset();
        //* 五值未全非空 → enabled=false → 直通零请求 (部分配置形态).
        final var partially = new SmsAlertNotifier(
            new SmsConfig("ak", "", "", "", "", SERVER.endpoint()), SmsAlertNotifierTest::hotline, SmsAlertNotifierTest.resolver()
        );
        partially.notify(UUID.randomUUID(), "RED", "自伤风险").await().atMost(AWAIT);
        assertTrue(SERVER.urls().isEmpty(), "禁用渠道不得发出任何请求");
    }

    //* 群发必须逐号独立发送而非一次合批: 两个请求各携带一个号码, 合计覆盖全部号码.
    @Test void notify_DialsEachPhoneExactlyOnce()
    {
        SERVER.reset();
        notifier().notify(UUID.randomUUID(), "RED", "检测到自伤倾向").await().atMost(AWAIT);
        final var urls = SERVER.urls();
        assertEquals(2, urls.size(), "双号码必须逐号各发一次");
        assertEquals(Set.of("13800138000", "13900139000"),
            Set.of(phoneNumberOf(urls.getFirst()), phoneNumberOf(urls.getLast())),
            "每个请求只携带一个号码, 且两请求合计覆盖全部号码");
    }

    //* 请求形态必须是指向配置端点的已签名 SendSms GET (回环端点验真签名链路真实走通).
    @Test void notify_SendsSignedSendSmsRequestToConfiguredEndpoint()
    {
        SERVER.reset();
        notifier().notify(UUID.randomUUID(), "RED", "x").await().atMost(AWAIT);
        final var url = SERVER.urls().getFirst();
        //* getRequestURI() 只含 path+query: 请求能落进本回环服务器本身就已证明端点按配置路由.
        assertTrue(url.startsWith("/?"), "请求必须以 canonical query 直接打在端点根路径 (RPC GET 形态)");
        assertTrue(url.contains("Action=SendSms"));
        assertTrue(url.contains("SignatureMethod=HMAC-SHA1"), "必须携带 RPC 签名方法");
        assertTrue(url.contains("&Signature="), "签名必须追加在 canonical 之后");
    }

    //* 模板参数必须携带解析后的主热线与学生显示名 — 值班咨询员靠它定位到人与干预资源.
    @Test void notify_TemplateParamCarriesHotlineAndStudent()
    {
        SERVER.reset();
        notifier().notify(UUID.randomUUID(), "RED", "检测到自伤倾向").await().atMost(AWAIT);
        final var decoded = URLDecoder.decode(SERVER.urls().getFirst(), StandardCharsets.UTF_8);
        assertTrue(decoded.contains("\"hotline\":\"400-161-9995\""), "模板参数必须含解析后的主热线");
        assertTrue(decoded.contains("\"student\":\"alice\""));
    }

    @Test void notify_BusinessFailureOnOnePhone_DoesNotBreakOthers()
    {
        SERVER.reset();
        SERVER.nextStatus(400);//* 首号拿到业务失败响应.
        SERVER.nextStatus(200);//* 次号拿到成功响应 — 逐号独立下发, 非合批同码.
        assertDoesNotThrow(() -> notifier().notify(UUID.randomUUID(), "RED", "x").await().atMost(AWAIT));
        assertEquals(2, SERVER.urls().size(), "首号业务失败不得中断其余号码, 两号都必须真实发出");
    }

    //* C1 回归: 逐号异步网络失败 (超时/拒连类, 区别于业务码失败) 必须被逐号层吞掉, 不得逃逸进合并流.
    //! 判别原理: 死号挂住 → 客户端 3s 请求超时产生确定性异步失败. 失败若从 sendOne 逃逸, merge 以 failure
    //! 收场并取消其余在途号码, 外层安全网哨兵 WARN ("短信预警推送失败") 必然同步留痕 — 外层哨兵零触发即为
    //! 逐号收口成立的确定性证据 (时序判别受生产 3s 请求超时钳制: 存活号在途窗 < 3s 与 3s 确定性失败不可同窗).
    @Test void notify_AsyncNetworkFailureOnOnePhone_DoesNotLeakToSafetyNet()
    {
        SERVER.reset();
        SERVER.holdPhones.add("13800138000");//* 死号: 挂住至客户端 3s 请求超时; 存活号走默认 200.
        final var capture = WarningLogCapture.attach(SmsAlertNotifier.class);
        try
        {
            assertDoesNotThrow(() -> notifier().notify(UUID.randomUUID(), "RED", "x").await().atMost(Duration.ofSeconds(10)));
            assertEquals(Set.of("13800138000", "13900139000"),
                SERVER.urls().stream().map(SmsAlertNotifierTest::phoneNumberOf).collect(java.util.stream.Collectors.toSet()),
                "两号都必须真实发出");
            assertTrue(capture.messages().stream().noneMatch(m -> m.contains("短信预警推送失败")),
                "逐号失败不得逃逸到外层安全网 (哨兵 WARN 被触发): " + capture.messages());
        }
        finally
        {
            capture.detach();
        }
    }

    @Test void notify_ConnectionRefused_CompletesQuietly() throws IOException
    {
        //* 拒连端口以 ServerSocket(0) 绑定即关动态生成 (WebhookAlertNotifierTest 同款): 硬编码端口 1 在部分环境可能被占用或被策略拦截.
        final int deadPort;
        try(var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { deadPort = socket.getLocalPort(); }
        final var dead = new SmsAlertNotifier(
            new SmsConfig("ak", "sk", "签名", "SMS_1", "13800138000", "http://127.0.0.1:" + deadPort),
            SmsAlertNotifierTest::hotline, SmsAlertNotifierTest.resolver()
        );
        assertDoesNotThrow(() -> dead.notify(UUID.randomUUID(), "RED", "x").await().atMost(Duration.ofSeconds(10)));
    }
}
