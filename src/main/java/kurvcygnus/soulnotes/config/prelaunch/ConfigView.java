package kurvcygnus.soulnotes.config.prelaunch;

import kurvcygnus.soulnotes.utils.PrintUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Pre-Launch 配置视图.
 * <p>显式值查找: 系统属性 > 环境变量 > 工作目录 config/application.properties; 未命中回退元数据默认值.
 * classpath 文件的 ${ENV:default} 解析属 Quarkus 运行时职责, 本视图不读取.</p>
 *
 * @implNote 构造时对系统属性/环境变量做一次性快照, 后续查找不再触达进程实况 — 保证向导多轮交互间读数一致.
 * @since 1.1.0
 */
public final class ConfigView
{
    private final @NotNull Map<String, String> sysProps;
    private final @NotNull Map<String, String> env;
    private final @NotNull Properties workdirProps;

    //* 包私有, 测试注入.
    /**
     * 快照注入构造 (包私有, 测试用); 生产经 {@link #loadIn} 装配.
     *
     * @param sysProps 系统属性快照
     * @param env 环境变量快照
     * @param workdirProps 工作目录配置文件内容
     */
    ConfigView(@NotNull Map<String, String> sysProps, @NotNull Map<String, String> env, @NotNull Properties workdirProps)
    {
        this.sysProps = sysProps;
        this.env = env;
        this.workdirProps = workdirProps;
    }

    /**
     * 以当前进程工作目录加载配置视图.
     *
     * @return 配置视图
     * @since 1.1.0
     */
    public static @NotNull ConfigView load() { return loadIn(Path.of("")); }

    /**
     * 以指定工作目录加载配置视图: 捕获系统属性/环境变量快照, 并读取 {@code workDir/config/application.properties} (存在时).
     *
     * @param workDir 工作目录, {@code config/application.properties} 的查找根
     * @return 配置视图
     * @throws IllegalStateException 配置文件存在但读取失败 — 文件损坏属用户可修复错误, 明确报错优于静默
     * @since 1.1.0
     */
    public static @NotNull ConfigView loadIn(@NotNull Path workDir)
    {
        final var props = new Properties();
        final var file = workDir.resolve("config").resolve("application.properties");
        if(Files.isRegularFile(file))
        {
            try(InputStream in = Files.newInputStream(file))
            {
                //* UTF-8 与 ConfigWriter 写入及 Quarkus 运行时读取契约对齐: Properties.load(InputStream) 默认
                //* ISO-8859-1, 会把非 ASCII 显式值 (如品牌名) 读成乱码, 与运行时行为分叉.
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            catch(Exception e) { throw new IllegalStateException(PrintUtils.quickFormat("读取 {} 失败", file), e); }//! 文件损坏属用户可修复错误, 明确报错优于静默.
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
    /**
     * 查找配置项的显式值, 查找顺序: 系统属性 > 环境变量 > 工作目录配置文件.
     *
     * @param propKey application.properties 配置键
     * @param envName 对应环境变量名, 可为 null (无环境变量形态的条目跳过该级)
     * @return 命中的显式值; 三级皆未命中时为 empty — 是否回退默认值由调用方经 {@link #resolved} 决定
     * @since 1.1.0
     */
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

    /**
     * 取配置项的最终生效值: 显式值优先, 未命中回退默认值.
     *
     * @param propKey application.properties 配置键
     * @param envName 对应环境变量名, 可为 null
     * @param defaultValue 回退默认值, null 视为空串
     * @return 生效值, 绝不为 null; 无任何来源时为空串 (调用方据此触发必配检查)
     * @since 1.1.0
     */
    public @NotNull String resolved(@NotNull String propKey, @Nullable String envName, @Nullable String defaultValue)
        { return explicit(propKey, envName).orElseGet(() -> defaultValue == null ? "" : defaultValue); }

    /**
     * 探测当前进程的生效 profile: 系统属性 {@code quarkus.profile} > 环境变量 {@code QUARKUS_PROFILE} > 默认 {@code prod}.
     *
     * @return 生效 profile 名
     * @since 1.1.0
     */
    public static @NotNull String detectProfile() { return detectProfile(snapshotSysProps(), Map.copyOf(System.getenv())); }

    /**
     * 基于给定快照探测生效 profile (与无参版本同规则, 供测试注入快照).
     *
     * @param sysProps 系统属性快照
     * @param env 环境变量快照
     * @return 生效 profile 名; 命中值为空白时归一为 {@code prod}
     * @since 1.1.0
     */
    public static @NotNull String detectProfile(@NotNull Map<String, String> sysProps, @NotNull Map<String, String> env)
    {
        final var p = sysProps.getOrDefault("quarkus.profile", env.getOrDefault("QUARKUS_PROFILE", "prod"));
        return p.isBlank() ? "prod" : p;
    }

    /**
     * 判定当前环境是否为可交互 TTY.
     *
     * @return {@code System.console()} 非 null 时为 true — 启动流程据此决定走 Setup 向导还是直接报告退出
     * @since 1.1.0
     */
    public static boolean tty() { return System.console() != null; }
}
