package kurvcygnus.soulnotes.config.prelaunch;

import kurvcygnus.soulnotes.ai.IModelCatalog;
import kurvcygnus.soulnotes.ai.asr.IAsrRuntimeControl;
import kurvcygnus.soulnotes.utils.PrintUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Pre-Launch 配置向导.
 * <p>pnpm 风格折叠清单的交互实现: 模式选择 (简单 = 仅必填 / 全面 = 全部) → 逐项编辑 (就地校验) →
 * 摘要确认 → 双文件落盘 → 完成屏 (启动/退出). 仅显式值进入结果与落盘, 未动项保持内置默认.</p>
 * <p>ASR 运行时交互: 保存 {@code asr.engine} / {@code asr.runtime.dir} 条目后触发一次
 * 就绪检查, 未就绪现场询问是否立即下载 (进度行内回显); 控制端口经构造注入, null = 禁用该交互.
 * 会话内改过 {@code asr.runtime.dir} 时, 就绪检查/下载/回执一律以目录重建函数产出的
 * "当前目录实例" 为准 (下载落会话新目录, 回执以新实例 {@code ready()} 判定), 重建函数 null 时回退预装配端口.</p>
 * <p>数据库交互流: 数据库组三项 (URL/用户名/密码) 全部保存且值指纹变化时触发一次性
 * probe → 五态分流 (直通 ✔ / 即时 ✗ 回重编辑 / 自动建库 / 询问建表); 网关经构造注入, null = 禁用该流.</p>
 * <p>AI 模型拉取步: AI 接入组 endpoint+key 保存且值指纹变化时触发一次性模型列表拉取
 * (探测 URL 回显 → 元数据列表 → 序号选择), 401/403 回重编辑密钥, 网络失败/空列表退化为手动输入;
 * 拉取成功后 endpoint 存规范形 (fetch 与存储分离), 目录端口经构造注入, null = 禁用该流.</p>
 * <p>交互妥协记录 (审计): Windows conhost 无 raw mode 且零依赖约束禁用 JLine, 方向键全屏导航不可行,
 * 降级为 "序号跳转 + Enter 顺序遍历"; Esc 键在行缓冲输入下不可检测, 展开态以字面量 {@code esc} 充当放弃;
 * 展开块渲染于清单底部而非条目行内 (行缓冲输入无法在已输出行之间插入交互块); 重绘采用滚动重印而非
 * {@link TerminalRenderer#eraseAbove(int)} 原地抹除 — 30 项清单超出 conhost 视口时光标上移被钳制在屏顶, 行号推算不可靠
 * (例外: 下载进度为单行自刷新块, 适用 eraseAbove 原地重写).</p>
 * @since 1.1.0
 */
public final class SetupWizard
{
    //region 公共表面

    /**
     * 向导模式: SIMPLE 仅呈现必填项 (低门槛优先), FULL 呈现全部条目.
     *
     * @since 1.1.0
     */
    public enum Mode
    {
        /** 简单配置: 仅必填项进入编辑清单. */
        SIMPLE,
        /** 全面配置: 全部条目进入编辑清单. */
        FULL
    }

    /**
     * 向导收场动作.
     * <p>映射进程退出码: LAUNCH/EXIT 为正常收场 (0); CANCELLED/FAILED 为异常收场 (1, 由 Entrance 映射 System.exit).</p>
     *
     * @since 1.1.0
     */
    //! CANCELLED 不得并入 EXIT: 向导中途取消 (EOF/Ctrl+C) 属异常收场, 由 Entrance 映射 System.exit(1); 向导自身禁调 System.exit (脚本化测试依赖). 用户主动选退出仍是 EXIT (退出码 0).
    //! FAILED 不得并入 EXIT (评审轮次 2): 写盘失败属异常收场, 退出码必须为 1 (由 Entrance 映射), 否则与用户主动退出同码, 取消/失败语义倒挂.
    public enum NextAction
    {
        /** 确认配置后启动应用 (退出码 0). */
        LAUNCH,
        /** 用户主动选择退出 (退出码 0). */
        EXIT,
        /** 向导中途取消 (EOF/Ctrl+C), 未落盘 (退出码 1). */
        CANCELLED,
        /** 落盘失败, 值未持久化 (退出码 1). */
        FAILED
    }

    /**
     * 向导执行结果.
     *
     * @param values 以 envName 为键的显式值集 (本次输入 + 已存在显式配置预填); 仅显式设置项入表 —
     *        可选留默认项不入表保证落盘最小化; 继承自环境的密钥项保留在 values (进程环境中仍生效) 但不落盘 (见 {@link #prefill});
     *        CANCELLED/FAILED 收场时恒为空表, 维持 "非空 values ⟺ 已落盘" 不变量
     * @param action 收场动作
     * @since 1.1.0
     */
    public record SetupResult(@NotNull Map<String, String> values, @NotNull NextAction action) {}

    //* prefill 产物: values 供交互复用; envNames 记录继承来源 — 密钥类 (SECRET/GENERATE) 继承项落盘时排除
    //* (README prod 指引: 密钥必须环境变量注入, 且摘要屏全掩码, 用户无从察觉明文被物化), 可变集: 本会话重输即移出.
    /**
     * 预填产物: values 供交互复用, envNames 并行记录继承来源.
     *
     * @param values envName 到预填值的映射 (仅显式已配置项)
     * @param envNames values 中继承自 sysprop/env/既有配置文件的键集 — 密钥类继承项落盘时排除
     */
    private record Prefill(@NotNull Map<String, String> values, @NotNull Set<String> envNames) {}

    //endregion

    //region 常量与构造

    private static final @NotNull String MASK = "******";
    //* Esc 键在行缓冲输入下不可检测, 以字面量充当放弃语义 (妥协记录见类 javadoc).
    private static final @NotNull String ESC_TOKEN = "esc";
    private static final int GENERATE_RANDOM_BYTES = 48;
    //* readCommand 的哨兵: null 已被 EOF 占用, 以 -1 表示 "q 完成".
    private static final int CMD_FINISH = -1;

    //* ASR 运行时交互触发键: 保存任一条目后触发一次 ready() 检查 (asr.lib.url 仅改下载源, 不触发).
    //* ASR_RUNTIME_DIR_ENV 单独收口: 会话内该值变化时, 就绪检查/下载须以它重建控制实例 (单一来源).
    private static final @NotNull String ASR_RUNTIME_DIR_ENV = "SOULNOTES_ASR_RUNTIME_DIR";
    private static final @NotNull Set<String> ASR_TRIGGER_ENVS =
        Set.of("SOULNOTES_ASR_ENGINE", ASR_RUNTIME_DIR_ENV);

    //* DB 交互流触发键: 数据库组三项 envName; 组名与元数据单一来源 (application.properties @group) 对齐.
    private static final @NotNull String DB_GROUP = "数据库";
    private static final @NotNull String DB_URL_ENV = "SOULNOTES_DB_URL";
    private static final @NotNull String DB_USER_ENV = "SOULNOTES_DB_USER";
    private static final @NotNull String DB_PASSWORD_ENV = "SOULNOTES_DB_PASSWORD";

    //* AI 模型拉取步触发键: AI 接入组的 endpoint 与 api-key 两项; model 项同组但仅作为选择结果落点,
    //* 不参与触发 — 手动改模型名不应引发网络探测. 组名与元数据单一来源 (application.properties @group) 对齐.
    private static final @NotNull String AI_GROUP = "AI 接入";
    private static final @NotNull String AI_ENDPOINT_ENV = "SOULNOTES_AI_ENDPOINT";
    private static final @NotNull String AI_API_KEY_ENV = "SOULNOTES_AI_API_KEY";
    private static final @NotNull String AI_MODEL_ENV = "SOULNOTES_AI_MODEL";

    //* 模型列表拉取超时钉死 10s: 拉取是向导内的同步交互, 过长等待会让人误以为向导挂死.
    private static final @NotNull Duration AI_FETCH_TIMEOUT = Duration.ofSeconds(10);

    private final @NotNull Path workDir;
    private final @Nullable IAsrRuntimeControl asrControl;
    //* 会话内 asr.runtime.dir 变更后的重建点: 目录 (来自会话 values) → 该目录的控制实例.
    //* null = 无重建能力, ASR 交互恒用预装配 asrControl (兼容既有构造器与旧测试).
    private final @Nullable Function<String, IAsrRuntimeControl> asrControlForDir;
    private final @Nullable IDatabaseGateway dbGateway;
    private final @Nullable IModelCatalog modelCatalog;

    /** 无端口最小构造: 当前进程工作目录落盘, 三类交互端口全部禁用. */
    public SetupWizard() { this(Path.of(""), null, null); }

    /**
     * 仅注入落盘目录的构造 (生产走进程工作目录, 测试注入 @TempDir 以断言落盘行为), 交互端口全部禁用.
     *
     * @param workDir 落盘工作目录
     * @since 1.1.0
     */
    public SetupWizard(@NotNull Path workDir) { this(workDir, null, null); }

    /**
     * 单端口便利构造: 仅启用 ASR 交互, 数据库流与模型拉取步禁用.
     *
     * @param workDir    落盘工作目录
     * @param asrControl ASR 运行时控制端口 (null = 禁用未就绪询问/下载交互)
     * @since 1.1.0
     */
    public SetupWizard(@NotNull Path workDir, @Nullable IAsrRuntimeControl asrControl) { this(workDir, asrControl, null); }

    /**
     * 三端口便利构造: 启用 ASR 与数据库交互, 模型拉取步禁用.
     *
     * @param workDir    落盘工作目录
     * @param asrControl ASR 运行时控制端口 (null = 禁用未就绪询问/下载交互; Pre-Launch 于 CDI 前运行, 只能纯构造注入)
     * @param dbGateway  数据库探测/修复网关 (null = 禁用数据库交互流; 仅生产 Entrance 注入 {@link PgGateway} 或测试注入 fake)
     * @since 1.1.0
     */
    public SetupWizard(@NotNull Path workDir, @Nullable IAsrRuntimeControl asrControl, @Nullable IDatabaseGateway dbGateway)
    {
        this(
            workDir,
            asrControl,
            dbGateway,
            null
        );
    }

    /**
     * 四端口便利构造: 启用 ASR/数据库/AI 模型拉取三类交互, 无 ASR 目录重建能力.
     *
     * @param workDir      落盘工作目录
     * @param asrControl   ASR 运行时控制端口 (null = 禁用未就绪询问/下载交互; Pre-Launch 于 CDI 前运行, 只能纯构造注入)
     * @param dbGateway    数据库探测/修复网关 (null = 禁用数据库交互流; 仅生产 Entrance 注入 {@link PgGateway} 或测试注入 fake)
     * @param modelCatalog 模型目录拉取端口 (null = 禁用 AI 模型拉取步; 生产 Entrance 注入 {@link kurvcygnus.soulnotes.ai.HttpModelCatalog} 或测试注入 fake)
     * @since 1.1.0
     */
    public SetupWizard(
        @NotNull Path workDir,
        @Nullable IAsrRuntimeControl asrControl,
        @Nullable IDatabaseGateway dbGateway,
        @Nullable IModelCatalog modelCatalog
    ) { this(workDir, asrControl, dbGateway, modelCatalog, null); }

    /**
     * 全量构造入口 (含 ASR 目录重建点), 生产 Entrance 使用.
     *
     * @param workDir          落盘工作目录
     * @param asrControl       ASR 运行时控制端口, 以向导前配置视图预装配 (null = 禁用未就绪询问/下载交互)
     * @param dbGateway        数据库探测/修复网关 (null = 禁用数据库交互流)
     * @param modelCatalog     模型目录拉取端口 (null = 禁用 AI 模型拉取步)
     * @param asrControlForDir 会话内运行时目录 → 控制实例的重建函数 (null = 恒用预装配端口);
     *                         就绪检查/下载/回执以它产出的当前目录实例为准, 防 "下载落旧目录 + 误报就绪"
     * @throws IllegalStateException {@code workDir} 为 null
     * @since 1.1.0
     */
    public SetupWizard(
        @NotNull Path workDir,
        @Nullable IAsrRuntimeControl asrControl,
        @Nullable IDatabaseGateway dbGateway,
        @Nullable IModelCatalog modelCatalog,
        @Nullable Function<String, IAsrRuntimeControl> asrControlForDir
    )
    {
        Objects.requireNonNull(workDir, "Param \"workDir\" must not be null!");
        this.workDir = workDir;
        this.asrControl = asrControl;
        this.dbGateway = dbGateway;
        this.modelCatalog = modelCatalog;
        this.asrControlForDir = asrControlForDir;
    }

    //endregion

    //region 主流程

    /**
     * 执行向导主流程: 模式 → 编辑 → 摘要 → 落盘 → 完成屏.
     *
     * @param items 全部向导条目元数据 (文件顺序); SIMPLE 模式下仅必填项进入编辑清单, 摘要仍覆盖全部
     * @param view 预填来源 (sysprop/env/工作目录显式配置)
     * @param io 终端交互通道, 全程读写经由它
     * @return 收场结果; EOF/Ctrl+C 在写入前发生时打印取消提示并返回空表 + CANCELLED (Entrance 据此以退出码 1 收场), 不落盘;
     *         写盘失败返回空表 + FAILED (评审轮次 2, 同为退出码 1); 写入后的 EOF 归为 EXIT (无可取消之物)
     * @throws IllegalStateException 任一参数为 null
     * @since 1.1.0
     */
    public @NotNull SetupResult run(
        @NotNull List<PropertyMetaParser.ConfigItemMeta> items,
        @NotNull ConfigView view,
        @NotNull TerminalIO io
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
        
        var editing = !visible.isEmpty();
        var expanded = editing ? 0 : null;  //* null = 折叠命令态; 非空 = 展开态下标 (顺序遍历从首项起).
        var next = 0;                       //* Enter 顺序遍历的光标.
        @Nullable String dbProbeKey = null;     //* DB 交互流重触发凭据: 最近一次探测的三项值指纹, null = 本会话尚未探测过.
        @Nullable String aiFetchKey = null;     //* AI 拉取步重触发凭据: 最近一次拉取的 endpoint+key 值指纹, null = 本会话尚未拉取过.
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
                            //* ASR 条目保存后触发运行时就绪检查: EOF 于询问符处沿用向导取消路径.
                            if(!offerAsrRuntimeIfNotReady(visible.get(expanded), values, io))
                                return cancelled(io);
                            
                            //* 数据库组三项齐备且值指纹变化时触发 DB 探测/修复流: EOF 于询问符处同走取消路径.
                            final var step = runDbFlowIfTriggered(visible.get(expanded), values, dbProbeKey, io);
                            
                            if(step.outcome() == DbFlowOutcome.CANCEL)
                                return cancelled(io);
                            
                            if(step.fingerprint() != null)
                                dbProbeKey = step.fingerprint();
                            
                            if(step.outcome() == DbFlowOutcome.REEDIT)
                                continue;  //* 就地失败: 不推进光标 — 循环回到顶部重绘清单并重新展开当前项 (向导既有编辑态语义).
                            
                            //* AI 组 endpoint+key 齐备且值指纹变化时触发模型拉取/选择步: EOF 于列表/输入符处同走取消路径.
                            final var aiStep = runAiFlowIfTriggered(visible.get(expanded), values, aiFetchKey, io);
                            
                            if(aiStep.outcome() == AiFlowOutcome.CANCEL)
                                return cancelled(io);
                            
                            if(aiStep.fingerprint() != null)
                                aiFetchKey = aiStep.fingerprint();
                            if(aiStep.outcome() == AiFlowOutcome.REEDIT)
                                continue;  //* 密钥被拒: 停在当前项重编辑, 改值保存后经指纹重触发.
                            
                            next = expanded + 1;
                            
                            if(next >= visible.size())
                            {
                                expanded = null;
                                editing = false;
                            }//* 末项已存 → 直达摘要.
                            else
                                expanded = next;//* 保存即顺序推进到下一项.
                        }
                        case DISCARD -> expanded = null;  //* 放弃 → 折叠命令态, 光标仍指向下一未编辑项.
                    }
                    continue;
                }
                final var cmd = readCommand(visible.size(), next, io);
                
                if(cmd == null)
                    return cancelled(io);
                
                if(cmd == CMD_FINISH)
                    editing = false;
                else
                {
                    expanded = cmd;
                    next = cmd + 1;
                }
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

    //* 返回 null = EOF 取消; 回车默认简单配置 (低门槛优先).
    /**
     * 阶段一: 询问向导模式; 回车默认简单配置 (低门槛优先), 非 1/2 输入原地红字重问.
     *
     * @param total 全部条目数 (全面配置选项文案用)
     * @param io 终端交互通道
     * @return 所选模式; EOF 取消时为 null
     */
    private static @Nullable Mode askMode(int total, @NotNull TerminalIO io)
    {
        io.writeOut(
            PrintUtils.quickFormat(
                """
                
                {}
                
                """,
                emph("== Soul Notes 配置向导 ==")
            )
        );
        io.writeOut("  1. 简单配置 (仅必填项)\n");
        io.writeOut(PrintUtils.quickFormat("  2. 全面配置 (全部 {} 项)\n", total));
        while(true)
        {
            io.writeOut("请选择 [1/2, 回车=1]: ");
            final var raw = io.readLine();
            if(raw == null)
                return null;
            final var t = raw.strip();
            if(t.isEmpty() || t.equals("1"))
                return Mode.SIMPLE;
            if(t.equals("2"))
                return Mode.FULL;
            io.writeOut(PrintUtils.quickFormat("{}\n", bad("✗ 无效选择, 请输入 1 或 2")));
        }
    }

    //endregion

    //region 阶段二: 清单渲染与编辑

    //* 清单整屏重绘 (滚动重印, 妥协记录见类 javadoc): 组头 + v2 状态行.
    /**
     * 清单整屏重绘 (滚动重印, 妥协记录见类 javadoc): 逐组输出组头与各条目状态行.
     *
     * @param visible 当前模式下可见的条目
     * @param values 会话显式值集 (决定行状态)
     * @param io 终端交互通道
     */
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
                io.writeOut(PrintUtils.quickFormat("{}\n", dim(PrintUtils.quickFormat("── {}", meta.group()))));
                prevGroup = meta.group();
            }
            io.writeOut(TerminalRenderer.listItemLine(stateOf(meta, values)) + "\n");
        }
    }

    //* 展开态结局: SAVE = 保留/保存并推进; DISCARD = esc 放弃折叠; CANCEL = EOF 取消.
    /** 展开态结局: SAVE = 保留/保存并推进; DISCARD = esc 放弃折叠; CANCEL = EOF 取消. */
    private enum Outcome
    {
        SAVE,
        DISCARD,
        CANCEL
    }

    /**
     * 展开块交互: 输入行 + 空行 + 灰斜体解释.
     * <p>空输入语义: 已有当前值 → 保留; GENERATE → 自动生成; 必填未配 → 红字重问 (不折叠);
     * 可选未配 → 保留默认且不入 values. 非法输入原地红字重问; {@code esc} 放弃折叠.</p>
     *
     * @param meta 当前展开条目的元数据
     * @param values 会话显式值集 (读写面: 保存写入, 回显读取)
     * @param prefilled 继承来源键集; 本条目被重输时移出, 恢复落盘资格
     * @param io 终端交互通道
     * @return 展开态结局; EOF 一律归 CANCEL
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
        var explainShown = false;  //* 解释只在首个输入行后渲染一次, 重问循环不重复刷屏.
        while(true)
        {
            io.writeOut(PrintUtils.quickFormat("  {} {}{}: ", emph("❯"), env, currentHint(meta, values)));
            final var raw = secretLike(meta) ? readSecretLine(io) : io.readLine();
            if(raw == null)
                return Outcome.CANCEL;
            if(!explainShown)
            {
                io.writeOut("\n");
                for(final var line : explain) io.writeOut(PrintUtils.quickFormat("  {}\n", dim(line)));
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
                    io.writeOut(PrintUtils.quickFormat("{}\n", bad(PrintUtils.quickFormat("  ✗ 必填项, 请输入值 (输入 {} 放弃)", ESC_TOKEN))));
                    continue;
                }
                return Outcome.SAVE;  //* 可选留空 = 保留默认, 不入 values (ConfigWriter 仅写显式值).
            }
            final var err = FieldValidator.validate(meta, t);
            if(err.isPresent())
            {
                io.writeOut(PrintUtils.quickFormat("{}\n", bad(PrintUtils.quickFormat("  ✗ {}", err.get()))));  //* 原地红字: 留在展开态重新提问.
                continue;
            }
            values.put(env, t);
            prefilled.remove(env);  //* 本会话重新输入: 覆盖继承值并恢复落盘资格 (不再视为 prefill 来源).
            return Outcome.SAVE;
        }
    }

    //* 折叠命令态: 返回 null = EOF 取消; CMD_FINISH = q 完成; >=0 = 待展开条目下标.
    /**
     * 折叠命令态: 序号跳转 / Enter 顺序遍历 (从 {@code next} 光标起) / q 完成; 无效输入红字重问.
     *
     * @param size 可见条目数 (序号范围)
     * @param next Enter 顺序遍历的光标
     * @param io 终端交互通道
     * @return 待展开条目下标 (0-based); {@code q} 为 CMD_FINISH 哨兵; EOF 取消为 null
     */
    private static @Nullable Integer readCommand(int size, int next, @NotNull TerminalIO io)
    {
        io.writeOut(PrintUtils.quickFormat("{}\n", dim("序号 跳转 / Enter 顺序遍历 / q 完成")));
        while(true)
        {
            io.writeOut(PrintUtils.quickFormat("{} ", emph("❯")));
            final var raw = io.readLine();
            if(raw == null)
                return null;
            final var t = raw.strip();
            if(t.isEmpty())
                return Math.min(next, size - 1);
            if(t.equalsIgnoreCase("q"))
                return CMD_FINISH;
            final var idx = parseIndex(t, size);
            if(idx != null)
                return idx;
            io.writeOut(
                PrintUtils.quickFormat(
                    "{}\n",
                    bad(
                        t.matches("[0-9]{1,9}") ?
                            PrintUtils.quickFormat("✗ 序号超出范围 (1-{})", size) :
                            PrintUtils.quickFormat("✗ 无效输入: 序号 (1-{}) / Enter 顺序遍历 / q 完成", size)
                    )
                )
            );
        }
    }

    //* 序号解析 (readCommand 与模型选择共用): 限长 9 位先挡超长数字串再 parse, 杜绝 parseInt 溢出异常; 合法返回 0-based 下标, 否则 null.
    /**
     * 序号解析 (readCommand 与模型选择共用): 限长 9 位先挡超长数字串, 杜绝 parseInt 溢出异常.
     *
     * @param t 用户输入文本
     * @param size 合法序号上界 (1-based)
     * @return 0-based 下标; 非数字或越界为 null
     */
    private static @Nullable Integer parseIndex(@NotNull String t, int size)
    {
        if(!t.matches("[0-9]{1,9}"))
            return null;
        final var idx = Integer.parseInt(t) - 1;
        return idx >= 0 && idx < size ? idx : null;
    }

    //endregion

    //region ASR 运行时交互

    /**
     * ASR 条目保存后的就绪检查与就地下载询问.
     * <p>就绪静默跳过 — 反复提示会淹没清单主流程; 就绪判定为纯文件检查, 即刻返回.
     * 检查/下载/回执一律以 {@link #effectiveAsrControl} 的 "当前目录实例" 为准.</p>
     *
     * @param meta 刚保存的条目元数据, 据其 envName 判定是否为触发键
     * @param values 会话显式值集, 提供运行时目录的当前取值
     * @param io 终端交互通道
     * @return false = 询问符处 EOF (沿用向导取消路径), 调用方立即收场; true = 静默跳过或询问流已走完
     */
    private boolean offerAsrRuntimeIfNotReady(
        @NotNull PropertyMetaParser.ConfigItemMeta meta,
        @NotNull Map<String, String> values,
        @NotNull TerminalIO io
    )
    {
        final @Nullable IAsrRuntimeControl control = effectiveAsrControl(values);
        if(control == null || !ASR_TRIGGER_ENVS.contains(meta.envName()) || control.ready())
            return true;
        io.writeOut(PrintUtils.quickFormat("{}\n", dim("ASR 运行时未就绪 (缺少本地模型或动态库, 语音转写暂不可用)")));
        while(true)
        {
            io.writeOut("运行时未就绪, 是否立即下载? [Y/n] ");
            final var raw = io.readLine();
            if(raw == null) return false;
            final var t = raw.strip();
            if(t.isEmpty() || t.equalsIgnoreCase("y"))
            {
                downloadRuntime(control, io);
                return true;
            }
            if(t.equalsIgnoreCase("n"))
            {
                io.writeOut(PrintUtils.quickFormat("{}\n", dim("已跳过, 可稍后手动放置或重试")));
                return true;
            }
            io.writeOut(PrintUtils.quickFormat("{}\n", bad("✗ 无效输入, 请输入 Y 或 n")));
        }
    }

    //* ASR 交互的控制实例解析 (重建点): 会话内改过运行时目录时以目录重建, 否则用预装配端口 —
    //* 保证下载落会话当前目录而非向导启动前的旧目录, 就绪判定与回执同源 (不说谎).
    /**
     * ASR 交互的控制实例解析 (重建点): 会话内改过运行时目录且具备重建能力时以目录重建,
     * 否则用预装配端口 — 保证下载落会话当前目录, 就绪判定与回执同源 (不说谎).
     *
     * @param values 会话显式值集, 提供运行时目录当前取值
     * @return 当前目录对应的控制实例; 端口未注入时为 null (交互整体禁用)
     */
    private @Nullable IAsrRuntimeControl effectiveAsrControl(@NotNull Map<String, String> values)
    {
        final var sessionDir = values.get(ASR_RUNTIME_DIR_ENV);
        if(sessionDir != null && asrControlForDir != null)
            return asrControlForDir.apply(sessionDir);
        return asrControl;
    }

    //* 阻塞等待下载 (Pre-Launch 无事件循环, await 是唯一消费方式); 失败不中断向导, 就地给灰色提示后继续主流程.
    //* 回执以本实例 ready() 判定而非下载动作的完成信号: 下载成功 ≠ 运行时就绪, 两者分叉时必须如实告知.
    /**
     * 阻塞执行运行时下载并回显进度; 下载后以 {@code ready()} 判定回执 (下载成功 ≠ 运行时就绪).
     * <p>失败 (含 "已有下载在进行" 的并发拒绝) 不中断向导, 就地给灰色提示后继续主流程.</p>
     *
     * @param control ASR 运行时控制端口 (就绪判定与下载同一实例)
     * @param io 终端交互通道
     */
    private static void downloadRuntime(@NotNull IAsrRuntimeControl control, @NotNull TerminalIO io)
    {
        final var printed = new AtomicBoolean(false);  //* 首帧直接输出, 后续帧先抹上一行再重写.
        try
        {
            control.ensureDownloaded((received, total) -> renderProgress(io, printed, received, total)).
                await().indefinitely();
            if(control.ready())
                io.writeOut(PrintUtils.quickFormat("{}\n", ok("✔ ASR 运行时就绪")));
            else
                io.writeOut(PrintUtils.quickFormat("{}\n", dim("下载已完成, 但运行时仍未就绪, 可稍后手动放置或重试")));
        }
        catch(Exception e)
        {
            //* Mutiny await 对受检异常会包一层, 统一解到根因再做类型分支.
            final var cause = rootCause(e);
            if(cause instanceof IllegalStateException concurrent && isAlreadyDownloading(concurrent))
                io.writeOut(PrintUtils.quickFormat("{}\n", dim("已有下载在进行, 请等待其完成")));
            else
                io.writeOut(PrintUtils.quickFormat("{}\n", dim(PrintUtils.quickFormat("下载失败 ({}), 可稍后手动放置或重试", cause.getMessage()))));
        }
    }

    //* AsrRuntimeManager 并发互斥的拒绝性失败 (第二路直接失败不排队), 以消息片段识别而非裸类型 —
    //* IllegalStateException 亦被 "布局异常" 路径复用, 两者提示语义不同.
    /**
     * 识别 "已有下载在进行" 的并发拒绝: 以消息片段而非裸类型判定 —
     * {@code IllegalStateException} 亦被其他路径复用, 提示语义不同.
     *
     * @param e 待判定异常
     * @return 消息含 "下载任务进行中" 片段时为 true
     */
    private static boolean isAlreadyDownloading(@NotNull IllegalStateException e)
        { return e.getMessage() != null && e.getMessage().contains("下载任务进行中"); }

    //* 进度行内回显 (TerminalRenderer 既有 eraseAbove 约定的唯一例外场景, 见类 javadoc): 单行自刷新.
    //* total 未知 (Content-Length 缺失, -1) 时无百分比可算, 退化为已接收 MB 数.
    /**
     * 进度行内回显 (eraseAbove 唯一例外场景, 见类 javadoc): 首帧直接输出, 后续帧抹上一行重写.
     *
     * @param io 终端交互通道
     * @param printed 是否已输出过首帧 (跨回调状态)
     * @param received 已接收字节数
     * @param total 总字节数; 未知 (Content-Length 缺失) 为 -1, 此时退化为已接收 MB 数
     */
    private static void renderProgress(@NotNull TerminalIO io, @NotNull AtomicBoolean printed, int received, int total)
    {
        final var line = total > 0 ?
                         PrintUtils.quickFormat("  下载中 {}%", (int) Math.min(received * 100L / total, 100)) :
                         PrintUtils.quickFormat("  已接收 {}MB", received / (1024 * 1024));
        io.writeOut(PrintUtils.quickFormat("{}{}\n", printed.get() ? TerminalRenderer.eraseAbove(1) : "", line));
        printed.set(true);
    }

    /** 沿原因链解到根因 (自环防御); Mutiny await 会把受检异常包一层, 类型分支前先解包. */
    private static @NotNull Throwable rootCause(@NotNull Throwable throwable)
    {
        var current = throwable;
        while(current.getCause() != null && current.getCause() != current)
            current = current.getCause();
        return current;
    }

    //endregion

    //region 数据库交互流

    //* DB 流结局: CONTINUE = 推进清单; REEDIT = 就地失败, 停在当前项重编辑; CANCEL = 询问符处 EOF, 沿用向导取消路径.
    /** DB 流结局: CONTINUE = 推进清单; REEDIT = 就地失败, 停在当前项重编辑; CANCEL = 询问符处 EOF, 沿用向导取消路径. */
    private enum DbFlowOutcome
    {
        CONTINUE,
        REEDIT,
        CANCEL
    }

    //* DB 流单步产物: fingerprint = 本次实际探测的三项值指纹 (null = 未触发, 调用方不得覆盖已存指纹);
    //* 重触发判定取 "三项值指纹" 而非 "每会话一次" — 两者实现复杂度相当, 指纹版免去改值后必须重启向导重跑的可用性坑,
    //* 且天然覆盖同值重存静默跳过 (与 ASR 就绪检查的静默语义对齐).
    /**
     * DB 流单步产物.
     *
     * @param fingerprint 本次实际探测的三项值指纹 (null = 未触发, 调用方不得覆盖已存指纹)
     * @param outcome 流结局
     */
    private record DbFlowStep(@Nullable String fingerprint, @NotNull DbFlowOutcome outcome)
        { static final @NotNull DbFlowStep SKIPPED = new DbFlowStep(null, DbFlowOutcome.CONTINUE);}

    /**
     * 数据库组条目保存后的探测/修复流入口.
     * <p>触发条件: 条目属数据库组, 三项 (URL/用户名/密码) 均已有值, 且三项值指纹不同于上次探测.
     * 未触发一律静默放行 — 反复探测只会淹没清单主流程 (与 ASR 就绪检查同语义).</p>
     *
     * @param meta 刚保存的条目元数据, 据其组名判定是否触发
     * @param values 会话显式值集, 提供三项的当前取值
     * @param lastFingerprint 上次探测的三项值指纹, null = 本会话尚未探测过
     * @param io 终端交互通道
     * @return 本次流产物: fingerprint 为本次实际探测的值指纹 (未触发时为 null, 调用方不得覆盖已存指纹)
     */
    private @NotNull DbFlowStep runDbFlowIfTriggered(
        @NotNull PropertyMetaParser.ConfigItemMeta meta,
        @NotNull Map<String, String> values,
        @Nullable String lastFingerprint,
        @NotNull TerminalIO io
    )
    {
        final @Nullable IDatabaseGateway gateway = dbGateway;
        if(gateway == null || !DB_GROUP.equals(meta.group()))
            return DbFlowStep.SKIPPED;
        final var url = values.get(DB_URL_ENV);
        final var user = values.get(DB_USER_ENV);
        final var password = values.get(DB_PASSWORD_ENV);
        if(url == null || user == null || password == null)
            return DbFlowStep.SKIPPED;  //* 三项未齐 (组内前序项尚未保存): 不探测.
        final var fingerprint = url + "\n" + user + "\n" + password;
        if(fingerprint.equals(lastFingerprint))
            return DbFlowStep.SKIPPED;  //* 同值重存: 静默跳过, 不重复探测.
        final DbTarget target;
        try { target = DbTarget.parse(url, user, password); }
        catch(IllegalStateException e)
        {
            //! URL 级校验 (scheme/host/端口) 已由 FieldValidator 在展开态拦截, 此处仅防御 userinfo 等深解析失败.
            io.writeOut(PrintUtils.quickFormat("{}\n", bad(PrintUtils.quickFormat("✗ 数据库地址无法解析: {}", e.getMessage()))));
            return new DbFlowStep(fingerprint, DbFlowOutcome.REEDIT);
        }
        return new DbFlowStep(fingerprint, runDbFlow(gateway, target, io));
    }

    //* 五态分流主流程: probe → OK 直通 / SCHEMA_MISSING 询问建表 / 其余即时 ✗; DB_MISSING 先自动建库再重探.
    /**
     * 五态分流主流程: probe → OK 直通 / SCHEMA_MISSING 询问建表 / 其余即时 ✗; DB_MISSING 先自动建库再重探.
     *
     * @param gateway 探测/修复网关
     * @param target 解析后的连接目标
     * @param io 终端交互通道
     * @return 流结局 (CONTINUE/REEDIT; EOF 取消在子流程内转 CANCEL)
     */
    private static @NotNull DbFlowOutcome runDbFlow(@NotNull IDatabaseGateway gateway, @NotNull DbTarget target, @NotNull TerminalIO io)
    {
        var probe = gateway.probe(target);
        if(probe.state() == ProbeResult.State.DB_MISSING)
        {
            io.writeOut(PrintUtils.quickFormat("{}\n", dim(PrintUtils.quickFormat("目标数据库 {} 不存在, 尝试自动创建...", target.database()))));
            try { gateway.createDatabase(target); }
            catch(IllegalStateException e)
            {
                io.writeOut(PrintUtils.quickFormat("{}\n", bad(PrintUtils.quickFormat("✗ 自动建库失败: {}", rootCause(e).getMessage()))));
                return DbFlowOutcome.REEDIT;  //* 无权限/竞争等: 保持在 DB 项展开态, 用户改值后经指纹重触发.
            }
            probe = gateway.probe(target);  //* 建库成功必须重探确认.
        }
        return switch(probe.state())
        {
            case OK ->
            {
                io.writeOut(PrintUtils.quickFormat("{}\n", ok("✔ 数据库连接就绪")));
                yield DbFlowOutcome.CONTINUE;
            }
            case SCHEMA_MISSING -> confirmSchema(gateway, target, probe.missingTables(), io);
            case UNREACHABLE ->
            {
                io.writeOut(PrintUtils.quickFormat("{}\n", bad("✗ 数据库不可达 (连接被拒绝或超时), 请确认 PostgreSQL 实例已运行且 host:port 正确")));
                yield DbFlowOutcome.REEDIT;
            }
            case AUTH_FAILED ->
            {
                io.writeOut(PrintUtils.quickFormat("{}\n", bad("✗ 数据库账号或密码被拒绝, 请重新输入用户名/密码")));
                yield DbFlowOutcome.REEDIT;
            }
            case DB_MISSING ->
            {
                //! 建库已报告成功但重探仍缺库: 只可能是并发竞争或服务端异常, 原地报错让用户重试.
                io.writeOut(PrintUtils.quickFormat("{}\n", bad("✗ 数据库创建后仍不存在 (可能并发竞争), 请重试")));
                yield DbFlowOutcome.REEDIT;
            }
        };
    }

    //* SCHEMA_MISSING 分支: 缺表清单 + [Y/n] 询问; y → 逐脚本建表 (进度一行一条) → 重探确认; n → 灰字提示后果并继续向导
    //* (拒绝不阻断: LAUNCH 前的 DbValidationTask 会以 BLOCK 拒绝启动, 后果链路完整).
    /**
     * SCHEMA_MISSING 分支: 缺表清单 + [Y/n] 询问; 同意则逐脚本建表 (进度一行一条) 后重探确认.
     * <p>拒绝不阻断向导 — LAUNCH 前的 DbValidationTask 会以 BLOCK 拒绝启动, 后果链路完整.</p>
     *
     * @param gateway 探测/修复网关
     * @param target 解析后的连接目标
     * @param missingTables probe 得出的缺失表清单
     * @param io 终端交互通道
     * @return 流结局; 询问符处 EOF 为 CANCEL
     */
    private static @NotNull DbFlowOutcome confirmSchema(
        @NotNull IDatabaseGateway gateway, @NotNull DbTarget target,
        @NotNull List<String> missingTables, @NotNull TerminalIO io
    )
    {
        io.writeOut(PrintUtils.quickFormat("{}\n", dim(PrintUtils.quickFormat("缺少数据表: {}", String.join(", ", missingTables)))));
        while(true)
        {
            io.writeOut("初始化数据库结构? [Y/n] ");
            final var raw = io.readLine();
            if(raw == null) return DbFlowOutcome.CANCEL;  //* EOF 沿用向导取消路径.
            final var t = raw.strip();
            if(t.isEmpty() || t.equalsIgnoreCase("y")) break;
            if(t.equalsIgnoreCase("n"))
            {
                io.writeOut(PrintUtils.quickFormat("{}\n", dim("已跳过: 启动校验将拒绝启动 (可稍后 --setup 重跑)")));
                return DbFlowOutcome.CONTINUE;
            }
            io.writeOut(PrintUtils.quickFormat("{}\n", bad("✗ 无效输入, 请输入 Y 或 n")));
        }
        try { gateway.applySchema(target, script -> io.writeOut(PrintUtils.quickFormat("{}\n", dim(PrintUtils.quickFormat("  执行 schema 脚本: {}", script))))); }
        catch(IllegalStateException e)
        {
            io.writeOut(PrintUtils.quickFormat("{}\n", bad(PrintUtils.quickFormat("✗ 数据库结构初始化失败: {}", rootCause(e).getMessage()))));
            return DbFlowOutcome.REEDIT;
        }
        if(gateway.probe(target).state() != ProbeResult.State.OK)
        {
            //! 脚本执行完毕但重探仍未全绿: 脚本与期望表集漂移 (构建期缺陷), 原地报错交由用户查看服务端日志.
            io.writeOut(PrintUtils.quickFormat("{}\n", bad("✗ 初始化脚本执行完毕但校验仍未通过, 请检查数据库日志")));
            return DbFlowOutcome.REEDIT;
        }
        io.writeOut(PrintUtils.quickFormat("{}\n", ok("✔ 数据库连接就绪")));
        return DbFlowOutcome.CONTINUE;
    }

    //endregion

    //region AI 模型拉取步

    //* AI 流结局: CONTINUE = 推进清单; REEDIT = 401/403 密钥被拒, 停在当前项重编辑; CANCEL = 选择/输入符处 EOF, 沿用向导取消路径.
    /** AI 流结局: CONTINUE = 推进清单; REEDIT = 401/403 密钥被拒, 停在当前项重编辑; CANCEL = 选择/输入符处 EOF, 沿用向导取消路径. */
    private enum AiFlowOutcome
    {
        CONTINUE,
        REEDIT,
        CANCEL
    }

    //* AI 流单步产物: fingerprint = 本次触发判定的值指纹 (null = 未触发/已取消, 调用方不得覆盖已存指纹).
    //* 成功路径以 "规范化后 endpoint + key" 入指纹而非触发时原值: 规范化会改写 values 中的 endpoint,
    //* 若以原值入指纹, 重存 key 会被误判为值变化而重复拉取; 失败路径原值入指纹, 同值重存静默跳过 (与 DB 流同语义).
    /**
     * AI 流单步产物.
     *
     * @param fingerprint 本次触发判定的值指纹 (null = 未触发/已取消, 调用方不得覆盖已存指纹);
     *                    成功路径以规范化后 endpoint + key 入指纹, 防重存 key 被误判为值变化而重复拉取
     * @param outcome 流结局
     */
    private record AiFlowStep(@Nullable String fingerprint, @NotNull AiFlowOutcome outcome)
        { static final @NotNull AiFlowStep SKIPPED = new AiFlowStep(null, AiFlowOutcome.CONTINUE);}

    /**
     * AI 组条目保存后的模型拉取/选择流入口.
     * <p>触发条件: 条目属 AI 接入组, endpoint 与 api-key 均已有值, 且二者值指纹不同于上次拉取.
     * properties 中 model 项位于 api-key 之前, 触发点在 key 保存时 — 选择结果直接覆写 values 中已填的
     * model 值 (清单行随重绘显示为已配置), 用户无需再手动输入模型. 未触发一律静默放行 (与 DB 流同语义).</p>
     *
     * @param meta 刚保存的条目元数据, 据其组名判定是否触发
     * @param values 会话显式值集, 提供触发键与选择结果的读写面
     * @param lastFingerprint 上次拉取的值指纹, null = 本会话尚未拉取过
     * @param io 终端交互通道
     * @return 本次流产物: fingerprint 为本次触发判定的值指纹 (未触发/已取消时为 null, 调用方不得覆盖已存指纹)
     */
    private @NotNull AiFlowStep runAiFlowIfTriggered(
        @NotNull PropertyMetaParser.ConfigItemMeta meta,
        @NotNull Map<String, String> values,
        @Nullable String lastFingerprint,
        @NotNull TerminalIO io
    )
    {
        final @Nullable IModelCatalog catalog = modelCatalog;
        if(catalog == null || !AI_GROUP.equals(meta.group()))
            return AiFlowStep.SKIPPED;
        final var endpoint = values.get(AI_ENDPOINT_ENV);
        final var apiKey = values.get(AI_API_KEY_ENV);
        if(endpoint == null || apiKey == null)
            return AiFlowStep.SKIPPED;  //* 两项未齐 (组内前序项尚未保存): 不拉取.
        final var fingerprint = endpoint + "\n" + apiKey;
        if(fingerprint.equals(lastFingerprint))
            return AiFlowStep.SKIPPED;  //* 同值重存: 静默跳过, 不重复探测.
        //* 回显最终请求 URL: 与 fetch 共用同一启发式 (单一来源), 用户可预判探测落点.
        io.writeOut(PrintUtils.quickFormat("{}\n", dim(PrintUtils.quickFormat("探测模型列表: {} ...", IModelCatalog.modelsUrl(endpoint)))));
        final IModelCatalog.CatalogResult result;
        try { result = catalog.fetch(endpoint, apiKey, AI_FETCH_TIMEOUT); }
        catch(IModelCatalog.UnauthorizedException e)
        {
            io.writeOut(PrintUtils.quickFormat("{}\n", bad("✗ 密钥无效 (服务端拒绝鉴权), 请重新输入接口地址与 API 密钥")));
            return new AiFlowStep(fingerprint, AiFlowOutcome.REEDIT);
        }
        catch(IOException e)
        {
            io.writeOut(PrintUtils.quickFormat("{}\n", emph(PrintUtils.quickFormat("⚠ 无法获取模型列表 ({}), 请手动输入模型名称", rootCause(e).getMessage()))));
            return new AiFlowStep(fingerprint, manualModelInput(values, io));
        }
        if(result.models().isEmpty())
        {
            io.writeOut(PrintUtils.quickFormat("{}\n", emph("⚠ 服务端返回的模型列表为空, 请手动输入模型名称")));
            return new AiFlowStep(fingerprint, manualModelInput(values, io));
        }
        renderModelList(result.models(), io);
        final var picked = readModelIndex(result.models().size(), io);
        if(picked < 0)
            return AiFlowStep.SKIPPED;  //* 选择符处 EOF: 向导随即取消, 指纹不再有意义.
        final var chosen = result.models().get(picked);
        values.put(AI_MODEL_ENV, chosen.id());
        //* fetch 与存储分离: endpoint 存探测成功的规范形 (langchain4j base-url 形态),
        //* 避免 "拉取成功但 chat 调用 404" 的路径不一致; 摘要屏自然呈现规范化后的值.
        values.put(AI_ENDPOINT_ENV, result.normalizedEndpoint());
        io.writeOut(
            PrintUtils.quickFormat(
                "{}\n",
                ok(
                    PrintUtils.quickFormat(
                        "✔ 模型已选定: {} (接口地址已规范化为 {})",
                        chosen.id(),
                        result.normalizedEndpoint()
                    )
                )
            )
        );
        return new AiFlowStep(result.normalizedEndpoint() + "\n" + apiKey, AiFlowOutcome.CONTINUE);
    }

    //* 元数据列表行: "序号. 模型ID [上下文: n|-] [思考: ✓|✗|-]"; 保持整行无着色, 行内容本身即断言面.
    /**
     * 渲染模型列表: "序号. 模型ID [上下文: n|-] [思考: ✓|✗|-]"; 整行无着色, 行内容本身即断言面.
     *
     * @param models 模型元数据列表
     * @param io 终端交互通道
     */
    private static void renderModelList(@NotNull List<IModelCatalog.ModelInfo> models, @NotNull TerminalIO io)
    {
        io.writeOut(PrintUtils.quickFormat("{}\n", dim(PrintUtils.quickFormat("可用模型 ({} 个):", models.size()))));
        for(var i = 0; i < models.size(); i++)
        {
            final var m = models.get(i);
            io.writeOut(PrintUtils.quickFormat("  {}. {} [上下文: {}] [思考: {}]\n", i + 1, m.id(), m.context(), m.reasoning()));
        }
    }

    //* 返回选中下标 (0-based); -1 = EOF 取消; 越界/非数字原地红字重问 (序号解析与折叠命令态共用 [[SetupWizard#parseIndex]]).
    /**
     * 询问模型序号; 越界/非数字原地红字重问 (序号解析与折叠命令态共用 [[SetupWizard#parseIndex]]).
     *
     * @param size 模型数 (序号上界, 1-based)
     * @param io 终端交互通道
     * @return 选中下标 (0-based); EOF 取消为 -1
     */
    private static int readModelIndex(int size, @NotNull TerminalIO io)
    {
        while(true)
        {
            io.writeOut(PrintUtils.quickFormat("请选择模型序号 [1-{}]: ", size));
            final var raw = io.readLine();
            if(raw == null)
                return -1;
            final var t = raw.strip();
            final var idx = parseIndex(t, size);
            if(idx != null)
                return idx;
            io.writeOut(
                PrintUtils.quickFormat(
                    "{}\n",
                    bad(t.matches("[0-9]{1,9}") ?
                        PrintUtils.quickFormat("✗ 序号超出范围 (1-{})", size) :
                        PrintUtils.quickFormat("✗ 无效输入, 请输入 1-{} 的序号", size)
                    )
                )
            );
        }
    }

    //* 网络失败/空列表的手动兜底: 仅写 model, endpoint 保持用户原样 (未经探测证实, 不做规范化); EOF 沿取消路径.
    /**
     * 网络失败/空列表的手动兜底: 仅写 model 值, endpoint 保持用户原样 (未经探测证实, 不做规范化).
     *
     * @param values 会话显式值集 (选择结果写入 AI_MODEL_ENV)
     * @param io 终端交互通道
     * @return 流结局; 输入符处 EOF 为 CANCEL
     */
    private static @NotNull AiFlowOutcome manualModelInput(@NotNull Map<String, String> values, @NotNull TerminalIO io)
    {
        while(true)
        {
            io.writeOut("请输入模型名称: ");
            final var raw = io.readLine();
            if(raw == null)
                return AiFlowOutcome.CANCEL;
            final var t = raw.strip();
            if(t.isEmpty())
            {
                io.writeOut(PrintUtils.quickFormat("{}\n", bad("✗ 模型名称不能为空")));
                continue;
            }
            values.put(AI_MODEL_ENV, t);
            return AiFlowOutcome.CONTINUE;
        }
    }

    //endregion

    //region 阶段三: 摘要与落盘

    /**
     * 渲染配置摘要: 分组列出全部显式值 (密钥全掩码), 继承的密钥项显式预告 "不写入文件";
     * 无显式项时提示全部使用内置默认.
     *
     * @param items 全部条目元数据 (摘要始终覆盖全部, 与编辑清单的可见集无关)
     * @param values 会话显式值集
     * @param prefilled 继承来源键集 (密钥类预告用)
     * @param io 终端交互通道
     */
    private static void renderSummary(
        @NotNull List<PropertyMetaParser.ConfigItemMeta> items,
        @NotNull Map<String, String> values,
        @NotNull Set<String> prefilled,
        @NotNull TerminalIO io
    )
    {
        io.writeOut(PrintUtils.quickFormat("\n{}\n", emph("== 配置摘要 ==")));
        String prevGroup = null;
        for(final var meta: items)
        {
            final var v = values.get(meta.envName());
            if(v == null)
                continue;
            if(prevGroup == null || !prevGroup.equals(meta.group()))
            {
                io.writeOut(PrintUtils.quickFormat("{}\n", dim(PrintUtils.quickFormat("── {}", meta.group()))));
                prevGroup = meta.group();
            }
            io.writeOut(PrintUtils.quickFormat("  {} = {}\n", meta.key(), displayValue(meta, v)));
            if(prefilled.contains(meta.envName()) && secretLike(meta))
                io.writeOut(PrintUtils.quickFormat("{}\n", dim("  (继承自环境, 不写入文件)")));  //* 掩码之下用户无从分辨落盘与否, 须显式预告该项不会进入输出文件.
        }
        if(values.isEmpty()) io.writeOut(PrintUtils.quickFormat("{}\n", dim("  (无显式设置项, 全部使用内置默认)")));
        io.writeOut(PrintUtils.quickFormat("{}\n", dim("未列出的项保持内置默认; 已存在的输出文件会先备份为 *.bak")));
    }

    //* 返回 null = EOF 取消; TRUE = 确认写入; FALSE = 否决回编辑态.
    /**
     * 询问写入确认; 无效输入红字重问.
     *
     * @param io 终端交互通道
     * @return TRUE = 确认写入; FALSE = 否决回编辑态; EOF 取消为 null
     */
    private static @Nullable Boolean readConfirm(@NotNull TerminalIO io)
    {
        while(true)
        {
            io.writeOut("确认写入? [Y/n] ");
            final var raw = io.readLine();
            if(raw == null)
                return null;
            final var t = raw.strip();
            if(t.isEmpty() || t.equalsIgnoreCase("y"))
                return Boolean.TRUE;
            if(t.equalsIgnoreCase("n"))
                return Boolean.FALSE;
            io.writeOut(PrintUtils.quickFormat("{}\n", bad("✗ 无效输入, 请输入 Y 或 n")));
        }
    }

    /**
     * 落盘 (经 {@link ConfigWriter}, 原文件自动备份为 {@code .bak}) 后进入完成屏, 询问启动或退出.
     * <p>落盘失败时向导以异常收场语义结束: stderr 报错并返回空表 + FAILED, 不存在静默半写状态.</p>
     *
     * @param items 全部条目元数据
     * @param values 会话显式值集
     * @param prefilled 继承来源键集 (密钥类落盘排除)
     * @param io 终端交互通道
     * @return LAUNCH/EXIT 携带非空 values; 完成屏处 EOF 归为 EXIT; 落盘失败为空表 + FAILED
     */
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
            //! SLF4J 会把尾参 Throwable 抽走不参与 {} 填充 (getThrowableCandidate), 传 e 本身会退化成字面 "{}" 丢失全部诊断,
            //! 必须先 toString() 才与旧 "+ e" 拼接逐字符等价.
            io.writeErr(PrintUtils.quickFormat("{}\n", bad(PrintUtils.quickFormat("✗ 配置写入失败: {}", e.toString()))));
            //! 评审轮次 2: 失败归 EXIT 会使进程退出码 0, 与取消的退出码 1 倒挂 → 改 FAILED (Entrance 映射 System.exit(1)); 返回空表: 值未持久化, 维持 "非空 values ⟺ 已落盘" 不变量.
            return new SetupResult(Map.of(), NextAction.FAILED);
        }
        //* 完成屏: 双文件 ✔ (含 .bak 备注) + 启动/退出选择.
        io.writeOut(PrintUtils.quickFormat("\n{} 配置已写入: {}{}\n", ok("✔"), displayPath(propsPath), bakNote(hadProps, "application.properties.bak")));
        io.writeOut(PrintUtils.quickFormat("{} 配置已写入: {}{}\n\n", ok("✔"), displayPath(envPath), bakNote(hadEnv, ".env.bak")));
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
            io.writeOut(PrintUtils.quickFormat("{}\n", bad("✗ 无效选择, 请输入 1 或 2")));
        }
    }

    //endregion

    //region 内部工具

    //* 预填显式配置 (sysprop/env/工作目录文件), 已配置项在清单中呈 CONFIGURED/OPTIONAL_SET, 展开态回车即保留;
    //* envNames 并行记录继承来源, 供摘要预告与 finish 排除密钥类条目落盘.
    /**
     * 预填显式配置 (sysprop/env/工作目录文件), 已配置项在清单中呈已配置态, 展开态回车即保留.
     *
     * @param items 全部条目元数据
     * @param view 配置视图
     * @return 预填值与继承来源键集; 无显式配置时两集合均为空
     */
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
            view.explicit(meta.key(), meta.envName()).ifPresent(v ->
            {
                values.put(meta.envName(), v);
                envNames.add(meta.envName());
            });
        }
        return new Prefill(values, envNames);
    }

    //* 落盘值 = values - (prefill 来源 ∩ 密钥类): 继承密钥在进程环境中仍生效, 物化到文件违反
    //* "prod 密钥必须环境变量注入" 指引且摘要屏全掩码, 用户无从察觉; 其余显式值照常落盘.
    /**
     * 计算落盘值 = values - (prefill 来源 ∩ 密钥类): 继承密钥在进程环境中仍生效, 不物化到文件
     * (prod 密钥必须环境变量注入的指引 + 掩码之下用户无从察觉明文被写入); 其余显式值照常落盘.
     *
     * @param items 全部条目元数据 (提供 inputType 与过滤序)
     * @param values 会话显式值集
     * @param prefilled 继承来源键集
     * @return 允许落盘的显式值 (保持 values 的插入序)
     */
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
    /**
     * 取消收场: stderr 提示未写入任何文件, 累计值一并丢弃 (空表), 维持 "非空 values ⟺ 已落盘" 不变量.
     *
     * @param io 终端交互通道
     * @return 空表 + CANCELLED (与 EXIT 的差异在退出码)
     */
    private static @NotNull SetupResult cancelled(@NotNull TerminalIO io)
    {
        io.writeErr("已取消, 未写入任何文件\n");
        return new SetupResult(Map.of(), NextAction.CANCELLED);
    }

    /** 密文行读取: {@link TerminalIO#readSecret} 的 String 适配; EOF 透传 null. */
    private static @Nullable String readSecretLine(@NotNull TerminalIO io)
    {
        final var chars = io.readSecret();
        return chars == null ? null : new String(chars);
    }

    //* GENERATE 空输入: 48 字节 SecureRandom → base64 (64 字符, 天然满足 minLength 32 的规则矩阵下限).
    /** GENERATE 空输入的自动生成: 48 字节 SecureRandom → base64 (64 字符, 天然满足 minLength 32 下限). */
    private static @NotNull String generateSecret()
    {
        //* 每次调用局部创建: static final 持有 SecureRandom 会被 native 构建期类初始化固化种子进
        //! image heap 抛 UnsupportedFeatureException (CI 实测; 与 PsychologyTipsRetriever 的 Random
        //! 实例字段同坑); 向导一次性调用, 重建开销可忽略.
        final var random = new SecureRandom();
        final var bytes = new byte[GENERATE_RANDOM_BYTES];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 判定条目是否密钥类 (SECRET/GENERATE): 决定输入不回显与摘要/回显掩码. */
    private static boolean secretLike(@NotNull PropertyMetaParser.ConfigItemMeta meta)
    {
        return meta.inputType() == PropertyMetaParser.InputType.SECRET ||
               meta.inputType() == PropertyMetaParser.InputType.GENERATE;
    }

    //* 密钥类字段的任何回显 (行尾/提示/摘要) 一律掩码, 明文仅存在于 values 与落盘文件.
    /** 密钥类字段一律掩码 (明文仅存在于 values 与落盘文件), 其余原样返回. */
    private static @NotNull String displayValue(@NotNull PropertyMetaParser.ConfigItemMeta meta, @NotNull String v) { return secretLike(meta) ? MASK : v; }

    /**
     * 展开提示符的当前值后缀: 已有值显 "当前" (密钥掩码), 否则显条目默认值 (空默认不显).
     *
     * @param meta 条目元数据
     * @param values 会话显式值集
     * @return 后缀文本; 无可显内容为空串
     */
    private static @NotNull String currentHint(
        @NotNull PropertyMetaParser.ConfigItemMeta meta,
        @NotNull Map<String, String> values
    )
    {
        final var current = values.get(meta.envName());
        if(current != null)
            return PrintUtils.quickFormat(" [当前: {}]", displayValue(meta, current));
        return meta.defaultValue().isEmpty() ? "" : PrintUtils.quickFormat(" [默认: {}]", displayValue(meta, meta.defaultValue()));
    }

    /**
     * 由当前值推导清单行状态: 已有值时先过格式校验 (预填值可能不合法, 整行红 + 回显原因),
     * 合法则呈 CONFIGURED/OPTIONAL_SET; 无值时呈 REQUIRED_EMPTY/OPTIONAL_DEFAULT.
     *
     * @param meta 条目元数据
     * @param values 会话显式值集
     * @return 清单行参数对象
     */
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
    /** 着色速记 (灰斜体): 收拢 TerminalRenderer 全限定引用, 保持渲染行可读. */
    private static @NotNull String dim(@NotNull String s) { return TerminalRenderer.paint(TerminalRenderer.Style.DIM, s); }

    /** 着色速记 (黄粗斜体强调), 语义同 {@link #dim}. */
    private static @NotNull String emph(@NotNull String s) { return TerminalRenderer.paint(TerminalRenderer.Style.EMPHASIS, s); }

    /** 着色速记 (绿 = 成功), 语义同 {@link #dim}. */
    private static @NotNull String ok(@NotNull String s) { return TerminalRenderer.paint(TerminalRenderer.Style.OK, s); }

    /** 着色速记 (红 = 校验失败/错误), 语义同 {@link #dim}. */
    private static @NotNull String bad(@NotNull String s) { return TerminalRenderer.paint(TerminalRenderer.Style.BAD, s); }

    //* 展示统一正斜杠: 跨平台一致且与文档/compose 路径写法对齐.
    /** 路径展示统一正斜杠: 跨平台一致且与文档/compose 路径写法对齐. */
    private static @NotNull String displayPath(@NotNull Path p) { return p.toString().replace('\\', '/'); }

    /** 已存在文件的落盘回执备注 (原文件已备份为 *.bak); 无备份为空串. */
    private static @NotNull String bakNote(boolean had, @NotNull String bakName)
        { return had ? PrintUtils.quickFormat(" (原文件已备份为 {})", bakName) : ""; }

    //endregion
}
