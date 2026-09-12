package kurvcygnus.soulnotes.exception;

import org.jetbrains.annotations.NotNull;

/**
 * <b>异常体系的基础接口</b>
 * <p>为任意 {@link Throwable} 附加一个 <b>类型标签</b> ({@link #tag()})，
 * 将其转化为<b>结构化</b>形式，并委托给 {@link #cause()} 保存原始异常。</p>
 * <hr>
 * <p><b>提供的能力:</b></p>
 * <ul>
 *     <li>{@link #tag()} — 非空字符串标签（如 {@code "VALIDATION"}、{@code "AUTH"}），
 *         用于分类异常，避免 {@code instanceof} 链。</li>
 *     <li>{@link #cause()} — 解包原始 {@link Throwable}，保留完整因果链。</li>
 * </ul>
 * @see StructuredException
 * @see IDetailedThrowable
 */
public interface IStructuredThrowable
{
    /**
     * 返回被此结构化异常装饰的原始 {@link Throwable}。
     * @return 被包装的异常，永不为 null
     */
    @NotNull Throwable cause();

    /**
     * 返回分类此异常的 <b>类型标签</b>。<br>
     * 标签代表异常所属的 <b>上下文语义</b>，<span style="color: f84b4b">不应用于模式匹配或外部回滚判定</span>，
     * 仅用于表示处理阶段，或供 {@link ITransactionalThrowable#rollback()} 使用。
     * @return 非空字符串标识（如 {@code "VALIDATION"}、{@code "AUTH"}），永不为 null
     */
    @NotNull String tag();
}