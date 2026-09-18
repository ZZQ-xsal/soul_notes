package kurvcygnus.soulnotes.domain.diary.dto;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import org.jetbrains.annotations.Nullable;

/**
 * 日记列表查询参数, 支持分页与时间范围过滤 (JAX-RS {@code @BeanParam} 载体).
 *
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

    //* 边界 clamp: page 最小为 1, size 最小为 1.
    public void setPage(int page) { this.page = Math.max(page, 1); }

    public void setSize(int size) { this.size = Math.max(size, 1); }

    public void setStartDate(@Nullable String startDate) { this.startDate = startDate; }

    public void setEndDate(@Nullable String endDate) { this.endDate = endDate; }
}