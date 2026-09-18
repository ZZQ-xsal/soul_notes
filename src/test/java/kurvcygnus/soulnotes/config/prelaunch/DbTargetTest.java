package kurvcygnus.soulnotes.config.prelaunch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DbTargetTest
{
    @Test void parseWithoutUserinfoFallsBackToParams()
    {
        final var target = DbTarget.parse("postgresql://localhost:5433/soulnotes", "kurv", "secret");
        assertEquals("localhost", target.host());
        assertEquals(5433, target.port());
        assertEquals("soulnotes", target.database());
        assertEquals("kurv", target.user());
        assertEquals("secret", target.password());
    }

    @Test void parseWithUserinfoOverridesParams()
    {
        //* 向导整串粘贴兼容 (Spec §5): userinfo 携带的凭据优先于分离收集的用户名/密码.
        final var target = DbTarget.parse("postgresql://alice:wonder@db.example.com:5432/notes", "kurv", "secret");
        assertEquals("alice", target.user());
        assertEquals("wonder", target.password());
        assertEquals("db.example.com", target.host());
        assertEquals(5432, target.port());
        assertEquals("notes", target.database());
    }

    @Test void parseUserinfoWithoutPasswordKeepsNullPassword()
    {
        final var target = DbTarget.parse("postgresql://alice@localhost:5432/notes", null, null);
        assertEquals("alice", target.user());
        assertNull(target.password());
    }

    @Test void parsePasswordMayContainColonsAndSlashes()
    {
        //* userinfo 先于 host/db 切分, 密码中的 ':' 与 '/' 不得破坏结构解析.
        final var target = DbTarget.parse("postgresql://alice:p@ss/w0rd@localhost:5432/notes", null, null);
        assertEquals("alice", target.user());
        assertEquals("p@ss/w0rd", target.password());
        assertEquals("localhost", target.host());
        assertEquals("notes", target.database());
    }

    @Test void parseDefaultsPortTo5432()
    {
        assertEquals(5432, DbTarget.parse("postgresql://localhost/notes", "u", "p").port());
    }

    @Test void parseIllegalSchemeThrows()
    {
        //* scheme 级校验由 FieldValidator 在配置层 BLOCK, 这里只拒绝结构上无法解析的非 postgresql 串.
        assertThrows(IllegalStateException.class, () -> DbTarget.parse("mysql://localhost/notes", "u", "p"));
        assertThrows(IllegalStateException.class, () -> DbTarget.parse("localhost:5432/notes", "u", "p"));
    }

    @Test void parseMissingDatabaseThrows()
    {
        assertThrows(IllegalStateException.class, () -> DbTarget.parse("postgresql://localhost:5432/", "u", "p"));
        assertThrows(IllegalStateException.class, () -> DbTarget.parse("postgresql://localhost:5432", "u", "p"));
    }

    @Test void parseMissingHostThrows()
    {
        assertThrows(IllegalStateException.class, () -> DbTarget.parse("postgresql://:5432/notes", "u", "p"));
    }

    @Test void parseIllegalPortThrows()
    {
        assertThrows(IllegalStateException.class, () -> DbTarget.parse("postgresql://localhost:port/notes", "u", "p"));
    }
}
