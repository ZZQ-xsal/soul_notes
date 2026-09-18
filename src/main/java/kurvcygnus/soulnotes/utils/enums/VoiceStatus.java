package kurvcygnus.soulnotes.utils.enums;

/**
 * <b>语音处理状态枚举</b>
 * <ul>
 *     <li>{@link #TRANSCRIBED} — 转录完成, transcribedText 已随响应返回 (静音空文本同属成功形态)</li>
 *     <li>{@link #FAILED} — 转录失败, message 携带原因 (离线安全网: 不转化为 5xx, 文字链路不受影响)</li>
 * </ul>
 * <p>//* 同步转录落地后仅此两态有消费方: PENDING/PROCESSED 系旧异步回调架构遗留, 已随 /asr-callback 拆除收敛 (零警告政策: 禁留未用枚举值).</p>
 * @since 1.0
 */
public enum VoiceStatus
{
    TRANSCRIBED,
    FAILED
}
