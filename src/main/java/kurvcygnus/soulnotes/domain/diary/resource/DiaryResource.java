package kurvcygnus.soulnotes.domain.diary.resource;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import kurvcygnus.soulnotes.domain.diary.dto.DiaryCreateRequest;
import kurvcygnus.soulnotes.domain.diary.dto.DiaryListQuery;
import kurvcygnus.soulnotes.domain.diary.dto.DiaryResponse;
import kurvcygnus.soulnotes.domain.diary.dto.EmotionWeatherVo;
import kurvcygnus.soulnotes.domain.diary.service.DiaryService;
import kurvcygnus.soulnotes.domain.diary.service.EmotionWeatherService;
import kurvcygnus.soulnotes.dto.ApiResponse;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * <b>情绪日记 REST 资源</b>
 * <ul>
 *     <li>{@code POST   /api/v1/diaries} — 创建日记</li>
 *     <li>{@code GET    /api/v1/diaries} — 分页列表</li>
 *     <li>{@code GET    /api/v1/diaries/{id}} — 单条详情</li>
 *     <li>{@code DELETE /api/v1/diaries/{id}} — 删除</li>
 *     <li>{@code GET    /api/v1/diaries/weather} — 情绪天气预报数据</li>
 * </ul>
 * @since 1.0
 */
@Path(ApiEndpointConstants.DIARY_BASE)
@RolesAllowed(UserRole.ROLE_STUDENT)
public final class DiaryResource
{
    @Inject DiaryService diaryService;

    @Inject EmotionWeatherService emotionWeatherService;

    @Inject SecurityIdentity securityIdentity;

    //* 从 SecurityIdentity 提取当前用户 ID (即 JWT subject).
    private @NotNull UUID currentUserId() { return UUID.fromString(securityIdentity.getPrincipal().getName()); }

    /**
     * <span style="color: 95cc6d">创建日记 (会触发 AI 分析).</span>
     */
    @POST
    public @NotNull Uni<ApiResponse<DiaryResponse>> create(@NotNull DiaryCreateRequest req)
    {
        return diaryService.create(req, currentUserId()).
            map(ApiResponse::success);
    }

    /**
     * <span style="color: 95cc6d">分页查询日记列表.</span>
     */
    @GET
    public @NotNull Uni<ApiResponse<List<DiaryResponse>>> list(@BeanParam @NotNull DiaryListQuery query)
    {
        return diaryService.listByUser(query, currentUserId()).
            map(ApiResponse::success);
    }

    /**
     * <span style="color: 95cc6d">查询单条日记详情.</span>
     */
    @GET @Path("/{id}")
    public @NotNull Uni<ApiResponse<DiaryResponse>> getById(@PathParam("id") long id)
    {
        return diaryService.getById(id, currentUserId()).
            map(ApiResponse::success);
    }

    /**
     * <span style="color: f84b4b">删除日记.</span>
     */
    @DELETE @Path("/{id}")
    public @NotNull Uni<ApiResponse<Void>> delete(@PathParam("id") long id)
    {
        return diaryService.delete(id, currentUserId()).
            map(v -> ApiResponse.success());
    }

    /**
     * <span style="color: 95cc6d">获取情绪天气预报数据.</span>
     */
    @GET @Path("/weather")
    public @NotNull Uni<ApiResponse<List<EmotionWeatherVo>>> getWeather(
        @QueryParam("startDate") @NotNull String startDate,
        @QueryParam("endDate") @NotNull String endDate
    )
    {
        return emotionWeatherService.getWeatherData(
            currentUserId(),
            LocalDate.parse(startDate),
            LocalDate.parse(endDate)
        ).map(ApiResponse::success);
    }
}