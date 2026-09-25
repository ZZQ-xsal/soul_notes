package kurvcygnus.soulnotes.websocket;

import org.jetbrains.annotations.NotNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 阿里云短信 SendSms RPC 签名构造 (零 SDK 直连).
 * <p>签名流程与 V3 之前的经典 RPC 形态一致: 参数按 key 字典序 → RFC3986 形态百分号编码拼接 canonical
 * → {@code GET&%2F&<canonical>} 为待签串 → {@code HmacSHA1(secret + "&")} → Base64.</p>
 *
 * <p>独立纯函数类: 可单测钉死; 若阿里云未来强制 V3 (TC3 形态) 可整体替换不影响渠道实现.</p>
 * @since 1.3.0
 */
public final class AliyunSmsSigner
{
    private AliyunSmsSigner() { throw new IllegalAccessError("Class \"AliyunSmsSigner\" is not meant to be instantized!"); }

    /**
     * 一次 SendSms 调用的业务参数 (签名无关的连接信息经 endpoint 传入, 供测试回环覆盖).
     *
     * @param accessKeyId  阿里云 AccessKey ID
     * @param signName     短信签名名称 (报审件)
     * @param templateCode 模板 Code (报审件, 占位符钉死 level/hotline/student)
     * @param templateParam 模板参数 JSON 串
     * @param phone        目标手机号 (单号, 群发由渠道层逐号调用)
     * @param endpoint     API 端点 (默认正式域名, 测试回环可覆盖)
     */
    public record SendSmsParams(
        @NotNull String accessKeyId,
        @NotNull String signName,
        @NotNull String templateCode,
        @NotNull String templateParam,
        @NotNull String phone,
        @NotNull String endpoint
    )
    {
        //* record 紧凑构造器逐字段断言: 签名入参缺一即快速失败, 不让 null 漂到编码/拼接深处才炸.
        public SendSmsParams
        {
            Objects.requireNonNull(accessKeyId, "Param \"accessKeyId\" must not be null!");
            Objects.requireNonNull(signName, "Param \"signName\" must not be null!");
            Objects.requireNonNull(templateCode, "Param \"templateCode\" must not be null!");
            Objects.requireNonNull(templateParam, "Param \"templateParam\" must not be null!");
            Objects.requireNonNull(phone, "Param \"phone\" must not be null!");
            Objects.requireNonNull(endpoint, "Param \"endpoint\" must not be null!");
        }
    }

    /**
     * 构造带签名的完整请求 URL.
     *
     * @param secretKey 阿里云 AccessKey Secret
     * @param p         业务参数
     * @param now       当前时刻 (UTC, 签名 Timestamp 形态)
     * @param nonce     签名唯一随机串 (UUID; 参数化以保证纯函数可测)
     * @return 完整 GET URL (含 Signature)
     */
    public static @NotNull String buildSignedUrl(@NotNull String secretKey, @NotNull SendSmsParams p, @NotNull Instant now, @NotNull String nonce)
    {
        //* 签名密钥与 nonce 不走 TreeMap 收集路径, 必须单独前置断言 (方法体最先接触它们).
        Objects.requireNonNull(secretKey, "Param \"secretKey\" must not be null!");
        Objects.requireNonNull(nonce, "Param \"nonce\" must not be null!");
        final var params = new TreeMap<String, String>();
        params.put("AccessKeyId", p.accessKeyId());
        params.put("Action", "SendSms");
        params.put("Format", "JSON");
        params.put("PhoneNumbers", p.phone());
        params.put("RegionId", "cn-hangzhou");
        params.put("SignName", p.signName());
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", nonce);
        params.put("SignatureVersion", "1.0");
        params.put("TemplateCode", p.templateCode());
        params.put("TemplateParam", p.templateParam());
        params.put("Timestamp", formatUtc(now));
        params.put("Version", "2017-05-25");
        //* TreeMap 迭代天然字典序, joining 直接收口拼接 (方法引用/收集器倾向, 免去手工 reduce).
        final var canonical = params.entrySet().stream().
            map(e -> percentEncode(e.getKey()) + "=" + percentEncode(e.getValue())).
            collect(Collectors.joining("&"));
        final var stringToSign = "GET&" + percentEncode("/") + "&" + percentEncode(canonical);
        final var signature = hmacSha1Base64(secretKey + "&", stringToSign);
        return p.endpoint() + "/?" + canonical + "&Signature=" + percentEncode(signature);
    }

    //* 阿里云 Timestamp 形态: UTC ISO8601 (yyyy-MM-dd'T'HH:mm:ss'Z').
    static @NotNull String formatUtc(@NotNull Instant now)
        { return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC).format(now); }

    //* 阿里云 pop 编码形态 (RFC3986): + → %20, * → %2A, %7E → ~ (URLEncoder 的 application/x-www-form-urlencoded 偏差修正).
    static @NotNull String percentEncode(@NotNull String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).
            replace("+", "%20").
            replace("*", "%2A").
            replace("%7E", "~");
    }

    //* HmacSHA1 → Base64: 签名密钥为 secret + "&" (RPC 约定), JDK 标准件.
    static @NotNull String hmacSha1Base64(@NotNull String key, @NotNull String data)
    {
        try
        {
            final var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        }
        //* HmacSHA1 为 JDK 保证存在的标准算法, 仅这两类受检异常可能在 init 时抛出, 多 catch 收窄.
        catch(NoSuchAlgorithmException | InvalidKeyException e) { throw new IllegalStateException("HmacSHA1 不可用 (JDK 标准算法缺失?)", e); }
    }
}
