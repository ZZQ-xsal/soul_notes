package kurvcygnus.soulnotes.websocket;

import io.quarkus.arc.All;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import kurvcygnus.soulnotes.support.InfraProbes;
import kurvcygnus.soulnotes.support.MockLlmProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>预警渠道 fan-out 装配证明</b> (真库): 五渠道 (websocket/webhook/sms/dingtalk/wecom) 必须被 CDI
 * 同时收集为 {@link IAlertNotifier} 列表 — 渠道即插即用的最终防线 (新增渠道漏标 @ApplicationScoped
 * 在此暴露为 fan-out 静默缺员).
 * <p>复用 {@link MockLlmProfile} 既有真库装配: 装配断言不发起 LLM 调用, mock 服务闲置无碍;
 * 基建守卫与全链路用例同源 (postgres + redis 双基建).</p>
 * @since 1.3.0
 */
@SuppressWarnings("NullableProblems")//! 测试模块不使用 JetBrains Annotations (项目测试惯例).
@QuarkusTest
@TestProfile(MockLlmProfile.class)
@EnabledIf(value = "pipelineInfraReachable", disabledReason = "本机 postgres/redis 未运行, 跳过五渠道装配真库用例")
class AlertChannelsWiringTest
{
    //* @EnabledIf 的引用方法必须落在被注解类内: QuarkusTest 类加载器下跨类全限定字符串解析失败 (ClinicalResourceTest 实测先例).
    static boolean pipelineInfraReachable() { return InfraProbes.pipelineInfraReachable(); }

    //* CDI 收集全部 IAlertNotifier 实现: 与 AlertDispatchService 的 fan-out 注入同构 — 装配缺员即预警分发静默缺员.
    //* @All 是 Arc 集合注入的必要限定符: 缺失时注入点退化为对 List 类型 bean 的普通解析 (AlertDispatchService 同款注释).
    @Inject @All List<IAlertNotifier> notifiers;

    @Test void fiveChannelsAreWired()
    {
        final var names = notifiers.stream().map(IAlertNotifier::channel).collect(Collectors.toSet());
        assertEquals(Set.of("websocket", "webhook", "sms", "dingtalk", "wecom"), names, "五渠道必须同时装配");
    }
}
