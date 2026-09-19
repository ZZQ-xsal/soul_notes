package kurvcygnus.soulnotes.config.prelaunch;

import kurvcygnus.soulnotes.utils.PrintUtils;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pre-Launch 数据库真校验任务 (非 TTY 段).
 * <p>只探测 + 报告 (零写入): UNREACHABLE/AUTH_FAILED/DB_MISSING/SCHEMA_MISSING 均为 BLOCK —
 * 拒绝启动优于运行期 500. createDatabase/applySchema 仅由 TTY 向导路径调用.
 * ConfigValidationTask 存在 DB 相关 BLOCK (URL/凭据缺失) 时探测无意义, 本任务静默跳过让配置层先报.</p>
 * @since 1.1.0
 */
public final class DbValidationTask implements IPreLaunchTask
{
    //* 条目定位键: 元数据 (key/env/default) 以 application.properties 为单一来源, 此处不复制默认字面量.
    private static final @NotNull String URL_KEY = "quarkus.datasource.reactive.url";
    private static final @NotNull String USER_KEY = "quarkus.datasource.username";
    private static final @NotNull String PASSWORD_KEY = "quarkus.datasource.password";
    private static final @NotNull String SUBJECT = "SOULNOTES_DB_URL";//* 五态皆属连接配置问题, 统一以 URL 环境变量为定位 subject.

    private final @NotNull IDatabaseGateway gateway;

    /**
     * 纯构造入口 (Pre-Launch 无 CDI 容器, 网关手工装配).
     *
     * @param gateway 生产传 {@link PgGateway}, 测试传 fake
     * @since 1.1.0
     */
    public DbValidationTask(@NotNull IDatabaseGateway gateway)
    { this.gateway = Objects.requireNonNull(gateway, "Param \"gateway\" must not be null!"); }

    /** @return 固定为 "数据库连通性". */
    @Override public @NotNull String name() { return "数据库连通性"; }

    /**
     * @return 10 — 必须排在 ConfigValidationTask (默认 0) 之后, 无效配置时探测无意义.
     */
    //* 次序值越大越靠后执行 — 必须排在 ConfigValidationTask (默认 0) 之后, 无效配置时探测无意义.
    @Override public int priority() { return 10; }

    /**
     * 执行五态探测并转译为问题: 非 OK 一律产出 BLOCK (subject 统一为 {@code SOULNOTES_DB_URL}).
     * <p>前置条件不满足时静默跳过 (返回空 issues): 任一 DB 元数据缺失 (配置结构损坏), 或三项必配值为空
     * (配置校验层必然已产出 BLOCK).</p>
     *
     * @param ctx 执行上下文
     * @return 探测结果; OK 为空 issues, 其余五态各携带一条面向用户的 BLOCK
     * @since 1.1.0
     */
    @Override public @NotNull Result run(@NotNull PreLaunchContext ctx)
    {
        final var urlMeta = meta(ctx, URL_KEY);
        final var userMeta = meta(ctx, USER_KEY);
        final var passwordMeta = meta(ctx, PASSWORD_KEY);
        //! 任一元数据缺失意味着 application.properties 结构损坏 (键被删), 配置校验层与向导均已失效,
        //! 此时盲目探测只会产出误导性诊断 — 静默跳过, 不在本层重复报告.
        if(urlMeta.isEmpty() || userMeta.isEmpty() || passwordMeta.isEmpty())
            return new Result(List.of());

        final var view = ctx.view();
        final var url = view.resolved(urlMeta.get().key(), urlMeta.get().envName(), urlMeta.get().defaultValue());
        final var user = view.resolved(userMeta.get().key(), userMeta.get().envName(), userMeta.get().defaultValue());
        final var password = view.resolved(passwordMeta.get().key(), passwordMeta.get().envName(), passwordMeta.get().defaultValue());
        //* 必配项为空时配置校验层必然产出 BLOCK (prod required 规则), 本任务探测无意义 — 让 config 层先报.
        if(url.isBlank() || user.isBlank() || password.isBlank())
            return new Result(List.of());

        final DbTarget target;
        try { target = DbTarget.parse(url, user, password); }
        catch(IllegalStateException e)
            { return block(PrintUtils.quickFormat("数据库地址结构非法, 无法解析: {}", e.getMessage())); }

        final var probe = gateway.probe(target);
        return switch(probe.state())
        {
            case OK -> new Result(List.of());
            case UNREACHABLE -> block("数据库不可达 (连接被拒绝或超时), 请确认 PostgreSQL 实例已运行且 host:port / 防火墙配置正确");
            case AUTH_FAILED -> block("数据库账号或密码被拒绝, 请检查 SOULNOTES_DB_USER / SOULNOTES_DB_PASSWORD");
            case DB_MISSING -> block("目标数据库不存在, 可经配置向导自动创建 (或手动执行 CREATE DATABASE)");
            case SCHEMA_MISSING -> block(PrintUtils.quickFormat(
                "数据库 schema 未就绪 (期望表缺失), 可经配置向导执行初始化脚本: {}",
                String.join(", ", probe.missingTables())));
        };
    }

    /** 组装单条 BLOCK 级结果. */
    private static @NotNull Result block(@NotNull String message) { return new Result(List.of(new Issue(Level.BLOCK, SUBJECT, message))); }

    /** 按配置键从条目元数据中定位条目; 无匹配 (键被删) 时为 empty. */
    private static @NotNull Optional<PropertyMetaParser.ConfigItemMeta> meta(@NotNull PreLaunchContext ctx, @NotNull String key)
    { return ctx.items().stream().filter(item -> key.equals(item.key())).findFirst(); }
}
