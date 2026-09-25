package kurvcygnus.soulnotes.config.prelaunch;

import kurvcygnus.soulnotes.ai.asr.AsrRuntimeManager;
import kurvcygnus.soulnotes.utils.PrintUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * 配置校验任务 (规则矩阵).
 * <p>单字段规则由 {@link FieldValidator} 承载; 必配规则 prod 严格 / dev 放宽 (dev 有内置默认);
 * AI 密钥为空或占位哨兵 placeholder 不分 profile 一律 BLOCK (用户裁决: AI 为应用必配项, 无 dev 放宽);
 * 弱 JWT 按规则矩阵分派: 显式弱值不分 profile 一律 BLOCK (格式级), 仅 dev 的内置默认弱密钥降为 WARN 提醒;
 * 天气阈值域与次序为跨字段规则, 依据 EmotionWeatherService#mapWeather 分支可达性: storm &gt; rainy &gt; overcast 严格递减,
 * sunny 仅校验 [0,1] 域不参与次序; 预警外部渠道为健康度警告规则: 短信五键部分配置渠道不生效, 手机号逐号格式校验.</p>
 *
 * <p>Pre-Launch 阶段运行于 CDI 容器启动之前 (Entrance#main 纯构造装配), 无法经容器注入
 * {@link AsrRuntimeManager}, ASR 就绪判定经 {@code BooleanSupplier} 端口传入 (生产传其 ready() 方法引用).</p>
 * @since 1.1.0
 */
public final class ConfigValidationTask implements IPreLaunchTask
{
    //* dev profile 下允许缺席的必配项 (application-dev.properties 提供运行时默认).
    private static final @NotNull List<String> DEV_RELAXED =
        List.of("SOULNOTES_DB_USER", "SOULNOTES_DB_PASSWORD", "SOULNOTES_JWT_SECRET");

    //* 短信渠道值班手机号格式 (大陆): 1 开头 11 位且第 2 位限 [3-9] — 启动期逐号校验, 坏号码 WARN 点名而非静默丢发.
    private static final @NotNull Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\d{9}");

    private final @NotNull BooleanSupplier asrReady;

    /**
     * 纯构造入口 (Pre-Launch 于 CDI 启动前运行, 无容器装配路径).
     *
     * @param asrReady ASR 运行时就绪探测 (生产传 AsrRuntimeManager::ready, 纯文件检查; 测试可伪造)
     * @since 1.1.0
     */
    public ConfigValidationTask(@NotNull BooleanSupplier asrReady)
    {
        this.asrReady = Objects.requireNonNull(
            asrReady,
            "Param \"asrReady\" must not be null!"
        );
    }

    /** @return 固定为 "配置校验". */
    @Override public @NotNull String name() { return "配置校验"; }

    /**
     * 执行全部规则: 单字段格式校验 + 必配检查 (prod 严格/dev 放宽) + 天气阈值跨字段次序 + AI 密钥占位检查 + 非 BLOCK 级警告规则.
     *
     * @param ctx 执行上下文 (配置视图 + 条目元数据 + 生效 profile)
     * @return 收集到的全部问题; 格式/必配/跨字段违规为 BLOCK, ASR 未就绪与 dev 弱密钥等为 WARN
     * @since 1.1.0
     */
    @Override public @NotNull Result run(@NotNull PreLaunchContext ctx)
    {
        final var issues = new ArrayList<Issue>();
        final var byEnv = new HashMap<String, PropertyMetaParser.ConfigItemMeta>();
        for(final var item: ctx.items())
            byEnv.put(item.envName(), item);

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
    /**
     * 天气阈值跨字段规则: 四项阈值逐一查 [0,1] 域, 且要求 storm &gt; rainy &gt; overcast 严格递减
     * (依据 EmotionWeatherService#mapWeather 分支可达性); 任一阈值缺失/非法时跳过次序比较.
     *
     * @param ctx 执行上下文
     * @param byEnv envName 到条目元数据的索引
     * @param issues 问题收集出口
     */
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

    /**
     * 解析单个阈值并查 [0,1] 域: 越域时上报 BLOCK 且仍返回解析值 (供次序比较).
     *
     * @param ctx 执行上下文
     * @param byEnv envName 到条目元数据的索引
     * @param env 阈值条目的环境变量名
     * @param issues 问题收集出口
     * @return 解析值; 条目元数据缺失或值非数字时为 null (非法性已由 FieldValidator 上报, 此处跳过次序检查)
     */
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
    /**
     * AI 密钥占位检查: 为空或等于占位哨兵 {@code placeholder} 时上报 BLOCK, 不分 profile.
     *
     * @param ctx 执行上下文
     * @param issues 问题收集出口
     */
    private static void validateAiKey(@NotNull PreLaunchContext ctx, @NotNull List<Issue> issues)
    {
        final var aiKey = ctx.view().resolved("ai.openai.api-key", "SOULNOTES_AI_API_KEY", "placeholder");
        if(aiKey.isBlank() || "placeholder".equals(aiKey))
            issues.add(new Issue(Level.BLOCK, "SOULNOTES_AI_API_KEY", "AI 密钥未配置 (为空或为占位符 placeholder), 请经 Setup 向导或环境变量提供"));
    }

    //* 警告规则: 不阻断启动. (ASR 就绪判定依赖实例端口的就绪探测, 故为实例方法)
    /**
     * 非 BLOCK 级警告规则: ASR 运行时未就绪 (恒 WARN) + prod 默认 CORS 白名单 + dev 弱 JWT 密钥 + 预警渠道健康度.
     *
     * @param ctx 执行上下文
     * @param byEnv envName 到条目元数据的索引
     * @param issues 问题收集出口
     */
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

        validateAlertChannels(ctx, byEnv, issues);
    }

    //* 预警外部渠道配置健康度 (spec §4): 短信五键部分配置 → WARN 渠道不生效; 手机号逐个格式校验.
    //* 钉钉/企微为单键渠道 (webhook 即完整配置, secret 可选), 无组完整性问题.
    //* WARN 而非 BLOCK 的不对称与 ASR 同理: 渠道为可插拔增强, WebSocket 在线推送与离线热线兜底仍完整可用.
    /**
     * 预警外部渠道配置健康度规则: 短信五键 (AccessKey/Secret/签名/模板/手机号) 部分配置时上报 WARN
     * (渠道暂不生效); 五键齐备时对值班手机号逐个格式校验, 非法号码逐号点名. 钉钉/企微为单键渠道,
     * webhook 即完整配置 (加签密钥可选), 无组完整性问题.
     *
     * @param ctx 执行上下文
     * @param byEnv envName 到条目元数据的索引
     * @param issues 问题收集出口
     */
    private static void validateAlertChannels(
        @NotNull PreLaunchContext ctx, @NotNull Map<String, PropertyMetaParser.ConfigItemMeta> byEnv, @NotNull List<Issue> issues
    )
    {
        final var keys = List.of(
            "SOULNOTES_ALERT_SMS_ACCESS_KEY", "SOULNOTES_ALERT_SMS_SECRET_KEY",
            "SOULNOTES_ALERT_SMS_SIGN_NAME", "SOULNOTES_ALERT_SMS_TEMPLATE_CODE",
            "SOULNOTES_ALERT_SMS_PHONES"
        );
        final var values = keys.stream().map(env -> resolveValue(ctx, byEnv, env)).toList();
        final var anySet = values.stream().anyMatch(v -> v != null && !v.isBlank());
        final var allSet = values.stream().allMatch(v -> v != null && !v.isBlank());
        if(anySet && !allSet)
            issues.add(new Issue(Level.WARN, "SOULNOTES_ALERT_SMS_PHONES",
                "短信渠道配置不完整 (AccessKey/Secret/签名/模板/手机号五键须齐备), 渠道暂不生效"));
        if(allSet)
        {
            //* allSet 谓词已保证末位 (PHONES) 非空, requireNonNull 仅为数据流显式收窄 (零警告).
            final var phones = Objects.requireNonNull(values.getLast(), "allSet 已保证 PHONES 键非空!");
            for(final var phone : phones.split(","))
            {
                final var normalized = phone.strip();
                if(!normalized.isEmpty() && !PHONE_PATTERN.matcher(normalized).matches())
                    issues.add(new Issue(Level.WARN, "SOULNOTES_ALERT_SMS_PHONES",
                        PrintUtils.quickFormat("值班手机号格式非法: {} (应为 1 开头的 11 位数字), 该号码不会收到预警短信", normalized)));
            }
        }
    }

    /**
     * 解析预警渠道单键的展开值 (与 {@link #threshold} 同款经 ctx 解析): 条目元数据缺失时为 null.
     *
     * @param ctx 执行上下文
     * @param byEnv envName 到条目元数据的索引
     * @param env 渠道键的环境变量名
     * @return 展开值; 元数据缺失时为 null (旧版 properties 未含该键, 渠道本体不存在, 视作未配置)
     */
    private static @Nullable String resolveValue(@NotNull PreLaunchContext ctx, @NotNull Map<String, PropertyMetaParser.ConfigItemMeta> byEnv, @NotNull String env)
    {
        final var item = byEnv.get(env);
        return item == null ? null : ctx.view().resolved(item.key(), env, item.defaultValue());
    }

    //endregion
}
