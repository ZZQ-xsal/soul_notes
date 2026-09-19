package kurvcygnus.soulnotes.utils.constants;

import org.jetbrains.annotations.NotNull;

/**
 * REST API 端点路径常量.
 * <p>所有基础路径均声明为编译期常量, 可直接用于 {@link jakarta.ws.rs.Path} 注解值;
 * 各 Resource 与限流过滤器等旁路组件统一引用此处, 防止路径字面量漂移.</p>
 * @since 1.0
 */
public final class ApiEndpointConstants
{
    private ApiEndpointConstants() { throw new IllegalAccessError("Class \"ApiEndpointConstants\" is not meant to be instantized!"); }

    /** 认证域基础路径 (登录等). */
    public static final @NotNull String AUTH_BASE   = "/api/v1/auth";
    /** 日记域基础路径. */
    public static final @NotNull String DIARY_BASE  = "/api/v1/diaries";
    /** 对话域基础路径 ({@code /send} 与 {@code /stream}). */
    public static final @NotNull String CHAT_BASE   = "/api/v1/chat";
    /** 语音域基础路径 ({@code /upload}). */
    public static final @NotNull String VOICE_BASE  = "/api/v1/voice";
    /** 危机干预域基础路径 (热线信息查询). */
    public static final @NotNull String CRISIS_BASE = "/api/v1/crisis";
}
