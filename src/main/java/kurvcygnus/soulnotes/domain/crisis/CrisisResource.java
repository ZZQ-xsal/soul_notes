package kurvcygnus.soulnotes.domain.crisis;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import kurvcygnus.soulnotes.config.RedisStartupConfig;
import kurvcygnus.soulnotes.dto.ApiResponse;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>危机干预离线兜底接口</b>
 * <ul>
 *     <li>即使 AI 服务或 Redis 不可用, 此端点也能返回热线信息 (使用静态默认值)</li>
 *     <li>无认证要求, 前端可缓存结果用于离线展示</li>
 * </ul>
 * @since 2.0
 */
@Path(ApiEndpointConstants.CRISIS_BASE)
public final class CrisisResource
{
    private final @NotNull RedisStartupConfig redisConfig;

    @Inject
    public CrisisResource(@NotNull RedisStartupConfig redisConfig) { this.redisConfig = redisConfig; }

    /**
     * <span style="color: 95cc6d">获取心理危机干预热线信息.</span>
     * <p>返回当前配置的热线信息, 前端可缓存此结果用于离线展示.</p>
     *
     * @return 热线信息 {@link ApiResponse}
     */
    @GET @Path("/hotline")
    @Produces(MediaType.APPLICATION_JSON)
    public @NotNull Uni<ApiResponse<@NotNull Map<@NotNull String, @NotNull String>>> getHotline()
    {
        return resolveHotline().map(raw ->
            {
                final var parts = raw.split("\\|");

                final var result = new LinkedHashMap<String, String>();
                result.put("name", parts.length >= 1 && !parts[0].isBlank() ? parts[0] : "心理援助热线");
                result.put("primary", parts.length >= 2 ? parts[1] : "400-161-9995");
                result.put("backup", parts.length >= 3 ? parts[2] : "");
                result.put("message", "你不需要独自面对一切, 专业的帮助随时可用。");

                return ApiResponse.success(result);
            }
        );
    }

    //region 辅助方法

    private @NotNull Uni<String> resolveHotline()
    {
        //! resolveHotline() 返回值恒非空 (Redis 故障时已有默认值兜底), 此处直接透传.
        return redisConfig.getHotline();
    }

    //endregion
}
