package kurvcygnus.soulnotes.config.prelaunch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FieldValidatorTest
{
    private static PropertyMetaParser.ConfigItemMeta meta(PropertyMetaParser.InputType type, String scheme, int minLen)
    { return new PropertyMetaParser.ConfigItemMeta("k", "ENV_X", "def", "g", "n", "", type, scheme, minLen, false); }

    @Test void urlSchemeCheck_supportsMultiScheme()
    {
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.URL, "redis://|rediss://", 0), "rediss://h:6380").isEmpty());
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.URL, "postgresql://", 0), "mysql://x").isPresent());
    }

    @Test void numberMustParsePositive()
    {
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.NUMBER, "", 0), "20").isEmpty());
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.NUMBER, "", 0), "-1").isPresent());
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.NUMBER, "", 0), "abc").isPresent());
    }

    //* INT 消费方是整型配置键 (TTL 秒数/限流上限/字节上限): "5.0" 能过 Double 解析但 Quarkus bean 创建期会崩溃, 校验层必须前置拒绝小数.
    @Test void intMustBePositiveInteger_noDecimals()
    {
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.INT, "", 0), "20").isEmpty());
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.INT, "", 0), "5.0").isPresent(), "小数形式必须拒绝, 即便数值上是整数");
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.INT, "", 0), "-3").isPresent());
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.INT, "", 0), "abc").isPresent());
    }

    @Test void secretMinLength()
    {
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.SECRET, "", 32), "a".repeat(32)).isEmpty());
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.SECRET, "", 32), "short").isPresent());
    }

    @Test void generateMinLength_sameAsSecret()
    {
        //* 规则矩阵: 显式弱 JWT (GENERATE) 属格式级 BLOCK, minLength 与 SECRET 同规; 空值仍交由 required 规则, 不在格式层报错.
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.GENERATE, "", 32), "").isEmpty());
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.GENERATE, "", 32), "short").isPresent());
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.GENERATE, "", 32), "a".repeat(32)).isEmpty());
    }

    @Test void blankFailsRequired_only()
    {
        //* 空值语义由 required 规则与默认值回退承载, 本类只查格式 — 故 blank 不在此报错, 由 ConfigValidationTask 上报"必填项未配置".
        assertTrue(FieldValidator.validate(meta(PropertyMetaParser.InputType.TEXT, "", 0), "").isEmpty());
    }
}
