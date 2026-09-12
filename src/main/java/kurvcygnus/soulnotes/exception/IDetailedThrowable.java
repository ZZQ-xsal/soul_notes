package kurvcygnus.soulnotes.exception;

import org.jetbrains.annotations.NotNull;

/**
 * <b>携带类型化详细数据的结构化异常接口</b>。<br>
 * 在 {@link IStructuredThrowable} 的基础上附加 <b>失败上下文数据</b>，
 * 使异常本身成为数据源。{@link #causeData()} 返回的类型化负载可供错误恢复逻辑使用。<hr>
 * <p><b>提供的能力:</b></p>
 * <ul>
 *     <li>{@link #causeData()} — 返回 <b>类型化的负载</b>（{@code T}），
 *         包含失败相关的领域细节（如非法输入值、失败实体 ID）。</li>
 *     <li>{@link #asException()} — 安全地将自身转型为具体 {@link StructuredException} 子类型。</li>
 *     <li>{@link #throwSelf()} — 便捷方法，直接抛出此异常，避免捕获后再转型。</li>
 * </ul>
 *
 * @param <E> 实现此接口的具体 {@link StructuredException} 子类型（CRTP 自类型）
 * @param <T> 详细原因数据的类型
 * @see StructuredException
 * @see ITransactionalThrowable
 * @since 1.0
 */
public interface IDetailedThrowable<E extends StructuredException & IDetailedThrowable<E, T>, T>
    extends IStructuredThrowable
{
    /**
     * 返回与此异常原因关联的 <b>类型化详细数据</b>。
     * @return 详细原因数据
     */
    @NotNull T causeData();

    /**
     * <span style="color: 95cc6d">安全</span> 自转型。<br>
     * 将当前实例转型为具体 {@link StructuredException} 子类型 {@code E}。
     * @return 转型后的此异常实例
     */
    @SuppressWarnings("unchecked")//! CRTP 模式：E 绑定为实现类型，转型安全。
    default @NotNull E asException() { return (E) this; }

    /**
     * 以具体类型 {@code E} 抛出此异常。
     * @throws E 始终抛出，因为这是一个失败结果
     */
    @SuppressWarnings("unused")//! 保留作为异常 API 的便捷方法, 供调用方显式抛出, 当前项目以 Uni 管道代替.
    default void throwSelf() throws E { throw asException(); }

    /**
     * {@inheritDoc}
     * 委托给 {@link #asException()}{@code .getMessage()}。
     * @return 底层结构化异常中的格式化消息
     */
    default @NotNull String getMessage() { return asException().getMessage(); }

    /**
     * 返回被包装的原始异常作为 {@linkplain Throwable#getCause() cause}。
     * @return 被包装的 Throwable，永不为 null
     */
    @Override default @NotNull Throwable cause() { return asException().cause(); }
}