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
 * application.properties 注释元数据解析器.
 * <p>向导条目元数据以 {@code # @tag} 注释写在配置项正上方, 本类将其解析为 {@link ConfigItemMeta}.
 * 环境变量名与默认值从值行 {@code ${ENV:default}} 提取, 不在注释中重复.</p>
 * @since 1.1.0
 */
public final class PropertyMetaParser
{
    //* INT 与 NUMBER 分立: NUMBER 允许任意正小数 (temperature/weather 阈值), INT 拒绝小数 —
    //! 消费方为整型配置键时放行 "5.0" 会在 Quarkus bean 创建期才崩溃, 校验层必须能区分两者.
    /**
     * 输入类型, 向导输入框形态与校验规则集的分派键.
     * <p>TEXT 无格式规则; URL 校验 scheme 前缀; SECRET/GENERATE 受 minLength 下限约束;
     * NUMBER 允许任意正小数 (temperature/weather 阈值); INT 仅接受正整数, 额外拒绝小数.</p>
     * @since 1.1.0
     */
    public enum InputType
    {
        TEXT,
        URL,
        SECRET,
        NUMBER,
        INT,
        GENERATE
    }

    /**
     * 单个向导条目的元数据.
     *
     * @param key application.properties 配置键
     * @param envName 值行 {@code ${ENV:default}} 中的环境变量名; 本对象仅对有展开结构的条目产出, 故不会为 null
     * @param defaultValue {@code ${ENV:default}} 中的默认值; 作为显式值未命中时的回退来源
     * @param group 向导分组节标题 ({@code @group}), 决定清单折叠与落盘分节
     * @param humanName 展示用中文名 ({@code @name}), 无标签时回退为 key
     * @param explain 多行解释文本, {@code @explain} 可重复、按行拼接; 无标签时为空串
     * @param inputType 输入类型 ({@code @input}), 决定 {@link FieldValidator} 的规则集; 无标签时为 TEXT
     * @param scheme URL 类条目允许的 scheme 前缀清单 ({@code @scheme}), {@code |} 分隔; 非 URL 类型为空串
     * @param minLength SECRET/GENERATE 的最小长度约束 ({@code @min-length}); 0 表示未声明约束
     * @param required 是否必配 ({@code @required} 标记存在即为 true)
     * @since 1.1.0
     */
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
     * 逐行解析属性文件内容, 产出向导条目元数据.
     * <p>标签行 {@code # @tag arg} 先挂到待归属队列, 遇到值行一并消费; 既非注释也非值行的内容会清空队列,
     * 防止游离标签错误归属到后续键上.</p>
     *
     * @param lines 属性文件的全部行
     * @return 向导条目元数据列表 (文件顺序, 即向导展示顺序); 非向导条目 (无 @group 或无 ${ENV:} 展开) 被静默剔除
     * @since 1.1.0
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
    /**
     * 由标签队列构造单条目元数据; 未知标签静默忽略 (旧解析器可读新文件).
     *
     * @param key 配置键 (值行等号左侧)
     * @param rawValue 值行等号右侧原文, 期望为 {@code ${ENV:default}} 形态
     * @param tags 待归属的 {@code @tag} 标签队列 (按出现顺序)
     * @return 条目元数据; 无 @group 或无 {@code ${ENV:}} 展开时为 null (非向导条目, 调用方跳过)
     * @throws IllegalArgumentException {@code @input} 参数非合法 InputType — 配置文件元数据写错, 快速失败
     * @throws NumberFormatException {@code @min-length} 参数非数字, 同上
     */
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
     * 读取 classpath 上的 application.properties 并解析.
     * <p>classpath 上可能存在多份 (主配置 + profile 变体), 全部解析后按枚举顺序合并.</p>
     *
     * @return 向导条目元数据列表 (不可变)
     * @throws IllegalStateException classpath 上找不到文件, 或读取失败 (构建配置错误, 快速失败)
     * @since 1.1.0
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
