package kurvcygnus.soulnotes.domain.crisis;

import jakarta.ws.rs.Path;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link CrisisResource} 结构单元测试</b>
 *
 * @author Claude Code
 * @since 1.1.0
 */
class CrisisResourceTest
{
    @Test void class_ShouldBeFinal()
    {
        assertTrue(Modifier.isFinal(CrisisResource.class.getModifiers()));
    }

    @Test void class_ShouldHavePathAnnotation()
    {
        final var path = CrisisResource.class.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals(ApiEndpointConstants.CRISIS_BASE, path.value());
    }

    @Test void method_GetHotlineExists() throws Exception
    {
        assertNotNull(CrisisResource.class.getMethod("getHotline"));
    }
}
