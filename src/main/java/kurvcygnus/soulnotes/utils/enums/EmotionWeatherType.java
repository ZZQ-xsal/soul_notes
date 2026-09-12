package kurvcygnus.soulnotes.utils.enums;

import org.jetbrains.annotations.NotNull;

/**
 * <b>情绪天气预报类型</b>
 * <p>每个枚举携带中文标签和图标标识, 用于前端"情绪天气预报"可视化渲染.</p>
 * @since 1.0
 */
public enum EmotionWeatherType
{
    SUNNY       ("晴",     "clear"),
    CLOUDY      ("多云",   "partly_cloudy"),
    OVERCAST    ("阴",     "overcast"),
    RAINY       ("雨",     "rain"),
    THUNDERSTORM("雷暴",   "storm");

    private final @NotNull String label;
    private final @NotNull String icon;

    EmotionWeatherType(@NotNull String label, @NotNull String icon)
    {
        this.label = label;
        this.icon  = icon;
    }

    //! getLabel/getIcon 参与 Jackson JSON 序列化 (反射调用), IDE 静态分析误报为未使用.
    @SuppressWarnings("unused")
    public @NotNull String getLabel() { return label; }
    @SuppressWarnings("unused")
    public @NotNull String getIcon()  { return icon;  }
}