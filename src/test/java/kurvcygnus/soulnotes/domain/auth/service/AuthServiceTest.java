package kurvcygnus.soulnotes.domain.auth.service;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link AuthService} 的密码哈希逻辑单元测试</b>
 * <p>通过反射测试 {@code hashPassword} 与 {@code verifyPassword} 方法的正确性.</p>
 *
 * @author Claude Code
 * @since 1.0
 */
class AuthServiceTest
{
    private static final String TEST_PASSWORD = "SecurePass123!";

    /**
     * 通过反射调用 AuthService#hashPassword 来验证 PBKDF2 哈希行为.
     */
    private static String invokeHashPassword(String password) throws Exception
    {
        final var method = AuthService.class.getDeclaredMethod("hashPassword", String.class);
        method.setAccessible(true);
        //* hashPassword 为实例方法 (GraalVM native-image 禁止 static 字段持有 SecureRandom), tokenService 在该算法路径上不可达, 传 null 安全.
        return (String) method.invoke(new AuthService(null), password);
    }

    /**
     * 通过反射调用 AuthService#verifyPassword 来验证密码校验逻辑.
     */
    private static boolean invokeVerifyPassword(String rawPassword, String storedHash) throws Exception
    {
        final var method = AuthService.class.getDeclaredMethod("verifyPassword", String.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(new AuthService(null), rawPassword, storedHash);
    }

    //region 哈希格式
    @Test
    void hashPassword_ShouldProducePbkdf2Format() throws Exception
    {
        final var hash = invokeHashPassword(TEST_PASSWORD);
        assertTrue(hash.startsWith("pbkdf2$210000$"));
        assertEquals(4, hash.split("\\$").length); // pbkdf2 / iterations / salt / hash
    }

    @Test
    void hashPassword_SameInput_ShouldProduceDifferentHashes() throws Exception
    {
        //* 随机盐: 相同密码两次哈希结果必须不同.
        assertNotEquals(invokeHashPassword(TEST_PASSWORD), invokeHashPassword(TEST_PASSWORD));
    }

    @Test
    void hashPassword_DifferentInput_ShouldProduceDifferentHashes() throws Exception
    {
        final var hash1 = invokeHashPassword("password1");
        final var hash2 = invokeHashPassword("password2");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void hashPassword_EmptyString_ShouldProduceValidHash() throws Exception
    {
        //* 验证空密码也能正常哈希, 不抛出异常.
        final var hash = invokeHashPassword("");
        assertTrue(hash.startsWith("pbkdf2$"));
    }

    @Test
    void hashPassword_WithUnicodeCharacters() throws Exception
    {
        //* 验证包含 Unicode 字符的密码也能正常哈希.
        final var password = "密码🔑123";
        final var hash = invokeHashPassword(password);
        assertTrue(hash.startsWith("pbkdf2$"));
    }
    //endregion

    //region 校验逻辑
    @Test
    void verifyPassword_CorrectPassword_ShouldReturnTrue() throws Exception
    {
        final var hash = invokeHashPassword(TEST_PASSWORD);
        assertTrue(invokeVerifyPassword(TEST_PASSWORD, hash));
    }

    @Test
    void verifyPassword_WrongPassword_ShouldReturnFalse() throws Exception
    {
        final var hash = invokeHashPassword(TEST_PASSWORD);
        assertFalse(invokeVerifyPassword("wrong-password", hash));
    }

    @Test
    void verifyPassword_LegacySha256Hash_ShouldStillVerify() throws Exception
    {
        //* 兼容旧哈希: 手工构造 SHA-256 无前缀哈希, 必须可验证通过.
        final var digest = MessageDigest.getInstance("SHA-256");
        final var legacy = HexFormat.of().formatHex(digest.digest(TEST_PASSWORD.getBytes()));
        assertTrue(invokeVerifyPassword(TEST_PASSWORD, legacy));
    }

    @Test
    void verifyPassword_CorruptedHash_ShouldReturnFalse() throws Exception
    {
        //* 损坏存储值按校验失败处理, 不抛异常.
        assertFalse(invokeVerifyPassword(TEST_PASSWORD, "pbkdf2$210000$bad$bad"));
    }
    //endregion
}
