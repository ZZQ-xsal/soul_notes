package kurvcygnus.soulnotes.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * <b>JSON 工具类</b>
 * <p>基于 Jackson {@link ObjectMapper} 的单例封装, 统一项目中 JSON 序列化/反序列化入口.</p>
 * <p>实体类等非 CDI 组件无法实例注入, 故由 {@code @Startup} 强制启动期创建本 Bean,
 * 将 Quarkus 托管 ObjectMapper 写入静态桥接字段, 供静态方法读取.</p>
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

    public JsonUtils(@NotNull ObjectMapper quarkusMapper) { mapper = quarkusMapper; }

    //region 序列化 / 反序列化

    /**
     * <span style="color: 95cc6d">将对象序列化为 JSON 字符串.</span>
     */
    public static @NotNull String toJson(@NotNull Object obj)
    {
        try { return requireMapper().writeValueAsString(obj); }
        catch(JsonProcessingException e) { throw new RuntimeException("JSON 序列化失败: " + obj.getClass().getName(), e); }
    }

    /**
     * <span style="color: 95cc6d">将 JSON 字符串反序列化为指定类型.</span>
     */
    public static <T> @NotNull T parseJson(@NotNull String json, @NotNull Class<T> type)
    {
        try { return requireMapper().readValue(json, type); }
        catch(JsonProcessingException e) { throw new RuntimeException("JSON 反序列化失败: " + type.getName(), e); }
    }

    /**
     * <span style="color: 95cc6d">将 JSON 字符串反序列化为泛型类型 (如 {@code List<Map<String, String>>}).</span>
     */
    public static <T> @NotNull T parseJson(@NotNull String json, @NotNull TypeReference<T> typeRef)
    {
        try { return requireMapper().readValue(json, typeRef); }
        catch(JsonProcessingException e) { throw new RuntimeException("JSON 反序列化失败: " + typeRef.getType(), e); }
    }

    //endregion

    //region 内部

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
