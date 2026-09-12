package kurvcygnus.soulnotes.exception;

import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

/**
 * <b>业务异常门面接口</b>, 为业务层提供声明式异常创建能力. <br>
 * 通过静态工厂方法统一创建 {@link HolderException} / {@link DataHolderException} 实例, 
 * 外部代码无需直接依赖具体实现类(两者均为包级私有). <hr>
 * <p><b>设计要点:</b></p>
 * <ul>
 *     <li><b>ErrorCode ≠ Tag</b> — {@link ErrorCode} 用于前后端协议(HTTP 状态码 + 业务码), 
 *         {@link #tag()} 用于运维分类(格式 {@code WHERE_WHAT_ACTION}, 如 {@code "AUTH_LOGIN_USER_NOT_FOUND"}). <br>
 *         <span style="color: f84b4b">禁止将 {@code ErrorCode.name()} 直接作为 tag 传入. </span></li>
 *     <li><b>Factory 语义</b> — 工厂方法的 {@code factory} 参数用于将 message 转换为被包装的 {@link Throwable}, 
 *         使异常链保留具体原因类型(如 {@link java.util.NoSuchElementException}、{@link IllegalArgumentException}). </li>
 * </ul>
 *
 * @param <T> 实现此接口的具体异常类型(CRTP 约束：{@code T extends StructuredException & IBusinessException<T>>})
 * @see StructuredException
 * @see ErrorCode
 * @see HolderException
 * @see DataHolderException
 */
public sealed interface IBusinessException<T extends StructuredException & IBusinessException<T>> extends IDetailedThrowable<T, Object>
{
    /**
     * 返回此业务异常的 {@link ErrorCode}, 用于确定 HTTP 响应状态码和前端错误码. 
     *
     * @return 非空错误码枚举
     */
    @NotNull ErrorCode getErrorCode();
    //region 静态工厂

    /**
     * 创建携带 <b>额外上下文数据</b> 的业务异常. <br>
     * {@code data} 用于附加失败现场的领域对象(如非法输入的实体、缺失记录的 ID), 
     * 通过 {@link IDetailedThrowable#causeData()} 暴露给上层恢复逻辑. 
     *
     * @param errorCode 错误码枚举
     * @param message   异常描述消息(将作为被包装异常的消息)
     * @param factory   将 message 转为被包装 {@link Throwable} 的工厂函数
     * @param tag       业务语义标签, 格式 {@code WHERE_WHAT_ACTION}(如 {@code "DIARY_READ_RECORD_NOT_FOUND"})
     * @param data      附加失败上下文数据
     * @return 业务异常实例
     * @throws IllegalArgumentException 当 {@code tag} 等于 {@code errorCode.name()}, 或 {@code data} 为 {@link ErrorCode} 实例时抛出
     */
    static @NotNull IBusinessException<?> of(
        @NotNull ErrorCode errorCode,
        @NotNull String message,
        @NotNull Function<String, Throwable> factory,
        @NotNull String tag,
        @NotNull Object data
    ) { return new DataHolderException(errorCode, message, factory, tag, data); }

    /**
     * 创建 <b>不携带额外数据</b> 的业务异常. <br>
     * 适用于仅需错误码 + 标签即可定位问题的场景. 
     *
     * @param errorCode 错误码枚举
     * @param message   异常描述消息(将作为被包装异常的消息)
     * @param factory   将 message 转为被包装 {@link Throwable} 的工厂函数
     * @param tag       业务语义标签, 格式 {@code WHERE_WHAT_ACTION}(如 {@code "AUTH_LOGIN_PASSWORD_MISMATCH"})
     * @return 业务异常实例
     * @throws IllegalArgumentException 当 {@code tag} 等于 {@code errorCode.name()} 时抛出
     */
    static @NotNull IBusinessException<?> of(
        @NotNull ErrorCode errorCode,
        @NotNull String message,
        @NotNull Function<String, Throwable> factory,
        @NotNull String tag
    ) { return new HolderException(errorCode, message, factory, tag); }
    //endregion
}

/**
 * <b>不携带额外数据的业务异常实现. </b><br>
 * 包级私有, 外部代码通过 {@link IBusinessException#of(ErrorCode, String, Function, String)} 间接创建. <hr>
 * <p><b>构造守卫:</b></p>
 * <ul>
 *     <li>禁止 {@code tag} 等于 {@code errorCode.name()} — 避免 tag 沦为 ErrorCode 的别名. </li>
 *     <li>{@link #causeData()} 返回 {@code errorCode} 自身作为降级数据. </li>
 * </ul>
 */
final class HolderException extends StructuredException implements IBusinessException<HolderException>, IDetailedThrowable<HolderException, Object>
{
    private final @NotNull ErrorCode errorCode;

    HolderException(
        @NotNull ErrorCode errorCode,
        @NotNull String message,
        @NotNull Function<String, Throwable> factory,
        @NotNull String tag
    )
    {
        super(factory.apply(message), tag);

        if(errorCode.name().equals(tag))
            throw new IllegalArgumentException("Contract Violated: Tag of this exception is valueless.");

        this.errorCode = errorCode;
    }

    @Override public @NotNull ErrorCode getErrorCode() { return errorCode; }

    @Override public @NotNull Object causeData() { return errorCode; }
}

/**
 * <b>携带额外上下文数据的业务异常实现. </b><br>
 * 包级私有, 外部代码通过 {@link IBusinessException#of(ErrorCode, String, Function, String, Object)} 间接创建. <hr>
 * <p><b>构造守卫:</b></p>
 * <ul>
 *     <li>禁止 {@code tag} 等于 {@code errorCode.name()} — 避免 tag 沦为 ErrorCode 的别名. </li>
 *     <li>禁止将 {@link ErrorCode} 实例作为 {@code data} 传入 — 防止敏感枚举泄漏到响应负载. </li>
 * </ul>
 */
final class DataHolderException extends StructuredException implements IDetailedThrowable<DataHolderException, Object>, IBusinessException<DataHolderException>
{
    private final @NotNull Object    data;
    private final @NotNull ErrorCode errorCode;

    DataHolderException(
        @NotNull ErrorCode errorCode,
        @NotNull String message,
        @NotNull Function<String, Throwable> factory,
        @NotNull String tag,
        @NotNull Object data
    )
    {
        super(factory.apply(message), tag);

        if(data instanceof ErrorCode)
            throw new IllegalArgumentException("Contract Violated: Wrapping ErrorCode is not allowed.");

        if(errorCode.name().equals(tag))
            throw new IllegalArgumentException("Contract Violated: Tag of this exception is valueless.");

        this.data = data;
        this.errorCode = errorCode;
    }

    @Override public @NotNull Object causeData() { return data; }

    @Override public @NotNull ErrorCode getErrorCode() { return errorCode; }
}
