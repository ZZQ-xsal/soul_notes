package kurvcygnus.soulnotes.ai.retriever;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link PsychologyTipsRetriever} 单元测试</b>
 * <p>等价性钉死: {@link #EXPECTED_TIPS} 为资源化迁移前硬编码的 13 条原 List,
 * 逐条断言 tips.md 解析结果与检索行为和它全等, 保证迁移零行为漂移.</p>
 *
 * @author Claude Code
 * @since 2.0
 */
class PsychologyTipsRetrieverTest
{
    //* 期望基准 = 迁移前硬编码的 13 条 (keywords/title/content 逐字保留, 含全角标点).
    private static final List<PsychologyTipsRetriever.Tip> EXPECTED_TIPS = List.of(
        new PsychologyTipsRetriever.Tip(
            "stress",
            "压力管理",
            "适度的压力能提升专注力与效率，但长期高压需要主动调节。尝试「4-7-8呼吸法」：吸气4秒→屏息7秒→呼气8秒，重复3次可快速平复情绪。"
        ),
        new PsychologyTipsRetriever.Tip(
            "stress,anxiety,sleep",
            "放松技巧",
            "渐进式肌肉放松法（PMR）：从脚趾开始逐部位绷紧5秒后放松15秒，逐步向上至面部。每日练习10分钟可显著降低焦虑水平。"
        ),
        new PsychologyTipsRetriever.Tip(
            "anxiety,worry",
            "焦虑应对",
            "「盒子呼吸法」：吸气4秒→屏息4秒→呼气4秒→屏息4秒。这种节奏性呼吸能激活副交感神经，帮助你在焦虑时恢复平静。"
        ),
        new PsychologyTipsRetriever.Tip(
            "sleep,rest",
            "睡眠健康",
            "保持规律作息比追求时长更重要。每天固定时间起床（即使是周末）能强化生物钟。睡前1小时减少蓝光暴露有助于褪黑素分泌。"
        ),
        new PsychologyTipsRetriever.Tip(
            "relationship,social",
            "人际关系",
            "高质量的关系不在于数量而在于深度。每周花15分钟与一位朋友进行真诚交流，比整天刷社交媒体的情感收益高得多。"
        ),
        new PsychologyTipsRetriever.Tip(
            "self-esteem,confidence",
            "自我接纳",
            "自我关怀（Self-Compassion）的三个核心：正念觉察情绪、理解人类共有经历、友善对待自己。对自己说「我已经尽力了」比苛责更有效。"
        ),
        new PsychologyTipsRetriever.Tip(
            "emotion,mood",
            "情绪认知",
            "情绪没有好坏之分，每种情绪都在传递信息。愤怒提醒你边界被侵犯，悲伤帮你整合失去，焦虑提醒你准备应对挑战。"
        ),
        new PsychologyTipsRetriever.Tip(
            "gratitude",
            "感恩练习",
            "每日写下三件值得感恩的小事（即使只是「今天的咖啡很好喝」），持续21天能显著提升幸福感。这是心理学验证最有效的积极干预之一。"
        ),
        new PsychologyTipsRetriever.Tip(
            "focus,procrastination",
            "专注力",
            "「番茄工作法」：25分钟专注工作 + 5分钟休息。每4个番茄钟后休息15-30分钟。这种方法利用时间紧迫感提升专注力，减少拖延。"
        ),
        new PsychologyTipsRetriever.Tip(
            "crisis,help",
            "寻求帮助",
            "当你感到难以承受时，主动寻求帮助是勇敢的表现。心理援助热线提供 7×24 小时免费支持：400-161-9995。你不需要独自面对。"
        ),
        new PsychologyTipsRetriever.Tip(
            "mindfulness",
            "正念练习",
            "「5-4-3-2-1」感官练习：说出你看到的5样东西、触摸到的4样、听到的3样、闻到的2样、尝到的1样。快速将注意力拉回当下。"
        ),
        new PsychologyTipsRetriever.Tip(
            "exercise,energy",
            "运动与情绪",
            "每周3次30分钟的中等强度有氧运动（快走、慢跑、游泳）的抗抑郁效果与轻中度抗抑郁药相当。运动产生的内啡肽是天然的情绪调节剂。"
        ),
        new PsychologyTipsRetriever.Tip(
            "study,exam",
            "考试焦虑",
            "考前焦虑是正常的生理反应。试试「积极重评」：将心跳加速、手心出汗重新解读为「身体在准备发挥最佳状态」而非「我很紧张」。"
        )
    );

    private final PsychologyTipsRetriever retriever = new PsychologyTipsRetriever(PsychologyTipsRetriever.DEFAULT_PACK);

    //region 等价性

    @Test void loadDefaultPack_ShouldEqualOriginalThirteenTips()
    {
        //* 逐条全等断言: keywords/title/content 任一字节漂移即失败 (record 语义 = 字段全等).
        assertEquals(EXPECTED_TIPS, KnowledgePackLoader.load(PsychologyTipsRetriever.DEFAULT_PACK));
    }

    @Test void retrieve_ShouldBehaveIdenticalToOriginalHardcodedList()
    {
        //* 覆盖: 空查询/空白/单关键词/多关键词/带空格分隔/无命中/子串命中/全部 13 条的关键词 CSV.
        for(final var query : List.of(
            "", "   ", "anxiety", "stress", "stress, sleep", "help", "crisis", "gratitude", "mindfulness",
            "study", "exam", "a", "xyznonexistent123", "self-esteem", "worry", "rest", "social", "confidence",
            "mood", "procrastination", "energy", "relationship", "focus", "sleep, anxiety, worry", "stress,anxiety,sleep"))
            assertEquals(expectedRetrieve(query), retriever.retrieve(query), "查询 \"" + query + "\" 行为漂移");
    }

    /**
     * 逐字复刻迁移前旧实现 (硬编码 List + 关键词交集 + limit 5), 作为等价性基准.
     */
    private static List<PsychologyTipsRetriever.Tip> expectedRetrieve(String query)
    {
        if(query.isBlank())
            return List.of();

        final var keywords = query.toLowerCase().split("[,\\s]+");
        return EXPECTED_TIPS.stream().
            filter(tip -> Stream.of(keywords).anyMatch(tip.keywords()::contains)).
            limit(5).
            toList();
    }

    //endregion

    //region 包选择与回退

    @Test void retrieve_MissingPack_ShouldFallbackToDefault()
    {
        final var fallback = new PsychologyTipsRetriever("no-such-pack");
        assertEquals(retriever.retrieve("anxiety"), fallback.retrieve("anxiety"));
        assertEquals(EXPECTED_TIPS.size(), KnowledgePackLoader.load(PsychologyTipsRetriever.DEFAULT_PACK).size());
    }

    @Test void retrieve_EmptyPackFile_ShouldFallbackToDefault()
    {
        final var fallback = new PsychologyTipsRetriever("empty-pack");
        assertEquals(retriever.retrieve("stress"), fallback.retrieve("stress"));
    }

    @Test void retrieve_BrokenPackFile_ShouldFallbackToDefault()
    {
        //* 全部块缺 keywords 行 → 解析为空 → 同样回退 default.
        final var fallback = new PsychologyTipsRetriever("broken-pack");
        assertEquals(retriever.retrieve("help"), fallback.retrieve("help"));
    }

    @Test void getRandomTip_MissingPack_ShouldFallbackToDefault()
    {
        final var fallback = new PsychologyTipsRetriever("no-such-pack");
        assertTrue(EXPECTED_TIPS.contains(fallback.getRandomTip()));
    }

    //endregion

    //region 既有行为语义 (与迁移前一致)

    @Test void retrieve_ByAnxiety_ShouldReturnMatchingTips()
    {
        final var results = retriever.retrieve("anxiety");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(tip -> tip.keywords().contains("anxiety")));
    }

    @Test void retrieve_BySleep_ShouldReturnMatchingTips()
    {
        final var results = retriever.retrieve("sleep");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(tip -> tip.keywords().contains("sleep")));
    }

    @Test void retrieve_ByMultiKeywords_ShouldReturnUnion()
    {
        final var results = retriever.retrieve("stress, sleep");
        assertFalse(results.isEmpty());
        assertTrue(results.size() >= 2);
    }

    @Test void retrieve_ByEmptyQuery_ShouldReturnEmpty()
    {
        assertTrue(retriever.retrieve("").isEmpty());
        assertTrue(retriever.retrieve("   ").isEmpty());
    }

    @Test void retrieve_ByNonExistentKeyword_ShouldReturnEmpty()
    {
        assertTrue(retriever.retrieve("xyznonexistent123").isEmpty());
    }

    @Test void getRandomTip_ShouldReturnNonNull()
    {
        final var tip = retriever.getRandomTip();
        assertNotNull(tip);
        assertNotNull(tip.keywords());
        assertNotNull(tip.title());
        assertNotNull(tip.content());
    }

    @Test void allTips_ShouldHaveNonBlankFields()
    {
        for(int i = 0; i < 20; i++)
        {
            final var tip = retriever.getRandomTip();
            assertFalse(tip.keywords().isBlank());
            assertFalse(tip.title().isBlank());
            assertFalse(tip.content().isBlank());
        }
    }

    @Test void retrieve_ResultLimit_ShouldBeAtMostFive()
    {
        //* 用空字符串匹配所有 tip 验证上限 (实际不会全匹配, 但证明 limit 机制)
        final var results = retriever.retrieve("a"); // 'a' 不匹配任何关键词
        assertTrue(results.size() <= 5);
    }

    @Test void retrieve_ByHelp_ShouldIncludeCrisisTip()
    {
        final var results = retriever.retrieve("help");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(tip -> tip.title().contains("寻求帮助")));
    }

    //endregion
}
