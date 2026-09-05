package com.fanqie.auto.page;

import com.fanqie.auto.TestFixtures;
import com.fanqie.auto.config.AutomationConfig;
import com.fanqie.auto.config.LocatorRegistry;
import com.fanqie.auto.core.UiNode;
import com.fanqie.auto.core.UiSnapshot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BasePage} 四级定位降级链中<b>可离线验证</b>的两级：
 * L1 语义匹配 + boundsHint 裁决（{@link BasePage#resolve}）与
 * L2 结构化几何推断（{@link BasePage#resolveByGeometry}）。
 * <p>
 * <b>resolve / resolveByGeometry 均不解引用 driver / gestures / logger</b>，
 * 只做快照内存计算，因此可用 null 依赖构造 BasePage 离线测试。
 * arbitrate / parseBoundsHintRegion 为 private，通过其唯一公开调用路径
 * （resolve / resolveByGeometry）间接覆盖，避免脆弱的反射。
 * <p>
 * 重点验证 L2 的<b>误点防护</b>：allowGeometryFallback=false 的控件（adWatch）
 * 即使快照存在右上角 clickable 节点也绝不返回几何命中。
 */
class BasePageTest {

    private static BasePage page;
    private static LocatorRegistry locators;

    @BeforeAll
    static void setUp() {
        AutomationConfig config = new AutomationConfig();
        locators = new LocatorRegistry();
        // resolve/resolveByGeometry 不触碰 driver/gestures/logger，传 null 离线安全
        page = new BasePage(null, config, locators, null, null);
    }

    private UiSnapshot snapshotOf(String fixtureName) {
        return new UiSnapshot(TestFixtures.load(fixtureName),
                TestFixtures.SCREEN_WIDTH, TestFixtures.SCREEN_HEIGHT);
    }

    // ==================== L2 resolveByGeometry：误点防护 ====================

    @Test
    @DisplayName("L2 误点防护：adWatch(allowGeometryFallback=false) 即使存在右上角 clickable 也返回 empty")
    void resolveByGeometry_blockedWhenNotAllowed() {
        UiSnapshot snap = snapshotOf("ad_close_ready.xml");
        // 前置：该快照确实存在右上角 clickable 节点
        assertFalse(snap.findClickableInRegion(0.8, 1.0, 0.0, 0.2, 0.15).isEmpty());

        Optional<UiNode> result = page.resolveByGeometry(locators.getAdWatch(), snap);
        assertFalse(result.isPresent(), "adWatch 禁用几何兜底，必须返回 empty 防误点浪费配额");
    }

    @Test
    @DisplayName("L2 几何兜底：adClose(allowGeometryFallback=true) 命中右上角小面积 clickable 节点")
    void resolveByGeometry_hitsTopRightForAdClose() {
        UiSnapshot snap = snapshotOf("ad_close_ready.xml");
        Optional<UiNode> result = page.resolveByGeometry(locators.getAdClose(), snap);
        assertTrue(result.isPresent(), "adClose 允许几何兜底，应命中右上角关闭按钮");
        assertTrue(result.get().isClickable());
        assertTrue(result.get().getBounds().centerInRegion(0.8, 1.0, 0.0, 0.2, 1080, 2340));
    }

    @Test
    @DisplayName("L2 几何兜底：区域内无 clickable 节点时返回 empty（视频播放中）")
    void resolveByGeometry_noCandidateReturnsEmpty() {
        UiSnapshot snap = snapshotOf("ad_video_playing.xml");
        Optional<UiNode> result = page.resolveByGeometry(locators.getAdClose(), snap);
        assertFalse(result.isPresent(), "视频播放页无右上角 clickable，应返回 empty");
    }

    // ==================== L1 resolve：语义匹配 + boundsHint 裁决 ====================

    @Test
    @DisplayName("L1 content-desc 通道：adClose 命中 desc=「关闭」的节点（text 通道未命中时降级）")
    void resolve_byContentDescChannel() {
        UiSnapshot snap = snapshotOf("ad_close_by_desc.xml");
        Optional<UiNode> result = page.resolve(locators.getAdClose(), snap);
        assertTrue(result.isPresent());
        assertEquals("关闭", result.get().getContentDesc());
    }

    @Test
    @DisplayName("L1 boundsHint 右上角裁决：多个「关闭」命中时优先取右上角节点")
    void resolve_arbitrateTopRight() {
        UiSnapshot snap = snapshotOf("two_close_buttons.xml");
        Optional<UiNode> result = page.resolve(locators.getAdClose(), snap);
        assertTrue(result.isPresent());
        // 右上角节点 bounds=[980,120][1050,190]，中心 (1015,155)
        assertEquals(1015, result.get().getBounds().centerX(), "应裁决出右上角关闭按钮");
        assertEquals(155, result.get().getBounds().centerY());
    }

    @Test
    @DisplayName("L1 boundsHint 底部裁决：多个「书架」命中时优先取 y 最大的底部 Tab")
    void resolve_arbitrateBottom() {
        UiSnapshot snap = snapshotOf("two_shelf_tabs.xml");
        Optional<UiNode> result = page.resolve(locators.getShelfTab(), snap);
        assertTrue(result.isPresent());
        // 底部 Tab bounds=[100,2150][300,2230]，中心 y=2190
        assertEquals(2190, result.get().getBounds().centerY(), "应裁决出底部书架 Tab");
    }

    @Test
    @DisplayName("L1 全部通道未命中时返回 empty（阅读页无「关闭」文案）")
    void resolve_noMatchReturnsEmpty() {
        UiSnapshot snap = snapshotOf("reader.xml");
        assertFalse(page.resolve(locators.getAdClose(), snap).isPresent());
    }

    @Test
    @DisplayName("resolve 对 null / 畸形快照返回 empty，不抛异常")
    void resolve_nullOrMalformedSnapshotReturnsEmpty() {
        assertFalse(page.resolve(locators.getAdClose(), null).isPresent());
        assertFalse(page.resolve(locators.getAdClose(), snapshotOf("malformed.xml")).isPresent());
        assertFalse(page.resolveByGeometry(locators.getAdClose(), null).isPresent());
    }
}
