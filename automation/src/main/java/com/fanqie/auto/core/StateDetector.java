package com.fanqie.auto.core;

import com.fanqie.auto.config.AutomationConfig;
import com.fanqie.auto.config.LocatorRegistry;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.Dimension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 状态识别器：整个方案的性能核心。
 * <p>
 * 关键设计决策：
 * 1. tick() 只做 1 次 driver.getPageSource() 并构造快照，同一 tick 内快照缓存复用，杜绝重复抓取。
 * 2. detect() 纯内存按优先级顺序匹配，返回首个命中状态。
 * 3. 严禁实现成"逐个状态试 driver.findElement"——那是 O(状态数) 次 RPC，
 *    未命中还会阻塞满超时，是性能陷阱。所有匹配必须基于 UiSnapshot 的内存节点表。
 * <p>
 * 匹配优先级（确定性由高到低）：
 * 1. resource-id 锚点（最高确定性、最低成本，含 READER_MENU 判定）
 * 2. text 候选集（来自 LocatorRegistry，含 SPLASH_AD 优先于 AD_CLOSE_READY）
 * 3. content-desc 候选集
 * 4. APP_LAUNCHING 保守判定（节点极少 + 无锚点 + 无视频大面积特征）
 * 5. 几何特征（如右上角小面积 clickable 节点 → AD_CLOSE_READY）
 * 6. 兜底 UNKNOWN
 */
public class StateDetector implements DriverAware {

    private static final Logger log = LoggerFactory.getLogger(StateDetector.class);

    private volatile AppiumDriver driver;
    private final AutomationConfig config;
    private final LocatorRegistry locators;

    /** 可观测性日志器（可选，通过 setLogger 注入，默认 null 安全） */
    private volatile RunLogger logger;

    /** 当前 tick 的缓存快照，同一 tick 内复用 */
    private UiSnapshot cachedSnapshot;
    /** 缓存快照的创建时间戳 */
    private long snapshotTimestamp;
    /** 上次 getPageSource 的耗时（毫秒） */
    private long lastPageSourceMs;

    /** E3：缓存屏幕尺寸，避免每 tick 都发 getSize() RPC（尺寸在单次运行中通常不变） */
    private volatile Dimension cachedScreenSize;

    public StateDetector(AppiumDriver driver, AutomationConfig config, LocatorRegistry locators) {
        this.driver = driver;
        this.config = config;
        this.locators = locators;
    }

    @Override
    public void refreshDriver(AppiumDriver newDriver) {
        if (newDriver != null) {
            this.driver = newDriver;
            // E3/M2：driver 刷新后屏幕尺寸缓存失效，下次 tick 重新获取
            invalidateScreenSizeCache();
            // N1：清除旧 session 的快照缓存，防止 getCachedSnapshot() 读到旧 session
            // 的 UI 树做点击决策（调用方均已对 null 安全）。
            this.cachedSnapshot = null;
            this.snapshotTimestamp = 0L;
            this.lastPageSourceMs = 0L;
        }
    }

    /**
     * M2：屏幕尺寸缓存失效统一入口。driver 刷新与 tick 自愈均调用本方法，
     * 消除“尺寸缓存只在 refreshDriver 失效”导致横屏/折叠屏旋转后比例判定失真的问题。
     */
    private void invalidateScreenSizeCache() {
        this.cachedScreenSize = null;
    }

    /**
     * E1：注入 RunLogger 以启用状态识别耗时上报。
     * 默认 null 安全——未注入时 detect() 不会上报耗时。
     * 已在 FanqieRunner 装配时调用 detector.setLogger(logger) 完成接线。
     */
    public void setLogger(RunLogger logger) {
        this.logger = logger;
    }

    /**
     * 执行一次 tick：获取 UI 树快照。
     * 只做 1 次 getPageSource()，并缓存结果供同一 tick 内多次 detect() 调用复用。
     * <p>
     * 为什么不用 uiautomator dump：华为设备单次 1.5-6s（需 fork 进程 + 启动 uiautomator 实例
     * + 序列化全树 + 落盘 + 读回），一轮广告流程将付出 10-60s 纯开销。
     * getPageSource() 走设备端长驻 instrumentation server，无进程 fork，0.3-1.2s 即可。
     *
     * @return 当前 UI 树的内存快照
     */
    public UiSnapshot tick() {
        long start = System.currentTimeMillis();
        String xml = null;
        // 首次超时时重试一次（给设备更多响应时间，书架/阅读器页 getPageSource 常慢）
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    Future<String> future = executor.submit(() -> driver.getPageSource());
                    xml = future.get(20, TimeUnit.SECONDS);
                    break; // 成功则跳出
                } finally {
                    executor.shutdownNow();
                }
            } catch (TimeoutException e) {
                if (attempt == 0) {
                    log.warn("[StateDetector] getPageSource 首次超时 (20s)，2s 后重试...");
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                } else {
                    log.warn("[StateDetector] getPageSource 重试仍超时，返回空快照");
                    return new UiSnapshot("",
                        cachedScreenSize != null ? cachedScreenSize.getWidth() : 1080,
                        cachedScreenSize != null ? cachedScreenSize.getHeight() : 2376);
                }
            } catch (Exception e) {
                log.warn("[StateDetector] getPageSource 异常: {}", e.getMessage());
                return new UiSnapshot("",
                    cachedScreenSize != null ? cachedScreenSize.getWidth() : 1080,
                    cachedScreenSize != null ? cachedScreenSize.getHeight() : 2376);
            }
        }
        if (xml == null) {
            return new UiSnapshot("",
                cachedScreenSize != null ? cachedScreenSize.getWidth() : 1080,
                cachedScreenSize != null ? cachedScreenSize.getHeight() : 2376);
        }
        lastPageSourceMs = System.currentTimeMillis() - start;

        // E3：屏幕尺寸首次获取后缓存复用（尺寸通常不变），driver 刷新时自动重置
        if (cachedScreenSize == null) {
            cachedScreenSize = driver.manage().window().getSize();
            log.info("[StateDetector] 屏幕尺寸首次获取并缓存: {}x{}",
                    cachedScreenSize.getWidth(), cachedScreenSize.getHeight());
        }
        UiSnapshot snapshot = new UiSnapshot(xml, cachedScreenSize.getWidth(), cachedScreenSize.getHeight());

        // M2：零成本自愈——若快照中任一节点 bounds 超出缓存尺寸（横屏激励视频/折叠屏旋转），
        // 说明缓存尺寸已过期，失效并重取，避免所有比例判定失真。
        if (isScreenSizeStale(snapshot, cachedScreenSize)) {
            invalidateScreenSizeCache();
            cachedScreenSize = driver.manage().window().getSize();
            log.info("[StateDetector] 检测到节点越界，屏幕尺寸缓存已自愈重取: {}x{}",
                    cachedScreenSize.getWidth(), cachedScreenSize.getHeight());
            snapshot = new UiSnapshot(xml, cachedScreenSize.getWidth(), cachedScreenSize.getHeight());
        }

        cachedSnapshot = snapshot;
        snapshotTimestamp = System.currentTimeMillis();

        log.debug("[StateDetector] tick完成: getPageSource={}ms, XML={}KB, 节点数={}",
                lastPageSourceMs, cachedSnapshot.xmlLength() / 1024, cachedSnapshot.size());

        return cachedSnapshot;
    }

    /**
     * 获取当前缓存的快照（不重新抓取）。
     */
    public UiSnapshot getCachedSnapshot() {
        return cachedSnapshot;
    }

    /**
     * 上次 getPageSource 耗时（毫秒），供 RunLogger 心跳输出。
     */
    public long getLastPageSourceMs() {
        return lastPageSourceMs;
    }

    /**
     * 纯内存状态识别：按优先级顺序匹配，返回首个命中的 PageState。
     * <p>
     * 对无法确定的状态（如 AD_VIDEO_PLAYING vs AD_CLOSE_READY）
     * 用「是否存在关闭按钮特征」区分：有关闭按钮 → AD_CLOSE_READY，无 → AD_VIDEO_PLAYING。
     *
     * @param snapshot 要分析的 UI 树快照
     * @return 识别出的页面状态
     */
    public PageState detect(UiSnapshot snapshot) {
        if (snapshot == null || snapshot.isParseFailed() || snapshot.size() == 0) {
            return PageState.UNKNOWN;
        }

        long detectStart = System.currentTimeMillis();
        PageState state = doDetect(snapshot);
        long detectMs = System.currentTimeMillis() - detectStart;

        // E1：上报状态识别耗时（null 安全）
        if (logger != null) {
            logger.recordDetectTime(detectMs);
        }

        log.debug("[StateDetector] 状态识别: {} ({}ms, XML={}KB)",
                state, detectMs, snapshot.xmlLength() / 1024);

        return state;
    }

    /**
     * 内部检测方法：按优先级逐级匹配。
     * <p>
     * 优先级顺序（确定性由高到低）：
     * 1. resource-id 锚点（含 READER_MENU 判定）
     * 2. text 候选集（含 SPLASH_AD，优先于 AD_CLOSE_READY 几何误判）
     * 3. content-desc 候选集
     * 4. APP_LAUNCHING（节点极少 + 无任何锚点命中 + 无视频大面积特征，保守判定）
     * 5. 几何特征（AD_VIDEO_PLAYING / AD_CLOSE_READY）
     * 6. 兜底 UNKNOWN
     */
    private PageState doDetect(UiSnapshot snapshot) {
        // === 优先级 1：resource-id 锚点（最高确定性、最低成本） ===
        PageState byResId = detectByResourceId(snapshot);
        if (byResId != null) return byResId;

        // === 优先级 2：text 候选集匹配（含 SPLASH_AD 优先于几何 AD_CLOSE_READY） ===
        PageState byText = detectByText(snapshot);
        if (byText != null) return byText;

        // === 优先级 3：content-desc 候选集匹配 ===
        PageState byDesc = detectByContentDesc(snapshot);
        if (byDesc != null) return byDesc;

        // === 优先级 4：APP_LAUNCHING 保守判定（在几何兜底之前） ===
        // 条件：节点极少 + 无书架/阅读/广告任何锚点命中 + 无视频大面积特征
        // 与 AD_VIDEO_PLAYING 区分：APP_LAUNCHING 要求不存在大面积节点
        PageState byLaunching = detectAppLaunching(snapshot);
        if (byLaunching != null) return byLaunching;

        // === 优先级 5：几何特征匹配 ===
        PageState byGeometry = detectByGeometry(snapshot);
        if (byGeometry != null) return byGeometry;

        // === 优先级 6：兜底 UNKNOWN ===
        return PageState.UNKNOWN;
    }

    /**
     * 通过 resource-id 锚点识别状态。
     * 判定依据：番茄小说的关键页面通常有稳定的 resource-id 标识（如书架 Tab、阅读页容器）。
     * 这是最高确定性的匹配方式，因为 resource-id 由开发者显式设定，不受运营文案变化影响。
     * <p>
     * 新增 READER_MENU 判定：当阅读页锚点存在且同时命中 readerMenuTexts 时，
     * 说明阅读页菜单工具栏已弹出，应返回 READER_MENU 而非 READER。
     */
    private PageState detectByResourceId(UiSnapshot snapshot) {
        // 书架页面特征：底部导航栏的 resource-id（实测后补充具体值）
        List<UiNode> shelfNodes = snapshot.findByResourceId("com.dragon.read:id/tab_shelf", false);
        if (!shelfNodes.isEmpty()) {
            // 还需确认书籍列表可见（防止 Tab 在其他页面也存在）
            List<UiNode> recyclerNodes = snapshot.findByResourceId("recycler", false);
            if (!recyclerNodes.isEmpty()) {
                return PageState.BOOKSHELF;
            }
        }

        // 阅读页容器特征
        List<UiNode> readerNodes = snapshot.findByResourceId("com.dragon.read:id/reader", false);
        if (!readerNodes.isEmpty()) {
            // READER_MENU 判定：阅读页锚点存在 + 菜单工具栏特征文案命中
            List<String> menuTexts = locators.readerMenuTexts();
            if (!menuTexts.isEmpty() && !snapshot.findByTextContains(menuTexts).isEmpty()) {
                log.debug("[StateDetector] 阅读页锚点存在且命中菜单文案，判定为 READER_MENU");
                return PageState.READER_MENU;
            }
            // 阅读页存在，无菜单覆盖
            return PageState.READER;
        }

        // 真机校准：部分设备阅读页 resource-id 全为混淆值、无 reader 锚，
        // 改用 dump 校准得到的混淆容器 id 识别阅读页（菜单文案命中则 READER_MENU，否则 READER）。
        for (String cid : locators.readerContainerIds()) {
            if (!snapshot.findByResourceId(cid, false).isEmpty()) {
                List<String> menuTexts = locators.readerMenuTexts();
                if (!menuTexts.isEmpty() && !snapshot.findByTextContains(menuTexts).isEmpty()) {
                    return PageState.READER_MENU;
                }
                return PageState.READER;
            }
        }

        return null; // 无命中，交给下一优先级
    }

    /**
     * 通过 text 候选集识别状态。
     * 判定依据：各状态的页面都有特征性文案（如「观看广告」「继续获取免费时长」「下一章」等）。
     * 文案匹配使用 contains 而非 equals，因为运营可能在文案前后附加空格或装饰字符。
     * <p>
     * SPLASH_AD 判定优先于 AD_CLOSE_READY：开屏广告的「跳过」按钮必须先于广告关闭按钮匹配，
     * 避免冷启动开屏广告被误判为 AD_CLOSE_READY（几何规则也可能误判，但 text 在此已先拦截）。
     */
    private PageState detectByText(UiSnapshot snapshot) {
        // 「继续获取免费时长」→ AD_CONTINUE_PROMPT（优先级高于关闭按钮，因为两者可能同时存在）
        if (!snapshot.findByTextContains(locators.adContinueTexts()).isEmpty()) {
            return PageState.AD_CONTINUE_PROMPT;
        }

        // 「观看广告」/「立即观看」→ AD_CONFIRM_DIALOG
        if (!snapshot.findByTextContains(locators.adWatchTexts()).isEmpty()) {
            return PageState.AD_CONFIRM_DIALOG;
        }

        // 底部广告入口文案 → AD_ENTRY_PROMPT
        if (!snapshot.findByTextContains(locators.adEntryTexts()).isEmpty()) {
            return PageState.AD_ENTRY_PROMPT;
        }

        // ★ SPLASH_AD：开屏广告跳过 → 仅冷启动全屏开屏广告命中（C1 修复）
        // splashSkipTexts 已收窄为开屏专有词（“跳过广告”等，裸“跳过”已归 ad.close.text），
        // 此处再叠加两个排他前置条件（全屏大面积 + 无右上角关闭 clickable），
        // 确保带“跳过”文案的广告关闭页/可跳过激励视频不会被误判为 SPLASH_AD。
        if (!snapshot.findByTextContains(locators.splashSkipTexts()).isEmpty()
                && isSplashAdExclusive(snapshot)) {
            log.debug("[StateDetector] 命中开屏专有跳过文案且满足全屏/无右上角关闭特征，判定为 SPLASH_AD");
            return PageState.SPLASH_AD;
        }

        // 「关闭」文案存在 → AD_CLOSE_READY
        List<UiNode> closeNodes = snapshot.findByTextContains(locators.adCloseTexts());
        if (!closeNodes.isEmpty()) {
            // 有关闭按钮文案 → AD_CLOSE_READY
            return PageState.AD_CLOSE_READY;
        }

        // 「下一章」→ CHAPTER_END
        if (!snapshot.findByTextContains(locators.chapterNextTexts()).isEmpty()) {
            return PageState.CHAPTER_END;
        }

        // 通用弹窗文案 → COMMON_POPUP
        if (!snapshot.findByTextContains(locators.commonDismissTexts()).isEmpty()) {
            // 需排除已命中其他状态的情况（上面已 return）
            return PageState.COMMON_POPUP;
        }

        // 书架 Tab 文案 → BOOKSHELF（resource-id 未命中时的文案兜底）
        if (!snapshot.findByTextContains(locators.shelfTabTexts()).isEmpty()) {
            return PageState.BOOKSHELF;
        }

        // READER_MENU 文案兜底：resource-id 未命中阅读页锚点时（正文自绘导致 reader id 可能缺失），
        // C3 收窄：仅当同时存在大面积正文节点（areaRatio>0.6）时才判 READER_MENU，
        // 否则回落后续判定，防止书架顶栏“设置”等被误判为阅读页菜单。
        if (!snapshot.findByTextContains(locators.readerMenuTexts()).isEmpty()
                && hasLargeAreaNode(snapshot, 0.6)) {
            log.debug("[StateDetector] resource-id 未命中阅读页但菜单文案+大面积正文存在，判定为 READER_MENU");
            return PageState.READER_MENU;
        }

        // READER 兜底（真机校准）：章节进度形如「1/21388」+ 全屏大面积正文 → 判定阅读页。
        // 用于 resource-id 混淆且菜单文案不常驻的场景，进度正则是抗版本漂移的稳定信号；
        // 置于本方法末尾：ad/popup/chapter/shelf 等语义文案已在前面优先命中，不会误抢。
        String readerProg = locators.readerProgressRegex();
        if (!readerProg.isEmpty() && !snapshot.extractByRegex(readerProg).isEmpty()
                && hasLargeAreaNode(snapshot, 0.6)) {
            log.debug("[StateDetector] 命中章节进度正则且存在全屏正文，判定为 READER");
            return PageState.READER;
        }

        return null; // 无命中
    }

    /**
     * 通过 content-desc 候选集识别状态。
     * 判定依据：穿山甲 SDK 常给关闭按钮设置 content-desc 而非 text。
     */
    private PageState detectByContentDesc(UiSnapshot snapshot) {
        // content-desc 包含「关闭」/「close」→ AD_CLOSE_READY
        if (!snapshot.findByContentDescContains(locators.adCloseDescs()).isEmpty()) {
            return PageState.AD_CLOSE_READY;
        }

        return null; // 无命中
    }

   /**
     * APP_LAUNCHING 保守判定：节点极少 + 无任何已知锚点命中 + 无视频大面积特征。
     * <p>
     * 与 AD_VIDEO_PLAYING 的区分：
     * - AD_VIDEO_PLAYING：节点极少但存在大面积节点（视频 SurfaceView，areaRatio > 0.6）
     * - APP_LAUNCHING：节点极少且不存在大面积节点，也无任何可识别锚点
     * <p>
     * 放在几何兜底之前：若节点极少且无大面积，几何规则（右上角小 clickable）可能误判，
     * 此时保守认定为 App 启动中更安全（启动期 UI 树尚未加载完成）。
     */
    private PageState detectAppLaunching(UiSnapshot snapshot) {
        // 条件 1：节点数极少（App 启动期间 UI 树尚未完全加载）
        if (snapshot.size() > 15) {
            return null; // 节点数不算少，不是启动期
        }

        int screenW = snapshot.getScreenWidth();
        int screenH = snapshot.getScreenHeight();

        // 条件 2：不存在大面积节点（排除 AD_VIDEO_PLAYING 的视频 SurfaceView）
        boolean hasLargeArea = snapshot.nodes().stream()
                .filter(UiNode::isHasBounds)
                .anyMatch(n -> n.getBounds().areaRatio(screenW, screenH) > 0.6);
        if (hasLargeArea) {
            return null; // 有大面积视频特征，交给几何规则判定 AD_VIDEO_PLAYING
        }

        // 条件 3：无书架/阅读/广告任何锚点命中（上面 resource-id/text/desc 全部未命中才会到这里）
        // 由于 detectByResourceId / detectByText / detectByContentDesc 全部返回 null 才进入此方法，
        // 条件 3 天然满足。

        log.debug("[StateDetector] 节点极少({})且无大面积特征、无锚点命中，保守判定为 APP_LAUNCHING",
                snapshot.size());
        return PageState.APP_LAUNCHING;
    }

    /**
     * 通过几何特征识别状态。
     * 判定依据：
     * - AD_VIDEO_PLAYING：存在大面积视频播放区域（面积 > 60% 屏幕）但无关闭按钮特征
     * - AD_CLOSE_READY：存在 areaRatio < 0.15 且中心落在右上角 (x>0.8W && y<0.2H) 的 clickable 节点
     * <p>
     * 几何特征是最后的兜底手段，可靠性低于语义属性匹配。
     * 注：APP_LAUNCHING 已在前一优先级处理，此处不再考虑节点极少的启动场景。
     */
    private PageState detectByGeometry(UiSnapshot snapshot) {
        int screenW = snapshot.getScreenWidth();
        int screenH = snapshot.getScreenHeight();

        // 检测右上角关闭按钮特征：areaRatio<0.15 且中心落在 x>0.8W && y<0.2H 的 clickable 节点
        List<UiNode> topRightClickables = snapshot.findClickableInRegion(0.8, 1.0, 0.0, 0.2, 0.15);
        if (!topRightClickables.isEmpty()) {
            // 右上角有小面积可点击节点 → 很可能是广告关闭按钮
            return PageState.AD_CLOSE_READY;
        }

        // 检测大面积视频播放区域（无关闭按钮、节点数极少）
        // 广告视频播放时 UI 树通常非常简洁（视频 SurfaceView + 少量控件）
        if (snapshot.size() <= 30) {
            // 检查是否存在大面积节点（视频播放区域）
            boolean hasLargeArea = snapshot.nodes().stream()
                    .filter(UiNode::isHasBounds)
                    .anyMatch(n -> n.getBounds().areaRatio(screenW, screenH) > 0.6);
            if (hasLargeArea) {
                return PageState.AD_VIDEO_PLAYING;
            }
        }

        return null; // 无命中，返回 UNKNOWN
    }

    /**
     * C1：SPLASH_AD 的排他前置判定——全屏大面积 + 无右上角关闭 clickable。
     * <p>
     * 只有真正的冷启动全屏开屏广告才应命中，避免与激励视频关闭页（AD_CLOSE_READY）混淆：
     * ② 存在全屏大面积节点（areaRatio>0.6，开屏广告整屏铺满）；
     * ③ 不存在 AD_CLOSE_READY 的右上角小 clickable 特征（复用 detectByGeometry 同一判定，
     *    否则更可能是激励视频关闭按钮）。
     */
    private boolean isSplashAdExclusive(UiSnapshot snapshot) {
        // 条件②：存在全屏大面积节点
        if (!hasLargeAreaNode(snapshot, 0.6)) {
            return false;
        }
        // 条件③：不存在右上角小 clickable 关闭特征（与 detectByGeometry 判定一致）
        boolean hasTopRightClose = !snapshot.findClickableInRegion(0.8, 1.0, 0.0, 0.2, 0.15).isEmpty();
        return !hasTopRightClose;
    }

    /**
     * 是否存在面积占比超过 threshold 的大面积节点。
     * 供 SPLASH_AD 排他判定与 READER_MENU 纯文案兜底收窄复用。
     */
    private boolean hasLargeAreaNode(UiSnapshot snapshot, double threshold) {
        int screenW = snapshot.getScreenWidth();
        int screenH = snapshot.getScreenHeight();
        return snapshot.nodes().stream()
                .filter(UiNode::isHasBounds)
                .anyMatch(n -> n.getBounds().areaRatio(screenW, screenH) > threshold);
    }

    /**
     * M2：判断快照中是否存在越界节点（bounds 超出缓存屏幕尺寸），用于尺寸缓存自愈。
     */
    private boolean isScreenSizeStale(UiSnapshot snapshot, Dimension size) {
        int w = size.getWidth();
        int h = size.getHeight();
        return snapshot.nodes().stream()
                .filter(UiNode::isHasBounds)
                .anyMatch(n -> n.getBounds().getRight() > w || n.getBounds().getBottom() > h);
    }
}
