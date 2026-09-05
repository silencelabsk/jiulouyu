package com.fanqie.auto.core;

import com.fanqie.auto.TestFixtures;
import com.fanqie.auto.config.AutomationConfig;
import com.fanqie.auto.config.LocatorRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link StateDetector#detect(UiSnapshot)} 的离线单元测试。
 * <p>
 * <b>关键：detect() 是纯内存判定，不解引用 driver</b>——因此构造 StateDetector 时
 * driver 传 null 即可安全测试；logger 未注入（null）时 detect 内部有 null 保护。
 * 用录制的 fixture XML 回放，断言各页面结构被识别为正确的 {@link PageState}。
 * <p>
 * 重点验证阶段新增分支与优先级：
 * <ul>
 *   <li>含「跳过」的开屏 → SPLASH_AD，且<b>优先于</b>几何 AD_CLOSE_READY</li>
 *   <li>阅读页锚点 + 菜单文案 → READER_MENU</li>
 *   <li>节点极少 + 无锚点 + 无大面积 → APP_LAUNCHING</li>
 *   <li>大面积视频 + 无关闭特征 → AD_VIDEO_PLAYING</li>
 *   <li>右上角小 clickable → AD_CLOSE_READY（几何兜底）</li>
 *   <li>content-desc「关闭」→ AD_CLOSE_READY（语义优先于几何）</li>
 *   <li>书架/阅读页 resource-id 锚点 → BOOKSHELF / READER</li>
 *   <li>畸形/空/null 快照 → UNKNOWN</li>
 * </ul>
 */
class StateDetectorTest {

    private static StateDetector detector;

    @BeforeAll
    static void setUp() {
        AutomationConfig config = new AutomationConfig();
        LocatorRegistry locators = new LocatorRegistry();
        // driver 传 null：detect() 路径不解引用 driver，纯内存判定，离线安全
        detector = new StateDetector(null, config, locators);
        // 故意不调用 setLogger，验证 logger 为 null 时 detect 的 null 安全性
    }

    private PageState detectFixture(String fixtureName) {
        UiSnapshot snap = new UiSnapshot(TestFixtures.load(fixtureName),
                TestFixtures.SCREEN_WIDTH, TestFixtures.SCREEN_HEIGHT);
        return detector.detect(snap);
    }

    @Test
    @DisplayName("书架页 fixture → BOOKSHELF（resource-id 锚点，最高优先级）")
    void detect_bookshelf() {
        assertEquals(PageState.BOOKSHELF, detectFixture("bookshelf.xml"));
    }

    @Test
    @DisplayName("阅读页 fixture → READER（阅读锚点存在且无菜单文案）")
    void detect_reader() {
        assertEquals(PageState.READER, detectFixture("reader.xml"));
    }

    @Test
    @DisplayName("阅读页+菜单文案 fixture → READER_MENU（新增分支）")
    void detect_readerMenu() {
        assertEquals(PageState.READER_MENU, detectFixture("reader_menu.xml"));
    }

    @Test
    @DisplayName("开屏广告含「跳过」fixture → SPLASH_AD")
    void detect_splashAd() {
        assertEquals(PageState.SPLASH_AD, detectFixture("splash_ad.xml"));
    }

    @Test
    @DisplayName("优先级验证：开屏「跳过」文案优先于几何右上角 clickable → SPLASH_AD 而非 AD_CLOSE_READY")
    void detect_splashAdPriorityOverGeometry() {
        // splash_ad.xml 同时含右上角 clickable「跳过」按钮（几何规则会判 AD_CLOSE_READY），
        // 但 text 候选集在优先级 2、几何在优先级 5，故必须先命中 SPLASH_AD。
        UiSnapshot snap = new UiSnapshot(TestFixtures.load("splash_ad.xml"),
                TestFixtures.SCREEN_WIDTH, TestFixtures.SCREEN_HEIGHT);
        // 先确认几何特征确实存在（右上角有小 clickable 节点），否则本优先级断言无意义
        assertEquals(1, snap.findClickableInRegion(0.8, 1.0, 0.0, 0.2, 0.15).size(),
                "前置条件：splash 快照应存在右上角 clickable 节点");
        assertEquals(PageState.SPLASH_AD, detector.detect(snap),
                "SPLASH_AD 必须优先于几何 AD_CLOSE_READY");
    }

    @Test
    @DisplayName("广告视频大面积无关闭特征 fixture → AD_VIDEO_PLAYING")
    void detect_adVideoPlaying() {
        assertEquals(PageState.AD_VIDEO_PLAYING, detectFixture("ad_video_playing.xml"));
    }

    @Test
    @DisplayName("右上角小面积 clickable fixture → AD_CLOSE_READY（几何兜底）")
    void detect_adCloseReadyByGeometry() {
        assertEquals(PageState.AD_CLOSE_READY, detectFixture("ad_close_ready.xml"));
    }

    @Test
    @DisplayName("content-desc「关闭」fixture → AD_CLOSE_READY（语义优先级高于几何）")
    void detect_adCloseReadyByContentDesc() {
        // ad_close_by_desc.xml 的关闭按钮位于屏幕中央（非右上角），
        // 几何规则不会命中，只能靠 content-desc 候选集在优先级 3 识别。
        assertEquals(PageState.AD_CLOSE_READY, detectFixture("ad_close_by_desc.xml"));
    }

    @Test
    @DisplayName("节点极少+无锚点+无大面积 fixture → APP_LAUNCHING（保守判定，新增分支）")
    void detect_appLaunching() {
        assertEquals(PageState.APP_LAUNCHING, detectFixture("app_launching.xml"));
    }

    @Test
    @DisplayName("畸形 XML 快照 → UNKNOWN（parseFailed 兜底）")
    void detect_malformedSnapshot_returnsUnknown() {
        assertEquals(PageState.UNKNOWN, detectFixture("malformed.xml"));
    }

    @Test
    @DisplayName("null / 空节点快照 → UNKNOWN")
    void detect_nullOrEmpty_returnsUnknown() {
        assertEquals(PageState.UNKNOWN, detector.detect(null), "null 快照应返回 UNKNOWN");

        UiSnapshot empty = new UiSnapshot("", TestFixtures.SCREEN_WIDTH, TestFixtures.SCREEN_HEIGHT);
        assertEquals(PageState.UNKNOWN, detector.detect(empty), "空节点快照应返回 UNKNOWN");
    }
}
