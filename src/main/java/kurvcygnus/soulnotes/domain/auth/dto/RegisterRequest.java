package kurvcygnus.soulnotes.domain.auth.dto;

import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.jetbrains.annotations.NotNull;

/**
 * <b>注册请求体</b>
 *
 * @param username 用户名
 * @param password 密码
 * @param role     用户角色
 * @since 1.0
 */
public record RegisterRequest(
    @NotNull String  username,
    @NotNull String  password,
    @NotNull UserRole role
) {}