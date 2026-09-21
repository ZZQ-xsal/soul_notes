package kurvcygnus.soulnotes.websocket;

import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.quarkus.websockets.next.UserData;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Singleton;
import kurvcygnus.soulnotes.domain.auth.service.TokenService;
import kurvcygnus.soulnotes.utils.constants.JwtConstants;
import kurvcygnus.soulnotes.utils.enums.UserRole;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket 升级认证网关: 在 HTTP → WS 握手期完成 JWT 校验并将身份写入 {@link UserData}.
 * <ul>
 *     <li>从 {@code Authorization: Bearer} 请求头或 {@code token} 查询参数提取 JWT</li>
 *     <li>通过 {@link JWTParser#verify(String, String)} 校验签名 (HS256 对称密钥), 提取 userId 存入 {@link UserData}</li>
 *     <li>校验 Redis 黑名单, 防止已注销的 Token 被重用</li>
 * </ul>
 *
 * @implNote 仅对 ChatWebSocket / AlertWebSocket / ClinicalFeedWebSocket 端点生效 (见 {@link #appliesTo});
 *           任一校验不通过一律拒绝升级并返回 401; 工作台端点额外强制 COUNSELOR/ADMIN 角色门槛 (403).
 * @since 1.0
 */
@Singleton
public final class WebSocketAuthUpgradeCheck implements HttpUpgradeCheck
{
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketAuthUpgradeCheck.class);

    //* ChatWebSocket / AlertWebSocket / ClinicalFeedWebSocket 从 UserData 读取此 Key 获取当前 userId.
    public static final @NotNull UserData.TypedKey<String> USER_ID_KEY = UserData.TypedKey.forString("userId");

    //region 注入
    private final @NotNull JWTParser jwtParser;
    private final @NotNull TokenService tokenService;
    private final @NotNull String jwtSecret;

    //! 必须使用 verify(token, secret) 显式验签, 与 TokenService 的 HS256 对称签名对齐.
    public WebSocketAuthUpgradeCheck(
        @NotNull JWTParser jwtParser,
        @NotNull TokenService tokenService,
        @ConfigProperty(name = "jwt.secret") @NotNull String jwtSecret
    )
    {
        this.jwtParser = jwtParser;
        this.tokenService = tokenService;
        this.jwtSecret = jwtSecret;
    }
    //endregion

    //region HttpUpgradeCheck

    /**
     * 握手期认证: 提取并验签 JWT, 校验黑名单, 通过后将 userId 写入 {@link UserData}
     * 供 {@code ChatWebSocket} / {@code AlertWebSocket} 在连接期读取.
     *
     * @param context 升级上下文 (HTTP 请求 + UserData)
     * @return 校验通过为允许升级; 缺少 Token、subject 为空、Token 已注销或验签失败时拒绝升级 (401);
     *         工作台 ({@code /ws/clinical} 前缀) 端点角色非 COUNSELOR/ADMIN 时拒绝升级 (403)
     */
    @Override
    public @NotNull Uni<CheckResult> perform(@NotNull HttpUpgradeContext context)
    {
        final var request = context.httpRequest();

        //* 优先从 Authorization header 提取 Token.
        var token = extractBearerToken(request.getHeader(JwtConstants.AUTH_HEADER));

        //* 若 header 中无 Token, 尝试从 query param 提取 (兼容部分 WebSocket 客户端).
        if(token == null)
            token = request.getParam("token");

        if(token == null || token.isBlank())
        {
            LOG.warn("WebSocket 升级拒绝: 缺少 JWT");
            return CheckResult.rejectUpgrade(401);
        }

        try
        {
            final var jwt = jwtParser.verify(token, jwtSecret);
            final var jti = jwt.getTokenID();
            final var sub = jwt.getSubject();

            if(sub == null || sub.isBlank())
            {
                LOG.warn("WebSocket 升级拒绝: JWT subject 为空");
                return CheckResult.rejectUpgrade(401);
            }

            //* 检查 Redis 黑名单.
            return tokenService.isBlacklisted(jti).
                flatMap(isBlacklisted ->
                {
                    if(isBlacklisted)
                    {
                        LOG.warn("WebSocket 升级拒绝: Token 已被注销");
                        return CheckResult.rejectUpgrade(401);
                    }

                    context.userData().put(USER_ID_KEY, sub);

                    //* 工作台端点角色断言: 咨询员通道与普通用户端点共用网关, 但角色门槛只能在升级期落实.
                    //  用 path() 而非 uri(): path 不含查询串, /ws/clinical/feed?token=xxx 形态天然命中前缀且不受参数污染.
                    if(request.path().startsWith("/ws/clinical"))
                    {
                        final var groups = jwt.getGroups();
                        //* 角色字面量引用 UserRole 常量 (ClinicalResource @RolesAllowed 同源, 单一来源防漂移).
                        if(groups == null || !(groups.contains(UserRole.ROLE_COUNSELOR) || groups.contains(UserRole.ROLE_ADMIN)))
                        {
                            LOG.warn("WebSocket 升级拒绝: 工作台端点非咨询员角色");
                            return CheckResult.rejectUpgrade(403);
                        }
                    }

                    LOG.debug("WebSocket 升级已授权: userId={}", sub);
                    return CheckResult.permitUpgrade();
                });
        }
        catch(ParseException e)
        {
            LOG.warn("WebSocket JWT 解析失败: {}", e.getMessage());
            return CheckResult.rejectUpgrade(401);
        }
    }

    /**
     * 限定本网关仅拦截对话、预警与工作台三个 WS 端点, 其余端点不做升级认证.
     *
     * @param endpointId 框架分配的端点标识
     * @return 端点 ID 含 {@code ChatWebSocket} / {@code AlertWebSocket} / {@code ClinicalFeedWebSocket} 时为 true
     */
    @Override
    public boolean appliesTo(@NotNull String endpointId)
    {
        //* 仅对 ChatWebSocket / AlertWebSocket / ClinicalFeedWebSocket 生效.
        return endpointId.contains("ChatWebSocket") || endpointId.contains("AlertWebSocket") || endpointId.contains("ClinicalFeedWebSocket");
    }

    //endregion

    //region 辅助方法

    //* 从 Authorization 请求头中提取 Bearer Token.
    private static @Nullable String extractBearerToken(@Nullable String authHeader)
    {
        if(authHeader == null || !authHeader.startsWith(JwtConstants.TOKEN_PREFIX))
            return null;
        return authHeader.substring(JwtConstants.TOKEN_PREFIX_LENGTH);
    }

    //endregion
}
