package kurvcygnus.soulnotes.utils.constants;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

//* 防漂移: application.properties 的 ${ENV:default} 默认值必须与 ConfigDefaults 常量一致.
class HotlineDefaultsConsistencyTest
{
    //* JetBrains 注解为 compileOnly 依赖, 不在 test 编译类路径上, 故测试源不使用 (与既有测试保持一致).
    private static final Pattern ENV_DEFAULT = Pattern.compile("\\$\\{[A-Z0-9_]+:(.*)}\\s*$");

    private static String defaultValueOf(Properties props, String key)
    {
        final var raw = props.getProperty(key);
        assertNotNull(raw, "缺少配置键: " + key);
        final var matcher = ENV_DEFAULT.matcher(raw);
        assertTrue(matcher.find(), key + " 应为 ${ENV:default} 形式: " + raw);
        return matcher.group(1);
    }

    @Test void hotlineDefaultsInPropertiesMatchConstants() throws Exception
    {
        final var props = loadClasspathProperties();
        assertEquals(ConfigDefaults.HOTLINE_NAME, defaultValueOf(props, "crisis.hotline.name"));
        assertEquals(ConfigDefaults.HOTLINE_PRIMARY, defaultValueOf(props, "crisis.hotline.primary"));
        assertEquals(ConfigDefaults.HOTLINE_BACKUP, defaultValueOf(props, "crisis.hotline.backup"));
    }

    //* src/test/resources/application.properties 在测试类路径上遮蔽 main 的同名资源 (单 getResourceAsStream 只会命中前者),
    //* 故按类路径顺序枚举全部同名资源并依次覆盖加载: Gradle 类路径中 main 资源排在 test 之后, 最终以 main 的值为准.
    private static Properties loadClasspathProperties() throws Exception
    {
        final var props = new Properties();
        final var urls = Collections.list(Thread.currentThread().getContextClassLoader().getResources("application.properties"));
        assertFalse(urls.isEmpty(), "classpath 上未找到 application.properties");
        for(final var url : urls)
        {
            //! Properties.load(InputStream) 固定按 ISO-8859-1 解码, 会把 UTF-8 中文默认值读成乱码 (其中 0x85 等字节
            //! 恰为正则行终止符, 导致 ${ENV:default} 匹配失败); Quarkus 运行时按 UTF-8 读取, 故此处必须显式指定 UTF-8.
            try (var in = url.openStream()) { props.load(new InputStreamReader(in, StandardCharsets.UTF_8)); }
        }
        return props;
    }
}

