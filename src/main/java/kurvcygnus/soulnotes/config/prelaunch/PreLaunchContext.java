package kurvcygnus.soulnotes.config.prelaunch;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/** <b>Pre-Launch 上下文</b>: 配置视图 + 向导条目元数据 + 生效 profile. @since 2.0 */
public record PreLaunchContext(@NotNull ConfigView view, @NotNull List<PropertyMetaParser.ConfigItemMeta> items, @NotNull String profile)
{}
