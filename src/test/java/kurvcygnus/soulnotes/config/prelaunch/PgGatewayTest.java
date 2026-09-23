package kurvcygnus.soulnotes.config.prelaunch;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.Test;

import kurvcygnus.soulnotes.support.InfraProbes;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>PgGateway 真机冒烟</b>: 依赖本机 postgres 开发容器 (与 application-dev.properties 同源的本地凭据).
 * <p>CI 等无库环境经 {@code assumeTrue} 自动跳过 (先例: VoskFFMTest). 只覆盖只读探测路径与连接失败映射,
 * createDatabase/applySchema 属真写操作, 不在本套件触达 (其分支逻辑由 DbValidationTaskTest 的 fake 覆盖).</p>
 * @since 1.1.0
 */
class PgGatewayTest
{
    private static final String HOST = "localhost";
    private static final int PORT = 5432;
    private static final String DB = "soulnotes";
    private static final String USER = "kurv";
    //* 真机口令经环境变量注入, 兜底为公开占位符: 本机口令曾随仓库泄露并已全历史去敏,
    //! 任何硬编码真值不允许回归; 本机跑真机用例前 export SOULNOTES_DB_PASSWORD 对齐即可.
    private static final String PASSWORD =
        Objects.requireNonNullElse(System.getenv("SOULNOTES_DB_PASSWORD"), "soulnotes_dev");

    @Test void probeRealServerReturnsOkWhenSchemaComplete()
    {
        assumeTrue(InfraProbes.postgresReachable(), "本机 postgres 未运行, 跳过真机探测用例");
        final var result = new PgGateway().probe(new DbTarget(HOST, PORT, DB, USER, PASSWORD));
        assertEquals(ProbeResult.State.OK, result.state());
        assertTrue(result.missingTables().isEmpty());
    }

    @Test void probeRealServerMapsWrongPasswordToAuthFailed()
    {
        assumeTrue(InfraProbes.postgresReachable(), "本机 postgres 未运行, 跳过真机探测用例");
        final var result = new PgGateway().probe(new DbTarget(HOST, PORT, DB, USER, "wrong-password-for-test"));
        assertEquals(ProbeResult.State.AUTH_FAILED, result.state());
    }

    @Test void probeRealServerMapsMissingDatabaseToDbMissing()
    {
        assumeTrue(InfraProbes.postgresReachable(), "本机 postgres 未运行, 跳过真机探测用例");
        //* 连接不存在的库不产生任何写入: 服务端校验库名后直接 FATAL 3D000.
        final var result = new PgGateway().probe(new DbTarget(HOST, PORT, "soulnotes_probe_absent_db", USER, PASSWORD));
        assertEquals(ProbeResult.State.DB_MISSING, result.state());
    }

    @Test void probeRefusedPortMapsToUnreachable()
    {
        assumeTrue(!InfraProbes.reachable(HOST, 5999), "端口 5999 意外被占用, 跳过拒绝连接用例");
        final var result = new PgGateway().probe(new DbTarget(HOST, 5999, DB, USER, PASSWORD));
        assertEquals(ProbeResult.State.UNREACHABLE, result.state());
    }

    //* applySchema 依赖简单查询协议的单批多语句 (每脚本一次 query 执行, 失败整脚本回滚):
    //* 此处用只读双 SELECT 实证本版本 Vert.x 支持该语义, 不触达任何写路径.
    @Test void simpleQueryProtocolAcceptsMultiStatementBatch()
    {
        assumeTrue(InfraProbes.postgresReachable(), "本机 postgres 未运行, 跳过真机探测用例");
        final var vertx = Vertx.vertx();
        try
        {
            final var options = new PgConnectOptions().setHost(HOST).setPort(PORT).setDatabase(DB).setUser(USER).setPassword(PASSWORD);
            final var pool = PgBuilder.pool(builder -> builder.using(vertx).connectingTo(options).with(new PoolOptions().setMaxSize(1)));
            try
            {
                final var rows = pool.query("SELECT 1 AS first_probe; SELECT 2 AS second_probe;").execute().
                    toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(1, rows.iterator().next().getInteger(0), "多语句批应可执行, 首语句结果可读");
            }
            finally { pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS); }
        }
        catch(Exception e) { fail("多语句简单查询批执行失败: " + e.getMessage()); }
        finally { vertx.close().toCompletionStage().toCompletableFuture().join(); }
    }
}
