package kurvcygnus.soulnotes.config.prelaunch;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * <b>终端渲染器</b> (Spec §7.1 / Visual Companion v2).
 * <p>pnpm 风格折叠清单的纯格式化层: 无状态静态方法, 只负责 ANSI 包装与行拼装;
 * 密钥掩码 ({@code ******}) 与取值取舍由调用方 (SetupWizard) 决定, 本类不感知配置语义.</p>
 * @since 2.0
 */
public final class TerminalRenderer
{
    //region 样式与状态体系

    //* 色板对齐 Visual Companion v2 (#8b949e 灰 / #e3b341 黄 / #3fb950 绿 / #f85149 红 / #58a6ff 天蓝 / #a5d6ff 浅蓝),
    //* 映射到最接近的标准 SGR 码 (亮色 90/94/96 在 Windows Terminal 与 xterm 下比暗色更贴近原设计); 六色互异, RESET 是复位常量而非颜色.
    public enum Style
    {
        DIM("\u001b[2;3;90m"),       //* 灰斜体 — 中文名/解释/键位提示/可选默认回显
        EMPHASIS("\u001b[1;3;33m"),  //* 黄粗斜体 — 强调
        OK("\u001b[32m"),            //* 绿 — 已配置
        BAD("\u001b[31m"),           //* 红 — 校验失败 (整行)
        OPT("\u001b[94m"),           //* 天蓝 — 可选未动圆点
        VALUE("\u001b[96m"),         //* 浅蓝 — 已保存值回显
        RESET("\u001b[0m");          //* 复位 — 终止任何样式

        private final @NotNull String code;

        Style(@NotNull String code) { this.code = code; }
    }

    //* 清单行状态 (Spec §7.1 双轨状态表): 必填三态 + 可选两态.
    public enum ItemState { REQUIRED_EMPTY, REQUIRED_INVALID, CONFIGURED, OPTIONAL_DEFAULT, OPTIONAL_SET }

    //* 清单行参数对象: echoText 语义随状态变化 (非法=失败原因 / 已配=保存值(密钥已掩码) / 可选默认=默认值 / 未填=null).
    public record ListItem(@NotNull ItemState state, @NotNull String envName, @NotNull String humanName, @Nullable String echoText) {}

    private static final @NotNull String CIRCLE_EMPTY = "○";
    private static final @NotNull String CIRCLE_FILLED = "●";
    private static final @NotNull String DEFAULT_PREFIX = "默认 ";
    private static final @NotNull String COLUMN_GAP = "  ";//* 变量名/中文名/回显之间的双空格分隔.

    private TerminalRenderer() { throw new IllegalAccessError("Class \"TerminalRenderer\" is not meant to be instantized!"); }

    //endregion

    //region 渲染

    /**
     * <span style="color: 95cc6d">以指定样式包装文本 (前缀样式码 + 复位结尾).</span>
     * @param style 样式; RESET 恒等返回 (复位不是颜色, 包装无意义)
     * @param text 原文
     * @return ANSI 包装后的文本
     */
    public static @NotNull String paint(@NotNull Style style, @NotNull String text)
    {
        if(style == Style.RESET) return text;
        return style.code + text + Style.RESET.code;
    }

    /**
     * <span style="color: 95cc6d">渲染清单折叠行: 圆点 + 变量名 + 灰斜体中文名 (+ 行尾回显), 不含换行符.</span>
     * <p>非法态整行红且不做内层着色 — 内层任何复位序列都会截断整行红色 (Spec §7.1 A1).</p>
     * @param item 行参数
     * @return 单行渲染结果
     */
    public static @NotNull String listItemLine(@NotNull ListItem item)
    {
        //* 可选默认: "默认 " 前缀与默认值一体, 默认值为空则整段回显省略.
        final var echoText = item.state() == ItemState.OPTIONAL_DEFAULT && item.echoText() != null && !item.echoText().isEmpty() ?
                             DEFAULT_PREFIX + item.echoText() :
                             item.echoText();
        return switch(item.state())
        {
            case REQUIRED_INVALID ->
                paint(Style.BAD, CIRCLE_EMPTY + ' ' + item.envName() + COLUMN_GAP + item.humanName() + plainEcho(echoText));
            case REQUIRED_EMPTY ->
                CIRCLE_EMPTY + ' ' + item.envName() + COLUMN_GAP + paint(Style.DIM, item.humanName());
            case CONFIGURED, OPTIONAL_SET ->
                paint(Style.OK, CIRCLE_FILLED) + ' ' + item.envName() + COLUMN_GAP + paint(Style.DIM, item.humanName()) + styledEcho(Style.VALUE, echoText);
            case OPTIONAL_DEFAULT ->
                paint(Style.OPT, CIRCLE_EMPTY) + ' ' + item.envName() + COLUMN_GAP + paint(Style.DIM, item.humanName()) + styledEcho(Style.DIM, echoText);
        };
    }

    /**
     * <span style="color: 95cc6d">构造原地重写序列: 光标上移 n 行, 逐行 (清行 + 下移) 抹掉旧内容后回到块顶行首.</span>
     * <p>清行用 {@code [2K} 而非靠新内容覆盖 — 新行可能比旧行短, 残影无法被覆盖抹除;
     * 下移用 {@code [1B} 而非换行 — 屏幕底部不触发滚动. 调用方随即整体重写新块.</p>
     * @param lines 待抹掉的行数
     * @return ANSI 序列; 非正数返回空串
     */
    public static @NotNull String eraseAbove(int lines)
    {
        if(lines <= 0)
            return "";
        return "\u001b[" + lines + "A" + ("\u001b[2K\u001b[1B").repeat(lines) + "\u001b[" + lines + "A\r";
    }

    //* 回显段 (着色版): 双空格引导; null/空白不产出, 避免空样式对.
    private static @NotNull String styledEcho(@NotNull Style style, @Nullable String echoText)
    {
        if(echoText == null || echoText.isEmpty())
            return "";
        return COLUMN_GAP + paint(style, echoText);
    }

    //* 回显段 (裸版): 仅供整行红的非法态使用 — 内层再着色会被复位截断.
    private static @NotNull String plainEcho(@Nullable String echoText) { return echoText == null || echoText.isEmpty() ? "" : COLUMN_GAP + echoText; }

    //endregion
}
