package kurvcygnus.soulnotes.config.prelaunch;

import kurvcygnus.soulnotes.utils.PrintUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 单字段格式校验器: 向导实时校验与启动校验 (ConfigValidationTask) 共用.
 * <p>两消费方共享同一实现, 保证向导就地拦截与启动期 BLOCK 的规则永不漂移; 本类只查格式,
 * 不产出问题对象, 错误以消息文本交调用方分派等级.</p>
 * @since 1.1.0
 */
public final class FieldValidator
{
    private FieldValidator() { throw new IllegalAccessError("Class \"FieldValidator\" is not meant to be instantized!"); }

    /**
     * 按元数据规则校验单个字段值.
     * <p>空值直接放行: 空值语义交给 required 规则与默认值回退, 这里只查格式 — 否则"必填项未配置"会对同一空值重复上报.</p>
     *
     * @param meta 字段元数据, 其 inputType 决定采用的规则集
     * @param raw  待校验的原始值
     * @return empty 表示合法; present 携带面向用户的错误描述
     * @since 1.1.0
     */
    public static @NotNull Optional<String> validate(@NotNull PropertyMetaParser.ConfigItemMeta meta, @NotNull String raw)
    {
        if(raw.isBlank())
            return Optional.empty();//* 空值语义交给 required 规则与默认值回退, 这里只查格式.
        return switch(meta.inputType())
        {
            case URL -> validateUrl(meta, raw);
            case NUMBER -> parsePositive(raw);
            case INT -> parseIntValue(raw);
            //* 规则矩阵: GENERATE 与 SECRET 同受 minLength 下限约束 — 显式弱 JWT 属格式级 BLOCK, 不分 profile; TEXT 无格式规则.
            case SECRET, GENERATE -> raw.length() >= meta.minLength() ?
                                     Optional.empty() :
                                     Optional.of(PrintUtils.quickFormat("长度至少 {} 字符", meta.minLength()));
            case TEXT -> Optional.empty();
        };
    }

    /** URL 前缀校验: 元数据 scheme 清单 ({@code |} 分隔) 命中任一即合法. */
    private static @NotNull Optional<String> validateUrl(@NotNull PropertyMetaParser.ConfigItemMeta meta, @NotNull String raw)
    {
        for(final var scheme: meta.scheme().split("\\|"))
            if(raw.startsWith(scheme)) return Optional.empty();//* 多 scheme 以 | 分隔, 命中任一即合法.
        return Optional.of(PrintUtils.quickFormat("必须以 {} 开头", meta.scheme().replace("|", " 或 ")));
    }

    /** NUMBER 规则: 任意正小数; 非数字或非正值均返回错误描述. */
    private static @NotNull Optional<String> parsePositive(@NotNull String raw)
    {
        try { return Double.parseDouble(raw) > 0 ? Optional.empty() : Optional.of("必须为正数"); }
        catch(NumberFormatException e) { return Optional.of("必须为数字"); }
    }

    //* INT 消费方是整型配置键 (TTL 秒数/限流上限/字节上限等): 错误延迟到 Quarkus bean 创建期才爆发, 校验层必须前置拦截.
    /**
     * INT 规则: 仅接受正整数, 含小数点的一律拒绝.
     *
     * @param raw 待校验值
     * @return empty 表示合法; present 携带 "必须为正整数" 类错误描述
     */
    private static @NotNull Optional<String> parseIntValue(@NotNull String raw)
    {
        if(raw.indexOf('.') >= 0)
            return Optional.of("必须为正整数");//! "5.0" 可过 Double 解析但整型消费方无法承载, 含 '.' 一律拒绝而非 parseLong 放行.
        try { return Long.parseLong(raw) > 0 ? Optional.empty() : Optional.of("必须为正整数"); }
        catch(NumberFormatException e) { return Optional.of("必须为正整数"); }
    }
}
