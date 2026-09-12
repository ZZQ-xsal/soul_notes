package kurvcygnus.soulnotes.domain.diary.dto;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import org.jetbrains.annotations.Nullable;

/**
 * <b>日记列表查询参数</b>
 * <p>支持分页和时间范围过滤.</p>
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
     * <span style="color: 95cc6d">获取 {@code OFFSET} 值.</span>
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