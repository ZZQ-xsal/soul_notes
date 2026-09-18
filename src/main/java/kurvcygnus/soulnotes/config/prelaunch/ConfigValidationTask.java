package kurvcygnus.soulnotes.config.prelaunch;

import jakarta.enterprise.context.ApplicationScoped;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>配置校验任务</b> (Spec §6.1 规则矩阵).
 * <p>单字段规则由 {@link FieldValidator} 承载; 必配规则 prod 严格 / dev 放宽 (dev 有内置默认);
 * 弱 JWT 按规则矩阵分派: 显式弱值不分 profile 一律 BLOCK (格式级), 仅 dev 的内置默认弱密钥降为 WARN 提醒;
 * 天气阈值域与次序为跨字段规则, 依据 EmotionWeatherService#mapWeather 分支可达性: storm &gt; rainy &gt; overcast 严格递减,
 * sunny 仅校验 [0,1] 域不参与次序.</p>
 * @since 2.0
 */
@ApplicationScoped
public final class ConfigValidationTask implements IPreLaunchTask
{
    //* dev profile 下允许缺席的必配项 (application-dev.properties 提供运行时默认).
    private static final @NotNull List<String> DEV_RELAXED =
        List.of("SOULNOTES_DB_USER", "SOULNOTES_DB_PASSWORD", "SOULNOTES_JWT_SECRET");

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
        validateWarnRules(ctx, byEnv, issues);
        return new Result(List.copyOf(issues));
    }

    //region 跨字段与警告规则

    //* 跨字段: 阈值 ∈ [0,1] 且 storm > rainy > overcast (严格递减, 否则 RAINY/OVERCAST 分支不可达);
    //* sunny 基于正向均值, 仅受 [0,1] 区间约束 (Spec §6.1 四项同规), 不进入次序比较.
    private static void validateWeather(@NotNull PreLaunchContext ctx, @NotNull Map<String, PropertyMetaParser.ConfigItemMeta> byEnv, @NotNull List<Issue> issues)
    {
        final var storm = threshold(ctx, byEnv, "SOULNOTES_WEATHER_STORM", issues);
        final var rainy = threshold(ctx, byEnv, "SOULNOTES_WEATHER_RAINY", issues);
        final var overcast = threshold(ctx, byEnv, "SOULNOTES_WEATHER_OVERCAST", issues);
        threshold(ctx, byEnv, "SOULNOTES_WEATHER_SUNNY", issues);
        if(storm == null || rainy == null || overcast == null) return;
        if(!(storm > rainy && rainy > overcast))
            issues.add(new Issue(Level.BLOCK, "weather.threshold", "阈值次序必须 storm > rainy > overcast (当前 " + storm + "/" + rainy + "/" + overcast + ")"));
    }

    private static Double threshold(@NotNull PreLaunchContext ctx, @NotNull Map<String, PropertyMetaParser.ConfigItemMeta> byEnv, @NotNull String env, @NotNull List<Issue> issues)
    {
        final var item = byEnv.get(env);
        if(item == null) return null;
        try
        {
            final var v = Double.parseDouble(ctx.view().resolved(item.key(), env, item.defaultValue()));
            if(v < 0 || v > 1) issues.add(new Issue(Level.BLOCK, env, "阈值必须处于 [0,1], 当前 " + v));
            return v;
        }
        catch(NumberFormatException e) { return null; }//! 非法数字已由 FieldValidator 报 BLOCK, 这里吞掉二次异常并跳过次序检查 (首个错误已上报).
    }

    //* 警告规则: 不阻断启动.
    private static void validateWarnRules(@NotNull PreLaunchContext ctx, @NotNull Map<String, PropertyMetaParser.ConfigItemMeta> byEnv, @NotNull List<Issue> issues)
    {
        final var aiKey = ctx.view().resolved("ai.openai.api-key", "SOULNOTES_AI_API_KEY", "placeholder");
        if(aiKey.isBlank() || "placeholder".equals(aiKey))
            issues.add(new Issue(Level.WARN, "SOULNOTES_AI_API_KEY", "AI 密钥未配置, LLM 功能将以降级回复运行"));

        final var asr = ctx.view().explicit("asr.callback.api-key", "SOULNOTES_ASR_CALLBACK_KEY").orElse("");
        if(asr.isBlank())
            issues.add(new Issue(Level.WARN, "SOULNOTES_ASR_CALLBACK_KEY", "ASR 回调密钥为空, /voice/asr-callback 不校验 X-API-Key"));

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
