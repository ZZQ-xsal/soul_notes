package kurvcygnus.soulnotes.domain.auth.security;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.domain.auth.service.TokenService;
import kurvcygnus.soulnotes.utils.constants.JwtConstants;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;

/**
 * JWT HTTP 认证机制, 是全站 Bearer Token 认证的入口.
 * <ul>
 *     <li>从 {@code Authorization: Bearer <token>} 请求头中提取 JWT</li>
 *     <li>通过 {@link JWTParser#verify(String, String)} 校验签名 (HS256 对称密钥), 使用荷载中的角色声明构建 {@link SecurityIdentity}</li>
 *     <li>校验失败时返回 {@code null} 以继续调用链中的下一个认证机制</li>
 * </ul>
 *
 * @implNote 构建身份前额外查询 Redis 黑名单, 使已注销 Token 即使签名合法也无法重用.
 * @since 1.0
 */
@ApplicationScoped
public final class JwtAuthenticationMechanism implements HttpAuthenticationMechanism
{
    private final @NotNull JWTParser jwtParser;
    private final @NotNull TokenService tokenService;
    private final @NotNull String jwtSecret;

    //! 不使用 JwtParser.parse(): 其依赖 mp.jwt.verify.* 公钥配置, 本项目采用 HS256 对称密钥,
    //! 必须显式传入与签发时相同的 secret 才能完成验签.
    public JwtAuthenticationMechanism(
        @NotNull JWTParser jwtParser,
        @NotNull TokenService tokenService,
        @ConfigProperty(name = "jwt.secret") @NotNull String jwtSecret
    )
    {
        this.jwtParser = jwtParser;
        this.tokenService = tokenService;
        this.jwtSecret = jwtSecret;
    }

    /**
     * 从请求头提取并校验 JWT, 校验通过 (且未命中黑名单) 时构建带角色的认证身份.
     *
     * @param context                 当前路由上下文
     * @param identityProviderManager 身份提供管理器 (本机制直接构建身份, 不经由此回调)
     * @return 认证成功为 {@link SecurityIdentity}; 缺少 Bearer 头、签名/解析失败或 Token 已注销时为 {@code null},
     *         表示"本机制未认证", 由调用链继续尝试后续机制或最终 401
     */
    @Override public @NotNull Uni<SecurityIdentity> authenticate(
        @NotNull RoutingContext context,
        @NotNull IdentityProviderManager identityProviderManager
    )
    {
        final var authHeader = context.request().getHeader(JwtConstants.AUTH_HEADER);
        if(authHeader == null || !authHeader.startsWith(JwtConstants.TOKEN_PREFIX))
            return Uni.createFrom().nullItem();

        final var token = authHeader.substring(JwtConstants.TOKEN_PREFIX_LENGTH);
        try
        {
            final var jwt = jwtParser.verify(token, jwtSecret);
            final var jti = jwt.getTokenID();
            //* Token 解析成功后, 检查 Redis 黑名单, 防止已注销的 Token 被重用.
            return tokenService.isBlacklisted(jti).
                flatMap(
                    isBlacklisted ->
                    {
                        if(isBlacklisted)
                            return Uni.createFrom().nullItem();
                        final var identity = QuarkusSecurityIdentity.builder().
                            setPrincipal(jwt).
                            addRoles(jwt.getGroups()).
                            build();
                        return Uni.createFrom().item(identity);
                    }
                );
        }
        catch(ParseException e)
        {
            //! Token 解析或签名校验失败, 返回 null 表示未认证.
            return Uni.createFrom().nullItem();
        }
    }

    //! quarkus-smallrye-jwt 自带机制 (priority 1000) 用 JWTParser.parse() 依赖 mp.jwt.verify.publickey 验签,
    //! 本项目为 HS256 对称密钥 (publickey 配置为 NONE), 自带机制必然失败并直接 401, 必须先于它执行.
    //! HttpAuthenticationMechanism 按 getPriority() 降序排序 (见 HttpSecurityConfiguration), 返回更高值即可优先.
    /**
     * 机制优先级 (2000), 高于 quarkus-smallrye-jwt 自带机制 (1000), 保证本机制先执行.
     *
     * @return 固定 2000
     * @implNote 自带机制依赖 {@code mp.jwt.verify.publickey} 验签, 本项目为 HS256 对称密钥
     *           (publickey 配置为 NONE), 自带机制必然失败并直接 401, 必须由本机制抢先完成认证.
     */
    @Override public int getPriority() { return 2000; }

    /**
     * 认证失败时的质询响应: 401 状态码 + {@code WWW-Authenticate} 头.
     *
     * @param context 当前路由上下文
     * @return 401 质询, realm 取自 {@link JwtConstants#CHALLENGE_REALM}
     */
    @Override public @NotNull Uni<ChallengeData> getChallenge(@NotNull RoutingContext context)
        { return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", JwtConstants.CHALLENGE_REALM)); }
}