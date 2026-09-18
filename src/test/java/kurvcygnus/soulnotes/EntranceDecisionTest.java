package kurvcygnus.soulnotes;

import kurvcygnus.soulnotes.config.prelaunch.IPreLaunchTask;
import org.junit.jupiter.api.Test;

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
}
