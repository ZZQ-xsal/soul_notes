package kurvcygnus.soulnotes.config.prelaunch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class DbValidationTaskTest
{
    private static PropertyMetaParser.ConfigItemMeta meta(String env, String key, String def, PropertyMetaParser.InputType t, String scheme)
    { return new PropertyMetaParser.ConfigItemMeta(key, env, def, "g", "n", "", t, scheme, 0, true); }

    private static final PropertyMetaParser.ConfigItemMeta DB_URL =
        meta("SOULNOTES_DB_URL", "quarkus.datasource.reactive.url", "postgresql://localhost:5432/soulnotes", PropertyMetaParser.InputType.URL, "postgresql://");
    private static final PropertyMetaParser.ConfigItemMeta DB_USER =
        meta("SOULNOTES_DB_USER", "quarkus.datasource.username", "", PropertyMetaParser.InputType.TEXT, "");
    private static final PropertyMetaParser.ConfigItemMeta DB_PASSWORD =
        meta("SOULNOTES_DB_PASSWORD", "quarkus.datasource.password", "", PropertyMetaParser.InputType.SECRET, "");

    //* 记录型 fake: probeCalls 验证探测发生, create/apply 计数钉死零写入契约 (Spec §5 非 TTY 只探测不写).
    @SuppressWarnings("NullableProblems")//! 测试源集不引 JetBrains 注解 (compileOnly), 以抑制覆写签名告警 (先例: VoskAsrEngineTest).
    private static final class FakeGateway implements IDatabaseGateway
    {
        private ProbeResult next = new ProbeResult(ProbeResult.State.OK, List.of());
        int probeCalls;
        int createCalls;
        int applyCalls;

        void returns(ProbeResult result) { this.next = result; }

        @Override public ProbeResult probe(DbTarget target) { probeCalls++; return next; }

        @Override public void createDatabase(DbTarget target) { createCalls++; }

        @Override public void applySchema(DbTarget target) { applyCalls++; }
    }

    private static PreLaunchContext ctx(Map<String, String> env)
    { return new PreLaunchContext(new ConfigView(Map.of(), env, new Properties()), List.of(DB_URL, DB_USER, DB_PASSWORD), "prod"); }

    private static Map<String, String> validEnv()
    {
        return Map.of("SOULNOTES_DB_USER", "kurv", "SOULNOTES_DB_PASSWORD", "secret");
    }

    @Test void okStateEmitsNoIssueAndNeverWrites()
    {
        final var fake = new FakeGateway();
        final var result = new DbValidationTask(fake).run(ctx(validEnv()));
        assertTrue(result.issues().isEmpty());
        assertEquals(1, fake.probeCalls);
        assertEquals(0, fake.createCalls, "非 TTY 路径零写入: 不得建库");
        assertEquals(0, fake.applyCalls, "非 TTY 路径零写入: 不得套用 schema");
    }

    @Test void everyNonOkStateEmitsExactlyOneBlock()
    {
        for(final var state : List.of(ProbeResult.State.UNREACHABLE, ProbeResult.State.AUTH_FAILED,
            ProbeResult.State.DB_MISSING, ProbeResult.State.SCHEMA_MISSING))
        {
            final var fake = new FakeGateway();
            fake.returns(new ProbeResult(state, List.of()));
            final var result = new DbValidationTask(fake).run(ctx(validEnv()));
            final var blocks = result.issues().stream().filter(i -> i.level() == IPreLaunchTask.Level.BLOCK).toList();
            assertEquals(1, blocks.size(), "state " + state + " 必须恰好产出一条 BLOCK");
            assertEquals("SOULNOTES_DB_URL", blocks.getFirst().subject());
        }
    }

    @Test void schemaMissingBlockListsMissingTables()
    {
        final var fake = new FakeGateway();
        fake.returns(new ProbeResult(ProbeResult.State.SCHEMA_MISSING, List.of("users", "platform_schema_version")));
        final var message = new DbValidationTask(fake).run(ctx(validEnv())).issues().getFirst().message();
        assertTrue(message.contains("users"), "BLOCK 消息须列出缺表");
        assertTrue(message.contains("platform_schema_version"), "BLOCK 消息须列出缺表");
    }

    @Test void dbMissingBlockMentionsWizardCreation()
    {
        final var fake = new FakeGateway();
        fake.returns(new ProbeResult(ProbeResult.State.DB_MISSING, List.of()));
        final var message = new DbValidationTask(fake).run(ctx(validEnv())).issues().getFirst().message();
        assertTrue(message.contains("向导"), "DB_MISSING 须提示可经向导自动创建");
    }

    @Test void authFailedBlockMentionsCredentials()
    {
        final var fake = new FakeGateway();
        fake.returns(new ProbeResult(ProbeResult.State.AUTH_FAILED, List.of()));
        final var message = new DbValidationTask(fake).run(ctx(validEnv())).issues().getFirst().message();
        assertTrue(message.contains("账号") || message.contains("密码"), "AUTH_FAILED 须提示检查账号密码");
    }

    @Test void blankUrlSkipsProbeSilently()
    {
        //* 配置层必配 BLOCK 未通过时探测无意义 (Spec §5): 让 ConfigValidationTask 先报, 本任务静默跳过.
        final var fake = new FakeGateway();
        final var result = new DbValidationTask(fake).run(ctx(Map.of("SOULNOTES_DB_URL", "")));
        assertTrue(result.issues().isEmpty());
        assertEquals(0, fake.probeCalls);
    }

    @Test void blankUserSkipsProbeSilently()
    {
        final var fake = new FakeGateway();
        final var result = new DbValidationTask(fake).run(ctx(Map.of("SOULNOTES_DB_PASSWORD", "secret")));
        assertTrue(result.issues().isEmpty());
        assertEquals(0, fake.probeCalls);
    }

    @Test void blankPasswordSkipsProbeSilently()
    {
        final var fake = new FakeGateway();
        final var result = new DbValidationTask(fake).run(ctx(Map.of("SOULNOTES_DB_USER", "kurv")));
        assertTrue(result.issues().isEmpty());
        assertEquals(0, fake.probeCalls);
    }

    @Test void structurallyIllegalUrlBlocksInsteadOfThrowing()
    {
        final var fake = new FakeGateway();
        final var result = new DbValidationTask(fake).run(ctx(Map.of(
            "SOULNOTES_DB_URL", "postgresql:///notes", "SOULNOTES_DB_USER", "kurv", "SOULNOTES_DB_PASSWORD", "secret")));
        assertEquals(1, result.issues().size());
        assertEquals(IPreLaunchTask.Level.BLOCK, result.issues().getFirst().level());
        assertEquals(0, fake.probeCalls);
    }
}
