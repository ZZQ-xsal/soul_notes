package kurvcygnus.soulnotes.domain.diary.resource;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link DiaryResource} 结构单元测试</b>
 *
 * @author Claude Code
 * @since 2.0
 */
class DiaryResourceTest
{
    @Test void class_ShouldBeFinal()
    {
        assertTrue(Modifier.isFinal(DiaryResource.class.getModifiers()));
    }

    @Test void class_ShouldHavePathAnnotation()
    {
        final var path = DiaryResource.class.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals(ApiEndpointConstants.DIARY_BASE, path.value());
    }

    @Test void class_ShouldHaveRolesAllowed()
    {
        assertTrue(DiaryResource.class.isAnnotationPresent(RolesAllowed.class));
    }

    @Test void methods_CreateListGetByIdDeleteAndWeatherExist() throws Exception
    {
        assertNotNull(DiaryResource.class.getMethod("create", kurvcygnus.soulnotes.domain.diary.dto.DiaryCreateRequest.class));
        assertNotNull(DiaryResource.class.getMethod("list", kurvcygnus.soulnotes.domain.diary.dto.DiaryListQuery.class));
        assertNotNull(DiaryResource.class.getMethod("getById", long.class));
        assertNotNull(DiaryResource.class.getMethod("delete", long.class));
        assertNotNull(DiaryResource.class.getMethod("getWeather", String.class, String.class));
    }

    @Test void parseDateRange_ValidDates_ShouldReturnRange() throws Exception
    {
        final var range = DiaryResource.parseDateRange("2026-01-01", "2026-01-31");
        assertEquals("2026-01-01", range.start().toString());
        assertEquals("2026-01-31", range.end().toString());
    }

    @Test void parseDateRange_InvalidFormat_ShouldThrowBusinessExceptionWithUnifiedPayload() throws Exception
    {
        final var ex = assertThrows(Exception.class, () -> DiaryResource.parseDateRange("01/02/2026", "2026-01-31"));
        assertInstanceOf(kurvcygnus.soulnotes.exception.StructuredException.class, ex);
        final var business = (kurvcygnus.soulnotes.exception.IBusinessException<?>) ex;
        assertEquals(kurvcygnus.soulnotes.exception.ErrorCode.BAD_REQUEST, business.getErrorCode());
        assertEquals("DIARY_WEATHER_DATE_INVALID", business.tag());
        assertTrue(business.getMessage().contains("yyyy-MM-dd"));
    }
}
