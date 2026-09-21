package kurvcygnus.soulnotes.domain.clinical.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.domain.auth.entity.User;
import kurvcygnus.soulnotes.domain.auth.service.TokenService;
import kurvcygnus.soulnotes.domain.chat.entity.AiChatSession;
import kurvcygnus.soulnotes.domain.clinical.entity.ClinicalAssessment;
import kurvcygnus.soulnotes.support.InfraProbes;
import kurvcygnus.soulnotes.support.MockLlmProfile;
import kurvcygnus.soulnotes.support.MockLlmServer;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>副医生落库全链路集成测试</b> (Mock-LLM 驱动, 真库).
 * <p>真实应用栈 (REST + Hibernate Reactive + LangChain4j OpenAI 客户端) 对接进程内
 * {@link MockLlmServer#respondWithClinical(String, String)} 布防的契约块回复, 覆盖 ChatService
 * {@code /chat/send} 挂点触达真实 DB 的验证缺口 (此前 ChatPipelineTest 契约块用例均为 NONE 载荷):</p>
 * <ul>
 *     <li>① RED 载荷全链路: 对话 200 且正文剥离 → {@code clinical_assessments} 真库落行
 *     (JSONB 真 JSON, canonical 免归一 → schema_hash 为 NULL) → 咨询员队列 API 可查 → RED 实名解锁</li>
 *     <li>② YELLOW 载荷变体: 落库 YELLOW → 默认 REVEAL_LEVEL=RED 下咨询员视角为 "学生 #短码" 掩码</li>
 * </ul>
 * <p>账号准备与真库装配对齐 {@code ClinicalResourceTest} 既有模式 (@TestProfile + @EnabledIf +
 * SessionFactory 造数); 轮询等待落库与 ChatPipelineTest 并发持久化先例一致 (落库是 fire-and-forget).</p>
 * @since 1.2.0
 */
@SuppressWarnings("NullableProblems")//! 测试模块不使用 JetBrains Annotations (项目测试惯例).
@QuarkusTest
@TestProfile(MockLlmProfile.class)
@EnabledIf(value = "pipelineInfraReachable", disabledReason = "本机 postgres/redis 未运行, 跳过副医生落库全链路用例")
class ClinicalPipelineTest
{
    //* @EnabledIf 的引用方法必须落在被注解类内: QuarkusTest 类加载器下跨类全限定字符串解析失败 (ChatPipelineTest 实测先例).
    static boolean pipelineInfraReachable() { return InfraProbes.pipelineInfraReachable(); }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration AWAIT = Duration.ofSeconds(20);

    //* 轮询参数 (200ms 间隔 / ~5s 上限): 落库挂点是 fire-and-forget, HTTP 200 先于 INSERT 返回,
   //* 测试侧只能轮询等待; 上限收紧到 5s — 挂点在事件循环链尾正常亚秒完成, 超时即链路断裂而非慢.
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration STORE_TIMEOUT = Duration.ofSeconds(5);

    private static final String RED_CLINICAL_JSON =
        "{\"riskLevel\":\"RED\",\"tags\":[\"crisis\",\"self-harm\"],\"summary\":\"高危信号, 建议立即介入\"}";
    private static final String YELLOW_CLINICAL_JSON =
        "{\"riskLevel\":\"YELLOW\",\"tags\":[\"insomnia\"],\"summary\":\"持续失眠, 建议关注\"}";

    //* 类级随机后缀 (UUID 前 8 位): 同一 JVM 运行内共享账号, 跨次运行不撞 users.user_name UNIQUE 约束.
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

    @Inject TokenService tokenService;
    @Inject Mutiny.SessionFactory sessionFactory;

    //* 测试账号准备 (真库, 每用例按需幂等创建).
    //> 与 ClinicalResourceTest 同款: 独立事务按用户名查重, 缺席则以 User.create 工厂落库后
    //> TokenService.generateToken 签发 JWT — 认证不走密码路径, passwordHash 任意非空串即可.

    private final Map<String, User> users = new HashMap<>();

    @BeforeEach void prepare()
    {
        ensureClinicalSchema();
        //* 用例隔离: 清空 mock 录制与编程状态, 防止跨用例的请求累积干扰断言 (ChatPipelineTest rearm 先例).
        MockLlmProfile.server().reset();
    }

    //region ① RED 载荷全链路
    @Test
    void chatSend_RedClinicalBlock_ShouldStoreRealJsonRevealIdentityAndSkipSchemaHash() throws InterruptedException
    {
        final var student = ensureUser("red-student", UserRole.STUDENT);
        MockLlmProfile.server().respondWithClinical("我在这里, 你不是一个人。", RED_CLINICAL_JSON);

        final var body = chatSend(tokenService.generateToken(student), "我觉得活着没意思");

        //* 对话 200 + 正文剥离: 前端响应全文不得携带 soulnotes 字样, 且仅见剥离后正文.
        final var root = readTree(body, "chat/send 响应");
        assertEquals(0, root.path("code").asInt(), PrintUtils.quickFormat("业务码应为 0: {}", body));
        assertFalse(body.contains("soulnotes"), PrintUtils.quickFormat("前端响应不得携带契约块字样: {}", body));
        assertEquals("我在这里, 你不是一个人。", root.path("data").path("content").asText(), "前端应仅见剥离后正文");

        //* 真库落行断言 (JSONB 真 JSON 形态, jsonb_typeof 先例同款语义).
        final var sessionId = latestSessionId(student.id);
        assertNotNull(sessionId, "会话应已落库");
        final var stored = awaitStoredAssessment(sessionId);
        assertNotNull(stored, "RED 评估行应在轮询窗口内落库 (挂点 fire-and-forget, 超时即链路断裂)");
        assertEquals("RED", stored.riskLevel(), "RED 载荷必须落库 riskLevel = RED");
        assertEquals("高危信号, 建议立即介入", stored.summary(), "canonical summary 应随落库冗余抽取");
        assertEquals("object", tagsJsonbType(stored.id()),
            "tags 必须落为真 JSON 对象而非双重编码字符串标量, 实际 jsonb_typeof = " + tagsJsonbType(stored.id()));
        assertEquals("RED", readTree(stored.tagsJson(), "tags JSONB").path("riskLevel").asText(), "载荷原文应保真落库");
        //* currentSchemaHash 指纹语义: 本 profile 下 clinicalSchema 为 canonical (免归一) → 指纹恒为 null.
        assertNull(stored.schemaHash(), "canonical 结构免归一, 落库行 schema_hash 必须为 NULL 而非空串或指纹值");

        //* 咨询员队列可查 + RED 实名解锁 (RevealPolicy 契约: RED 记录返回完整 UUID 与 username).
        final var queue = readTree(counselorQueue(counselorToken(), "RED"), "风险队列响应");
        final var entry = findBySessionId(queue, sessionId);
        assertNotNull(entry, PrintUtils.quickFormat("咨询员 RED 队列应可查到该条评估: {}", queue));
        assertEquals(student.id.toString(), entry.path("userId").asText(), "RED 记录应解锁完整学生 UUID 而非短码");
        assertEquals(student.username, entry.path("displayName").asText(), "RED 记录实名解锁应显示 username 而非 学生# 短码");
    }
    //endregion

    //region ② YELLOW 载荷变体
    @Test
    void chatSend_YellowClinicalBlock_ShouldStoreAndMaskIdentityForCounselor() throws InterruptedException
    {
        final var student = ensureUser("yellow-student", UserRole.STUDENT);
        MockLlmProfile.server().respondWithClinical("慢慢来, 我陪着你。", YELLOW_CLINICAL_JSON);

        final var body = chatSend(tokenService.generateToken(student), "最近总是睡不好");

        assertEquals(0, readTree(body, "chat/send 响应").path("code").asInt(), "YELLOW 载荷不得中断主对话链路");
        assertFalse(body.contains("soulnotes"), PrintUtils.quickFormat("前端响应不得携带契约块字样: {}", body));

        final var sessionId = latestSessionId(student.id);
        assertNotNull(sessionId, "会话应已落库");
        final var stored = awaitStoredAssessment(sessionId);
        assertNotNull(stored, "YELLOW 评估行应在轮询窗口内落库 (挂点 fire-and-forget, 超时即链路断裂)");
        assertEquals("YELLOW", stored.riskLevel(), "YELLOW 载荷必须原样落库不降级");
        assertEquals("持续失眠, 建议关注", stored.summary(), "canonical summary 应随落库冗余抽取");

        //* 默认 REVEAL_LEVEL=RED (clinical.reveal-level 缺省): YELLOW 不解锁, 咨询员视角为掩码形态.
        final var queue = readTree(counselorQueue(counselorToken(), "YELLOW"), "风险队列响应");
        final var entry = findBySessionId(queue, sessionId);
        assertNotNull(entry, PrintUtils.quickFormat("咨询员 YELLOW 队列应可查到该条评估: {}", queue));
        final var shortCode = student.id.toString().substring(0, 8);
        assertEquals(shortCode, entry.path("userId").asText(), "YELLOW 记录不得解锁完整学生 UUID (最小必要原则)");
        assertEquals("学生 #" + shortCode, entry.path("displayName").asText(), "YELLOW 记录咨询员视角应为 学生#短码 掩码形态");
    }
    //endregion

    //region 测试脚手架
    /**
     * 确保角色账号存在并返回实体: 独立事务按用户名查重, 缺席则以 {@code User.create} 工厂落库
     * (ClinicalResourceTest 同款模式).
     */
    private User ensureUser(String rolePrefix, UserRole role)
    {
        return users.computeIfAbsent(rolePrefix, k ->
        {
            final var username = PrintUtils.quickFormat("clinical-pipeline-{}-{}", rolePrefix, SUFFIX);
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

    private String counselorToken() { return tokenService.generateToken(ensureUser("counselor", UserRole.COUNSELOR)); }

    /**
     * 发送对话消息并返回原始响应体: 断言在调用侧展开, 保留全文供契约块字样断言.
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

    /**
     * 咨询员视角拉取风险队列并返回原始响应体.
     */
    private static String counselorQueue(String token, String level)
    {
        return given().
            header("Authorization", PipelineUsers.bearer(token)).
            queryParam("level", level).
            when().
            get(ApiEndpointConstants.CLINICAL_BASE + "/assessments").
            then().
            statusCode(200).
            extract().asString();
    }

    /**
     * 该用户最新会话 ID: 独立事务新开 session 查询, 读已提交数据不受一级缓存干扰
     * (ChatPipelineTest latestSession 先例).
     */
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

    /**
     * 轮询等待该会话的评估行落库: 挂点是 fire-and-forget (HTTP 200 先于 INSERT 返回),
     * 200ms 间隔 / ~5s 上限与既有异步持久化先例一致; 轮询等待禁用 await().indefinitely().
     */
    private StoredAssessment awaitStoredAssessment(UUID sessionId) throws InterruptedException
    {
        final var deadline = System.currentTimeMillis() + STORE_TIMEOUT.toMillis();
        var stored = readStoredAssessment(sessionId);
        while(stored == null && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(POLL_INTERVAL.toMillis());
            stored = readStoredAssessment(sessionId);
        }
        return stored;
    }

    /**
     * 按会话 ID 读取评估行 (缺席为 null): 独立事务经 Panache 实体查询取全字段, 免多次往返.
     */
    private StoredAssessment readStoredAssessment(UUID sessionId)
    {
        return sessionFactory.withTransaction((session, tx) ->
            session.createQuery("from ClinicalAssessment a where a.sessionId = ?1 order by a.createdAt desc", ClinicalAssessment.class).
                setParameter(1, sessionId).
                setMaxResults(1).
                getSingleResultOrNull()
        ).map(a -> a == null ? null : new StoredAssessment(a.id, a.riskLevel, a.tags, a.schemaHash, a.summary)).
            await().atMost(AWAIT);
    }

    /**
     * 原生查询直探 tags 列真实存储形态 (jsonb_typeof): 钉死新写入落为真 JSON 对象而非字符串标量
     * 双重编码 (ChatPipelineTest messagesJsonbType 先例).
     */
    private String tagsJsonbType(UUID assessmentId)
    {
        return sessionFactory.withTransaction((session, tx) ->
            session.createNativeQuery("select jsonb_typeof(tags) from clinical_assessments where id = ?1", String.class).
                setParameter(1, assessmentId).
                getSingleResultOrNull()
        ).await().atMost(AWAIT);
    }

    /**
     * 从队列响应中定位来源会话匹配的条目: sessionId 在 VO 中恒不脱敏, 是跨脱敏状态的确定性关联锚点.
     */
    private static JsonNode findBySessionId(JsonNode queueResponse, UUID sessionId)
    {
        for(final var entry: queueResponse.path("data"))
        {
            if(sessionId.toString().equals(entry.path("sessionId").asText()))
                return entry;
        }
        return null;
    }

    /**
     * 落库行的测试侧投影 (实体出事务即分离, 字段快照后断言).
     */
    private record StoredAssessment(UUID id, String riskLevel, String tagsJson, String schemaHash, String summary) {}

    private static JsonNode readTree(String json, String what)
    {
        try { return MAPPER.readTree(json); }
        catch(Exception e) { throw new AssertionError(PrintUtils.quickFormat("{} 不是合法 JSON: {}", what, json), e); }
    }

    //* 真库前置: dev 库可能尚未应用 05 号迁移, 幂等确保 clinical_assessments 表与索引存在
   //* (ClinicalResourceTest 同款, 脚本单一来源: classpath 直读权威 DDL, IF NOT EXISTS 可重复执行).
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
                ClinicalPipelineTest.class.getResourceAsStream(resource),
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
