package kurvcygnus.soulnotes.config;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.BasicBinder;
import org.hibernate.type.descriptor.jdbc.BasicExtractor;
import org.hibernate.type.descriptor.jdbc.JsonJdbcType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * <b>字符串型 JSONB 映射</b>
 * <p>实体字段以 String 承载 JSON 文本: 写入时按首字符分派为 Vert.x JSON 结构 (array/object),
 * 客户端据此以 jsonb 形态发送参数, 使 PostgreSQL 落为真 JSON, 而非把文本再包一层字符串标量的双重编码形态;
 * 读取时把 jsonb 的 array/object 编码回 JSON 文本, 字符串标量行原样读出, 保证存量数据可继续被 JsonUtils 解析.</p>
 * <p>为何不复用 Hibernate Reactive 内建实现 (@JdbcTypeCode(SqlTypes.JSON) 的默认 ReactiveJsonJdbcType):
 * 其绑定与读取均假定 jsonb 为 object, 数组写入会触发 JsonObject 解析失败, 字符串标量读取会发生强转异常.</p>
 * <p>绑定依赖 Vert.x 客户端对 JsonObject/JsonArray 参数的 jsonb 编码, 仅适用于 PostgreSQL.</p>
 * @since 2.0
 */
@RegisterForReflection//! native 下 @JdbcType 经 ManagedBean 反射实例化该类需要注册 (先例: DiaryResponse).
public final class ReactiveJsonStringJdbcType extends JsonJdbcType
{
    //* 供 @JdbcType 注解经 ManagedBean 路径反射实例化, 必须保留公开无参构造器.
    public ReactiveJsonStringJdbcType() { super(null); }

    //region 绑定 (写入)
    @Override
    public <X> ValueBinder<X> getBinder(JavaType<X> javaType)
    {
        return new BasicBinder<>(javaType, this)
        {
            @Override
            protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options) throws SQLException
            {
                st.setObject(index, encodeToJson(javaType.unwrap(value, String.class, options)));
            }

            @Override
            protected void doBind(CallableStatement st, X value, String name, WrapperOptions options) throws SQLException
            {
                st.setObject(name, encodeToJson(javaType.unwrap(value, String.class, options)));
            }
        };
    }
    //endregion

    //region 读取
    @Override
    public <X> ValueExtractor<X> getExtractor(JavaType<X> javaType)
    {
        return new BasicExtractor<>(javaType, this)
        {
            @Override
            protected X doExtract(ResultSet rs, int paramIndex, WrapperOptions options) throws SQLException
            {
                return fromString(decodeToJsonText(rs.getObject(paramIndex)), getJavaType(), options);
            }

            @Override
            protected X doExtract(CallableStatement statement, int index, WrapperOptions options) throws SQLException
            {
                return fromString(decodeToJsonText(statement.getObject(index)), getJavaType(), options);
            }

            @Override
            protected X doExtract(CallableStatement statement, String name, WrapperOptions options) throws SQLException
            {
                return fromString(decodeToJsonText(statement.getObject(name)), getJavaType(), options);
            }
        };
    }
    //endregion

    //region 编解码
    //* 非数组/对象的 JSON 标量文本走原始 String, 由客户端按标量形态落库, 语义与 Jackson 序列化结果一致.
    private static @NotNull Object encodeToJson(@NotNull String text)
    {
        final var trimmed = text.stripLeading();
        if(trimmed.startsWith("[")) { return new JsonArray(trimmed); }
        if(trimmed.startsWith("{")) { return new JsonObject(trimmed); }
        return text;
    }

    //* 字符串标量行由 Vert.x 解码为内部原文, 必须原样返回而非再编码, 否则存量文本会带上引号无法解析.
    private static @Nullable String decodeToJsonText(@Nullable Object value)
    {
        return switch(value)
        {
            case null -> null;
            case JsonObject object -> object.encode();
            case JsonArray array -> array.encode();
            default -> String.valueOf(value);
        };
    }
    //endregion
}
