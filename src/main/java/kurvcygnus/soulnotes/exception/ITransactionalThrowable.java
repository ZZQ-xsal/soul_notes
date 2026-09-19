package kurvcygnus.soulnotes.exception;

/**
 * 可回滚/可恢复的结构化异常, 携带补偿动作 ({@link #rollback()}).
 * <p>本体系中能力最完备的错误恢复接口 — 失败不再是终点, 异常自身携带撤销副作用的能力:
 * {@link #rollback()} 执行补偿动作, 撤销与此异常关联的状态变更并返回恢复值,
 * 将异常从被动错误描述提升为主动恢复代理.</p>
 * <p>继承 {@link IDetailedThrowable} (类型化原因数据) 和 {@link IStructuredThrowable} (类型标签),
 * 单个异常即可携带: 分类 + 详细上下文 + 回滚策略.</p>
 *
 * @param <E> 实现此接口的具体 {@link StructuredException} 子类型 (CRTP 自类型)
 * @param <T> 详细原因数据类型 (从 {@link IDetailedThrowable} 传递)
 * @param <R> 回滚返回值的类型 — 补偿后产生的降级结果
 * @see StructuredException
 * @since 1.0
 */
public interface ITransactionalThrowable<E extends StructuredException & ITransactionalThrowable<E, T, R>, T, R>
    extends IDetailedThrowable<E, T>
{
    /**
     * 执行回滚/补偿动作, 撤销失败前已发生的部分副作用, 并返回恢复值.
     * @return 补偿后的降级结果, 类型为 {@code R}
     */
    R rollback();
}