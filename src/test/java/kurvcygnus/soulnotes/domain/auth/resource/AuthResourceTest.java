package kurvcygnus.soulnotes.domain.auth.resource;

import jakarta.ws.rs.Path;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link AuthResource} 结构单元测试</b>
 *
 * @author Claude Code
 * @since 1.1.0
 */
class AuthResourceTest
{
    @Test void class_ShouldBeFinal()
    {
        assertTrue(Modifier.isFinal(AuthResource.class.getModifiers()));
    }

    @Test void class_ShouldHavePathAnnotation()
    {
        final var path = AuthResource.class.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals(ApiEndpointConstants.AUTH_BASE, path.value());
    }

    @Test void methods_RegisterLoginAndLogoutExist() throws Exception
    {
        assertNotNull(AuthResource.class.getMethod("register", kurvcygnus.soulnotes.domain.auth.dto.RegisterRequest.class));
        assertNotNull(AuthResource.class.getMethod("login", kurvcygnus.soulnotes.domain.auth.dto.LoginRequest.class));
        assertNotNull(AuthResource.class.getMethod("logout", String.class));
    }
}
