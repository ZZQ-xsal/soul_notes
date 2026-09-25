package kurvcygnus.soulnotes.domain.crisis;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import kurvcygnus.soulnotes.support.InfraProbes;
import kurvcygnus.soulnotes.support.MockLlmProfile;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;

/**
 * <b>GET {@code /api/v1/crisis/hotline} 离线兜底端点契约测试</b> (真库装配).
 * <p>复用 {@link MockLlmProfile} 既有装配 (ClinicalResourceTest 同款); 危机端点 @PermitAll
 * 且不触碰 AI/DB, 仅需 Redis (缺席时 RedisStartupConfig 自带配置兜底, 端点仍恒 200).</p>
 * @since 1.4.0
 */
@SuppressWarnings("NullableProblems")//! 测试模块不使用 JetBrains Annotations (项目测试惯例).
@QuarkusTest
@TestProfile(MockLlmProfile.class)
@EnabledIf(value = "pipelineInfraReachable", disabledReason = "本机 postgres/redis 未运行, 跳过危机端点契约真库用例")
class CrisisEndpointContractTest
{
    //* @EnabledIf 的引用方法必须落在被注解类内 (ChatPipelineTest 实测先例).
    static boolean pipelineInfraReachable() { return InfraProbes.pipelineInfraReachable(); }

    //* 回归 (预约入口, 1.4.0): 离线兜底端点必须下发 appointmentUrl 字段 — 未配置时空串, 前端判空隐藏.
    @Test
    void hotlinePayload_ShouldCarryAppointmentUrlField()
    {
        given().
        when().
            get(ApiEndpointConstants.CRISIS_BASE + "/hotline").
        then().
            statusCode(200).
            body("code", equalTo(0)).
            body("data", hasKey("appointmentUrl")).
            body("data.primary", equalTo("400-161-9995"));
    }
}
