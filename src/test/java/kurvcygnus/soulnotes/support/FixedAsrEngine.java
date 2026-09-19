package kurvcygnus.soulnotes.support;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import kurvcygnus.soulnotes.ai.asr.AsrResult;
import kurvcygnus.soulnotes.ai.asr.IAsrEngine;

import java.nio.file.Path;

/**
 * <b>固定转录文本的 ASR 替身 (测试基建)</b>
 * <p>以 CDI {@code @Alternative} 替换生产 {@code VoskAsrEngine} (MockLlmProfile 启用):
 * 转录不依赖本地模型与动态库, 文本固定供响应断言. 相比 QuarkusMock, 替身机制对
 * final 实现类无要求, 且替身随 profile 装配, 无逐用例安装步骤.</p>
 * @since 2.0
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class FixedAsrEngine implements IAsrEngine
{
    public static final String FIXED_TEXT = "窗外的雨停了";

    @Override public Uni<AsrResult> transcribe(Path wavFile) { return Uni.createFrom().item(AsrResult.ofText(FIXED_TEXT)); }

    @Override public String name() { return "fixed"; }
}
