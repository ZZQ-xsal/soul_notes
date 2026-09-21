package kurvcygnus.soulnotes.domain.clinical;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.support.InfraProbes;
import kurvcygnus.soulnotes.support.MockLlmProfile;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>保留期清理边界</b> (真库): 0/负数禁用; 正数执行不抛 (删除行数由数据状态决定).
 * <p>//! 容器装配偏离 brief 的裸 {@code @QuarkusTest}: 测试资源默认禁用数据源
 * (test application.properties), 真库断言必须复用 {@link MockLlmProfile}
 * (项目内唯一 @QuarkusTest 真库装配, ClinicalResourceTest 同款) — 裸容器下
 * SessionFactory bean 缺席, positiveDays 用例必然失败.</p>
 * @since 1.2.0
 */
@SuppressWarnings("NullableProblems")//! 测试模块不使用 JetBrains Annotations (项目测试惯例).
@QuarkusTest
@TestProfile(MockLlmProfile.class)
@EnabledIf(value = "pipelineInfraReachable", disabledReason = "本机 postgres/redis 未运行, 跳过保留期清理真库用例")
class ClinicalRetentionCleanerTest
{
    //* @EnabledIf 的引用方法必须落在被注解类内: QuarkusTest 类加载器下跨类全限定字符串解析失败 (ChatPipelineTest 实测先例).
    static boolean pipelineInfraReachable() { return InfraProbes.pipelineInfraReachable(); }

    @Inject ClinicalRetentionCleaner cleaner;

    //* 真库前置: dev 库可能尚未应用 05 号迁移, 幂等确保 clinical_assessments 表存在 (ClinicalResourceTest 同款).
    @Inject Mutiny.SessionFactory sessionFactory;

    //* 实例方法而非 static @BeforeAll — 注入字段仅实例方法可见 (QuarkusTest 生命周期约束).
    @BeforeEach void ensureClinicalSchema()
    {
        final var ddls = schemaStatements("/db/schema/05_clinical_assessments.sql");
        sessionFactory.withSession(session ->
        {
            io.smallrye.mutiny.Uni<Void> chain = io.smallrye.mutiny.Uni.createFrom().voidItem();
            for(final var ddl: ddls)
                chain = chain.chain(v -> session.createNativeQuery(ddl).executeUpdate().replaceWithVoid());
            return chain;
        }).await().atMost(java.time.Duration.ofSeconds(20));
    }

    @Test void disabled_WhenDaysNonPositive()
    {
        assertEquals(0L, cleaner.cleanOnce(0).await().atMost(java.time.Duration.ofSeconds(5)));
        assertEquals(0L, cleaner.cleanOnce(-1).await().atMost(java.time.Duration.ofSeconds(5)));
    }

    @Test void positiveDays_RunsCleanWithoutError()
    {
        assertTrue(cleaner.cleanOnce(3650).await().atMost(java.time.Duration.ofSeconds(5)) >= 0);
    }

    //region 测试脚手架
    /**
     * 读取 classpath 下的建表脚本并拆为语句列表: 剥离 {@code --} 注释行, 按分号切分 (ClinicalResourceTest 同款).
     */
    private static List<String> schemaStatements(String resource)
    {
        try(var stream = Objects.requireNonNull(
                ClinicalRetentionCleanerTest.class.getResourceAsStream(resource),
                kurvcygnus.soulnotes.utils.PrintUtils.quickFormat("classpath 资源缺失: {}", resource));
            var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)))
        {
            final var sql = reader.lines().
                filter(line -> !line.strip().startsWith("--")).
                reduce("", (left, right) -> left + "\n" + right);
            final var statements = new ArrayList<String>();
            for(final var part: sql.split(";"))
            {
                if(!part.isBlank())
                    statements.add(part.strip());
            }
            return statements;
        }
        catch(IOException e) { throw new IllegalStateException(kurvcygnus.soulnotes.utils.PrintUtils.quickFormat("schema 脚本读取失败: {}", resource), e); }
    }
    //endregion
}
