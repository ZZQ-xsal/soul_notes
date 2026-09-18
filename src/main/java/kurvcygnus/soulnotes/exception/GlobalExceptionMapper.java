package kurvcygnus.soulnotes.exception;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import kurvcygnus.soulnotes.dto.ApiResponse;
import org.jetbrains.annotations.NotNull;

/**
 * 全局结构化异常映射器, 将所有 {@link StructuredException} 统一转换为 {@link ApiResponse} JSON 错误响应.
 * <p>实现 {@link IBusinessException} 的异常按其 {@link ErrorCode} 确定 HTTP 状态码与业务码;
 * 其余 {@link StructuredException} 回退为 500 内部错误.</p>
 * <p>注意: 匹配 {@link StructuredException} 而非 {@link HolderException} — {@link DataHolderException}
 * 是 {@link HolderException} 的兄弟类 (均继承 {@link StructuredException}), 若声明为
 * {@code ExceptionMapper<HolderException>} 将无法捕获后者.</p>
 * @since 1.0
 */
@Provider
public final class GlobalExceptionMapper implements ExceptionMapper<StructuredException>
{
    /**
     * 将结构化异常渲染为 JSON 错误响应.
     * @param exception 被 RESTEasy Reactive 捕获的结构化异常
     * @return 业务异常按其 {@link ErrorCode} 构造的响应; 其余一律 500 + {@code INTERNAL_ERROR} 负载, 永不为 {@code null}
     */
    @Override public @NotNull Response toResponse(@NotNull StructuredException exception)
    {
        if(exception instanceof IBusinessException<?> bizEx)
        {
            final var errorCode = bizEx.getErrorCode();
            return Response.status(errorCode.getHttpStatus()).
                entity(ApiResponse.error(errorCode)).
                build();
        }
        return Response.status(ErrorCode.INTERNAL_ERROR.getHttpStatus()).
            entity(ApiResponse.error(ErrorCode.INTERNAL_ERROR)).
            build();
    }
}
