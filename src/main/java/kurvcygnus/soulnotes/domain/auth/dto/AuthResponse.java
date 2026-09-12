package kurvcygnus.soulnotes.domain.auth.dto;

import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * <b>登录/注册成功响应</b>
 *
 * @param token    JWT Token
 * @param userId   用户 ID
 * @param username 用户名
 * @param role     用户角色
 * @since 1.0
 */
public record AuthResponse(
    @NotNull String   token,
    @NotNull UUID     userId,
    @NotNull String   username,
    @NotNull UserRole role
) {}