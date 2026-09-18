package kurvcygnus.soulnotes.config.prelaunch;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * <b>Pre-Launch 配置视图</b>
 * <p>显式值查找: 系统属性 > 环境变量 > 工作目录 config/application.properties; 未命中回退元数据默认值.
 * classpath 文件的 ${ENV:default} 解析属 Quarkus 运行时职责, 本视图不读取 (Spec §6).</p>
 * @since 2.0
 */
public final class ConfigView
{
    private final @NotNull Map<String, String> sysProps;
    private final @NotNull Map<String, String> env;
    private final @NotNull Properties workdirProps;

    //* 包私有, 测试注入.
    ConfigView(@NotNull Map<String, String> sysProps, @NotNull Map<String, String> env, @NotNull Properties workdirProps)
        { this.sysProps = sysProps; this.env = env; this.workdirProps = workdirProps; }

    public static @NotNull ConfigView load() { return loadIn(Path.of("")); }

    public static @NotNull ConfigView loadIn(@NotNull Path workDir)
    {
        final var props = new Properties();
        final var file = workDir.resolve("config").resolve("application.properties");
        if(Files.isRegularFile(file))
        {
            try(InputStream in = Files.newInputStream(file)) { props.load(in); }
            catch(Exception e) { throw new IllegalStateException("读取 " + file + " 失败", e); }//! 文件损坏属用户可修复错误, 明确报错优于静默.
        }
        return new ConfigView(snapshotSysProps(), Map.copyOf(System.getenv()), props);
    }

    //* System.getProperties() 是 Hashtable<Object,Object>, 而 Map.copyOf 需要 Map<String,String>, 必须安全收窄.
    private static @NotNull Map<String, String> snapshotSysProps()
    {
        final var snapshot = new HashMap<String, String>();
        for(final var entry: System.getProperties().entrySet())
        {
            //! Hashtable 理论上可被外部塞入非 String 键值, instanceof 过滤同时规避了 null 值被 String.valueOf 变成 "null" 的问题.
            if(entry.getKey() instanceof String key && entry.getValue() instanceof String value)
                snapshot.put(key, value);
        }
        return Map.copyOf(snapshot);
    }

    //* sysprop > env > workdir.
    public @NotNull Optional<String> explicit(@NotNull String propKey, @Nullable String envName)
    {
        final var sys = sysProps.get(propKey);
        if(sys != null)
            return Optional.of(sys);
        if(envName != null)
        {
            final var envVal = env.get(envName);
            if(envVal != null)
                return Optional.of(envVal);
        }
        final var fileVal = workdirProps.getProperty(propKey);
        return fileVal != null ? Optional.of(fileVal) : Optional.empty();
    }

    public @NotNull String resolved(@NotNull String propKey, @Nullable String envName, @Nullable String defaultValue)
        { return explicit(propKey, envName).orElseGet(() -> defaultValue == null ? "" : defaultValue); }

    public static @NotNull String detectProfile() { return detectProfile(snapshotSysProps(), Map.copyOf(System.getenv())); }

    public static @NotNull String detectProfile(@NotNull Map<String, String> sysProps, @NotNull Map<String, String> env)
    {
        final var p = sysProps.getOrDefault("quarkus.profile", env.getOrDefault("QUARKUS_PROFILE", "prod"));
        return p.isBlank() ? "prod" : p;
    }

    public static boolean tty() { return System.console() != null; }
}
