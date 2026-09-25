package kurvcygnus.soulnotes.domain.chat.resource;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import kurvcygnus.soulnotes.domain.chat.dto.ChatHistoryMessage;
import kurvcygnus.soulnotes.domain.chat.dto.ChatMessageVo;
import kurvcygnus.soulnotes.domain.chat.dto.ChatSendRequest;
import kurvcygnus.soulnotes.domain.chat.dto.ChatSessionVo;
import kurvcygnus.soulnotes.domain.chat.service.ChatService;
import kurvcygnus.soulnotes.dto.ApiResponse;
import kurvcygnus.soulnotes.exception.ErrorCode;
import kurvcygnus.soulnotes.exception.IBusinessException;
import kurvcygnus.soulnotes.utils.constants.ApiEndpointConstants;
import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * AI 树洞对话 REST 资源, 面向 STUDENT 角色提供非流式/流式对话与会话查询端点.
 * <ul>
 *     <li>{@code POST /api/v1/chat/send} — 发送消息, 获取完整回复 (非流式)</li>
 *     <li>{@code POST /api/v1/chat/stream} — SSE 流式回复 (JSON Body)</li>
 *     <li>{@code GET  /api/v1/chat/sessions} — 历史会话列表</li>
 *     <li>{@code GET  /api/v1/chat/sessions/{sessionId}/messages} — 会话完整消息历史</li>
 * </ul>
 *
 * @implNote userId 从 JWT subject 解析 (认证由全局机制保证), 用户只能访问自己的会话.
 * @since 1.0
 */
@SuppressWarnings("JavadocDeclaration") @Path(ApiEndpointConstants.CHAT_BASE)
@RolesAllowed(UserRole.ROLE_STUDENT)
public final class ChatResource
{
    @Inject ChatService chatService;
    @Inject SecurityIdentity securityIdentity;

    //* 从 SecurityIdentity 提取当前用户 ID (即 JWT subject).
    private @NotNull UUID currentUserId() { return UUID.fromString(securityIdentity.getPrincipal().getName()); }

    /**
     * 发送消息并返回 AI 完整回复 (非流式).
     *
     * @param req 发送请求 (含可选会话 ID 与消息内容)
     * @return 助手角色的回复消息; LLM 不可用时为固定兜底文案 (同样落库)
     * @throws IBusinessException 会话 ID 不存在时 (SESSION_NOT_FOUND)
     */
    @POST @Path("/send")
    public @NotNull Uni<ApiResponse<ChatMessageVo>> send(@NotNull ChatSendRequest req) { return chatService.sendMessage(req, currentUserId()).map(ApiResponse::success); }

    /**
     * SSE 流式回复, 逐 token 推送以支持前端逐字渲染.
     *
     * @param req 发送请求 (含可选会话 ID 与消息内容)
     * @return SSE 事件流 (text/event-stream); LLM 中途失败时补发固定兜底文案后正常收流
     * @throws IBusinessException 会话 ID 合法但对应会话不存在时 (SESSION_NOT_FOUND), 以流失败形式发出;
     *                            非法 UUID 格式的会话 ID 不报错, 回退为新会话
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
     * 获取当前用户的会话概览列表 (按最近活跃排序).
     *
     * @return 会话概览列表 (可能为空)
     */
    @GET @Path("/sessions")
    public @NotNull Uni<ApiResponse<List<ChatSessionVo>>> listSessions() { return chatService.listSessions(currentUserId()).map(ApiResponse::success); }

    /**
     * 拉取指定会话的完整消息历史 (按对话顺序, 含 LLM 回复).
     *
     * @param sessionId 会话 ID
     * @return 消息列表 (role/content, 按对话顺序; 空会话为空列表)
     * @throws IBusinessException 会话 ID 非法、不存在或不属于当前用户时 (SESSION_NOT_FOUND, 同码同文案防枚举)
     * @since 1.2.1
     */
    @GET @Path("/sessions/{sessionId}/messages")
    public @NotNull Uni<ApiResponse<List<ChatHistoryMessage>>> listMessages(@PathParam("sessionId") @NotNull String sessionId)
    {
        return chatService.listMessages(parseSessionIdOrNotFound(sessionId), currentUserId()).map(ApiResponse::success);
    }

    /**
     * 删除指定会话 (硬删除, 含全部消息历史).
     *
     * @param sessionId 会话 ID
     * @return 空载荷的成功响应
     * @throws IBusinessException 会话 ID 非法、不存在或不属于当前用户时 (SESSION_NOT_FOUND, 同码同文案防枚举)
     * @since 1.2.1
     */
    @DELETE @Path("/sessions/{sessionId}")
    public @NotNull Uni<ApiResponse<Void>> deleteSession(@PathParam("sessionId") @NotNull String sessionId)
    {
        return chatService.deleteSession(
            parseSessionIdOrNotFound(sessionId),
            currentUserId()
        ).map(_ -> ApiResponse.success());
    }

    //* 路径参数 UUID 解析: 非法格式与 "不存在" 同码同文案回应 — 不向客户端区分两种失败形态, 防会话枚举.
    private static @NotNull UUID parseSessionIdOrNotFound(@NotNull String sessionId)
    {
        try { return UUID.fromString(sessionId); }
        catch(IllegalArgumentException e)
        {
            throw IBusinessException.of(
                ErrorCode.SESSION_NOT_FOUND,
                "会话不存在",
                NoSuchElementException::new,
                "CHAT_SESSION_LOOKUP_NOT_FOUND"
            ).asException();
        }
    }
}