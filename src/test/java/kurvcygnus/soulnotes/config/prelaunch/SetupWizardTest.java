package kurvcygnus.soulnotes.config.prelaunch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SetupWizardTest
{
    //* JetBrains 注解为 compileOnly 依赖, 不在 test 编译类路径上, 故测试源不使用 (与既有测试保持一致).
    //* 脚本行序契约: 模式选择 → (展开态输入行 | esc 放弃 | 序号/q 命令) → 摘要 [Y/n] → 完成屏 1/2; SECRET/GENERATE 项经 readSecret 消费同一队列.
    @TempDir Path dir;

    //* 夹具: 3 必填 (URL/TEXT/GENERATE) + 2 可选 (NUMBER/SECRET), 覆盖全部输入类型与清单状态分支.
    private static List<PropertyMetaParser.ConfigItemMeta> fixture()
    {
        return List.of(
            meta("a.url", "TEST_A_URL", "", "组一", "A 地址", "PostgreSQL 连接说明.", PropertyMetaParser.InputType.URL, "postgresql://", 0, true),
            meta("b.name", "TEST_B_NAME", "", "组一", "B 名称", "", PropertyMetaParser.InputType.TEXT, "", 0, true),
            meta("c.num", "TEST_C_NUM", "0.5", "组二", "C 数值", "", PropertyMetaParser.InputType.NUMBER, "", 0, false),
            meta("d.key", "TEST_D_KEY", "", "组二", "D 密钥", "", PropertyMetaParser.InputType.SECRET, "", 8, false),
            meta("e.jwt", "TEST_E_JWT", "", "组三", "E 密钥", "", PropertyMetaParser.InputType.GENERATE, "", 32, true));
    }

    private static PropertyMetaParser.ConfigItemMeta meta(String key, String env, String defaultValue, String group,
        String name, String explain, PropertyMetaParser.InputType type, String scheme, int minLength, boolean required)
    {
        return new PropertyMetaParser.ConfigItemMeta(key, env, defaultValue, group, name, explain, type, scheme, minLength, required);
    }

    @Test void fullFlowCollectsExplicitValuesOnly() throws Exception
    {
        final var sink = new StringBuilder();
        //* 脚本: 模式回车=简单 → URL → TEXT → GENERATE 空输入自动生成 → 摘要回车确认 → 完成屏选启动.
        final var io = TerminalIO.fake(List.of("", "postgresql://db:5432/sn", "b-name-v", "", "y", "1"), sink);
        final var result = new SetupWizard(dir).run(fixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.LAUNCH, result.action());
        assertEquals(Set.of("TEST_A_URL", "TEST_B_NAME", "TEST_E_JWT"), result.values().keySet(), "values 只含显式输入项, 未动的可选项不入表");
        assertEquals("postgresql://db:5432/sn", result.values().get("TEST_A_URL"));
        final var jwt = result.values().get("TEST_E_JWT");
        assertTrue(jwt.length() >= 32, "GENERATE 空输入须自动生成满足 minLength 的密钥: " + jwt);
        assertDoesNotThrow(() -> Base64.getDecoder().decode(jwt), "GENERATE 产物必须是 base64");

        final var out = sink.toString();
        assertTrue(out.contains("── 组一"), "编辑阶段必须先渲染组头 + v2 清单行");
        assertTrue(out.contains("○ TEST_A_URL"), "未填必填项必须呈空心圆清单行");
        assertTrue(out.contains("PostgreSQL 连接说明."), "展开块的灰斜体解释必须渲染");
        assertTrue(out.contains("******"), "密钥类回显必须掩码");
        assertFalse(out.contains(jwt), "自动生成密钥不得明文出现在 UI 输出");
        final var props = Files.readString(dir.resolve("config/application.properties"));
        //* ConfigWriter 按已写出键的最大宽度对齐: 本次写出的最长键为 6 字符, "a.url" 补 1 空格至同一 "=" 列.
        assertTrue(props.contains("a.url  = postgresql://db:5432/sn"));
        assertFalse(props.contains("c.num"), "未动的可选项不得落盘");
        assertTrue(Files.readString(dir.resolve(".env")).contains("TEST_B_NAME=b-name-v"));
    }

    @Test void invalidInputStaysExpandedUntilValid() throws Exception
    {
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → 首项先输坏 URL (原地红字不折叠) → 再输好 URL → 余项正常 → 摘要确认 → 完成屏退出.
        final var io = TerminalIO.fake(List.of("1", "mysql://x", "postgresql://good:5432/db", "b-v", "", "y", "2"), sink);
        final var result = new SetupWizard(dir).run(fixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action());
        assertEquals("postgresql://good:5432/db", result.values().get("TEST_A_URL"), "留在展开态后最终值必须为合法输入");
        final var out = sink.toString();
        assertTrue(out.contains("✗"), "校验失败必须有红字反馈");
        assertTrue(out.contains("必须以 postgresql:// 开头"), "失败原因必须来自 FieldValidator");
        assertTrue(Files.exists(dir.resolve("config/application.properties")), "确认后正常落盘");
    }

    @Test void secretInputMaskedAndMinLengthEnforced()
    {
        final var sink = new StringBuilder();
        //* 脚本: 全面模式 → esc 折叠首项 → 序号 4 跳到 SECRET 项 → 先短后长 (minLength=8) → esc 折过 GENERATE 项 → q 摘要 → EOF 取消.
        final var io = TerminalIO.fake(List.of("2", "esc", "4", "short", "a-long-secret-99", "esc", "q"), sink);
        final var result = new SetupWizard(dir).run(fixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.CANCELLED, result.action());
        assertTrue(result.values().isEmpty(), "取消后不得返回任何值");
        final var out = sink.toString();
        assertFalse(out.contains("a-long-secret-99"), "SECRET 输入不得明文回显");
        assertTrue(out.contains("长度至少 8 字符"), "minLength 反馈必须可见");
        assertFalse(Files.exists(dir.resolve("config/application.properties")), "取消不落盘");
        assertFalse(Files.exists(dir.resolve(".env")));
    }

    @Test void eofCancelWritesNothing()
    {
        final var sink = new StringBuilder();
        final var io = TerminalIO.fake(List.of(""), sink);  //* 选完模式即在首项输入处 EOF.
        final var result = new SetupWizard(dir).run(fixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.CANCELLED, result.action());
        assertTrue(result.values().isEmpty());
        assertTrue(sink.toString().contains("已取消"));
        assertFalse(Files.exists(dir.resolve("config")), "取消不得创建任何文件或目录");
        assertFalse(Files.exists(dir.resolve(".env")));
    }

    @Test void writeFailureSignalsFailedAndDropsValues() throws Exception
    {
        //* config 被同名普通文件占用 → ConfigWriter#createDirectories 于任何文件写出之前抛 IOException (快速失败路径), 无半写状态.
        Files.createFile(dir.resolve("config"));
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → URL → TEXT → GENERATE 生成 → 摘要确认 → 写盘失败.
        final var io = TerminalIO.fake(List.of("", "postgresql://db:5432/sn", "b-name-v", "", "y"), sink);
        final var result = new SetupWizard(dir).run(fixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.FAILED, result.action());
        assertTrue(result.values().isEmpty(), "FAILED 即未持久化, 必须返回空表 (对齐 \"非空 values ⟺ 已落盘\" 不变量)");
        assertTrue(sink.toString().contains("配置写入失败"), "写盘失败必须红字可见");
        assertFalse(Files.exists(dir.resolve(".env")), "失败早于任何文件写出, 不得产生半写产物");
    }

    @Test void numberJumpSummaryRejectAndRetainValues() throws Exception
    {
        final var sink = new StringBuilder();
        //* 脚本: 模式误输 9 重问 → 全面 → esc 折叠 → 序号 3 跳到可选 NUMBER 项填 1.5 → esc 折过 SECRET 项
        //*       → q 摘要 → n 否决回编辑 → 回车展开首项补填 → esc 折过 TEXT 项 → q 摘要 → 回车确认 → 启动.
        final var io = TerminalIO.fake(List.of(
            "9", "2", "esc", "3", "1.5", "esc", "q", "n", "", "postgresql://x:1/d", "esc", "q", "", "1"), sink);
        final var result = new SetupWizard(dir).run(fixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.LAUNCH, result.action());
        assertEquals(Set.of("TEST_C_NUM", "TEST_A_URL"), result.values().keySet());
        assertEquals("1.5", result.values().get("TEST_C_NUM"), "摘要否决回编辑后已填值必须保留");
        assertTrue(sink.toString().contains("无效选择"), "模式误输必须重问");
        final var props = Files.readString(dir.resolve("config/application.properties"));
        assertTrue(props.contains("c.num = 1.5"));
        assertFalse(props.contains("b.name"), "esc 放弃项不得落盘");
    }

    //* env 注入的密钥经 prefill 进入 values 但不得物化落盘 (README prod 指引: 密钥必须环境变量注入), 摘要屏须以灰字注明.
    @Test void prefilledSecretKeptInValuesButNotPersisted() throws Exception
    {
        final var view = new ConfigView(Map.of(), Map.of("TEST_D_KEY", "env-injected-secret-9"), new Properties());
        final var sink = new StringBuilder();
        //* 脚本: 全面模式 → a.url → b.name → c.num 留空 → d.key (SECRET) 留空保留继承值 → e.jwt 自动生成 → 摘要确认 → 启动.
        final var io = TerminalIO.fake(List.of("2", "postgresql://db:5432/sn", "b-v", "", "", "", "y", "1"), sink);
        final var result = new SetupWizard(dir).run(fixture(), view, io);

        assertEquals(SetupWizard.NextAction.LAUNCH, result.action());
        assertEquals("env-injected-secret-9", result.values().get("TEST_D_KEY"), "继承密钥留在 values (环境仍生效), 仅落盘被排除");
        final var envFile = Files.readString(dir.resolve(".env"));
        assertFalse(envFile.contains("TEST_D_KEY"), ".env 不得物化环境注入的密钥");
        assertFalse(envFile.contains("env-injected-secret-9"), ".env 不得出现继承密钥明文");
        assertFalse(Files.readString(dir.resolve("config/application.properties")).contains("d.key"), "properties 不得物化环境注入的密钥");
        assertTrue(sink.toString().contains("继承自环境"), "摘要屏必须对不落盘的继承密钥给出灰字说明");
        assertTrue(Files.readString(dir.resolve(".env")).contains("TEST_B_NAME=b-v"), "非密钥显式项照常落盘");
    }

    //* 本会话重新输入的密钥覆盖继承值并恢复落盘资格 (排除集随重输移除).
    @Test void reenteredPrefilledSecretIsPersisted() throws Exception
    {
        final var view = new ConfigView(Map.of(), Map.of("TEST_D_KEY", "env-injected-secret-9"), new Properties());
        final var sink = new StringBuilder();
        //* 脚本: 全面模式 → a.url → b.name → c.num 留空 → d.key 本次重新输入 → e.jwt 自动生成 → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of("2", "postgresql://db:5432/sn", "b-v", "", "fresh-secret-77", "", "y", "2"), sink);
        final var result = new SetupWizard(dir).run(fixture(), view, io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action());
        assertEquals("fresh-secret-77", result.values().get("TEST_D_KEY"), "重输值覆盖继承值");
        assertTrue(Files.readString(dir.resolve(".env")).contains("TEST_D_KEY=fresh-secret-77"), "本会话重输的密钥属用户显式值, 必须落盘");
        assertFalse(sink.toString().contains("继承自环境"), "重输后该项不再标记为继承来源");
    }

    @Test void prefilledExplicitValuesCarriedIntoResult() throws Exception
    {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.properties"), "a.url = postgresql://prefilled:5432/x\n");
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → 首项已预填, 回车保留 (不重新输入) → TEXT → GENERATE → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of("", "", "b-v", "", "y", "2"), sink);
        final var result = new SetupWizard(dir).run(fixture(), ConfigView.loadIn(dir), io);

        assertEquals("postgresql://prefilled:5432/x", result.values().get("TEST_A_URL"), "已配置项回车即保留, 无需重新输入");
        assertTrue(sink.toString().contains("postgresql://prefilled:5432/x"), "预填值须以当前值提示可见");
    }
}
