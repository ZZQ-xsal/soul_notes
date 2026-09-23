package kurvcygnus.soulnotes.domain.diary.entity;

import kurvcygnus.soulnotes.config.ReactiveJsonStringJdbcType;
import org.hibernate.annotations.JdbcType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link MoodDiary} 的单元测试</b>
 * <p>验证实体字段赋值与静态查询方法签名正确性.</p>
 *
 * @author Claude Code
 * @since 1.0
 */
class MoodDiaryTest
{
    @Test
    void entity_ShouldAcceptAllFields()
    {
        final var diary = new MoodDiary();
        diary.id = 1L;
        diary.userId = UUID.randomUUID();
        diary.content = "测试日记内容";
        diary.audioUrl = "https://audio.example.com/test.wav";
        diary.analysisResult = "{\"positive\":0.8,\"negative\":0.2}";
        diary.createdAt = Instant.now();

        assertEquals(1L, diary.id);
        assertNotNull(diary.userId);
        assertEquals("测试日记内容", diary.content);
        assertNotNull(diary.audioUrl);
        assertNotNull(diary.analysisResult);
        assertNotNull(diary.createdAt);
    }

    @Test
    void entity_OptionalFields_ShouldBeNullable()
    {
        final var diary = new MoodDiary();
        diary.id = 2L;
        diary.userId = UUID.randomUUID();
        diary.createdAt = Instant.now();

        assertNull(diary.content);
        assertNull(diary.audioUrl);
        assertNull(diary.analysisResult);
    }

    @Test
    void analysisResult_Field_ShouldDeclareJsonJdbcType() throws NoSuchFieldException
    {
        //* 声明字符串型 JSONB 映射后, 新写入为真 JSON (jsonb_typeof = object), 存量字符串标量行读出仍可解析;
        //* 缺失时 Hibernate Reactive 把 JSON 文本再包一层, 存成字符串标量 (jsonb_typeof = string) 的双重编码形态.
        final var field = MoodDiary.class.getDeclaredField("analysisResult");
        final var jdbcType = field.getAnnotation(JdbcType.class);

        assertNotNull(jdbcType, "analysisResult 字段缺少 @JdbcType 声明");
        assertEquals(ReactiveJsonStringJdbcType.class, jdbcType.value());
    }

    @Test
    void findByUserAndDateRange_ShouldExistAsStaticMethod()
    {
        //* 验证静态查询方法的签名存在 (不会抛出 NoSuchMethodError).
        final var methods = MoodDiary.class.getDeclaredMethods();
        assertTrue(
            java.util.Arrays.stream(methods).anyMatch(m -> m.getName().equals("findByUserAndDateRange"))
        );
    }

    @Test
    void findByUserFiltered_ShouldExistAsStaticMethod()
    {
        //* 验证静态查询方法的签名存在 (不会抛出 NoSuchMethodError).
        //* findByUserId 已随日期筛选接入拆除 (其唯一消费方 listByUser 改走 findByUserFiltered(null, null) 全量形态).
        final var methods = MoodDiary.class.getDeclaredMethods();
        assertTrue(
            java.util.Arrays.stream(methods).anyMatch(m -> m.getName().equals("findByUserFiltered"))
        );
    }
}