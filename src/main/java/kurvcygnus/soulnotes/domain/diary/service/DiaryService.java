package kurvcygnus.soulnotes.domain.diary.service;

import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import kurvcygnus.soulnotes.domain.diary.dto.DiaryCreateRequest;
import kurvcygnus.soulnotes.domain.diary.dto.DiaryListQuery;
import kurvcygnus.soulnotes.domain.diary.dto.DiaryResponse;
import kurvcygnus.soulnotes.domain.diary.entity.MoodDiary;
import kurvcygnus.soulnotes.domain.voice.service.VoiceStorageService;
import kurvcygnus.soulnotes.exception.ErrorCode;
import kurvcygnus.soulnotes.exception.IBusinessException;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 日记业务服务, 承载日记的创建/查询/删除.
 * <ul>
 *     <li>创建日记 (写入 DB → 触发 AI 分析)</li>
 *     <li>分页查询、单条查询、删除 (校验归属)</li>
 *     <li>语音来源: audioData(Base64) 解码落盘并生成 audioUrl</li>
 * </ul>
 *
 * @implNote AI 分析失败不影响日记创建本身 (分析失败仅日志并降级跳过 analysisResult 回写),
 *           保证记录行为永远成功 — 情绪记录是主链路, 分析是增值链路.
 * @since 1.0
 */
@ApplicationScoped
public final class  DiaryService
{
    private final @NotNull EmotionAnalysisService emotionAnalysisService;
    private final @NotNull VoiceStorageService voiceStorageService;

    public DiaryService(
        @NotNull EmotionAnalysisService emotionAnalysisService,
        @NotNull VoiceStorageService voiceStorageService
    )
    {
        this.emotionAnalysisService = emotionAnalysisService;
        this.voiceStorageService    = voiceStorageService;
    }

    //region 核心业务
    /**
     * 创建日记并触发 AI 情感分析.
     * <p>流程: 参数校验 → (语音来源时 Base64 解码落盘生成 audioUrl) → 持久化日记 →
     * 执行情感分析与预警检测并回写 {@code analysisResult}; DB 操作在同一事务内
     * (语音落盘为文件 I/O, 不参与事务).</p>
     *
     * @param req    创建请求
     * @param userId 当前认证用户 ID
     * @return 创建的日记响应 (AI 分析失败时 analysisResult 为 null, 创建本身不受影响)
     * @throws IBusinessException content 与 audioData 均为空 (BAD_REQUEST),
     *                            或 audioData 非法 Base64 (BAD_REQUEST) 时
     */
    @WithTransaction
    public @NotNull Uni<DiaryResponse> create(@NotNull DiaryCreateRequest req, @NotNull UUID userId)
    {
        if(req.content() == null && req.audioData() == null)
            return Uni.createFrom().failure(
                IBusinessException.of(
                    ErrorCode.BAD_REQUEST,
                    "日记内容不能为空: 至少提供 content 或 audioData 一项",
                    IllegalArgumentException::new,
                    "DIARY_CREATE_BOTH_NULL"
                ).asException()
            );

        final var diary = new MoodDiary();
        diary.userId    = userId;
        diary.content   = req.content();
        diary.createdAt = Instant.now();

        return resolveAudioUrl(req).
            flatMap(
                audioUrl ->
                {
                    diary.audioUrl = audioUrl;
                    return diary.persistAndFlush().
                        flatMap(_ -> analyzeAndDetect(diary)).
                        map(DiaryResponse::fromEntity);
                }
            );
    }

    /**
     * 分页查询用户日记列表 (创建时间倒序).
     *
     * @param query  分页查询参数 (消费前经 {@link DiaryListQuery#normalize} 收敛: 缺席/越界值钳位, 最小 1)
     * @param userId 当前认证用户 ID
     * @return 当前页日记响应列表 (可能为空)
     */
    @WithTransaction
    public @NotNull Uni<List<DiaryResponse>> listByUser(
        @NotNull DiaryListQuery query,
        @NotNull UUID userId
    )
    {
        //* @BeanParam 参数值直写字段不走 setter 钳位, 消费前必须 normalize 收敛 (PageRequest#normalize 同款).
        final var normalized = query.normalize();
        return MoodDiary.findByUserId(userId).page(normalized.getPage() - 1, normalized.getSize()).list().
            map(
                list -> list.stream().
                    map(DiaryResponse::fromEntity).
                    toList()
            );
    }

    /**
     * 查询单条日记详情, 校验用户归属.
     *
     * @param id     日记 ID
     * @param userId 当前认证用户 ID (归属校验依据)
     * @return 日记响应
     * @throws IBusinessException 日记不存在或不属于该用户时 (均为 DIARY_NOT_FOUND,
     *                            越权与缺失同一错误码, 不泄露他人日记的存在性)
     */
    @SuppressWarnings("JavadocDeclaration") @WithTransaction
    public @NotNull Uni<DiaryResponse> getById(long id, @NotNull UUID userId)
    {
        return MoodDiary.findById(id).
            onItem().
            ifNull().failWith(
                () -> IBusinessException.of(
                    ErrorCode.DIARY_NOT_FOUND,
                    "日记不存在",
                    NoSuchElementException::new,
                    "DIARY_READ_RECORD_NOT_FOUND"
                ).asException()
            ).
            map(MoodDiary.class::cast).
            flatMap(
                diary -> !diary.userId.equals(userId) ?
                    Uni.createFrom().failure(
                        IBusinessException.of(
                            ErrorCode.DIARY_NOT_FOUND,
                            "无权访问此日记",
                            IllegalStateException::new,
                            "DIARY_READ_ACCESS_DENIED"
                        ).asException()
                    ) :
                    Uni.createFrom().item(DiaryResponse.fromEntity(diary))
            );
    }

    /**
     * 删除日记 (硬删除), 校验用户归属.
     *
     * @param id     日记 ID
     * @param userId 当前认证用户 ID (归属校验依据)
     * @return 完成信号
     * @throws IBusinessException 日记不存在或不属于该用户时 (均为 DIARY_NOT_FOUND)
     */
    @WithTransaction
    public @NotNull Uni<Void> delete(long id, @NotNull UUID userId)
    {
        return MoodDiary.<MoodDiary>findById(id).
            onItem().
            ifNull().failWith(
                () -> IBusinessException.of(
                    ErrorCode.DIARY_NOT_FOUND,
                    "日记不存在",
                    NoSuchElementException::new,
                    "DIARY_DELETE_RECORD_NOT_FOUND"
                ).asException()
            ).
            flatMap(
                diary -> !diary.userId.equals(userId) ?
                    Uni.createFrom().failure(
                        IBusinessException.of(
                            ErrorCode.DIARY_NOT_FOUND,
                            "无权删除此日记",
                            IllegalStateException::new,
                            "DIARY_DELETE_ACCESS_DENIED"
                        ).asException()
                    ) :
                    diary.delete()
            );
    }
    //endregion

    //region AI 分析
    //* 委托 EmotionAnalysisService 执行 AI 情感分析与预警检测.
    private @NotNull Uni<MoodDiary> analyzeAndDetect(@NotNull MoodDiary diary) { return emotionAnalysisService.analyzeAsync(diary); }
    //endregion

    //region 语音处理
    //* 解码 audioData(Base64) 并落盘, 返回生成的 audioUrl; 无语音数据时返回 null.
    private @NotNull Uni<String> resolveAudioUrl(@NotNull DiaryCreateRequest req)
    {
        if(req.audioData() == null || req.audioData().isBlank())
            return Uni.createFrom().nullItem();
        try
        {
            final var bytes = Base64.getDecoder().decode(req.audioData());
            return voiceStorageService.store(
                "diary-" + UUID.randomUUID() + ".m4a",
                new ByteArrayInputStream(bytes)
            ).map(stored -> VoiceStorageService.audioUrlOf(stored.fileId()));
        }
        catch(IllegalArgumentException e)
        {
            throw IBusinessException.of(
                ErrorCode.BAD_REQUEST,
                "audioData 不是合法的 Base64 编码",
                IllegalArgumentException::new,
                "DIARY_CREATE_INVALID_BASE64"
            ).asException();
        }
    }
    //endregion
}