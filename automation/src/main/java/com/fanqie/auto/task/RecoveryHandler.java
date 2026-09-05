package com.fanqie.auto.task;

import com.fanqie.auto.config.AutomationConfig;
import com.fanqie.auto.config.LocatorRegistry;
import com.fanqie.auto.core.DriverAware;
import com.fanqie.auto.core.DriverFactory;
import com.fanqie.auto.core.DriverRegistry;
import com.fanqie.auto.core.GestureSupport;
import com.fanqie.auto.core.PageState;
import com.fanqie.auto.core.RunLogger;
import com.fanqie.auto.core.StateDetector;
import com.fanqie.auto.core.UiNode;
import com.fanqie.auto.core.UiSnapshot;
import com.fanqie.auto.core.WaitSupport;
import com.fanqie.auto.page.BookshelfPage;
import io.appium.java_client.AppiumDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 五级自愈处理器：保证任意步失败都能回到已知锚点。
 * <p>
 * 五级恢复策略（由弱到强）：
 * <ol>
 *   <li><b>COMMON_POPUP 处理</b>：按 LocatorRegistry.commonDismissTexts() 候选文案表
 *       由弱到强尝试关闭（应对青少年模式弹窗、权限弹窗、登录弹窗、更新提示、网络异常等）。
 *       <p>
 *       「由弱到强」的顺序理由：先试无副作用的「我知道了/以后再说/暂不/跳过」，
 *       最后才试「取消/不同意」。因为「取消」在某些弹窗中可能触发退出确认或返回上一级，
 *       而「我知道了」只是确认关闭弹窗本身，副作用最小。</p></li>
 *   <li><b>逐级 back()</b>：最多 maxRecoveryRetry 次，每次 back 后重新 detect() 确认是否已回到锚点。</li>
 *   <li><b>重启 App</b>：terminateApp + activateApp，状态机回到 BOOKSHELF。</li>
 *   <li><b>导航到书架</b>：重启后若状态非 BOOKSHELF，主动调 switchToShelfTab()。</li>
 *   <li><b>重建 session</b>：连续失败达 maxConsecutiveErrors 次时，
 *       driverFactory.recreate()（noReset=true 保证书架与登录态不丢）→ 从断点继续。</li>
 *   <li><b>落盘诊断</b>：重建仍失败则 captureSnapshot("recovery-failed") 保存现场后退出。</li>
 * </ol>
 * <p>
 * <b>安全底线：超过 maxRecoveryRetry(3) 则停止并保留现场，绝不盲目连点。</b>
 */
public class RecoveryHandler implements DriverAware {

    private static final Logger log = LoggerFactory.getLogger(RecoveryHandler.class);

    private volatile AppiumDriver driver;
    private final AutomationConfig config;
    private final LocatorRegistry locators;
    private final StateDetector detector;
    private final WaitSupport waitSupport;
    private final GestureSupport gestures;
    private final RunLogger logger;
    private final DriverFactory driverFactory;
    private final DriverRegistry registry;

    /** 连续错误计数（跨多次 recover 调用累积） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /** BookshelfPage 引用（延迟注入，避免循环依赖） */
    private BookshelfPage bookshelfPage;

    public RecoveryHandler(AppiumDriver driver, AutomationConfig config, LocatorRegistry locators,
                           StateDetector detector, WaitSupport waitSupport, GestureSupport gestures,
                           RunLogger logger, DriverFactory driverFactory, DriverRegistry registry) {
        this.driver = driver;
        this.config = config;
        this.locators = locators;
        this.detector = detector;
        this.waitSupport = waitSupport;
        this.gestures = gestures;
        this.logger = logger;
        this.driverFactory = driverFactory;
        this.registry = registry;
    }

    @Override
    public void refreshDriver(AppiumDriver newDriver) {
        if (newDriver != null) this.driver = newDriver;
    }

    /**
     * 设置 BookshelfPage 引用（延迟注入，避免构造时循环依赖）。
     */
    public void setBookshelfPage(BookshelfPage bookshelfPage) {
        this.bookshelfPage = bookshelfPage;
    }

    // ==================== 公开 API ====================

    /**
     * 执行恢复流程：尝试从当前异常状态回到已知锚点（READER 或 BOOKSHELF）。
     * <p>
     * 五级恢复由弱到强逐级尝试，超过 maxRecoveryRetry 则停止并保留现场。
     *
     * @param currentState 当前检测到的异常状态
     * @return true=恢复到锚点（READER/BOOKSHELF）, false=恢复失败
     */
    public boolean recover(PageState currentState) {
        log.info("[Recovery] 开始恢复流程 (当前状态={}, 连续失败={})", currentState, consecutiveFailures.get());

        int maxRetry = config.maxRecoveryRetry();

        // === 第 1 级：如果是 COMMON_POPUP，先尝试关闭弹窗 ===
        if (currentState == PageState.COMMON_POPUP) {
            UiSnapshot snapshot = detector.tick();
            boolean dismissed = dismissPopup(snapshot);
            if (dismissed) {
                PageState afterDismiss = detector.detect(detector.tick());
                // N2：第1级恢复成功判定收窄为显式锚点白名单（与第2级一致），
                // 不再用 !isAbnormal()——后者未覆盖新增状态，会把 READER_MENU/SPLASH_AD/APP_LAUNCHING 等误判为“已恢复”。
                if (afterDismiss.isReaderFamily() || afterDismiss == PageState.BOOKSHELF) {
                    log.info("[Recovery] 弹窗关闭成功，恢复到状态: {}", afterDismiss);
                    consecutiveFailures.set(0);
                    logger.resetConsecutiveErrors();
                    return true;
                }
            }
        }

        // === 第 2 级：逐级 back()，每次 back 后重新 detect() ===
        for (int i = 0; i < maxRetry; i++) {
            log.info("[Recovery] 第 2 级恢复：执行 back() ({}/{})", i + 1, maxRetry);
            try {
                driver.navigate().back();
            } catch (Exception e) {
                log.warn("[Recovery] back() 执行异常: {}", e.getMessage());
                break;
            }

            // back 后等待 UI 稳定并重新检测
            try {
                Thread.sleep(config.pollIntervalMs() * 2);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }

            UiSnapshot snapshot = detector.tick();
            PageState afterBack = detector.detect(snapshot);
            logger.updateState(afterBack);

            // 检查是否已回到锚点
            if (afterBack == PageState.READER || afterBack == PageState.BOOKSHELF) {
                log.info("[Recovery] back() 后已回到锚点: {}", afterBack);
                consecutiveFailures.set(0);
                logger.resetConsecutiveErrors();
                return true;
            }

            // 如果 back 后遇到弹窗，尝试关闭
            if (afterBack == PageState.COMMON_POPUP) {
                dismissPopup(snapshot);
            }
        }

        // === 第 3 级：重启 App ===
        log.info("[Recovery] 第 3 级恢复：重启 App");
        try {
            gestures.restartApp(config.appPackage());
        } catch (Exception e) {
            log.error("[Recovery] 重启 App 异常: {}", e.getMessage());
        }

        // 等待 App 启动完成
        try {
            PageState afterRestart = waitSupport.untilState(config.appLaunchTimeoutMs(),
                    PageState.BOOKSHELF, PageState.SPLASH_AD, PageState.COMMON_POPUP,
                    PageState.APP_LAUNCHING);
            logger.updateState(afterRestart);

            // === 第 4 级：重启后若状态非 BOOKSHELF，主动导航 ===
            if (afterRestart == PageState.BOOKSHELF) {
                log.info("[Recovery] 重启后已回到书架，恢复成功");
                consecutiveFailures.set(0);
                logger.resetConsecutiveErrors();
                return true;
            }

            if (afterRestart == PageState.SPLASH_AD || afterRestart == PageState.COMMON_POPUP) {
                // 等待开屏广告/弹窗结束
                try {
                    Thread.sleep(config.pollIntervalMs() * 4);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                UiSnapshot splashSnapshot = detector.tick();
                if (afterRestart == PageState.COMMON_POPUP) {
                    dismissPopup(splashSnapshot);
                } else {
                    // 尝试关闭开屏广告
                    List<String> closeTexts = locators.adCloseTexts();
                    clickByTexts(closeTexts, splashSnapshot);
                }
            }

            // 主动导航到书架
            if (bookshelfPage != null) {
                log.info("[Recovery] 第 4 级恢复：主动导航到书架 Tab");
                boolean switched = bookshelfPage.switchToShelfTab();
                if (switched) {
                    consecutiveFailures.set(0);
                    logger.resetConsecutiveErrors();
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("[Recovery] 重启后等待状态超时: {}", e.getMessage());
        }

        // === 第 5 级：重建 session ===
        consecutiveFailures.incrementAndGet();
        logger.setConsecutiveErrors(consecutiveFailures.get());

        if (consecutiveFailures.get() >= config.maxConsecutiveErrors()) {
            log.warn("[Recovery] 第 5 级恢复：连续失败 {} 次，尝试重建 session",
                    consecutiveFailures.get());
            try {
                AppiumDriver newDriver = driverFactory.recreate();
                if (newDriver != null) {
                    log.info("[Recovery] session 重建成功");
                    // ★ 关键修复：刷新所有组件的 driver 引用，防止旧 driver 导致 NoSuchSessionException
                    registry.refreshAll(newDriver);
                    log.info("[Recovery] 已通过 DriverRegistry 刷新所有组件的 driver 引用");
                    // 重建后尝试回到书架
                    gestures.activateApp(config.appPackage());
                    try {
                        PageState afterRecreate = waitSupport.untilState(config.appLaunchTimeoutMs(),
                                PageState.BOOKSHELF, PageState.COMMON_POPUP);
                        logger.updateState(afterRecreate);
                        if (afterRecreate == PageState.BOOKSHELF) {
                            consecutiveFailures.set(0);
                            logger.resetConsecutiveErrors();
                            return true;
                        }
                    } catch (Exception e) {
                        log.warn("[Recovery] 重建后等待状态超时");
                    }
                }
            } catch (Exception e) {
                log.error("[Recovery] session 重建失败: {}", e.getMessage());
            }
        }

        // === 第 6 级：落盘诊断快照后退出 ===
        log.error("[Recovery] ★ 所有恢复手段均失败，保留现场并退出");
        log.error("[Recovery] 超过 maxRecoveryRetry={}，停止并保留现场，绝不盲目连点", config.maxRecoveryRetry());
        logger.recordError("recovery_failed");
        logger.captureSnapshot("recovery-failed");
        logger.markSessionDead();
        return false;
    }

    /**
     * 关闭通用弹窗（COMMON_POPUP）。
     * <p>
     * 按 LocatorRegistry.commonDismissTexts() 候选文案表由弱到强尝试关闭。
     * <p>
     * 「由弱到强」的顺序理由：
     * - 先试「我知道了/以后再说/暂不/跳过/知道了/下次再说/忽略/稍后再说」—— 无副作用，只关闭弹窗
     * - 最后试「取消/不同意/关闭」—— 「取消」在某些弹窗中可能触发退出确认或返回上一级
     * <p>
     * 覆盖场景：青少年模式弹窗、权限弹窗、登录弹窗、更新提示、网络异常提示等。
     *
     * @param snapshot 当次 UI 快照
     * @return true=成功关闭弹窗, false=未找到可关闭的按钮
     */
    public boolean dismissPopup(UiSnapshot snapshot) {
        if (snapshot == null) return false;

        List<String> dismissTexts = locators.commonDismissTexts();
        if (dismissTexts.isEmpty()) {
            log.warn("[Recovery] commonDismissTexts 为空，无法关闭弹窗");
            return false;
        }

        log.info("[Recovery] 尝试关闭通用弹窗（由弱到强，候选数={}）", dismissTexts.size());

        // 由弱到强遍历候选文案：
        // properties 中的顺序已经按「弱→强」排列：
        // 我知道了|以后再说|取消|暂不|跳过|不同意|知道了|下次再说|关闭|不再提示|忽略|稍后再说
        // 这里按列表顺序逐个尝试，前面的副作用更小
        for (String text : dismissTexts) {
            List<UiNode> hits = snapshot.findByTextContains(java.util.Collections.singletonList(text));
            if (!hits.isEmpty()) {
                // 找到匹配的节点，点击第一个可点击的（或其可点击祖先）
                UiNode target = hits.get(0);
                if (!target.isClickable()) {
                    // 尝试找可点击祖先
                    var ancestor = snapshot.nearestClickableAncestor(target);
                    if (ancestor.isPresent()) {
                        target = ancestor.get();
                    }
                }
                if (target.isHasBounds()) {
                    int x = target.getBounds().centerX();
                    int y = target.getBounds().centerY();
                    gestures.tapAtPixel(x, y);
                    log.info("[Recovery] 弹窗关闭成功：点击了文案「{}」at ({}, {})", text, x, y);
                    return true;
                }
            }
        }

        // 文案匹配全部未命中，尝试 content-desc
        for (String desc : dismissTexts) {
            List<UiNode> hits = snapshot.findByContentDescContains(java.util.Collections.singletonList(desc));
            if (!hits.isEmpty()) {
                UiNode target = hits.get(0);
                if (target.isHasBounds()) {
                    gestures.tapAtPixel(target.getBounds().centerX(), target.getBounds().centerY());
                    log.info("[Recovery] 弹窗关闭成功：点击了 desc「{}」", desc);
                    return true;
                }
            }
        }

        log.warn("[Recovery] 通用弹窗关闭失败：所有候选文案均未命中");
        return false;
    }

    // ==================== 内部方法 ====================

    /**
     * 在快照中按文案列表查找并点击（RecoveryHandler 内部用，不依赖 BasePage）。
     */
    private boolean clickByTexts(List<String> texts, UiSnapshot snapshot) {
        if (texts == null || texts.isEmpty() || snapshot == null) return false;
        List<UiNode> hits = snapshot.findByTextContains(texts);
        if (hits.isEmpty()) return false;

        UiNode target = hits.get(0);
        if (!target.isClickable()) {
            var ancestor = snapshot.nearestClickableAncestor(target);
            if (ancestor.isPresent()) target = ancestor.get();
        }
        if (target.isHasBounds()) {
            gestures.tapAtPixel(target.getBounds().centerX(), target.getBounds().centerY());
            return true;
        }
        return false;
    }
}
