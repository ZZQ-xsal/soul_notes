package kurvcygnus.soulnotes.ai.asr;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * <b>ASR 转录结果</b>
 * <p>三态载体: 成功 (text 非空) / 合法空结果 (静音音频, text = "" 且 error = null) / 失败 (error 非空).</p>
 * <p>引擎侧永不抛异常的约定靠本类型落地: 一切失败都转成 error 文本,
 * 上层据此走降级分支而不触碰异常流 (Offline Safety Net).</p>
 *
 * @param text  转录文本 (失败时为 null, 静音时为空串)
 * @param error 失败原因 (成功时为 null)
 * @since 1.0
 */
public record AsrResult(@Nullable String text, @Nullable String error)
{
    /**
     * <span style="color: 95cc6d">是否为 "有可用文本" 的成功结果.</span>
     * <p>静音等合法空文本不算 success: 上游 LLM 管道对空文本应有独立分支, 与失败降级路径区分.</p>
     * @return error 为空且 text 非空白时为 true
     */
    public boolean success() { return error == null && text != null && !text.isBlank(); }

    /**
     * <span style="color: 95cc6d">构造成功结果.</span>
     * @param text 转录文本 (可为空串, 表示静音)
     */
    public static @NotNull AsrResult ofText(@Nullable String text) { return new AsrResult(text, null); }

    /**
     * <span style="color: f84b4b">构造失败结果.</span>
     * @param error 失败原因
     */
    public static @NotNull AsrResult ofError(@Nullable String error) { return new AsrResult(null, error); }
}
