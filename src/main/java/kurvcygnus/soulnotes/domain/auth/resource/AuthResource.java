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
 * <b>认证 REST 资源</b>
 * <ul>
 *     <li>{@code POST /api/v1/auth/register} — 注册</li>
 *     <li>{@code POST /api/v1/auth/login} — 登录</li>
 *     <li>{@code POST /api/v1/auth/logout} — 登出 (需 Bearer Token)</li>
 * </ul>
 * @since 1.0
 */
@Path(ApiEndpointConstants.AUTH_BASE)
public final class AuthResource
{
    @Inject AuthService authService;

    /**
     * <span style="color: 95cc6d">用户注册.</span>
     */
    @POST @Path("/register") @PermitAll
    public @NotNull Uni<ApiResponse<AuthResponse>> register(@NotNull RegisterRequest req) { return authService.register(req).map(ApiResponse::success); }

    /**
     * <span style="color: 95cc6d">用户登录.</span>
     */
    @POST @Path("/login") @PermitAll
    public @NotNull Uni<ApiResponse<AuthResponse>> login(@NotNull LoginRequest req) { return authService.login(req).map(ApiResponse::success); }

    /**
     * <span style="color: f84b4b">用户登出, 将 Token 加入黑名单.</span>
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