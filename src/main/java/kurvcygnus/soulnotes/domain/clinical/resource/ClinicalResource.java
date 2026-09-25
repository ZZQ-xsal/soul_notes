package kurvcygnus.soulnotes.domain.clinical.resource;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import kurvcygnus.soulnotes.domain.clinical.dto.AssessmentVo;
import kurvcygnus.soulnotes.domain.clinical.dto.StatsSummary;
import kurvcygnus.soulnotes.domain.clinical.service.ClinicalAssessmentService;
import kurvcygnus.soulnotes.dto.ApiResponse;
import kurvcygnus.soulnotes.dto.PageRequest;
import kurvcygnus.soulnotes.exception.ErrorCode;
import kurvcygnus.soulnotes.exception.IBusinessException;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 咨询员工作台 REST 资源, 面向 COUNSELOR/ADMIN 提供副医生评估的消费端.
 * <ul>
 *     <li>{@code GET /api/v1/clinical/assessments} — 风险队列 (等级/时间窗过滤, 倒序分页)</li>
 *     <li>{@code GET /api/v1/clinical/students/{userId}/assessments} — 学生时间线</li>
 *     <li>{@code GET /api/v1/clinical/stats/summary} — 聚合统计</li>
 * </ul>
 *
 * @implNote 脱敏在 Service 层统一出口完成 (RevealPolicy), 本资源零身份逻辑;
 *           聊天正文永不暴露 (Spec §6 伦理红线).
 * @since 1.2.0
 */
@Path(ApiEndpointConstants.CLINICAL_BASE)
@RolesAllowed({UserRole.ROLE_COUNSELOR, UserRole.ROLE_ADMIN})
public final class ClinicalResource
{
    //* 等级过滤白名单: 非白名单值直接 400 (防注入与脏查询语义).
    private static final @NotNull Set<String> LEVELS = Set.of("YELLOW", "RED");

    @Inject ClinicalAssessmentService assessmentService;

    @Inject SecurityIdentity securityIdentity;

    //* 当前身份仅用于审计日志, 数据权限由角色注解保证 (咨询员可看全部学生 — 工作台语义).
    @SuppressWarnings("unused")//! 预留审计日志挂点 (UserRole 同款保留位), 工作台操作审计任务接线时消费.
    private @NotNull String currentPrincipal() { return securityIdentity.getPrincipal().getName(); }

    /**
     * 风险队列.
     *
     * @param level 等级过滤 (YELLOW/RED, 空为全部)
     * @param days  时间窗天数 (<=0 不限)
     * @param page  分页参数
     * @return 评估视图列表
     */
    @GET @Path("/assessments")
    public @NotNull Uni<ApiResponse<List<AssessmentVo>>> queue(
        @QueryParam("level") @Nullable String level,
        @QueryParam("days") @DefaultValue("7") int days,
        @BeanParam @NotNull PageRequest page
    )
    {
        final var normalized = level == null || level.isBlank() ? null : level.strip().toUpperCase(Locale.ROOT);
        if(normalized != null && !LEVELS.contains(normalized))
            return Uni.createFrom().failure(
                IBusinessException.of(
                    ErrorCode.BAD_REQUEST,
                    "风险等级仅允许 YELLOW 或 RED",
                    IllegalArgumentException::new,
                    "CLINICAL_QUEUE_LEVEL_INVALID"
                ).asException()
            );
        //* @BeanParam 缺席参数不走 setter (直设 int 默认值), 消费前须经 normalize 钳位收敛.
        return assessmentService.listAssessments(normalized, days, page.normalize()).map(ApiResponse::success);
    }

    /**
     * 学生时间线.
     */
    @GET @Path("/students/{userId}/assessments")
    public @NotNull Uni<ApiResponse<List<AssessmentVo>>> timeline(
        @PathParam("userId") @NotNull String userId,
        @BeanParam @NotNull PageRequest page
    )
    {
        final UUID studentId;
        try { studentId = UUID.fromString(userId); }
        catch(IllegalArgumentException e)
        {
            return Uni.createFrom().failure(
                IBusinessException.of(
                    ErrorCode.BAD_REQUEST, "学生 ID 非法", IllegalArgumentException::new, "CLINICAL_STUDENT_ID_INVALID"
                ).asException()
            );
        }
        //* @BeanParam 缺席参数不走 setter (直设 int 默认值), 消费前须经 normalize 钳位收敛.
        return assessmentService.listByStudent(studentId, page.normalize()).map(ApiResponse::success);
    }

    /**
     * 聚合统计.
     */
    @GET @Path("/stats/summary")
    public @NotNull Uni<ApiResponse<StatsSummary>> stats(@QueryParam("days") @DefaultValue("7") int days)
        { return assessmentService.statsSummary(days).map(ApiResponse::success); }
}
