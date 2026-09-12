package kurvcygnus.soulnotes.utils.constants;

import org.jetbrains.annotations.NotNull;

/**
 * <b>JWT 相关常量</b>
 * <ul>
 *     <li>{@link #ISSUER} — JWT 签发者标识</li>
 *     <li>{@link #TOKEN_PREFIX} — Authorization 头的 Token 前缀</li>
 *     <li>{@link #TOKEN_PREFIX_LENGTH} — Bearer 前缀长度（用于 {@code substring}）</li>
 *     <li>{@link #AUTH_HEADER} — 请求头名称</li>
 *     <li>{@link #CHALLENGE_REALM} — 认证质询 Realm</li>
 * </ul>
 * @since 1.0
 */
public final class JwtConstants
{
    private JwtConstants() { throw new IllegalAccessError("Class \"JwtConstants\" is not meant to be instantized!"); }

    public static final @NotNull String ISSUER             = "soul-notes";
    public static final @NotNull String TOKEN_PREFIX        = "Bearer ";
    public static final int             TOKEN_PREFIX_LENGTH = 7;
    public static final @NotNull String AUTH_HEADER         = "Authorization";
    public static final @NotNull String CHALLENGE_REALM     = "Bearer";
}
