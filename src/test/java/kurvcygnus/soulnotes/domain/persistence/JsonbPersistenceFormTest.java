package kurvcygnus.soulnotes.domain.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Uni;
import kurvcygnus.soulnotes.domain.chat.entity.AiChatSession;
import kurvcygnus.soulnotes.domain.diary.entity.MoodDiary;
import kurvcygnus.soulnotes.support.InfraProbes;
import kurvcygnus.soulnotes.utils.JsonUtils;
import kurvcygnus.soulnotes.utils.PrintUtils;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.bytecode.internal.none.BytecodeProviderImpl;
import org.hibernate.bytecode.spi.BytecodeProvider;
import org.hibernate.reactive.mutiny.Mutiny;
import org.hibernate.reactive.pool.ReactiveConnectionPool;
import org.hibernate.reactive.provider.ReactiveServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>JSONB 持久化形态真库验证</b>
 * <p>依赖本机 postgres 开发容器 (与 PgGatewayTest 同源的本地凭据), CI 等无库环境经 {@code assumeTrue} 整类跳过.</p>
 * <p>验证两层语义: 新写入必须落为真 JSON (jsonb_typeof = array/object), 而非字符串标量的双重编码形态;
 * 存量字符串标量行读取后仍是可被 JsonUtils 解析的 JSON 文本 (迁移安全, 不做数据回填).</p>
 * <p>所有用例在单事务内完成写入与断言并以 markForRollback 收尾, 对开发库零足迹.</p>
 * @since 1.1.0
 */
class JsonbPersistenceFormTest
{
    //region 基建
    private static final String HOST = "localhost";
    private static final int PORT = 5432;
    private static final String DB = "soulnotes";
    private static final String USER = "kurv";
    //* 真机口令经环境变量注入, 兜底为公开占位符: 本机口令曾随仓库泄露并已全历史去敏,
    //! 任何硬编码真值不允许回归; 本机跑真机用例前 export SOULNOTES_DB_PASSWORD 对齐即可.
    private static final String PASSWORD =
        Objects.requireNonNullElse(System.getenv("SOULNOTES_DB_PASSWORD"), "soulnotes_dev");

    private static Mutiny.SessionFactory factory;

    @BeforeAll
    @SuppressWarnings("InstantiationOfUtilityClass")//! JsonUtils 为 final 全静态成员类, IDE 误报实例化; 构造器正是 CDI 桥接注入入口.
    static void bootStandaloneReactiveFactory()
    {
        //* 判定逻辑收编至测试源公共工具 (与 @EnabledIf 全链路守卫同源), 500ms 短超时只判端口有无监听者.
        assumeTrue(InfraProbes.postgresReachable(), "本机 postgres 未运行, 跳过 JSONB 持久化形态真库用例");

        //* 纯测试环境无 CDI 容器, 手动构造与生产等价的 mapper (含 JavaTimeModule).
        new JsonUtils(new ObjectMapper().registerModule(new JavaTimeModule()));

        final var settings = new HashMap<String, Object>();
        //? HR 3.x 连接池仅读取传统键名 (hibernate.connection.*), ORM 中对应常量已标废弃, 故用字面量规避告警.
        settings.put("hibernate.connection.url", PrintUtils.quickFormat("postgresql://{}:{}/{}", HOST, PORT, DB));
        settings.put("hibernate.connection.username", USER);
        settings.put("hibernate.connection.password", PASSWORD);

        //* 独立反应式引导: 显式声明实体映射, 复用生产实体的注解形态, 不触碰 Quarkus 运行时.
        final var registry = new ReactiveServiceRegistryBuilder().
            applySettings(settings).
            //? Quarkus 运行时类路径无 byte-buddy (增强已在构建期代劳), 直接注入 no-op 字节码提供者绕过服务发现.
            addService(BytecodeProvider.class, new BytecodeProviderImpl()).
            build();

        //* 独立引导下 JdbcEnvironment 构建早于服务批量启动, 须先取一次连接池服务以触发其 start 生命周期.
        registry.getService(ReactiveConnectionPool.class);

        @SuppressWarnings("resource")//! 工厂生命周期归 JUnit 管理: @AfterAll 统一关闭, 无法纳入 try-with-resources.
        final SessionFactory sessionFactory = new MetadataSources(registry).
            addAnnotatedClass(AiChatSession.class).
            addAnnotatedClass(MoodDiary.class).
            buildMetadata().
            getSessionFactoryBuilder().
            build();

        factory = sessionFactory.unwrap(Mutiny.SessionFactory.class);
    }

    @AfterAll
    static void closeFactory()
    {
        if(factory == null)
            return;

        factory.close();
    }
    //endregion

    //region 新写入形态
    @Test
    void freshSession_Messages_ShouldPersistAsTrueJsonArray()
    {
        final var sessionId = UUID.randomUUID();
        final var messagesJson = JsonUtils.toJson(List.of(Map.of("role", "user", "content", "陪我聊聊好吗")));

        final var userId = UUID.randomUUID();

        factory.withTransaction((session, transaction) ->
            {
                final var entity = new AiChatSession();
                entity.id = sessionId;
                entity.userId = userId;
                entity.messages = messagesJson;
                entity.warningTriggered = false;
                entity.updatedAt = Instant.now();

                return insertTempUser(session, userId).
                    chain(_ -> session.persist(entity)).
                    chain(session::flush).
                    chain(() -> jsonbTypeOfSessionMessages(session, sessionId)).
                    invoke(storedType -> assertEquals(
                        "array", storedType,
                        PrintUtils.quickFormat("新写入的 messages 应为真 JSON 数组, 实际 jsonb_typeof = {}", storedType)
                    )).
                    //* 清除一级缓存后再读: 托管实例会绕过数据库读取器, 读写回路断言将空转.
                    invoke(_ -> session.clear()).
                    chain(() -> session.find(AiChatSession.class, sessionId)).
                    invoke(loaded ->
                    {
                        assertNotNull(loaded, "新写入的会话应可读回 (清除一级缓存后须真正走数据库读取器)");
                        //* jsonb 会把对象键按 "键长优先+字节序" 归一化, 精确文本比对必失败, 断言语义等值.
                        final var expected = JsonUtils.parseJson(messagesJson, new TypeReference<List<Map<String, String>>>() { });
                        final var actual = JsonUtils.parseJson(loaded.messages, new TypeReference<List<Map<String, String>>>() { });
                        assertEquals(expected, actual, "读写回路: 数组保序且对象键序归一化后, 语义应等值");
                    }).
                    invoke(_ -> transaction.markForRollback());
            }
        ).await().indefinitely();
    }

    @Test
    void freshDiary_AnalysisResult_ShouldPersistAsTrueJsonObject()
    {
        final var analysisJson = "{\"positive\":0.8,\"negative\":0.2}";

        final var userId = UUID.randomUUID();

        factory.withTransaction((session, transaction) ->
            {
                final var diary = new MoodDiary();
                diary.userId = userId;
                diary.content = "今天心情不错";
                diary.analysisResult = analysisJson;
                diary.createdAt = Instant.now();

                return insertTempUser(session, userId).
                    chain(_ -> session.persist(diary)).
                    chain(session::flush).
                    chain(() -> jsonbTypeOfDiaryAnalysis(session, diary.id)).
                    invoke(storedType -> assertEquals(
                        "object", storedType,
                        PrintUtils.quickFormat("新写入的 analysisResult 应为真 JSON 对象, 实际 jsonb_typeof = {}", storedType)
                    )).
                    //* 清除一级缓存后再读: 托管实例会绕过数据库读取器, 读写回路断言将空转.
                    invoke(_ -> session.clear()).
                    chain(() -> session.find(MoodDiary.class, diary.id)).
                    invoke(loaded ->
                    {
                        assertNotNull(loaded, "新写入的日记应可读回 (清除一级缓存后须真正走数据库读取器)");
                        //* jsonb 会把对象键按 "键长优先+字节序" 归一化, 精确文本比对必失败, 断言语义等值.
                        final var expected = JsonUtils.parseJson(analysisJson, new TypeReference<Map<String, Object>>() { });
                        final var actual = JsonUtils.parseJson(loaded.analysisResult, new TypeReference<Map<String, Object>>() { });
                        assertEquals(expected, actual, "读写回路: 对象键序归一化后, 语义应等值");
                    }).
                    invoke(_ -> transaction.markForRollback());
            }
        ).await().indefinitely();
    }
    //endregion

    //region 存量兼容
    @Test
    void legacyStringScalarRow_Messages_ShouldStillReadAsParseableText()
    {
        final var sessionId = UUID.randomUUID();
        final var tempUserId = UUID.randomUUID();
        final var legacyMessagesJson = JsonUtils.toJson(List.of(Map.of("role", "user", "content", "历史会话")));

        factory.withTransaction((session, transaction) ->
            {
                final var insertUser = session.createNativeQuery(
                    "insert into users (id, user_name, password_hash, role) values (?1, ?2, ?3, ?4)"
                ).
                    setParameter(1, tempUserId).
                    setParameter(2, PrintUtils.quickFormat("jsonb-legacy-{}", sessionId)).
                    setParameter(3, "not-a-real-hash").
                    setParameter(4, "student").
                    executeUpdate();

                //* to_jsonb(text) 把 JSON 文本包成字符串标量, 精确模拟注解引入前的双重编码存量行.
                //* CAST 写法而非 ?3::text: HR 对原生 SQL 的序号参数解析无法处理参数后的冒号强制转型.
                final var insertSession = session.createNativeQuery(
                    "insert into ai_chat_sessions (id, user_id, messages, warning_triggered, updated_at) values (?1, ?2, to_jsonb(CAST(?3 AS text)), false, ?4)"
                ).
                    setParameter(1, sessionId).
                    setParameter(2, tempUserId).
                    setParameter(3, legacyMessagesJson).
                    setParameter(4, LocalDateTime.now()).
                    executeUpdate();

                return insertUser.
                    chain(_ -> insertSession).
                    chain(_ -> session.find(AiChatSession.class, sessionId)).
                    invoke(loaded ->
                    {
                        assertNotNull(loaded, "存量字符串标量行应可读出");
                        //* 标量行是逐字节保真的原文 (非 jsonb 对象, 不受键序归一化影响), 精确相等即迁移安全契约本身.
                        assertEquals(legacyMessagesJson, loaded.messages, "存量行读出文本应与写入时一致");
                        final var parsed = JsonUtils.parseJson(loaded.messages, new TypeReference<List<Map<String, String>>>() { });
                        assertEquals(1, parsed.size(), "存量文本应可被 JsonUtils 解析");
                    }).
                    invoke(_ -> transaction.markForRollback());
            }
        ).await().indefinitely();
    }
    //endregion

    //region 辅助
    //* 实体表均外键引用 users, 事务内造临时用户满足约束, 随事务回滚不留痕迹.
    private static Uni<Void> insertTempUser(Mutiny.Session session, UUID userId)
    {
        return session.createNativeQuery("insert into users (id, user_name, password_hash, role) values (?1, ?2, ?3, ?4)").
            setParameter(1, userId).
            setParameter(2, PrintUtils.quickFormat("jsonb-form-{}", userId)).
            setParameter(3, "not-a-real-hash").
            setParameter(4, "student").
            executeUpdate().
            map(_ -> null);
    }

    //* 原生查询直探数据库真实存储形态, 与实体映射完全解耦.
    private static Uni<String> jsonbTypeOfSessionMessages(Mutiny.Session session, UUID sessionId)
    {
        return session.createNativeQuery("select jsonb_typeof(messages) from ai_chat_sessions where id = ?1", String.class).
            setParameter(1, sessionId).
            getSingleResultOrNull();
    }

    private static Uni<String> jsonbTypeOfDiaryAnalysis(Mutiny.Session session, Long diaryId)
    {
        return session.createNativeQuery("select jsonb_typeof(analysis_result) from mood_diaries where id = ?1", String.class).
            setParameter(1, diaryId).
            getSingleResultOrNull();
    }
    //endregion
}
