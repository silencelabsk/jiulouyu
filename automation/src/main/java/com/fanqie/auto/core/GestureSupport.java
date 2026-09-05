package com.fanqie.auto.core;

import com.fanqie.auto.config.AutomationConfig;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.Dimension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 手势操作封装：所有坐标由运行时屏幕尺寸按比例计算。
 * <p>
 * 关键设计决策：
 * 1. 代码中不得出现任何绝对像素常量——用户手机是 2376×1080 但绝不能写死。
 *    所有坐标必须由 driver.manage().window().getSize() 运行时按比例计算。
 * 2. 翻页优先用 tap 策略（点击右侧热区），最快最稳。
 *    番茄小说阅读页热区通常是「左1/3上一页、右1/3下一页、中间弹菜单」，
 *    取 0.85 明确落在右三分之一内且避开中间菜单区。
 * 3. swipe 策略用 mobile: swipeGesture（单次设备端执行），
 *    比 W3C PointerInput 多点注入更稳（无注入失败风险）。
 * 4. activateApp / terminateApp 用 mobile: 命令而非 capability 硬编码 appActivity，
 *    因为番茄小说启动 Activity 硬编码会报权限拒绝。
 */
public class GestureSupport implements DriverAware {

    private static final Logger log = LoggerFactory.getLogger(GestureSupport.class);

    private volatile AppiumDriver driver;
    private final AutomationConfig config;

    /** 缓存屏幕尺寸，避免每次手势都发 RPC */
    private volatile Dimension cachedSize;

    public GestureSupport(AppiumDriver driver, AutomationConfig config) {
        this.driver = driver;
        this.config = config;
    }

    @Override
    public void refreshDriver(AppiumDriver newDriver) {
        if (newDriver != null) {
            this.driver = newDriver;
            // M2：与 StateDetector 对齐——driver 刷新后屏幕尺寸缓存失效，
            // 防止 session 重建或横屏/折叠屏旋转后沿用旧尺寸导致比例坐标失真。
            // （同时使 invalidateScreenSizeCache() 被实际调用，消除死 API）
            invalidateScreenSizeCache();
        }
    }

    /**
     * 获取屏幕尺寸（带缓存，首次调用时获取）。
     * 屏幕尺寸在单次运行中通常不变（除非旋转），缓存避免额外 RPC。
     */
    public Dimension getScreenSize() {
        if (cachedSize == null) {
            cachedSize = driver.manage().window().getSize();
            log.info("[GestureSupport] 屏幕尺寸: {}x{}", cachedSize.getWidth(), cachedSize.getHeight());
        }
        return cachedSize;
    }

    /** 清除屏幕尺寸缓存（如设备旋转后需要重新获取） */
    public void invalidateScreenSizeCache() {
        cachedSize = null;
    }

    // ==================== 翻页操作 ====================

    /**
     * 翻到下一页。
     * 按 reader.turn.strategy 配置分派：
     * - tap：点击 (next.tap.ratio.x * W, next.tap.ratio.y * H)，默认 0.85W / 0.50H
     * - swipe：mobile: swipeGesture（direction=left, percent=0.6, duration=300ms）
     */
    public void turnPageNext() {
        String strategy = config.turnStrategy();
        if ("swipe".equalsIgnoreCase(strategy)) {
            swipeLeft();
        } else {
            // 默认 tap 策略
            tapAtRatio(config.nextTapRatioX(), config.nextTapRatioY());
        }
        log.debug("[GestureSupport] 翻到下一页 (策略={})", strategy);
    }

    /**
     * 翻到上一页。
     * 点击 (prev.tap.ratio.x * W, 0.50 * H)，默认 0.15W / 0.50H（左三分之一内）。
     */
    public void turnPagePrev() {
        tapAtRatio(config.prevTapRatioX(), 0.50);
        log.debug("[GestureSupport] 翻到上一页");
    }

    // ==================== 通用手势 ====================

    /**
     * 通用比例点击：将屏幕比例坐标转换为绝对像素并执行点击。
     * 经 mobile: clickGesture 执行（设备端单次执行，比 W3C Actions 更稳）。
     *
     * @param xRatio 相对于屏幕宽度的 x 比例 (0.0~1.0)
     * @param yRatio 相对于屏幕高度的 y 比例 (0.0~1.0)
     */
    public void tapAtRatio(double xRatio, double yRatio) {
        Dimension size = getScreenSize();
        int x = (int) (xRatio * size.getWidth());
        int y = (int) (yRatio * size.getHeight());
        tapAtPixel(x, y);
    }

    /**
     * 在绝对像素坐标处点击。
     * 经 mobile: clickGesture 执行。
     */
    public void tapAtPixel(int x, int y) {
        Map<String, Object> params = new HashMap<>();
        params.put("x", x);
        params.put("y", y);
        driver.executeScript("mobile: clickGesture", params);
        log.debug("[GestureSupport] 点击坐标: ({}, {})", x, y);
    }

    /**
     * 向上滑动（用于书架列表向下滚动查看更多书籍）。
     */
    public void swipeUp() {
        executeSwipeGesture("up", config.swipePercent(), config.swipeDurationMs());
        log.debug("[GestureSupport] 向上滑动");
    }

    /**
     * 向下滑动（用于书架列表向上滚动回到顶部）。
     */
    public void swipeDown() {
        executeSwipeGesture("down", config.swipePercent(), config.swipeDurationMs());
        log.debug("[GestureSupport] 向下滑动");
    }

    /**
     * 向左滑动（翻页备选策略）。
     */
    public void swipeLeft() {
        executeSwipeGesture("left", config.swipePercent(), config.swipeDurationMs());
        log.debug("[GestureSupport] 向左滑动");
    }

    /**
     * 向右滑动。
     */
    public void swipeRight() {
        executeSwipeGesture("right", config.swipePercent(), config.swipeDurationMs());
        log.debug("[GestureSupport] 向右滑动");
    }

    /**
     * 执行 mobile: swipeGesture。
     * 为什么用 mobile: swipeGesture 而非 W3C PointerInput：
     * 前者是单次设备端执行，比多点注入更稳（无注入失败风险），
     * 且 UiAutomator2 driver 对其有专门优化。
     *
     * @param direction  方向：up/down/left/right
     * @param percent    滑动距离占屏幕比例 (0.0~1.0)
     * @param durationMs 滑动持续时间（毫秒），过快可能被系统忽略
     */
    private void executeSwipeGesture(String direction, double percent, long durationMs) {
        Dimension size = getScreenSize();
        Map<String, Object> params = new HashMap<>();
        params.put("left", (int) (size.getWidth() * 0.1));
        params.put("top", (int) (size.getHeight() * 0.1));
        params.put("width", (int) (size.getWidth() * 0.8));
        params.put("height", (int) (size.getHeight() * 0.8));
        params.put("direction", direction);
        params.put("percent", percent);
        params.put("speed", durationMs > 0 ? (int) (size.getHeight() * percent / (durationMs / 1000.0)) : 5000);
        driver.executeScript("mobile: swipeGesture", params);
    }

    // ==================== App 生命周期操作 ====================

    /**
     * 激活（启动/切换到前台）指定包名的 App。
     * 用 mobile: activateApp 而非硬编码 appActivity：
     * 有一手资料指出番茄小说启动 Activity 硬编码会报权限拒绝，且版本升级后 Activity 名会变。
     *
     * @param packageName App 包名
     */
    public void activateApp(String packageName) {
        Map<String, Object> params = new HashMap<>();
        params.put("appId", packageName);
        driver.executeScript("mobile: activateApp", params);
        log.info("[GestureSupport] 激活 App: {}", packageName);
    }

    /**
     * 终止（强制停止）指定包名的 App。
     * 用于恢复流程：terminateApp + activateApp 重启 App。
     * <p>
     * 注意：这里用 terminateApp 而非 removeApp/uninstallApp，
     * 后者会卸载 App 导致用户数据丢失，绝不允许。
     *
     * @param packageName App 包名
     */
    public void terminateApp(String packageName) {
        Map<String, Object> params = new HashMap<>();
        params.put("appId", packageName);
        driver.executeScript("mobile: terminateApp", params);
        log.info("[GestureSupport] 终止 App: {}", packageName);
    }

    /**
     * 重启 App（先终止再激活）。
     * 恢复流程的关键步骤：重启后状态机回到 BOOKSHELF 重新进入。
     */
    public void restartApp(String packageName) {
        terminateApp(packageName);
        // 短暂等待确保进程完全退出
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        activateApp(packageName);
        log.info("[GestureSupport] App 已重启: {}", packageName);
    }
}
