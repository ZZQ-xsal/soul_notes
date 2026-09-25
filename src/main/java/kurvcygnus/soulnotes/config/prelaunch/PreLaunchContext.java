package kurvcygnus.soulnotes.config.prelaunch;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Pre-Launch 上下文: 配置视图 + 向导条目元数据 + 生效 profile.
 *
 * @param view 显式值查找视图 (系统属性 > 环境变量 > 工作目录配置文件 > 元数据默认值)
 * @param items 向导条目元数据, 文件顺序即向导展示与落盘顺序
 * @param profile 生效 profile 名 ({@code quarkus.profile} / {@code QUARKUS_PROFILE}, 默认 prod), 必配与警告规则按其分派
 * @since 1.1.0
 */
public record PreLaunchContext(@NotNull ConfigView view, @NotNull List<PropertyMetaParser.ConfigItemMeta> items, @NotNull String profile) {}
