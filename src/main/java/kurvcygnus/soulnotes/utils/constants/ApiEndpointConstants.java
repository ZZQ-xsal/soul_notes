package kurvcygnus.soulnotes.utils.constants;

import org.jetbrains.annotations.NotNull;

/**
 * <b>REST API 端点路径常量</b>
 * <p>所有基础路径均声明为编译期常量，可在 <u>{@link jakarta.ws.rs.Path}</u> 注解中使用。</p>
 * @since 1.0
 */
public final class ApiEndpointConstants
{
    private ApiEndpointConstants() { throw new IllegalAccessError("Class \"ApiEndpointConstants\" is not meant to be instantized!"); }

    public static final @NotNull String AUTH_BASE   = "/api/v1/auth";
    public static final @NotNull String DIARY_BASE  = "/api/v1/diaries";
    public static final @NotNull String CHAT_BASE   = "/api/v1/chat";
    public static final @NotNull String VOICE_BASE  = "/api/v1/voice";
    public static final @NotNull String CRISIS_BASE = "/api/v1/crisis";
}
