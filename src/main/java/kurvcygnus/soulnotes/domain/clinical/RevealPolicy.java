package kurvcygnus.soulnotes.domain.clinical;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 隐私分级策略: 按风险等级解锁学生真实身份 ("最小必要"原则).
 * <p>未解锁记录的 {@code userId} 与 {@code displayName} 一并掩码为 8 位稳定短码 —
 * 防止咨询员拿 userId 直接打学生维度 API 绕过脱敏; 解锁记录返回完整 UUID 与 username.
 * REST 与 WS 推送共用本出口, 杜绝旁路.</p>
 * @since 1.2.0
 */
public final class RevealPolicy
{
    private RevealPolicy() { throw new IllegalAccessError("Class \"RevealPolicy\" is not meant to be instantized!"); }

    /**
     * 实名解锁等级配置值.
     */
    public enum RevealLevel
    {
        RED, YELLOW, NEVER;

        /**
         * 解析配置值.
         *
         * @param raw 配置原文 (大小写不敏感; null/非法值回落 RED — 最保守默认, 启动不被脏配置阻断)
         * @return 解析结果
         */
        public static @NotNull RevealLevel parse(@Nullable String raw)
        {
            if(raw == null || raw.isBlank())
                return RED;
            try { return valueOf(raw.strip().toUpperCase(Locale.ROOT)); }
            catch(IllegalArgumentException e) { return RED; }
        }

        /**
         * 判定该等级配置下指定风险记录是否解锁实名.
         */
        public boolean unlocks(@NotNull String riskLevel) { return switch(this) { case NEVER -> false; case YELLOW -> true; case RED -> "RED".equals(riskLevel); }; }
    }

    /**
     * 脱敏后的身份标识.
     *
     * @param userId      解锁时为完整 UUID; 掩码时为 8 位短码 (前端回传查询用, 短码不可反查)
     * @param displayName 解锁时为 username; 掩码时为 "学生 #短码"
     */
    public record MaskedIdentity(@NotNull String userId, @NotNull String displayName) {}

    /**
     * 按配置等级与记录风险等级计算身份标识.
     *
     * @param level    配置的解锁等级
     * @param riskLevel 记录的风险等级 (YELLOW / RED)
     * @param userId   学生完整 ID
     * @param username 学生实名 (可为 null — 用户被删时掩码兜底)
     * @return 身份标识 (恒非 null)
     */
    public static @NotNull MaskedIdentity identity(
        @NotNull RevealLevel level, @NotNull String riskLevel, @NotNull UUID userId, @Nullable String username
    )
    {
        Objects.requireNonNull(level, "Param \"level\" must not be null!");
        Objects.requireNonNull(riskLevel, "Param \"riskLevel\" must not be null!");
        Objects.requireNonNull(userId, "Param \"userId\" must not be null!");
        if(level.unlocks(riskLevel) && username != null)
            return new MaskedIdentity(userId.toString(), username);
        //* UUID 前 8 位为确定性短码: 同一学生恒定同一掩码, 咨询员可跨页/跨推送比对而不可反查.
        final var shortCode = userId.toString().substring(0, 8);
        return new MaskedIdentity(shortCode, "学生 #" + shortCode);
    }
}
