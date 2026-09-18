package kurvcygnus.soulnotes.ai;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * <b>模型目录拉取端口: OpenAI 兼容 {@code /models} 列表探测</b>
 * <p>Pre-Launch 阶段 (配置向导 AI 拉取步, Spec §6) 运行于 CDI 容器启动之前, 只能以纯构造装配消费
 * 模型列表能力; 本接口是该消费面的最小端口, 生产实现为 {@link HttpModelCatalog}.</p>
 *
 * <p>//* 不做 sealed: 向导的单元测试须跨包伪造本端口 (sealed permits 无法覆盖测试源),
 * 与 {@code IAsrRuntimeControl} 同一定位, 允许可替换实现.</p>
 * @since 2.0
 */
public interface IModelCatalog
{
    //region 数据表面

    //* 单条模型元数据: context 为上下文长度展示值, reasoning 为思考能力展示值 (✓|✗|-), 无对应扩展字段时均为 "-".
    record ModelInfo(@NotNull String id, @NotNull String context, @NotNull String reasoning) {}

    //* 拉取结果: normalizedEndpoint 为探测成功的 base + /v1 规范形 (langchain4j base-url 所需形态, fetch 与存储分离).
    record CatalogResult(@NotNull List<ModelInfo> models, @NotNull String normalizedEndpoint)
    {
        public CatalogResult { models = List.copyOf(models); }
    }

    //* 401/403 专属分型: 密钥被拒与网络失败必须可区分, 向导对前者回重编辑、后者走手动输入兜底.
    class UnauthorizedException extends Exception
    {
        public UnauthorizedException(@NotNull String message) { super(message); }
    }

    //endregion

    //region URL 启发式 (单一来源)

    /**
     * <span style="color: 95cc6d">探测 URL 启发式 (Spec §6): endpoint 以 {@code /v1} 结尾 → 拼 {@code /models};
     * 否则拼 {@code /v1/models}.</span>
     * <p>//* 静态置于端口而非实现: 向导须在拉取前回显最终请求 URL (Spec §6), 与 fetch 共用同一启发式避免两处漂移.</p>
     * @param endpoint 用户输入的 OpenAI 兼容接口根地址
     * @return 实际探测的完整 URL
     */
    static @NotNull String modelsUrl(@NotNull String endpoint)
    {
        //* normalizeEndpoint 恒以 /v1 结尾, 探测 URL 即规范形 + /models, 两条路径不可能漂移.
        return normalizeEndpoint(endpoint) + "/models";
    }

    /**
     * <span style="color: 95cc6d">存储规范形 (Spec §6 fetch 与存储分离): base + {@code /v1}
     * (langchain4j base-url 所需形态).</span>
     * <p>//* 与 {@link #modelsUrl} 共用同一后缀判定, 避免 "拉取成功但 chat 调用 404" 的路径不一致.</p>
     * @param endpoint 用户输入的接口根地址
     * @return 规范化 endpoint, 恒以 {@code /v1} 结尾
     */
    static @NotNull String normalizeEndpoint(@NotNull String endpoint)
    {
        var base = trimTrailingSlashes(endpoint);
        return base.endsWith("/v1") ? base : base + "/v1";
    }

    //* 尾斜杠多为手误粘贴, 若不剥除会产生 //v1//models 病态拼接; 全部剥除而非仅一个, 与规范化保持同一形态.
    private static @NotNull String trimTrailingSlashes(@NotNull String endpoint)
    {
        var base = endpoint;
        while(base.endsWith("/"))
            base = base.substring(0, base.length() - 1);
        return base;
    }

    //endregion

    //region 拉取

    /**
     * <span style="color: 95cc6d">同步拉取模型列表 (阻塞式, Pre-Launch 无事件循环可挂靠).</span>
     * @param endpoint 用户输入的接口根地址 (启发式见 {@link #modelsUrl})
     * @param apiKey Bearer 鉴权密钥
     * @param timeout 单次请求超时
     * @return 模型列表 + 规范化 endpoint, 绝不返回 null
     * @throws UnauthorizedException HTTP 401/403 (密钥被拒)
     * @throws java.io.IOException 其余非 200 / 连接失败 / 超时 / 响应体不可解析
     */
    @NotNull CatalogResult fetch(@NotNull String endpoint, @NotNull String apiKey, @NotNull Duration timeout)
        throws UnauthorizedException, IOException;

    //endregion
}
