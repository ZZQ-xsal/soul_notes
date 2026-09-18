package kurvcygnus.soulnotes.config.prelaunch;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
