package kurvcygnus.soulnotes.domain.auth.dto;

import org.jetbrains.annotations.NotNull;

/**
 * <b>登录请求体</b>
 *
 * @param username 用户名
 * @param password 密码
 * @since 1.0
 */
public record LoginRequest(
    @NotNull String username,
    @NotNull String password
) {}