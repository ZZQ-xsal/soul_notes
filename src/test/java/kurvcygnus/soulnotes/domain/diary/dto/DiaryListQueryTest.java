package kurvcygnus.soulnotes.domain.diary.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link DiaryListQuery} 的单元测试</b>
 * <p>验证分页参数计算与日期范围设置.</p>
 *
 * @author Claude Code
 * @since 1.0
 */
class DiaryListQueryTest
{
    @Test void defaultValues_ShouldBeJavaDefaults()
    {
        final var query = new DiaryListQuery();
        //* @DefaultValue 仅在 JAX-RS 容器中生效, 直接 new 时字段为 Java 默认值 0.
        assertEquals(0, query.getPage());
        assertEquals(0, query.getSize());
        assertEquals(0, query.getOffset());
    }

    @Test void offsetCalculation_ShouldWorkCorrectly()
    {
        final var query = new DiaryListQuery();
        query.setPage(2);
        query.setSize(15);
        assertEquals(15, query.getOffset());
    }

    @Test void negativePage_ShouldClampToOne()
    {
        //* page 最小为 1, 负数应 clamp 到 1, offset 应为 0.
        final var query = new DiaryListQuery();
        query.setPage(-1);
        query.setSize(20);
        assertEquals(1, query.getPage());
        assertEquals(0, query.getOffset());
    }

    @Test void zeroSize_ShouldProduceOffsetZero()
    {
        final var query = new DiaryListQuery();
        query.setPage(1);
        query.setSize(0);
        //* size 被 clamp 到 1, offset = (1-1)*1 = 0.
        assertEquals(1, query.getSize());
        assertEquals(0, query.getOffset());
    }

    @Test void dateRange_ShouldBeSettable()
    {
        final var query = new DiaryListQuery();
        query.setStartDate("2026-01-01");
        query.setEndDate("2026-06-19");
        assertEquals("2026-01-01", query.getStartDate());
        assertEquals("2026-06-19", query.getEndDate());
    }

    @Test void dateRange_WithNull_ShouldBeAccepted()
    {
        final var query = new DiaryListQuery();
        query.setStartDate(null);
        query.setEndDate(null);
        assertNull(query.getStartDate());
        assertNull(query.getEndDate());
    }

    @Test void normalize_ZeroFields_ShouldApplyContractDefault()
    {
        //* normalize 契约与 PageRequest 同语义: 0 (缺席参数的字段直设值) 视为未传参, 收敛为文档默认 第 1 页/每页 20.
        final var query = new DiaryListQuery();
        query.normalize();
        assertEquals(1, query.getPage());
        assertEquals(20, query.getSize());
        assertEquals(0, query.getOffset());
    }

    @Test void normalize_NegativeFields_ShouldClampToOne()
    {
        //* 负值经 setter 钳位: page >= 1, size >= 1, offset 恒 >= 0.
        final var query = new DiaryListQuery();
        query.setPage(-5);
        query.setSize(-5);
        query.normalize();
        assertEquals(1, query.getPage());
        assertEquals(1, query.getSize());
        assertEquals(0, query.getOffset());
    }

    @Test void normalize_ValidFields_ShouldBeUntouched()
    {
        //* 合法在界值不收敛不改写 (返回 this 链式语义).
        final var query = new DiaryListQuery();
        query.setPage(2);
        query.setSize(15);
        assertEquals(query, query.normalize());
        assertEquals(2, query.getPage());
        assertEquals(15, query.getSize());
        assertEquals(15, query.getOffset());
    }
}