package kurvcygnus.soulnotes.exception;

import kurvcygnus.soulnotes.utils.PrintUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 具体的结构化运行时异常, 为任意 {@link Throwable} 附加 {@linkplain #tag() 类型标签} 进行分类处理.
 * <p>作为 {@link IStructuredThrowable} 的默认实现, 也是本项目中所有自定义异常的推荐基类.</p>
 * <ul>
 *     <li>格式化消息 — 格式为 {@code <SimpleName:Tag> message}, 使日志和堆栈具备自描述性.</li>
 *     <li>校验守卫 — 禁止包装另一个 {@link IStructuredThrowable} 实例, 禁止空白标签.</li>
 *     <li>非空消息保证 — 重写 {@link #getMessage()} 确保返回值永不为 {@code null}.</li>
 * </ul>
 * @see IStructuredThrowable
 * @since 1.0
 */
public class StructuredException extends RuntimeException implements IStructuredThrowable
{
    private final @Nullable Throwable wrappedException;
    private final @NotNull String tag;

    /**
     * 构造一个新的结构化异常, 包装给定的 {@link Throwable} 并附加类型标签.
     *
     * @param cause 被包装的原始异常, 不能为 {@code null}, 且不能实现 {@link IStructuredThrowable}
     * @param tag   非空类型标签 (如 {@code "VALIDATION"})
     * @throws NullPointerException     如果任一参数为 {@code null}
     * @throws IllegalArgumentException 如果 {@code cause} 已实现 {@link IStructuredThrowable}, 或标签为空白
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
     * @implNote 原始 {@link Throwable#getMessage()} 可为 {@code null},
     * 此实现经格式化消息保证返回值永不为 {@code null}.
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