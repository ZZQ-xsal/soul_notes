package kurvcygnus.soulnotes.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link ClinicalOutputSplitter} soulnotes 注释块拆流单元测试</b>
 * <p>契约开启时 AI 回复末尾携带 {@code <!--soulnotes {...}-->} 结构化块, 拆流器宽容匹配
 * 格式变体并取最后一个块; JSON 解析失败或无块时整条回复原样透传 (优雅降级, 剥离是主机制,
 * 注释在 markdown 渲染下不可见仅是兜底, 两者缺一不可).</p>
 * @since 2.0
 */
class ClinicalOutputSplitterTest
{
    @BeforeAll
    @SuppressWarnings("InstantiationOfUtilityClass")//! JsonUtils 为 final 全静态成员类, IDE 误报实例化; 构造器正是 CDI 桥接注入入口.
    static void initMapper()
    {
        //* 纯单元测试无 CDI 容器, 手动构造 mapper (拆流器经 JsonUtils 解析 payload).
        new JsonUtils(new ObjectMapper());
    }

    //region 合法块
    @Test void split_LegalBlock_StripsTextAndParsesPayload()
    {
        final var result = ClinicalOutputSplitter.split(
            "今天辛苦了<!--soulnotes {\"tags\": [\"疲惫\"], \"riskLevel\": \"NONE\", \"summary\": \"ok\"}-->");
        assertEquals("今天辛苦了", result.text());
        final var payload = result.payload();
        assertNotNull(payload);
        assertEquals("NONE", payload.get("riskLevel").asText());
        assertEquals("疲惫", payload.get("tags").get(0).asText());
    }

    @Test void split_EmptyTagsBlock_ParsesFine() //* 契约要求无可提取信息时也输出空 tags 块
    {
        final var result = ClinicalOutputSplitter.split(
            "晚安<!--soulnotes {\"tags\": [], \"riskLevel\": \"NONE\", \"summary\": \"\"}-->");
        assertEquals("晚安", result.text());
        final var payload = result.payload();
        assertNotNull(payload);
        assertTrue(payload.get("tags").isEmpty());
    }

    @Test void split_NestedBracesJson_CapturesFullPayload()
    {
        final var result = ClinicalOutputSplitter.split(
            "正文<!--soulnotes {\"tags\": [\"a\"], \"extra\": {\"k\": \"v\"}}-->");
        assertEquals("正文", result.text());
        final var payload = result.payload();
        assertNotNull(payload);
        assertEquals("v", payload.get("extra").get("k").asText());
    }

    @Test void split_TrailingWhitespaceAfterBlock_IsStripped()
    {
        final var result = ClinicalOutputSplitter.split(
            "正文<!--soulnotes {\"riskLevel\": \"NONE\"}-->  \n");
        assertEquals("正文", result.text());
        assertNotNull(result.payload());
    }

    @Test void split_VariantDelimiters_Tolerated() //* 容忍 <!--- 多横线与 --!> 感叹号变体
    {
        final var result = ClinicalOutputSplitter.split(
            "正文<!---soulnotes {\"riskLevel\": \"YELLOW\"}--!>  \n");
        assertEquals("正文", result.text());
        final var payload = result.payload();
        assertNotNull(payload);
        assertEquals("YELLOW", payload.get("riskLevel").asText());
    }

    @Test void split_MultipleBlocks_TakesLastOne()
    {
        final var result = ClinicalOutputSplitter.split(
            "开头<!--soulnotes {\"riskLevel\": \"RED\"}-->中间<!--soulnotes {\"riskLevel\": \"YELLOW\"}-->");
        assertEquals("开头<!--soulnotes {\"riskLevel\": \"RED\"}-->中间", result.text());
        final var payload = result.payload();
        assertNotNull(payload);
        assertEquals("YELLOW", payload.get("riskLevel").asText());
    }
    //endregion

    //region 优雅降级
    @Test void split_NoBlock_PassesThroughVerbatim() //! 无块时必须逐字节原样返回, 不可有任何加工
    {
        final var reply = "我就是一条普通回复\n";
        final var result = ClinicalOutputSplitter.split(reply);
        assertEquals(reply, result.text());
        assertNull(result.payload());
    }

    @Test void split_BrokenJsonJson_PassesWholeReplyThrough() //! JSON 解析失败时整条回复 (含坏块) 原样透传
    {
        final var reply = "抱歉<!--soulnotes {tags: }-->";
        final var result = ClinicalOutputSplitter.split(reply);
        assertEquals(reply, result.text());
        assertNull(result.payload());
    }
    //endregion
}
