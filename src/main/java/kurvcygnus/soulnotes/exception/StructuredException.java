package kurvcygnus.soulnotes.exception;

import kurvcygnus.soulnotes.utils.PrintUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * <b>具体的结构化运行时异常</b>，为任意 {@link Throwable} 附加 {@linkplain #tag() 类型标签} 进行分类处理。<br>
 * 作为 {@link IStructuredThrowable} 的 <b>默认实现</b>，也是本项目中所有自定义异常的推荐基类。<hr>
 * <p><b>提供的能力:</b></p>
 * <ul>
 *     <li><b>格式化消息</b> — 格式为 {@code <SimpleName:Tag> message}，使日志和堆栈具备自描述性。</li>
 *     <li><b>校验守卫</b> — 禁止包装另一个 {@link IStructuredThrowable} 实例，禁止空白标签。</li>
 *     <li><b>非空消息保证</b> — 重写 {@link #getMessage()} 确保返回值永不为 null。</li>
 * </ul>
 * @see IStructuredThrowable
 */
public class StructuredException extends RuntimeException implements IStructuredThrowable
{
    private final @Nullable Throwable wrappedException;
    private final @NotNull String tag;

    /**
     * 构造一个新的结构化异常，包装给定的 {@link Throwable} 并附加类型标签。
     *
     * @param cause 被包装的原始异常，不能为 null，且不能实现 {@link IStructuredThrowable}
     * @param tag   非空类型标签（如 {@code "VALIDATION"}）
     * @throws NullPointerException     如果任一参数为 null
     * @throws IllegalArgumentException 如果 {@code cause} 已实现 {@link IStructuredThrowable}，或标签为空白
     */
    public StructuredException(@NotNull Throwable cause, @NotNull String tag)
    {
        super(
            PrintUtils.quickFormat(
                "<{}:{}> {}",
                cause.getClass().getSimpleName(),
                checkType(tag),
                Objects.requireNonNull(cause, "Param \"cause\" must not be null!").getMessage()
            ),
            cause
        );
        this.wrappedException = checkCause(cause);
        this.tag = tag;
    }

    /**
     * {@inheritDoc}
     */
    @Override public @NotNull Throwable cause()
    {
        //! 当无包装异常时，返回自身作为 cause 以保证非空契约。
        return wrappedException != null ? wrappedException : this;
    }

    /**
     * {@inheritDoc}
     */
    @Override public @NotNull String tag() { return tag; }

    /**
     * @implNote 原始 {@link Throwable#getMessage()} 可为 null，
     * 此实现确保返回值永不为 null。
     */
    @Override public @NotNull String getMessage() { return super.getMessage(); }

    //region 校验
    private static @NotNull Throwable checkCause(@NotNull Throwable cause)
    {
        if(cause instanceof IStructuredThrowable)
            throw new IllegalArgumentException("不允许包装另一个 IStructuredThrowable！");
        //! 不校验 cause.getMessage() 是否为空 —— 某些异常确实没有消息（如 NullPointerException），
        //! 此时使用空字符串作为消息即可。
        return cause;
    }

    private static @NotNull String checkType(@NotNull String tag)
    {
        Objects.requireNonNull(tag, "Param \"tag\" must not be null!");

        if(tag.isBlank())
            throw new IllegalArgumentException("标签不能为空！");

        return tag;
    }
    //endregion
}