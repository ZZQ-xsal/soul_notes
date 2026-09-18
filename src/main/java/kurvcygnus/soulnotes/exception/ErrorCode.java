package kurvcygnus.soulnotes.exception;

import org.jetbrains.annotations.NotNull;

/**
 * 统一业务错误码枚举, 所有业务异常经此传递一致的前后端错误信息.
 * <p>业务码格式: {@code HTTP状态码(3位) + 业务域编号(3位) + 具体错误编号(3位)},
 * 高位段与 {@link #getHttpStatus()} 对应, 前端可据前缀直接获知 HTTP 语义.</p>
 * @since 1.0
 */
public enum ErrorCode
{
    //region 通用
    SUCCESS            (200, 0,       "success"),
    BAD_REQUEST        (400, 400000,  "请求参数错误"),
    INTERNAL_ERROR     (500, 500000,  "服务器内部错误"),
    //endregion

    //region 认证 (401xxx / 403xxx)
    AUTH_TOKEN_EXPIRED (401, 401001,  "Token 已过期"),
    AUTH_TOKEN_INVALID (401, 401002,  "Token 无效"),
    AUTH_UNAUTHORIZED  (401, 401003,  "未登录"),
    AUTH_FORBIDDEN     (403, 403001,  "权限不足"),
    //endregion

    //region 用户 (404xxx / 409xxx)
    USER_NOT_FOUND     (404, 404001,  "用户不存在"),
    USERNAME_DUPLICATE (409, 409001,  "用户名已被占用"),
    //endregion

    //region 日记 (40401x)
    DIARY_NOT_FOUND    (404, 404010,  "日记不存在"),
    //endregion

    //region 对话 (40402x)
    SESSION_NOT_FOUND  (404, 404020,  "会话不存在"),
    //endregion

    //region AI/外部服务 (503xxx)
    AI_SERVICE_DOWN    (503, 503001,  "AI 服务暂不可用");
    //endregion

    private final int httpStatus;
    private final int code;
    private final @NotNull String message;

    ErrorCode(int httpStatus, int code, @NotNull String message)
    {
        this.httpStatus = httpStatus;
        this.code       = code;
        this.message    = message;
    }

    /**
     * @return 映射的 HTTP 状态码, 供异常映射器构造响应
     */
    public int getHttpStatus() { return httpStatus; }

    /**
     * @return 业务错误码 (格式见类说明), 前端据此分支处理
     */
    public int getCode()       { return code; }

    /**
     * @return 人读错误描述, 直接进入响应体的 {@code message}
     */
    public @NotNull String getMessage() { return message; }
}