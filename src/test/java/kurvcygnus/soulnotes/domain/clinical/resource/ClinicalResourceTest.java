package kurvcygnus.soulnotes.domain.clinical.resource;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.domain.auth.entity.User;
import kurvcygnus.soulnotes.domain.auth.service.TokenService;
import kurvcygnus.soulnotes.domain.clinical.entity.ClinicalAssessment;
import kurvcygnus.soulnotes.support.InfraProbes;
import kurvcygnus.soulnotes.support.MockLlmProfile;
import kurvcygnus.soulnotes.utils.JsonUtils;
import kurvcygnus.soulnotes.utils.PrintUtils;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * <b>ClinicalResource 契约测试</b> (真库): 权限矩阵 + 队列/时间线/统计形态.
 * <p>复用 {@link MockLlmProfile} 既有真库装配 (项目内唯一 @QuarkusTest 真库 Profile):
 * 纯 REST 契约用例不发起 LLM 调用, mock 服务闲置无碍; 基建守卫与全链路用例同源.</p>
 * @since 1.2.0
 */
@SuppressWarnings("NullableProblems")//! 测试模块不使用 JetBrains Annotations (项目测试惯例).
@QuarkusTest
@TestProfile(MockLlmProfile.class)
@EnabledIf(value = "pipelineInfraReachable", disabledReason = "本机 postgres/redis 未运行, 跳过工作台契约真库用例")
class ClinicalResourceTest
{
    //* @EnabledIf 的引用方法必须落在被注解类内: QuarkusTest 类加载器下跨类全限定字符串解析失败 (ChatPipelineTest 实测先例).
    static boolean pipelineInfraReachable() { return InfraProbes.pipelineInfraReachable(); }

    private static final Duration AWAIT = Duration.ofSeconds(20);

    private static final String RED_PAYLOAD =
        "{\"riskLevel\":\"RED\",\"tags\":[\"crisis\"],\"summary\":\"高危倾向\"}";

    //* 类级随机后缀 (UUID 前 8 位): 同一 JVM 运行内共享账号, 跨次运行不撞 users.user_name UNIQUE 约束.
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);

    @Inject TokenService tokenService;
    @Inject Mutiny.SessionFactory sessionFactory;

    //* 测试账号与评估数据准备 (真库, 每用例按需幂等创建).
    //> token 生成复用 TokenService (User.create + persist), 与 ChatPipelineTest 账号准备模式一致;
    //> JUnit 默认每用例新建实例, 账号/token 以实例 Map 惰性复用, 无需跨用例缓存字段.

    private final Map<String, User> users = new HashMap<>();

    //* 真库前置: dev 库可能尚未应用 05 号迁移, 幂等确保 clinical_assessments 表与索引存在;
    //* 脚本单一来源 (classpath 直读权威 DDL, IF NOT EXISTS 可重复执行), @BeforeEach 实例方法
    //* 而非 static @BeforeAll — 注入字段仅实例方法可见 (QuarkusTest 生命周期约束).
    @BeforeEach void ensureClinicalSchema()
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

    @Test void permissions_StudentForbidden_CounselorOk()
    {
        given().header("Authorization", "Bearer " + counselorToken()).
            queryParam("level", "RED").
        when().get("/api/v1/clinical/assessments").
        then().statusCode(200).body("code", equalTo(0));

        given().header("Authorization", "Bearer " + studentToken()).
        when().get("/api/v1/clinical/assessments").
        then().statusCode(403);
    }

    @Test void queue_RecordsRedEntryAndRevealsIdentity()
    {
        final var student = recordAssessment(RED_PAYLOAD);
        given().header("Authorization", "Bearer " + counselorToken()).
            queryParam("level", "RED").queryParam("days", 7).
        when().get("/api/v1/clinical/assessments").
        then().statusCode(200).
            body("data.findAll { it.riskLevel == 'RED' }", hasSize(greaterThanOrEqualTo(1))).
            body("data[0].summary", equalTo("高危倾向")).
            body("data[0].tags", notNullValue()).
            //* 默认实名解锁等级 RED: 队列 RED 条目应揭示真实 UUID 与用户名 (RevealPolicy 契约).
            body("data[0].userId", equalTo(student.id.toString())).
            body("data[0].displayName", equalTo(student.username));
    }

    //* 回归 (审查修复轮 1): 缺席 page/size 须经 normalize 收敛为契约默认 (每页 20) 而非钳位 1 —
    //* 造 2 条同等级数据断言不被 LIMIT 1 截断.
    @Test void queue_AbsentPagingParams_ShouldApplyContractDefault()
    {
        recordAssessment(RED_PAYLOAD);
        recordAssessment(RED_PAYLOAD);
        given().header("Authorization", "Bearer " + counselorToken()).
            queryParam("level", "RED").queryParam("days", 7).
        when().get("/api/v1/clinical/assessments").
        then().statusCode(200).
            body("data.size()", greaterThanOrEqualTo(2));
    }

    @Test void timeline_ListsStudentEntriesNewestFirst()
    {
        final var student = recordAssessment(RED_PAYLOAD);
        given().header("Authorization", "Bearer " + counselorToken()).
            queryParam("page", 1).queryParam("size", 10).
        when().get("/api/v1/clinical/students/" + student.id + "/assessments").
        then().statusCode(200).
            body("code", equalTo(0)).
            body("data.size()", greaterThanOrEqualTo(1)).
            body("data[0].summary", equalTo("高危倾向"));
    }

    @Test void timeline_IllegalStudentId_BadRequest()
    {
        given().header("Authorization", "Bearer " + counselorToken()).
        when().get("/api/v1/clinical/students/not-a-uuid/assessments").
        then().statusCode(400);
    }

    @Test void level_OutsideWhitelist_BadRequest()
    {
        given().header("Authorization", "Bearer " + counselorToken()).
            queryParam("level", "BLUE").
        when().get("/api/v1/clinical/assessments").
        then().statusCode(400).
            body("code", equalTo(400000));
    }

    @Test void stats_SummaryShape()
    {
        given().header("Authorization", "Bearer " + counselorToken()).
            queryParam("days", 7).
        when().get("/api/v1/clinical/stats/summary").
        then().statusCode(200).
            body("data.byLevel", notNullValue()).
            body("data.byDay", notNullValue());
    }

    //region 测试脚手架
    private String counselorToken() { return tokenFor("counselor", UserRole.COUNSELOR); }
    private String studentToken() { return tokenFor("student", UserRole.STUDENT); }

    /**
     * 取角色账号并签发 JWT: 真库直插账号 (幂等: 按用户名查重), 认证不走密码路径 (走 JWT), passwordHash 任意非空串即可.
     */
    private String tokenFor(String rolePrefix, UserRole role)
    {
        return tokenService.generateToken(ensureUser(rolePrefix, role));
    }

    /**
     * 确保角色账号存在并返回实体: 独立事务按用户名查重, 缺席则以 {@code User.create} 工厂落库.
     */
    private User ensureUser(String rolePrefix, UserRole role)
    {
        return users.computeIfAbsent(rolePrefix, k ->
        {
            final var username = PrintUtils.quickFormat("clinical-it-{}-{}", rolePrefix, SUFFIX);
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
     * 为指定学生真库直插一条评估行 (字段映射与 {@code recordAsync} 逐一对应), 返回被评估学生.
     * <p>//! 不经 {@code recordAsync} 造数: 其内部 {@code Panache.withTransaction} 要求
     * 被标记安全的 Vert.x 请求上下文, 仅存在于事件循环/请求线程, JUnit 线程直调必抛
     * "No current Vertx context found"; 补救须经 Quarkus 内部运行时 API 伪造安全上下文, 得不偿失 —
     * 服务落库路径已由 {@code ClinicalAssessmentServiceTest} (NONE 跳过/单测) 与全链路用例
     * (真实事件循环) 覆盖, 本契约测试仅需已提交的行验证查询/脱敏/HTTP 形态.</p>
     */
    private User recordAssessment(String payload)
    {
        final var student = ensureUser("assessed", UserRole.STUDENT);
        final var payloadNode = JsonUtils.parseJson(payload, JsonNode.class);
        final var assessment = new ClinicalAssessment();
        assessment.id = UUID.randomUUID();
        assessment.userId = student.id;
        assessment.sessionId = UUID.randomUUID();
        assessment.riskLevel = payloadNode.path("riskLevel").asText();
        assessment.tags = payload;
        assessment.summary = payloadNode.path("summary").asText("");
        assessment.schemaHash = null;
        assessment.createdAt = Instant.now();
        sessionFactory.withTransaction((session, tx) -> assessment.persist()).await().atMost(AWAIT);
        return student;
    }

    /**
     * 读取 classpath 下的建表脚本并拆为语句列表: 剥离 {@code --} 注释行, 按分号切分.
     */
    private static List<String> schemaStatements(String resource)
    {
        try(var stream = Objects.requireNonNull(
                ClinicalResourceTest.class.getResourceAsStream(resource),
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
