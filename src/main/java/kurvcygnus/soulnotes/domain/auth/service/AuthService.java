package kurvcygnus.soulnotes.domain.auth.service;

import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.domain.auth.dto.AuthResponse;
import kurvcygnus.soulnotes.domain.auth.dto.LoginRequest;
import kurvcygnus.soulnotes.domain.auth.dto.RegisterRequest;
import kurvcygnus.soulnotes.domain.auth.entity.User;
import kurvcygnus.soulnotes.exception.ErrorCode;
import kurvcygnus.soulnotes.exception.IBusinessException;
import kurvcygnus.soulnotes.utils.PrintUtils;
import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.jetbrains.annotations.NotNull;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;
import java.util.NoSuchElementException;

/**
 * <b>认证服务</b>
 * <ul>
 *     <li>注册: 检查用户名唯一性 → 密码哈希 → 创建用户 → 签发 Token</li>
 *     <li>登录: 查找用户 → 密码校验 → 签发 Token</li>
 *     <li>登出: 将 Token 加入 Redis 黑名单</li>
 * </ul>
 * @since 1.0
 */
@ApplicationScoped
public final class AuthService
{
    //region 注入
    private final @NotNull TokenService tokenService;

    public AuthService(@NotNull TokenService tokenService) { this.tokenService = tokenService; }
    //endregion

    //region 核心业务
    /**
     * <span style="color: 95cc6d">用户注册.</span>
     *
     * @param req 注册请求
     * @return 认证成功响应 (含 Token)
     */
    @WithTransaction public @NotNull Uni<AuthResponse> register(@NotNull RegisterRequest req)
    {
        //* 角色提权防护: 注册仅允许 STUDENT, 忽略/拒绝客户端传入的管理员等角色.
        if(req.role() != UserRole.STUDENT)
            return Uni.createFrom().failure(
                IBusinessException.of(
                    ErrorCode.BAD_REQUEST,
                    "注册仅允许 STUDENT 角色",
                    IllegalArgumentException::new,
                    "AUTH_REGISTER_ROLE_NOT_ALLOWED"
                ).asException()
            );

        return User.findByUsername(req.username()).
            onItem().ifNotNull().failWith(
                () -> IBusinessException.of(
                    ErrorCode.USERNAME_DUPLICATE,
                    "用户名已被占用",
                    RuntimeException::new,
                    "AUTH_REGISTER_USERNAME_CONFLICT"
                ).asException()
            ).
            onItem().ifNull().switchTo(createUser(req)).
            flatMap(
                user ->
                {
                    final var token = tokenService.generateToken(user);
                    return Uni.createFrom().item(new AuthResponse(token, user.id, user.username, user.role));
                }
            );
    }

    /**
     * <span style="color: 95cc6d">用户登录.</span>
     *
     * @param req 登录请求
     * @return 认证成功响应 (含 Token)
     */
    @WithTransaction public @NotNull Uni<AuthResponse> login(@NotNull LoginRequest req)
    {
        return User.findByUsername(req.username()).
            onItem().ifNull().failWith(
                () -> IBusinessException.of(
                    ErrorCode.USER_NOT_FOUND,
                    "用户不存在",
                    NoSuchElementException::new,
                    "AUTH_LOGIN_USER_NOT_FOUND"
                ).asException()
            ).
            flatMap(
                user ->
                {
                    if(!verifyPassword(req.password(), user.passwordHash))
                        return Uni.createFrom().failure(
                            IBusinessException.of(
                                ErrorCode.AUTH_UNAUTHORIZED,
                                "密码错误",
                                IllegalStateException::new,
                                "AUTH_LOGIN_PASSWORD_MISMATCH"
                            ).asException()
                        );
                    final var token = tokenService.generateToken(user);
                    return Uni.createFrom().item(new AuthResponse(token, user.id, user.username, user.role));
                }
            );
    }

    /**
     * <span style="color: 95cc6d">用户登出 (将 Token 加入黑名单).</span>
     *
     * @param token JWT Token
     * @return {@link Uni<Void>}
     */
    public @NotNull Uni<Void> logout(@NotNull String token) { return tokenService.invalidateToken(token); }
    //endregion

    //region 辅助方法
    //* 密码最小长度.
    private static final int MIN_PASSWORD_LENGTH = 8;

    //* 密码哈希: PBKDF2WithHmacSHA256 (JDK 内置, 零新增依赖), 迭代次数采用 OWASP 推荐值.
    //* 存储格式: "pbkdf2$<iterations>$<saltHex>$<hashHex>", 随机盐保证相同密码每次哈希不同.
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int PBKDF2_SALT_BYTES = 16;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final @NotNull String PBKDF2_PREFIX = "pbkdf2$";
    private static final @NotNull SecureRandom PBKDF2_RANDOM = new SecureRandom();

    private static @NotNull String hashPassword(@NotNull String password)
    {
        final var salt = new byte[PBKDF2_SALT_BYTES];
        PBKDF2_RANDOM.nextBytes(salt);
        final var spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
        try
        {
            final var hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return PrintUtils.quickFormat(
                "{}{}${}${}",
                PBKDF2_PREFIX, PBKDF2_ITERATIONS, HexFormat.of().formatHex(salt), HexFormat.of().formatHex(hash)
            );
        }
        catch(NoSuchAlgorithmException | InvalidKeySpecException e)
        {
            throw new RuntimeException("PBKDF2 不可用", e);
        }
        finally { spec.clearPassword(); }//! 及时清除密钥材料, 减少内存残留风险.
    }

    private static boolean verifyPassword(@NotNull String rawPassword, @NotNull String storedHash)
    {
        //! 兼容原型阶段遗留的 SHA-256 无盐哈希 (无前缀), 该批用户建议后续登录时重哈希迁移.
        if(!storedHash.startsWith(PBKDF2_PREFIX))
            return sha256Hex(rawPassword).equals(storedHash);

        final var parts = storedHash.split("\\$");
        if(parts.length != 4)
            return false;

        try
        {
            final var spec = new PBEKeySpec(
                rawPassword.toCharArray(),
                HexFormat.of().parseHex(parts[2]),
                Integer.parseInt(parts[1]),
                HexFormat.of().parseHex(parts[3]).length * 8
            );
            final var actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            //* 常量时间比较, 防时序侧信道.
            return MessageDigest.isEqual(actual, HexFormat.of().parseHex(parts[3]));
        }
        catch(NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e)
        {
            //! 存储值损坏时按校验失败处理, 不抛异常避免登录接口暴露内部细节.
            return false;
        }
    }

    private static @NotNull String sha256Hex(@NotNull String input)
    {
        try
        {
            final var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes()));//* 与旧实现保持相同编码, 确保旧哈希可验证.
        }
        catch(NoSuchAlgorithmException e) { throw new RuntimeException("SHA-256 不可用", e); }
    }
    
    //* 校验密码强度: 至少 8 位, 包含字母和数字.
    private static @NotNull Uni<User> createUser(@NotNull RegisterRequest req)
    {
        final var password = req.password();
        
        if(password.length() < MIN_PASSWORD_LENGTH)
            throw IBusinessException.of(
                ErrorCode.BAD_REQUEST,
                PrintUtils.quickFormat("密码长度不能少于{}位", MIN_PASSWORD_LENGTH),
                IllegalArgumentException::new,
                "AUTH_REGISTER_WEAK_PASSWORD_LENGTH"
            ).asException();
        if(
            !password.matches(".*[a-zA-Z].*") ||
            !password.matches(".*\\d.*") ||
            !password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")
        ) throw IBusinessException.of(
            ErrorCode.BAD_REQUEST,
            "密码必须包含字母、数字和特殊字符",
            IllegalArgumentException::new,
            "AUTH_REGISTER_WEAK_PASSWORD_COMPLEXITY"
        ).asException();
        
        final var user = User.create(req.username(), hashPassword(req.password()), req.role());
        return user.persistAndFlush().replaceWith(user);
    }
    //endregion
}