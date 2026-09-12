package kurvcygnus.soulnotes.exception;

/**
 * <b>可回滚 / 可恢复的结构化异常</b>，携带 <b>补偿动作</b> ({@link #rollback()})。<br>
 * 这是本体系中<b>最强大</b>的错误恢复接口 —— 失败不再是终点，异常自身携带撤销副作用的能力。<hr>
 * <p><b>提供的能力:</b></p>
 * <ul>
 *     <li>{@link #rollback()} — 执行 <b>补偿动作</b>，撤销与此异常关联的状态变更，
 *         并返回一个恢复值。将异常从被动错误描述提升为主动恢复代理。</li>
 *     <li>继承 {@link IDetailedThrowable}（类型化原因数据）和 {@link IStructuredThrowable}（类型标签），
 *         单个异常即可携带：分类 + 详细上下文 + 回滚策略。</li>
 * </ul>
 *
 * @param <E> 实现此接口的具体 {@link StructuredException} 子类型（CRTP 自类型）
 * @param <T> 详细原因数据类型（从 {@link IDetailedThrowable} 传递）
 * @param <R> 回滚返回值的类型 —— 补偿后产生的降级结果
 * @see StructuredException
 */
public interface ITransactionalThrowable<E extends StructuredException & ITransactionalThrowable<E, T, R>, T, R>
    extends IDetailedThrowable<E, T>
{
    /**
     * 执行 <b>回滚 / 补偿动作</b>，撤销失败前已发生的部分副作用，并返回恢复值。
     * @return 补偿后的降级结果，类型为 {@code R}
     */
    R rollback();
}