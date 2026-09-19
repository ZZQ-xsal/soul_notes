package kurvcygnus.soulnotes.utils;

import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;

/**
 * 时间处理相关的静态工具集合.
 * @since 1.0
 */
public final class TimeUtils
{
    /**
     * 项目统一的业务时区 (Asia/Shanghai).
     * <p>跨日时间边界 (如近期情绪记录查询窗口) 一律按此时区取日期与切日,
     * 不依赖服务器本地时区, 避免部署环境时区漂移.</p>
     */
    public static final @NotNull ZoneId ZONE_ASIA_SHANGHAI = ZoneId.of("Asia/Shanghai");

    private TimeUtils() { throw new IllegalAccessError("Class \"TimeUtils\" is not meant to be instantized!"); }
}
