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
 * <b>终端 IO 通道</b>
 * <p>Pre-Launch 阶段 (校验报告/配置向导) 的统一交互出口. 双输出通道分离 UI 与诊断:
 * {@link #writeOut} = stdout (向导清单/横幅等界面渲染, 保持 stdout 可被管道干净消费),
 * {@link #writeErr} = stderr (校验报告/回退提示等诊断输出). 输入侧 {@link #readSecret}
 * 优先 {@code Console.readPassword} (不回显), 无 Console 环境回退 {@link #readLine}.</p>
 * @since 2.0
 */
public interface TerminalIO
{
    //region 输出通道

    //* UI 渲染通道 (stdout): 向导界面输出专用, 诊断/报告请走 writeErr.
    void writeOut(@NotNull String text);

    //* 诊断通道 (stderr): 校验报告/取消提示/回退提示等非 UI 输出.
    void writeErr(@NotNull String text);

    //endregion

    //region 输入

    //* 返回 null 表示输入流不可读或流已关闭, 语义为"用户放弃交互", 由调用方走取消/退出分支.
    @Nullable String readLine();

    //* Console.readPassword (不回显); 无 Console 环境 (管道/IDE/重定向) 回退 readLine 并提示回显风险, null 语义同 readLine.
    char @Nullable [] readSecret();

    //endregion

    //region 工厂

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

    //* 测试注入用 Fake: 脚本化输入队列, 双通道写入汇入同一 sink 供断言.
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
