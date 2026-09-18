package kurvcygnus.soulnotes.config.prelaunch;

import io.smallrye.mutiny.Uni;
import kurvcygnus.soulnotes.ai.IModelCatalog;
import kurvcygnus.soulnotes.ai.asr.IAsrRuntimeControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SetupWizardTest
{
    //* JetBrains 注解为 compileOnly 依赖, 不在 test 编译类路径上, 故测试源不使用 (与既有测试保持一致).
    //* 脚本行序契约: 模式选择 → (展开态输入行 | esc 放弃 | 序号/q 命令) → 摘要 [Y/n] → 完成屏 1/2; SECRET/GENERATE 项经 readSecret 消费同一队列.
    @TempDir Path dir;

    //* 夹具: 3 必填 (URL/TEXT/GENERATE) + 2 可选 (NUMBER/SECRET), 覆盖全部输入类型与清单状态分支.
    private static List<PropertyMetaParser.ConfigItemMeta> fixture()
    {
        return List.of(
            meta("a.url", "TEST_A_URL", "", "组一", "A 地址", "PostgreSQL 连接说明.", PropertyMetaParser.InputType.URL, "postgresql://", 0, true),
            meta("b.name", "TEST_B_NAME", "", "组一", "B 名称", "", PropertyMetaParser.InputType.TEXT, "", 0, true),
            meta("c.num", "TEST_C_NUM", "0.5", "组二", "C 数值", "", PropertyMetaParser.InputType.NUMBER, "", 0, false),
            meta("d.key", "TEST_D_KEY", "", "组二", "D 密钥", "", PropertyMetaParser.InputType.SECRET, "", 8, false),
            meta("e.jwt", "TEST_E_JWT", "", "组三", "E 密钥", "", PropertyMetaParser.InputType.GENERATE, "", 32, true));
    }

    private static PropertyMetaParser.ConfigItemMeta meta(String key, String env, String defaultValue, String group,
        String name, String explain, PropertyMetaParser.InputType type, String scheme, int minLength, boolean required)
    {
        return new PropertyMetaParser.ConfigItemMeta(key, env, defaultValue, group, name, explain, type, scheme, minLength, required);
    }

    @Test void fullFlowCollectsExplicitValuesOnly() throws Exception
    {
        final var sink = new StringBuilder();
        //* 脚本: 模式回车=简单 → URL → TEXT → GENERATE 空输入自动生成 → 摘要回车确认 → 完成屏选启动.
        final var io = TerminalIO.fake(List.of("", "postgresql://db:5432/sn", "b-name-v", "", "y", "1"), sink);
        final var result = new SetupWizard(dir).run(fixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.LAUNCH, result.action());
        assertEquals(Set.of("TEST_A_URL", "TEST_B_NAME", "TEST_E_JWT"), result.values().keySet(), "values 只含显式输入项, 未动的可选项不入表");
        assertEquals("postgresql://db:5432/sn", result.values().get("TEST_A_URL"));
        final var jwt = result.values().get("TEST_E_JWT");
        assertTrue(jwt.length() >= 32, "GENERATE 空输入须自动生成满足 minLength 的密钥: " + jwt);
        assertDoesNotThrow(() -> Base64.getDecoder().decode(jwt), "GENERATE 产物必须是 base64");

        final var out = sink.toString();
        assertTrue(out.contains("── 组一"), "编辑阶段必须先渲染组头 + v2 清单行");
        assertTrue(out.contains("○ TEST_A_URL"), "未填必填项必须呈空心圆清单行");
        assertTrue(out.contains("PostgreSQL 连接说明."), "展开块的灰斜体解释必须渲染");
        assertTrue(out.contains("******"), "密钥类回显必须掩码");
        assertFalse(out.contains(jwt), "自动生成密钥不得明文出现在 UI 输出");
        final var props = Files.readString(dir.resolve("config/application.properties"));
        //* ConfigWriter 按已写出键的最大宽度对齐: 本次写出的最长键为 6 字符, "a.url" 补 1 空格至同一 "=" 列.
        assertTrue(props.contains("a.url  = postgresql://db:5432/sn"));
        assertFalse(props.contains("c.num"), "未动的可选项不得落盘");
        assertTrue(Files.readString(dir.resolve(".env")).contains("TEST_B_NAME=b-name-v"));
    }

    @Test void invalidInputStaysExpandedUntilValid() throws Exception
    {
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → 首项先输坏 URL (原地红字不折叠) → 再输好 URL → 余项正常 → 摘要确认 → 完成屏退出.
        final var io = TerminalIO.fake(List.of("1", "mysql://x", "postgresql://good:5432/db", "b-v", "", "y", "2"), sink);
        final var result = new SetupWizard(dir).run(fixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action());
        assertEquals("postgresql://good:5432/db", result.values().get("TEST_A_URL"), "留在展开态后最终值必须为合法输入");
        final var out = sink.toString();
        assertTrue(out.contains("✗"), "校验失败必须有红字反馈");
        assertTrue(out.contains("必须以 postgresql:// 开头"), "失败原因必须来自 FieldValidator");
        assertTrue(Files.exists(dir.resolve("config/application.properties")), "确认后正常落盘");
    }

    @Test void secretInputMaskedAndMinLengthEnforced()
    {
        final var sink = new StringBuilder();
        //* 脚本: 全面模式 → esc 折叠首项 → 序号 4 跳到 SECRET 项 → 先短后长 (minLength=8) → esc 折过 GENERATE 项 → q 摘要 → EOF 取消.
        final var io = TerminalIO.fake(List.of("2", "esc", "4", "short", "a-long-secret-99", "esc", "q"), sink);
        final var result = new SetupWizard(dir).run(fixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.CANCELLED, result.action());
        assertTrue(result.values().isEmpty(), "取消后不得返回任何值");
        final var out = sink.toString();
        assertFalse(out.contains("a-long-secret-99"), "SECRET 输入不得明文回显");
        assertTrue(out.contains("长度至少 8 字符"), "minLength 反馈必须可见");
        assertFalse(Files.exists(dir.resolve("config/application.properties")), "取消不落盘");
        assertFalse(Files.exists(dir.resolve(".env")));
    }

    @Test void eofCancelWritesNothing()
    {
        final var sink = new StringBuilder();
        final var io = TerminalIO.fake(List.of(""), sink);  //* 选完模式即在首项输入处 EOF.
        final var result = new SetupWizard(dir).run(fixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.CANCELLED, result.action());
        assertTrue(result.values().isEmpty());
        assertTrue(sink.toString().contains("已取消"));
        assertFalse(Files.exists(dir.resolve("config")), "取消不得创建任何文件或目录");
        assertFalse(Files.exists(dir.resolve(".env")));
    }

    @Test void writeFailureSignalsFailedAndDropsValues() throws Exception
    {
        //* config 被同名普通文件占用 → ConfigWriter#createDirectories 于任何文件写出之前抛 IOException (快速失败路径), 无半写状态.
        Files.createFile(dir.resolve("config"));
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → URL → TEXT → GENERATE 生成 → 摘要确认 → 写盘失败.
        final var io = TerminalIO.fake(List.of("", "postgresql://db:5432/sn", "b-name-v", "", "y"), sink);
        final var result = new SetupWizard(dir).run(fixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.FAILED, result.action());
        assertTrue(result.values().isEmpty(), "FAILED 即未持久化, 必须返回空表 (对齐 \"非空 values ⟺ 已落盘\" 不变量)");
        assertTrue(sink.toString().contains("配置写入失败"), "写盘失败必须红字可见");
        //* 防回归: SLF4J 尾参 Throwable 被抽取不参与 {} 填充, 误传 e 本身会只剩字面 "{}".
        //* 此路径实际抛 FileAlreadyExistsException (其 toString 不含 "IOException"), 故断言消息片段 (文件路径) 必须透出.
        assertTrue(sink.toString().contains(dir.resolve("config").toString()), "失败输出必须携带异常详情 (toString 含路径), 不得只剩字面 {}");
        assertFalse(Files.exists(dir.resolve(".env")), "失败早于任何文件写出, 不得产生半写产物");
    }

    @Test void numberJumpSummaryRejectAndRetainValues() throws Exception
    {
        final var sink = new StringBuilder();
        //* 脚本: 模式误输 9 重问 → 全面 → esc 折叠 → 序号 3 跳到可选 NUMBER 项填 1.5 → esc 折过 SECRET 项
        //*       → q 摘要 → n 否决回编辑 → 回车展开首项补填 → esc 折过 TEXT 项 → q 摘要 → 回车确认 → 启动.
        final var io = TerminalIO.fake(List.of(
            "9", "2", "esc", "3", "1.5", "esc", "q", "n", "", "postgresql://x:1/d", "esc", "q", "", "1"), sink);
        final var result = new SetupWizard(dir).run(fixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.LAUNCH, result.action());
        assertEquals(Set.of("TEST_C_NUM", "TEST_A_URL"), result.values().keySet());
        assertEquals("1.5", result.values().get("TEST_C_NUM"), "摘要否决回编辑后已填值必须保留");
        assertTrue(sink.toString().contains("无效选择"), "模式误输必须重问");
        final var props = Files.readString(dir.resolve("config/application.properties"));
        assertTrue(props.contains("c.num = 1.5"));
        assertFalse(props.contains("b.name"), "esc 放弃项不得落盘");
    }

    //* env 注入的密钥经 prefill 进入 values 但不得物化落盘 (README prod 指引: 密钥必须环境变量注入), 摘要屏须以灰字注明.
    @Test void prefilledSecretKeptInValuesButNotPersisted() throws Exception
    {
        final var view = new ConfigView(Map.of(), Map.of("TEST_D_KEY", "env-injected-secret-9"), new Properties());
        final var sink = new StringBuilder();
        //* 脚本: 全面模式 → a.url → b.name → c.num 留空 → d.key (SECRET) 留空保留继承值 → e.jwt 自动生成 → 摘要确认 → 启动.
        final var io = TerminalIO.fake(List.of("2", "postgresql://db:5432/sn", "b-v", "", "", "", "y", "1"), sink);
        final var result = new SetupWizard(dir).run(fixture(), view, io);

        assertEquals(SetupWizard.NextAction.LAUNCH, result.action());
        assertEquals("env-injected-secret-9", result.values().get("TEST_D_KEY"), "继承密钥留在 values (环境仍生效), 仅落盘被排除");
        final var envFile = Files.readString(dir.resolve(".env"));
        assertFalse(envFile.contains("TEST_D_KEY"), ".env 不得物化环境注入的密钥");
        assertFalse(envFile.contains("env-injected-secret-9"), ".env 不得出现继承密钥明文");
        assertFalse(Files.readString(dir.resolve("config/application.properties")).contains("d.key"), "properties 不得物化环境注入的密钥");
        assertTrue(sink.toString().contains("继承自环境"), "摘要屏必须对不落盘的继承密钥给出灰字说明");
        assertTrue(Files.readString(dir.resolve(".env")).contains("TEST_B_NAME=b-v"), "非密钥显式项照常落盘");
    }

    //* 本会话重新输入的密钥覆盖继承值并恢复落盘资格 (排除集随重输移除).
    @Test void reenteredPrefilledSecretIsPersisted() throws Exception
    {
        final var view = new ConfigView(Map.of(), Map.of("TEST_D_KEY", "env-injected-secret-9"), new Properties());
        final var sink = new StringBuilder();
        //* 脚本: 全面模式 → a.url → b.name → c.num 留空 → d.key 本次重新输入 → e.jwt 自动生成 → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of("2", "postgresql://db:5432/sn", "b-v", "", "fresh-secret-77", "", "y", "2"), sink);
        final var result = new SetupWizard(dir).run(fixture(), view, io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action());
        assertEquals("fresh-secret-77", result.values().get("TEST_D_KEY"), "重输值覆盖继承值");
        assertTrue(Files.readString(dir.resolve(".env")).contains("TEST_D_KEY=fresh-secret-77"), "本会话重输的密钥属用户显式值, 必须落盘");
        assertFalse(sink.toString().contains("继承自环境"), "重输后该项不再标记为继承来源");
    }

    @Test void prefilledExplicitValuesCarriedIntoResult() throws Exception
    {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.properties"), "a.url = postgresql://prefilled:5432/x\n");
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → 首项已预填, 回车保留 (不重新输入) → TEXT → GENERATE → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of("", "", "b-v", "", "y", "2"), sink);
        final var result = new SetupWizard(dir).run(fixture(), ConfigView.loadIn(dir), io);

        assertEquals("postgresql://prefilled:5432/x", result.values().get("TEST_A_URL"), "已配置项回车即保留, 无需重新输入");
        assertTrue(sink.toString().contains("postgresql://prefilled:5432/x"), "预填值须以当前值提示可见");
    }

    //region ASR 运行时交互

    //* 与真实 asr.engine / asr.runtime.dir 标签项同构的最小夹具 (两个可选 TEXT 项, 触发键即 envName).
    private static List<PropertyMetaParser.ConfigItemMeta> asrFixture()
    {
        return List.of(
            meta("asr.engine", "SOULNOTES_ASR_ENGINE", "vosk", "ASR", "ASR 引擎", "当前仅 vosk 可选.", PropertyMetaParser.InputType.TEXT, "", 0, false),
            meta("asr.runtime.dir", "SOULNOTES_ASR_RUNTIME_DIR", "asr-model", "ASR", "ASR 运行时目录", "lib/ 放动态库, model/ 放模型.", PropertyMetaParser.InputType.TEXT, "", 0, false));
    }

    //* 最小端口伪造: 就绪态/下载结果/进度帧全部脚本化, 进度在调用线程同步回调 (无并发干扰).
    private static final class FakeAsrControl implements IAsrRuntimeControl
    {
        private boolean ready;
        private IOException downloadFailure;
        private boolean alreadyDownloading;
        private boolean readyAfterDownload = true;
        private int downloadCalls;
        private final List<int[]> progressFrames = new ArrayList<>();

        @Override public boolean ready() { return ready; }

        @Override public Uni<Void> ensureDownloaded(BiConsumer<Integer, Integer> progress)
        {
            downloadCalls++;
            if(alreadyDownloading)
                return Uni.createFrom().failure(new IllegalStateException("已有 ASR 运行时下载任务进行中, 请等待其完成"));
            if(downloadFailure != null)
                return Uni.createFrom().failure(downloadFailure);
            for(final int[] frame : progressFrames)
                progress.accept(frame[0], frame[1]);
            ready = readyAfterDownload;  //* 成功语义: 下载完成后运行时就绪 (回执真值测试可脚本化为未就绪)
            return Uni.createFrom().voidItem();
        }
    }

    //* 目录感知工厂替身: 记录重建点请求的目录, 每目录发一个独立替身实例 (断言下载确实发生在会话新目录实例上).
    private static final class DirAwareAsrControlFactory implements Function<String, IAsrRuntimeControl>
    {
        final List<String> requestedDirs = new ArrayList<>();
        final Map<String, FakeAsrControl> created = new HashMap<>();

        @Override public IAsrRuntimeControl apply(String dir)
        {
            requestedDirs.add(dir);
            return created.computeIfAbsent(dir, k -> new FakeAsrControl());
        }
    }

    private static int countOccurrences(String haystack, String needle)
    {
        int count = 0;
        for(int idx = haystack.indexOf(needle); idx >= 0; idx = haystack.indexOf(needle, idx + needle.length()))
            count++;
        return count;
    }

    //* 拒绝路径: 未就绪询问 [Y/n], 选 n 只给灰色提示并继续主流程, 不得触发下载.
    @Test void asrNotReadyDeclinedDownloadContinues()
    {
        final var control = new FakeAsrControl();
        final var sink = new StringBuilder();
        //* 脚本: 全面模式 → 引擎项留空保存 → [Y/n] 选 n → 目录项留空保存 → 再选 n → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of("2", "", "n", "", "n", "y", "2"), sink);
        final var result = new SetupWizard(dir, control).run(asrFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action(), "拒绝下载必须继续主流程而非中断");
        assertEquals(0, control.downloadCalls, "拒绝路径不得触发下载");
        final var out = sink.toString();
        assertTrue(out.contains("是否立即下载"), "运行时未就绪必须现场询问");
        assertEquals(2, countOccurrences(out, "是否立即下载"), "两个 ASR 条目保存后各触发一次就绪检查");
        assertTrue(out.contains("可稍后手动放置或重试"), "拒绝后必须给出灰色提示");
    }

    //* 成功路径: 下载进度行内回显百分比, 完成给就绪提示; 就绪后第二项保存不再重复询问.
    @Test void asrDownloadSuccessShowsProgressAndReadyHint()
    {
        final var control = new FakeAsrControl();
        control.progressFrames.addAll(List.of(new int[] {0, 100}, new int[] {40, 100}, new int[] {100, 100}));
        final var sink = new StringBuilder();
        //* 脚本: 全面模式 → 引擎项留空保存 → [Y/n] 回车默认 Y → 目录项留空保存 (已就绪, 无询问) → 摘要确认 → 启动.
        final var io = TerminalIO.fake(List.of("2", "", "", "", "y", "1"), sink);
        final var result = new SetupWizard(dir, control).run(asrFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.LAUNCH, result.action());
        assertEquals(1, control.downloadCalls);
        final var out = sink.toString();
        assertTrue(out.contains("下载中"), "下载进度必须行内回显");
        assertTrue(out.contains("%"), "进度回显必须含百分比字符");
        assertTrue(out.contains("就绪"), "下载成功必须给出就绪提示");
        assertEquals(1, countOccurrences(out, "是否立即下载"), "下载成功后第二项保存不得重复询问");
    }

    //* 并发保护: ensureDownloaded 第二路拒绝性失败只显示 "已有下载在进行", 主流程继续.
    @Test void asrConcurrentDownloadShowsInProgressHint()
    {
        final var control = new FakeAsrControl();
        control.alreadyDownloading = true;
        final var sink = new StringBuilder();
        //* 脚本: 全面模式 → 引擎项留空保存 → [Y/n] 选 y (并发拒绝) → 目录项留空保存 → 选 n → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of("2", "", "y", "", "n", "y", "2"), sink);
        final var result = new SetupWizard(dir, control).run(asrFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action(), "并发拒绝不得中断向导");
        final var out = sink.toString();
        assertTrue(out.contains("已有下载在进行"), "并发第二路必须显示进行中提示");
        assertFalse(out.contains("✔ ASR 运行时就绪"), "并发拒绝不得误报就绪");
    }

    //* 下载失败: 给出灰色重试提示后主流程继续, 不抛异常不中断.
    @Test void asrDownloadFailureShowsRetryHintAndContinues()
    {
        final var control = new FakeAsrControl();
        control.downloadFailure = new IOException("网络中断");
        final var sink = new StringBuilder();
        //* 脚本: 全面模式 → 引擎项留空保存 → [Y/n] 选 y (失败) → 目录项留空保存 → 选 n → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of("2", "", "y", "", "n", "y", "2"), sink);
        final var result = new SetupWizard(dir, control).run(asrFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action(), "下载失败必须继续主流程");
        final var out = sink.toString();
        assertTrue(out.contains("网络中断"), "失败原因必须可见");
        assertTrue(out.contains("可稍后手动放置或重试"), "失败必须给出灰色重试提示");
    }

    //* 提示符处 EOF 沿用向导取消路径: CANCELLED 且不落盘.
    @Test void asrPromptEofCancelsWizard()
    {
        final var control = new FakeAsrControl();
        final var sink = new StringBuilder();
        //* 脚本: 全面模式 → 引擎项留空保存 → [Y/n] 处 EOF (队列耗尽).
        final var io = TerminalIO.fake(List.of("2", ""), sink);
        final var result = new SetupWizard(dir, control).run(asrFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.CANCELLED, result.action());
        assertEquals(0, control.downloadCalls, "EOF 不得触发下载");
        assertTrue(sink.toString().contains("已取消"), "取消提示必须可见");
        assertFalse(Files.exists(dir.resolve("config")), "取消不得落盘");
    }

    //* 会话内改运行时目录: 重建点必须以向导会话 values 中的当前目录请求新实例 —
    //* 下载落新目录 (旧目录实例零触碰), 就绪检查与回执同样取新实例判定 (修复 "文件落旧目录 + 误报就绪").
    @Test void asrRuntimeDirChangedInSession_DownloadRebuildsControlWithSessionDir() throws Exception
    {
        final var base = new FakeAsrControl();  //* 向导前旧目录的预装配实例: 全程不得被触碰.
        final var factory = new DirAwareAsrControlFactory();
        final var sink = new StringBuilder();
        final var newDir = "asr-warehouse/session-runtime";
        //* 脚本: 全面模式 → 引擎项留空保存 → [Y/n] 选 n → 目录项输入新目录保存 → [Y/n] 回车 Y (对重建实例下载) → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of("2", "", "n", newDir, "", "y", "2"), sink);
        final var result = new SetupWizard(dir, base, null, null, factory).run(asrFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action());
        assertEquals(List.of(newDir), factory.requestedDirs, "重建点必须以会话 values 中的当前目录请求实例");
        assertEquals(0, base.downloadCalls, "向导前旧目录实例不得触发下载 (防文件落旧目录)");
        assertEquals(1, factory.created.get(newDir).downloadCalls, "下载必须发生在新目录重建实例上");
        assertTrue(sink.toString().contains("✔ ASR 运行时就绪"), "回执必须以新实例 ready() 判定");
    }

    //* 回执真值: 下载动作成功但新实例 ready() 为 false 时, 不得打印就绪回执 (向导不得替运行时说谎).
    @Test void asrDownloadSucceedsButSessionDirNotReady_ReceiptMustNotClaimReady() throws Exception
    {
        final var factory = new DirAwareAsrControlFactory();
        final var broken = new FakeAsrControl();
        broken.readyAfterDownload = false;  //* 脚本化 "下载成功但布局仍不就绪": 回执判定必须读到 false.
        factory.created.put("asr-warehouse/broken-runtime", broken);
        final var sink = new StringBuilder();
        final var newDir = "asr-warehouse/broken-runtime";
        //* 脚本: 全面模式 → 引擎项留空保存 (基座端口为 null, 此处无询问) → 目录项输入新目录保存 → [Y/n] 回车 Y → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of("2", "", newDir, "", "y", "2"), sink);
        final var result = new SetupWizard(dir, null, null, null, factory).run(asrFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action());
        assertEquals(1, broken.downloadCalls, "下载应发生在新目录重建实例上");
        assertFalse(sink.toString().contains("✔ ASR 运行时就绪"), "新实例未就绪时不得误报就绪");
        assertTrue(sink.toString().contains("仍未就绪"), "回执必须如实告知运行时仍未就绪");
    }

    //endregion

    //region 数据库交互流

    //* 与 application.properties 数据库组同构的最小夹具: URL/用户名/密码三项必填 + 一项他组可选 (验证组外条目不触发 DB 流).
    private static List<PropertyMetaParser.ConfigItemMeta> dbFixture()
    {
        return List.of(
            meta("quarkus.datasource.reactive.url", "SOULNOTES_DB_URL", "postgresql://localhost:5432/soulnotes", "数据库", "数据库连接地址", "", PropertyMetaParser.InputType.URL, "postgresql://", 0, true),
            meta("quarkus.datasource.username", "SOULNOTES_DB_USER", "", "数据库", "数据库用户名", "", PropertyMetaParser.InputType.TEXT, "", 0, true),
            meta("quarkus.datasource.password", "SOULNOTES_DB_PASSWORD", "", "数据库", "数据库密码", "", PropertyMetaParser.InputType.SECRET, "", 0, true),
            meta("other.note", "OTHER_NOTE", "", "其他", "其他项", "", PropertyMetaParser.InputType.TEXT, "", 0, false));
    }

    //* 最小网关伪造: probe 结果按脚本队列依序返回 (越界钳制到末项), create/apply 动作计数, 失败脚本化.
    private static final class FakeGateway implements IDatabaseGateway
    {
        private final List<ProbeResult> probeScript;
        private IllegalStateException createFailure;
        private int probeCalls;
        private int createCalls;
        private int applyCalls;

        private FakeGateway(ProbeResult... results) { probeScript = List.of(results); }

        @Override public ProbeResult probe(DbTarget target)
        {
            probeCalls++;
            var idx = probeCalls - 1;
            if(idx >= probeScript.size()) idx = probeScript.size() - 1;
            return probeScript.get(idx);
        }

        @Override public void createDatabase(DbTarget target)
        {
            createCalls++;
            if(createFailure != null) throw createFailure;
        }

        @Override public void applySchema(DbTarget target) { applyCalls++; }  //* 向导只走带进度的重载, 单参版本仅为接口完备.

        @Override public void applySchema(DbTarget target, Consumer<String> scriptProgress)
        {
            applyCalls++;
            for(final var script : List.of("01_users.sql", "04_platform_schema_version.sql"))
                scriptProgress.accept(script);
        }
    }

    //* probe → OK: 一行 ✔ 回显, 无任何写入动作, 向导照常推进.
    @Test void dbProbeOkEchoesReadyAndContinues()
    {
        final var gateway = new FakeGateway(new ProbeResult(ProbeResult.State.OK, List.of()));
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → URL → 用户名 → 密码 (三项齐备触发探测 OK) → 摘要确认 → 启动.
        final var io = TerminalIO.fake(List.of("", "postgresql://db:5432/sn", "db-user", "db-pass", "y", "1"), sink);
        final var result = new SetupWizard(dir, null, gateway).run(dbFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.LAUNCH, result.action());
        assertEquals(1, gateway.probeCalls);
        assertEquals(0, gateway.createCalls);
        assertEquals(0, gateway.applyCalls);
        assertTrue(sink.toString().contains("数据库连接就绪"), "probe OK 必须一行 ✔ 回显");
    }

    //* DB_MISSING: 自动建库 → 重探 OK → ✔, 全程零额外交互.
    @Test void dbMissingAutoCreatesThenReprobesReady()
    {
        final var gateway = new FakeGateway(
            new ProbeResult(ProbeResult.State.DB_MISSING, List.of()),
            new ProbeResult(ProbeResult.State.OK, List.of()));
        final var sink = new StringBuilder();
        final var io = TerminalIO.fake(List.of("", "postgresql://db:5432/sn", "db-user", "db-pass", "y", "1"), sink);
        final var result = new SetupWizard(dir, null, gateway).run(dbFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.LAUNCH, result.action());
        assertEquals(1, gateway.createCalls, "DB_MISSING 必须恰好自动建库一次");
        assertEquals(2, gateway.probeCalls, "建库后必须重探确认");
        final var out = sink.toString();
        assertTrue(out.contains("自动创建"), "自动建库动作必须可见");
        assertTrue(out.contains("数据库连接就绪"), "重探 OK 必须回显就绪");
    }

    //* 建库失败 (无权限/竞争): ✗ 原因就地可见并回到当前项重编辑 — 改值再保存经指纹重触发第二次探测/建库.
    @Test void dbMissingCreateFailureStaysOnItemForReedit()
    {
        final var gateway = new FakeGateway(
            new ProbeResult(ProbeResult.State.DB_MISSING, List.of()),
            new ProbeResult(ProbeResult.State.DB_MISSING, List.of()));
        gateway.createFailure = new IllegalStateException("账号 kurv 无 CREATEDB 权限, 无法自动建库");
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → URL → 用户名 → 密码 (建库失败 ✗) → 密码项重编辑换值 (再次失败 ✗) → EOF 取消.
        final var io = TerminalIO.fake(List.of("", "postgresql://db:5432/sn", "db-user", "db-pass", "db-pass2"), sink);
        final var result = new SetupWizard(dir, null, gateway).run(dbFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.CANCELLED, result.action());
        assertEquals(2, gateway.createCalls, "失败必须回到编辑态而非中断向导, 改值保存后再次触发");
        assertEquals(2, gateway.probeCalls);
        final var out = sink.toString();
        assertTrue(out.contains("自动建库失败"), "建库失败必须红字可见");
        assertTrue(out.contains("无 CREATEDB 权限"), "失败原因必须携带");
        assertEquals(2, countOccurrences(out, "自动建库失败"), "两次保存各触发一次探测/建库");
    }

    //* AUTH_FAILED: ✗ 即时报错 + 回重编辑; 改对密码后重探 OK, 无建库/建表动作.
    @Test void authFailedReturnsToEditingThenRecovers()
    {
        final var gateway = new FakeGateway(
            new ProbeResult(ProbeResult.State.AUTH_FAILED, List.of()),
            new ProbeResult(ProbeResult.State.OK, List.of()));
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → URL → 用户名 → 错密码 (AUTH ✗) → 密码项重编辑输对 → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of("", "postgresql://db:5432/sn", "db-user", "wrong-pass", "right-pass", "y", "2"), sink);
        final var result = new SetupWizard(dir, null, gateway).run(dbFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action());
        assertEquals(2, gateway.probeCalls, "改值后必须重新探测");
        assertEquals(0, gateway.createCalls);
        assertEquals(0, gateway.applyCalls);
        assertTrue(sink.toString().contains("账号或密码被拒绝"), "AUTH_FAILED 必须即时报错");
        assertEquals("right-pass", result.values().get("SOULNOTES_DB_PASSWORD"), "重编辑后的值必须生效");
    }

    //* UNREACHABLE: ✗ 即时报错 + 回重编辑, 不做任何写入动作; 重编辑符处 EOF 走向导取消路径.
    @Test void unreachableReportsErrorAndReturnsToEditing()
    {
        final var gateway = new FakeGateway(new ProbeResult(ProbeResult.State.UNREACHABLE, List.of()));
        final var sink = new StringBuilder();
        final var io = TerminalIO.fake(List.of("", "postgresql://db:5432/sn", "db-user", "db-pass"), sink);
        final var result = new SetupWizard(dir, null, gateway).run(dbFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.CANCELLED, result.action());
        assertEquals(0, gateway.createCalls);
        assertTrue(sink.toString().contains("数据库不可达"), "UNREACHABLE 必须即时报错");
    }

    //* SCHEMA_MISSING + y (回车默认): 缺表清单可见, 逐脚本一行回显, 重探 OK 后 ✔.
    @Test void schemaMissingConfirmedAppliesScriptsAndProbesOk()
    {
        final var gateway = new FakeGateway(
            new ProbeResult(ProbeResult.State.SCHEMA_MISSING, List.of("users", "mood_diaries")),
            new ProbeResult(ProbeResult.State.OK, List.of()));
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → URL → 用户名 → 密码 (SCHEMA_MISSING) → 询问符回车 = y → 摘要确认 → 启动.
        final var io = TerminalIO.fake(List.of("", "postgresql://db:5432/sn", "db-user", "db-pass", "", "y", "1"), sink);
        final var result = new SetupWizard(dir, null, gateway).run(dbFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.LAUNCH, result.action());
        assertEquals(1, gateway.applyCalls);
        assertEquals(2, gateway.probeCalls, "建表后必须重探确认");
        final var out = sink.toString();
        assertTrue(out.contains("users, mood_diaries"), "缺表清单必须可见");
        assertTrue(out.contains("初始化数据库结构"), "必须现场询问");
        assertTrue(out.contains("01_users.sql"), "applySchema 必须逐脚本一行回显");
        assertTrue(out.contains("数据库连接就绪"), "重探 OK 必须回显就绪");
    }

    //* SCHEMA_MISSING + n: 只给灰色后果提示并继续向导, 不执行任何写脚本.
    @Test void schemaMissingDeclinedSkipsInitAndContinues()
    {
        final var gateway = new FakeGateway(new ProbeResult(ProbeResult.State.SCHEMA_MISSING, List.of("users")));
        final var sink = new StringBuilder();
        final var io = TerminalIO.fake(List.of("", "postgresql://db:5432/sn", "db-user", "db-pass", "n", "y", "2"), sink);
        final var result = new SetupWizard(dir, null, gateway).run(dbFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action(), "拒绝初始化不得中断向导");
        assertEquals(0, gateway.applyCalls);
        assertTrue(sink.toString().contains("启动校验将拒绝启动"), "n 后必须提示 LAUNCH 前 BLOCK 后果");
    }

    //* 询问符处 EOF 沿用向导取消路径: CANCELLED 且不执行建表.
    @Test void schemaPromptEofCancelsWizard()
    {
        final var gateway = new FakeGateway(new ProbeResult(ProbeResult.State.SCHEMA_MISSING, List.of("users")));
        final var sink = new StringBuilder();
        final var io = TerminalIO.fake(List.of("", "postgresql://db:5432/sn", "db-user", "db-pass"), sink);
        final var result = new SetupWizard(dir, null, gateway).run(dbFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.CANCELLED, result.action());
        assertEquals(0, gateway.applyCalls, "EOF 不得触发建表");
        assertTrue(sink.toString().contains("初始化数据库结构"));
    }

    //* 重触发设计 (三项值指纹): 同值重存 (摘要否决回编辑后逐项回车保留) 不重探 — 避免重复探测噪音.
    @Test void sameValueResaveDoesNotReprobe()
    {
        final var gateway = new FakeGateway(new ProbeResult(ProbeResult.State.OK, List.of()));
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → 三项填写 (probe#1 OK) → 摘要否决回编辑 → 命令态回车展开 URL → 回车依次保留三项 (指纹未变) → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of("", "postgresql://db:5432/sn", "db-user", "db-pass", "n", "", "", "", "", "y", "2"), sink);
        final var result = new SetupWizard(dir, null, gateway).run(dbFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action());
        assertEquals(1, gateway.probeCalls, "同值重存不得重复探测");
        assertEquals(1, countOccurrences(sink.toString(), "数据库连接就绪"));
    }

    //endregion

    //region AI 模型拉取步

    //* 与 application.properties AI 接入组同构的最小夹具: endpoint/model/key 三项必填 + 一项他组可选 (验证组外条目不触发拉取);
    //* properties 中 model 项位于 key 之前, 触发点在 key 保存时 — 选择结果直接覆写 values 中已填的 model 值.
    private static List<PropertyMetaParser.ConfigItemMeta> aiFixture()
    {
        return List.of(
            meta("ai.openai.endpoint", "SOULNOTES_AI_ENDPOINT", "https://api.openai.com/v1", "AI 接入", "AI 接口地址", "", PropertyMetaParser.InputType.URL, "https://|http://", 0, true),
            meta("ai.openai.model-name", "SOULNOTES_AI_MODEL", "gpt-4o-mini", "AI 接入", "模型名称", "", PropertyMetaParser.InputType.TEXT, "", 0, true),
            meta("ai.openai.api-key", "SOULNOTES_AI_API_KEY", "placeholder", "AI 接入", "API 密钥", "", PropertyMetaParser.InputType.SECRET, "", 0, true),
            meta("other.note", "OTHER_NOTE", "", "其他", "其他项", "", PropertyMetaParser.InputType.TEXT, "", 0, false));
    }

    //* 脚本化单次 fetch 结果: ok 与 error 二选一 (error 非 null 时 fetch 抛出), 测试源无 JetBrains 注解, 可空性以本注释为准.
    private record ScriptedResult(IModelCatalog.CatalogResult ok, Exception error) {}

    private static ScriptedResult ok(IModelCatalog.CatalogResult result) { return new ScriptedResult(result, null); }

    private static ScriptedResult unauthorized() { return new ScriptedResult(null, new IModelCatalog.UnauthorizedException("HTTP 401: API 密钥被服务端拒绝")); }

    private static ScriptedResult failure(IOException cause) { return new ScriptedResult(null, cause); }

    private static IModelCatalog.ModelInfo model(String id, String context, String reasoning)
    {
        return new IModelCatalog.ModelInfo(id, context, reasoning);
    }

    //* 最小端口伪造: 结果按脚本队列依序返回 (越界钳制到末项), 记录最近一次调用参数供触发条件断言.
    private static final class FakeCatalog implements IModelCatalog
    {
        private final List<ScriptedResult> script;
        private int calls;
        private String lastEndpoint;
        private String lastApiKey;
        private Duration lastTimeout;

        private FakeCatalog(ScriptedResult... results) { script = List.of(results); }

        @Override public CatalogResult fetch(String endpoint, String apiKey, Duration timeout) throws UnauthorizedException, IOException
        {
            calls++;
            lastEndpoint = endpoint;
            lastApiKey = apiKey;
            lastTimeout = timeout;
            final var step = script.get(Math.min(calls - 1, script.size() - 1));
            if(step.error() instanceof UnauthorizedException u) throw u;
            if(step.error() instanceof IOException io) throw io;
            return step.ok();
        }
    }

    //* 成功链: key 保存触发拉取 → 回显探测 URL + 元数据列表行 → 非法序号重问 → 选中项覆写 values 中的 model 与 endpoint (规范化形).
    @Test void aiFetchSuccessSelectionOverridesManualAndNormalizesEndpoint() throws Exception
    {
        final var catalog = new FakeCatalog(ok(new IModelCatalog.CatalogResult(
            List.of(model("m-a", "128000", "✓"), model("m-b", "-", "-")), "https://gate.example.com/v1")));
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → endpoint → model 先手动填占位 → key (触发拉取) → 序号 9 越界 → abc 非法 → 0 越界 → 选 1 → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of(
            "", "https://gate.example.com", "manual-typed", "sk-1", "9", "abc", "0", "1", "y", "2"), sink);
        final var result = new SetupWizard(dir, null, null, catalog).run(aiFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action());
        assertEquals(1, catalog.calls);
        assertEquals("https://gate.example.com", catalog.lastEndpoint, "必须以用户原始输入触发拉取 (启发式在 fetch 内部)");
        assertEquals("sk-1", catalog.lastApiKey);
        assertEquals(Duration.ofSeconds(10), catalog.lastTimeout, "拉取超时须为 钉死的 10s");
        assertEquals("m-a", result.values().get("SOULNOTES_AI_MODEL"), "选中项必须覆写先前手动填入的 model");
        assertEquals("https://gate.example.com/v1", result.values().get("SOULNOTES_AI_ENDPOINT"), "拉取成功后 endpoint 必须存规范形 (fetch 与存储分离)");
        final var out = sink.toString();
        assertTrue(out.contains("探测模型列表: https://gate.example.com/v1/models"), "必须回显最终请求 URL");
        assertTrue(out.contains("1. m-a [上下文: 128000] [思考: ✓]"), "列表行格式必须为 序号. 模型ID [上下文: n] [思考: ✓]");
        assertTrue(out.contains("2. m-b [上下文: -] [思考: -]"));
        assertEquals(2, countOccurrences(out, "序号超出范围"), "越界序号必须重问");
        assertEquals(1, countOccurrences(out, "无效输入"), "非数字序号必须重问");
        assertTrue(out.contains("模型已选定: m-a"), "选定结果必须可见回显");
        assertTrue(Files.readString(dir.resolve(".env")).contains("SOULNOTES_AI_ENDPOINT=https://gate.example.com/v1"), "落盘值必须是规范化 endpoint");
    }

    //* 401/403 → ✗ 密钥无效, 停在当前项重编辑; 换正确 key 后经指纹重触发, 第二次拉取选定成功.
    @Test void aiFetchUnauthorizedReeditsKeyThenRecovers()
    {
        final var catalog = new FakeCatalog(unauthorized(), ok(new IModelCatalog.CatalogResult(
            List.of(model("m-a", "-", "-"), model("m-b", "8192", "✗")), "https://gate.example.com/v1")));
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → endpoint (已带 /v1) → model 占位 → 错 key (401 ✗ 回重编辑) → 对 key → 选 2 → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of(
            "", "https://gate.example.com/v1", "placeholder-m", "bad-key", "good-key", "2", "y", "2"), sink);
        final var result = new SetupWizard(dir, null, null, catalog).run(aiFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action());
        assertEquals(2, catalog.calls, "401 后换 key 重存必须重新拉取");
        assertEquals("m-b", result.values().get("SOULNOTES_AI_MODEL"));
        assertEquals("good-key", result.values().get("SOULNOTES_AI_API_KEY"));
        assertEquals("https://gate.example.com/v1", result.values().get("SOULNOTES_AI_ENDPOINT"), "已带 /v1 的输入规范化后不变");
        assertTrue(sink.toString().contains("密钥无效"), "401/403 必须以密钥无效红字可见");
    }

    //* 网络失败 → ⚠ 携带原因 + 手动输入 model 兜底; endpoint 保持用户原样 (未经探测证实, 不做规范化).
    @Test void aiFetchNetworkFailureFallsBackToManualModelInput()
    {
        final var catalog = new FakeCatalog(failure(new IOException("连接被拒绝")));
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → endpoint → model 占位 → key (拉取失败) → 手动输入模型名 → 摘要确认 → 启动.
        final var io = TerminalIO.fake(List.of(
            "", "https://gate.example.com", "old-model", "sk-1", "my-manual-model", "y", "1"), sink);
        final var result = new SetupWizard(dir, null, null, catalog).run(aiFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.LAUNCH, result.action(), "网络失败必须继续主流程而非中断向导");
        assertEquals("my-manual-model", result.values().get("SOULNOTES_AI_MODEL"), "手动输入的模型名必须生效");
        assertEquals("https://gate.example.com", result.values().get("SOULNOTES_AI_ENDPOINT"), "未探测成功不得擅自规范化 endpoint");
        final var out = sink.toString();
        assertTrue(out.contains("无法获取模型列表"), "网络失败必须 ⚠ 可见");
        assertTrue(out.contains("连接被拒绝"), "失败原因必须携带");
    }

    //* 空列表与网络失败同兜底: ⚠ 提示后手动输入.
    @Test void aiFetchEmptyListFallsBackToManualModelInput()
    {
        final var catalog = new FakeCatalog(ok(new IModelCatalog.CatalogResult(List.of(), "https://gate.example.com/v1")));
        final var sink = new StringBuilder();
        final var io = TerminalIO.fake(List.of(
            "", "https://gate.example.com", "old-model", "sk-1", "manual-fallback", "y", "2"), sink);
        final var result = new SetupWizard(dir, null, null, catalog).run(aiFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action());
        assertEquals("manual-fallback", result.values().get("SOULNOTES_AI_MODEL"));
        assertTrue(sink.toString().contains("模型列表为空"));
    }

    //* 重触发设计 (endpoint+key 值指纹): 拉取成功后同值重存各 AI 条目不得重复拉取.
    @Test void aiSameValueResaveDoesNotRefetch()
    {
        final var catalog = new FakeCatalog(ok(new IModelCatalog.CatalogResult(
            List.of(model("m-a", "-", "-")), "https://gate.example.com/v1")));
        final var sink = new StringBuilder();
        //* 脚本: 简单模式 → 三项填写 (拉取 + 选 1) → 摘要否决回编辑 → 命令态回车展开首项 → 回车依次保留三项 (指纹未变) → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of(
            "", "https://gate.example.com", "manual-typed", "sk-1", "1", "n", "", "", "", "", "", "2"), sink);
        final var result = new SetupWizard(dir, null, null, catalog).run(aiFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action());
        assertEquals(1, catalog.calls, "同值重存不得重复拉取 (指纹以规范化后 endpoint 参与计算)");
        assertEquals("m-a", result.values().get("SOULNOTES_AI_MODEL"));
    }

    //* 他组条目保存不得触发 AI 拉取 (组门禁), 即便 endpoint+key 已齐备; 全面模式下 key 保存选完后自动展开他组项.
    @Test void aiFlowNotTriggeredByOtherGroupItems()
    {
        final var catalog = new FakeCatalog(ok(new IModelCatalog.CatalogResult(
            List.of(model("m-a", "-", "-")), "https://gate.example.com/v1")));
        final var sink = new StringBuilder();
        //* 脚本: 全面模式 → endpoint → model → key (触发一次拉取) → 选 1 → 自动展开他组项填 x (不得再触发) → 摘要确认 → 退出.
        final var io = TerminalIO.fake(List.of(
            "2", "https://gate.example.com", "m", "sk-1", "1", "x", "", "2"), sink);
        final var result = new SetupWizard(dir, null, null, catalog).run(aiFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.EXIT, result.action());
        assertEquals(1, catalog.calls, "非 AI 组条目保存不得触发拉取");
    }

    //* 选择符处 EOF 沿用向导取消路径: CANCELLED 且不落盘.
    @Test void aiSelectionPromptEofCancelsWizard()
    {
        final var catalog = new FakeCatalog(ok(new IModelCatalog.CatalogResult(
            List.of(model("m-a", "-", "-")), "https://gate.example.com/v1")));
        final var sink = new StringBuilder();
        final var io = TerminalIO.fake(List.of("", "https://gate.example.com", "m", "sk-1"), sink);
        final var result = new SetupWizard(dir, null, null, catalog).run(aiFixture(), ConfigView.loadIn(dir), io);

        assertEquals(SetupWizard.NextAction.CANCELLED, result.action());
        assertTrue(result.values().isEmpty());
        assertTrue(sink.toString().contains("请选择模型序号"), "EOF 前必须已进入模型选择符");
        assertFalse(Files.exists(dir.resolve("config")), "取消不得落盘");
    }

    //endregion
}
