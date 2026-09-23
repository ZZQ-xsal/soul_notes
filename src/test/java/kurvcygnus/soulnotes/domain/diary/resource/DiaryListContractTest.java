package kurvcygnus.soulnotes.domain.diary.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.domain.diary.entity.MoodDiary;
import kurvcygnus.soulnotes.support.InfraProbes;
import kurvcygnus.soulnotes.support.MockLlmProfile;
import kurvcygnus.soulnotes.support.PipelineUsers;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * <b>GET {@code /api/v1/diaries} 缺席分页参数契约测试</b> (真库).
 * <p>复用 {@link MockLlmProfile} 既有真库装配 (项目内唯一 @QuarkusTest 真库 Profile),
 * 与 {@code ClinicalResourceTest#queue_AbsentPagingParams_ShouldApplyContractDefault} 同款语义.</p>
 * @since 1.2.0
 */
@SuppressWarnings("NullableProblems")//! 测试模块不使用 JetBrains Annotations (项目测试惯例).
@QuarkusTest
@TestProfile(MockLlmProfile.class)
@EnabledIf(value = "pipelineInfraReachable", disabledReason = "本机 postgres/redis 未运行, 跳过日记列表契约真库用例")
class DiaryListContractTest
{
    //* @EnabledIf 的引用方法必须落在被注解类内: QuarkusTest 类加载器下跨类全限定字符串解析失败 (ChatPipelineTest 实测先例).
    static boolean pipelineInfraReachable() { return InfraProbes.pipelineInfraReachable(); }

    private static final Duration AWAIT = Duration.ofSeconds(20);

    @Inject Mutiny.SessionFactory sessionFactory;

    //* 回归 (终审必修 1): 缺席 page/size 时 @BeanParam 不应用 @DefaultValue 也不调 setter (字段直落 int 默认 0),
    //* 消费点未 normalize 即 page(-1, 0) → Panache "size must be > 0" → 500.
    //* 造 2 条日记断言 200 且不被截断/异常 (ClinicalResourceTest 同名先例).
    @Test void list_AbsentPagingParams_ShouldApplyContractDefault()
    {
        final var account = PipelineUsers.register();
        seedDiary(account.userId());
        seedDiary(account.userId());

        given().header("Authorization", PipelineUsers.bearer(account.token())).
        when().get(ApiEndpointConstants.DIARY_BASE).
        then().statusCode(200).
            body("code", equalTo(0)).
            body("data.size()", greaterThanOrEqualTo(2));
    }

    //* 回归 (终审必修 1, 实测 RED): 在场参数直接写入字段 (setter 钳位从不执行), page=0&size=0
    //* 即 page(-1, 0) → Panache "Page index must be >= 0" → 500; normalize 契约与 PageRequest 同语义:
    //* 0 视为未传参收敛为文档默认 (第 1 页/每页 20), 故 2 条日记应整页返回.
    @Test void list_ZeroValuedPagingParams_ShouldNormalizeToContractDefault()
    {
        final var account = PipelineUsers.register();
        seedDiary(account.userId());
        seedDiary(account.userId());

        given().header("Authorization", PipelineUsers.bearer(account.token())).
            queryParam("page", 0).queryParam("size", 0).
        when().get(ApiEndpointConstants.DIARY_BASE).
        then().statusCode(200).
            body("code", equalTo(0)).
            body("data.size()", greaterThanOrEqualTo(2));
    }

    //* 回归 (终审必修 1, 实测 RED): 负值同样穿透 setter → page(-6, -6) → Panache IAE → 500;
    //* normalize 语义: 越界值经 setter 钳位 (page >= 1, size >= 1) → page(0, 1) 恰返回第 1 页 1 条.
    @Test void list_NegativePagingParams_ShouldClampToFirstPageSingleItem()
    {
        final var account = PipelineUsers.register();
        seedDiary(account.userId());
        seedDiary(account.userId());

        given().header("Authorization", PipelineUsers.bearer(account.token())).
            queryParam("page", -5).queryParam("size", -5).
        when().get(ApiEndpointConstants.DIARY_BASE).
        then().statusCode(200).
            body("code", equalTo(0)).
            body("data.size()", equalTo(1));
    }

    //* 回归 (前端对接反馈实锤, 实测 RED): DiaryListQuery 的 startDate/endDate 自 1.0 起声明却从未被
    //! Service 消费 (listByUser 只取分页) — 前端传日期返回全量, "日期筛选用不了".
    //* 语义: 双参过滤 (区间含端点, end 按上海时区取次日零点前) → 30 天前的旧日记必须被滤掉.
    @Test void list_DateRangeFilter_ShouldConsumeStartAndEnd()
    {
        final var account = PipelineUsers.register();
        seedDiaryAt(account.userId(), Instant.now().minus(Duration.ofDays(30)));
        seedDiaryAt(account.userId(), Instant.now());

        given().header("Authorization", PipelineUsers.bearer(account.token())).
            queryParam("startDate", java.time.LocalDate.now().minusDays(1).toString()).
            queryParam("endDate", java.time.LocalDate.now().plusDays(1).toString()).
        when().get(ApiEndpointConstants.DIARY_BASE).
        then().statusCode(200).
            body("code", equalTo(0)).
            body("data.size()", equalTo(1));
    }

    //* 单边过滤: 只传 startDate 时从该日 (含) 起返回 — 可选参数的不对称形态同样必须生效.
    @Test void list_StartDateOnly_ShouldFilterFrom()
    {
        final var account = PipelineUsers.register();
        seedDiaryAt(account.userId(), Instant.now().minus(Duration.ofDays(30)));
        seedDiaryAt(account.userId(), Instant.now());

        given().header("Authorization", PipelineUsers.bearer(account.token())).
            queryParam("startDate", java.time.LocalDate.now().minusDays(1).toString()).
        when().get(ApiEndpointConstants.DIARY_BASE).
        then().statusCode(200).
            body("code", equalTo(0)).
            body("data.size()", equalTo(1));
    }

    //* 非法日期格式统一负载 (与 /diaries/weather 的 parseDateRange 同语义): 修前参数被忽略返回 200 全量.
    @Test void list_InvalidDateFormat_ShouldReturnUnifiedError()
    {
        final var account = PipelineUsers.register();
        seedDiary(account.userId());

        given().header("Authorization", PipelineUsers.bearer(account.token())).
            queryParam("startDate", "2026/09/01").
        when().get(ApiEndpointConstants.DIARY_BASE).
        then().statusCode(400).
            body("code", equalTo(400000));
    }

    /**
     * 为该用户真库直插一条日记: 不经 {@code POST /diaries} 的创建链路 (其异步 AI 分析与本题无关),
     * 与 {@code ClinicalResourceTest#recordAssessment} 同款只造行不走路由的取舍.
     */
    private void seedDiary(String userId)
    {
        seedDiaryAt(userId, Instant.now());
    }

    //* 指定 createdAt 的造数变体: 日期过滤用例需要跨区间的时间分布 (seedDiary 的恒 now 无法构造过滤判别力).
    private void seedDiaryAt(String userId, Instant createdAt)
    {
        final var diary = new MoodDiary();
        diary.userId    = UUID.fromString(userId);
        diary.content   = "契约测试日记 " + UUID.randomUUID();
        diary.createdAt = createdAt;
        sessionFactory.withTransaction((session, tx) -> diary.persist()).await().atMost(AWAIT);
    }
}
