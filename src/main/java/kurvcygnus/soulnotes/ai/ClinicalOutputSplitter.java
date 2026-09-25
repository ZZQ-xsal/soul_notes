package kurvcygnus.soulnotes.ai;

import com.fasterxml.jackson.databind.JsonNode;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * 结构化输出拆流器 ("副医生"预埋).
 * <p>从 AI 回复中提取 {@code AiPromptConstants#CLINICAL_OUTPUT_CONTRACT} 约定的
 * {@code <!--soulnotes {...}-->} HTML 注释块: 命中且 JSON 合法, 正文剔除该块并返回 payload;
 * 无块或 JSON 解析失败, 整条回复原样透传 (优雅降级).</p>
 * <p>边界: 剥离是主机制, 注释在 markdown/HTML 渲染下不可见仅是兜底 — 前端若以纯文本渲染,
 * 透传的注释块会以原文可见, 两者缺一不可.</p>
 * @since 1.1.0
 */
public final class ClinicalOutputSplitter
{
    private static final Logger LOG = LoggerFactory.getLogger(ClinicalOutputSplitter.class);

    //* 宽容正则: "<!--+" 容忍 <!--- 多横线开标记, "--!?>" 容忍收标记感叹号变体;
    //* DOTALL 使块内 JSON 可跨行; 捕获组仅取花括号 JSON 段, 整体锚定收标记使嵌套花括号正确回溯到最外层闭合.
    private static final Pattern SOULNOTES_BLOCK = Pattern.compile("<!--+\\s*soulnotes\\s*(\\{.*?})\\s*--!?>", Pattern.DOTALL);

    private ClinicalOutputSplitter() { throw new IllegalAccessError("Class \"ClinicalOutputSplitter\" is not meant to be instantized!"); }

    /**
     * 拆流结果.
     * <ul>
     *     <li>{@code text} — 供前端展示与落库的正文; 命中块时已剔除注释块并去除块后尾随空白</li>
     *     <li>{@code payload} — 结构化负载 (宽松 schema, 未知字段忽略); 无块或解析失败时为 {@code null}</li>
     * </ul>
     */
    public record SplitResult(
        @NotNull String text,
        @Nullable JsonNode payload
    ) {}

    /**
     * 从回复中提取最后一个 {@code soulnotes} 注释块.
     * <p>多块时取最后一个为权威: 契约约定块在回复末尾, 但 LLM 可能在正文中复述过早期块.
     * JSON 解析失败时 payload 为 null 且正文原样透传, 解析失败经 DEBUG 日志可观测.</p>
     *
     * @param reply AI 原始回复
     * @return 拆流结果 (永不返回 null)
     */
    public static @NotNull SplitResult split(@NotNull String reply)
    {
        //* 必须在 find() 循环内快照匹配状态: 末次 find() 返回 false 时 Matcher 被重置,
        //! 循环后再取 group/start/end 会抛 IllegalStateException 被降级吞掉, 永远走透传.
        String blockJson = null;
        int blockStart = 0, blockEnd = 0;
        final var matcher = SOULNOTES_BLOCK.matcher(reply);
        while(matcher.find())
        {
            blockJson = matcher.group(1);
            blockStart = matcher.start();
            blockEnd = matcher.end();
        }
        if(blockJson == null)
            return new SplitResult(reply, null);
        try
        {
            final var payload = JsonUtils.parseJson(blockJson, JsonNode.class);
            //* 剔除块本体并去除尾随空白 (契约约定块位于最末尾, 前置段尾部常带换行);
            //! 块位于正文中段时 suffix 拼接是宽容行为, 契约违反场景本就依赖注释隐形兜底.
            final var text = (reply.substring(0, blockStart) + reply.substring(blockEnd)).stripTrailing();
            return new SplitResult(text, payload);
        }
        catch(RuntimeException e)
        {
            //* [[JsonUtils#parseJson]] 失败包装为 RuntimeException; DEBUG 级观测而非 WARN:
            //* 降级路径对用户无感知, WARN 会把模型偶发的格式抖动放大为告警噪音.
            LOG.debug("soulnotes 结构化块 JSON 解析失败, 整条回复原样透传: {}", e.getMessage());
            return new SplitResult(reply, null);
        }
    }
}
