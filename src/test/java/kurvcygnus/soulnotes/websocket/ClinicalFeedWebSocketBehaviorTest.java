package kurvcygnus.soulnotes.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.domain.auth.entity.User;
import kurvcygnus.soulnotes.domain.auth.service.TokenService;
import kurvcygnus.soulnotes.domain.chat.entity.AiChatSession;
import kurvcygnus.soulnotes.support.InfraProbes;
import kurvcygnus.soulnotes.support.MockLlmProfile;
import kurvcygnus.soulnotes.support.PipelineUsers;
import kurvcygnus.soulnotes.utils.PrintUtils;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>工作台 WS 推送行为级测试</b> (真库 + 真实升级握手, ChatPipelineTest §5 先例).
 * <p>补齐 spec §9 承诺的行为验证: {@link WebSocketAuthUpgradeCheck} 的角色门 (STUDENT 403 / COUNSELOR 放行)
 * 与 RED 评估落库后的 {@code NEW_ASSESSMENT} 实时帧 (复用 {@code ClinicalPipelineTest} 的 Mock-LLM 造数与
 * fire-and-forget 轮询先例).</p>
 * <p>结构断言 (注解/方法/端点声明) 仍在 {@code ClinicalFeedWebSocketTest} 纯单元类 — 真库装配
 * (@TestProfile + @EnabledIf) 是类级注解, 会连带纯结构用例一起依赖本机 postgres/redis, 故分置两类.</p>
 * @since 1.2.0
 */
@SuppressWarnings("NullableProblems")//! 测试模块不使用 JetBrains Annotations (项目测试惯例).
@QuarkusTest
@TestProfile(MockLlmProfile.class)
@EnabledIf(value = "pipelineInfraReachable", disabledReason = "本机 postgres/redis 未运行, 跳过工作台 WS 行为用例")
class ClinicalFeedWebSocketBehaviorTest
{
    //* @EnabledIf 的引用方法必须落在被注解类内: QuarkusTest 类加载器下跨类全限定字符串解析失败 (ChatPipelineTest 实测先例).
    static boolean pipelineInfraReachable() { return InfraProbes.pipelineInfraReachable(); }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Duration AWAIT = Duration.ofSeconds(20);

    //* WS 帧轮询上限 (~10s): 落库挂点是 fire-and-forget (HTTP 200 先于 INSERT/广播返回),
    //* ClinicalPipelineTest 先例收紧到 5s, 此处含 mock LLM 往返放宽一倍, 超时即链路断裂而非慢.
    private static final long FRAME_DEADLINE_MS = 10000;

    private static final String RED_CLINICAL_JSON =
        "{\"riskLevel\":\"RED\",\"tags\":[\"crisis\"],\"summary\":\"高危信号, 建议立即介入\"}";

    //* 类级随机后缀 (UUID 前 8 位): 同一 JVM 运行内共享账号, 跨次运行不撞 users.user_name UNIQUE 约束.
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

    @Inject TokenService tokenService;
    @Inject Mutiny.SessionFactory sessionFactory;

    @TestHTTPResource("/ws/clinical/feed") URI wsUri;

    //* 测试账号准备 (真库, 每用例按需幂等创建) — ClinicalResourceTest/ClinicalPipelineTest 同款模式.
    private final Map<String, User> users = new HashMap<>();

    @BeforeEach void prepare()
    {
        ensureClinicalSchema();
        //* 用例隔离: 清空 mock 录制与编程状态, 防止跨用例的请求累积干扰断言 (ChatPipelineTest rearm 先例).
        MockLlmProfile.server().reset();
    }

    //region (a) STUDENT 角色门: 升级必须被拒 (403)
    @Test void studentUpgrade_ToClinicalFeed_ShouldBeRejectedWith403()
    {
        final var student = ensureUser("ws-student", UserRole.STUDENT);

        final var handshake = assertThrows(CompletionException.class,
            () -> HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).
                buildAsync(endpointWithToken(tokenService.generateToken(student)), collectingListener(null, null)).join(),
            "STUDENT token 升级工作台端点必须失败");
        //* JDK HttpClient 的握手失败携带 WebSocketHandshakeException, 可直接取服务端升级响应的 HTTP 状态:
        //* 角色门语义 (WebSocketAuthUpgradeCheck 403 分支) 无需降级为笼统的 "连接不可建立" 即可精确断言.
        assertInstanceOf(WebSocketHandshakeException.class, handshake.getCause(), "失败原因应为升级握手被拒");
        assertEquals(403, ((WebSocketHandshakeException) handshake.getCause()).getResponse().statusCode(),
            "非咨询员角色的升级响应必须是 403");
    }
    //endregion

    //region (b) COUNSELOR 放行: 升级成功且连接可用
    @Test void counselorUpgrade_ToClinicalFeed_ShouldBeAccepted()
    {
        final var counselor = ensureUser("ws-counselor-ok", UserRole.COUNSELOR);
        final var opened = new AtomicBoolean(false);

        final var webSocket = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).
            buildAsync(endpointWithToken(tokenService.generateToken(counselor)), collectingListener(opened, null)).join();

        assertTrue(opened.get(), "升级握手成功后端点 onOpen 应已触发 (连接可用)");
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }
    //endregion

    //region (c) RED 造数 → NEW_ASSESSMENT 实时帧
    @Test void redAssessmentBroadcast_WhileCounselorConnected_ShouldDeliverNewAssessmentFrame() throws Exception
    {
        final var counselor = ensureUser("ws-counselor-live", UserRole.COUNSELOR);
        final var student = ensureUser("ws-red-student", UserRole.STUDENT);

        final var frames = new ArrayList<String>();
        final var webSocket = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).
            buildAsync(endpointWithToken(tokenService.generateToken(counselor)), collectingListener(null, frames)).join();

        try
        {
            //* 造数走真实对话链路 (不经 recordAsync 直调): 挂点里的 Panache.withTransaction 要求
            //* 被标记安全的 Vert.x 上下文, JUnit 线程直调必抛 (ClinicalResourceTest.recordAssessment 注释先例);
            //* 广播只有 recordAsync 落库成功后才发出, 故必须由 Mock-LLM 驱动完整管线.
            MockLlmProfile.server().respondWithClinical("我在这里, 你不是一个人。", RED_CLINICAL_JSON);
            final var body = chatSend(tokenService.generateToken(student), "我觉得活着没意思");
            assertEquals(0, readTree(body, "chat/send 响应").path("code").asInt(), "对话链路必须成功, 广播才有机会发出");

            final var sessionId = latestSessionId(student.id);
            assertNotNull(sessionId, "会话应已落库");
            final var frame = awaitFrame(frames, sessionId.toString());
            assertNotNull(frame, PrintUtils.quickFormat("轮询窗口内应收到 NEW_ASSESSMENT 帧; 实际帧: {}", frames));

            final var assessment = frame.path("assessment");
            assertEquals("NEW_ASSESSMENT", frame.path("type").asText(), "推送帧 type 必须是 NEW_ASSESSMENT");
            assertEquals("RED", assessment.path("riskLevel").asText(), "帧内评估等级必须与载荷一致");
            assertEquals(sessionId.toString(), assessment.path("sessionId").asText(), "帧应来自本次造数的会话");
            assertEquals(student.username, assessment.path("displayName").asText(), "RED 实名解锁应随 WS 帧生效 (RevealPolicy 契约)");
        }
        finally { webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); }
    }

    //* 轮询等待匹配会话的 NEW_ASSESSMENT 帧: 广播对全部在线咨询员一视同仁, 按唯一锚点 sessionId 过滤.
    private static JsonNode awaitFrame(List<String> frames, String sessionId) throws InterruptedException
    {
        final var deadline = System.currentTimeMillis() + FRAME_DEADLINE_MS;
        while(System.currentTimeMillis() < deadline)
        {
            for(final var raw: frames)
            {
                final var node = readTree(raw, "WS 帧");
                if("NEW_ASSESSMENT".equals(node.path("type").asText()) &&
                    sessionId.equals(node.path("assessment").path("sessionId").asText()))
                    return node;
            }
            Thread.sleep(200);
        }
        return null;
    }
    //endregion

    //region 测试脚手架
    //* http(s) 升级为 ws(s), token 走查询参数 (升级网关同时支持 header 与 query param, ChatPipelineTest §5 先例).
    private URI endpointWithToken(String token)
    {
        final var scheme = "https".equals(wsUri.getScheme()) ? "wss" : "ws";
        return URI.create(PrintUtils.quickFormat("{}://{}:{}/ws/clinical/feed?token={}", scheme, wsUri.getHost(), wsUri.getPort(), token));
    }

    /**
     * 帧收集监听器: 文本帧可能被分片 (last=false), 按整消息聚合后入列;
     * {@code opened} / {@code frames} 传 null 时跳过对应观测 (用例按需取用).
     */
    private static WebSocket.Listener collectingListener(AtomicBoolean opened, List<String> frames)
    {
        return new WebSocket.Listener()
        {
            private final StringBuilder partial = new StringBuilder();

            @Override
            public void onOpen(WebSocket ws)
            {
                if(opened != null)
                    opened.set(true);
                ws.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last)
            {
                if(frames != null)
                {
                    partial.append(data);
                    if(last)
                    {
                        frames.add(partial.toString());
                        partial.setLength(0);
                    }
                }
                return WebSocket.Listener.super.onText(ws, data, last);
            }
        };
    }

    /**
     * 确保角色账号存在并返回实体: 独立事务按用户名查重, 缺席则以 {@code User.create} 工厂落库
     * (ClinicalResourceTest 同款).
     */
    private User ensureUser(String rolePrefix, UserRole role)
    {
        return users.computeIfAbsent(rolePrefix, k ->
        {
            final var username = PrintUtils.quickFormat("ws-feed-{}-{}", rolePrefix, SUFFIX);
            return sessionFactory.withTransaction((session, tx) ->
                User.findByUsername(username).
                    onItem().ifNull().switchTo(() ->
                    {
                        final var user = User.create(username, "it-not-a-real-hash", role);
                        return user.persist().replaceWith(user);
                    })
            ).await().atMost(AWAIT);
        });
    }

    /**
     * 发送对话消息并返回原始响应体: 广播由服务端落库挂点 fire-and-forget 触发, 客户端只负责点燃链路
     * (ClinicalPipelineTest 同款).
     */
    private static String chatSend(String token, String content)
    {
        return Objects.requireNonNull(
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

    //* 该用户最新会话 ID: 独立事务新开 session 查询 (ChatPipelineTest.latestSession 先例).
    private UUID latestSessionId(UUID userId)
    {
        final var session = sessionFactory.withTransaction((s, tx) ->
            s.createQuery("from AiChatSession x where x.userId = ?1 order by x.updatedAt desc", AiChatSession.class).
                setParameter(1, userId).
                setMaxResults(1).
                getSingleResultOrNull()
        ).await().atMost(AWAIT);
        return session == null ? null : session.id;
    }

    private static JsonNode readTree(String json, String what)
    {
        try { return MAPPER.readTree(json); }
        catch(Exception e) { throw new AssertionError(PrintUtils.quickFormat("{} 不是合法 JSON: {}", what, json), e); }
    }

    //* 真库前置: dev 库可能尚未应用 05 号迁移, 幂等确保 clinical_assessments 表与索引存在
    //* (ClinicalResourceTest 同款, 脚本单一来源: classpath 直读权威 DDL).
    private void ensureClinicalSchema()
    {
        final var ddls = schemaStatements("/db/schema/05_clinical_assessments.sql");
        sessionFactory.withSession(session ->
        {
            Uni<Void> chain = Uni.createFrom().voidItem();
            for(final var ddl: ddls)
                chain = chain.chain(v -> session.createNativeQuery(ddl).executeUpdate().replaceWithVoid());
            return chain;
        }).await().atMost(AWAIT);
    }

    /**
     * 读取 classpath 下的建表脚本并拆为语句列表: 剥离 {@code --} 注释行, 按分号切分
     * (ClinicalResourceTest 同款).
     */
    private static List<String> schemaStatements(String resource)
    {
        try(var stream = Objects.requireNonNull(
                ClinicalFeedWebSocketBehaviorTest.class.getResourceAsStream(resource),
                PrintUtils.quickFormat("classpath 资源缺失: {}", resource));
            var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)))
        {
            final var sql = reader.lines().
                filter(line -> !line.strip().startsWith("--")).
                reduce("", (left, right) -> left + "\n" + right);
            final var statements = new ArrayList<String>();
            for(final var part: sql.split(";"))
            {
                if(!part.isBlank())
                    statements.add(part.strip());
            }
            return statements;
        }
        catch(IOException e) { throw new IllegalStateException(PrintUtils.quickFormat("schema 脚本读取失败: {}", resource), e); }
    }
    //endregion
}
