package kurvcygnus.soulnotes.utils.enums;

import org.jetbrains.annotations.NotNull;

/**
 * 情绪天气预报类型.
 * <p>每个枚举携带中文标签和前端图标标识, 用于"情绪天气预报"可视化渲染;
 * 取值与情感分析提示词约定的 {@code weather} 字段 (sunny/cloudy/overcast/rainy/thunderstorm) 一一对应.</p>
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
    /**
     * @return 中文标签 (如 "晴", "雷暴")
     */
    @SuppressWarnings("unused")
    public @NotNull String getLabel() { return label; }

    /**
     * @return 前端图标标识 (如 {@code "clear"}, {@code "storm"})
     */
    @SuppressWarnings("unused")
    public @NotNull String getIcon()  { return icon;  }
}