package kurvcygnus.soulnotes.config.prelaunch;

import kurvcygnus.soulnotes.utils.PrintUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>application.properties 注释元数据解析器</b>
 * <p>向导条目元数据以 {@code # @tag} 注释写在配置项正上方, 本类将其解析为 {@link ConfigItemMeta}.
 * 环境变量名与默认值从值行 {@code ${ENV:default}} 提取, 不在注释中重复.</p>
 * @since 2.0
 */
public final class PropertyMetaParser
{
    //* INT 与 NUMBER 分立: NUMBER 允许任意正小数 (temperature/weather 阈值), INT 拒绝小数 —
    //! 消费方为整型配置键时放行 "5.0" 会在 Quarkus bean 创建期才崩溃, 校验层必须能区分两者.
    public enum InputType
    {
        TEXT,
        URL,
        SECRET,
        NUMBER,
        INT,
        GENERATE
    }

    public record ConfigItemMeta(
        String key,
        String envName,
        String defaultValue,
        String group,
        String humanName,
        String explain,
        InputType inputType,
        String scheme,
        int minLength,
        boolean required
    )
    {}

    private static final @NotNull Pattern VALUE_LINE = Pattern.compile("^([A-Za-z0-9._-]+)\\s*=\\s*(.*)$");
    private static final @NotNull Pattern ENV_DEFAULT = Pattern.compile("^\\$\\{([A-Z0-9_]+):(.*)}\\s*$", Pattern.DOTALL);

    private PropertyMetaParser() { throw new IllegalAccessError("Class \"PropertyMetaParser\" is not meant to be instantized!"); }

    //region 行解析

    /**
     * <span style="color: 95cc6d">逐行解析属性文件内容, 产出向导条目元数据.</span>
     * <p>标签行 {@code # @tag arg} 先挂到待归属队列, 遇到值行一并消费; 既非注释也非值行的内容会清空队列,
     * 防止游离标签错误归属到后续键上.</p>
     *
     * @param lines 属性文件的全部行
     * @return 向导条目元数据列表 (文件顺序, 即向导展示顺序)
     */
    public static @NotNull List<ConfigItemMeta> parse(@NotNull List<String> lines)
    {
        final var items = new ArrayList<ConfigItemMeta>();
        final var tags  = new ArrayList<String>();//* 当前待归属的注释标签 (按出现顺序).
        for(final var rawLine: lines)
        {
            final var line = rawLine.strip();
            if(line.startsWith("# @"))
            {
                tags.add(line.substring(3));
                continue;
            }
            if(line.isEmpty() || line.startsWith("#")) continue;

            final var matcher = VALUE_LINE.matcher(line);
            if(!matcher.matches())
            {
                tags.clear();
                continue;
            }
            if(!tags.isEmpty())
            {
                //! buildMeta 对非向导条目 (无 @group 或无 ${ENV:} 展开) 返回 null, 必须跳过而非入列.
                final var meta = buildMeta(matcher.group(1), matcher.group(2).strip(), List.copyOf(tags));
                if(meta != null) items.add(meta);
            }
            tags.clear();
        }
        return items;
    }

    //* 标签语义: group/name/explain(可重复)/input/scheme/min-length/required.
    private static @Nullable ConfigItemMeta buildMeta(@NotNull String key, @NotNull String rawValue, @NotNull List<String> tags)
    {
        var group = "";
        var humanName = key;
        final var explain = new StringBuilder();
        var inputType = PropertyMetaParser.InputType.TEXT;
        var scheme = "";
        var minLength = 0;
        var required = false;
        
        for(final var tag: tags)
        {
            final var sp = tag.indexOf(' ');
            final var name = sp < 0 ? tag : tag.substring(0, sp);
            final var arg  = sp < 0 ? "" : tag.substring(sp + 1).strip();
            switch(name)
            {
                case "group" -> group = arg;
                case "name" -> humanName = arg;
                case "explain" ->
                {
                    if(!explain.isEmpty()) explain.append('\n');
                    explain.append(arg);
                }
                case "input" -> inputType = PropertyMetaParser.InputType.valueOf(arg.toUpperCase());
                case "scheme" -> scheme = arg;
                case "min-length" -> minLength = Integer.parseInt(arg);
                case "required" -> required = true;
                //* 未知标签静默忽略, 保证旧版本解析器可读新文件.
                default -> {}
            }
        }
        final Matcher env = ENV_DEFAULT.matcher(rawValue);
        final var envName = env.matches() ? env.group(1) : null;
        final var defaultValue = env.matches() ? env.group(2) : null;
        if(group.isEmpty() || envName == null) return null;//? 无 group 或无 env 的项视为非向导条目, 不进入向导.
        return new ConfigItemMeta(key, envName, defaultValue, group, humanName, explain.toString(), inputType, scheme, minLength, required);
    }

    //endregion

    //region 资源读取

    /**
     * <span style="color: 95cc6d">读取 classpath 上的 application.properties 并解析.</span>
     *
     * @return 向导条目元数据列表
     * @throws IllegalStateException classpath 上找不到文件, 或读取失败 (构建配置错误, 快速失败)
     */
    public static @NotNull List<ConfigItemMeta> parseResource()
    {
        final List<URL> urls;
        try { urls = Collections.list(Thread.currentThread().getContextClassLoader().getResources("application.properties")); }
        catch(IOException e) { throw new IllegalStateException("枚举 classpath 上的 application.properties 失败", e); }
        if(urls.isEmpty())
            throw new IllegalStateException("classpath 缺少 application.properties (native 需 resources.includes)");//! 构建配置错误, 快速失败.

        final var items = new ArrayList<ConfigItemMeta>();
        for(final var url : urls)
        {
            try(final var reader = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) { items.addAll(parse(reader.lines().toList())); }
            catch(IOException e) { throw new IllegalStateException(PrintUtils.quickFormat("读取 application.properties 失败: {}", url), e); }
        }
        return List.copyOf(items);
    }

    //endregion
}
