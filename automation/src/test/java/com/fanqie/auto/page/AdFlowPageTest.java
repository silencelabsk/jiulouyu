package com.fanqie.auto.page;

import com.fanqie.auto.TestFixtures;
import com.fanqie.auto.config.AutomationConfig;
import com.fanqie.auto.config.LocatorRegistry;
import com.fanqie.auto.core.UiSnapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AdFlowPage#readGainedMinutes(UiSnapshot)} 的离线单元测试。
 * <p>
 * <b>readGainedMinutes 只依赖 snapshot + locators.getRewardRegex()</b>，
 * 不解引用 driver / detector / gestures / logger，因此可用 null 依赖构造 AdFlowPage 离线测试。
 * <p>
 * 验证：
 * <ul>
 *   <li>奖励弹窗 fixture「30分钟免广告时长」→ 提取 30</li>
 *   <li>无「N分钟」文案的 fixture → 返回 0</li>
 *   <li>null 快照 → 返回 0（不抛异常）</li>
 * </ul>
 */
class AdFlowPageTest {

    private static AdFlowPage adFlowPage;

    @BeforeAll
    static void setUp() {
        AutomationConfig config = new AutomationConfig();
        LocatorRegistry locators = new LocatorRegistry();
        // readGainedMinutes 不触碰 driver/detector/gestures/logger/waitSupport，全部传 null 离线安全
        adFlowPage = new AdFlowPage(null, config, locators, null, null, null, null);
    }

    private UiSnapshot snapshotOf(String fixtureName) {
        return new UiSnapshot(TestFixtures.load(fixtureName),
                TestFixtures.SCREEN_WIDTH, TestFixtures.SCREEN_HEIGHT);
    }

    @Test
    @DisplayName("奖励弹窗含「30分钟」→ readGainedMinutes 返回 30")
    void readGainedMinutes_extractsThirty() {
        assertEquals(30, adFlowPage.readGainedMinutes(snapshotOf("reward_granted.xml")));
    }

    @Test
    @DisplayName("无「N分钟」文案的阅读页快照 → readGainedMinutes 返回 0")
    void readGainedMinutes_noNumberReturnsZero() {
        assertEquals(0, adFlowPage.readGainedMinutes(snapshotOf("reader.xml")));
    }

    @Test
    @DisplayName("null 快照 → readGainedMinutes 返回 0，不抛异常")
    void readGainedMinutes_nullSnapshotReturnsZero() {
        assertEquals(0, adFlowPage.readGainedMinutes(null));
    }

    @Test
    @DisplayName("畸形 XML 快照 → readGainedMinutes 返回 0（空节点表无匹配）")
    void readGainedMinutes_malformedReturnsZero() {
        assertEquals(0, adFlowPage.readGainedMinutes(snapshotOf("malformed.xml")));
    }
}
