package kurvcygnus.soulnotes.config.prelaunch;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Pre-Launch 任务: CDI 容器启动前执行的单项配置校验/探测单元.
 * <p>sealed: 新任务 (如 DB 探活) 需显式修订 permits — 有意的扩展门槛.</p>
 *
 * @implNote 任务间存在次序依赖时以 {@link #priority()} 表达, 不引入任务间引用 — 保持各任务可独立单测.
 * @since 1.1.0
 */
public sealed interface IPreLaunchTask permits ConfigValidationTask, DbValidationTask
{
    /**
     * 问题等级: 决定 issue 是否阻断启动.
     */
    enum Level
    {
        /**
         * 阻断级: 存在此类 issue 时拒绝启动.
         */
        //* 阻断: 存在此级 issue 时拒绝启动 (无 TTY 直接退出, 有 TTY 引导 Setup 向导).
        BLOCK,
        /**
         * 警告级: 仅报告, 不影响启动结论.
         */
        //* 警告: 仅报告, 不影响启动结论.
        WARN
    }

    /**
     * 单条问题.
     *
     * @param level 问题等级, 决定是否阻断启动
     * @param subject 定位标识, 惯例为环境变量名 (如 {@code SOULNOTES_DB_URL}), 便于按配置项定位
     * @param message 面向用户的描述文本, 已按展示格式写好
     * @since 1.1.0
     */
    record Issue(@NotNull Level level, @NotNull String subject, @NotNull String message)
    {}

    /**
     * 单任务的执行结果.
     *
     * @param issues 本任务发现的全部问题; 无问题时为空列表, 绝不为 null
     * @since 1.1.0
     */
    record Result(@NotNull List<Issue> issues)
    {
        /**
         * 判定本任务是否含有阻断级问题.
         *
         * @return 存在 {@link Level#BLOCK} 级 issue 时为 true
         * @since 1.1.0
         */
        public boolean hasBlocks() { return issues.stream().anyMatch(i -> i.level() == Level.BLOCK); }
    }

    /**
     * 任务名.
     *
     * @return 人类可读短名, 用于校验报告与向导进度展示 (如 "配置校验")
     * @since 1.1.0
     */
    @NotNull String name();

    /**
     * 执行次序.
     *
     * @return 次序值, 值越大越靠后执行; 默认 0 — 有前后依赖的任务 (如 DB 探测须排在配置校验之后) 以更大值表达
     * @since 1.1.0
     */
    default int priority() { return 0; }

    /**
     * 执行本任务并收集问题.
     *
     * @param ctx 配置视图/条目元数据/生效 profile 组成的执行上下文
     * @return 收集结果; 探测前置条件不满足时允许静默跳过 (返回空 issues), 而非产出噪音问题
     * @since 1.1.0
     */
    @NotNull Result run(@NotNull PreLaunchContext ctx);
}
