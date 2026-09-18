package kurvcygnus.soulnotes.utils.constants;

import org.jetbrains.annotations.NotNull;

/**
 * 配置默认值权威常量.
 * <p>application.properties 的 {@code ${ENV:default}} 与代码内 defaultValue 必须引用此处, 防止多处字面量漂移.</p>
 * <p>当前承载心理援助热线三要素, 是危机干预弹窗与离线安全网兜底文案的单一来源.</p>
 * @since 1.1.0
 */
public final class ConfigDefaults
{
    private ConfigDefaults() { throw new IllegalAccessError("Class \"ConfigDefaults\" is not meant to be instantized!"); }

    /** 主热线号码, {@code crisis.hotline.primary} 的默认值, Redis 配置损坏/缺失时的回退值. */
    public static final @NotNull String HOTLINE_PRIMARY = "400-161-9995";

    /** 备用热线号码, {@code crisis.hotline.backup} 的默认值. */
    public static final @NotNull String HOTLINE_BACKUP  = "12355";

    /** 热线展示名称, {@code crisis.hotline.name} 的默认值. */
    public static final @NotNull String HOTLINE_NAME    = "全国心理援助热线";
}
