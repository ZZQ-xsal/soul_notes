package kurvcygnus.soulnotes.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kurvcygnus.soulnotes.utils.PrintUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

/**
 * HTTP 模型目录拉取器.
 * <p>以 JDK {@link java.net.http.HttpClient} 探测 OpenAI 兼容 {@code /models} 端点 (URL 启发式见
 * {@link IModelCatalog#modelsUrl}), 解析 {@code data[].id} 与扩展字段为向导展示行;
 * 401/403 分型为 {@link IModelCatalog.UnauthorizedException} 供向导回重编辑密钥, 其余失败归一
 * {@link IOException} 供向导走手动输入兜底 — 拉取成功即连通性 + 密钥双重验证, 不重复造验证轮子.</p>
 * <p>无状态且 Pre-Launch 阶段先于 CDI 启动, 纯构造即可用 (Entrance 直接 new).</p>
 * @since 1.1.0
 */
public final class HttpModelCatalog implements IModelCatalog
{
    //* 单例复用 (AsrRuntimeManager 先例): 连接池/线程复用; 读超时按次经 fetch 的 timeout 参数下发, 不钉死在客户端.
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    //* Jackson 直用而非 JsonUtils 静态桥: 后者由 Quarkus @Startup 注入, Pre-Launch 阶段尚未初始化.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    //* 思考能力展示值 (列表行): 从宽映射, 见 [[HttpModelCatalog#reasoningOf]].
    private static final String REASONING_YES = "✓";
    private static final String REASONING_NO = "✗";
    private static final String FIELD_ABSENT = "-";

    /**
     * 同步阻塞拉取: 401/403 抛 {@link UnauthorizedException}, 中断/非 200/解析失败一律归一 {@link IOException}.
     */
    @Override public @NotNull CatalogResult fetch(@NotNull String endpoint, @NotNull String apiKey, @NotNull Duration timeout)
        throws UnauthorizedException, IOException
    {
        Objects.requireNonNull(endpoint, "Param \"endpoint\" must not be null!");
        Objects.requireNonNull(apiKey, "Param \"apiKey\" must not be null!");
        Objects.requireNonNull(timeout, "Param \"timeout\" must not be null!");
        final var request = buildRequest(endpoint, apiKey, timeout);
        final HttpResponse<String> response;
        try { response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString()); }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();  //* 恢复中断标记后以 IOException 收场, 向导层走手动输入兜底而非裸崩.
            throw new IOException("模型列表请求被中断", e);
        }
        final var status = response.statusCode();
        if(status == 401 || status == 403)
            throw new UnauthorizedException(PrintUtils.quickFormat("HTTP {}: API 密钥被服务端拒绝", status));
        if(status != 200)
            throw new IOException(PrintUtils.quickFormat("HTTP {}", status));
        return parse(response.body(), endpoint);
    }

    //region 请求构造

    //* 地址经 FieldValidator 仅校验 scheme 前缀, 深层不合法 (空格/非法字符) 会在此暴露, 归一 IOException 交向导兜底.
    private static @NotNull HttpRequest buildRequest(@NotNull String endpoint, @NotNull String apiKey, @NotNull Duration timeout)
        throws IOException
    {
        try
        {
            return HttpRequest.newBuilder(URI.create(IModelCatalog.modelsUrl(endpoint)))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        }
        catch(IllegalArgumentException e)
        {
            throw new IOException(PrintUtils.quickFormat("接口地址无法解析: {}", endpoint), e);
        }
    }

    //endregion

    //region 响应解析

    private static @NotNull CatalogResult parse(@NotNull String body, @NotNull String endpoint) throws IOException
    {
        final JsonNode data;
        try { data = MAPPER.readTree(body).path("data"); }
        catch(JsonProcessingException e) { throw new IOException("响应不是有效的模型列表 JSON", e); }
        if(!data.isArray())
            throw new IOException("响应缺少 data 模型数组");
        final var models = new ArrayList<ModelInfo>();
        for(final var node : data)
        {
            final var id = node.path("id").asText("");
            if(id.isBlank())
                continue;  //* 从宽: 单条目缺 id 只跳过该条, 不废整个列表.
            models.add(new ModelInfo(id, contextOf(node), reasoningOf(node)));
        }
        return new CatalogResult(models, IModelCatalog.normalizeEndpoint(endpoint));
    }

    //* 上下文长度: 数值/字符串原样透传 (字符串型如 "256k" 服务端自有语义, 不做强转), 缺失/非标量 → "-".
    private static @NotNull String contextOf(@NotNull JsonNode node)
    {
        final var value = node.get("context_length");
        return value != null && value.isValueNode() && !value.isNull() ? value.asText() : FIELD_ABSENT;
    }

    //* 思考能力启发式 (从宽, 不同中转/网关字段形态不一):
    //* supported_parameters 任一元素含 "reasoning" → ✓; reasoning 布尔字段 true → ✓, 显式 false → ✗; 其余 (缺失/未知形态) → "-".
    //* ✓ 优先于 ✗: 两处信号矛盾时按能力具备处理, 误报影响仅为展示行多一个勾.
    private static @NotNull String reasoningOf(@NotNull JsonNode node)
    {
        final var parameters = node.get("supported_parameters");
        if(parameters != null && parameters.isArray())
        {
            for(final var parameter : parameters)
            {
                if(parameter.asText("").toLowerCase(Locale.ROOT).contains("reasoning"))
                    return REASONING_YES;
            }
        }
        final var flag = node.get("reasoning");
        if(flag != null && flag.isBoolean())
            return flag.asBoolean() ? REASONING_YES : REASONING_NO;
        return FIELD_ABSENT;
    }

    //endregion
}
