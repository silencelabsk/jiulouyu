package com.fanqie.auto.core;

import com.fanqie.auto.config.AutomationConfig;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.Dimension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 手势操作封装：所有坐标由运行时屏幕尺寸按比例计算。
 * <p>
 * 真机校准（华为 HarmonyOS）：uiautomator 在设备上频繁崩溃（exit 137），
 * 导致 Appium 的 mobile: clickGesture / swipeGesture 全部失效。
 * 故所有手势改用 adb shell input 命令直接注入输入事件，不经过 uiautomator。
 * <p>
 * 关键设计决策：
 * 1. 代码中不得出现任何绝对像素常量——用户手机是 2376×1080 但绝不能写死。
 *    所有坐标必须由 driver.manage().window().getSize() 运行时按比例计算。
 * 2. 点击/滑动用 adb shell input（不依赖 uiautomator）。
 * 3. App 生命周期用 adb am force-stop / monkey（不依赖 mobile: 命令）。
 * 4. 屏幕尺寸仍通过 Appium driver 获取（该 RPC 不依赖 uiautomator）。
 */
public class GestureSupport implements DriverAware {

    private static final Logger log = LoggerFactory.getLogger(GestureSupport.class);

    private volatile AppiumDriver driver;
    private final AutomationConfig config;

    /** adb 可执行文件绝对路径（运行时探测） */
    private final String adbPath;

    /** 缓存屏幕尺寸，避免每次手势都发 RPC */
    private volatile Dimension cachedSize;

    public GestureSupport(AppiumDriver driver, AutomationConfig config) {
        this.driver = driver;
        this.config = config;
        this.adbPath = detectAdbPath();
        log.info("[GestureSupport] adb 路径: {}", adbPath);
    }

    @Override
    public void refreshDriver(AppiumDriver newDriver) {
        if (newDriver != null) {
            this.driver = newDriver;
            invalidateScreenSizeCache();
        }
    }

    /**
     * 获取屏幕尺寸（带缓存，首次调用时获取）。
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

    // ==================== adb 执行 ====================

    /**
     * 探测 adb 可执行文件路径。
     * 优先级：ANDROID_HOME/platform-tools/adb > PATH 中的 adb > 常见安装路径。
     */
    private static String detectAdbPath() {
        // 1. ANDROID_HOME
        String androidHome = System.getenv("ANDROID_HOME");
        if (androidHome != null && !androidHome.isEmpty()) {
            String candidate = androidHome + "\\platform-tools\\adb.exe";
            if (new java.io.File(candidate).exists()) return candidate;
        }
        // 2. PATH
        for (String dir : System.getenv("Path").split(java.io.File.pathSeparator)) {
            String candidate = dir + "\\adb.exe";
            if (new java.io.File(candidate).exists()) return candidate;
        }
        // 3. 常见 Winget 安装路径
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            String winget = localAppData + "\\Microsoft\\WinGet\\Packages\\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\\platform-tools\\adb.exe";
            if (new java.io.File(winget).exists()) return winget;
        }
        // 4. 退化
        return "adb";
    }

    /**
     * 执行 adb shell 命令。
     *
     * @param args adb shell 后的参数（如 "input tap 100 200"）
     * @return 命令输出（trimmed）
     */
    private String adbShell(String args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(adbPath);
        cmd.add("shell");
        for (String part : args.split("\\s+")) {
            if (!part.isEmpty()) cmd.add(part);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            int exit = p.waitFor();
            if (exit != 0) {
                log.warn("[GestureSupport] adb shell {} 退出码={}: {}", args, exit, sb.toString().trim());
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("[GestureSupport] adb shell 执行失败: {} ({})", args, e.getMessage());
            return "";
        }
    }

    // ==================== 翻页操作 ====================

    /**
     * 翻到下一页。
     * 按 reader.turn.strategy 配置分派：
     * - tap：点击 (next.tap.ratio.x * W, next.tap.ratio.y * H)，默认 0.85W / 0.50H
     * - swipe：adb shell input swipe（direction=left）
     */
    public void turnPageNext() {
        String strategy = config.turnStrategy();
        if ("swipe".equalsIgnoreCase(strategy)) {
            swipeLeft();
        } else {
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
     * 真机校准：用 adb shell input tap（不依赖 uiautomator）。
     */
    public void tapAtPixel(int x, int y) {
        adbShell("input tap " + x + " " + y);
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
     * 向左滑动。
     */
    public void swipeLeft() {
        executeSwipeGesture("left", config.swipePercent(), config.swipeDurationMs());
        log.debug("[GestureSupport] 向左滑动");
    }

    /**
     * 在阅读页内容区域 bounds 内向左滑动。
     * 使用 reader.content.*.ratio 配置约束滑动范围，避免滑到底部触发评论/菜单等控件。
     */
    public void swipeLeftInContentArea() {
        Dimension size = getScreenSize();
        int w = size.getWidth();
        int h = size.getHeight();

        // 计算内容区域 bounds
        int top = (int) (config.contentTopRatio() * h);
        int bottom = (int) (config.contentBottomRatio() * h);
        int left = (int) (config.contentLeftRatio() * w);
        int right = (int) (config.contentRightRatio() * w);

        // 在内容区域中心 y 位置，从右向左滑动
        int cy = (top + bottom) / 2;
        int x1 = right - 20;   // 起点：右侧留 20px 边距
        int x2 = left + 20;    // 终点：左侧留 20px 边距

        adbShell("input swipe " + x1 + " " + cy + " " + x2 + " " + cy + " " + (int) config.swipeDurationMs());
        log.debug("[GestureSupport] 在内容区域内向左滑动: ({},{}) -> ({},{})", x1, cy, x2, cy);
    }

    /**
     * 向右滑动。
     */
    public void swipeRight() {
        executeSwipeGesture("right", config.swipePercent(), config.swipeDurationMs());
        log.debug("[GestureSupport] 向右滑动");
    }

    /**
     * 执行 adb shell input swipe。
     * 真机校准：不依赖 uiautomator，直接注入输入事件。
     *
     * @param direction  方向：up/down/left/right
     * @param percent    滑动距离占屏幕比例 (0.0~1.0)
     * @param durationMs 滑动持续时间（毫秒）
     */
    private void executeSwipeGesture(String direction, double percent, long durationMs) {
        Dimension size = getScreenSize();
        int w = size.getWidth();
        int h = size.getHeight();
        int cx = w / 2;
        int cy = h / 2;
        int dx = (int) (w * percent * 0.8);
        int dy = (int) (h * percent * 0.8);

        int x1, y1, x2, y2;
        switch (direction) {
            case "up":    x1 = cx; y1 = cy + dy / 2; x2 = cx; y2 = cy - dy / 2; break;
            case "down":  x1 = cx; y1 = cy - dy / 2; x2 = cx; y2 = cy + dy / 2; break;
            case "left":  x1 = cx + dx / 2; y1 = cy; x2 = cx - dx / 2; y2 = cy; break;
            case "right": x1 = cx - dx / 2; y1 = cy; x2 = cx + dx / 2; y2 = cy; break;
            default:      x1 = cx; y1 = cy; x2 = cx; y2 = cy;
        }

        adbShell("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + (int) durationMs);
    }

    // ==================== App 生命周期操作 ====================

    /**
     * 激活（启动/切换到前台）指定包名的 App。
     * 用 adb monkey 而非 mobile: activateApp：
     * 有一手资料指出番茄小说启动 Activity 硬编码会报权限拒绝，且版本升级后 Activity 名会变。
     *
     * @param packageName App 包名
     */
    public void activateApp(String packageName) {
        adbShell("monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1");
        log.info("[GestureSupport] 激活 App: {}", packageName);
    }

    /**
     * 终止（强制停止）指定包名的 App。
     * 用 adb am force-stop 而非 mobile: terminateApp。
     *
     * @param packageName App 包名
     */
    public void terminateApp(String packageName) {
        adbShell("am force-stop " + packageName);
        log.info("[GestureSupport] 终止 App: {}", packageName);
    }

    /**
     * 重启 App（先终止再激活）。
     */
    public void restartApp(String packageName) {
        terminateApp(packageName);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        activateApp(packageName);
        log.info("[GestureSupport] App 已重启: {}", packageName);
    }

    // ==================== 设备状态查询 ====================

    /**
     * 获取当前前台 Activity 名称（通过 adb dumpsys，不依赖 uiautomator）。
     * 返回短名称（如 "ReaderActivity"），失败返回 null。
     */
    public String getCurrentActivity() {
        String output = adbShell("dumpsys activity activities | grep mResumedActivity");
        if (output.isEmpty()) return null;
        // 解析 "mResumedActivity: ActivityRecord{... u0 com.dragon.read/.reader.ui.ReaderActivity t94}"
        int slash = output.lastIndexOf('/');
        if (slash < 0) return null;
        int space = output.indexOf(' ', slash);
        if (space < 0) return output.substring(slash + 1);
        return output.substring(slash + 1, space);
    }
}
