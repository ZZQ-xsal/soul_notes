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
 * <b>JWT 认证机制</b>
 * <ul>
 *     <li>从 {@code Authorization: Bearer <token>} 请求头中提取 JWT</li>
 *     <li>通过 {@link JWTParser#verify(String, String)} 校验签名 (HS256 对称密钥), 使用荷载中的角色声明构建 {@link SecurityIdentity}</li>
 *     <li>校验失败时返回 {@code null} 以继续调用链中的下一个认证机制</li>
 * </ul>
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
    @Override public int getPriority() { return 2000; }

    @Override public @NotNull Uni<ChallengeData> getChallenge(@NotNull RoutingContext context)
        { return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", JwtConstants.CHALLENGE_REALM)); }
}