package kurvcygnus.soulnotes.utils.constants;

import org.jetbrains.annotations.NotNull;

/**
 * <b>Redis Key 模式常量</b>
 * <ul>
 *     <li>{@link #TOKEN_BLACKLIST} — JWT 黑名单 Key，使用 {@link String#formatted(Object...)} 传入 jti</li>
 *     <li>{@link #RATE_LIMIT} — 限流 Key，预留未来使用</li>
 * </ul>
 * @since 1.0
 */
public final class RedisKeyConstants
{
    private RedisKeyConstants() { throw new IllegalAccessError("Class \"RedisKeyConstants\" is not meant to be instantized!"); }

    public static final @NotNull String TOKEN_BLACKLIST = "jwt:blacklist:%s";
    //? Phase 4: 限流功能启用时使用
    public static final @NotNull String RATE_LIMIT      = "ratelimit:%s:%s";
}
