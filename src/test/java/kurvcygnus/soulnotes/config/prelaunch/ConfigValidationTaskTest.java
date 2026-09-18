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
        final var result = new ConfigValidationTask().run(ctx);
        assertTrue(result.hasBlocks());
        assertTrue(result.issues().stream().anyMatch(i -> "SOULNOTES_JWT_SECRET".equals(i.subject()) && i.level() == IPreLaunchTask.Level.BLOCK));
    }

    @Test void devMissingJwtSecretIsSkipped()
    {
        final var ctx = new PreLaunchContext(view(Map.of()), List.of(DB_URL, JWT, STORM, RAINY, AI_KEY), "dev");
        assertFalse(new ConfigValidationTask().run(ctx).hasBlocks());
    }

    //* 规则矩阵裁定: prod 弱 JWT 为 BLOCK (格式级, GENERATE minLength); dev 显式弱值同为 BLOCK, 仅 dev 内置默认弱密钥降为 WARN.
    @Test void prodExplicitShortJwtBlocks()
    {
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_JWT_SECRET", "short")), List.of(DB_URL, JWT, AI_KEY), "prod");
        final var issues = new ConfigValidationTask().run(ctx).issues();
        assertTrue(issues.stream().anyMatch(i -> "SOULNOTES_JWT_SECRET".equals(i.subject()) && i.level() == IPreLaunchTask.Level.BLOCK));
    }

    @Test void devExplicitShortJwtBlocks()
    {
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_JWT_SECRET", "short")), List.of(DB_URL, JWT, AI_KEY), "dev");
        final var issues = new ConfigValidationTask().run(ctx).issues();
        assertTrue(issues.stream().anyMatch(i -> "SOULNOTES_JWT_SECRET".equals(i.subject()) && i.level() == IPreLaunchTask.Level.BLOCK));
    }

    @Test void devDefaultWeakJwtWarnsOnly()
    {
        //* 显式未设置 + 元数据默认短密钥 = "内置 dev 默认密钥" 情形 — 期望 WARN 提醒且整体无 BLOCK.
        final var weakDefault = new PropertyMetaParser.ConfigItemMeta(JWT.key(), JWT.envName(), "dev-secret", JWT.group(), JWT.humanName(), "", JWT.inputType(), "", JWT.minLength(), JWT.required());
        final var ctx = new PreLaunchContext(view(Map.of()), List.of(DB_URL, weakDefault, AI_KEY), "dev");
        final var result = new ConfigValidationTask().run(ctx);
        assertTrue(result.issues().stream().anyMatch(i -> "SOULNOTES_JWT_SECRET".equals(i.subject()) && i.level() == IPreLaunchTask.Level.WARN));
        assertFalse(result.hasBlocks());
    }

    @Test void weatherThresholdOrderAndRange()
    {
        //* 默认值副本 "0.6" 本身合法 — 越界值由 env 显式注入 (resolved 优先 env), 触发的是区间规则而非次序规则 (无 OVERCAST 条目, 次序检查提前短路).
        final var stormBad = new PropertyMetaParser.ConfigItemMeta(STORM.key(), STORM.envName(), "0.6", STORM.group(), STORM.humanName(), "", STORM.inputType(), "", 0, false);
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_WEATHER_STORM", "1.5")), List.of(stormBad, RAINY), "prod");
        final var issues = new ConfigValidationTask().run(ctx).issues();
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("[0,1]")));
    }

    //* sunny 基于正向均值, 不参与 storm > rainy > overcast 次序比较, 但 Spec §6.1 规定四项阈值均 ∈ [0,1] — 越界同样 BLOCK.
    @Test void sunnyThresholdRangeChecked()
    {
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_WEATHER_SUNNY", "1.5")), List.of(STORM, RAINY, SUNNY), "prod");
        final var issues = new ConfigValidationTask().run(ctx).issues();
        assertTrue(issues.stream().anyMatch(i -> "SOULNOTES_WEATHER_SUNNY".equals(i.subject()) && i.level() == IPreLaunchTask.Level.BLOCK));
    }

    @Test void placeholderAiKeyWarnsNotBlocks()
    {
        final var ctx = new PreLaunchContext(view(Map.of("SOULNOTES_AI_API_KEY", "placeholder")), List.of(DB_URL, JWT, AI_KEY), "prod");
        final var issues = new ConfigValidationTask().run(ctx).issues();
        assertTrue(issues.stream().anyMatch(i -> "SOULNOTES_AI_API_KEY".equals(i.subject()) && i.level() == IPreLaunchTask.Level.WARN));
        assertFalse(issues.stream().anyMatch(i -> "SOULNOTES_AI_API_KEY".equals(i.subject()) && i.level() == IPreLaunchTask.Level.BLOCK));
    }
}
