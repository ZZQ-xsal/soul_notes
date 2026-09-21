package kurvcygnus.soulnotes.dto;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import org.jetbrains.annotations.NotNull;

/**
 * 分页查询通用参数, 由 REST 查询方法经 {@code @QueryParam} 直接绑定接收分页参数.
 * <p>默认第 1 页, 每页 20 条.</p>
 * @since 1.0
 */
public final class PageRequest
{
    @QueryParam("page") @DefaultValue("1")  private int page;
    @QueryParam("size") @DefaultValue("20") private int size;

    /**
     * @return 页码, 从 1 起
     */
    public int getPage() { return page; }

    /**
     * @return 每页条数
     */
    public int getSize() { return size; }

    /**
     * 获取 {@code OFFSET} 值, 用于 SQL 分页.
     * @return {@code (page - 1) * size}
     */
    public int getOffset() { return (page - 1) * size; }

    //* 兜底规范化: @BeanParam 场景下参数缺席时 RESTEasy Reactive 不应用 @DefaultValue 也不调用 setter
    //* (字段直设为 int 默认值 0), 直接消费会致 OFFSET 为负或 LIMIT 为 0 — 消费点 (Resource 边界) 须先经本方法收敛.
    /**
     * 就地规范化分页参数: 0 视为未传参, 收敛为类文档承诺的默认值 (第 1 页 / 每页 20 条, 与字段 {@code @DefaultValue} 同源);
     * 其余越界值 (负值等) 经 setter 钳位收敛 (page >= 1, size >= 1).
     * @return {@code this} (链式调用)
     */
    public @NotNull PageRequest normalize() { setPage(page == 0 ? 1 : page); setSize(size == 0 ? 20 : size); return this; }

    //! 允许 Resource 层通过 Builder 或直接 setter 构造查询对象.
    //* 边界 clamp: page 最小为 1, size 最小为 1, 防止 SQL OFFSET 为负或 LIMIT 为 0.

    /**
     * 设置页码.
     * @param page 页码, 小于 1 时收敛为 1, 保证 SQL {@code OFFSET} 不为负
     */
    public void setPage(int page) { this.page = Math.max(page, 1); }

    /**
     * 设置每页条数.
     * @param size 条数, 小于 1 时收敛为 1, 保证 SQL {@code LIMIT} 不为 0
     */
    public void setSize(int size) { this.size = Math.max(size, 1); }
}