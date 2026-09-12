package kurvcygnus.soulnotes.domain.auth.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * <b>用户实体</b>
 * <p>对应 {@code users} 表, 使用 Panache 响应式模式, 手动管理 ID.</p>
 * @since 1.0
 */
@Entity @Table(name = "users")
public final class User extends PanacheEntityBase
{
    //region 字段
    //! ID 由应用层生成 (UUID), 避免暴露自增 ID 的安全风险.
    @Id public UUID id;

    @Column(name = "user_name", unique = true, nullable = false)
    public String username;

    @Column(name = "password_hash", nullable = false)
    public String passwordHash;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    public UserRole role;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    //endregion

    //region 工厂方法
    //* 通过受控的工厂方法创建用户实例, 确保字段完整性.
    public static @NotNull User create(@NotNull String username, @NotNull String passwordHash, @NotNull UserRole role)
    {
        final var user = new User();
        user.id           = UUID.randomUUID();
        user.username     = username;
        user.passwordHash = passwordHash;
        user.role         = role;
        user.createdAt    = Instant.now();
        return user;
    }
    //endregion

    //region 静态查询
    /**
     * 根据用户名查找用户.
     *
     * @param username 用户名 (非空)
     * @return 包含 {@link User} 的 {@link Uni}, 未找到时为 {@code null}
     */
    public static @NotNull Uni<User> findByUsername(@NotNull String username) { return find("username", username).firstResult(); }
    //endregion
}