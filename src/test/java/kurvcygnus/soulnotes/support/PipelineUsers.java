package kurvcygnus.soulnotes.support;

import kurvcygnus.soulnotes.utils.PrintUtils;

import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * <b>全链路测试注册脚手架</b>
 * <p>经 {@code /api/v1/auth/register} 注册随机用户并返回 JWT, 供各集成测试类复用.
 * 注册走真实 HTTP 面 (无事务回滚), 用户名含随机段避免开发库唯一约束冲突.</p>
 * @since 2.0
 */
public final class PipelineUsers
{
    /**
     * <b>注册账号</b>
     * @param token 签发的 JWT
     * @param userId 用户 ID (数据库主键, 供查库断言)
     */
    public record Account(String token, String userId) {}

    private PipelineUsers() { throw new IllegalAccessError("Class \"PipelineUsers\" is not meant to be instantized!"); }

    public static Account register()
    {
        final var username = PrintUtils.quickFormat("pipeline-{}", UUID.randomUUID().toString().substring(0, 8));
        final var body = PrintUtils.quickFormat("{\"username\":\"{}\",\"password\":\"pipeline-pass-123\",\"role\":\"STUDENT\"}", username);
        final var response = given().
            contentType("application/json").
            body(body).
            when().
            post("/api/v1/auth/register").
            then().
            statusCode(200).
            extract();
        return new Account(response.path("data.token"), response.path("data.userId"));
    }

    public static String bearer(String token) { return PrintUtils.quickFormat("Bearer {}", token); }
}
