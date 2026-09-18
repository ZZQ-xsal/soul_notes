package kurvcygnus.soulnotes.config.prelaunch;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * <b>Pre-Launch 任务</b>
 * <p>sealed: 新任务 (如 DB 探活) 需显式修订 permits — 有意的扩展门槛 (Spec §6).</p>
 * @since 2.0
 */
public sealed interface IPreLaunchTask permits ConfigValidationTask
{
    enum Level { BLOCK, WARN }
    record Issue(@NotNull Level level, @NotNull String subject, @NotNull String message) {}
    record Result(@NotNull List<Issue> issues) { public boolean hasBlocks() { return issues.stream().anyMatch(i -> i.level() == Level.BLOCK); } }

    @NotNull String name();
    default int priority() { return 0; }
    @NotNull Result run(@NotNull PreLaunchContext ctx);
}
