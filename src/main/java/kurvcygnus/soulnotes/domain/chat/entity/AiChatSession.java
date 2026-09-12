package kurvcygnus.soulnotes.domain.chat.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.*;
import kurvcygnus.soulnotes.utils.JsonUtils;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>AI 对话 Session 实体</b>
 * <p>对应 {@code ai_chat_sessions} 表, {@code messages} 字段以 JSONB 存储对话历史.</p>
 * @since 1.0
 */
@Entity
@Table(
    name = "ai_chat_sessions",
    indexes = @Index(name = "idx_chat_sessions_user_id", columnList = "user_id")
)
public final class AiChatSession extends PanacheEntityBase
{
    //region 字段
    //! 使用 UUID 作为主键, 安全且适合作为会话 ID 直接返回给前端.
    @Id
    public UUID id;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Column(columnDefinition = "JSONB")
    public String messages;

    @Column(name = "warning_triggered", nullable = false)
    public boolean warningTriggered;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
    //endregion

    //region 消息操作
    /**
     * <span style="color: 95cc6d">向对话历史追加一条消息.</span>
     *
     * @param role    角色: "user" / "assistant"
     * @param content 消息内容
     */
    public void addMessage(@NotNull String role, @NotNull String content)
    {
        final var list = getMessageList();
        list.add(Map.of("role", role, "content", content));
        this.messages = JsonUtils.toJson(list);
        this.updatedAt = Instant.now();
    }

    /**
     * <span style="color: f84b4b">截断对话历史至最近 N 条, 避免 Token 超限.</span>
     *
     * @param maxMessages 保留的最大消息条数
     */
    public void truncate(int maxMessages)
    {
        final var list = getMessageList();
        if(list.size() > maxMessages)
        {
            //* 使用 new ArrayList<>(trimmed) 进行防御性复制, 消除 subList 可变切片导致的原 List 生命周期延长隐患.
            final var trimmed = list.subList(list.size() - maxMessages, list.size());
            this.messages = JsonUtils.toJson(new ArrayList<>(trimmed));
        }
        this.updatedAt = Instant.now();
    }
    //endregion

    //region 静态查询
    /**
     * <span style="color: 95cc6d">查询指定用户的所有会话 (按更新时间倒序).</span>
     *
     * @param userId 用户 ID
     * @return 会话列表
     */
    public static @NotNull Uni<List<AiChatSession>> findByUserId(@NotNull UUID userId) { return find("userId = ?1 ORDER BY updatedAt DESC", userId).list(); }
    //endregion

    //region JSON 辅助
    //* 使用 JsonUtils 的统一封装进行 JSON 序列化/反序列化.
    private @NotNull List<Map<String, String>> getMessageList()
    {
        if(messages == null || messages.isBlank())
            return new ArrayList<>();
        //* [[JsonUtils#parseJson]] 在失败时已包装为 [[RuntimeException]] 抛出, 此处无需再静默吞掉.
        return JsonUtils.parseJson(messages, new TypeReference<>() { });
    }
    //endregion
}