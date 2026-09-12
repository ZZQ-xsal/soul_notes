package kurvcygnus.soulnotes.utils.enums;

/**
 * <b>语音处理状态枚举</b>
 * <ul>
 *     <li>{@link #PENDING} — 待处理, 语音上传成功但 ASR 尚未完成</li>
 *     <li>{@link #PROCESSED} — 已完成, ASR 转录成功</li>
 *     <li>{@link #FAILED} — 失败, 转录出错或文件无效</li>
 * </ul>
 * @since 1.0
 */
public enum VoiceStatus
{
    PENDING,
    PROCESSED,
    FAILED
}
