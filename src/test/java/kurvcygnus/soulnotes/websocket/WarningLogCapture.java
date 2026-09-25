package kurvcygnus.soulnotes.websocket;

import org.jboss.logmanager.Level;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.LogRecord;

/**
 * <b>WARN 日志捕获器</b>: 挂在 jboss-logmanager 的指定渠道 logger 上, 供渠道安全网哨兵断言
 * (项目 SLF4J 后端 = slf4j-jboss-logmanager, 纯单元测试无 Quarkus 容器, 只能直挂 LogContext 捕获).
 * <p>渠道 fire-and-forget 契约的失败收口只能从日志观测: 外层安全网哨兵 WARN 是否触发, 是
 * "逐号失败是否逃逸" 这类契约最直接的确定性判别 (时序判别受生产 3s 请求超时钳制, 不可靠).</p>
 * <p>测试源不使用 JetBrains Annotations, 故无 NullableProblems 检查面, 不设抑制注解.</p>
 * @since 1.2.0
 */
final class WarningLogCapture extends java.util.logging.Handler
{
    private final org.jboss.logmanager.Logger target;
    //* 挂接前的显式等级 (null = 继承): detach 时还原, 不污染其余用例的日志行为.
    //* jboss Logger 的 getLevel 未覆写 (回 JUL Level) 而 setLevel 被覆写为只收 jboss Level, 还原时需换算.
    private final java.util.logging.Level previousLevel;
    private final List<String> messages = new CopyOnWriteArrayList<>();

    private WarningLogCapture(org.jboss.logmanager.Logger target, java.util.logging.Level previousLevel)
    {
        this.target = target;
        this.previousLevel = previousLevel;
    }

    /**
     * 挂接到 loggerOwner 对应类名的 logger, 捕获其全部 WARN 及以上日志.
     *
     * @param loggerOwner 渠道实现类 (logger category 与生产 {@code LoggerFactory.getLogger(Class)} 一致)
     * @return 已挂接的捕获器 (用毕必须 {@link #detach()})
     */
    static WarningLogCapture attach(Class<?> loggerOwner)
    {
        final var target = org.jboss.logmanager.LogContext.getLogContext().getLogger(loggerOwner.getName());
        final var capture = new WarningLogCapture(target, target.getLevel());
        target.setLevel(Level.WARNING);
        target.addHandler(capture);
        return capture;
    }

    /**
     * 已捕获的日志消息原文 (SLF4J {} 占位符不替换, 断言按前缀子串匹配).
     */
    List<String> messages() { return List.copyOf(messages); }

    /**
     * 摘下捕获器并还原 logger 等级.
     */
    void detach()
    {
        target.removeHandler(this);
        target.setLevel(previousLevel);
    }

    @Override public void publish(LogRecord record) { messages.add(formatPlaceholders(record)); }

    @Override public void flush() { }

    @Override public void close() { }

    //* SLF4J {} 占位符还原 (ExtLogRecord#getFormattedMessage 已弃用, 故手工替换): 逐个 "{}" 消费一个实参,
    //* 断言需要命中 errcode 等实参, 裸 pattern 不含它们.
    private static String formatPlaceholders(LogRecord record)
    {
        final var pattern = record.getMessage();
        final var args = record.getParameters();
        if(args == null || args.length == 0)
            return pattern;
        final var out = new StringBuilder();
        var argIndex = 0;
        for(var i = 0; i < pattern.length(); i++)
        {
            if(argIndex < args.length && i + 1 < pattern.length() && pattern.charAt(i) == '{' && pattern.charAt(i + 1) == '}')
            {
                out.append(args[argIndex++]);
                i++;//* 跳过配对的 '}'.
            }
            else
                out.append(pattern.charAt(i));
        }
        return out.toString();
    }
}
