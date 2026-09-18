package kurvcygnus.soulnotes.config.prelaunch;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * 数据库探测/修复网关 (可测性收敛点).
 * <p>探测动作收敛于本接口: 生产实现 {@link PgGateway}, 任务逻辑单测用 fake 驱动五态.</p>
 *
 * @implNote 签名为同步阻塞式 — Pre-Launch 阶段运行于 CDI 启动之前, 无事件循环可挂靠, 阻塞是安全且有意的.
 * @since 1.1.0
 */
public interface IDatabaseGateway
{
    /**
     * 五态探测 (只读, 零写入).
     * <p>{@code SELECT 1} 区分 UNREACHABLE / AUTH_FAILED / DB_MISSING, 连库成功后核对期望表集得出 OK / SCHEMA_MISSING.</p>
     *
     * @param target 探测目标 (host/port/db/user/password)
     * @return 五态结果, 绝不返回 null; 探测失败不抛异常, 一律归入五态之一
     * @since 1.1.0
     */
    @NotNull ProbeResult probe(@NotNull DbTarget target);

    /**
     * 连同主机 {@code postgres} 默认维护库创建目标数据库 (仅 TTY 向导路径调用).
     *
     * @param target 目标库名取自 {@code target.database()}, 连接凭据复用同一账号
     * @throws IllegalStateException 建库失败 (库已存在竞争 / 无权限 / postgres 库不可达), 携带人类可读原因
     * @since 1.1.0
     */
    void createDatabase(@NotNull DbTarget target);

    /**
     * 按文件名序执行 classpath {@code db/schema/*.sql} 幂等脚本 (仅 TTY 向导路径调用).
     *
     * @param target 目标数据库
     * @throws IllegalStateException 单脚本执行失败即中止, 携带脚本名与原因
     * @since 1.1.0
     */
    void applySchema(@NotNull DbTarget target);

    /**
     * 带逐脚本进度的建表重载: {@code scriptProgress} 按执行序接收每个脚本名 (向导逐行回显用).
     * <p>默认实现委托单参版本并忽略进度 — 既有实现 (测试 fake) 无需感知进度语义;
     * 生产 {@link PgGateway} 覆写本方法提供真实逐脚本回调.</p>
     *
     * @param target 目标数据库
     * @param scriptProgress 按执行序接收脚本名 (如 {@code 01_users.sql}), 仅在脚本执行成功后回调
     * @throws IllegalStateException 单脚本执行失败即中止, 携带脚本名与原因
     * @since 1.1.0
     */
    default void applySchema(@NotNull DbTarget target, @NotNull Consumer<String> scriptProgress) { applySchema(target); }
}
