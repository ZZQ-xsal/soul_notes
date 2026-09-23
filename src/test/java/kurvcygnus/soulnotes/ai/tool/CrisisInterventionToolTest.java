package kurvcygnus.soulnotes.ai.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link CrisisInterventionTool} 单元测试</b>
 *
 * @author Claude Code
 * @since 1.1.0
 */
class CrisisInterventionToolTest
{
    @Test void getCrisisMessage_ShouldReturnHotlineInfo()
    {
        final var tool = new CrisisInterventionTool("400-161-9995", "12355", "全国心理援助热线");
        final var message = tool.getCrisisMessage("test-user");

        assertNotNull(message);
        assertTrue(message.contains("400"));
        assertTrue(message.contains("12355"));
    }

    @Test void getCrisisMessage_ShouldBeNonEmpty()
    {
        final var tool = new CrisisInterventionTool("400-161-9995", "12355", "测试热线");
        assertFalse(tool.getCrisisMessage("any-user").isBlank());
    }

    @Test void getCrisisMessage_WithCustomHotline()
    {
        final var tool = new CrisisInterventionTool("010-88888888", "010-99999999", "校园心理中心");
        final var message = tool.getCrisisMessage("user-1");

        assertTrue(message.contains("010-88888888"));
        assertTrue(message.contains("校园心理中心"));
        assertTrue(message.contains("备用热线"));
    }

    @Test void getCrisisMessage_WithoutBackup_ShouldOmitBackupLine()
    {
        final var tool = new CrisisInterventionTool("400-161-9995", "", "全国心理援助热线");
        final var message = tool.getCrisisMessage("user-2");

        assertTrue(message.contains("400-161-9995"));
        assertFalse(message.contains("备用热线"));
    }
}
