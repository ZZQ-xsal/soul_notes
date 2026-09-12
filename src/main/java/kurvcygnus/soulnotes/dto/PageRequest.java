package kurvcygnus.soulnotes.dto;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

/**
 * <b>分页查询通用参数</b>
 * <ul>
 *     <li>用于 REST 查询方法中接收分页与排序参数</li>
 *     <li>默认第 1 页, 每页 20 条</li>
 * </ul>
 * @since 1.0
 */
public final class PageRequest
{
    @QueryParam("page") @DefaultValue("1")  private int page;
    @QueryParam("size") @DefaultValue("20") private int size;

    public int getPage() { return page; }

    public int getSize() { return size; }

    /**
     * <span style="color: 95cc6d">获取 {@code OFFSET} 值, 用于 SQL 分页.</span>
     */
    public int getOffset() { return (page - 1) * size; }

    //! 允许 Resource 层通过 Builder 或直接 setter 构造查询对象.
    //* 边界 clamp: page 最小为 1, size 最小为 1, 防止 SQL OFFSET 为负或 LIMIT 为 0.
    public void setPage(int page) { this.page = Math.max(page, 1); }

    public void setSize(int size) { this.size = Math.max(size, 1); }
}