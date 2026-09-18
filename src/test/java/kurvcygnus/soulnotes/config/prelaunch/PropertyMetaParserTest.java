package kurvcygnus.soulnotes.config.prelaunch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PropertyMetaParserTest
{
    //* JetBrains 注解为 compileOnly 依赖, 不在 test 编译类路径上, 故测试源不使用 (与既有测试保持一致).
    private static final List<String> FIXTURE = List.of(
        "#* ---- Datasource ----",
        "# @group 数据库",
        "# @name 数据库地址",
        "# @explain 连接地址, 格式 postgresql://host:port/dbname。",
        "# @explain 容器部署时 host 填服务名。",
        "# @input url",
        "# @scheme postgresql://",
        "# @required",
        "quarkus.datasource.reactive.url = ${SOULNOTES_DB_URL:postgresql://localhost:5432/soulnotes}",
        "",
        "# @group 数据库",
        "# @name 数据库用户名",
        "# @input text",
        "# @required",
        "quarkus.datasource.username = ${SOULNOTES_DB_USER:}",
        "",
        "# @group 安全",
        "# @name JWT 签名密钥",
        "# @explain 至少 32 字节。",
        "# @input generate",
        "# @min-length 32",
        "# @required",
        "jwt.secret = ${SOULNOTES_JWT_SECRET:}",
        "",
        "# @group 安全",
        "# @name Token 有效期 (秒)",
        "# @explain 签发的 JWT 有效时长。",
        "# @input int",
        "jwt.ttl-seconds = ${SOULNOTES_JWT_TTL:604800}",
        "",
        "#* 无标签的框架键不进入向导.",
        "mp.jwt.verify.issuer = soul-notes"
    );

    @Test void parseExtractsTagsEnvDefaultAndSkipsUntagged()
    {
        final var items = PropertyMetaParser.parse(FIXTURE);
        assertEquals(4, items.size());

        final var dbUrl = items.getFirst();
        assertEquals("quarkus.datasource.reactive.url", dbUrl.key());
        assertEquals("SOULNOTES_DB_URL", dbUrl.envName());
        assertEquals("postgresql://localhost:5432/soulnotes", dbUrl.defaultValue());
        assertEquals("数据库", dbUrl.group());
        assertEquals("数据库地址", dbUrl.humanName());
        assertEquals("连接地址, 格式 postgresql://host:port/dbname。\n容器部署时 host 填服务名。", dbUrl.explain());
        assertEquals(PropertyMetaParser.InputType.URL, dbUrl.inputType());
        assertEquals("postgresql://", dbUrl.scheme());
        assertTrue(dbUrl.required());

        assertEquals("", items.get(1).defaultValue());
        assertEquals(PropertyMetaParser.InputType.GENERATE, items.get(2).inputType());
        assertEquals(32, items.get(2).minLength());
        assertEquals(PropertyMetaParser.InputType.INT, items.get(3).inputType(), "@input int 必须解析为 INT 类型");
    }

    @Test void parseResourceFindsTaggedItems()
    {
        final var items = PropertyMetaParser.parseResource();
        assertTrue(items.size() >= 20, "真实文件至少 20 个向导条目, 实际: " + items.size());
        assertTrue(items.stream().anyMatch(i -> "SOULNOTES_DB_URL".equals(i.envName())));
        assertTrue(items.stream().allMatch(i -> i.envName() != null));
    }

    //* ASR 组: 三项必须为带标签向导条目且位于 AI 高级组之后 (向导展示顺序即文件顺序).
    @Test void parseResourceAsrGroupTaggedAndPlacedAfterAiAdvanced()
    {
        final var items = PropertyMetaParser.parseResource();
        final var engine = indexOfEnv(items, "SOULNOTES_ASR_ENGINE");
        final var runtimeDir = indexOfEnv(items, "SOULNOTES_ASR_RUNTIME_DIR");
        final var libUrl = indexOfEnv(items, "SOULNOTES_ASR_LIB_URL");
        assertTrue(engine >= 0 && runtimeDir >= 0 && libUrl >= 0, "asr.engine/asr.runtime.dir/asr.lib.url 必须全部带标签进入向导");

        assertEquals(items.get(engine).group(), items.get(runtimeDir).group());
        assertEquals(items.get(runtimeDir).group(), items.get(libUrl).group());
        assertEquals("ASR", items.get(engine).group(), "三个 ASR 项必须同属 ASR 组");
        assertEquals("vosk", items.get(engine).defaultValue(), "asr.engine 默认引擎为 vosk");
        assertEquals("asr-model", items.get(runtimeDir).defaultValue(), "asr.runtime.dir 默认目录");
        assertEquals("", items.get(libUrl).defaultValue(), "asr.lib.url 默认值必须为空 (留空使用内置阿里云镜像)");
        assertTrue(items.get(libUrl).explain().contains("阿里云"), "lib.url 说明必须写明默认来源");

        final var aiAdvanced = IntStream.range(0, items.size()).
            filter(i -> "AI 高级".equals(items.get(i).group())).
            max().orElse(-1);
        assertTrue(aiAdvanced >= 0, "AI 高级组必须存在");
        assertTrue(engine > aiAdvanced, "ASR 组必须排在 AI 高级组之后 (向导展示顺序)");
    }

    //* 集成组 (网络-集成组): Webhook 预警渠道两键必须带标签进入向导, 空 url 即渠道禁用.
    @Test void parseResourceAlertWebhookGroupTaggedAfterNetwork()
    {
        final var items = PropertyMetaParser.parseResource();
        final var url = indexOfEnv(items, "SOULNOTES_ALERT_WEBHOOK_URL");
        final var token = indexOfEnv(items, "SOULNOTES_ALERT_WEBHOOK_TOKEN");
        assertTrue(url >= 0 && token >= 0, "alert.webhook 两键必须全部带标签进入向导");

        assertEquals("集成", items.get(url).group(), "Webhook 两键必须同属集成组");
        assertEquals(items.get(url).group(), items.get(token).group());
        assertEquals("", items.get(url).defaultValue(), "Webhook url 默认必须为空 (空 = 渠道禁用)");
        assertEquals("", items.get(token).defaultValue(), "Webhook token 默认必须为空 (空 = 不带鉴权头)");
        assertTrue(items.get(url).explain().contains("禁用"), "url 说明必须写明空 = 渠道禁用");
        assertTrue(items.get(url).explain().contains("hotline"), "url 说明必须写明负载形状");
        assertTrue(items.get(token).explain().contains("Bearer"), "token 说明必须写明机构侧鉴权方式");

        final var cors = indexOfEnv(items, "SOULNOTES_CORS_ORIGINS");
        assertTrue(cors >= 0, "网络组必须存在");
        assertTrue(url > cors, "集成组必须排在网络组之后 (向导展示顺序即文件顺序)");
    }

    //* issuer 配置化: mp.jwt.verify.issuer 带标签进入向导 (安全组, 与 TokenService 签发同键);
    //* 品牌名属部署微调, 不加标签, 不得进入向导清单.
    @Test void parseResourceIssuerTaggedInSecurityGroupButBrandUntagged()
    {
        final var items = PropertyMetaParser.parseResource();
        final var issuer = indexOfEnv(items, "SOULNOTES_JWT_ISSUER");
        assertTrue(issuer >= 0, "mp.jwt.verify.issuer 必须带标签进入向导");
        assertEquals("安全", items.get(issuer).group());
        assertEquals("soul-notes", items.get(issuer).defaultValue(), "issuer 默认值必须为 soul-notes");

        assertTrue(items.stream().noneMatch(i -> "app.brand-name".equals(i.key())), "品牌名属部署微调, 不得进入向导清单");
    }

    private static int indexOfEnv(List<PropertyMetaParser.ConfigItemMeta> items, String env)
    {
        return IntStream.range(0, items.size()).filter(i -> env.equals(items.get(i).envName())).findFirst().orElse(-1);
    }
}
