package kurvcygnus.soulnotes;

import kurvcygnus.soulnotes.ai.asr.AsrRuntimeManager;
import kurvcygnus.soulnotes.config.prelaunch.ConfigView;
import kurvcygnus.soulnotes.config.prelaunch.DbTarget;
import kurvcygnus.soulnotes.config.prelaunch.IDatabaseGateway;
import kurvcygnus.soulnotes.config.prelaunch.IPreLaunchTask;
import kurvcygnus.soulnotes.config.prelaunch.ProbeResult;
import kurvcygnus.soulnotes.config.prelaunch.PropertyMetaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EntranceDecisionTest
{
    //region decide: 启动决策矩阵

    @Test void cleanConfigProceeds() { assertEquals(Entrance.Action.PROCEED, Entrance.decide(false, true, false)); }
    @Test void blocksWithTtyOfferWizard() { assertEquals(Entrance.Action.OFFER_WIZARD, Entrance.decide(true, true, false)); }
    @Test void blocksWithoutTtyExit() { assertEquals(Entrance.Action.REPORT_EXIT, Entrance.decide(true, false, false)); }
    @Test void setupFlagShortCircuits() { assertEquals(Entrance.Action.OFFER_WIZARD, Entrance.decide(false, true, true)); }

    //endregion

    //region wantsSetup: --setup/setup 短路进向导

    @Test void longFlagTriggersSetup() { assertTrue(Entrance.wantsSetup("--setup")); }
    @Test void bareWordTriggersSetup() { assertTrue(Entrance.wantsSetup("setup")); }
    @Test void absentFlagKeepsNormalFlow()
    {
        assertFalse(Entrance.wantsSetup());
        assertFalse(Entrance.wantsSetup("--port", "8080"));
    }

    //endregion

    //region formatReport: BLOCK/WARN 分组纯函数

    @Test void reportGroupsBlocksBeforeWarns()
    {
        final var report = Entrance.formatReport(List.of(
            new IPreLaunchTask.Issue(IPreLaunchTask.Level.WARN, "SOULNOTES_AI_API_KEY", "AI 密钥未配置"),
            new IPreLaunchTask.Issue(IPreLaunchTask.Level.BLOCK, "SOULNOTES_DB_PASSWORD", "必填项未配置")
        ));
        final var lines = report.lines().toList();
        assertEquals(2, lines.size());
        assertEquals("❌ [SOULNOTES_DB_PASSWORD] 必填项未配置", lines.get(0));
        assertEquals("⚠ [SOULNOTES_AI_API_KEY] AI 密钥未配置", lines.get(1));
    }

    @Test void emptyIssuesYieldEmptyReport() { assertEquals("", Entrance.formatReport(List.of())); }

    //endregion

    //region asrControl: Pre-Launch ASR 控制装配 (配置值解析)

    //* 工作目录/环境/系统属性中的显式 asr.lib.url 必须进入装配 (条目语义: 自定义 JAR 源, 如含 arm 构建的版本),
    //* 这是 "用户配置自定义 URL 却仍静默下载内置默认" 的回归钉.
    @Test void asrControlResolvesExplicitLibUrlFromWorkdirConfig(@TempDir Path dir) throws Exception
    {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.properties"), """
            asr.runtime.dir = asr-model
            asr.lib.url = https://example.invalid/vosk-arm.jar
            """);
        final var control = Entrance.asrControl(ConfigView.loadIn(dir), PropertyMetaParser.parseResource());

        assertEquals("https://example.invalid/vosk-arm.jar", control.libJarUrl());
    }

    @Test void asrControlFallsBackToDefaultLibUrlWhenUnset(@TempDir Path dir)
    {
        final var control = Entrance.asrControl(ConfigView.loadIn(dir), PropertyMetaParser.parseResource());

        assertEquals(AsrRuntimeManager.DEFAULT_LIB_JAR_URL, control.libJarUrl());
    }

    //endregion

    //region banner: 品牌名配置化 (Spec §7.3)

    //* 品牌行必须位于 ASCII Art 顶部: Art 字模固定不可参数化, 品牌以 Art 上方的文字行呈现.
    @Test void bannerCarriesBrandLineOnTop()
    {
        final var text = Entrance.banner("测试品牌");
        assertTrue(text.startsWith("测试品牌\n"), "品牌行必须是横幅首行, 实际首行: " + text.lines().findFirst().orElse(""));
        assertTrue(text.contains("Start Initializing..."), "固定启动提示不得丢失");
    }

    @Test void bannerKeepsAsciiArt()
    {
        assertTrue(Entrance.banner("Soul Notes").contains("█████"), "ASCII Art 字模不得丢失");
    }

    //* 品牌名与 Pre-Launch 校验同源解析: 系统属性 > 环境变量 > 工作目录 config 文件 > 内置默认.
    @Test void brandNameReadsWorkdirConfigOverDefault(@TempDir Path dir) throws Exception
    {
        assumeTrue(System.getenv("SOULNOTES_BRAND_NAME") == null, "环境变量已设品牌名时无法验证工作目录文件优先级");
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.properties"), "app.brand-name = 文件品牌\n");

        assertEquals("文件品牌", Entrance.resolveBrandName(ConfigView.loadIn(dir)));
    }

    @Test void brandNameFallsBackToDefault(@TempDir Path dir)
    {
        assumeTrue(System.getenv("SOULNOTES_BRAND_NAME") == null, "环境变量已设品牌名时默认值分支不可达");
        assertEquals("Soul Notes", Entrance.resolveBrandName(ConfigView.loadIn(dir)));
    }

    //endregion

    //region runPreLaunchTasks: 配置 + DB 双任务编排 (Task 9)

    @TempDir Path dir;

    //* 固定五态结果的探针伪造: create/apply 属写路径, 编排层 (非 TTY 零写入语义) 不应触达.
    private static IDatabaseGateway fixedGateway(ProbeResult result)
    {
        return new IDatabaseGateway()
        {
            @Override public ProbeResult probe(DbTarget target) { return result; }
            @Override public void createDatabase(DbTarget target) { throw new UnsupportedOperationException(); }
            @Override public void applySchema(DbTarget target) { throw new UnsupportedOperationException(); }
        };
    }

    //* 干净 dev 配置: 显式 AI key (占位哨兵规则) + 显式 DB 凭据 (DB 任务对空必配字段自跳过, 须给足才探测).
    private void writeCleanDevConfig() throws Exception
    {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.properties"), """
            ai.openai.api-key = sk-unit-test-key
            quarkus.datasource.username = kurv
            quarkus.datasource.password = unit-pass
            """);
    }

    //* 回归钉: DB 探测非 OK 必须进入合并问题集 (编排遗漏 DbValidationTask 时本测试即失败).
    @Test void dbProbeBlockSurfacesInCombinedIssues() throws Exception
    {
        writeCleanDevConfig();
        final var issues = Entrance.runPreLaunchTasks(ConfigView.loadIn(dir), PropertyMetaParser.parseResource(), "dev",
            fixedGateway(new ProbeResult(ProbeResult.State.SCHEMA_MISSING, List.of("users"))));
        assertTrue(issues.stream().anyMatch(i ->
            i.level() == IPreLaunchTask.Level.BLOCK && i.subject().equals("SOULNOTES_DB_URL")), "DB 探测 BLOCK 必须出现在合并问题集");
    }

    //* 干净配置 + OK 探测 → 无任何 BLOCK, 证明编排不引入假阳性.
    @Test void cleanConfigAndOkProbeYieldNoBlocks() throws Exception
    {
        writeCleanDevConfig();
        final var issues = Entrance.runPreLaunchTasks(ConfigView.loadIn(dir), PropertyMetaParser.parseResource(), "dev",
            fixedGateway(new ProbeResult(ProbeResult.State.OK, List.of())));
        assertTrue(issues.stream().noneMatch(i -> i.level() == IPreLaunchTask.Level.BLOCK), "干净配置 + OK 探测不得产出 BLOCK: " + issues);
        assertTrue(issues.stream().noneMatch(i -> i.subject().equals("SOULNOTES_DB_URL")), "probe OK 不得产出 DB 问题行");
    }

    //endregion
}
