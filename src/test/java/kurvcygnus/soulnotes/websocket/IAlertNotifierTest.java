package kurvcygnus.soulnotes.websocket;

import kurvcygnus.soulnotes.utils.constants.ConfigDefaults;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link IAlertNotifier#primaryHotlineOf} 单元测试</b>
 * <p>热线主号码解析是 WS 与 Webhook 双渠道的单一来源, 兜底分支直指离线安全网常量
 *, 逐分支钉死防漂移.</p>
 * @since 1.1.0
 */
class IAlertNotifierTest
{
    @Test void primaryHotlineOf_TakesSecondSegment()
    {
        assertEquals("400-161-9995", IAlertNotifier.primaryHotlineOf("全国心理援助热线|400-161-9995|12355"));
    }

    @Test void primaryHotlineOf_BlankRaw_FallsBackToDefault()
    {
        assertEquals(ConfigDefaults.HOTLINE_PRIMARY, IAlertNotifier.primaryHotlineOf(""));
        assertEquals(ConfigDefaults.HOTLINE_PRIMARY, IAlertNotifier.primaryHotlineOf("   "));
    }

    @Test void primaryHotlineOf_MissingOrBlankPrimarySegment_FallsBackToDefault()
    {
        assertEquals(ConfigDefaults.HOTLINE_PRIMARY, IAlertNotifier.primaryHotlineOf("只有名称"));
        assertEquals(ConfigDefaults.HOTLINE_PRIMARY, IAlertNotifier.primaryHotlineOf("名称||12355"), "主号码段空白必须回退默认值");
    }
}
