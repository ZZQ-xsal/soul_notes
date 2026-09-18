package kurvcygnus.soulnotes.domain.auth.dto;

import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 注册/登录成功后的认证响应体, 携带后续请求所需的全部身份信息.
 *
 * @param token    签发的 JWT, 客户端以 {@code Authorization: Bearer <token>} 携带
 * @param userId   用户 ID (与 JWT subject 同值)
 * @param username 用户名
 * @param role     用户角色 (当前恒为 STUDENT, 见注册端角色提权防护)
 * @since 1.0
 */
public record AuthResponse(
    @NotNull String   token,
    @NotNull UUID     userId,
    @NotNull String   username,
    @NotNull UserRole role
) {}