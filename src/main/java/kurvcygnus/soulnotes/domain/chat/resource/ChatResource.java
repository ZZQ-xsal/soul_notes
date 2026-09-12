package kurvcygnus.soulnotes.domain.chat.resource;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import kurvcygnus.soulnotes.domain.chat.dto.ChatMessageVo;
import kurvcygnus.soulnotes.domain.chat.dto.ChatSendRequest;
import kurvcygnus.soulnotes.domain.chat.dto.ChatSessionVo;
import kurvcygnus.soulnotes.domain.chat.service.ChatService;
import kurvcygnus.soulnotes.dto.ApiResponse;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * <b>AI 树洞对话 REST 资源</b>
 * <ul>
 *     <li>{@code POST /api/v1/chat/send} — 发送消息, 获取完整回复 (非流式)</li>
 *     <li>{@code POST /api/v1/chat/stream} — SSE 流式回复 (JSON Body)</li>
 *     <li>{@code GET  /api/v1/chat/sessions} — 历史会话列表</li>
 * </ul>
 * @since 1.0
 */
@Path(ApiEndpointConstants.CHAT_BASE)
@RolesAllowed(UserRole.ROLE_STUDENT)
public final class ChatResource
{
    @Inject ChatService chatService;
    @Inject SecurityIdentity securityIdentity;

    //* 从 SecurityIdentity 提取当前用户 ID (即 JWT subject).
    private @NotNull UUID currentUserId() { return UUID.fromString(securityIdentity.getPrincipal().getName()); }

    /**
     * <span style="color: 95cc6d">发送消息, 获取完整回复.</span>
     */
    @POST @Path("/send")
    public @NotNull Uni<ApiResponse<ChatMessageVo>> send(@NotNull ChatSendRequest req) { return chatService.sendMessage(req, currentUserId()).map(ApiResponse::success); }

    /**
     * <span style="color: 95cc6d">SSE 流式回复, 支持前端逐字渲染.</span>
     */
    @POST @Path("/stream") @Produces(MediaType.SERVER_SENT_EVENTS)
    public @NotNull Multi<String> stream(@NotNull ChatSendRequest req)
    {
        return chatService.streamMessage(
            req.sessionId() != null ? req.sessionId().toString() : null,
            req.content(),
            currentUserId()
        );
    }

    /**
     * <span style="color: 95cc6d">获取历史会话列表.</span>
     */
    @GET @Path("/sessions")
    public @NotNull Uni<ApiResponse<List<ChatSessionVo>>> listSessions() { return chatService.listSessions(currentUserId()).map(ApiResponse::success); }
}