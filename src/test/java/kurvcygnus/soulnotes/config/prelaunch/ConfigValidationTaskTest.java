package kurvcygnus.soulnotes.config.prelaunch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ConfigValidationTaskTest
{
    private static PropertyMetaParser.ConfigItemMeta meta(String env, String key, String def, PropertyMetaParser.InputType t, String scheme, int minLen, boolean required)
    { return new PropertyMetaParser.ConfigItemMeta(key, env, def, "g", "n", "", t, scheme, minLen, required); }

    private static ConfigView view(Map<String, String> env)
    { return new ConfigView(Map.of(), env, new Properties()); }

    //* 就绪端口注入: 生产传 AsrRuntimeManager::ready, 测试以布尔脚本伪造 (ASR 就绪是文件系统检查, 与校验规则本身无关).
    private static ConfigValidationTask task(boolean asrReady)
    { return new ConfigValidationTask(() -> asrReady); }

    private static final PropertyMetaParser.ConfigItemMeta DB_URL =
        meta("SOULNOTES_DB_URL", "quarkus.datasource.reactive.url", "postgresql://localhost:5432/soulnotes", PropertyMetaParser.InputType.URL, "postgresql://", 0, true);
    private static final PropertyMetaParser.ConfigItemMeta JWT =
        meta("SOULNOTES_JWT_SECRET", "jwt.secret", "", PropertyMetaParser.InputType.GENERATE, "", 32, true);
    private static final PropertyMetaParser.ConfigItemMeta STORM =
        meta("SOULNOTES_WEATHER_STORM", "weather.threshold.storm", "0.8", PropertyMetaParser.InputType.NUMBER, "", 0, false);
    private static final PropertyMetaParser.ConfigItemMeta RAINY =
        meta("SOULNOTES_WEATHER_RAINY", "weather.threshold.rainy", "0.6", PropertyMetaParser.InputType.NUMBER, "", 0, false);
    private static final PropertyMetaParser.ConfigItemMeta SUNNY =
        meta("SOULNOTES_WEATHER_SUNNY", "weather.threshold.sunny", "0.6", PropertyMetaParser.InputType.NUMBER, "", 0, false);
    private static final PropertyMetaParser.ConfigItemMeta AI_KEY =
        meta("SOULNOTES_AI_API_KEY", "ai.openai.api-key", "placeholder", PropertyMetaParser.InputType.SECRET, "", 0, true);

    @Test void prodMissingJwtSecretBlocks()
    {
        final var ctx = new PreLaunchContext(view(Map.of()), List.of(DB_URL, JWT, STORM, RAINY, AI_KEY), "prod");
        final var result = task(true).run(ctx);
        assertTrue(result.hasBlocks());
        assertTrue(result.issues().stream().anyMatch(i -> "SOULNOTES_JWT_SECRET".equals(i.subject()) && i.level() == IPreLaunchTask.Level.BLOCK));
    }

    @Test void devMissingJwtSecretIsSkipped()
    {
        //* AI 密钥已升级为不分 profile 的 BLOCK (无 dev 放宽), 夹具须注入有效密钥才能隔离出 "dev JWT 放宽" 这一本用例语义.
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_AI_API_KEY", "sk-dev-test")), List.of(DB_URL, JWT, STORM, RAINY, AI_KEY), "dev");
        assertFalse(task(true).run(ctx).hasBlocks());
    }

    //* 规则矩阵裁定: prod 弱 JWT 为 BLOCK (格式级, GENERATE minLength); dev 显式弱值同为 BLOCK, 仅 dev 内置默认弱密钥降为 WARN.
    @Test void prodExplicitShortJwtBlocks()
    {
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_JWT_SECRET", "short")), List.of(DB_URL, JWT, AI_KEY), "prod");
        final var issues = task(true).run(ctx).issues();
        assertTrue(issues.stream().anyMatch(i -> "SOULNOTES_JWT_SECRET".equals(i.subject()) && i.level() == IPreLaunchTask.Level.BLOCK));
    }

    @Test void devExplicitShortJwtBlocks()
    {
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_JWT_SECRET", "short")), List.of(DB_URL, JWT, AI_KEY), "dev");
        final var issues = task(true).run(ctx).issues();
        assertTrue(issues.stream().anyMatch(i -> "SOULNOTES_JWT_SECRET".equals(i.subject()) && i.level() == IPreLaunchTask.Level.BLOCK));
    }

    @Test void devDefaultWeakJwtWarnsOnly()
    {
        //* 显式未设置 + 元数据默认短密钥 = "内置 dev 默认密钥" 情形 — 期望 WARN 提醒且整体无 BLOCK.
        final var weakDefault = new PropertyMetaParser.ConfigItemMeta(JWT.key(), JWT.envName(), "dev-secret", JWT.group(), JWT.humanName(), "", JWT.inputType(), "", JWT.minLength(), JWT.required());
        //* 注入有效 AI 密钥: AI 密钥规则不分 profile BLOCK, 不隔离会淹没本用例 "仅 WARN 无 BLOCK" 的断言.
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_AI_API_KEY", "sk-dev-test")), List.of(DB_URL, weakDefault, AI_KEY), "dev");
        final var result = task(true).run(ctx);
        assertTrue(result.issues().stream().anyMatch(i -> "SOULNOTES_JWT_SECRET".equals(i.subject()) && i.level() == IPreLaunchTask.Level.WARN));
        assertFalse(result.hasBlocks());
    }

    @Test void weatherThresholdOrderAndRange()
    {
        //* 默认值副本 "0.6" 本身合法 — 越界值由 env 显式注入 (resolved 优先 env), 触发的是区间规则而非次序规则 (无 OVERCAST 条目, 次序检查提前短路).
        final var stormBad = new PropertyMetaParser.ConfigItemMeta(STORM.key(), STORM.envName(), "0.6", STORM.group(), STORM.humanName(), "", STORM.inputType(), "", 0, false);
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_WEATHER_STORM", "1.5")), List.of(stormBad, RAINY), "prod");
        final var issues = task(true).run(ctx).issues();
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("[0,1]")));
    }

    //* sunny 基于正向均值, 不参与 storm > rainy > overcast 次序比较, 但 Spec §6.1 规定四项阈值均 ∈ [0,1] — 越界同样 BLOCK.
    @Test void sunnyThresholdRangeChecked()
    {
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_WEATHER_SUNNY", "1.5")), List.of(STORM, RAINY, SUNNY), "prod");
        final var issues = task(true).run(ctx).issues();
        assertTrue(issues.stream().anyMatch(i -> "SOULNOTES_WEATHER_SUNNY".equals(i.subject()) && i.level() == IPreLaunchTask.Level.BLOCK));
    }

    //* 用户裁决: AI 为应用必配项, 占位哨兵 placeholder 不得视为已配置 — 不分 profile 一律 BLOCK (有 TTY 引导 Setup, 无 TTY 拒绝启动).
    @Test void placeholderAiKeyBlocks()
    {
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_AI_API_KEY", "placeholder")), List.of(DB_URL, JWT, AI_KEY), "prod");
        final var issues = task(true).run(ctx).issues();
        assertTrue(issues.stream().anyMatch(i -> "SOULNOTES_AI_API_KEY".equals(i.subject()) && i.level() == IPreLaunchTask.Level.BLOCK));
    }

    //* required=false 副本隔离必配规则 (required=true 时空值已由 "必填项未配置" 先行 BLOCK), 独占验证 AI 密钥规则对 resolved 空值同样 BLOCK.
    @Test void emptyAiKeyBlocks()
    {
        final var optionalAiKey = new PropertyMetaParser.ConfigItemMeta(AI_KEY.key(), AI_KEY.envName(), AI_KEY.defaultValue(), AI_KEY.group(), AI_KEY.humanName(), "", AI_KEY.inputType(), "", AI_KEY.minLength(), false);
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_AI_API_KEY", "")), List.of(DB_URL, JWT, optionalAiKey), "prod");
        final var issues = task(true).run(ctx).issues();
        assertTrue(issues.stream().anyMatch(i -> "SOULNOTES_AI_API_KEY".equals(i.subject()) && i.level() == IPreLaunchTask.Level.BLOCK));
    }

    //* ASR 为可插拔能力: 运行时未就绪仅 WARN 不 BLOCK (文字链路与离线热线兜底仍完整可用), 与 AI 密钥 BLOCK 的不对称是有意的.
    @Test void asrRuntimeNotReadyWarnsWithoutBlocking()
    {
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_AI_API_KEY", "sk-dev-test")), List.of(DB_URL), "prod");
        final var result = task(false).run(ctx);
        assertTrue(result.issues().stream().anyMatch(i -> "SOULNOTES_ASR_RUNTIME_DIR".equals(i.subject()) && i.level() == IPreLaunchTask.Level.WARN), "未就绪必须产出 ASR 运行时 WARN");
        assertFalse(result.hasBlocks(), "ASR 未就绪不得产生 BLOCK");
    }

    @Test void asrRuntimeReadyEmitsNoAsrIssue()
    {
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_AI_API_KEY", "sk-dev-test")), List.of(DB_URL), "prod");
        final var issues = task(true).run(ctx).issues();
        assertFalse(issues.stream().anyMatch(i -> "SOULNOTES_ASR_RUNTIME_DIR".equals(i.subject())), "就绪时不得出现任何 ASR WARN");
    }

    //* 裁定 #7: /asr-callback 回调架构已拆除, 对应的回调密钥 WARN 规则随之退役 — 钉死其不得再出现.
    @Test void asrCallbackKeyWarnRuleIsRetired()
    {
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_AI_API_KEY", "sk-dev-test")), List.of(DB_URL), "prod");
        final var issues = task(true).run(ctx).issues();
        assertFalse(issues.stream().anyMatch(i -> "SOULNOTES_ASR_CALLBACK_KEY".equals(i.subject())), "回调架构已拆除, 不得再产出回调密钥 WARN");
    }
}
