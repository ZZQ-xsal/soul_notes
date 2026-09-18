package kurvcygnus.soulnotes.ai.retriever;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link KnowledgePackLoader} 单元测试</b>
 * <p>tips.md 块格式: {@code ### 标题} 行开块 → {@code keywords: a,b,c} 行 → 正文至下一块.</p>
 *
 * @author Claude Code
 * @since 2.0
 */
class KnowledgePackLoaderTest
{
    //region 纯解析入口 parse

    @Test void parse_StandardBlock_ShouldParseTip()
    {
        final var markdown = """
            ### 压力管理
            keywords: stress,anxiety,sleep
            适度的压力能提升专注力与效率。
            """;

        assertEquals(
            List.of(new PsychologyTipsRetriever.Tip("stress,anxiety,sleep", "压力管理", "适度的压力能提升专注力与效率。")),
            KnowledgePackLoader.parse(markdown, "test"));
    }

    @Test void parse_MissingKeywordsLine_ShouldSkipBlock()
    {
        //* keywords 行为块必需: 缺失则该块整体跳过并记录, 不产出残缺 Tip.
        final var markdown = """
            ### 坏块无关键词行
            这一块缺少 keywords 行.
            """;

        assertTrue(KnowledgePackLoader.parse(markdown, "test").isEmpty());
    }

    @Test void parse_MixedGoodAndBadBlocks_ShouldSkipOnlyBadBlock()
    {
        final var markdown = """
            ### 好块
            keywords: mood
            有效内容.

            ### 坏块
            没有 keywords 行.
            """;

        assertEquals(
            List.of(new PsychologyTipsRetriever.Tip("mood", "好块", "有效内容.")),
            KnowledgePackLoader.parse(markdown, "test"));
    }

    @Test void parse_MultiLineContent_ShouldJoinWithNewline()
    {
        final var markdown = """
            ### 多行块
            keywords: multi
            第一行.
            第二行.
            """;

        assertEquals(
            List.of(new PsychologyTipsRetriever.Tip("multi", "多行块", "第一行.\n第二行.")),
            KnowledgePackLoader.parse(markdown, "test"));
    }

    @Test void parse_EmptyKeywordsValue_ShouldKeepTipWithEmptyKeywords()
    {
        //* keywords 行存在但值为空: 块合法, 产出空标签 Tip (永不命中检索, 但可被随机推送).
        final var markdown = """
            ### 无标签块
            keywords:
            内容.
            """;

        assertEquals(
            List.of(new PsychologyTipsRetriever.Tip("", "无标签块", "内容.")),
            KnowledgePackLoader.parse(markdown, "test"));
    }

    @Test void parse_PreambleBeforeFirstBlock_ShouldBeIgnored()
    {
        final var markdown = """
            # 知识包说明 (非块内容)

            ### 正块
            keywords: a
            内容.
            """;

        assertEquals(
            List.of(new PsychologyTipsRetriever.Tip("a", "正块", "内容.")),
            KnowledgePackLoader.parse(markdown, "test"));
    }

    @Test void parse_EmptyText_ShouldReturnEmpty()
    {
        assertTrue(KnowledgePackLoader.parse("", "test").isEmpty());
        assertTrue(KnowledgePackLoader.parse("   \n  \n", "test").isEmpty());
    }

    @Test void parse_WindowsLineEndings_ShouldNotLeakCarriageReturn()
    {
        final var markdown = "### 标题\r\nkeywords: a\r\n第一行.\r\n第二行.\r\n";

        assertEquals(
            List.of(new PsychologyTipsRetriever.Tip("a", "标题", "第一行.\n第二行.")),
            KnowledgePackLoader.parse(markdown, "test"));
    }

    //endregion

    //region classpath 入口 load

    @Test void load_MissingPack_ShouldReturnEmpty()
    {
        //* 包缺失返回空列表, 回退决策交由调用方 (Retriever) 处理.
        assertTrue(KnowledgePackLoader.load("no-such-pack").isEmpty());
    }

    @Test void load_EmptyPackFile_ShouldReturnEmpty()
    {
        assertTrue(KnowledgePackLoader.load("empty-pack").isEmpty());
    }

    @Test void load_BrokenPackFile_ShouldReturnEmpty()
    {
        //* 全部块均坏 (无 keywords 行) → 解析为空, 调用方同样触发回退.
        assertTrue(KnowledgePackLoader.load("broken-pack").isEmpty());
    }

    @Test void load_DefaultPack_ShouldReturnThirteenTips()
    {
        assertEquals(13, KnowledgePackLoader.load("default").size());
    }

    //endregion
}
