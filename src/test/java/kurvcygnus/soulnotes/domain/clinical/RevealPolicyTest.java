package kurvcygnus.soulnotes.domain.clinical;

import kurvcygnus.soulnotes.domain.clinical.RevealPolicy.RevealLevel; //* RevealLevel 是 RevealPolicy 的嵌套类型, 同包也无法用简单名引用, 必须显式导入.
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>RevealPolicy 脱敏矩阵</b>: 3 档配置 × YELLOW/RED × 解锁/掩码全覆盖 + 短码稳定性.
 * @since 1.2.0
 */
@SuppressWarnings("NullableProblems")//! 测试模块不使用 JetBrains Annotations (项目测试惯例).
class RevealPolicyTest
{
    private static final UUID ID = UUID.fromString("a1b2c3d4-e5f6-4789-a012-3456789abcde");
    private static final String NAME = "alice";

    @Test void redConfig_UnlocksOnlyRed()
    {
        var red = RevealPolicy.identity(RevealLevel.RED, "RED", ID, NAME);
        assertEquals(ID.toString(), red.userId(), "RED 配置下 RED 记录解锁完整 UUID");
        assertEquals(NAME, red.displayName(), "RED 配置下 RED 记录解锁实名");

        var yellow = RevealPolicy.identity(RevealLevel.RED, "YELLOW", ID, NAME);
        assertEquals("a1b2c3d4", yellow.userId(), "RED 配置下 YELLOW 记录掩码为 8 位短码");
        assertEquals("学生 #a1b2c3d4", yellow.displayName());
    }

    @Test void yellowConfig_UnlocksBoth()
    {
        assertEquals(NAME, RevealPolicy.identity(RevealLevel.YELLOW, "YELLOW", ID, NAME).displayName());
        assertEquals(NAME, RevealPolicy.identity(RevealLevel.YELLOW, "RED", ID, NAME).displayName());
    }

    @Test void neverConfig_UnlocksNothing()
    {
        assertEquals("a1b2c3d4", RevealPolicy.identity(RevealLevel.NEVER, "RED", ID, NAME).userId());
        assertEquals("学生 #a1b2c3d4", RevealPolicy.identity(RevealLevel.NEVER, "RED", ID, NAME).displayName());
    }

    @Test void shortCode_IsStableAcrossCalls()
    {
        var first = RevealPolicy.identity(RevealLevel.RED, "YELLOW", ID, NAME);
        var second = RevealPolicy.identity(RevealLevel.RED, "YELLOW", ID, NAME);
        assertEquals(first.userId(), second.userId(), "同 userId 恒定同短码 (时间线分页前后一致)");
    }

    @Test void parse_InvalidFallsBackToRed()
    {
        assertEquals(RevealLevel.RED, RevealLevel.parse(null));
        assertEquals(RevealLevel.RED, RevealLevel.parse("bogus"));
        assertEquals(RevealLevel.YELLOW, RevealLevel.parse("yellow"));
        assertEquals(RevealLevel.NEVER, RevealLevel.parse("NEVER"));
    }
}
