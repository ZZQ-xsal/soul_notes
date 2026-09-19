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
import kurvcygnus.soulnotes.exception.ErrorCode;
import kurvcygnus.soulnotes.exception.IBusinessException;
import kurvcygnus.soulnotes.utils.PrintUtils;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * 情绪日记 REST 资源, 面向 STUDENT 角色提供日记 CRUD 与情绪天气预报数据端点.
 * <ul>
 *     <li>{@code POST   /api/v1/diaries} — 创建日记</li>
 *     <li>{@code GET    /api/v1/diaries} — 分页列表</li>
 *     <li>{@code GET    /api/v1/diaries/{id}} — 单条详情</li>
 *     <li>{@code DELETE /api/v1/diaries/{id}} — 删除</li>
 *     <li>{@code GET    /api/v1/diaries/weather} — 情绪天气预报数据</li>
 * </ul>
 *
 * @implNote userId 从 JWT subject 解析 (认证由全局机制保证), 所有读写均限定当前用户自己的日记.
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
     * 创建日记并触发 AI 情感分析 (异步回写, 不阻塞创建响应).
     *
     * @param req 创建请求 (content/audioData 至少一项)
     * @return 已创建的日记响应; AI 分析失败不影响本响应, 仅缺失 analysisResult
     * @throws IBusinessException content 与 audioData 均为空, 或 audioData 非法 Base64 时 (BAD_REQUEST)
     */
    @POST
    public @NotNull Uni<ApiResponse<DiaryResponse>> create(@NotNull DiaryCreateRequest req)
    {
        return diaryService.create(req, currentUserId()).
            map(ApiResponse::success);
    }

    /**
     * 分页查询当前用户的日记列表 (创建时间倒序).
     *
     * @param query 分页/过滤参数 (page 默认 1, size 默认 20, 非法值钳位)
     * @return 当前页日记响应列表 (可能为空)
     */
    @GET
    public @NotNull Uni<ApiResponse<List<DiaryResponse>>> list(@BeanParam @NotNull DiaryListQuery query)
    {
        return diaryService.listByUser(query, currentUserId()).
            map(ApiResponse::success);
    }

    /**
     * 查询单条日记详情.
     *
     * @param id 日记 ID
     * @return 日记响应
     * @throws IBusinessException 日记不存在或不属于当前用户时 (DIARY_NOT_FOUND, 越权与缺失同一错误码,
     *                            不向客户端泄露他人日记的存在性)
     */
    @GET @Path("/{id}")
    public @NotNull Uni<ApiResponse<DiaryResponse>> getById(@PathParam("id") long id)
    {
        return diaryService.getById(id, currentUserId()).
            map(ApiResponse::success);
    }

    /**
     * 删除日记 (硬删除).
     *
     * @param id 日记 ID
     * @return 空载荷的成功响应
     * @throws IBusinessException 日记不存在或不属于当前用户时 (DIARY_NOT_FOUND)
     */
    @DELETE @Path("/{id}")
    public @NotNull Uni<ApiResponse<Void>> delete(@PathParam("id") long id)
    {
        return diaryService.delete(id, currentUserId()).
            map(v -> ApiResponse.success());
    }

    /**
     * 获取情绪天气预报数据: 指定日期范围内按日聚合的情感均值与天气类型.
     *
     * @param startDate 开始日期 (ISO 格式 yyyy-MM-dd, 含)
     * @param endDate   结束日期 (ISO 格式 yyyy-MM-dd, 含)
     * @return 按天排列的天气预报 VO 列表 (无日记的日期不产出条目)
     * @throws IBusinessException startDate/endDate 非法日期格式时 (DIARY_WEATHER_DATE_INVALID, 统一错误负载)
     */
    @GET @Path("/weather")
    public @NotNull Uni<ApiResponse<List<EmotionWeatherVo>>> getWeather(
        @QueryParam("startDate") @NotNull String startDate,
        @QueryParam("endDate") @NotNull String endDate
    )
    {
        final var range = parseDateRange(startDate, endDate);
        return emotionWeatherService.getWeatherData(currentUserId(), range.start(), range.end()).map(ApiResponse::success);
    }

    /**
     * 解析天气查询的日期区间, 非法格式统一转为业务异常负载.
     *
     * @param startDate 开始日期 (ISO 格式 yyyy-MM-dd)
     * @param endDate   结束日期 (ISO 格式 yyyy-MM-dd)
     * @return 解析后的起止日期
     * @throws IBusinessException 任一日期非 ISO 格式时 (DIARY_WEATHER_DATE_INVALID)
     */
    static @NotNull DateRange parseDateRange(@NotNull String startDate, @NotNull String endDate)
    {
        try
        {
            return new DateRange(LocalDate.parse(startDate), LocalDate.parse(endDate));
        }
        catch(DateTimeParseException e)
        {
            //* 日期参数属用户输入, 统一走业务异常负载; 裸 DateTimeParseException 会绕过全局映射.
            throw IBusinessException.of(
                ErrorCode.BAD_REQUEST,
                PrintUtils.quickFormat("日期格式无效: \"{}\" / \"{}\", 须为 yyyy-MM-dd", startDate, endDate),
                msg -> new DateTimeParseException(msg, startDate, 0),
                "DIARY_WEATHER_DATE_INVALID"
            ).asException();
        }
    }

    /** 天气查询的日期区间. */
    record DateRange(@NotNull LocalDate start, @NotNull LocalDate end) {}
}