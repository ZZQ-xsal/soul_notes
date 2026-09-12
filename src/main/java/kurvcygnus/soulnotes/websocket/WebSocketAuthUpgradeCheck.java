package kurvcygnus.soulnotes.websocket;

import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.quarkus.websockets.next.UserData;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Singleton;
import kurvcygnus.soulnotes.domain.auth.service.TokenService;
import kurvcygnus.soulnotes.utils.constants.JwtConstants;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>WebSocket 升级认证网关</b>
 * <ul>
 *     <li>从 {@code Authorization: Bearer} 请求头或 {@code token} 查询参数提取 JWT</li>
 *     <li>通过 {@link JWTParser#verify(String, String)} 校验签名 (HS256 对称密钥), 提取 userId 存入 {@link UserData}</li>
 *     <li>校验 Redis 黑名单, 防止已注销的 Token 被重用</li>
 * </ul>
 * @since 1.0
 */
@Singleton
public final class WebSocketAuthUpgradeCheck implements HttpUpgradeCheck
{
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketAuthUpgradeCheck.class);

    //* ChatWebSocket / AlertWebSocket 从 UserData 读取此 Key 获取当前 userId.
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

    @Override
    public boolean appliesTo(@NotNull String endpointId)
    {
        //* 仅对 ChatWebSocket 和 AlertWebSocket 生效.
        return endpointId.contains("ChatWebSocket") || endpointId.contains("AlertWebSocket");
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
