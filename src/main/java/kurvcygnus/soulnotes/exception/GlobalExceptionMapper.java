package kurvcygnus.soulnotes.exception;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import kurvcygnus.soulnotes.dto.ApiResponse;
import org.jetbrains.annotations.NotNull;

/**
 * <b>全局结构化异常映射器</b>，将所有 {@link StructuredException} 统一转换为 JSON 错误响应。<br>
 * 对实现 {@link IBusinessException} 的异常提取 {@link ErrorCode} 确定 HTTP 状态码；
 * 对其他 {@link StructuredException} 回退为 500 内部错误。<hr>
 * <p><b>注意:</b></p>
 * <ul>
 *     <li>匹配 {@link StructuredException} 而非 {@link HolderException}，因为 {@link DataHolderException}
 *         是 {@link HolderException} 的兄弟类（均继承 {@link StructuredException}），后者无法被 {@code ExceptionMapper<HolderException>} 捕获。</li>
 * </ul>
 * @since 1.1
 */
@Provider
public final class GlobalExceptionMapper implements ExceptionMapper<StructuredException>
{
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
