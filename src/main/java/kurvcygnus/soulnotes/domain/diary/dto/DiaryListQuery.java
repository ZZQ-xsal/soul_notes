package kurvcygnus.soulnotes.domain.diary.dto;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 日记列表查询参数, 支持分页与时间范围过滤 (JAX-RS {@code @BeanParam} 载体).
 * <p>默认第 1 页, 每页 20 条.</p>
 * @since 1.0
 */
public final class DiaryListQuery
{
    @QueryParam("page")       @DefaultValue("1")  private int page;
    @QueryParam("size")       @DefaultValue("20") private int size;
    @QueryParam("startDate")  private @Nullable String startDate;
    @QueryParam("endDate")    private @Nullable String endDate;

    public int getPage() { return page; }

    public int getSize() { return size; }

    /**
     * 计算当前分页对应的 SQL {@code OFFSET} 值.
     *
     * @return {@code (page - 1) * size}; page/size 经 setter 钳位后恒 >= 0
     */
    public int getOffset() { return (page - 1) * size; }

    public @Nullable String getStartDate() { return startDate; }

    public @Nullable String getEndDate() { return endDate; }

    //* 兜底规范化: @BeanParam 场景下 RESTEasy Reactive 对参数值直写字段而不调用 setter (实测 Quarkus 3.36:
    //* 缺席参数应用 {@code @DefaultValue}, 但在场 0/负值绕过 setter 钳位直达消费点), 直接消费会致
    //* OFFSET 为负或 LIMIT 为 0 — 消费点 (Service 边界) 须先经本方法收敛 (PageRequest#normalize 同款语义).
    /**
     * 就地规范化分页参数: 0 视为未传参, 收敛为类文档承诺的默认值 (第 1 页 / 每页 20 条, 与字段 {@code @DefaultValue} 同源);
     * 其余越界值 (负值等) 经 setter 钳位收敛 (page >= 1, size >= 1).
     * @return {@code this} (链式调用)
     * @since 1.2.0
     */
    public @NotNull DiaryListQuery normalize() { setPage(page == 0 ? 1 : page); setSize(size == 0 ? 20 : size); return this; }

    //* 边界 clamp: page 最小为 1, size 最小为 1.
    public void setPage(int page) { this.page = Math.max(page, 1); }

    public void setSize(int size) { this.size = Math.max(size, 1); }

    public void setStartDate(@Nullable String startDate) { this.startDate = startDate; }

    public void setEndDate(@Nullable String endDate) { this.endDate = endDate; }
}