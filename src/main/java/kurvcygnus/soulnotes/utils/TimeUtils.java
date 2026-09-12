package kurvcygnus.soulnotes.utils;

import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;

public final class TimeUtils
{
    public static final @NotNull ZoneId ZONE_ASIA_SHANGHAI = ZoneId.of("Asia/Shanghai");

    private TimeUtils() { throw new IllegalAccessError("Class \"TimeUtils\" is not meant to be instantized!"); }
}
