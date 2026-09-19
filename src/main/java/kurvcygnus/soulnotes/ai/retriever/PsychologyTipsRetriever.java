package kurvcygnus.soulnotes.ai.retriever;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Stream;

/**
 * 心理小知识检索器.
 * <p>知识库自 classpath {@code knowledge/{pack}/tips.md} 加载, {@code SOULNOTES_KNOWLEDGE_PACK}
 * 选择包 (默认 {@code default}), 机构可整包替换心理知识而无需改代码.</p>
 * <p>回退链: 配置包缺失/解析为空 → 回退内置 default 包并 WARN; default 亦不可用 → 空列表 + WARN,
 * 检索返回空. 后续可扩展为基于向量数据库的 RAG 检索.</p>
 * @since 1.0
 */
@ApplicationScoped
public final class PsychologyTipsRetriever
{
    private static final Logger LOG = LoggerFactory.getLogger(PsychologyTipsRetriever.class);

    /**
     * 内置回退知识包名 ({@code knowledge/default/tips.md}).
     *
     * @since 1.1.0
     */
    public static final String DEFAULT_PACK = "default";

    //* 配置包名仅用于启动后首次检索解析 (包切换经重启生效), 缓存后不再读取.
    private final @NotNull String packName;

    //* 实例字段: GraalVM native-image 禁止 static final 字段持有 Random 实例 (构建期种子会被固化进镜像堆), 单例 bean 下实例字段语义等价.
    private final @NotNull Random random = new Random();

    //* 懒加载缓存 (bean 内仅解析一次): 避免构造期 IO 拖慢启动; volatile 保证并发首调的可见性,
    //* 竞态下最坏重复解析一次, 结果幂等无害.
    private volatile List<@NotNull Tip> tips;

    /**
     * 构造入口 (CDI 注入 / 测试直构共用).
     * <p>直构时 @ConfigProperty 注解不生效, 参数按普通字符串传入 — 单测借此注入任意包名验证回退链.</p>
     *
     * @param packName 知识包名 (SOULNOTES_KNOWLEDGE_PACK, 默认 {@link #DEFAULT_PACK})
     * @since 1.1.0
     */
    @Inject
    public PsychologyTipsRetriever(
        @ConfigProperty(name = "knowledge.pack", defaultValue = DEFAULT_PACK) @NotNull String packName)
    { this.packName = Objects.requireNonNull(packName, "Param \"packName\" must not be null!"); }

    /**
     * 按关键词检索相关心理小知识.
     *
     * @param query 搜索关键词, 逗号或空格分隔; 空白查询直接返回空列表
     * @return 匹配的心理知识列表 (最多 5 条); 知识包整体不可用时为空列表
     */
    public @NotNull List<@NotNull Tip> retrieve(@NotNull String query)
    {
        if(query.isBlank())
            return List.of();

        final var keywords = query.toLowerCase().split("[,\\s]+");
        return tips().stream().
            filter(tip -> Stream.of(keywords).anyMatch(tip.keywords()::contains)).
            limit(5).
            toList();
    }

    /**
     * 随机获取一条心理小知识.
     *
     * @return 随机 Tip
     * @throws IllegalStateException 知识包为空 (default 亦缺失属部署错误) — 显式快败而非抛除零异常
     */
    public @NotNull Tip getRandomTip()
    {
        final var all = tips();
        //! 空包防除零: nextInt(0) 会抛晦涩的 IllegalArgumentException, 显式 ISE 指明根因
        //! (default 包亦缺失属部署错误, 多为 native 镜像未包含 knowledge/** 资源).
        if(all.isEmpty())
            throw new IllegalStateException("知识包为空, 无法随机推送心理小知识 (请检查 knowledge/** 是否随应用部署)");
        return all.get(random.nextInt(all.size()));
    }

    /**
     * 解析并缓存当前包的 Tip 列表.
     * <p>回退决策集中于此: 配置包非空即用; 否则 (非 default 时) 回退 default 并 WARN;
     * default 自身缺失/为空则空列表 + WARN — 每种降级态都有日志, 机构配置错误不被静默吞掉.</p>
     *
     * @since 1.1.0
     */
    private @NotNull List<@NotNull Tip> tips()
    {
        final var cached = tips;
        if(cached != null)
            return cached;

        final var configured = KnowledgePackLoader.load(packName);
        if(!configured.isEmpty())
            return tips = configured;

        if(DEFAULT_PACK.equals(packName))
        {
            LOG.warn("内置知识包 default 缺失或解析为空, 心理知识检索不可用 (请检查 knowledge/** 资源打包)");
            return tips = List.of();
        }

        LOG.warn("知识包 {} 缺失或解析为空, 回退内置 default 包", packName);
        final var fallback = KnowledgePackLoader.load(DEFAULT_PACK);
        if(fallback.isEmpty())
            LOG.warn("内置知识包 default 亦缺失或解析为空, 心理知识检索不可用");
        return tips = fallback;
    }

    /**
     * 心理知识条目.
     *
     * @param keywords 关键词标签 (逗号分隔)
     * @param title    标题
     * @param content  正文内容
     */
    public record Tip(
        @NotNull String keywords,
        @NotNull String title,
        @NotNull String content
    )
    {}
}
