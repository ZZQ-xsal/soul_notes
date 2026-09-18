package kurvcygnus.soulnotes.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import kurvcygnus.soulnotes.exception.ErrorCode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 统一 JSON 响应外壳.
 * <ul>
 *     <li>成功: {@code {code: 0, message: "success", data: ...}}</li>
 *     <li>失败: {@code {code: xxx, message: "错误描述", data: null}}</li>
 * </ul>
 * <p>序列化时忽略 {@code null} 字段 ({@code NON_NULL}), 故失败形态下 {@code data} 键整体缺席.</p>
 * @param <T> 业务负载类型
 * @since 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ApiResponse<T>
{
    /** 业务状态码: {@code 0} 表示成功, 其余取值见 {@link ErrorCode}. */
    public final int code;

    /** 人读消息: 成功时固定为 {@code "success"}, 失败时为错误描述. */
    public final @NotNull String message;

    /** 业务负载, 失败或无负载时为 {@code null}. */
    public final @Nullable T data;

    private ApiResponse(int code, @NotNull String message, @Nullable T data)
    {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    //region 工厂方法

    /**
     * 构建成功响应, 携带业务负载.
     * @param data 业务负载, 可为 {@code null} (序列化时该键缺席)
     * @param <T>  负载类型
     * @return {@code code=0}, {@code message="success"} 的响应
     */
    public static <T> @NotNull ApiResponse<T> success(@Nullable T data) { return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data); }

    /**
     * 构建成功响应, 不携带业务负载.
     * @param <T> 负载类型
     * @return {@code code=0}, {@code message="success"}, {@code data} 缺席的响应
     */
    public static <T> @NotNull ApiResponse<T> success() { return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null); }

    /**
     * 构建失败响应.
     * @param errorCode 决定 {@code code} 与 {@code message} 的错误码
     * @param <T>       负载类型
     * @return {@code data} 缺席的失败响应
     */
    public static <T> @NotNull ApiResponse<T> error(@NotNull ErrorCode errorCode)
        { return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null); }
    //endregion
}