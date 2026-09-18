package kurvcygnus.soulnotes.config.prelaunch;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <b>Pre-Launch 配置向导</b> (Spec §7.0-7.2).
 * <p>pnpm 风格折叠清单的交互实现: 模式选择 (简单 = 仅必填 / 全面 = 全部) → 逐项编辑 (就地校验) →
 * 摘要确认 → 双文件落盘 → 完成屏 (启动/退出). 仅显式值进入结果与落盘, 未动项保持内置默认.</p>
 * <p>交互妥协记录 (审计): Windows conhost 无 raw mode 且零依赖约束禁用 JLine, 方向键全屏导航不可行,
 * 降级为 "序号跳转 + Enter 顺序遍历"; Esc 键在行缓冲输入下不可检测, 展开态以字面量 {@code esc} 充当放弃;
 * 展开块渲染于清单底部而非条目行内 (行缓冲输入无法在已输出行之间插入交互块); 重绘采用滚动重印而非
 * {@link TerminalRenderer#eraseAbove(int)} 原地抹除 — 30 项清单超出 conhost 视口时光标上移被钳制在屏顶, 行号推算不可靠.</p>
 * @since 2.0
 */
public final class SetupWizard
{
    //region 公共表面

    public enum Mode { SIMPLE, FULL }

    //! CANCELLED 不得并入 EXIT: Spec §6.2 规定向导中途取消 (EOF/Ctrl+C) → 退出码 1, 由 Entrance 据此 System.exit(1); 向导自身禁调 System.exit (脚本化测试依赖). 用户主动选退出仍是 EXIT (退出码 0).
    //! FAILED 不得并入 EXIT (评审轮次 2): 写盘失败属异常收场, 退出码必须为 1 (由 Entrance 映射), 否则与用户主动退出同码, 取消/失败语义倒挂.
    public enum NextAction { LAUNCH, EXIT, CANCELLED, FAILED }

    //* values 以 envName 为键, 仅显式设置项 (本次输入 + 已存在显式配置预填); 可选留默认项不入表, 保证落盘最小化.
    //* 继承自环境的密钥项保留在 values (进程环境中仍生效) 但不落盘, 见 [[SetupWizard#prefill]].
    public record SetupResult(@NotNull Map<String, String> values, @NotNull NextAction action) {}

    //* prefill 产物: values 供交互复用; envNames 记录继承来源 — 密钥类 (SECRET/GENERATE) 继承项落盘时排除
    //* (README prod 指引: 密钥必须环境变量注入, 且摘要屏全掩码, 用户无从察觉明文被物化), 可变集: 本会话重输即移出.
    private record Prefill(@NotNull Map<String, String> values, @NotNull Set<String> envNames) {}

    //endregion

    //region 常量与构造

    private static final @NotNull String MASK = "******";
    //* Esc 键在行缓冲输入下不可检测, 以字面量充当放弃语义 (妥协记录见类 javadoc).
    private static final @NotNull String ESC_TOKEN = "esc";
    private static final int GENERATE_RANDOM_BYTES = 48;
    //* readCommand 的哨兵: null 已被 EOF 占用, 以 -1 表示 "q 完成".
    private static final int CMD_FINISH = -1;
    private static final @NotNull SecureRandom RANDOM = new SecureRandom();

    private final @NotNull Path workDir;

    public SetupWizard() { this(Path.of("")); }

    //* workDir 注入点: 生产走进程工作目录, 测试注入 @TempDir 以断言落盘行为.
    public SetupWizard(@NotNull Path workDir)
    {
        Objects.requireNonNull(workDir, "Param \"workDir\" must not be null!");
        this.workDir = workDir;
    }

    //endregion

    //region 主流程

    /**
     * <span style="color: 95ccfd">执行向导主流程: 模式 → 编辑 → 摘要 → 落盘 → 完成屏.</span>
     * <p>EOF/Ctrl+C 在写入前发生时打印取消提示并返回空表 + CANCELLED (Entrance 据此以退出码 1 收场, Spec §6.2), 不落盘; 写盘失败返回空表 + FAILED (评审轮次 2, 同为退出码 1); 写入后的 EOF 归为 EXIT (无可取消之物).</p>
     */
    public @NotNull SetupResult run(
        @NotNull List<PropertyMetaParser.ConfigItemMeta> items,
        @NotNull ConfigView view, @NotNull TerminalIO io
    )
    {
        Objects.requireNonNull(items, "Param \"items\" must not be null!");
        Objects.requireNonNull(view, "Param \"view\" must not be null!");
        Objects.requireNonNull(io, "Param \"io\" must not be null!");

        final var prefill = prefill(items, view);
        final var values = prefill.values();
        final var prefilled = prefill.envNames();
        final var mode = askMode(items.size(), io);
        if(mode == null) return cancelled(io);
        final var visible = items.stream().
            filter(meta -> mode == Mode.FULL || meta.required()).
            toList();

        boolean editing = !visible.isEmpty();
        Integer expanded = editing ? 0 : null;  //* null = 折叠命令态; 非空 = 展开态下标 (顺序遍历从首项起).
        int next = 0;                           //* Enter 顺序遍历的光标.
        while(true)
        {
            if(editing)
            {
                if(expanded != null)
                {
                    renderList(visible, values, io);  //* 每次展开先整屏重绘清单: 行状态随 values 实时变化 (滚动重印, 妥协记录见类 javadoc).
                    switch(editExpanded(visible.get(expanded), values, prefilled, io))
                    {
                        case CANCEL -> { return cancelled(io); }
                        case SAVE ->
                        {
                            next = expanded + 1;
                            if(next >= visible.size()) { expanded = null; editing = false; }  //* 末项已存 → 直达摘要.
                            else expanded = next;                                             //* 保存即顺序推进到下一项.
                        }
                        case DISCARD -> expanded = null;  //* 放弃 → 折叠命令态, 光标仍指向下一未编辑项.
                    }
                    continue;
                }
                final var cmd = readCommand(visible.size(), next, io);
                if(cmd == null) return cancelled(io);
                if(cmd == CMD_FINISH) editing = false;
                else { expanded = cmd; next = cmd + 1; }
                continue;
            }

            //* 摘要阶段: 确认 → 落盘 + 完成屏; 否决 → 回编辑态 (values 保留).
            renderSummary(items, values, prefilled, io);
            final var confirm = readConfirm(io);
            if(confirm == null) return cancelled(io);
            if(confirm) return finish(items, values, prefilled, io);
            editing = !visible.isEmpty();
            next = 0;
        }
    }

    //endregion

    //region 阶段一: 模式选择

    //* 返回 null = EOF 取消; 回车默认简单配置 (低门槛优先, Spec §7.0).
    private static @Nullable Mode askMode(int total, @NotNull TerminalIO io)
    {
        io.writeOut("\n" + emph("== Soul Notes 配置向导 ==") + "\n\n");
        io.writeOut("  1. 简单配置 (仅必填项)\n");
        io.writeOut("  2. 全面配置 (全部 " + total + " 项)\n");
        while(true)
        {
            io.writeOut("请选择 [1/2, 回车=1]: ");
            final var raw = io.readLine();
            if(raw == null) return null;
            final var t = raw.strip();
            if(t.isEmpty() || t.equals("1")) return Mode.SIMPLE;
            if(t.equals("2")) return Mode.FULL;
            io.writeOut(bad("✗ 无效选择, 请输入 1 或 2") + "\n");
        }
    }

    //endregion

    //region 阶段二: 清单渲染与编辑

    //* 清单整屏重绘 (滚动重印, 妥协记录见类 javadoc): 组头 + v2 状态行.
    private static void renderList(
        @NotNull List<PropertyMetaParser.ConfigItemMeta> visible,
        @NotNull Map<String, String> values,
        @NotNull TerminalIO io
    )
    {
        io.writeOut("\n");
        String prevGroup = null;
        for(final var meta : visible)
        {
            if(prevGroup == null || !prevGroup.equals(meta.group()))
            {
                io.writeOut(dim("── " + meta.group()) + "\n");
                prevGroup = meta.group();
            }
            io.writeOut(TerminalRenderer.listItemLine(stateOf(meta, values)) + "\n");
        }
    }

    //* 展开态结局: SAVE = 保留/保存并推进; DISCARD = esc 放弃折叠; CANCEL = EOF 取消.
    private enum Outcome { SAVE, DISCARD, CANCEL }

    /**
     * <span style="color: 95ccfd">展开块交互 (Spec §7.1): 输入行 + 空行 + 灰斜体解释.</span>
     * <p>空输入语义: 已有当前值 → 保留; GENERATE → 自动生成; 必填未配 → 红字重问 (不折叠);
     * 可选未配 → 保留默认且不入 values. 非法输入原地红字重问; {@code esc} 放弃折叠.</p>
     */
    private static @NotNull Outcome editExpanded(
        @NotNull PropertyMetaParser.ConfigItemMeta meta,
        @NotNull Map<String, String> values,
        @NotNull Set<String> prefilled,
        @NotNull TerminalIO io
    )
    {
        final var env = meta.envName();
        final var explain = meta.explain().isEmpty() ? List.<String>of() : List.of(meta.explain().split("\n"));
        boolean explainShown = false;  //* 解释只在首个输入行后渲染一次, 重问循环不重复刷屏.
        while(true)
        {
            io.writeOut("  " + emph("❯") + " " + env + currentHint(meta, values) + ": ");
            final var raw = secretLike(meta) ? readSecretLine(io) : io.readLine();
            if(raw == null)
                return Outcome.CANCEL;
            if(!explainShown)
            {
                io.writeOut("\n");
                for(final var line : explain) io.writeOut("  " + dim(line) + "\n");
                explainShown = true;
            }
            final var t = raw.strip();
            if(t.equalsIgnoreCase(ESC_TOKEN))
                return Outcome.DISCARD;
            if(t.isEmpty())
            {
                if(values.containsKey(env))
                    return Outcome.SAVE;  //* 回车 = 保留当前值.
                if(meta.inputType() == PropertyMetaParser.InputType.GENERATE)
                {
                    values.put(env, generateSecret());
                    return Outcome.SAVE;
                }
                if(meta.required())
                {
                    io.writeOut(bad("  ✗ 必填项, 请输入值 (输入 " + ESC_TOKEN + " 放弃)") + "\n");
                    continue;
                }
                return Outcome.SAVE;  //* 可选留空 = 保留默认, 不入 values (ConfigWriter 仅写显式值).
            }
            final var err = FieldValidator.validate(meta, t);
            if(err.isPresent())
            {
                io.writeOut(bad("  ✗ " + err.get()) + "\n");  //* 原地红字: 留在展开态重新提问.
                continue;
            }
            values.put(env, t);
            prefilled.remove(env);  //* 本会话重新输入: 覆盖继承值并恢复落盘资格 (不再视为 prefill 来源).
            return Outcome.SAVE;
        }
    }

    //* 折叠命令态: 返回 null = EOF 取消; CMD_FINISH = q 完成; >=0 = 待展开条目下标.
    private static @Nullable Integer readCommand(int size, int next, @NotNull TerminalIO io)
    {
        io.writeOut(dim("序号 跳转 / Enter 顺序遍历 / q 完成") + "\n");
        while(true)
        {
            io.writeOut(emph("❯") + " ");
            final var raw = io.readLine();
            if(raw == null)
                return null;
            final var t = raw.strip();
            if(t.isEmpty())
                return Math.min(next, size - 1);
            if(t.equalsIgnoreCase("q"))
                return CMD_FINISH;
            //* 限长 9 位: 先挡超长数字串, 再 parse, 杜绝 parseInt 溢出异常.
            if(t.matches("[0-9]{1,9}"))
            {
                final var idx = Integer.parseInt(t) - 1;
                if(idx >= 0 && idx < size) return idx;
                io.writeOut(bad("✗ 序号超出范围 (1-" + size + ")") + "\n");
                continue;
            }
            io.writeOut(bad("✗ 无效输入: 序号 (1-" + size + ") / Enter 顺序遍历 / q 完成") + "\n");
        }
    }

    //endregion

    //region 阶段三: 摘要与落盘

    private static void renderSummary(
        @NotNull List<PropertyMetaParser.ConfigItemMeta> items,
        @NotNull Map<String, String> values,
        @NotNull Set<String> prefilled,
        @NotNull TerminalIO io
    )
    {
        io.writeOut("\n" + emph("== 配置摘要 ==") + "\n");
        String prevGroup = null;
        for(final var meta: items)
        {
            final var v = values.get(meta.envName());
            if(v == null)
                continue;
            if(prevGroup == null || !prevGroup.equals(meta.group()))
            {
                io.writeOut(dim("── " + meta.group()) + "\n");
                prevGroup = meta.group();
            }
            io.writeOut("  " + meta.key() + " = " + displayValue(meta, v) + "\n");
            if(prefilled.contains(meta.envName()) && secretLike(meta))
                io.writeOut(dim("  (继承自环境, 不写入文件)") + "\n");  //* 掩码之下用户无从分辨落盘与否, 须显式预告该项不会进入输出文件.
        }
        if(values.isEmpty()) io.writeOut(dim("  (无显式设置项, 全部使用内置默认)") + "\n");
        io.writeOut(dim("未列出的项保持内置默认; 已存在的输出文件会先备份为 *.bak") + "\n");
    }

    //* 返回 null = EOF 取消; TRUE = 确认写入; FALSE = 否决回编辑态.
    private static @Nullable Boolean readConfirm(@NotNull TerminalIO io)
    {
        while(true)
        {
            io.writeOut("确认写入? [Y/n] ");
            final var raw = io.readLine();
            if(raw == null) return null;
            final var t = raw.strip();
            if(t.isEmpty() || t.equalsIgnoreCase("y")) return Boolean.TRUE;
            if(t.equalsIgnoreCase("n")) return Boolean.FALSE;
            io.writeOut(bad("✗ 无效输入, 请输入 Y 或 n") + "\n");
        }
    }

    private @NotNull SetupResult finish(
        @NotNull List<PropertyMetaParser.ConfigItemMeta> items,
        @NotNull Map<String, String> values,
        @NotNull Set<String> prefilled,
        @NotNull TerminalIO io
    )
    {
        final var propsPath = workDir.resolve("config").resolve("application.properties");
        final var envPath = workDir.resolve(".env");
        final var hadProps = Files.exists(propsPath);
        final var hadEnv = Files.exists(envPath);
        try { ConfigWriter.write(workDir, items, persistableValues(items, values, prefilled), Instant.now().toString()); }
        catch(IOException e)
        {
            //! 落盘失败必须显式可见, 向导以异常收场语义结束 — 不存在静默半写状态.
            io.writeErr(bad("✗ 配置写入失败: " + e) + "\n");
            //! 评审轮次 2: 失败归 EXIT 会使进程退出码 0, 与取消的退出码 1 倒挂 → 改 FAILED (Entrance 映射 System.exit(1)); 返回空表: 值未持久化, 维持 "非空 values ⟺ 已落盘" 不变量.
            return new SetupResult(Map.of(), NextAction.FAILED);
        }
        //* 完成屏 (Spec §7.2.5): 双文件 ✔ (含 .bak 备注) + 启动/退出选择.
        io.writeOut("\n" + ok("✔") + " 配置已写入: " + displayPath(propsPath) + bakNote(hadProps, "application.properties.bak") + "\n");
        io.writeOut(ok("✔") + " 配置已写入: " + displayPath(envPath) + bakNote(hadEnv, ".env.bak") + "\n\n");
        io.writeOut("  1. 启动应用\n  2. 退出\n");
        while(true)
        {
            io.writeOut("请选择 [1/2, 回车=1]: ");
            final var raw = io.readLine();
            if(raw == null) return new SetupResult(Map.copyOf(values), NextAction.EXIT);
            final var t = raw.strip();
            if(t.isEmpty() || t.equals("1"))
                return new SetupResult(Map.copyOf(values), NextAction.LAUNCH);
            if(t.equals("2")) return new SetupResult(Map.copyOf(values), NextAction.EXIT);
            io.writeOut(bad("✗ 无效选择, 请输入 1 或 2") + "\n");
        }
    }

    //endregion

    //region 内部工具

    //* 预填显式配置 (sysprop/env/工作目录文件), 已配置项在清单中呈 CONFIGURED/OPTIONAL_SET, 展开态回车即保留;
    //* envNames 并行记录继承来源, 供摘要预告与 finish 排除密钥类条目落盘.
    private static @NotNull Prefill prefill(
        @NotNull List<PropertyMetaParser.ConfigItemMeta> items,
        @NotNull ConfigView view
    )
    {
        final var values = new LinkedHashMap<String, String>();
        final var envNames = new LinkedHashSet<String>();
        for(final var meta: items)
        {
            if(meta.envName() == null)
                continue;  //! 非向导条目理论上不会出现 (解析器已过滤), 防御 null 键入表.
            view.explicit(meta.key(), meta.envName()).ifPresent(v -> { values.put(meta.envName(), v); envNames.add(meta.envName()); });
        }
        return new Prefill(values, envNames);
    }

    //* 落盘值 = values - (prefill 来源 ∩ 密钥类): 继承密钥在进程环境中仍生效, 物化到文件违反
    //* "prod 密钥必须环境变量注入" 指引且摘要屏全掩码, 用户无从察觉; 其余显式值照常落盘.
    private static @NotNull Map<String, String> persistableValues(
        @NotNull List<PropertyMetaParser.ConfigItemMeta> items,
        @NotNull Map<String, String> values,
        @NotNull Set<String> prefilled
    )
    {
        final var persisted = new LinkedHashMap<String, String>();
        for(final var meta: items)
        {
            if(!values.containsKey(meta.envName())) continue;
            if(prefilled.contains(meta.envName()) && secretLike(meta)) continue;
            persisted.put(meta.envName(), values.get(meta.envName()));
        }
        return persisted;
    }

    //* 取消语义: 不落盘且累计值一并丢弃 (空表), 维持 "非空 values ⟺ 已落盘" 不变量; 返回 CANCELLED 而非 EXIT, 差异在退出码 (见 NextAction 上的 //! 注).
    private static @NotNull SetupResult cancelled(@NotNull TerminalIO io)
    {
        io.writeErr("已取消, 未写入任何文件\n");
        return new SetupResult(Map.of(), NextAction.CANCELLED);
    }

    private static @Nullable String readSecretLine(@NotNull TerminalIO io)
    {
        final var chars = io.readSecret();
        return chars == null ? null : new String(chars);
    }

    //* GENERATE 空输入: 48 字节 SecureRandom → base64 (64 字符, 天然满足 minLength 32 的规则矩阵下限).
    private static @NotNull String generateSecret()
    {
        final var bytes = new byte[GENERATE_RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static boolean secretLike(@NotNull PropertyMetaParser.ConfigItemMeta meta)
    {
        return meta.inputType() == PropertyMetaParser.InputType.SECRET ||
               meta.inputType() == PropertyMetaParser.InputType.GENERATE;
    }

    //* 密钥类字段的任何回显 (行尾/提示/摘要) 一律掩码, 明文仅存在于 values 与落盘文件.
    private static @NotNull String displayValue(@NotNull PropertyMetaParser.ConfigItemMeta meta, @NotNull String v) { return secretLike(meta) ? MASK : v; }

    private static @NotNull String currentHint(
        @NotNull PropertyMetaParser.ConfigItemMeta meta,
        @NotNull Map<String, String> values
    )
    {
        final var current = values.get(meta.envName());
        if(current != null)
            return " [当前: " + displayValue(meta, current) + "]";
        return meta.defaultValue().isEmpty() ? "" : " [默认: " + displayValue(meta, meta.defaultValue()) + "]";
    }

    private static @NotNull TerminalRenderer.ListItem stateOf(
        @NotNull PropertyMetaParser.ConfigItemMeta meta,
        @NotNull Map<String, String> values
    )
    {
        final var env = meta.envName();
        final var current = values.get(env);
        if(current != null)
        {
            final var err = FieldValidator.validate(meta, current);
            //* 预填值可能不合法 (env/旧配置): 整行红 + 回显原因, 提示用户展开重填.
            return err.map(
                s -> new TerminalRenderer.ListItem(
                    TerminalRenderer.ItemState.REQUIRED_INVALID,
                    env,
                    meta.humanName(),
                    s
                )
            ).orElseGet(
                () -> new TerminalRenderer.ListItem(
                    meta.required() ? TerminalRenderer.ItemState.CONFIGURED : TerminalRenderer.ItemState.OPTIONAL_SET,
                    env, meta.humanName(), displayValue(meta, current)
                )
            );
        }
        if(meta.required())
            return new TerminalRenderer.ListItem(TerminalRenderer.ItemState.REQUIRED_EMPTY, env, meta.humanName(), null);
        return new TerminalRenderer.ListItem(TerminalRenderer.ItemState.OPTIONAL_DEFAULT, env, meta.humanName(), meta.defaultValue());
    }

    //* 着色速记: 收拢主要调用点的 TerminalRenderer 全限定引用, 保持渲染行可读.
    private static @NotNull String dim(@NotNull String s) { return TerminalRenderer.paint(TerminalRenderer.Style.DIM, s); }

    private static @NotNull String emph(@NotNull String s) { return TerminalRenderer.paint(TerminalRenderer.Style.EMPHASIS, s); }

    private static @NotNull String ok(@NotNull String s) { return TerminalRenderer.paint(TerminalRenderer.Style.OK, s); }

    private static @NotNull String bad(@NotNull String s) { return TerminalRenderer.paint(TerminalRenderer.Style.BAD, s); }

    //* 展示统一正斜杠: 跨平台一致且与文档/compose 路径写法对齐.
    private static @NotNull String displayPath(@NotNull Path p) { return p.toString().replace('\\', '/'); }

    private static @NotNull String bakNote(boolean had, @NotNull String bakName)
        { return had ? " (原文件已备份为 " + bakName + ")" : ""; }

    //endregion
}
