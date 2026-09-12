package kurvcygnus.soulnotes.utils;

import kurvcygnus.soulnotes.utils.lint.CallerSensitive;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

import java.util.Objects;

/**
 * 收纳打印日志相关的方法的静态集合工具类.
 */
public final class PrintUtils
{
    private PrintUtils() { throw new IllegalAccessError("Class \"PrintUtils\" is not meant to be instantized!"); }

    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final String STACKTRACE_SENSITIVE = "涉及调用栈抓取.";

    /**
     * 获取一个 <u>{@link Logger}</u> 实例.
     * @apiNote 该获取通过 <u>{@link StackWalker}</u> 获取调用者信息, 以此解决 <u>{@link LoggerFactory#getLogger(Class)}</u> 中反复填写 {@code Class<?>}
     * 的重复劳动, 以及潜在的重构参数错误问题.<br>
     * 由于该方法需获取调用者信息, <span style="color: f84b4b">因此这个方法不可以被再次包装.</span>
     */
    @CallerSensitive(STACKTRACE_SENSITIVE)
    public static @NotNull Logger getLogger() { return LoggerFactory.getLogger(STACK_WALKER.getCallerClass()); }

    /**
     * 获取一个格式化的字符串.
     * @apiNote 该方法使用 {@code {}} 作为占位符.
     * @implNote <u>{@link String#format(String, Object...)}</u> 和 <u>{@link String#formatted(Object...)}</u> 都涉及复杂的正则解析式, 太慢了.<br>
     * 因此在需要格式化字符串时请使用这个方法来保证性能.
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