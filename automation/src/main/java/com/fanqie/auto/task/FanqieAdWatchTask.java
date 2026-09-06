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
        // 真机校准：番茄书架混排「短剧」视频，误点会落入竖屏视频页而非阅读器。
        // 故循环选书：若打开后判定为视频/短剧页 → 返回首页并重新点底部「书架」Tab → 换一本（排除已试）。
        log.info("[Task] 阶段 2：打开书架中的一本书（自动跳过短剧/视频页）");
        java.util.Set<String> triedIds = new java.util.LinkedHashSet<>();
        int maxPickAttempts = config.maxRecoveryRetry() + 3;
        PageState readerState = PageState.UNKNOWN;
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
            UiSnapshot pickSnapshot = detector.tick();
            readerState = detector.detect(pickSnapshot);
            if (readerState.isReaderFamily()) {
                logger.updateState(readerState);
                stateMachine.markBookUsed(bookId);
                log.info("[Task] 成功进入小说阅读页: {}", readerState);
                enteredReader = true;
                break;
            }
            if (isVideoPage(pickSnapshot)) {
                log.warn("[Task] 打开『{}』后落入视频/短剧页，返回首页并重新点击底部「书架」换一本",
                        truncateId(bookId));
                if (!backHomeAndReopenShelf()) {
                    log.warn("[Task] 返回首页并重点书架失败，终止选书");
                    break;
                }
                continue;
            }
            log.warn("[Task] 打开书籍后状态为 {}（非阅读页家族、非视频），尝试恢复后重选", readerState);
            boolean recovered = recoveryHandler.recover(readerState);
            if (!recovered) {
                log.error("[Task] 恢复失败，任务终止");
                return false;
            }
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
            pagesLoop:
            for (int page = 1; page <= pagesPerCycle; page++) {
                log.info("[Task] 翻页 {}/{} (外层循环 {})", page, pagesPerCycle, cycle);

                // 执行翻页并获取翻页后的状态
                PageState afterTurn = readerPage.turnPage();

                // 根据翻页后的状态决策
                switch (afterTurn) {
                    case READER:
                    case READER_MENU:
                        // 正常翻页（READER_MENU 是 READER 的可自愈子态，工具栏短暂可见），继续
                        break;

                    case AD_ENTRY_PROMPT:
                    case AD_CONFIRM_DIALOG:
                    case AD_VIDEO_PLAYING:
                    case AD_CLOSE_READY:
                    case AD_CONTINUE_PROMPT:
                    case AD_REWARD_GRANTED:
                        // 遇到广告相关状态，进入广告子循环
                        log.info("[Task] 翻页后遇到广告状态: {}，进入广告子循环", afterTurn);
                        boolean adSuccess = stateMachine.runAdSubLoop();
                        if (!adSuccess) {
                            log.warn("[Task] 广告子循环异常退出，尝试恢复");
                            recoveryHandler.recover(afterTurn);
                        }
                        // 广告子循环结束后确认回到 READER
                        ensureBackToReader();
                        // D5：本书广告轮次达上限且全局目标未达 → 回书架换下一本书继续
                        // C5.2：轮换失败可能把自己丢在书架，破坏「子循环后必在 READER」不变量；
                        // 返回 false 时重新确认处于阅读页家族（含 READER_MENU），否则触发恢复，仍失败则跳过本轮翻页
                        if (!rotateBookIfNeeded() && !confirmInReaderFamily()) {
                            log.warn("[Task] 轮换后无法回到阅读页家族，跳过本轮翻页");
                            break pagesLoop;
                        }
                        break;

                    case CHAPTER_END:
                        // 到达章节末尾，跳转下一章
                        log.info("[Task] 到达章节末尾，跳转下一章");
                        boolean nextChapter = readerPage.goNextChapter();
                        if (!nextChapter) {
                            log.warn("[Task] 跳转下一章失败，尝试恢复");
                            recoveryHandler.recover(PageState.CHAPTER_END);
                            ensureBackToReader();
                        }
                        break;

                    case COMMON_POPUP:
                    case UNKNOWN:
                    case RECOVERY_NEEDED:
                        // 异常状态，交 RecoveryHandler
                        log.warn("[Task] 翻页后遇到异常状态: {}，交由 RecoveryHandler", afterTurn);
                        boolean recovered = recoveryHandler.recover(afterTurn);
                        if (!recovered) {
                            log.error("[Task] 恢复失败，终止当前循环");
                            return false;
                        }
                        ensureBackToReader();
                        break;

                    default:
                        log.debug("[Task] 翻页后状态: {}，继续翻页", afterTurn);
                        break;
                }

                // 检查底部广告入口（即使翻页后状态为 READER，底部可能有入口浮层）
                if (afterTurn.isReaderFamily()) {
                    UiSnapshot currentSnapshot = detector.getCachedSnapshot();
                    if (currentSnapshot != null && readerPage.hasBottomRewardEntry(currentSnapshot)) {
                        // 检查是否需要进入广告流程
                        if (stateMachine.getEarnedMinutes() < config.targetFreeMinutes()
                                && stateMachine.getTotalAdRounds() < config.maxTotalAdRounds()) {
                            log.info("[Task] 检测到底部广告入口，进入广告子循环");
                            boolean clicked = readerPage.clickBottomRewardEntry();
                            if (clicked) {
                                stateMachine.runAdSubLoop();
                                ensureBackToReader();
                                // D5：广告子循环后按需轮换书籍
                                // C5.2：轮换失败时重新确认阅读页家族，仍失败则跳过本轮翻页
                                if (!rotateBookIfNeeded() && !confirmInReaderFamily()) {
                                    log.warn("[Task] 轮换后无法回到阅读页家族，跳过本轮翻页");
                                    break pagesLoop;
                                }
                            }
                        }
                    }
                }
            }

            // 一轮翻页结束后，如果还没达标，主动寻找底部广告入口
            if (stateMachine.getEarnedMinutes() < config.targetFreeMinutes()) {
                log.info("[Task] 本轮翻页结束，主动寻找底部广告入口");
                UiSnapshot endSnapshot = detector.tick();
                if (readerPage.hasBottomRewardEntry(endSnapshot)) {
                    readerPage.clickBottomRewardEntry();
                    stateMachine.runAdSubLoop();
                    ensureBackToReader();
                    // D5：广告子循环后按需轮换书籍
                    // C5.2：轮换失败时重新确认阅读页家族，仍失败则优雅收尾退出外层循环
                    if (!rotateBookIfNeeded() && !confirmInReaderFamily()) {
                        log.warn("[Task] 轮换后无法回到阅读页家族，优雅收尾退出外层循环");
                        break;
                    }
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

        // 等待到达书架或中间状态
        try {
            PageState launchState = waitSupport.untilState(config.appLaunchTimeoutMs(),
                    PageState.BOOKSHELF, PageState.APP_LAUNCHING, PageState.SPLASH_AD,
                    PageState.COMMON_POPUP);
            logger.updateState(launchState);
            log.info("[Task] App 启动后状态: {}", launchState);

            // 即便检测到 BOOKSHELF，也可能因底部「书架」Tab 文案在书城/短剧等主页共享而误判，
            // 故始终主动切一次书架 Tab，确保真正落在书架页再选书（已在书架时为幂等空操作）。
            if (launchState == PageState.BOOKSHELF) {
                return bookshelfPage.switchToShelfTab();
            }

            // 处理中间状态
            int maxAttempts = config.maxRecoveryRetry();
            for (int i = 0; i < maxAttempts; i++) {
                UiSnapshot snapshot = detector.tick();
                PageState state = detector.detect(snapshot);

                switch (state) {
                    case BOOKSHELF:
                        log.info("[Task] 已到达书架页");
                        return true;
                    case SPLASH_AD:
                        log.info("[Task] 检测到开屏广告，尝试关闭");
                        adFlowPage.clickClose();
                        break;
                    case COMMON_POPUP:
                        log.info("[Task] 检测到通用弹窗，尝试关闭");
                        recoveryHandler.dismissPopup(snapshot);
                        break;
                    case APP_LAUNCHING:
                        log.info("[Task] App 仍在启动中，等待...");
                        break;
                    default:
                        log.info("[Task] 当前状态: {}，等待变为书架", state);
                        break;
                }

                // 等待状态变化
                try {
                    Thread.sleep(config.pollIntervalMs() * 4);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            // 多次尝试后仍未到书架
            UiSnapshot finalSnapshot = detector.tick();
            PageState finalState = detector.detect(finalSnapshot);
            if (finalState == PageState.BOOKSHELF) return bookshelfPage.switchToShelfTab();

            // 尝试切换到书架 Tab
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

    /**
     * 返回首页（退出视频播放页）并重新点击底部「书架」Tab。
     * <p>
     * 用户诉求：误开短剧/视频后，先回到含底部导航栏的主页面，再强制点一次「书架」Tab 以回到书架换书。
     *
     * @return true=已回到书架, false=未能回到可导航主页面或切换书架失败
     */
    private boolean backHomeAndReopenShelf() {
        int maxBack = config.maxRecoveryRetry() + 1;
        for (int i = 0; i < maxBack; i++) {
            UiSnapshot s = detector.tick();
            PageState st = detector.detect(s);
            if (st == PageState.BOOKSHELF) {
                break; // 已在含书架锚点的主页面，直接走下面的强制点 Tab
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

    /** 截断书籍标识用于日志（避免过长标题刷屏）。 */
    private String truncateId(String s) {
        if (s == null) return "";
        return s.length() <= 20 ? s : s.substring(0, 20) + "...";
    }

    /**
     * 确保当前状态回到 READER。
     * 如果不在 READER，尝试恢复。
     */
    private void ensureBackToReader() {
        UiSnapshot snapshot = detector.tick();
        PageState state = detector.detect(snapshot);

        if (state.isReaderFamily()) {
            logger.updateState(state);
            return;
        }

        log.info("[Task] 广告子循环后状态为 {}，需要回到 READER", state);

        // 如果在书架，重新打开书
        if (state == PageState.BOOKSHELF) {
            bookshelfPage.openAnyBook();
            stateMachine.markBookUsed(bookshelfPage.getLastOpenedBookId());
            return;
        }

        // 其他状态尝试恢复
        if (state == PageState.COMMON_POPUP) {
            recoveryHandler.dismissPopup(snapshot);
            return;
        }

        // 尝试等待回到 READER
        try {
            PageState result = waitSupport.untilState(config.actionTimeoutMs(),
                    PageState.READER, PageState.READER_MENU, PageState.BOOKSHELF);
            logger.updateState(result);
            if (result == PageState.BOOKSHELF) {
                bookshelfPage.openAnyBook();
                stateMachine.markBookUsed(bookshelfPage.getLastOpenedBookId());
            }
        } catch (Exception e) {
            log.warn("[Task] 无法回到 READER，尝试恢复流程");
            recoveryHandler.recover(state);
        }
    }

    /**
     * C5.2：轮换失败后的状态守卫——重新确认当前处于阅读页家族（READER / READER_MENU）。
     * <p>
     * rotateToNextBook 失败时可能停在书架（已 navigateBackToShelf 但无未用书），
     * 直接继续翻页会破坏「子循环后必在 READER」不变量。本方法先 tick+detect 确认，
     * 若不在阅读页家族则触发恢复并再次确认。
     *
     * @return true=已处于阅读页家族, false=恢复后仍无法回到阅读页家族
     */
    private boolean confirmInReaderFamily() {
        UiSnapshot snapshot = detector.tick();
        PageState state = detector.detect(snapshot);
        if (state.isReaderFamily()) {
            logger.updateState(state);
            return true;
        }
        log.warn("[Task] 轮换后未处于阅读页家族（当前={}），尝试恢复", state);
        recoveryHandler.recover(state);
        snapshot = detector.tick();
        state = detector.detect(snapshot);
        logger.updateState(state);
        return state.isReaderFamily();
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
     *
     * @return true=已回到书架, false=返回失败
     */
    private boolean navigateBackToShelf() {
        int maxBack = config.maxRecoveryRetry();
        for (int i = 0; i < maxBack; i++) {
            UiSnapshot snapshot = detector.tick();
            PageState state = detector.detect(snapshot);
            if (state == PageState.BOOKSHELF) {
                logger.updateState(state);
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
