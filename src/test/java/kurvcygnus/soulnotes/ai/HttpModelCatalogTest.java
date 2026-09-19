package kurvcygnus.soulnotes.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link HttpModelCatalog} 行为单元测试</b>
 * <p>经 JDK 内置 HttpServer 回环伺服 OpenAI 兼容 {@code /models} 响应, 覆盖 URL 启发式
 * ( {@code /v1} 结尾与否则拼路径 / 规范化 ), Bearer 头, 扩展字段从宽映射, 401/403 与其余非 200
 * 的异常分型, 以及连接拒绝/读超时的网络失败路径, 不触真实网络.</p>
 * @since 2.0
 */
class HttpModelCatalogTest
{
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    //region 回环 fixture

    /**
     * <b>回环模型目录服务器</b>
     * <p>任意路径均伺服同一状态码 + 响应体, 请求路径与 Authorization 头逐次记录供启发式/Bearer 断言;
     * hold 非 null 时 handler 先挂住再响应 (读超时用例), close 时放行以免线程滞留.</p>
     */
    private static final class CatalogServer implements AutoCloseable
    {
        private final HttpServer server;
        private final List<String> paths = new ArrayList<>();
        private final List<String> authorizations = new ArrayList<>();
        private final int status;
        private final String body;
        private final CountDownLatch hold;

        CatalogServer(int status, String body, CountDownLatch hold) throws IOException
        {
            this.status = status;
            this.body = body;
            this.hold = hold;
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", this::respond);
            server.start();
        }

        private void respond(HttpExchange exchange) throws IOException
        {
            paths.add(exchange.getRequestURI().getPath());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            final var gate = hold;
            if(gate != null)
                try
                {
                    gate.await();  //* 挂住直至 close() 放行: 无界但安全, try-with-resources 保证 close 必达.
                }
                catch(InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            try(exchange)
            {
                final var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try(var out = exchange.getResponseBody())
                {
                    out.write(bytes);
                }
            }
        }

        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        List<String> paths() { return List.copyOf(paths); }

        String lastAuthorization() { return authorizations.getLast(); }

        @Override public void close()
        {
            if(hold != null) hold.countDown();  //* 放行挂住的 handler, 避免线程滞留到测试进程之外.
            server.stop(0);
        }
    }

    //endregion

    //region URL 启发式与规范化

    //* endpoint 不以 /v1 结尾 → 探测 {endpoint}/v1/models, normalized = base + /v1; 无扩展字段映射为 "-".
    @Test void endpointWithoutV1ProbesAtV1ModelsAndNormalizes() throws Exception
    {
        try(var server = new CatalogServer(200, "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-x\"},{\"id\":\"deepseek-v3\"}]}", null))
        {
            final var result = new HttpModelCatalog().fetch(server.endpoint(), "sk-abc", TIMEOUT);

            assertEquals(List.of("/v1/models"), server.paths(), "不带 /v1 的 endpoint 必须拼接 /v1/models 探测");
            assertEquals("Bearer sk-abc", server.lastAuthorization(), "鉴权必须以 Bearer 头携带密钥");
            assertEquals(server.endpoint() + "/v1", result.normalizedEndpoint(), "normalized 必须是 langchain4j base-url 形态 (base + /v1)");
            assertEquals(List.of("gpt-x", "deepseek-v3"), result.models().stream().map(IModelCatalog.ModelInfo::id).toList());
            assertTrue(result.models().stream().allMatch(m -> m.context().equals("-") && m.reasoning().equals("-")), "无扩展字段须映射为 -");
        }
    }

    //* endpoint 已以 /v1 结尾 → 只拼 /models, normalized 与输入一致 (不重复追加).
    @Test void endpointEndingWithV1AppendsModelsOnly() throws Exception
    {
        try(var server = new CatalogServer(200, "{\"data\":[{\"id\":\"m\"}]}", null))
        {
            final var v1 = server.endpoint() + "/v1";
            final var result = new HttpModelCatalog().fetch(v1, "sk", TIMEOUT);

            assertEquals(List.of("/v1/models"), server.paths());
            assertEquals(v1, result.normalizedEndpoint());
        }
    }

    //* 尾斜杠 endpoint 不得产生 //v1//models 之类的病态拼接, 规范化结果同样无重复斜杠.
    @Test void trailingSlashEndpointIsTrimmedBeforeHeuristic() throws Exception
    {
        try(var server = new CatalogServer(200, "{\"data\":[{\"id\":\"m\"}]}", null))
        {
            final var result = new HttpModelCatalog().fetch(server.endpoint() + "/", "sk", TIMEOUT);

            assertEquals(List.of("/v1/models"), server.paths());
            assertEquals(server.endpoint() + "/v1", result.normalizedEndpoint());
        }
    }

    //endregion

    //region 扩展字段从宽映射

    //* context_length 原样透传; 思考判定从宽: supported_parameters 含 reasoning 或 reasoning=true → ✓, 显式 false → ✗, 无 → -.
    @Test void extensionFieldsMappedLeniently() throws Exception
    {
        final var body = """
            {"data":[
              {"id":"a","context_length":128000,"supported_parameters":["tools","reasoning"]},
              {"id":"b","context_length":8192,"reasoning":false},
              {"id":"c","reasoning":true},
              {"id":"d","supported_parameters":["chat.formatted"]},
              {"id":"e","context_length":"256k"}
            ]}
            """;
        try(var server = new CatalogServer(200, body, null))
        {
            final var models = new HttpModelCatalog().fetch(server.endpoint(), "sk", TIMEOUT).models();

            assertEquals(5, models.size());
            assertEquals("128000", models.get(0).context());
            assertEquals("✓", models.get(0).reasoning(), "supported_parameters 含 reasoning → ✓");
            assertEquals("8192", models.get(1).context());
            assertEquals("✗", models.get(1).reasoning(), "显式 reasoning=false → ✗");
            assertEquals("-", models.get(2).context());
            assertEquals("✓", models.get(2).reasoning(), "reasoning=true → ✓");
            assertEquals("-", models.get(3).context());
            assertEquals("-", models.get(3).reasoning(), "无任何信号 → -");
            assertEquals("256k", models.get(4).context(), "字符串型 context_length 原样透传 (从宽)");
            assertEquals("-", models.get(4).reasoning());
        }
    }

    //* data 为空数组 → 空列表正常返回, 由向导层走手动输入兜底.
    @Test void emptyDataYieldsEmptyModelList() throws Exception
    {
        try(var server = new CatalogServer(200, "{\"data\":[]}", null))
        {
            assertTrue(new HttpModelCatalog().fetch(server.endpoint(), "sk", TIMEOUT).models().isEmpty());
        }
    }

    //endregion

    //region 异常分型

    //* 401/403 是密钥被拒的明确信号, 必须以 UnauthorizedException 分型抛出供向导走 "重输密钥" 分支.
    @Test void http401And403ThrowUnauthorized() throws Exception
    {
        try(var server = new CatalogServer(401, "{\"error\":\"invalid api key\"}", null))
        {
            assertThrows(IModelCatalog.UnauthorizedException.class,
                () -> new HttpModelCatalog().fetch(server.endpoint(), "sk", TIMEOUT));
        }
        try(var server = new CatalogServer(403, "{}", null))
        {
            assertThrows(IModelCatalog.UnauthorizedException.class,
                () -> new HttpModelCatalog().fetch(server.endpoint(), "sk", TIMEOUT));
        }
    }

    //* 其余非 200 (以 500 为例) 属服务/网络层失败 → IOException, 携带状态码.
    @Test void otherErrorStatusThrowsIOException() throws Exception
    {
        try(var server = new CatalogServer(500, "boom", null))
        {
            final var e = assertThrows(IOException.class,
                () -> new HttpModelCatalog().fetch(server.endpoint(), "sk", TIMEOUT));
            assertTrue(e.getMessage().contains("500"), "异常消息必须携带状态码: " + e.getMessage());
        }
    }

    //* 200 但响应体不是 JSON (反代劫持/网关错误页) → IOException, 向导层据此走手动输入兜底.
    @Test void malformedBodyThrowsIOException() throws Exception
    {
        try(var server = new CatalogServer(200, "<html>gateway error</html>", null))
        {
            assertThrows(IOException.class,
                () -> new HttpModelCatalog().fetch(server.endpoint(), "sk", TIMEOUT));
        }
    }

    //* 端口无监听 → 连接被拒 (ConnectException 是 IOException 子类), 不得逃逸为非受检异常.
    @Test void connectionRefusedThrowsIOException() throws Exception
    {
        final int deadPort;
        try(var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { deadPort = socket.getLocalPort(); }
        //* 端口已释放, 连接必然被拒; 极端竞争下端口被复用会误收响应, 概率可忽略.
        assertThrows(IOException.class,
            () -> new HttpModelCatalog().fetch("http://127.0.0.1:" + deadPort, "sk", TIMEOUT));
    }

    //* 服务端挂住不响应 → 请求级超时 (HttpTimeoutException 是 IOException 子类) 必须在 timeout 参数内触发.
    @Test void readTimeoutThrowsIOException() throws Exception
    {
        try(var server = new CatalogServer(200, "{}", new CountDownLatch(1)))
        {
            assertThrows(HttpTimeoutException.class,
                () -> new HttpModelCatalog().fetch(server.endpoint(), "sk", Duration.ofMillis(200)));
        }
    }

    //endregion
}
