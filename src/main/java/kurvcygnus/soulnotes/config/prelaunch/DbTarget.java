package kurvcygnus.soulnotes.config.prelaunch;

import kurvcygnus.soulnotes.utils.PrintUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 数据库探测目标: 从 {@code postgresql://[user:pass@]host:port/db} 解析出的连接四要素.
 * <p>向导支持整串粘贴 (userinfo 可选), 无 userinfo 时凭据取分离收集的 {@code SOULNOTES_DB_USER/PASSWORD}.
 * scheme 级校验由 FieldValidator 在配置层 BLOCK, 本类只负责结构解析.</p>
 *
 * @param host 主机名或 IP
 * @param port 端口, 连接串未显式给出时为 5432
 * @param database 目标库名
 * @param user 用户名; URL userinfo 优先, 缺失时回落到分离输入值, 可为 null
 * @param password 密码; URL userinfo 优先, 缺失时回落到分离输入值, 可为 null
 * @since 1.1.0
 */
public record DbTarget(@NotNull String host, int port, @NotNull String database, @Nullable String user, @Nullable String password)
{
    private static final @NotNull String SCHEME = "postgresql://";
    private static final int DEFAULT_PORT = 5432;

    /**
     * 解析 PostgreSQL 连接串为探测目标.
     *
     * @param url {@code postgresql://[user[:pass]@]host[:port]/db} 形式的连接串
     * @param user 无 userinfo 时使用的用户名 (可为 null)
     * @param password 无 userinfo 时使用的密码 (可为 null)
     * @return 解析出的连接四要素; 凭据取 URL userinfo, userinfo 缺失时回落到同义参数
     * @throws IllegalStateException URL 结构非法 (scheme 错误 / host 或库名缺失 / 端口非数字), 调用方转 BLOCK
     * @since 1.1.0
     */
    public static @NotNull DbTarget parse(@NotNull String url, @Nullable String user, @Nullable String password)
    {
        Objects.requireNonNull(url, "Param \"url\" must not be null!");
        if(!url.startsWith(SCHEME))
            throw new IllegalStateException(PrintUtils.quickFormat("数据库地址必须以 {} 开头: {}", SCHEME, url));

        var rest = url.substring(SCHEME.length());

        //* userinfo 先于 host 切分: 凭据中可能出现 ':' '/' '@' 等结构字符, 必须最先剥离;
        //* 取最后一个 '@' 切分, 密码本身含 '@' 时仍能正确还原 (用户名按规定不含 '@').
        final var at = rest.lastIndexOf('@');
        String urlUser = null;
        String urlPassword = null;
        if(at >= 0)
        {
            final var userinfo = rest.substring(0, at);
            rest = rest.substring(at + 1);
            final var colon = userinfo.indexOf(':');
            if(colon < 0)
                urlUser = userinfo;
            else
            {
                urlUser = userinfo.substring(0, colon);
                urlPassword = userinfo.substring(colon + 1);
            }
        }

        final var slash = rest.lastIndexOf('/');
        final var hostPort = slash < 0 ? rest : rest.substring(0, slash);
        final var database = slash < 0 ? "" : rest.substring(slash + 1);
        if(hostPort.isEmpty())
            throw new IllegalStateException(PrintUtils.quickFormat("数据库地址缺少 host: {}", url));
        if(database.isEmpty())
            throw new IllegalStateException(PrintUtils.quickFormat("数据库地址缺少库名: {}", url));

        final var colon = hostPort.indexOf(':');
        final var host = colon < 0 ? hostPort : hostPort.substring(0, colon);
        if(host.isEmpty())
            throw new IllegalStateException(PrintUtils.quickFormat("数据库地址缺少 host: {}", url));

        var port = DEFAULT_PORT;
        if(colon >= 0)
            try { port = Integer.parseInt(hostPort.substring(colon + 1)); }
            catch(NumberFormatException e) { throw new IllegalStateException(PrintUtils.quickFormat("数据库地址端口非法: {}", url), e); }

        return new DbTarget(host, port, database,
            urlUser != null ? urlUser : user,
            urlUser != null ? urlPassword : password);
    }
}
