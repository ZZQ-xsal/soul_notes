package kurvcygnus.soulnotes.domain.auth.dto;

import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.jetbrains.annotations.NotNull;

/**
 * 注册请求体.
 *
 * @param username 用户名 (全局唯一, 重复时注册失败)
 * @param password 明文密码, 服务端校验强度 (至少 8 位且同时含字母、数字、特殊字符) 后仅存 PBKDF2 哈希
 * @param role     期望用户角色, 服务端仅接受 STUDENT, 其余值注册被拒绝 (角色提权防护)
 * @since 1.0
 */
public record RegisterRequest(
    @NotNull String  username,
    @NotNull String  password,
    @NotNull UserRole role
) {}