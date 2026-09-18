package kurvcygnus.soulnotes.config.prelaunch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ConfigViewTest
{
    //* JetBrains 注解为 compileOnly 依赖, 不在 test 编译类路径上, 故测试源不使用 (与既有测试保持一致).
    private final ConfigView view = new ConfigView(
        Map.of("jwt.secret", "from-sysprop"),
        Map.of("SOULNOTES_JWT_SECRET", "from-env"),
        propsOfFile("jwt.secret = from-workdir\nchat.history.max-messages = 33")
    );

    private static Properties propsOfFile(String content)
    {
        final var p = new Properties();
        try { p.load(new java.io.StringReader(content)); } catch(java.io.IOException e) { throw new IllegalStateException(e); }
        return p;
    }

    @Test void explicitPrioritySyspropOverEnvOverWorkdir()
    {
        assertEquals(Optional.of("from-sysprop"), view.explicit("jwt.secret", "SOULNOTES_JWT_SECRET"));
        assertEquals(Optional.of("from-env"),//* 无 sysprop 时 env 胜过工作目录, 印证优先级中段 (工作目录回退由 loadReadsWorkdirConfigFile 覆盖).
            new ConfigView(Map.of(), Map.of("SOULNOTES_JWT_SECRET", "from-env"), propsOfFile("jwt.secret = from-workdir")).
                explicit("jwt.secret", "SOULNOTES_JWT_SECRET"));
        assertEquals(Optional.empty(), view.explicit("nope.key", "NOPE_ENV"));//* 三层皆未命中才返回 empty (fixture 的 chat.history.max-messages 命中工作目录层, 不能用作 absent 用例).
    }

    @Test void resolvedFallsBackToDefault()
    {
        assertEquals("33", view.resolved("chat.history.max-messages", "SOULNOTES_CHAT_HISTORY_MAX", "50"));
        assertEquals("50", view.resolved("nope.key", "NOPE_ENV", "50"));
    }

    @Test void loadReadsWorkdirConfigFile(@TempDir Path dir) throws Exception
    {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.properties"), "jwt.secret = workdir-value\n");
        final var loaded = ConfigView.loadIn(dir);
        assertEquals(Optional.of("workdir-value"), loaded.explicit("jwt.secret", "SOULNOTES_JWT_SECRET"));
    }

    @Test void detectProfileDefaultsToProd()
    { assertEquals("prod", ConfigView.detectProfile(Map.of(), Map.of())); assertEquals("dev", ConfigView.detectProfile(Map.of("quarkus.profile", "dev"), Map.of())); }
}
