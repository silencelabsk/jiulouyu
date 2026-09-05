package com.fanqie.auto.core;

import com.fanqie.auto.TestFixtures;
import com.fanqie.auto.config.AutomationConfig;
import com.fanqie.auto.config.LocatorRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link StateDetector#detect(UiSnapshot)} 的离线单元测试。
 * <p>
 * <b>关键：detect() 是纯内存判定，不解引用 driver</b>——因此构造 StateDetector 时
 * driver 传 null 即可安全测试；logger 未注入（null）时 detect 内部有 null 保护。
 * 用录制的 fixture XML 回放，断言各页面结构被识别为正确的 {@link PageState}。
 * <p>
 * 重点验证阶段新增分支与优先级：
 * <ul>
 *   <li>真正全屏开屏广告（全屏大面积 + 开屏专有「跳过广告」 + 无右上角关闭）→ SPLASH_AD</li>
 *   <li>带「跳过」文案但存在右上角关闭 clickable / 无大面积 → AD_CLOSE_READY（C1 排他）</li>
 *   <li>阅读页锚点 + 菜单文案 → READER_MENU；纯文案兜底需大面积正文（C3.5 收窄）</li>
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
    @DisplayName("阅读页+菜单文案 fixture → READER_MENU（resource-id 主分支）")
    void detect_readerMenu() {
        assertEquals(PageState.READER_MENU, detectFixture("reader_menu.xml"));
    }

    @Test
    @DisplayName("C3.5：无阅读页锚点但大面积正文 + 菜单文案 → READER_MENU（纯文案兜底收窄正例）")
    void detect_readerMenuTextOnly_withLargeArea() {
        assertEquals(PageState.READER_MENU, detectFixture("reader_menu_text_only.xml"));
    }

    @Test
    @DisplayName("C3.5：菜单文案但无大面积正文 → 非 READER_MENU（纯文案兜底收窄反例，防书架顶栏「设置」误判）")
    void detect_readerMenuText_noLargeArea_isNotReaderMenu() {
        assertNotEquals(PageState.READER_MENU, detectFixture("menu_text_no_large_area.xml"),
                "无大面积正文时不应仅凭菜单文案判为 READER_MENU");
    }

    @Test
    @DisplayName("真正的冷启动全屏开屏广告 fixture → SPLASH_AD（全屏大面积 + 开屏专有文案 + 无右上角关闭）")
    void detect_splashAd() {
        // C1 修复后：splash_ad.xml 为真正开屏广告——全屏大面积节点 + 开屏专有「跳过广告」文案 +
        // 跳过按钮位于右下角（非右上角关闭热区），三个排他条件全部满足才判 SPLASH_AD。
        assertEquals(PageState.SPLASH_AD, detectFixture("splash_ad.xml"));
    }

    @Test
    @DisplayName("C1：带裸「跳过」文案且右上角存在关闭 clickable → AD_CLOSE_READY 而非 SPLASH_AD")
    void detect_skipTextWithTopRightClose_isAdCloseReadyNotSplash() {
        // 这正是 C1 修复的核心缺陷场景：可跳过激励视频/广告关闭页带「跳过」文案 + 右上角关闭按钮。
        // 旧实现会因 splashSkipTexts 命中裸「跳过」而误判 SPLASH_AD（抢占 AD_CLOSE_READY）；
        // 修复后裸「跳过」归 ad.close.text，且右上角关闭特征使 SPLASH_AD 排他条件不成立。
        UiSnapshot snap = new UiSnapshot(TestFixtures.load("ad_close_with_skip_text.xml"),
                TestFixtures.SCREEN_WIDTH, TestFixtures.SCREEN_HEIGHT);
        // 前置条件：确实存在右上角小 clickable 关闭特征，否则本用例无意义
        assertEquals(1, snap.findClickableInRegion(0.8, 1.0, 0.0, 0.2, 0.15).size(),
                "前置条件：快照应存在右上角关闭 clickable 节点");
        assertEquals(PageState.AD_CLOSE_READY, detector.detect(snap),
                "带跳过文案但存在右上角关闭按钮时应判 AD_CLOSE_READY，绝不能误判 SPLASH_AD");
    }

    @Test
    @DisplayName("C1：命中「跳过广告」文案但无全屏大面积节点 → 非 SPLASH_AD（排他条件②）")
    void detect_skipTextWithoutLargeArea_isNotSplash() {
        assertEquals(PageState.AD_CLOSE_READY, detectFixture("skip_text_no_large_area.xml"),
                "无大面积节点时 SPLASH_AD 排他条件不成立，裸「跳过」子串归入 AD_CLOSE_READY");
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
