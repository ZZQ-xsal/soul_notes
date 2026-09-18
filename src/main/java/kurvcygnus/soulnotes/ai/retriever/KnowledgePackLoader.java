package kurvcygnus.soulnotes.ai.retriever;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * <b>知识包加载器</b>
 * <p>从 classpath {@code knowledge/{pack}/tips.md} 以 UTF-8 读取心理知识包并解析为 Tip 列表,
 * 使机构可经 {@code SOULNOTES_KNOWLEDGE_PACK} 切换/替换知识包而无需改代码.</p>
 * <p>块格式钉死: {@code ### 标题} 行开块, 紧随的 {@code keywords: a,b,c} 行为关键词标签 (块必需),
 * 其余行至下一块为正文. keywords 行缺失的块整体跳过并记 WARN — 残缺块进入检索会破坏匹配语义, 宁缺毋滥;
 * keywords 行存在但值为空属合法 (空标签 Tip 永不命中检索, 仍可被随机推送).</p>
 * @since 2.0
 */
public final class KnowledgePackLoader
{
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgePackLoader.class);

    private static final String RESOURCE_PATTERN = "knowledge/%s/tips.md";
    private static final String BLOCK_PREFIX = "### ";
    private static final String KEYWORDS_PREFIX = "keywords:";

    private KnowledgePackLoader() {}

    /**
     * <span style="color: 95cc6d">从 classpath 加载指定知识包.</span>
     * <p>包缺失/文件为空/IO 失败一律返回空列表 (仅记日志, 绝不抛出) — 回退到内置 default 包的
     * 决策由调用方 ({@link PsychologyTipsRetriever}) 统一处理, 加载器保持无状态纯职责.</p>
     * @param pack 包名 (对应 classpath 目录 knowledge/{pack}/)
     * @return 解析出的 Tip 列表 (不可变, 可能为空)
     */
    public static @NotNull @Unmodifiable List<PsychologyTipsRetriever.Tip> load(@NotNull String pack)
    {
        Objects.requireNonNull(pack, "Param \"pack\" must not be null!");
        final var resourcePath = RESOURCE_PATTERN.formatted(pack);

        try(final var input = KnowledgePackLoader.class.getClassLoader().getResourceAsStream(resourcePath))
        {
            if(input == null)
            {
                LOG.debug("知识包资源不存在: {}", resourcePath);
                return List.of();
            }
            return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8), pack);
        }
        catch(final IOException e)
        {
            //! 读取失败与缺失同语义: 返回空列表交由调用方回退, IO 异常不允许炸掉检索链路.
            LOG.warn("知识包资源读取失败: {}, 原因: {}", resourcePath, e.getMessage());
            return List.of();
        }
    }

    /**
     * <span style="color: 95cc6d">解析 tips.md 块格式文本.</span>
     * <p>纯函数入口 (无 IO): 单测直测格式语义, 未来非 classpath 来源 (上传/远端拉取) 亦可复用.</p>
     * @param markdown tips.md 原始文本 (UTF-8)
     * @param pack     包名 (仅用于日志定位坏块)
     * @return 解析出的 Tip 列表 (不可变, 可能为空)
     */
    public static @NotNull @Unmodifiable List<PsychologyTipsRetriever.Tip> parse(@NotNull String markdown, @NotNull String pack)
    {
        Objects.requireNonNull(markdown, "Param \"markdown\" must not be null!");
        Objects.requireNonNull(pack, "Param \"pack\" must not be null!");

        //region 逐行状态机
        final var tips = new ArrayList<PsychologyTipsRetriever.Tip>();
        String title = null;
        String keywords = null;
        final var contentLines = new ArrayList<String>();

        for(final var rawLine: markdown.lines().toList())
        {
            if(rawLine.startsWith(BLOCK_PREFIX))
            {
                flushBlock(tips, title, keywords, contentLines, pack);
                title = rawLine.substring(BLOCK_PREFIX.length()).trim();
                keywords = null;
                contentLines.clear();
            }
            else if(title != null)
            {
                //* keywords 行必须是标题后的第一行 (格式钉死): contentLines 非空说明已错过位置, 后续 keywords 字样一律计入正文.
                if(keywords == null && contentLines.isEmpty() && rawLine.startsWith(KEYWORDS_PREFIX))
                    keywords = rawLine.substring(KEYWORDS_PREFIX.length()).trim();
                else
                    contentLines.add(rawLine);
            }
            //* 首块之前的行 (文件前言) 忽略, 不影响解析.
        }
        flushBlock(tips, title, keywords, contentLines, pack);
        //endregion

        return List.copyOf(tips);
    }

    /**
     * 收块: 当前块缓冲写入结果列表.
     * <p>仅 keywords 行缺失时跳过整块 (WARN), 标题与正文均不做修正尝试 — 静默补全会让机构难以察觉包配置错误.</p>
     */
    private static void flushBlock(
        @NotNull List<PsychologyTipsRetriever.Tip> tips,
        @Nullable String title,
        @Nullable String keywords,
        @NotNull List<String> contentLines,
        @NotNull String pack)
    {
        if(title == null)
            return;
        if(keywords == null)
        {
            LOG.warn("知识包 {} 中块 \"{}\" 缺少 keywords 行, 该块已跳过", pack, title);
            return;
        }
        tips.add(new PsychologyTipsRetriever.Tip(keywords, title, String.join("\n", contentLines).strip()));
    }
}
