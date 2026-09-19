package kurvcygnus.soulnotes.domain.auth.resource;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import kurvcygnus.soulnotes.domain.auth.dto.AuthResponse;
import kurvcygnus.soulnotes.domain.auth.dto.LoginRequest;
import kurvcygnus.soulnotes.domain.auth.dto.RegisterRequest;
import kurvcygnus.soulnotes.domain.auth.service.AuthService;
import kurvcygnus.soulnotes.dto.ApiResponse;
import kurvcygnus.soulnotes.exception.ErrorCode;
import kurvcygnus.soulnotes.exception.IBusinessException;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import kurvcygnus.soulnotes.utils.constants.JwtConstants;
import org.jetbrains.annotations.NotNull;

/**
 * 认证 REST 资源, 提供注册/登录/登出三个端点.
 * <ul>
 *     <li>{@code POST /api/v1/auth/register} — 注册</li>
 *     <li>{@code POST /api/v1/auth/login} — 登录</li>
 *     <li>{@code POST /api/v1/auth/logout} — 登出 (需 Bearer Token)</li>
 * </ul>
 *
 * @implNote register 与 login 标注 {@code @PermitAll}; 其余端点走全局 JWT 认证机制,
 *           请求经 {@code Authorization: Bearer} 头鉴权, 未认证时由框架返回 401.
 * @since 1.0
 */
@Path(ApiEndpointConstants.AUTH_BASE)
public final class AuthResource
{
    @Inject AuthService authService;

    /**
     * 用户注册, 成功后直接返回认证信息 (无需再登录).
     *
     * @param req 注册请求 (用户名/密码/角色)
     * @return 认证响应 (含 JWT)
     * @throws IBusinessException 角色非 STUDENT、用户名已被占用或密码强度不足时 (BAD_REQUEST / USERNAME_DUPLICATE)
     */
    @POST @Path("/register") @PermitAll
    public @NotNull Uni<ApiResponse<AuthResponse>> register(@NotNull RegisterRequest req) { return authService.register(req).map(ApiResponse::success); }

    /**
     * 用户登录, 校验通过后签发新 JWT.
     *
     * @param req 登录请求 (用户名/密码)
     * @return 认证响应 (含 JWT)
     * @throws IBusinessException 用户不存在或密码错误时 (USER_NOT_FOUND / AUTH_UNAUTHORIZED)
     */
    @POST @Path("/login") @PermitAll
    public @NotNull Uni<ApiResponse<AuthResponse>> login(@NotNull LoginRequest req) { return authService.login(req).map(ApiResponse::success); }

    /**
     * 用户登出, 将当前 Token 加入 Redis 黑名单使其立即失效 (直至原过期时间).
     *
     * @param authorization {@code Authorization} 请求头 (Bearer Token)
     * @return 空载荷的成功响应
     * @throws IBusinessException Authorization 头缺失或 Token 格式非法时 (AUTH_TOKEN_INVALID), 请求以失败 Uni 直接结束
     */
    @POST @Path("/logout")
    public @NotNull Uni<ApiResponse<Void>> logout(@HeaderParam("Authorization") @NotNull String authorization)
    {
        if(authorization.isBlank())
            return Uni.createFrom().failure(
                IBusinessException.of(
                    ErrorCode.AUTH_TOKEN_INVALID,
                    "Authorization 头为空",
                    IllegalStateException::new,
                    "AUTH_LOGOUT_MISSING_HEADER"
                ).asException()
            );
        //! 去除 "Bearer " 前缀以提取裸 Token; 若前缀不存在则直接使用原值.
        final var token = authorization.startsWith(JwtConstants.TOKEN_PREFIX) ?
            authorization.substring(JwtConstants.TOKEN_PREFIX_LENGTH) :
            authorization;
        if(token.isBlank())
            return Uni.createFrom().failure(
                IBusinessException.of(
                    ErrorCode.AUTH_TOKEN_INVALID,
                    "Token 格式错误",
                    IllegalStateException::new,
                    "AUTH_LOGOUT_MALFORMED_TOKEN"
                ).asException()
            );
        return authService.logout(token).map(v -> ApiResponse.success());
    }
}