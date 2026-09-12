package kurvcygnus.soulnotes.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import kurvcygnus.soulnotes.exception.ErrorCode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * <b>统一 JSON 响应外壳</b>
 * <ul>
 *     <li>成功: {@code {code: 0, message: "success", data: ...}}</li>
 *     <li>失败: {@code {code: xxx, message: "错误描述", data: null}}</li>
 * </ul>
 * @since 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ApiResponse<T>
{
    public final int code;
    public final @NotNull String message;
    public final @Nullable T data;

    private ApiResponse(int code, @NotNull String message, @Nullable T data)
    {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    //region 工厂方法
    public static <T> @NotNull ApiResponse<T> success(@Nullable T data) { return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data); }

    public static <T> @NotNull ApiResponse<T> success() { return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null); }

    public static <T> @NotNull ApiResponse<T> error(@NotNull ErrorCode errorCode)
        { return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null); }
    //endregion
}