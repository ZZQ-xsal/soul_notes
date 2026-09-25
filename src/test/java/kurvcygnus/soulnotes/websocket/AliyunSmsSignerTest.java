package kurvcygnus.soulnotes.websocket;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>阿里云 RPC 签名纯函数钉死</b>
 * <p>签名测试哲学: canonical query 与 stringToSign 以固定 nonce/timestamp 手写字符串钉死 (完全确定),
 * HmacSHA1/Base64 为 JDK 标准件不再自证; 签名端到端正确性由回环服务器请求断言 + 首次真机部署实测兜底
 * (spec §8).</p>
 * <p>独立钉死不依赖 JetBrains Annotations: 本测试源不使用其注解, 无 NullableProblems 检查面.</p>
 * @since 1.3.0
 */
class AliyunSmsSignerTest
{
    private static final AliyunSmsSigner.SendSmsParams PARAMS = new AliyunSmsSigner.SendSmsParams(
        "testid", "测试签名", "SMS_12345678", "{\"level\":\"RED\"}", "13800138000",
        "https://dysmsapi.aliyuncs.com"
    );
    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
    private static final String NONCE = "nonce-0001";

    @Test void buildSignedUrl_ProducesDeterministicCanonicalQuery()
    {
        final var url = AliyunSmsSigner.buildSignedUrl("testsecret", PARAMS, NOW, NONCE);
        //* 固定 nonce/timestamp 下 canonical 完全确定: 按 key 字典序, RFC3986 形态编码.
        assertTrue(url.contains("AccessKeyId=testid"), "公网参数必须包含");
        assertTrue(url.contains("Action=SendSms"));
        assertTrue(url.contains("PhoneNumbers=13800138000"));
        assertTrue(url.contains("RegionId=cn-hangzhou"));
        assertTrue(url.contains("TemplateParam=%7B%22level%22%3A%22RED%22%7D"), "JSON 模板参数必须整体编码");
        assertTrue(url.contains("Timestamp=2026-01-02T03%3A04%3A05Z"), "UTC ISO8601 形态");
        assertTrue(url.contains("SignatureNonce=nonce-0001"));
        assertTrue(url.contains("SignatureMethod=HMAC-SHA1"));
        assertTrue(url.contains("SignatureVersion=1.0"));
        assertTrue(url.contains("Version=2017-05-25"));
        assertTrue(url.contains("Format=JSON"));
        assertTrue(url.contains("SignName=" + AliyunSmsSigner.percentEncode("测试签名")));
        assertTrue(url.contains("&Signature="), "签名必须追加在 canonical 之后");
        assertTrue(url.indexOf("Version=2017-05-25") < url.indexOf("&Signature="), "canonical 段必须先于 Signature");
    }

    @Test void buildSignedUrl_CanonicalIsSortedBeforeSignature()
    {
        final var url = AliyunSmsSigner.buildSignedUrl("testsecret", PARAMS, NOW, NONCE);
        final var query = url.substring(url.indexOf("?") + 1);
        final var withoutSig = query.substring(0, query.indexOf("&Signature="));
        final var keys = java.util.Arrays.stream(withoutSig.split("&")).
            map(pair -> pair.substring(0, pair.indexOf('='))).toList();
        final var sorted = keys.stream().sorted().toList();
        assertEquals(sorted, keys, "canonical 参数必须按 key 字典序 (签名算入顺序)");
    }

    @Test void hmacSha1Base64_IsDeterministic()
    {
        final var first = AliyunSmsSigner.hmacSha1Base64("testsecret&", "GET&%2F&AccessKeyId%3Dtestid");
        final var second = AliyunSmsSigner.hmacSha1Base64("testsecret&", "GET&%2F&AccessKeyId%3Dtestid");
        assertEquals(first, second, "同输入恒同输出");
        assertNotEquals(AliyunSmsSigner.hmacSha1Base64("other&", "x"), first, "密钥变化输出必须变化");
    }

    @Test void percentEncode_FollowsAliyunRfc3986Form()
    {
        assertEquals("a%20b", AliyunSmsSigner.percentEncode("a b"), "空格必须 %20 (非 +)");
        assertEquals("%2A", AliyunSmsSigner.percentEncode("*"));
        assertEquals("~", AliyunSmsSigner.percentEncode("~"), "~ 不得编码 (RFC3986 unreserved)");
        assertEquals("%E6%B5%8B", AliyunSmsSigner.percentEncode("测"), "UTF-8 多字节编码");
    }

    @Test void formatUtc_IsIso8601Zulu()
    {
        assertEquals("2026-01-02T03:04:05Z", AliyunSmsSigner.formatUtc(Instant.parse("2026-01-02T03:04:05Z")));
        assertEquals("2026-01-02T03:04:05Z", AliyunSmsSigner.formatUtc(Instant.parse("2026-01-02T11:04:05+08:00")), "跨时区 Instant 必须换算到 UTC");
    }

    //region I2 golden vector

    //* 官方 RPC 签名机制示例参数集 (testid/testsecret, DescribeInstances, 固定 SignatureNonce/Timestamp —
    //* 参数集取自官方文档算例) 的待签串与签名值钉死. 期望值生成方式: 官方帮助页正文为 SPA 无法直接引用期望
    //* Signature 值, 按预案以独立 JDK 脚本离线复算生成 (不经过生产代码, 与 Task 1 的 Python 交叉验证同法);
    //* canonical 构造顺序由 buildSignedUrl_CanonicalIsSortedBeforeSignature 排序断言另钉.
    @Test void officialRpcExampleVector_StringToSignAndSignature()
    {
        final var params = new java.util.TreeMap<String, String>();
        params.put("AccessKeyId", "testid");
        params.put("Action", "DescribeInstances");
        params.put("Format", "XML");
        params.put("SignatureNonce", "3ee8c1b8-83cc-11e6-a2c6-50b8a8a5b6f4");
        params.put("Timestamp", "2016-09-27T14:12:00Z");
        params.put("Version", "2014-05-26");
        final var canonical = params.entrySet().stream().
            map(e -> AliyunSmsSigner.percentEncode(e.getKey()) + "=" + AliyunSmsSigner.percentEncode(e.getValue())).
            collect(java.util.stream.Collectors.joining("&"));
        final var stringToSign = "GET&" + AliyunSmsSigner.percentEncode("/") + "&" + AliyunSmsSigner.percentEncode(canonical);
        assertEquals(
            "GET&%2F&AccessKeyId%3Dtestid%26Action%3DDescribeInstances%26Format%3DXML%26SignatureNonce%3D3ee8c1b8-83cc-11e6-a2c6-50b8a8a5b6f4%26Timestamp%3D2016-09-27T14%253A12%253A00Z%26Version%3D2014-05-26",
            stringToSign,
            "官方示例参数集的待签串 (Timestamp 冒号二次编码 %253A 是经典易错点)");
        assertEquals("cqBaCEqwuDUJxX/WeU1wpRJrGBA=",
            AliyunSmsSigner.hmacSha1Base64("testsecret&", stringToSign),
            "官方示例参数集的签名值 (独立脚本复算向量)");
    }

    //* buildSignedUrl 端到端黄金向量: 与 PARAMS/NOW/NONCE 常量同参, 独立脚本复算的完整 URL 逐字符钉死 —
    //* 固定参数集组装 / 排序 / 双重编码 / 签名追加位任何一环出错都会破坏该向量.
    @Test void buildSignedUrl_FullUrlGoldenVector()
    {
        assertEquals(
            "https://dysmsapi.aliyuncs.com/?AccessKeyId=testid&Action=SendSms&Format=JSON&PhoneNumbers=13800138000"
                + "&RegionId=cn-hangzhou&SignName=%E6%B5%8B%E8%AF%95%E7%AD%BE%E5%90%8D&SignatureMethod=HMAC-SHA1"
                + "&SignatureNonce=nonce-0001&SignatureVersion=1.0&TemplateCode=SMS_12345678"
                + "&TemplateParam=%7B%22level%22%3A%22RED%22%7D&Timestamp=2026-01-02T03%3A04%3A05Z&Version=2017-05-25"
                + "&Signature=QHSYIDCv8vWsWPyVfQCbcOT8Dy8%3D",
            AliyunSmsSigner.buildSignedUrl("testsecret", PARAMS, NOW, NONCE),
            "SendSms 完整签名 URL 黄金向量 (独立脚本复算)");
    }

    //endregion
}
