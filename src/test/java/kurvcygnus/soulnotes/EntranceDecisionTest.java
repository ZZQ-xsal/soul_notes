package kurvcygnus.soulnotes;

import kurvcygnus.soulnotes.ai.asr.AsrRuntimeManager;
import kurvcygnus.soulnotes.config.prelaunch.ConfigView;
import kurvcygnus.soulnotes.config.prelaunch.IPreLaunchTask;
import kurvcygnus.soulnotes.config.prelaunch.PropertyMetaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
}
