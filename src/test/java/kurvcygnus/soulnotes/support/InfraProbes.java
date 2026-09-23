package kurvcygnus.soulnotes.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * <b>测试基建缺席探测 (测试源公共工具)</b>
 * <p>统一 "本机开发容器是否存在" 的判定逻辑 (原 {@code JsonbPersistenceFormTest#reachable} 收编):
 * 500ms 短超时只判定端口是否有监听者, 不参与连接本身.</p>
 * <p>两类消费面:</p>
 * <ul>
 *     <li>{@code assumeTrue} 真机用例守卫 (JsonbPersistenceFormTest / PgGatewayTest 先例)</li>
 *     <li>{@code @EnabledIf} 类级守卫: Mock-LLM 全链路 {@code @QuarkusTest} 强依赖 postgres + redis,
 *     应用启动期即失败, {@code assumeTrue} 来不及救 — 必须在 JUnit 执行条件层拦截</li>
 * </ul>
 * <p>不标注 JetBrains 注解: 该库为 compileOnly, 测试源集不可见 (先例: 全部测试类).</p>
 * @since 1.1.0
 */
public final class InfraProbes
{
    public static final String POSTGRES_HOST = "localhost";
    public static final int POSTGRES_PORT = 5432;
    public static final int REDIS_PORT = 6379;

    private InfraProbes() { }

    /**
     * <span style="color: 95cc6d">postgres 开发容器是否在监听.</span>
     * @return true = 端口可达
     */
    public static boolean postgresReachable() { return reachable(POSTGRES_HOST, POSTGRES_PORT); }

    /**
     * <span style="color: 95cc6d">redis 开发容器是否在监听.</span>
     * @return true = 端口可达
     */
    public static boolean redisReachable() { return reachable(POSTGRES_HOST, REDIS_PORT); }

    /**
     * <span style="color: 95cc6d">Mock-LLM 全链路 (@QuarkusTest) 所需的双基建是否齐备.</span>
     * <p>{@code @EnabledIf} 直接引用本方法 (public 静态无参, 字符串全限定引用的签名契约).</p>
     * @return true = postgres 与 redis 均可达, 全链路用例可执行
     */
    public static boolean pipelineInfraReachable() { return postgresReachable() && redisReachable(); }

    //* 500ms 短超时只为守卫探路: 判定端口是否有监听者, 不参与连接本身.
    public static boolean reachable(String host, int port)
    {
        try(var socket = new Socket())
        {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        }
        catch(IOException e) { return false; }
    }
}
