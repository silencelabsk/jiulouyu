package com.fanqie.auto.task;

import com.fanqie.auto.config.AutomationConfig;
import com.fanqie.auto.config.LocatorRegistry;
import com.fanqie.auto.core.DriverAware;
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

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 广告观看状态机——稳定性核心，转移表驱动，绝不写死步骤顺序。
 * <p>
 * 关键设计决策：
 * <ul>
 *   <li><b>转移表驱动</b>：用 Map&lt;PageState, StateHandler&gt; 实现，当前状态决定下一步动作，
 *       任意状态跳变都能被转移表吸收。不假设"先看视频再关闭再继续"的固定顺序。</li>
 *   <li><b>四重独立熔断</b>：
 *       ① 单入口视频轮次 maxAdRoundsPerEntry(4)
 *       ② 全局视频轮次 maxTotalAdRounds(40)
 *       ③ 目标分钟 targetFreeMinutes(120)
 *       ④ 单状态滞留 maxStateDwellMs(90000ms)
 *       四者任一触发即退出当前广告子循环，杜绝死循环空转。</li>
 *   <li><b>以业务量为主退出条件</b>：earnedMinutes >= targetFreeMinutes 是主退出条件；
 *       轮次上限只是熔断保护，不是流程假设。
 *       用户描述的"4个视频=120分钟"被实现为上限保护而非硬编码步数，
 *       这样才能天然吸收"有时3个、有时5个、有时直接跳过、有时弹二次确认"的运营波动。</li>
 *   <li><b>幂等性</b>：每个动作执行前先重新 detect() 确认状态未漂移，
 *       若已漂移则放弃本次动作并按新状态重新决策（杜绝"点了已经消失的按钮"）；
 *       动作后校验状态确实发生了预期转移，未转移则计入失败并走 runWithRetry 退避重试。</li>
 * </ul>
 */
public class AdWatchStateMachine implements DriverAware {

    private static final Logger log = LoggerFactory.getLogger(AdWatchStateMachine.class);

    // ==================== 依赖 ====================
    private volatile AppiumDriver driver;
    private final AutomationConfig config;
    private final LocatorRegistry locators;
    private final StateDetector detector;
    private final WaitSupport waitSupport;
    private final GestureSupport gestures;
    private final RunLogger logger;
    private final AdFlowPage adFlowPage;
    private final ReaderPage readerPage;
    private final BookshelfPage bookshelfPage;
    private final RecoveryHandler recoveryHandler;

    // ==================== 进度状态 ====================
    /** 本次子循环内的广告轮次 */
    private final AtomicInteger currentEntryRound = new AtomicInteger(0);
    /** 全局累计广告轮次 */
    private final AtomicInteger totalAdRounds = new AtomicInteger(0);
    /** 累计获得的免广告分钟数 */
    private final AtomicInteger earnedMinutes = new AtomicInteger(0);
    /** 连续错误计数 */
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    /**
     * M3：广告轮次自增 ID。每真正开始一轮新广告（进入视频流程）时 +1。
     * <p>
     * 替代旧的单纯布尔标志 rewardCountedThisRound：旧方案仅在 AD_VIDEO_PLAYING 重置，
     * 若某轮未经过该态（视频加载快/被误判分流），布尔保留 true → 真实奖励漏计。
     * 改用自增 roundId + lastCountedRoundId，确保“每真实一轮至多计一次且不漏计”。
     */
    private final AtomicInteger roundId = new AtomicInteger(0);
    /** M3：最近一次已计入奖励的 roundId（初值 -1 表示尚无任何轮次计数）。 */
    private final AtomicInteger lastCountedRoundId = new AtomicInteger(-1);

    /** M1：每日配额耗尽连续命中计数（去抖），需连续多 tick 命中才 latch，避免瞬时误判。 */
    private final AtomicInteger quotaExhaustedStreak = new AtomicInteger(0);
    /** M1：连续命中多少个 tick 才确认配额耗尽（去抖阈值）。 */
    private static final int QUOTA_EXHAUSTED_DEBOUNCE_TICKS = 2;
    /** 当前状态进入时间戳 */
    private volatile long stateEnteredAt = System.currentTimeMillis();
    /** 当前状态 */
    private volatile PageState currentState = PageState.UNKNOWN;

    /** D3：任务启动时间戳（墙钟时间熔断基准），构造时初始化 */
    private final long runStartAt = System.currentTimeMillis();

    /** D4：是否已识别到「每日配额耗尽」，命中后走优雅收尾而非反复重试 */
    private volatile boolean dailyQuotaExhausted = false;

    /** D5：已使用过的书籍标识集合（per-book 进度维度，持久化到 progress.properties） */
    private final Set<String> usedBooks = ConcurrentHashMap.newKeySet();

    /** 进度持久化文件路径 */
    private final Path progressFile;

    // ==================== 转移表 ====================
    private final Map<PageState, StateHandler> transitionTable = new EnumMap<>(PageState.class);

    /**
     * 状态处理函数式接口。
     * 返回处理后的实际状态（供状态机判断是否需要重新决策）。
     */
    @FunctionalInterface
    public interface StateHandler {
        PageState handle(PageState confirmedState, UiSnapshot snapshot);
    }

    public AdWatchStateMachine(AppiumDriver driver, AutomationConfig config, LocatorRegistry locators,
                               StateDetector detector, WaitSupport waitSupport, GestureSupport gestures,
                               RunLogger logger, AdFlowPage adFlowPage, ReaderPage readerPage,
                               BookshelfPage bookshelfPage, RecoveryHandler recoveryHandler) {
        this.driver = driver;
        this.config = config;
        this.locators = locators;
        this.detector = detector;
        this.waitSupport = waitSupport;
        this.gestures = gestures;
        this.logger = logger;
        this.adFlowPage = adFlowPage;
        this.readerPage = readerPage;
        this.bookshelfPage = bookshelfPage;
        this.recoveryHandler = recoveryHandler;

        // 进度持久化文件
        String baseDir = System.getProperty("automation.logs.dir", "automation/logs");
        this.progressFile = Paths.get(baseDir, "progress.properties");

        // 从持久化文件恢复进度（断点续跑）
        loadProgress();

        // 构建转移表
        buildTransitionTable();
    }

    @Override
    public void refreshDriver(AppiumDriver newDriver) {
        if (newDriver != null) this.driver = newDriver;
    }

    // ==================== 公开 API ====================

    /**
     * 执行广告子循环：从当前广告相关状态开始，跑到 earned >= targetFreeMinutes 或触及熔断。
     * <p>
     * 调用方在进入此方法前应已确认状态为广告相关态（AD_ENTRY_PROMPT / AD_CONFIRM_DIALOG 等）。
     *
     * @return true=正常结束（达成目标或熔断退出）, false=异常退出（需上层恢复）
     */
    public boolean runAdSubLoop() {
        log.info("[StateMachine] 进入广告子循环 (当前轮次={}, 累计分钟={}, 目标={})",
                currentEntryRound.get(), earnedMinutes.get(), config.targetFreeMinutes());

        // 重置单入口轮次计数
        currentEntryRound.set(0);
        stateEnteredAt = System.currentTimeMillis();

        while (!shouldExitSubLoop()) {
            // 每轮循环开始：tick + detect 获取当前状态
            UiSnapshot snapshot = detector.tick();

            // E2：每 tick 后上报 getPageSource 耗时与 XML 大小，供 RunLogger 心跳输出
            logger.updatePageSourceMetrics(detector.getLastPageSourceMs(), snapshot.xmlLength());

            PageState detectedState = detector.detect(snapshot);

            // 状态变更日志
            if (detectedState != currentState) {
                logger.updateState(detectedState);
                currentState = detectedState;
                stateEnteredAt = System.currentTimeMillis();
            }

            // D4/M1：每日配额耗尽识别（去抖 + 上下文约束）→ 优雅收尾退出，不落入 UNKNOWN 反复重试
            if (!dailyQuotaExhausted) {
                if (matchesDailyQuotaExhausted(snapshot)) {
                    int streak = quotaExhaustedStreak.incrementAndGet();
                    // M1：需连续 QUOTA_EXHAUSTED_DEBOUNCE_TICKS 个 tick 均命中才 latch，避免瞬时误判
                    if (streak >= QUOTA_EXHAUSTED_DEBOUNCE_TICKS) {
                        dailyQuotaExhausted = true;
                        // M1：可观测——latch 时用 log.error 并记录异常分布，区分“达标结束”与“配额结束”
                        log.error("[StateMachine] ★ 连续 {} 个 tick 命中「每日配额耗尽」且满足弹窗上下文，判定配额耗尽，优雅收尾退出 (earned={}min, total={})",
                                streak, earnedMinutes.get(), totalAdRounds.get());
                        logger.recordError("daily_quota_exhausted");
                        saveProgress();
                        return true;
                    }
                } else {
                    // M1：未命中则重置去抖计数（要求“连续”命中）
                    quotaExhaustedStreak.set(0);
                }
            }

            // 单状态滞留熔断检测
            long dwellMs = System.currentTimeMillis() - stateEnteredAt;
            if (dwellMs > config.maxStateDwellMs()) {
                log.warn("[StateMachine] 单状态滞留熔断: state={}, dwell={}ms > max={}ms",
                        currentState, dwellMs, config.maxStateDwellMs());
                logger.recordError("state_dwell_timeout");
                consecutiveErrors.incrementAndGet();
                logger.setConsecutiveErrors(consecutiveErrors.get());
                // 尝试恢复
                boolean recovered = recoveryHandler.recover(currentState);
                if (!recovered) {
                    log.error("[StateMachine] 恢复失败，退出广告子循环");
                    return false;
                }
                stateEnteredAt = System.currentTimeMillis();
                continue;
            }

            // 查找转移表中的处理器
            StateHandler handler = transitionTable.get(detectedState);
            if (handler == null) {
                // 未注册的状态（如 READER）表示已退出广告流程
                if (detectedState == PageState.READER || detectedState == PageState.BOOKSHELF) {
                    log.info("[StateMachine] 状态已回到 {}，广告子循环正常结束", detectedState);
                    return true;
                }
                // 其他未知状态走恢复
                log.warn("[StateMachine] 转移表无处理器: state={}", detectedState);
                consecutiveErrors.incrementAndGet();
                logger.setConsecutiveErrors(consecutiveErrors.get());
                logger.recordError("no_handler_" + detectedState.name());
                boolean recovered = recoveryHandler.recover(detectedState);
                if (!recovered) return false;
                stateEnteredAt = System.currentTimeMillis();
                continue;
            }

            // ★ 幂等性保证：执行动作前已重新 detect() 确认状态（上面刚做的）
            // 执行状态处理器
            try {
                PageState afterState = handler.handle(detectedState, snapshot);

                // 动作后校验状态是否发生了预期转移
                if (afterState == detectedState) {
                    // 状态未变化，计入失败并退避重试
                    consecutiveErrors.incrementAndGet();
                    logger.setConsecutiveErrors(consecutiveErrors.get());
                    logger.recordError("no_transition_" + detectedState.name());
                    log.warn("[StateMachine] 动作后状态未转移: {} (连续错误={})",
                            detectedState, consecutiveErrors.get());

                    if (consecutiveErrors.get() >= config.maxConsecutiveErrors()) {
                        log.error("[StateMachine] 连续错误达上限 {}，触发恢复",
                                config.maxConsecutiveErrors());
                        boolean recovered = recoveryHandler.recover(detectedState);
                        if (!recovered) return false;
                        consecutiveErrors.set(0);
                        logger.resetConsecutiveErrors();
                    } else {
                        // 退避重试
                        backoff(consecutiveErrors.get());
                    }
                } else {
                    // 状态成功转移，重置连续错误
                    consecutiveErrors.set(0);
                    logger.resetConsecutiveErrors();
                    currentState = afterState;
                    stateEnteredAt = System.currentTimeMillis();
                    logger.updateState(afterState);
                }
            } catch (Exception e) {
                log.error("[StateMachine] 状态处理器执行异常: state={}, error={}",
                        detectedState, e.getMessage());
                logger.recordError("handler_exception_" + detectedState.name());
                consecutiveErrors.incrementAndGet();
                logger.setConsecutiveErrors(consecutiveErrors.get());

                if (consecutiveErrors.get() >= config.maxConsecutiveErrors()) {
                    boolean recovered = recoveryHandler.recover(detectedState);
                    if (!recovered) return false;
                    consecutiveErrors.set(0);
                    logger.resetConsecutiveErrors();
                } else {
                    backoff(consecutiveErrors.get());
                }
            }

            // 每轮结束后持久化进度
            saveProgress();
        }

        log.info("[StateMachine] 广告子循环退出 (触发熔断条件): earned={}min, round={}, total={}",
                earnedMinutes.get(), currentEntryRound.get(), totalAdRounds.get());
        saveProgress();
        return true;
    }

    /** 获取累计获得的免广告分钟数 */
    public int getEarnedMinutes() { return earnedMinutes.get(); }
    /** 获取全局广告轮次 */
    public int getTotalAdRounds() { return totalAdRounds.get(); }
    /** 设置累计分钟数（供外部恢复进度） */
    public void setEarnedMinutes(int minutes) { earnedMinutes.set(minutes); }
    /** 设置全局轮次（供外部恢复进度） */
    public void setTotalAdRounds(int rounds) { totalAdRounds.set(rounds); }

    /** D5：获取当前入口（本书）已完成的广告轮次，供上层判断是否需轮换书籍 */
    public int getCurrentEntryRound() { return currentEntryRound.get(); }

    /** D4：是否已识别到「每日配额耗尽」（供 FanqieAdWatchTask 外层循环优雅收尾） */
    public boolean isDailyQuotaExhausted() { return dailyQuotaExhausted; }

    /** D5：标记一本书已使用（per-book 进度），bookId 为空时忽略 */
    public void markBookUsed(String bookId) {
        if (bookId != null && !bookId.trim().isEmpty()) {
            usedBooks.add(bookId.trim());
        }
    }

    /** D5：获取已使用书籍标识集合（供 BookshelfPage.openNextBook 排除） */
    public Set<String> getUsedBooks() { return usedBooks; }

    /**
     * D4/M1：判断快照是否命中「每日配额耗尽」文案候选集。
     * <p>
     * M1 上下文约束：命中配额文案时，要求同时存在弹窗型关闭/取消按钮
     * （commonDismissTexts 或 adCloseTexts），以排除广告卡片/落地页中歧义文案的误判。
     */
    private boolean matchesDailyQuotaExhausted(UiSnapshot snapshot) {
        if (snapshot == null) return false;
        List<String> quotaTexts = locators.dailyQuotaExhaustedTexts();
        if (quotaTexts.isEmpty()) return false;
        if (snapshot.findByTextContains(quotaTexts).isEmpty()) return false;
        // M1：上下文约束——伴随弹窗型关闭/取消按钮才视为真正的配额耗尽弹窗
        return !snapshot.findByTextContains(locators.commonDismissTexts()).isEmpty()
                || !snapshot.findByTextContains(locators.adCloseTexts()).isEmpty();
    }

    // ==================== 熔断判定 ====================

    /**
     * 四重独立熔断判定：任一触发即退出当前广告子循环，杜绝死循环空转。
     * <p>
     * 以业务量为主退出条件：earnedMinutes >= targetFreeMinutes 是主退出条件；
     * 轮次上限只是熔断保护，不是流程假设。
     */
    private boolean shouldExitSubLoop() {
        // 熔断 1：目标分钟数达成（主退出条件）
        if (earnedMinutes.get() >= config.targetFreeMinutes()) {
            log.info("[StateMachine] 熔断触发：目标分钟数已达成 ({}/{})",
                    earnedMinutes.get(), config.targetFreeMinutes());
            return true;
        }
        // 熔断 2：单入口视频轮次达上限
        if (currentEntryRound.get() >= config.maxAdRoundsPerEntry()) {
            log.info("[StateMachine] 熔断触发：单入口视频轮次达上限 ({}/{})",
                    currentEntryRound.get(), config.maxAdRoundsPerEntry());
            return true;
        }
        // 熔断 3：全局视频轮次达上限
        if (totalAdRounds.get() >= config.maxTotalAdRounds()) {
            log.info("[StateMachine] 熔断触发：全局视频轮次达上限 ({}/{})",
                    totalAdRounds.get(), config.maxTotalAdRounds());
            return true;
        }
        // 熔断 5（D3）：墙钟时间预算耗尽 → 长跑优雅收尾（预算 <=0 表示不启用）
        long wallClockBudgetMs = config.maxWallClockMs();
        if (wallClockBudgetMs > 0) {
            long elapsedMs = System.currentTimeMillis() - runStartAt;
            if (elapsedMs >= wallClockBudgetMs) {
                log.info("[StateMachine] 熔断触发：墙钟时间预算已耗尽（已运行 {}ms >= 预算 {}ms），因墙钟预算优雅收尾退出",
                        elapsedMs, wallClockBudgetMs);
                return true;
            }
        }
        // 熔断 4：单状态滞留超时（在主循环中单独检测，此处不重复）
        return false;
    }

    // ==================== 转移表构建 ====================

    private void buildTransitionTable() {
        // APP_LAUNCHING：心跳等待
        transitionTable.put(PageState.APP_LAUNCHING, (state, snapshot) -> {
            log.info("[StateMachine] APP_LAUNCHING：心跳等待启动完成");
            try {
                PageState result = waitSupport.untilState(config.appLaunchTimeoutMs(),
                        PageState.BOOKSHELF, PageState.SPLASH_AD, PageState.COMMON_POPUP);
                return result;
            } catch (Exception e) {
                return detector.detect(detector.tick());
            }
        });

        // SPLASH_AD：优先点击「跳过」按钮，否则等待开屏广告自然消失
        // C1.3：SPLASH_AD 仅处理冷启动开屏广告，不属于“看激励视频领时长”的计费轮次，
        // 故有意不计入 currentEntryRound/totalAdRounds（不计数是正确的）。
        // 检测层已收窄 SPLASH_AD 判定（全屏大面积 + 无右上角关闭 clickable + 开屏专有词），
        // 确保本处理器不会误吞真正的激励视频关闭（AD_CLOSE_READY）。
        transitionTable.put(PageState.SPLASH_AD, (state, snapshot) -> {
            log.info("[StateMachine] SPLASH_AD：尝试跳过开屏广告（不计入广告轮次）");
            // 策略 1：用 splashSkipTexts 直接匹配点击「跳过」按钮
            List<String> skipTexts = locators.splashSkipTexts();
            boolean clicked = adFlowPage.clickByTextCandidates(skipTexts, snapshot);
            if (clicked) {
                log.info("[StateMachine] SPLASH_AD：已通过 splashSkipTexts 点击跳过");
            } else {
                // 策略 2：复用 adFlowPage.clickClose() 的 L1→L4 降级链
                log.info("[StateMachine] SPLASH_AD：splashSkipTexts 未命中，尝试 clickClose 降级链");
                adFlowPage.clickClose();
            }
            // 动作后重新检测状态
            return detector.detect(detector.tick());
        });

        // BOOKSHELF：点书籍条目
        transitionTable.put(PageState.BOOKSHELF, (state, snapshot) -> {
            log.info("[StateMachine] BOOKSHELF：打开下一本未使用的书籍");
            // N3：与 task 层 per-book 记账统一——用 openNextBook 排除已用书籍，成功后 markBookUsed，
            // 避免与 D5 轮换记账错位、在少数书间打转。
            boolean opened = bookshelfPage.openNextBook(usedBooks);
            if (!opened) {
                // 兜底：无未使用书籍时打开任意一本，仍保证不把自己丢在书架空转
                opened = bookshelfPage.openAnyBook();
            }
            if (opened) {
                markBookUsed(bookshelfPage.getLastOpenedBookId());
                return detector.detect(detector.tick());
            }
            return PageState.BOOKSHELF; // 打开失败，状态不变
        });

        // AD_ENTRY_PROMPT：点底部「观看视频获取免广告时长」
        transitionTable.put(PageState.AD_ENTRY_PROMPT, (state, snapshot) -> {
            log.info("[StateMachine] AD_ENTRY_PROMPT：点击底部广告入口");
            boolean clicked = readerPage.clickBottomRewardEntry();
            if (!clicked) {
                log.warn("[StateMachine] 点击底部广告入口失败");
                return PageState.AD_ENTRY_PROMPT;
            }
            // 等待进入广告确认弹窗或直接进入视频播放
            try {
                return waitSupport.untilState(config.actionTimeoutMs(),
                        PageState.AD_CONFIRM_DIALOG, PageState.AD_VIDEO_PLAYING,
                        PageState.AD_CLOSE_READY, PageState.READER);
            } catch (Exception e) {
                return detector.detect(detector.tick());
            }
        });

        // AD_CONFIRM_DIALOG：点「观看广告」
        transitionTable.put(PageState.AD_CONFIRM_DIALOG, (state, snapshot) -> {
            log.info("[StateMachine] AD_CONFIRM_DIALOG：点击「观看广告」");
            boolean clicked = adFlowPage.clickWatchAd();
            if (!clicked) {
                log.warn("[StateMachine] 点击「观看广告」失败（L1未命中且几何兜底被禁止）");
                return PageState.AD_CONFIRM_DIALOG;
            }
            // M3：点击「观看广告」成功即真正开启一轮新广告，递增 roundId（为奖励计数幂等提供依据）。
            // 覆盖“CONFIRM→（跳过 VIDEO）→REWARD”的分流路径，避免该轮奖励漏计。
            roundId.incrementAndGet();
            // 等待进入视频播放
            try {
                return waitSupport.untilState(config.actionTimeoutMs(),
                        PageState.AD_VIDEO_PLAYING, PageState.AD_CLOSE_READY,
                        PageState.AD_CONTINUE_PROMPT, PageState.READER);
            } catch (Exception e) {
                return detector.detect(detector.tick());
            }
        });

        // AD_VIDEO_PLAYING：不动作，心跳等待
        transitionTable.put(PageState.AD_VIDEO_PLAYING, (state, snapshot) -> {
            // M3：新一轮视频开始播放，递增 roundId（与 AD_CONFIRM_DIALOG 共同保证每轮至少递增一次），
            // 供 AD_REWARD_GRANTED 用 lastCountedRoundId 判断“本轮是否已计数”。
            roundId.incrementAndGet();
            log.info("[StateMachine] AD_VIDEO_PLAYING：不做任何操作，等待倒计时结束");
            // 视频播放期间任何点击都可能误触广告落地页
            adFlowPage.waitCountdownFinished();
            return detector.detect(detector.tick());
        });

        // AD_CLOSE_READY：点「关闭」
        transitionTable.put(PageState.AD_CLOSE_READY, (state, snapshot) -> {
            log.info("[StateMachine] AD_CLOSE_READY：点击「关闭」按钮");

            boolean closed = adFlowPage.clickClose();
            if (!closed) {
                log.warn("[StateMachine] 点击「关闭」失败，不计入轮次（避免重试重复计数）");
                return PageState.AD_CLOSE_READY;
            }
            // C4：轮次计数移到 clickClose() 确认关闭成功之后。旧实现进入即无条件自增，
            // 若 clickClose() 失败返回同状态，主循环重试会再次自增 → 熔断被虚假触发、轮换误判。
            currentEntryRound.incrementAndGet();
            totalAdRounds.incrementAndGet();
            logger.updateAdRound(currentEntryRound.get());
            logger.incrementTotalAdRounds();
            // 等待进入后续状态
            try {
                return waitSupport.untilState(config.actionTimeoutMs(),
                        PageState.AD_CONTINUE_PROMPT, PageState.AD_REWARD_GRANTED,
                        PageState.READER, PageState.AD_ENTRY_PROMPT);
            } catch (Exception e) {
                return detector.detect(detector.tick());
            }
        });

        // AD_CONTINUE_PROMPT：判断是否继续观看
        transitionTable.put(PageState.AD_CONTINUE_PROMPT, (state, snapshot) -> {
            // ★ 幂等性：先检查熔断条件再决定是否继续
            boolean shouldContinue = currentEntryRound.get() < config.maxAdRoundsPerEntry()
                    && earnedMinutes.get() < config.targetFreeMinutes()
                    && totalAdRounds.get() < config.maxTotalAdRounds();

            if (shouldContinue) {
                log.info("[StateMachine] AD_CONTINUE_PROMPT：继续观看 (轮次={}/{}, 分钟={}/{})",
                        currentEntryRound.get(), config.maxAdRoundsPerEntry(),
                        earnedMinutes.get(), config.targetFreeMinutes());
                boolean clicked = adFlowPage.clickContinueGetFreeTime();
                if (clicked) {
                    try {
                        return waitSupport.untilState(config.actionTimeoutMs(),
                                PageState.AD_VIDEO_PLAYING, PageState.AD_CONFIRM_DIALOG,
                                PageState.AD_CLOSE_READY);
                    } catch (Exception e) {
                        return detector.detect(detector.tick());
                    }
                }
                // 点击失败，尝试关闭弹窗
                adFlowPage.clickDismissContinuePrompt();
                return detector.detect(detector.tick());
            } else {
                // 已达上限，关闭弹窗退出
                log.info("[StateMachine] AD_CONTINUE_PROMPT：已达上限，关闭弹窗退出");
                adFlowPage.clickDismissContinuePrompt();
                try {
                    return waitSupport.untilState(config.actionTimeoutMs(),
                            PageState.AD_REWARD_GRANTED, PageState.READER, PageState.AD_ENTRY_PROMPT);
                } catch (Exception e) {
                    return detector.detect(detector.tick());
                }
            }
        });

        // AD_REWARD_GRANTED：读取奖励并关闭弹窗
        transitionTable.put(PageState.AD_REWARD_GRANTED, (state, snapshot) -> {
            log.info("[StateMachine] AD_REWARD_GRANTED：读取奖励分钟数");
            // M3：用 roundId + lastCountedRoundId 保证“每真实一轮至多计一次且不漏计”。
            // getAndSet 原子地拿到上一次已计数的 roundId：与当前不同则为本轮首次计数。
            int thisRound = roundId.get();
            int prevCounted = lastCountedRoundId.getAndSet(thisRound);
            if (prevCounted != thisRound) {
                int gained = adFlowPage.readGainedMinutes(snapshot);
                if (gained > 0) {
                    earnedMinutes.addAndGet(gained);
                    logger.addEarnedMinutes(gained);
                    log.info("[StateMachine] 本次获得 {} 分钟（roundId={}），累计 {}/{} 分钟",
                            gained, thisRound, earnedMinutes.get(), config.targetFreeMinutes());
                } else {
                    // M4/C 阶段兜底：readGainedMinutes 无奖励语义命中时返回 0，按 flow.reward.fallback.minutes 保守累加
                    int fallback = config.rewardFallbackMinutes();
                    earnedMinutes.addAndGet(fallback);
                    logger.addEarnedMinutes(fallback);
                    log.warn("[StateMachine] 未从快照中提取到奖励分钟数，按兜底估值累加 {} 分钟（roundId={}），累计 {}/{} 分钟",
                            fallback, thisRound, earnedMinutes.get(), config.targetFreeMinutes());
                }
            } else {
                log.info("[StateMachine] AD_REWARD_GRANTED：本轮（roundId={}）奖励已计数，跳过重复累加", thisRound);
            }

            // 关闭奖励弹窗
            adFlowPage.clickClose();
            try {
                return waitSupport.untilState(config.actionTimeoutMs(),
                        PageState.READER, PageState.AD_CONTINUE_PROMPT, PageState.AD_ENTRY_PROMPT);
            } catch (Exception e) {
                return detector.detect(detector.tick());
            }
        });

        // CHAPTER_END：点「下一章」
        transitionTable.put(PageState.CHAPTER_END, (state, snapshot) -> {
            log.info("[StateMachine] CHAPTER_END：跳转到下一章");
            boolean success = readerPage.goNextChapter();
            if (success) {
                return detector.detect(detector.tick());
            }
            return PageState.CHAPTER_END;
        });

        // COMMON_POPUP：交 RecoveryHandler 关闭
        transitionTable.put(PageState.COMMON_POPUP, (state, snapshot) -> {
            log.info("[StateMachine] COMMON_POPUP：交由 RecoveryHandler 关闭弹窗");
            boolean dismissed = recoveryHandler.dismissPopup(snapshot);
            if (dismissed) {
                return detector.detect(detector.tick());
            }
            return PageState.COMMON_POPUP;
        });

        // READER_MENU：点击正文空白区/中心比例热区收起菜单
        transitionTable.put(PageState.READER_MENU, (state, snapshot) -> {
            log.info("[StateMachine] READER_MENU：点击正文区域收起菜单");
            // 点击屏幕中心偏左下区域（避开菜单工具栏按钮，命中正文空白区收起菜单）
            // 番茄小说阅读页热区：中间点击弹菜单，再次点击中间或翻页区域可收起
            gestures.tapAtRatio(0.85, 0.50);
            // 动作后校验是否回到 READER
            UiSnapshot afterSnap = detector.tick();
            PageState afterState = detector.detect(afterSnap);
            if (afterState == PageState.READER_MENU) {
                // C3.3：第二次不要用屏幕正中央 (0.5,0.5)——那是番茄 toggle 菜单热区，会反复开关菜单。
                // 改用 systemBack() 收起菜单，语义上等价于「返回」，能稳定回到 READER。
                log.debug("[StateMachine] READER_MENU：右侧热区点击未收起，改用 systemBack() 返回收起菜单");
                try {
                    if (driver != null) driver.navigate().back();
                } catch (Exception e) {
                    log.warn("[StateMachine] READER_MENU：systemBack() 异常，回落右侧热区点击: {}", e.getMessage());
                    gestures.tapAtRatio(0.85, 0.50);
                }
                afterSnap = detector.tick();
                afterState = detector.detect(afterSnap);
            }
            return afterState;
        });

        // UNKNOWN / RECOVERY_NEEDED：交 RecoveryHandler 五级恢复
        transitionTable.put(PageState.UNKNOWN, (state, snapshot) -> {
            log.warn("[StateMachine] UNKNOWN：交由 RecoveryHandler 恢复");
            recoveryHandler.recover(state);
            return detector.detect(detector.tick());
        });
        transitionTable.put(PageState.RECOVERY_NEEDED, (state, snapshot) -> {
            log.warn("[StateMachine] RECOVERY_NEEDED：交由 RecoveryHandler 恢复");
            recoveryHandler.recover(state);
            return detector.detect(detector.tick());
        });
    }

    // ==================== 退避重试 ====================

    /**
     * 退避等待：500ms → 1s → 2s（指数退避）。
     * 注意：这是退避重试，不是状态轮询，允许使用 Thread.sleep。
     */
    private void backoff(int errorCount) {
        long backoffMs = 500L * (1L << Math.min(errorCount - 1, 2)); // 500, 1000, 2000
        log.debug("[StateMachine] 退避等待 {}ms (错误计数={})", backoffMs, errorCount);
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 进度持久化 ====================

    /**
     * 从 progress.properties 恢复进度（断点续跑）。
     * <p>
     * session 重建/程序重启后读回 earnedMinutes 和 totalAdRounds，
     * 不重复已完成轮次。
     */
    private void loadProgress() {
        if (!Files.exists(progressFile)) return;
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(progressFile.toFile()), StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(reader);
            int savedEarned = Integer.parseInt(props.getProperty("earnedMinutes", "0"));
            int savedTotal = Integer.parseInt(props.getProperty("totalAdRounds", "0"));
            if (savedEarned > 0 || savedTotal > 0) {
                earnedMinutes.set(savedEarned);
                totalAdRounds.set(savedTotal);
                log.info("[StateMachine] 从进度文件恢复: earnedMinutes={}, totalAdRounds={}",
                        savedEarned, savedTotal);
            }
            // D5/C5.3/N4：恢复 per-book 维度进度（已用书籍标识集合）
            // C5.3：增加时效维度——仅当持久化日期为「当天」时才恢复 usedBooks，
            //       否则清空（跨天残留会导致续跑首次轮换即无书可换）。旧文件缺 lastUpdateDate 字段时保守清空。
            String savedDate = props.getProperty("lastUpdateDate", "");
            boolean sameDay = !savedDate.isEmpty() && savedDate.equals(LocalDate.now().toString());
            String savedBooks = props.getProperty("usedBooks", "");
            if (sameDay && savedBooks != null && !savedBooks.trim().isEmpty()) {
                for (String id : decodeUsedBooks(savedBooks)) {
                    usedBooks.add(id);
                }
                log.info("[StateMachine] 从进度文件恢复当天已用书籍 {} 本: {}", usedBooks.size(), usedBooks);
            } else if (savedBooks != null && !savedBooks.trim().isEmpty()) {
                log.info("[StateMachine] 进度文件的已用书籍非当天（savedDate={}），按跨天清空处理，重新遍历全部书籍", savedDate);
            }
        } catch (Exception e) {
            log.warn("[StateMachine] 加载进度文件失败（将从零开始）: {}", e.getMessage());
        }
    }

    /**
     * 持久化当前进度到 progress.properties。
     */
    private void saveProgress() {
        try {
            Files.createDirectories(progressFile.getParent());
            Properties props = new Properties();
            props.setProperty("earnedMinutes", String.valueOf(earnedMinutes.get()));
            props.setProperty("totalAdRounds", String.valueOf(totalAdRounds.get()));
            // D5/N4：持久化 per-book 维度进度（已用书籍标识集合）
            // N4：每个 id 经 URLEncoder 编码后用 "|" 分隔，避免书名（content-desc/text 原文）含逗号被拆错
            props.setProperty("usedBooks", encodeUsedBooks(usedBooks));
            // C5.3：记录持久化日期，loadProgress 据此判断 usedBooks 是否为当天（跨天清空）
            props.setProperty("lastUpdateDate", LocalDate.now().toString());
            props.setProperty("lastUpdate", String.valueOf(System.currentTimeMillis()));
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(progressFile.toFile()), StandardCharsets.UTF_8)) {
                props.store(writer, "自动化进度持久化（断点续跑）");
            }
        } catch (IOException e) {
            log.warn("[StateMachine] 保存进度文件失败: {}", e.getMessage());
        }
    }

    /**
     * N4：将已用书籍集合编码为持久化字符串（每个 id 经 URL 编码后用 "|" 分隔）。
     * <p>
     * 旧实现用 "," 直接 join，书名（content-desc/text 原文）含逗号时会被拆错。
     */
    private String encodeUsedBooks(Set<String> books) {
        List<String> encoded = new ArrayList<>();
        for (String id : books) {
            if (id == null || id.trim().isEmpty()) continue;
            try {
                encoded.add(URLEncoder.encode(id.trim(), StandardCharsets.UTF_8.name()));
            } catch (Exception e) {
                // 编码失败安全降级：跳过该 id
                log.debug("[StateMachine] usedBooks id 编码失败，跳过: {}", id);
            }
        }
        return String.join("|", encoded);
    }

    /**
     * N4：解码持久化的已用书籍集合。
     * <p>
     * 新格式：每个 id 经 URLEncoder 编码后用 "|" 分隔。
     * 向后兼容：若无 "|" 分隔符则回落按 "," 拆分（旧格式）。
     * 单个 token 解析失败时安全跳过（降级为空集的一部分）。
     */
    private List<String> decodeUsedBooks(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return result;
        String[] tokens = raw.contains("|") ? raw.split("\\|") : raw.split(",");
        for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            try {
                String decoded = URLDecoder.decode(t, StandardCharsets.UTF_8.name());
                if (!decoded.trim().isEmpty()) result.add(decoded.trim());
            } catch (Exception e) {
                // 解析失败安全降级：跳过该 token
                log.debug("[StateMachine] usedBooks token 解码失败，跳过: {}", t);
            }
        }
        return result;
    }

    /**
     * 清除进度文件（新一轮完整运行前调用）。
     */
    public void resetProgress() {
        earnedMinutes.set(0);
        totalAdRounds.set(0);
        currentEntryRound.set(0);
        // M3：重置奖励计数幂等相关的轮次 ID
        roundId.set(0);
        lastCountedRoundId.set(-1);
        // M1：重置配额耗尽去抖计数
        quotaExhaustedStreak.set(0);
        // D5：清除 per-book 进度（已用书籍集合），使新一轮运行可重新遍历全部书籍
        usedBooks.clear();
        // D4：重置每日配额耗尽标志
        dailyQuotaExhausted = false;
        try {
            Files.deleteIfExists(progressFile);
        } catch (IOException e) {
            log.warn("[StateMachine] 删除进度文件失败: {}", e.getMessage());
        }
    }
}
