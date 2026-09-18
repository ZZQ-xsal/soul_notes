package kurvcygnus.soulnotes;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;
import kurvcygnus.soulnotes.ai.asr.AsrRuntimeManager;
import kurvcygnus.soulnotes.config.prelaunch.ConfigValidationTask;
import kurvcygnus.soulnotes.config.prelaunch.ConfigView;
import kurvcygnus.soulnotes.config.prelaunch.IPreLaunchTask;
import kurvcygnus.soulnotes.config.prelaunch.PreLaunchContext;
import kurvcygnus.soulnotes.config.prelaunch.PropertyMetaParser;
import kurvcygnus.soulnotes.config.prelaunch.SetupWizard;
import kurvcygnus.soulnotes.config.prelaunch.TerminalIO;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@QuarkusMain public final class Entrance
{
    enum Action { PROCEED, OFFER_WIZARD, REPORT_EXIT }

    //* 决策矩阵 (Spec §6.2): setup 短路 > 无 BLOCK 放行 > 有 BLOCK 看 TTY (交互进向导, 非交互报错退出).
    static @NotNull Action decide(boolean hasBlocks, boolean tty, boolean setupRequested)
    {
        if(setupRequested)
            return Action.OFFER_WIZARD;
        if(!hasBlocks)
            return Action.PROCEED;
        return tty ? Action.OFFER_WIZARD : Action.REPORT_EXIT;
    }

    static boolean wantsSetup(@NotNull String... args) { return Arrays.stream(args).anyMatch(Entrance::isSetupFlag); }

    private static boolean isSetupFlag(@NotNull String arg) { return arg.equals("--setup") || arg.equals("setup"); }

    //* 纯函数: BLOCK 组在前 (❌), WARN 组在后 (⚠), 每行以 \n 结尾; 空问题集返回空串 (main 各分支均仅在存在问题时调用, 空集分支仅为纯函数完备性保留).
    static @NotNull String formatReport(@NotNull List<IPreLaunchTask.Issue> issues)
    {
        if(issues.isEmpty())
            return "";
        final var sb = new StringBuilder();
        appendIssueGroup(sb, issues, IPreLaunchTask.Level.BLOCK, "❌");
        appendIssueGroup(sb, issues, IPreLaunchTask.Level.WARN, "⚠");
        return sb.toString();
    }

    private static void appendIssueGroup(@NotNull StringBuilder sb, @NotNull List<IPreLaunchTask.Issue> issues, @NotNull IPreLaunchTask.Level level, @NotNull String icon)
    {
        for(final var issue: issues)
        {
            if(issue.level() != level) continue;
            sb.append(icon).append(" [").append(issue.subject()).append("] ").append(issue.message()).append('\n');
        }
    }

    public static void main(String... args)
    {
        printBanner();
        final var io = TerminalIO.system();
        final var items = PropertyMetaParser.parseResource();
        final var profile = resolveProfile();

        if(wantsSetup(args))
        {
            runWizardAndMaybeLaunch(items, profile, args, io);
            return;
        }

        final var view = ConfigView.load();
        final var result = new ConfigValidationTask(asrControl(view, items)::ready).run(new PreLaunchContext(view, items, profile));
        switch(decide(result.hasBlocks(), ConfigView.tty(), false))
        {
            case PROCEED ->
            {
                //* 仅警告流程 (Spec §6): 无 BLOCK 但存在 ⚠ 时, 必须先打印警告清单再继续启动, 不能静默放行.
                if(!result.issues().isEmpty()) io.writeErr(formatReport(result.issues()));
                Quarkus.run(args);
            }
            case OFFER_WIZARD ->
            {
                io.writeErr(formatReport(result.issues()));
                io.writeErr("是否进入配置向导修复? [Y/n] ");
                final var answer = io.readLine();
                if(answer != null && answer.strip().equalsIgnoreCase("n"))
                {
                    io.writeErr("已取消, 退出码 1\n");
                    System.exit(1);
                }
                runWizardAndMaybeLaunch(items, profile, args, io);
            }
            case REPORT_EXIT ->
            {
                io.writeErr(formatReport(result.issues()));
                System.exit(1);
            }
        }
    }

    //* Quarkus dev fork (quarkusDev) 不透传 -Dquarkus.profile, 而 [[LaunchMode]] 的静态位在 main 入口时尚未初始化 (Quarkus.run 之后才置位),
    //* 故 dev-runner classpath 独有的 deployment 类成为入口时刻唯一可靠的 dev 信号 — prod fast-jar 与 native 镜像均不含 deployment 类.
    //* 显式 quarkus.profile/QUARKUS_PROFILE 仍然最优先, 优先序与 Quarkus 运行时 ConfigUtils#getProfiles 保持一致.
    private static @NotNull String resolveProfile()
    {
        final var sysProp = System.getProperty("quarkus.profile");
        final var envVar = System.getenv("QUARKUS_PROFILE");
        if((sysProp != null && !sysProp.isBlank()) || (envVar != null && !envVar.isBlank()))
            return ConfigView.detectProfile();
        return underQuarkusDevFork() ? "dev" : ConfigView.detectProfile();
    }

    private static boolean underQuarkusDevFork()
        { return Entrance.class.getClassLoader().getResource("io/quarkus/deployment/dev/IsolatedDevModeMain.class") != null; }

    //* 向导落盘的是工作目录 config/application.properties, 必须重新 load 才能读到刚写入的显式值;
    //* LAUNCH 前以全新视图重跑校验任务, 无 BLOCK 方可放行 (向导修复路径的二次校验, Spec §6.2).
    private static void runWizardAndMaybeLaunch(@NotNull List<PropertyMetaParser.ConfigItemMeta> items, @NotNull String profile, @NotNull String[] args, @NotNull TerminalIO io)
    {
        final var view = ConfigView.load();
        final var result = new SetupWizard(Path.of(""), asrControl(view, items)).run(items, view, io);
        if(result.action() == SetupWizard.NextAction.CANCELLED)
            System.exit(1);  //! 取消提示已由向导写往 stderr; Spec §6.2 规定向导中途取消 → 退出码 1, 区别于下方用户主动退出的正常返回.
        if(result.action() == SetupWizard.NextAction.FAILED)
            System.exit(1);  //! 写盘失败提示已由向导写往 stderr; 评审轮次 2: 异常收场与取消同为退出码 1, 不得与用户主动退出 (退出码 0) 混同.
        if(result.action() == SetupWizard.NextAction.EXIT) return;  //* 用户主动退出: 正常返回, 不进入 Quarkus.
        final var freshView = ConfigView.load();
        final var validation = new ConfigValidationTask(asrControl(freshView, items)::ready).run(new PreLaunchContext(freshView, items, profile));
        if(validation.hasBlocks())
        {
            io.writeErr(formatReport(validation.issues()));
            System.exit(1);
        }
        Quarkus.run(args);
    }

    //* Pre-Launch 全程处于 CDI 启动之前, AsrRuntimeManager 只能纯构造装配 (ready() 为纯文件检查, 不触网);
    //* 运行时目录与 lib JAR 地址均经向导元数据 + 配置视图解析, 与校验/向导同源 (单一来源 application.properties 的
    //* ${ENV:default}), 不复制内置默认字面量; lib URL 经 resolveLibUrl 归一空值, 保证自定义 JAR 源 (如 arm 构建) 生效.
    //* 注意: 向导落盘后 LAUNCH 前以 freshView 重建实例仅使校验/就绪判定读到新目录, 向导会话内已下载到旧目录的文件不会迁移.
    static @NotNull AsrRuntimeManager asrControl(@NotNull ConfigView view, @NotNull List<PropertyMetaParser.ConfigItemMeta> items)
    {
        final var runtimeDir = metaValue(view, items, "asr.runtime.dir");
        final var libUrl = AsrRuntimeManager.resolveLibUrl(metaValue(view, items, "asr.lib.url"));
        return new AsrRuntimeManager(Path.of(runtimeDir), AsrRuntimeManager.DEFAULT_MODEL_URL, libUrl);
    }

    //* 向导条目值解析 (显式值 > 内置默认): 元数据为键/环境名/默认值的单一来源, 这里不做任何复制.
    private static @NotNull String metaValue(@NotNull ConfigView view, @NotNull List<PropertyMetaParser.ConfigItemMeta> items, @NotNull String key)
    {
        final var meta = items.stream().
            filter(item -> key.equals(item.key())).
            findFirst().
            orElseThrow(() -> new IllegalStateException("application.properties 缺少 " + key + " 向导条目"));
        return view.resolved(meta.key(), meta.envName(), meta.defaultValue());
    }

    private static void printBanner()
    {
        //* 请不要把这里的打印换成[[Logger#info]].
        //* [[Logger]]对ASCII Art的支持不完善, 有肉眼可见的乱码问题.
        //* 而且没有 Log Header 会更酷.
        System.out.println(
            """
              █████████                      ████     ██████   █████           █████                     ███
             ███░░░░░███                    ░░███    ░░██████ ░░███           ░░███                     ░███
            ░███    ░░░   ██████  █████ ████ ░███     ░███░███ ░███   ██████  ███████    ██████   █████ ░███
            ░░█████████  ███░░███░░███ ░███  ░███     ░███░░███░███  ███░░███░░░███░    ███░░███ ███░░  ░███
             ░░░░░░░░███░███ ░███ ░███ ░███  ░███     ░███ ░░██████ ░███ ░███  ░███    ░███████ ░░█████ ░███
             ███    ░███░███ ░███ ░███ ░███  ░███     ░███  ░░█████ ░███ ░███  ░███ ███░███░░░   ░░░░███░░░
            ░░█████████ ░░██████  ░░████████ █████    █████  ░░█████░░██████   ░░█████ ░░██████  ██████  ███
             ░░░░░░░░░   ░░░░░░    ░░░░░░░░ ░░░░░    ░░░░░    ░░░░░  ░░░░░░     ░░░░░   ░░░░░░  ░░░░░░  ░░░
            
            Start Initializing...
            """
        );
    }
}
