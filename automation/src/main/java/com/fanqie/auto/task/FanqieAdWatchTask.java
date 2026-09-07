package com.fanqie.auto.task;

import com.fanqie.auto.config.AutomationConfig;
import com.fanqie.auto.config.LocatorRegistry;
import com.fanqie.auto.core.DriverAware;
import com.fanqie.auto.core.EnvDoctor;
import com.fanqie.auto.core.GestureSupport;
import com.fanqie.auto.core.PageState;
import com.fanqie.auto.core.RunLogger;
import com.fanqie.auto.core.StateDetector;
import com.fanqie.auto.core.UiSnapshot;
import com.fanqie.auto.core.WaitSupport;
import com.fanqie.auto.page.AdFlowPage;
import com.fanqie.auto.page.BookshelfPage;
import com.fanqie.auto.page.ReaderPage;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.Dimension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 番茄小说广告观看任务——业务编排。
 * <p>
 * 实现 Task 接口（含 run() 与 getName()），便于未来扩展到其他 App / 其他任务而核心引擎零改动。
 * <p>
 * 完整流程：
 * <pre>
 * EnvDoctor.checkAll() 自检
 *   → activateApp 启动番茄小说（不硬编码 Activity）
 *   → untilState(BOOKSHELF, APP_LAUNCHING, SPLASH_AD, COMMON_POPUP)
 *   → 若为 APP_LAUNCHING/SPLASH_AD/COMMON_POPUP，交状态机推进到 BOOKSHELF
 *   → switchToShelfTab() 确保在书架
 *   → openAnyBook() 打开任意一本书 → 确认进入 READER
 *   → 外层循环 outerCycles 次：
 *       内层：翻页 pagesPerCycle 次，每次翻页后 detect
 *           遇广告态 → 进入 AdWatchStateMachine 广告子循环
 *           遇 CHAPTER_END → goNextChapter()
 *           遇 COMMON_POPUP/UNKNOWN → RecoveryHandler
 *       广告子循环结束后确认回到 READER
 *   → 收尾：输出统计汇总
 * </pre>
 */
public class FanqieAdWatchTask implements Task, DriverAware {

    private static final Logger log = LoggerFactory.getLogger(FanqieAdWatchTask.class);

    private volatile AppiumDriver driver;
    private final AutomationConfig config;
    private final LocatorRegistry locators;
    private final StateDetector detector;
    private final WaitSupport waitSupport;
    private final GestureSupport gestures;
    private final RunLogger logger;
    private final BookshelfPage bookshelfPage;
    private final ReaderPage readerPage;
    private final AdFlowPage adFlowPage;
    private final AdWatchStateMachine stateMachine;
    private final RecoveryHandler recoveryHandler;

    /** 命令行覆盖的外层循环次数（null 表示使用 config 值） */
    private Integer cyclesOverride;
    /** 命令行覆盖的每轮翻页次数（null 表示使用 config 值） */
    private Integer pagesOverride;

    public FanqieAdWatchTask(AppiumDriver driver, AutomationConfig config, LocatorRegistry locators,
                             StateDetector detector, WaitSupport waitSupport, GestureSupport gestures,
                             RunLogger logger, BookshelfPage bookshelfPage, ReaderPage readerPage,
                             AdFlowPage adFlowPage, AdWatchStateMachine stateMachine,
                             RecoveryHandler recoveryHandler) {
        this.driver = driver;
        this.config = config;
        this.locators = locators;
        this.detector = detector;
        this.waitSupport = waitSupport;
        this.gestures = gestures;
        this.logger = logger;
        this.bookshelfPage = bookshelfPage;
        this.readerPage = readerPage;
        this.adFlowPage = adFlowPage;
        this.stateMachine = stateMachine;
        this.recoveryHandler = recoveryHandler;
    }

    @Override
    public void refreshDriver(AppiumDriver newDriver) {
        if (newDriver != null) this.driver = newDriver;
    }

    /** 设置命令行覆盖的外层循环次数 */
    public void setCyclesOverride(Integer cycles) { this.cyclesOverride = cycles; }
    /** 设置命令行覆盖的每轮翻页次数 */
    public void setPagesOverride(Integer pages) { this.pagesOverride = pages; }

    @Override
    public String getName() {
        return "番茄免费小说-看视频领免广告时长";
    }

    @Override
    public boolean run() {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  任务启动: {}  ║", getName());
        log.info("╚══════════════════════════════════════════════════════════════╝");

        int outerCycles = (cyclesOverride != null) ? cyclesOverride : config.outerCycles();
        int pagesPerCycle = (pagesOverride != null) ? pagesOverride : config.pagesPerCycle();

        // D3：记录任务启动时间戳（墙钟时间熔断基准）
        long taskStartAt = System.currentTimeMillis();

        // === 阶段 1：启动 App 并等待到达书架 ===
        log.info("[Task] 阶段 1：启动 App 并导航到书架");
        boolean launched = launchAndNavigateToShelf();
        if (!launched) {
            log.error("[Task] 无法启动 App 或导航到书架，任务终止");
            return false;
        }

        // === 阶段 2：打开一本书进入阅读页 ===
        // 真机校准：
        // 1. 点击书封后先打开书籍详情页，需左滑才能进入阅读器
        // 2. uiautomator 在设备上崩溃，getPageSource 不可用，改用 activity 名称判定状态
        // 3. 书架混排短剧视频，误点会落入视频页，需检测并换书
        log.info("[Task] 阶段 2：打开书架中的一本书（自动跳过短剧/视频页）");
        java.util.Set<String> triedIds = new java.util.LinkedHashSet<>();
        int maxPickAttempts = config.maxRecoveryRetry() + 3;
        boolean enteredReader = false;
        for (int pick = 0; pick < maxPickAttempts; pick++) {
            boolean opened = triedIds.isEmpty()
                    ? bookshelfPage.openAnyBook()
                    : bookshelfPage.openNextBook(triedIds);
            if (!opened) {
                log.warn("[Task] 第 {} 次选书未能打开新条目（书架可能已无未试条目）", pick + 1);
                break;
            }
            String bookId = bookshelfPage.getLastOpenedBookId();
            triedIds.add(bookId);

            // 左滑进入阅读器（从书籍详情页 → 阅读器）
            bookshelfPage.swipeLeftToRead();

            // 用 activity 名称验证是否进入阅读器（不依赖 getPageSource）
            String activity = gestures.getCurrentActivity();
            log.info("[Task] 开书+左滑后 activity: {}", activity);

            if (activity != null && activity.contains("Reader")) {
                stateMachine.markBookUsed(bookId);
                log.info("[Task] 成功进入小说阅读页 (activity={})", activity);
                enteredReader = true;
                break;
            }

            // 检查是否落入视频/短剧页（通过 activity 名称判断）
            if (activity != null && (activity.contains("Video") || activity.contains("Drama")
                    || activity.contains("Short") || activity.contains("Play"))) {
                log.warn("[Task] 打开『{}』后落入视频/短剧页 (activity={})，返回首页换一本",
                        truncateId(bookId), activity);
                if (!backHomeAndReopenShelf()) {
                    log.warn("[Task] 返回首页并重点书架失败，终止选书");
                    break;
                }
                continue;
            }

            // 其他情况：可能在书架页（点击无效）或详情页（左滑未生效）
            log.warn("[Task] 打开书籍后未进入阅读器 (activity={})，尝试下一本", activity);
        }
        if (!enteredReader) {
            log.error("[Task] 多次尝试仍无法进入小说阅读页（书架可能多为短剧/视频），任务终止");
            return false;
        }
        log.info("[Task] 已进入阅读页，开始主循环");

        // === 阶段 3：外层循环 ===
        for (int cycle = 1; cycle <= outerCycles; cycle++) {
            log.info("[Task] ═══ 外层循环 {}/{} ═══", cycle, outerCycles);

            // 每轮外层循环开始前检查全局熔断
            if (stateMachine.getEarnedMinutes() >= config.targetFreeMinutes()) {
                log.info("[Task] 全局熔断：目标分钟数已达成 ({}/{})，提前正常收尾",
                        stateMachine.getEarnedMinutes(), config.targetFreeMinutes());
                break;
            }
            if (stateMachine.getTotalAdRounds() >= config.maxTotalAdRounds()) {
                log.info("[Task] 全局熔断：总广告轮次达上限 ({}/{})，提前正常收尾",
                        stateMachine.getTotalAdRounds(), config.maxTotalAdRounds());
                break;
            }

            // D3：墙钟时间预算耗尽 → 优雅收尾退出（预算 <=0 表示不启用）
            long wallClockBudgetMs = config.maxWallClockMs();
            if (wallClockBudgetMs > 0 && System.currentTimeMillis() - taskStartAt >= wallClockBudgetMs) {
                log.info("[Task] 墙钟时间预算已耗尽（已运行 {}ms >= 预算 {}ms），因墙钟预算优雅收尾退出",
                        System.currentTimeMillis() - taskStartAt, wallClockBudgetMs);
                break;
            }

            // D4：每日配额耗尽 → 优雅收尾退出（正常结束并汇总统计）
            if (stateMachine.isDailyQuotaExhausted()) {
                log.info("[Task] 每日配额已耗尽，优雅收尾退出");
                break;
            }

            // === 内层：翻页 pagesPerCycle 次 ===
            // 优化：不再每页调用 getPageSource/detect 检测状态（uiautomator 在设备上崩溃），
            // 而是持续翻页，通过 activity 名称判断是否仍在阅读器。
            // 翻不动了（activity 不再是 ReaderActivity）说明到了视频页或广告页。
            // 防死循环：连续触发非阅读器 activity 超过阈值，认为当前页面有问题（可能点到了广告链接）
            final int MAX_CONSECUTIVE_NON_READER = 2;
            int consecutiveNonReaderCount = 0;
            pagesLoop:
            for (int page = 1; page <= pagesPerCycle; page++) {
                log.info("[Task] 翻页 {}/{} (外层循环 {})", page, pagesPerCycle, cycle);

                // 执行翻页手势（adb input tap/swipe，不依赖 uiautomator）
                gestures.turnPageNext();
                logger.incrementPageCount();

                // 短暂等待页面加载
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

                // 通过 activity 名称检查是否仍在阅读器（不截图、不调 getPageSource）
                String activity = gestures.getCurrentActivity();

                if (activity != null && activity.contains("Reader")) {
                    // 仍在阅读器，正常翻页，重置计数器
                    log.debug("[Task] 翻页成功，仍在阅读器 (activity={})", activity);
                    consecutiveNonReaderCount = 0;
                    continue;
                }

                // activity 变化 → 离开阅读页（翻到视频页/广告弹窗等）
                log.info("[Task] 翻页后 activity 变化: {} → 离开阅读页", activity);
                consecutiveNonReaderCount++;
                
                // 防死循环：连续触发非阅读器 activity 超过阈值，停止翻页
                if (consecutiveNonReaderCount > MAX_CONSECUTIVE_NON_READER) {
                    log.warn("[Task] 连续 {} 次翻页触发非阅读器 activity，认为当前页面有问题，停止翻页",
                            consecutiveNonReaderCount);
                    break pagesLoop;
                }

                if (activity != null && (activity.contains("Ad") || activity.contains("Reward")
                        || activity.contains("ad") || activity.contains("reward"))) {
                    // 广告相关 activity → 暂时跳过广告子循环（getPageSource 不可用），直接尝试返回阅读器
                    // TODO: 后续改造广告子循环为 activity 检测方式
                    log.info("[Task] 检测到广告 activity: {}，跳过广告子循环（getPageSource 不可用），尝试返回阅读器", activity);
                    // 尝试按返回回到阅读器
                    try { driver.navigate().back(); } catch (Exception e) { /* ignore */ }
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    String afterBack = gestures.getCurrentActivity();
                    if (afterBack != null && afterBack.contains("Reader")) {
                        log.info("[Task] 按返回后回到阅读器，继续翻页");
                    } else {
                        // 返回不行，尝试返回首页重新打开书籍
                        log.warn("[Task] 按返回后仍非阅读器 (activity={})，尝试返回首页重新打开书籍", afterBack);
                        if (backHomeAndReopenShelf() && reopenFirstBook()) {
                            log.info("[Task] 已重新打开书籍，继续翻页");
                        } else {
                            log.warn("[Task] 返回首页重新打开书籍失败，终止翻页");
                            break pagesLoop;
                        }
                    }
                } else if (activity != null && (activity.contains("Video") || activity.contains("Drama")
                        || activity.contains("Short") || activity.contains("Play"))) {
                    // 视频/短剧页 → 返回首页换书
                    log.warn("[Task] 翻到视频/短剧页 (activity={})，返回首页换书", activity);
                    if (!backHomeAndReopenShelf()) {
                        log.warn("[Task] 返回首页并重新打开书架失败，终止翻页");
                        break pagesLoop;
                    }
                } else {
                    // 其他非阅读器状态（WebView/直播页等）→ 逐级恢复（不用 swipeDown，会打开下拉框）
                    log.warn("[Task] 翻页后非阅读器状态 (activity={})，尝试恢复", activity);
                    // 第 1 级：点击左上角返回箭头（很多子页面如 WebView/Live 有固定返回按钮）
                    Dimension size = gestures.getScreenSize();
                    int backArrowX = (int) (0.06 * size.getWidth());  // 左上角 ~60px
                    int backArrowY = (int) (0.07 * size.getHeight()); // 状态栏下方 ~166px
                    log.info("[Task] 点击左上角返回箭头: pixel=({}, {})", backArrowX, backArrowY);
                    gestures.tapAtPixel(backArrowX, backArrowY);
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    String afterArrow = gestures.getCurrentActivity();
                    if (afterArrow != null && afterArrow.contains("Reader")) {
                        log.info("[Task] 点击左上角返回箭头后回到阅读器，继续翻页");
                        continue;
                    }
                    // 第 2 级：系统返回键
                    log.warn("[Task] 点击返回箭头后仍非阅读器 (activity={})，按系统返回键", afterArrow);
                    try { driver.navigate().back(); } catch (Exception e) { /* ignore */ }
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    String afterBack = gestures.getCurrentActivity();
                    if (afterBack != null && afterBack.contains("Reader")) {
                        log.info("[Task] 按系统返回后回到阅读器，继续翻页");
                        continue;
                    }
                    // 第 3 级：返回首页重新打开书籍
                    log.warn("[Task] 返回箭头+系统返回均无效 (activity={})，尝试返回首页重新打开书籍", afterBack);
                    if (backHomeAndReopenShelf() && reopenFirstBook()) {
                        log.info("[Task] 已重新打开书籍，继续翻页");
                        continue;
                    }
                    log.warn("[Task] 所有恢复手段均失败，终止翻页");
                    break pagesLoop;
                }
            }

            // 一轮翻页结束后，如果还没达标，尝试点击底部广告入口（坐标方式，不依赖 getPageSource）
            if (stateMachine.getEarnedMinutes() < config.targetFreeMinutes()
                    && stateMachine.getTotalAdRounds() < config.maxTotalAdRounds()) {
                log.info("[Task] 本轮翻页结束，尝试点击底部广告入口（坐标方式）");
                // 阅读器底部「看视频 免费看」按钮通常在屏幕底部中央
                gestures.tapAtRatio(0.50, 0.95);
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

                String afterTap = gestures.getCurrentActivity();
                if (afterTap != null && !afterTap.contains("Reader")) {
                    // activity 变化 → 可能进入了广告流程
                    log.info("[Task] 点击底部后 activity 变化: {}，可能进入广告流程", afterTap);
                    stateMachine.runAdSubLoop();
                    ensureBackToReader();
                    if (!rotateBookIfNeeded() && !confirmInReaderFamily()) {
                        log.warn("[Task] 轮换后无法回到阅读页家族，优雅收尾退出外层循环");
                        break;
                    }
                } else {
                    log.info("[Task] 点击底部后仍在阅读器，未检测到广告入口");
                }
            }
        }

        // === 收尾：输出统计汇总 ===
        log.info("[Task] ═══ 任务完成 ═══");
        log.info("[Task] 累计免广告时长: {} 分钟 (目标: {} 分钟)",
                stateMachine.getEarnedMinutes(), config.targetFreeMinutes());
        log.info("[Task] 总广告轮次: {} (上限: {})",
                stateMachine.getTotalAdRounds(), config.maxTotalAdRounds());
        return true;
    }

    // ==================== 内部方法 ====================

    /**
     * 启动 App 并导航到书架页。
     * 处理可能遇到的开屏广告、通用弹窗等中间状态。
     */
    private boolean launchAndNavigateToShelf() {
        // 每次运行先 kill 再冷启（restartApp = terminateApp + activateApp）：
        // 番茄冷启后可能停在「短剧」等上次使用的 Tab，故随后强制点一次底部「书架」 Tab。
        try {
            gestures.restartApp(config.appPackage());
            log.info("[Task] App 已 kill 并重新启动（冷启）: {}", config.appPackage());
        } catch (Exception e) {
            log.error("[Task] 重启 App 失败: {}", e.getMessage());
            return false;
        }

        // 等待 App 启动（使用 activity 检测代替 waitSupport.untilState，uiautomator 在设备上崩溃）
        try {
            long startTime = System.currentTimeMillis();
            long timeout = config.appLaunchTimeoutMs();
            PageState launchState = PageState.APP_LAUNCHING;
            
            while (System.currentTimeMillis() - startTime < timeout) {
                String activity = gestures.getCurrentActivity();
                if (activity != null && (activity.contains("Main") || activity.contains("bookshelf"))) {
                    launchState = PageState.BOOKSHELF;
                    break;
                }
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            
            logger.updateState(launchState);
            log.info("[Task] App 启动后状态: {}", launchState);

            // 即便检测到 BOOKSHELF，也可能因底部「书架」Tab 文案在书城/短剧等主页共享而误判，
            // 故始终主动切一次书架 Tab，确保真正落在书架页再选书（已在书架时为幂等空操作）。
            if (launchState == PageState.BOOKSHELF) {
                return bookshelfPage.switchToShelfTab();
            }

            // 处理中间状态（使用 activity 检测代替 detector.tick()，uiautomator 在设备上崩溃）
            int maxAttempts = config.maxRecoveryRetry();
            for (int i = 0; i < maxAttempts; i++) {
                String activity = gestures.getCurrentActivity();
                log.info("[Task] App 启动中... (activity={})", activity);

                if (activity != null && (activity.contains("Main") || activity.contains("bookshelf"))) {
                    log.info("[Task] 已到达书架页 (activity={})", activity);
                    return bookshelfPage.switchToShelfTab();
                }

                // 检查是否是开屏广告
                if (activity != null && (activity.contains("Ad") || activity.contains("Splash"))) {
                    log.info("[Task] 检测到开屏广告，尝试坐标关闭");
                    Dimension size = gestures.getScreenSize();
                    int closeX = (int) (0.9 * size.getWidth());
                    int closeY = (int) (0.1 * size.getHeight());
                    gestures.tapAtPixel(closeX, closeY);
                }

                // 检查是否是通用弹窗
                if (activity != null && (activity.contains("Dialog") || activity.contains("Popup"))) {
                    log.info("[Task] 检测到通用弹窗，尝试按返回关闭");
                    try { driver.navigate().back(); } catch (Exception e) { /* ignore */ }
                }

                // 等待状态变化
                try {
                    Thread.sleep(config.pollIntervalMs() * 4);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            // 多次尝试后仍未到书架，尝试切换到书架 Tab
            log.info("[Task] 尝试主动切换到书架 Tab");
            return bookshelfPage.switchToShelfTab();

        } catch (Exception e) {
            log.error("[Task] 等待 App 启动超时: {}", e.getMessage());
            // 超时后尝试直接切换到书架
            return bookshelfPage.switchToShelfTab();
        }
    }

    /**
     * 真机校准：判断当前快照是否为「短剧/视频」页（点开短剧后落入的竖屏播放页）。
     * <p>
     * 依据：整页 text/content-desc 命中短剧特征正则（第N集/全N集/选集/观看全集/看剧/漫剧/第N季 等）。
     * 小说阅读器正文不会出现这些标记，故可用于区分「误开视频」与「正常进入阅读器」。
     */
    private boolean isVideoPage(UiSnapshot snapshot) {
        if (snapshot == null) return false;
        String regex = locators.bookShortDramaRegex();
        if (regex == null || regex.isEmpty()) return false;
        java.util.regex.Pattern p;
        try {
            p = java.util.regex.Pattern.compile(regex);
        } catch (Exception e) {
            log.warn("[Task] 短剧正则编译失败，跳过视频页判定: {}", e.getMessage());
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (com.fanqie.auto.core.UiNode n : snapshot.nodes()) {
            if (n.hasText()) sb.append(n.getText()).append(' ');
            if (n.hasContentDesc()) sb.append(n.getContentDesc()).append(' ');
        }
        return p.matcher(sb.toString()).find();
    }

    /** 截断书籍标识用于日志（避免过长标题刷屏）。 */
    private String truncateId(String s) {
        if (s == null) return "";
        return s.length() <= 20 ? s : s.substring(0, 20) + "...";
    }

    /**
     * 确保回到阅读器（基于 activity 名称检测，不依赖 getPageSource）。
     * 广告子循环结束后调用，确认仍在阅读器。
     */
    private void ensureBackToReader() {
        String activity = gestures.getCurrentActivity();
        if (activity != null && activity.contains("Reader")) {
            return;
        }
        log.info("[Task] 广告子循环后不在阅读器 (activity={})，按返回尝试恢复", activity);
        try { driver.navigate().back(); } catch (Exception e) { /* ignore */ }
        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        String afterBack = gestures.getCurrentActivity();
        if (afterBack != null && afterBack.contains("Reader")) {
            log.info("[Task] 按返回后已回到阅读器");
        } else {
            log.warn("[Task] 按返回后仍非阅读器 (activity={})", afterBack);
        }
    }

    /**
     * 从视频页面返回首页并重新打开书架 Tab。
     * 通过 activity 名称判断是否回到主页面（不依赖 getPageSource）。
     *
     * @return true=已回到书架, false=未能回到
     */
    private boolean backHomeAndReopenShelf() {
        int maxBack = config.maxRecoveryRetry() + 1;
        for (int i = 0; i < maxBack; i++) {
            String activity = gestures.getCurrentActivity();
            if (activity != null && (activity.contains("Main") || activity.contains("bookshelf"))) {
                break;
            }
            try {
                driver.navigate().back();
            } catch (Exception e) {
                log.warn("[Task] 返回首页 back() 异常: {}", e.getMessage());
                break;
            }
            try {
                Thread.sleep(config.pollIntervalMs() * 2);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return bookshelfPage.switchToShelfTab();
    }

    /**
     * 确认当前处于阅读页家族（基于 activity 名称检测，不依赖 getPageSource）。
     *
     * @return true=已处于阅读页, false=恢复后仍无法回到阅读页
     */
    private boolean confirmInReaderFamily() {
        String activity = gestures.getCurrentActivity();
        if (activity != null && activity.contains("Reader")) {
            return true;
        }
        log.warn("[Task] 未处于阅读页 (activity={})，按返回尝试恢复", activity);
        try { driver.navigate().back(); } catch (Exception e) { /* ignore */ }
        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        activity = gestures.getCurrentActivity();
        return activity != null && activity.contains("Reader");
    }

    /**
     * 重新打开书架中的第一本书并进入阅读器。
     * 用于评论层/浮层恢复失败后的兜底策略：下滑 → 返回 → 重新打开书籍。
     *
     * @return true=成功打开书籍并进入阅读器, false=失败
     */
    private boolean reopenFirstBook() {
        log.info("[Task] 尝试重新打开书架第一本书");
        // 点击书架网格第一本书（坐标方式）
        boolean opened = bookshelfPage.openFirstBookByCoordinate();
        if (!opened) {
            log.warn("[Task] 坐标点击第一本书失败");
            return false;
        }
        // 左滑进入阅读器
        bookshelfPage.swipeLeftToRead();
        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        String activity = gestures.getCurrentActivity();
        if (activity != null && activity.contains("Reader")) {
            log.info("[Task] 重新打开书籍成功，已进入阅读器 (activity={})", activity);
            return true;
        }
        log.warn("[Task] 重新打开书籍后未进入阅读器 (activity={})", activity);
        return false;
    }

    // ==================== D5：多本书轮换 ====================

    /**
     * D5：判断是否需要轮换书籍，若需要则执行轮换。
     * <p>
     * 触发条件：本书广告轮次达单入口上限 maxAdRoundsPerEntry，且全局目标（分钟/总轮次）未达，
     * 且未识别到每日配额耗尽。满足则回书架并换下一本未用书籍继续，保持对单本流程的向后兼容。
     *
     * @return true=已轮换到新书并进入其阅读页, false=未轮换（条件不满足或轮换失败）
     */
    private boolean rotateBookIfNeeded() {
        boolean perEntryLimitReached = stateMachine.getCurrentEntryRound() >= config.maxAdRoundsPerEntry();
        boolean globalTargetNotMet = stateMachine.getEarnedMinutes() < config.targetFreeMinutes()
                && stateMachine.getTotalAdRounds() < config.maxTotalAdRounds();
        if (perEntryLimitReached && globalTargetNotMet && !stateMachine.isDailyQuotaExhausted()) {
            log.info("[Task] 本书广告轮次达上限 ({}/{}) 且全局目标未达，尝试轮换到下一本书",
                    stateMachine.getCurrentEntryRound(), config.maxAdRoundsPerEntry());
            return rotateToNextBook();
        }
        return false;
    }

    /**
     * D5：执行书籍轮换——标记当前书已用 → 返回书架 → 打开下一本未使用书籍。
     *
     * @return true=成功轮换到新书, false=无法返回书架或书架中已无未使用书籍
     */
    private boolean rotateToNextBook() {
        // 标记当前书已用（per-book 进度）
        stateMachine.markBookUsed(bookshelfPage.getLastOpenedBookId());

        // 返回书架
        if (!navigateBackToShelf()) {
            log.warn("[Task] 无法返回书架，放弃轮换，继续使用当前书");
            return false;
        }

        // 打开下一本未使用的书（排除已用书籍集合）
        boolean opened = bookshelfPage.openNextBook(stateMachine.getUsedBooks());
        if (!opened) {
            // C5.1：openNextBook 失败（书架书全在 usedBooks 或滚动到底无未用书）时兜底——
            // 先回顶（下滑）再打开任意一本书，避免停在书架空转、破坏「子循环后必在 READER」不变量。
            log.info("[Task] openNextBook 未找到未使用书籍，回顶后兜底打开任意一本书");
            try {
                gestures.swipeDown();
            } catch (Exception e) {
                log.debug("[Task] 回顶下滑异常（忽略）: {}", e.getMessage());
            }
            opened = bookshelfPage.openAnyBook();
            if (opened) {
                stateMachine.markBookUsed(bookshelfPage.getLastOpenedBookId());
                log.info("[Task] 兜底打开书籍成功: {}", bookshelfPage.getLastOpenedBookId());
                return true;
            }
            // 兜底仍失败：显式恢复到书架锚点，尽力不把自己丢在书架空转
            log.warn("[Task] 兜底打开书籍仍失败，触发 BOOKSHELF 恢复");
            recoveryHandler.recover(PageState.BOOKSHELF);
            return false;
        }
        stateMachine.markBookUsed(bookshelfPage.getLastOpenedBookId());
        log.info("[Task] 已轮换到新书籍: {}（累计已用 {} 本）",
                bookshelfPage.getLastOpenedBookId(), stateMachine.getUsedBooks().size());
        return true;
    }

    /**
     * D5：从阅读页返回书架。先逐级系统 back() 退出阅读页，仍不到书架则主动切换书架 Tab。
     * 使用 activity 检测代替 detector.tick()（uiautomator 在设备上崩溃）。
     *
     * @return true=已回到书架, false=返回失败
     */
    private boolean navigateBackToShelf() {
        int maxBack = config.maxRecoveryRetry();
        for (int i = 0; i < maxBack; i++) {
            String activity = gestures.getCurrentActivity();
            if (activity != null && (activity.contains("Main") || activity.contains("bookshelf"))) {
                log.info("[Task] 已到达书架页 (activity={})", activity);
                return true;
            }
            // 退出阅读页：系统 back()
            try {
                driver.navigate().back();
            } catch (Exception e) {
                log.warn("[Task] 返回书架时 back() 异常: {}", e.getMessage());
                break;
            }
            try {
                Thread.sleep(config.pollIntervalMs() * 2);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        // 仍未到书架，尝试主动切换书架 Tab
        log.info("[Task] back() 未回到书架，尝试主动切换书架 Tab");
        return bookshelfPage.switchToShelfTab();
    }
}
