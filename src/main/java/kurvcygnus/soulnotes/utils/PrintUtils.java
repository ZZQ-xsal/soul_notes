package kurvcygnus.soulnotes.utils;

import kurvcygnus.soulnotes.utils.lint.CallerSensitive;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

import java.util.Objects;

/**
 * 日志打印与文本格式化相关的静态工具集合.
 * @since 1.0
 */
public final class PrintUtils
{
    private PrintUtils() { throw new IllegalAccessError("Class \"PrintUtils\" is not meant to be instantized!"); }

    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final String STACKTRACE_SENSITIVE = "涉及调用栈抓取.";

    /**
     * 获取与调用者类绑定的 {@link Logger} 实例.
     * <p>经 {@link StackWalker} 解析调用者类, 免去 {@link LoggerFactory#getLogger(Class)} 处反复填写
     * {@code Class<?>} 字面量的重复劳动, 也消除重构改名后忘记同步参数的隐患.</p>
     * @apiNote 该方法依赖调用者类信息, 属于调用者敏感方法, 不可被再次包装 —
     *          包装后 {@code getCallerClass()} 将解析到包装者而非真实业务类.
     * @return 以调用者类命名的 Logger, 永不为 {@code null}
     * @since 1.0
     */
    @CallerSensitive(STACKTRACE_SENSITIVE)
    public static @NotNull Logger getLogger() { return LoggerFactory.getLogger(STACK_WALKER.getCallerClass()); }

    /**
     * 以 {@code {}} 占位符格式化字符串, 返回渲染后的最终文本.
     *
     * @param format 含 {@code {}} 占位符的模板, 不得为空白
     * @param args   依序填充占位符的参数; 尾参若为 {@link Throwable} 会被 SLF4J
     *               视为异常候选剥离而不填充占位符, 需输出异常详情时传 {@code toString()}
     * @return 所有占位符替换完毕后的字符串, 永不为 {@code null}
     * @throws NullPointerException     {@code format} 或 {@code args} 为 {@code null} 时
     * @throws IllegalArgumentException 模板为空白时
     * @implNote 走 SLF4J {@code MessageFormatter} 而非 {@link String#format(String, Object...)} /
     *           {@link String#formatted(Object...)}: 后两者每次调用做正则解析, 在高频日志与 UI 文案路径上开销显著.
     * @since 1.0
     */
    public static @NotNull String quickFormat(@NotNull String format, @NotNull Object @NotNull ... args)
    {
        Objects.requireNonNull(format, "Param \"format\" must not be null!");
        Objects.requireNonNull(args, "Param \"args\" must not be null!");
        if(format.isBlank())
            throw new IllegalArgumentException("Param \"format\" must not be empty!");
        //* 使用 arrayFormat 以正确展开 varargs 数组.
        return MessageFormatter.arrayFormat(format, args).getMessage();
    }
}