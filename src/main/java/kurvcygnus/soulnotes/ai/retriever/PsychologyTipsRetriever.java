package kurvcygnus.soulnotes.ai.retriever;

import jakarta.enterprise.context.ApplicationScoped;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * <b>心理小知识检索器</b>
 * <p>内置常见心理话题的知识库, 支持关键词匹配检索.</p>
 * <p>后续可扩展为基于向量数据库的 RAG 检索.</p>
 * @since 2.0
 */
@ApplicationScoped
public final class PsychologyTipsRetriever
{
    private static final @Unmodifiable @NotNull List<Tip> TIPS = List.of(
        new Tip(
            "stress",
            "压力管理",
            "适度的压力能提升专注力与效率，但长期高压需要主动调节。尝试「4-7-8呼吸法」：吸气4秒→屏息7秒→呼气8秒，重复3次可快速平复情绪。"
        ),
        new Tip(
            "stress,anxiety,sleep",
            "放松技巧",
            "渐进式肌肉放松法（PMR）：从脚趾开始逐部位绷紧5秒后放松15秒，逐步向上至面部。每日练习10分钟可显著降低焦虑水平。"
        ),
        new Tip(
            "anxiety,worry",
            "焦虑应对",
            "「盒子呼吸法」：吸气4秒→屏息4秒→呼气4秒→屏息4秒。这种节奏性呼吸能激活副交感神经，帮助你在焦虑时恢复平静。"
        ),
        new Tip(
            "sleep,rest",
            "睡眠健康",
            "保持规律作息比追求时长更重要。每天固定时间起床（即使是周末）能强化生物钟。睡前1小时减少蓝光暴露有助于褪黑素分泌。"
        ),
        new Tip(
            "relationship,social",
            "人际关系",
            "高质量的关系不在于数量而在于深度。每周花15分钟与一位朋友进行真诚交流，比整天刷社交媒体的情感收益高得多。"
        ),
        new Tip(
            "self-esteem,confidence",
            "自我接纳",
            "自我关怀（Self-Compassion）的三个核心：正念觉察情绪、理解人类共有经历、友善对待自己。对自己说「我已经尽力了」比苛责更有效。"
        ),
        new Tip(
            "emotion,mood",
            "情绪认知",
            "情绪没有好坏之分，每种情绪都在传递信息。愤怒提醒你边界被侵犯，悲伤帮你整合失去，焦虑提醒你准备应对挑战。"
        ),
        new Tip(
            "gratitude",
            "感恩练习",
            "每日写下三件值得感恩的小事（即使只是「今天的咖啡很好喝」），持续21天能显著提升幸福感。这是心理学验证最有效的积极干预之一。"
        ),
        new Tip(
            "focus,procrastination",
            "专注力",
            "「番茄工作法」：25分钟专注工作 + 5分钟休息。每4个番茄钟后休息15-30分钟。这种方法利用时间紧迫感提升专注力，减少拖延。"
        ),
        new Tip(
            "crisis,help",
            "寻求帮助",
            "当你感到难以承受时，主动寻求帮助是勇敢的表现。心理援助热线提供 7×24 小时免费支持：400-161-9995。你不需要独自面对。"
        ),
        new Tip(
            "mindfulness",
            "正念练习",
            "「5-4-3-2-1」感官练习：说出你看到的5样东西、触摸到的4样、听到的3样、闻到的2样、尝到的1样。快速将注意力拉回当下。"
        ),
        new Tip(
            "exercise,energy",
            "运动与情绪",
            "每周3次30分钟的中等强度有氧运动（快走、慢跑、游泳）的抗抑郁效果与轻中度抗抑郁药相当。运动产生的内啡肽是天然的情绪调节剂。"
        ),
        new Tip(
            "study,exam",
            "考试焦虑",
            "考前焦虑是正常的生理反应。试试「积极重评」：将心跳加速、手心出汗重新解读为「身体在准备发挥最佳状态」而非「我很紧张」。"
        )
    );

    private static final @NotNull Random RANDOM = new Random();

    /**
     * <span style="color: 95cc6d">按关键词检索相关心理小知识.</span>
     *
     * @param query 搜索关键词, 逗号或空格分隔
     * @return 匹配的心理知识列表 (最多 5 条)
     */
    public @NotNull List<@NotNull Tip> retrieve(@NotNull String query)
    {
        if(query.isBlank())
            return List.of();

        final var keywords = query.toLowerCase().split("[,\\s]+");
        return TIPS.stream().
            filter(tip -> Stream.of(keywords).anyMatch(tip.keywords()::contains)).
            limit(5).
            toList();
    }

    /**
     * <span style="color: 95cc6d">随机获取一条心理小知识.</span>
     *
     * @return 随机 Tip
     */
    public @NotNull Tip getRandomTip() { return TIPS.get(RANDOM.nextInt(TIPS.size())); }

    /**
     * <b>心理知识条目</b>
     *
     * @param keywords 关键词标签 (逗号分隔)
     * @param title    标题
     * @param content  正文内容
     */
    public record Tip(
        @NotNull String keywords,
        @NotNull String title,
        @NotNull String content
    ) {}
}
