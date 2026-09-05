package com.fanqie.auto.core;

import com.fanqie.auto.config.AutomationConfig;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 统一等待原语：杜绝散落 Thread.sleep（除退避重试外）。
 * <p>
 * 设计原则：
 * 1. 全部基于 WebDriverWait(driver, timeout, Duration.ofMillis(pollInterval))，只用显式等待。
 * 2. untilState 支持一次等待多个目标状态——这是稳定性的关键细节：
 *    翻页后必须一次覆盖 READER, AD_ENTRY_PROMPT, AD_CONFIRM_DIALOG, CHAPTER_END，
 *    因为翻页可能直接触发广告弹窗。
 * 3. implicitlyWait 已在 DriverFactory 中设为 0，此处不依赖隐式等待。
 */
public class WaitSupport implements DriverAware {

    private static final Logger log = LoggerFactory.getLogger(WaitSupport.class);

    private volatile AppiumDriver driver;
    private final AutomationConfig config;
    private final StateDetector stateDetector;

    public WaitSupport(AppiumDriver driver, AutomationConfig config, StateDetector stateDetector) {
        this.driver = driver;
        this.config = config;
        this.stateDetector = stateDetector;
    }

    @Override
    public void refreshDriver(AppiumDriver newDriver) {
        if (newDriver != null) this.driver = newDriver;
    }

    /**
     * 等待直到页面状态命中给定目标之一。
     * <p>
     * 这是稳定性的关键细节：翻页后必须一次覆盖多个可能状态
     * （如 READER, AD_ENTRY_PROMPT, AD_CONFIRM_DIALOG, CHAPTER_END），
     * 因为翻页可能直接触发广告弹窗。
     *
     * @param targets 期望的目标状态（命中任一即返回）
     * @return 实际命中的 PageState
     * @throws TimeoutException 超时未命中任何目标状态
     */
    public PageState untilState(PageState... targets) {
        return untilState(config.actionTimeoutMs(), targets);
    }

    /**
     * 等待直到页面状态命中给定目标之一（自定义超时）。
     *
     * @param timeoutMs 超时时间（毫秒）
     * @param targets   期望的目标状态
     * @return 实际命中的 PageState
     */
    public PageState untilState(long timeoutMs, PageState... targets) {
        List<PageState> targetList = Arrays.asList(targets);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofMillis(timeoutMs),
                Duration.ofMillis(config.pollIntervalMs()));

        try {
            return wait.until(d -> {
                UiSnapshot snapshot = stateDetector.tick();
                PageState current = stateDetector.detect(snapshot);
                if (targetList.contains(current)) {
                    return current;
                }
                return null; // 继续轮询
            });
        } catch (TimeoutException e) {
            log.warn("[WaitSupport] 等待状态 {} 超时 ({}ms)，当前状态: {}",
                    targetList, timeoutMs, stateDetector.detect(stateDetector.getCachedSnapshot()));
            throw e;
        }
    }

    /**
     * 等待直到指定状态消失（离开该状态）。
     * 用途：等待 AD_VIDEO_PLAYING 结束、等待 APP_LAUNCHING 完成。
     *
     * @param stateToGone 需要消失的状态
     * @return 消失后的新状态
     */
    public PageState untilStateGone(PageState stateToGone) {
        return untilStateGone(stateToGone, config.maxStateDwellMs());
    }

    /**
     * 等待直到指定状态消失（自定义超时）。
     */
    public PageState untilStateGone(PageState stateToGone, long timeoutMs) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofMillis(timeoutMs),
                Duration.ofMillis(config.pollIntervalMs()));

        try {
            return wait.until(d -> {
                UiSnapshot snapshot = stateDetector.tick();
                PageState current = stateDetector.detect(snapshot);
                if (current != stateToGone) {
                    return current;
                }
                return null; // 仍在目标状态，继续轮询
            });
        } catch (TimeoutException e) {
            log.warn("[WaitSupport] 等待状态 {} 消失超时 ({}ms)", stateToGone, timeoutMs);
            throw e;
        }
    }

    /**
     * 等待直到 UI 树中出现任一候选文案。
     * 用途：等待特定按钮/文本出现。
     *
     * @param candidates 候选文案列表
     * @return 包含匹配文案的快照
     */
    public UiSnapshot untilAnyTextPresent(List<String> candidates) {
        return untilAnyTextPresent(candidates, config.actionTimeoutMs());
    }

    /**
     * 等待直到 UI 树中出现任一候选文案（自定义超时）。
     */
    public UiSnapshot untilAnyTextPresent(List<String> candidates, long timeoutMs) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofMillis(timeoutMs),
                Duration.ofMillis(config.pollIntervalMs()));

        try {
            return wait.until(d -> {
                UiSnapshot snapshot = stateDetector.tick();
                if (!snapshot.findByTextContains(candidates).isEmpty()) {
                    return snapshot;
                }
                return null;
            });
        } catch (TimeoutException e) {
            log.warn("[WaitSupport] 等待文案 {} 出现超时 ({}ms)", candidates, timeoutMs);
            throw e;
        }
    }

    /**
     * 等待直到快照满足自定义条件。
     * 通用等待原语，供上层实现复杂条件判断。
     *
     * @param predicate 快照匹配条件
     * @return 满足条件的快照
     */
    public UiSnapshot untilSnapshotMatches(Predicate<UiSnapshot> predicate) {
        return untilSnapshotMatches(predicate, config.actionTimeoutMs());
    }

    /**
     * 等待直到快照满足自定义条件（自定义超时）。
     */
    public UiSnapshot untilSnapshotMatches(Predicate<UiSnapshot> predicate, long timeoutMs) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofMillis(timeoutMs),
                Duration.ofMillis(config.pollIntervalMs()));

        try {
            return wait.until(d -> {
                UiSnapshot snapshot = stateDetector.tick();
                if (predicate.test(snapshot)) {
                    return snapshot;
                }
                return null;
            });
        } catch (TimeoutException e) {
            log.warn("[WaitSupport] 等待快照条件满足超时 ({}ms)", timeoutMs);
            throw e;
        }
    }

    /**
     * 幂等重试：执行 supplier，失败后退避重试。
     * 退避策略：backoffMs → backoffMs*2 → backoffMs*4（指数退避）。
     * <p>
     * 为什么需要：UI 操作可能因时序竞争偶发失败（如动画未完成、节点尚未挂载），
     * 短暂退避后重试通常能成功，无需走完整恢复流程。
     *
     * @param supplier    要执行的操作
     * @param maxAttempts 最大尝试次数
     * @param backoffMs   首次退避时间（毫秒），后续翻倍
     * @param <T>         返回类型
     * @return supplier 的执行结果
     * @throws RuntimeException 所有重试均失败
     */
    public <T> T runWithRetry(Supplier<T> supplier, int maxAttempts, long backoffMs) {
        Exception lastException = null;
        long currentBackoff = backoffMs;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return supplier.get();
            } catch (Exception e) {
                lastException = e;
                log.warn("[WaitSupport] 第 {}/{} 次尝试失败: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(currentBackoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试被中断", ie);
                    }
                    currentBackoff *= 2; // 指数退避：500ms → 1s → 2s
                }
            }
        }

        throw new RuntimeException("所有 " + maxAttempts + " 次重试均失败", lastException);
    }

    /**
     * 无返回值的幂等重试版本。
     */
    public void runWithRetry(Runnable action, int maxAttempts, long backoffMs) {
        runWithRetry(() -> {
            action.run();
            return null;
        }, maxAttempts, backoffMs);
    }
}
