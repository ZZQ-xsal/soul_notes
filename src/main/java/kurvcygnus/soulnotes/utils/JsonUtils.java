package kurvcygnus.soulnotes.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * JSON 序列化/反序列化的统一静态入口, 基于 Quarkus 托管的 Jackson {@link ObjectMapper} 单例.
 * <p>实体类等非 CDI 组件无法实例注入 Mapper, 故由 {@code @Startup} 强制启动期创建本 Bean,
 * 将 Quarkus 托管 ObjectMapper 写入静态桥接字段, 供静态方法读取; 单元测试可经
 * {@link #JsonUtils(ObjectMapper)} 手动注入.</p>
 * @since 1.0
 */
@Startup
@ApplicationScoped
public final class JsonUtils
{
    //region 静态桥接

    //! 静态桥接: 由 Quarkus 启动期 (见类上 @Startup) 注入托管 ObjectMapper,
    //! 供实体类等非 CDI 组件静态调用; 单元测试直接 new JsonUtils(mapper) 初始化.
    private static volatile @Nullable ObjectMapper mapper;

    //endregion

    /**
     * CDI 构造注入入口: 启动期由 Quarkus 调用, 将托管 Mapper 写入静态桥接字段.
     * @param quarkusMapper Quarkus 容器托管的 ObjectMapper, 不得为 {@code null}
     */
    public JsonUtils(@NotNull ObjectMapper quarkusMapper) { mapper = quarkusMapper; }

    //region 序列化 / 反序列化

    /**
     * 将对象序列化为 JSON 字符串.
     * @param obj 待序列化对象, 不得为 {@code null}
     * @return JSON 文本, 永不为 {@code null}
     * @throws RuntimeException 底层 Jackson 序列化失败时抛出, cause 为 {@link JsonProcessingException},
     *                          消息携带目标类名
     * @since 1.0
     */
    public static @NotNull String toJson(@NotNull Object obj)
    {
        try { return requireMapper().writeValueAsString(obj); }
        catch(JsonProcessingException e) { throw new RuntimeException(PrintUtils.quickFormat("JSON 序列化失败: {}", obj.getClass().getName()), e); }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型.
     * @param json JSON 文本, 不得为 {@code null}
     * @param type 目标类型, 不得为 {@code null}
     * @param <T>  目标类型
     * @return 反序列化后的实例, 永不为 {@code null}
     * @throws RuntimeException 底层 Jackson 解析失败时抛出, cause 为 {@link JsonProcessingException},
     *                          消息携带目标类型名
     * @since 1.0
     */
    public static <T> @NotNull T parseJson(@NotNull String json, @NotNull Class<T> type)
    {
        try { return requireMapper().readValue(json, type); }
        catch(JsonProcessingException e) { throw new RuntimeException(PrintUtils.quickFormat("JSON 反序列化失败: {}", type.getName()), e); }
    }

    /**
     * 将 JSON 字符串反序列化为泛型类型 (如 {@code List<Map<String, String>>}).
     * @param json    JSON 文本, 不得为 {@code null}
     * @param typeRef 描述泛型目标类型的 {@link TypeReference}, 不得为 {@code null}
     * @param <T>     目标类型
     * @return 反序列化后的实例, 永不为 {@code null}
     * @throws RuntimeException 底层 Jackson 解析失败时抛出, cause 为 {@link JsonProcessingException},
     *                          消息携带目标类型描述
     * @since 1.0
     */
    public static <T> @NotNull T parseJson(@NotNull String json, @NotNull TypeReference<T> typeRef)
    {
        try { return requireMapper().readValue(json, typeRef); }
        catch(JsonProcessingException e) { throw new RuntimeException(PrintUtils.quickFormat("JSON 反序列化失败: {}", typeRef.getType()), e); }
    }

    //endregion

    //region 内部

    /**
     * 取当前桥接的 ObjectMapper, 未初始化时快速失败.
     * @return 已注入的托管 Mapper
     * @throws IllegalStateException 运行在非 Quarkus 环境且未手动注入 (如纯单元测试) 时
     */
    private static @NotNull ObjectMapper requireMapper()
    {
        final var current = mapper;
        //! 未初始化说明运行在非 Quarkus 环境且未手动注入 (如纯单元测试), 明确报错引导.
        if(current == null)
            throw new IllegalStateException("JsonUtils 未初始化: Quarkus 环境会自动注入, 单元测试请先 new JsonUtils(new ObjectMapper().registerModule(new JavaTimeModule()))");
        return current;
    }

    //endregion
}
