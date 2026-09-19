package kurvcygnus.soulnotes.utils.enums;

/**
 * 用户角色枚举.
 * <ul>
 *     <li>{@link #STUDENT} — 学生</li>
 *     <li>{@link #COUNSELOR} — 心理咨询师</li>
 *     <li>{@link #ADMIN} — 管理员</li>
 * </ul>
 * @since 1.0
 */
public enum UserRole
{
    STUDENT,
    COUNSELOR,
    ADMIN;

    //* 字符串常量, 用于 @RolesAllowed 等注解 (注解值必须为编译期常量).
    public static final String ROLE_STUDENT   = "STUDENT";

    //! COUNSELOR / ADMIN 目前无端点使用, 保留供未来角色扩展.
    @SuppressWarnings("unused")
    public static final String ROLE_COUNSELOR = "COUNSELOR";
    @SuppressWarnings("unused")
    public static final String ROLE_ADMIN     = "ADMIN";
}