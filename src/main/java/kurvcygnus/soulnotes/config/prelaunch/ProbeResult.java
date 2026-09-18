package kurvcygnus.soulnotes.config.prelaunch;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 数据库探测结果: 五种互斥状态, 详见 {@link State}.
 *
 * @param state 探测结论
 * @param missingTables 缺失表清单; 仅 {@link State#SCHEMA_MISSING} 时非空, 其余状态恒为空列表
 * @since 1.1.0
 */
public record ProbeResult(@NotNull State state, @NotNull List<String> missingTables)
{
    public enum State
    {
        /** 全部就绪 (连通 + 四表齐全). */
        OK,
        /** 连接拒绝 / DNS 失败 / 连接超时 — 网络或实例层面不可达. */
        UNREACHABLE,
        /** 鉴权被拒: SQLSTATE 28P01 (invalid_password) / 28000 (invalid_authorization_specification). */
        AUTH_FAILED,
        /** 目标库不存在: SQLSTATE 3D000 (invalid_catalog_name). */
        DB_MISSING,
        /** 连接成功但期望表集不齐全, missingTables 携带缺表清单. */
        SCHEMA_MISSING
    }

    /** 紧凑构造: 防御性拷贝缺失表清单为不可变列表. */
    public ProbeResult
    {
        missingTables = List.copyOf(missingTables);
    }
}
