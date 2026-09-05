package com.fanqie.auto.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 全局配置加载器。
 * <p>
 * 从 automation.properties 加载全部配置，带默认值兜底（properties 缺失某项时用内置默认值，不抛异常）。
 * 支持从外部路径加载（供 --config 命令行参数使用）。
 * <p>
 * 设计原则：所有超时、轮次、阈值一律从此类获取，代码中不出现魔法数字。
 */
public class AutomationConfig {

    private final Properties props;

    /**
     * 从 classpath 加载默认配置文件。
     */
    public AutomationConfig() {
        this.props = new Properties();
        loadFromClasspath("automation.properties");
    }

    /**
     * 从外部文件路径加载配置（覆盖 classpath 中的默认值）。
     * 供 --config 参数使用，允许用户不重新编译即可调整配置。
     *
     * @param externalPath 外部 properties 文件的绝对/相对路径
     */
    public AutomationConfig(String externalPath) {
        this.props = new Properties();
        // 先加载 classpath 默认值作为兜底
        loadFromClasspath("automation.properties");
        // 再用外部文件覆盖
        loadFromFile(externalPath);
    }

    private void loadFromClasspath(String resourceName) {
        // 使用 UTF-8 Reader 加载，防止中文注释或值被 ISO-8859-1 解码为乱码
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (is != null) {
                props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            } else {
                System.err.println("[AutomationConfig] 警告：classpath 中未找到 " + resourceName + "，将使用内置默认值");
            }
        } catch (IOException e) {
            System.err.println("[AutomationConfig] 警告：加载 " + resourceName + " 失败: " + e.getMessage());
        }
    }

    private void loadFromFile(String path) {
        try (InputStream is = new FileInputStream(path)) {
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[AutomationConfig] 警告：加载外部配置 " + path + " 失败: " + e.getMessage());
        }
    }

    // ==================== 类型化 Getter ====================

    public String getString(String key, String defaultValue) {
        return props.getProperty(key, defaultValue).trim();
    }

    public int getInt(String key, int defaultValue) {
        String val = props.getProperty(key);
        if (val == null || val.trim().isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            System.err.println("[AutomationConfig] 配置项 " + key + " 值 '" + val + "' 不是合法整数，使用默认值 " + defaultValue);
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String val = props.getProperty(key);
        if (val == null || val.trim().isEmpty()) return defaultValue;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            System.err.println("[AutomationConfig] 配置项 " + key + " 值 '" + val + "' 不是合法长整数，使用默认值 " + defaultValue);
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        String val = props.getProperty(key);
        if (val == null || val.trim().isEmpty()) return defaultValue;
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            System.err.println("[AutomationConfig] 配置项 " + key + " 值 '" + val + "' 不是合法浮点数，使用默认值 " + defaultValue);
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = props.getProperty(key);
        if (val == null || val.trim().isEmpty()) return defaultValue;
        return Boolean.parseBoolean(val.trim());
    }

    // ==================== Appium 配置 ====================

    public String appiumServerUrl() {
        return getString("appium.server.url", "http://127.0.0.1:4723");
    }

    public String platformName() {
        return getString("appium.platform.name", "Android");
    }

    public String automationName() {
        return getString("appium.automation.name", "UiAutomator2");
    }

    /** 设备序列号，留空则 Appium 自动取第一个在线设备 */
    public String deviceUdid() {
        return getString("appium.device.udid", "");
    }

    public String appPackage() {
        return getString("appium.app.package", "com.dragon.read");
    }

    public boolean noReset() {
        return getBoolean("appium.no.reset", true);
    }

    public int newCommandTimeoutSec() {
        return getInt("appium.new.command.timeout.sec", 1800);
    }

    public long uia2ServerLaunchTimeoutMs() {
        return getLong("appium.uia2.server.launch.timeout.ms", 60000L);
    }

    public boolean skipServerInstallation() {
        return getBoolean("appium.skip.server.installation", false);
    }

    public boolean skipDeviceInitialization() {
        return getBoolean("appium.skip.device.initialization", false);
    }

    public boolean keepScreenOn() {
        return getBoolean("appium.keep.screen.on", true);
    }

    // ==================== 驱动类型（可扩展性） ====================

    /**
     * 驱动类型：决定 FanqieRunner 装配哪个 DriverFactory 实现。
     * <p>
     * 当前支持 "uiautomator2"（标准 Appium 2 + UiAutomator2 路线）。
     * 预留 "harmony_hdc" 等未来值（鸿蒙 NEXT 兜底路线），
     * 届时新增对应 DriverFactory 实现即可，上层零改动。
     * properties 缺失或未知值时由 FanqieRunner 回退到 uiautomator2。
     */
    public String driverType() {
        return getString("driver.type", "uiautomator2");
    }

    // ==================== 时序配置 ====================

    public long pollIntervalMs() {
        return getLong("timing.poll.interval.ms", 250L);
    }

    public long stateDetectTimeoutMs() {
        return getLong("timing.state.detect.timeout.ms", 3000L);
    }

    public long actionTimeoutMs() {
        return getLong("timing.action.timeout.ms", 8000L);
    }

    public long appLaunchTimeoutMs() {
        return getLong("timing.app.launch.timeout.ms", 30000L);
    }

    public long adVideoTimeoutMs() {
        return getLong("timing.ad.video.timeout.ms", 45000L);
    }

    public long adCloseReadyTimeoutMs() {
        return getLong("timing.ad.close.ready.timeout.ms", 15000L);
    }

    public long maxStateDwellMs() {
        return getLong("timing.max.state.dwell.ms", 90000L);
    }

    public int heartbeatIntervalSec() {
        return getInt("timing.heartbeat.interval.sec", 30);
    }

    // ==================== 流程配置 ====================

    public int pagesPerCycle() {
        return getInt("flow.pages.per.cycle", 5);
    }

    public int maxAdRoundsPerEntry() {
        return getInt("flow.max.ad.rounds.per.entry", 4);
    }

    public int maxTotalAdRounds() {
        return getInt("flow.max.total.ad.rounds", 40);
    }

    public int targetFreeMinutes() {
        return getInt("flow.target.free.minutes", 120);
    }

    /**
     * C1：奖励兜底估值（分钟）。
     * 当 AD_REWARD_GRANTED 快照未能提取到「N 分钟」数字时，按此保守值累加，
     * 避免因运营文案漂移导致 earnedMinutes 永远无法推进而无法退出。
     */
    public int rewardFallbackMinutes() {
        return getInt("flow.reward.fallback.minutes", 30);
    }

    /**
     * D3：墙钟时间预算（毫秒），长跑熔断保护。
     * 任务累计运行时长达到此值即优雅收尾退出，防止无限期占用设备。
     * 默认 3 小时 = 10800000ms。设为 0 或负值表示不启用墙钟熔断。
     */
    public long maxWallClockMs() {
        return getLong("flow.max.wall.clock.ms", 10800000L);
    }

    public int outerCycles() {
        return getInt("flow.outer.cycles", 2);
    }

    public int maxConsecutiveErrors() {
        return getInt("flow.max.consecutive.errors", 3);
    }

    public int maxRecoveryRetry() {
        return getInt("flow.max.recovery.retry", 3);
    }

    // ==================== 阅读页翻页策略 ====================

    /** 翻页策略："tap" 或 "swipe" */
    public String turnStrategy() {
        return getString("reader.turn.strategy", "tap");
    }

    public double nextTapRatioX() {
        return getDouble("reader.next.tap.ratio.x", 0.85);
    }

    public double nextTapRatioY() {
        return getDouble("reader.next.tap.ratio.y", 0.50);
    }

    public double prevTapRatioX() {
        return getDouble("reader.prev.tap.ratio.x", 0.15);
    }

    public double swipePercent() {
        return getDouble("reader.swipe.percent", 0.6);
    }

    public long swipeDurationMs() {
        return getLong("reader.swipe.duration.ms", 300L);
    }

    // ==================== 验证与调试 ====================

    public boolean verifyPageTurnedByScreenshot() {
        return getBoolean("verify.page.turned.by.screenshot", false);
    }

    public boolean dryRun() {
        return getBoolean("runtime.dry.run", false);
    }

    /**
     * 获取原始 Properties 对象（供 LocatorRegistry 等内部使用）。
     */
    public Properties rawProperties() {
        return props;
    }
}
