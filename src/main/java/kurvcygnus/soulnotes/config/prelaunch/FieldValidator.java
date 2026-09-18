package kurvcygnus.soulnotes.config.prelaunch;

import kurvcygnus.soulnotes.utils.PrintUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/** <b>单字段校验</b> (向导实时校验与启动校验共用). @since 2.0 */
public final class FieldValidator
{
    private FieldValidator() { throw new IllegalAccessError("Class \"FieldValidator\" is not meant to be instantized!"); }

    /**
     * <span style="color: 95cc6d">按元数据校验单个字段值.</span>
     * <p>空值直接放行: 空值语义交给 required 规则与默认值回退, 这里只查格式 — 否则"必填项未配置"会对同一空值重复上报.</p>
     *
     * @param meta 字段元数据 (决定采用的规则集)
     * @param raw  待校验的原始值
     * @return empty = 合法; present = 面向用户的错误描述
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

    private static @NotNull Optional<String> validateUrl(@NotNull PropertyMetaParser.ConfigItemMeta meta, @NotNull String raw)
    {
        for(final var scheme: meta.scheme().split("\\|"))
            if(raw.startsWith(scheme)) return Optional.empty();//* 多 scheme 以 | 分隔, 命中任一即合法.
        return Optional.of(PrintUtils.quickFormat("必须以 {} 开头", meta.scheme().replace("|", " 或 ")));
    }

    private static @NotNull Optional<String> parsePositive(@NotNull String raw)
    {
        try { return Double.parseDouble(raw) > 0 ? Optional.empty() : Optional.of("必须为正数"); }
        catch(NumberFormatException e) { return Optional.of("必须为数字"); }
    }

    //* INT 消费方是整型配置键 (TTL 秒数/限流上限/字节上限等): 错误延迟到 Quarkus bean 创建期才爆发, 校验层必须前置拦截.
    private static @NotNull Optional<String> parseIntValue(@NotNull String raw)
    {
        if(raw.indexOf('.') >= 0)
            return Optional.of("必须为正整数");//! "5.0" 可过 Double 解析但整型消费方无法承载, 含 '.' 一律拒绝而非 parseLong 放行.
        try { return Long.parseLong(raw) > 0 ? Optional.empty() : Optional.of("必须为正整数"); }
        catch(NumberFormatException e) { return Optional.of("必须为正整数"); }
    }
}
