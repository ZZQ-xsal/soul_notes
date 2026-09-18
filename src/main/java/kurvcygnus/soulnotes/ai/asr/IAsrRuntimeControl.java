package kurvcygnus.soulnotes.ai.asr;

import io.smallrye.mutiny.Uni;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;

/**
 * ASR 运行时控制端口: 就绪探测 + 运行时自动安装.
 * <p>Pre-Launch 阶段 (配置校验/向导) 运行于 CDI 容器启动之前, 只能以纯构造装配消费 ASR 能力;
 * 本接口是该消费面的最小端口, 生产实现为 {@link AsrRuntimeManager}.</p>
 *
 * <p>不做 sealed: Pre-Launch 的单元测试须跨包伪造本端口 (sealed permits 无法覆盖测试源),
 * 与 {@code IPreLaunchTask} 那种 "有意的扩展门槛" 定位不同, 这里允许可替换实现.</p>
 * @since 1.1.0
 */
public interface IAsrRuntimeControl
{
    /**
     * 运行时是否就绪 (模型完整 + 平台动态库存在).
     * <p>纯文件系统检查, 无网络与副作用, 可在任意线程安全调用 (Pre-Launch 校验与向导均在主线程).</p>
     *
     * @return true = 可直接进入识别
     */
    boolean ready();

    /**
     * 确保运行时就绪: 缺模型补模型 zip, 缺动态库补 JAR 提取.
     *
     * @param progress 进度回调 (已接收字节, 总字节; Content-Length 缺失时总字节为 -1), 可为 null
     * @return 完成信号; 失败时 Uni 失败, 并发第二路以 IllegalStateException 拒绝 (不排队)
     */
    @SuppressWarnings("NullableProblems")//! Mock实现都位于测试模块下; 测试模块无法使用 JetBrains Annotations.
    @NotNull Uni<Void> ensureDownloaded(@Nullable BiConsumer<Integer, Integer> progress);
}
