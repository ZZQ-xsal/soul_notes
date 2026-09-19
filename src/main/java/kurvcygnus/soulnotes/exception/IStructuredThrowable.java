package kurvcygnus.soulnotes.exception;

import org.jetbrains.annotations.NotNull;

/**
 * 异常体系的基础接口.
 * <p>为任意 {@link Throwable} 附加一个类型标签 ({@link #tag()}), 将其转化为结构化形式,
 * 并委托给 {@link #cause()} 保存原始异常.</p>
 * <ul>
 *     <li>{@link #tag()} — 非空字符串标签 (如 {@code "VALIDATION"}、{@code "AUTH"}),
 *         用于分类异常, 避免 {@code instanceof} 链.</li>
 *     <li>{@link #cause()} — 解包原始 {@link Throwable}, 保留完整因果链.</li>
 * </ul>
 * @see StructuredException
 * @see IDetailedThrowable
 * @since 1.0
 */
public interface IStructuredThrowable
{
    /**
     * 返回被此结构化异常装饰的原始 {@link Throwable}.
     * @return 被包装的异常, 永不为 {@code null}
     */
    @NotNull Throwable cause();

    /**
     * 返回分类此异常的类型标签.
     * <p>标签代表异常所属的上下文语义, 不应用于模式匹配或外部回滚判定,
     * 仅用于表示处理阶段, 或供 {@link ITransactionalThrowable#rollback()} 使用.</p>
     * @return 非空字符串标识 (如 {@code "VALIDATION"}、{@code "AUTH"}), 永不为 {@code null}
     */
    @NotNull String tag();
}