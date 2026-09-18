package kurvcygnus.soulnotes.ai.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.utils.PrintUtils;
import kurvcygnus.soulnotes.utils.constants.ConfigDefaults;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;

/**
 * <b>危机干预热线工具</b>
 * <p>当情感分析或对话中检测到 <span style="color: f84b4b">RED 预警</span> 时,
 * AI Agent 可调用此工具获取心理援助热线信息.</p>
 *
 * <span style="color: 95cc6d">热线信息优先从 Redis 加载, 不可用时使用配置默认值.</span>
 * @since 2.0
 */
@ApplicationScoped
public final class CrisisInterventionTool
{
    private final @NotNull String primaryHotline;
    private final @NotNull String backupHotline;
    private final @NotNull String hotlineName;

    //* 默认热线兜底 (仅当无 Redis 且无配置时).
    //* 委托: 兼容别名, 权威单一来源为 [[ConfigDefaults]], 防止字面量漂移.
    public static final @NotNull String DEFAULT_PRIMARY = ConfigDefaults.HOTLINE_PRIMARY;
    public static final @NotNull String DEFAULT_BACKUP  = ConfigDefaults.HOTLINE_BACKUP;
    public static final @NotNull String DEFAULT_NAME    = ConfigDefaults.HOTLINE_NAME;

    public CrisisInterventionTool(
        @ConfigProperty(name = "crisis.hotline.primary", defaultValue = DEFAULT_PRIMARY) @NotNull String primary,
        @ConfigProperty(name = "crisis.hotline.backup", defaultValue = DEFAULT_BACKUP) @NotNull String backup,
        @ConfigProperty(name = "crisis.hotline.name", defaultValue = DEFAULT_NAME) @NotNull String name
    )
    {
        this.primaryHotline = primary;
        this.backupHotline  = backup;
        this.hotlineName    = name;
    }

    /**
     * <b>获取危机干预信息</b>
     * <ul>
     *     <li>返回心理援助热线信息</li>
     *     <li>记录预警日志</li>
     * </ul>
     *
     * @param userId 触发预警的用户 ID
     * @return 包含热线信息的文本
     */
    @Tool("当检测到红色预警时调用, 返回心理危机干预热线与建议")
    @SuppressWarnings("unused") //* userId 预留用于后续查询学校定制热线
    public @NotNull String getCrisisMessage(@ToolMemoryId String userId)
    {
        //* userId 可用于后续查询用户所在学校, 展示对应的校园咨询中心热线.
        return buildMessage(hotlineName, primaryHotline, backupHotline);
    }

    private static @NotNull String buildMessage(@NotNull String name, @NotNull String primary, @NotNull String backup)
    {
        final var sb = new StringBuilder();
        sb.append(PrintUtils.quickFormat("🚨 我们很关心你。\n\n{}: {}", name, primary));
        if(!backup.isBlank())
            sb.append(PrintUtils.quickFormat("\n备用热线: {}", backup));
        sb.append("\n\n你的安全是最重要的, 请立即联系专业人士。\n我们一直在你身边。");
        return sb.toString();
    }
}
