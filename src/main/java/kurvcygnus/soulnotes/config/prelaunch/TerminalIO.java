package kurvcygnus.soulnotes.config.prelaunch;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;

/**
 * 终端 IO 通道: Pre-Launch 阶段 (校验报告/配置向导) 的统一交互出口.
 * <p>双输出通道分离 UI 与诊断: {@link #writeOut} = stdout (向导清单/横幅等界面渲染, 保持 stdout 可被管道干净消费),
 * {@link #writeErr} = stderr (校验报告/回退提示等诊断输出).</p>
 *
 * @implNote 输入侧 {@link #readSecret} 优先 {@code Console.readPassword} (不回显), 无 Console 环境回退 {@link #readLine};
 *           读取失败一律归一为 null 交调用方决策, 不让启动流程抛异常.
 * @since 1.1.0
 */
public interface TerminalIO
{
    //region 输出通道

    /**
     * UI 渲染通道 (stdout): 向导界面输出专用, 诊断/报告请走 {@link #writeErr}.
     *
     * @param text 待写出文本, 原样输出; 换行由调用方携带在文本内
     * @since 1.1.0
     */
    void writeOut(@NotNull String text);

    /**
     * 诊断通道 (stderr): 校验报告/取消提示/回退提示等非 UI 输出.
     *
     * @param text 待写出文本, 原样输出
     * @since 1.1.0
     */
    void writeErr(@NotNull String text);

    //endregion

    //region 输入

    /**
     * 读取一行明文输入.
     *
     * @return 用户输入行 (不含行尾换行); null 表示输入流不可读或已关闭 (EOF), 语义为"用户放弃交互", 由调用方走取消/退出分支
     * @since 1.1.0
     */
    @Nullable String readLine();

    /**
     * 读取一行密文输入.
     *
     * @return 密文字符数组; 有 Console 时经 {@code Console.readPassword} 不回显,
     *         无 Console 环境 (管道/IDE/重定向) 回退 {@link #readLine} 并先经 stderr 提示回显风险; null 语义同 {@link #readLine}
     * @since 1.1.0
     */
    char @Nullable [] readSecret();

    //endregion

    //region 工厂

    /**
     * 生产实现: 以 UTF-8 显式包装 std 三流.
     *
     * @return 绑定进程标准流的终端通道
     * @implNote Windows 控制台默认 GBK: out/err 显式以 UTF-8 重建, 与 stdin 侧对称, 避免中文 UI/报告乱码.
     * @since 1.1.0
     */
    static @NotNull TerminalIO system()
    {
        final var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        //* Windows 控制台默认 GBK: 显式以 UTF-8 重建 out/err, 与 stdin 侧对称, 避免中文 UI/报告乱码.
        final var out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        final var err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        return new TerminalIO()
        {
            //* 显式 flush 保证无换行的提示符即时可见 (print 不触发 autoFlush).
            @Override public void writeOut(@NotNull String text)
            {
                out.print(text);
                out.flush();
            }

            @Override public void writeErr(@NotNull String text)
            {
                err.print(text);
                err.flush();
            }

            @Override public @Nullable String readLine()
            {
                try { return reader.readLine(); }
                catch(IOException e) { return null; }//! stdin 不可读 (管道关闭等) 不应让启动流程抛异常, 归一为 null 交调用方决策.
            }

            @Override public char @Nullable [] readSecret()
            {
                final var console = System.console();
                if(console != null) return console.readPassword();
                //* 无 Console 回退明文输入必须提示; 提示走 err 通道, 不污染 UI 渲染流.
                writeErr("(当前终端不支持隐藏输入, 密文将回显)\n");
                final var line = readLine();
                return line == null ? null : line.toCharArray();
            }
        };
    }

    /**
     * 测试注入用 Fake: 脚本化输入队列, 双通道写入汇入同一 sink 供断言.
     *
     * @param scriptedInputs 预置输入序列, 耗尽后 {@code readLine/readSecret} 返回 null (与 EOF 语义一致)
     * @param sink 全部写出内容 (out 与 err 不区分) 的收集器
     * @return 可编程的终端通道
     * @since 1.1.0
     */
    static @NotNull TerminalIO fake(@NotNull List<String> scriptedInputs, @NotNull StringBuilder sink)
    {
        final var queue = new ArrayDeque<>(scriptedInputs);
        return new TerminalIO()
        {
            @Override public void writeOut(@NotNull String text) { sink.append(text); }

            @Override public void writeErr(@NotNull String text) { sink.append(text); }

            @Override public @Nullable String readLine() { return queue.pollFirst(); }//* 脚本耗尽 → null, 与 EOF 语义一致.

            @Override public char @Nullable [] readSecret()
            {
                final var line = queue.pollFirst();
                return line == null ? null : line.toCharArray();
            }
        };
    }

    //endregion
}
