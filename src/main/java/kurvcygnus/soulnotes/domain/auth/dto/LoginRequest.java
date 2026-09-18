package kurvcygnus.soulnotes.domain.auth.dto;

import org.jetbrains.annotations.NotNull;

/**
 * 登录请求体.
 *
 * @param username 用户名
 * @param password 明文密码 (仅用于传输与校验, 服务端只存哈希)
 * @since 1.0
 */
public record LoginRequest(
    @NotNull String username,
    @NotNull String password
) {}