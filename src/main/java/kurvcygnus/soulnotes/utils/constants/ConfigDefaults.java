package kurvcygnus.soulnotes.utils.constants;

import org.jetbrains.annotations.NotNull;

/**
 * <b>配置默认值权威常量</b>
 * <p>application.properties 的 `${ENV:default}` 与代码内 defaultValue 必须引用此处, 防止多处字面量漂移.</p>
 * @since 2.0
 */
public final class ConfigDefaults
{
    private ConfigDefaults() { throw new IllegalAccessError("Class \"ConfigDefaults\" is not meant to be instantized!"); }

    public static final @NotNull String HOTLINE_PRIMARY = "400-161-9995";
    public static final @NotNull String HOTLINE_BACKUP  = "12355";
    public static final @NotNull String HOTLINE_NAME    = "全国心理援助热线";
}
