package kurvcygnus.soulnotes.domain.chat.resource;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link ChatResource} 结构单元测试</b>
 *
 * @author Claude Code
 * @since 1.1.0
 */
class ChatResourceTest
{
    @Test void class_ShouldBeFinal()
    {
        assertTrue(Modifier.isFinal(ChatResource.class.getModifiers()));
    }

    @Test void class_ShouldHavePathAnnotation()
    {
        final var path = ChatResource.class.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals(ApiEndpointConstants.CHAT_BASE, path.value());
    }

    @Test void class_ShouldHaveRolesAllowed()
    {
        assertTrue(ChatResource.class.isAnnotationPresent(RolesAllowed.class));
    }

    @Test void methods_SendStreamAndListSessionsExist() throws Exception
    {
        assertNotNull(ChatResource.class.getMethod("send", kurvcygnus.soulnotes.domain.chat.dto.ChatSendRequest.class));
        assertNotNull(ChatResource.class.getMethod("listSessions"));
    }
}
