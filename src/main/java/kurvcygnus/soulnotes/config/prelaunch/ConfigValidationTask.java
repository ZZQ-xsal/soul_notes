package kurvcygnus.soulnotes.config.prelaunch;

import kurvcygnus.soulnotes.ai.asr.AsrRuntimeManager;
import kurvcygnus.soulnotes.utils.PrintUtils;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * <b>配置校验任务</b> (规则矩阵).
 * <p>单字段规则由 {@link FieldValidator} 承载; 必配规则 prod 严格 / dev 放宽 (dev 有内置默认);
 * AI 密钥为空或占位哨兵 placeholder 不分 profile 一律 BLOCK (用户裁决: AI 为应用必配项, 无 dev 放宽);
 * 弱 JWT 按规则矩阵分派: 显式弱值不分 profile 一律 BLOCK (格式级), 仅 dev 的内置默认弱密钥降为 WARN 提醒;
 * 天气阈值域与次序为跨字段规则, 依据 EmotionWeatherService#mapWeather 分支可达性: storm &gt; rainy &gt; overcast 严格递减,
 * sunny 仅校验 [0,1] 域不参与次序.</p>
 *
 * <p>Pre-Launch 阶段运行于 CDI 容器启动之前 (Entrance#main 纯构造装配), 无法经容器注入
 * {@link AsrRuntimeManager}, ASR 就绪判定经 {@code BooleanSupplier} 端口传入 (生产传其 ready() 方法引用).</p>
 * @since 2.0
 */
public final class ConfigValidationTask implements IPreLaunchTask
{
    //* dev profile 下允许缺席的必配项 (application-dev.properties 提供运行时默认).
    private static final @NotNull List<String> DEV_RELAXED =
        List.of("SOULNOTES_DB_USER", "SOULNOTES_DB_PASSWORD", "SOULNOTES_JWT_SECRET");

    private final @NotNull BooleanSupplier asrReady;

    /**
     * <span style="color: 95cc6d">纯构造入口 (Pre-Launch 于 CDI 启动前运行, 无容器装配路径).</span>
     * @param asrReady ASR 运行时就绪探测 (生产传 AsrRuntimeManager::ready, 测试可伪造)
     */
    public ConfigValidationTask(@NotNull BooleanSupplier asrReady)
    { this.asrReady = Objects.requireNonNull(asrReady, "Param \"asrReady\" must not be null!"); }

    @Override public @NotNull String name() { return "配置校验"; }

    @Override public @NotNull Result run(@NotNull PreLaunchContext ctx)
    {
        final var issues = new ArrayList<Issue>();
        final var byEnv = new HashMap<String, PropertyMetaParser.ConfigItemMeta>();
        for(final var item: ctx.items()) byEnv.put(item.envName(), item);

        for(final var item: ctx.items())
        {
            final var raw = ctx.view().explicit(item.key(), item.envName());
            final var value = ctx.view().resolved(item.key(), item.envName(), item.defaultValue());

            raw.flatMap(v -> FieldValidator.validate(item, v)).
                ifPresent(msg -> issues.add(new Issue(Level.BLOCK, item.envName(), msg)));

            if(item.required() && value.isBlank() && !(ctx.profile().equals("dev") && DEV_RELAXED.contains(item.envName())))
                issues.add(new Issue(Level.BLOCK, item.envName(), "必填项未配置"));
        }

        validateWeather(ctx, byEnv, issues);
        validateAiKey(ctx, issues);
        validateWarnRules(ctx, byEnv, issues);
        return new Result(List.copyOf(issues));
    }

    //region 跨字段与警告规则

    //* 跨字段: 阈值 ∈ [0,1] 且 storm > rainy > overcast (严格递减, 否则 RAINY/OVERCAST 分支不可达);
    //* sunny 基于正向均值, 与其余三项阈值同受 [0,1] 区间约束, 不进入次序比较.
    private static void validateWeather(@NotNull PreLaunchContext ctx, @NotNull Map<String, PropertyMetaParser.ConfigItemMeta> byEnv, @NotNull List<Issue> issues)
    {
        final var storm = threshold(ctx, byEnv, "SOULNOTES_WEATHER_STORM", issues);
        final var rainy = threshold(ctx, byEnv, "SOULNOTES_WEATHER_RAINY", issues);
        final var overcast = threshold(ctx, byEnv, "SOULNOTES_WEATHER_OVERCAST", issues);
        threshold(ctx, byEnv, "SOULNOTES_WEATHER_SUNNY", issues);
        if(storm == null || rainy == null || overcast == null) return;
        if(!(storm > rainy && rainy > overcast))
            issues.add(new Issue(Level.BLOCK, "weather.threshold", PrintUtils.quickFormat("阈值次序必须 storm > rainy > overcast (当前 {}/{}/{})", storm, rainy, overcast)));
    }

    private static Double threshold(@NotNull PreLaunchContext ctx, @NotNull Map<String, PropertyMetaParser.ConfigItemMeta> byEnv, @NotNull String env, @NotNull List<Issue> issues)
    {
        final var item = byEnv.get(env);
        if(item == null) return null;
        try
        {
            final var v = Double.parseDouble(ctx.view().resolved(item.key(), env, item.defaultValue()));
            if(v < 0 || v > 1) issues.add(new Issue(Level.BLOCK, env, PrintUtils.quickFormat("阈值必须处于 [0,1], 当前 {}", v)));
            return v;
        }
        catch(NumberFormatException e) { return null; }//! 非法数字已由 FieldValidator 报 BLOCK, 这里吞掉二次异常并跳过次序检查 (首个错误已上报).
    }

    //* 用户裁决: AI 为应用必配项, 占位哨兵 placeholder 不得视为已配置 —
    //* 不分 profile 一律 BLOCK: 有 TTY 时 Entrance#decide 自动引导 Setup 向导补配, 无 TTY (CI/管道) 直接拒绝启动.
    private static void validateAiKey(@NotNull PreLaunchContext ctx, @NotNull List<Issue> issues)
    {
        final var aiKey = ctx.view().resolved("ai.openai.api-key", "SOULNOTES_AI_API_KEY", "placeholder");
        if(aiKey.isBlank() || "placeholder".equals(aiKey))
            issues.add(new Issue(Level.BLOCK, "SOULNOTES_AI_API_KEY", "AI 密钥未配置 (为空或为占位符 placeholder), 请经 Setup 向导或环境变量提供"));
    }

    //* 警告规则: 不阻断启动. (ASR 就绪判定依赖实例端口的就绪探测, 故为实例方法)
    private void validateWarnRules(@NotNull PreLaunchContext ctx, @NotNull Map<String, PropertyMetaParser.ConfigItemMeta> byEnv, @NotNull List<Issue> issues)
    {
        //* ASR 为可插拔能力: 运行时未就绪仅 WARN 不 BLOCK — 文字链路与离线热线兜底仍完整可用,
        //* 与 AI 密钥 BLOCK 的不对称是有意的 (AI 缺失则对话/情绪分析全链路不可用). ready() 为纯文件检查, 直接调用即可.
        //* subject 采用 env 名 (SOULNOTES_ASR_RUNTIME_DIR), 与其余 issue 的命名面一致, 便于按环境变量定位.
        if(!asrReady.getAsBoolean())
            issues.add(new Issue(Level.WARN, "SOULNOTES_ASR_RUNTIME_DIR", "ASR 运行时未就绪 (缺本地模型或动态库), 语音转写暂不可用; 可经 Setup 向导下载或手动放置运行时目录"));

        if(ctx.profile().equals("prod"))
        {
            final var cors = ctx.view().resolved("quarkus.http.cors.origins", "SOULNOTES_CORS_ORIGINS", "http://localhost:5173");
            if("http://localhost:5173".equals(cors))
                issues.add(new Issue(Level.WARN, "SOULNOTES_CORS_ORIGINS", "prod 使用默认 CORS 白名单, 请按部署环境收紧"));
        }

        //* 规则矩阵: dev 弱密钥属 WARN — 提醒内置默认/弱密钥勿用于生产; 显式弱 JWT (不分 profile) 已由 FieldValidator (GENERATE minLength) 升为 BLOCK, 不在此重复上报.
        if(ctx.profile().equals("dev"))
        {
            final var jwtMeta = byEnv.get("SOULNOTES_JWT_SECRET");
            if(jwtMeta != null)
            {
                //* 默认值取自元数据而非硬编码 — WARN 要覆盖的正是"显式未设置, 落在内置默认密钥上"这一情形.
                final var jwt = ctx.view().resolved(jwtMeta.key(), jwtMeta.envName(), jwtMeta.defaultValue());
                if(!jwt.isBlank() && jwt.length() < jwtMeta.minLength())
                    issues.add(new Issue(Level.WARN, "SOULNOTES_JWT_SECRET", "当前 JWT 密钥为开发默认/弱密钥, 勿用于生产环境"));
            }
        }
    }

    //endregion
}
