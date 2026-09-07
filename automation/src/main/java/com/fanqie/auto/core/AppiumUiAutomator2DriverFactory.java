package com.fanqie.auto.core;

import com.fanqie.auto.config.AutomationConfig;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.WebDriverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.time.Duration;

/**
 * 标准 Appium 2 + UiAutomator2 路线的 DriverFactory 实现。
 * <p>
 * 关键设计决策：
 * 1. 绝不设置 appActivity，也绝不设置 app：改用运行时 mobile: activateApp 按包名激活。
 *    有一手资料指出番茄小说启动 Activity 硬编码会报权限拒绝。
 * 2. 绝不能出现任何 uninstall / clearApp / reset 类调用：
 *    capability 里的 noReset=true 是保护用户数据的底线。
 * 3. 创建 driver 后必须立即 implicitlyWait(Duration.ZERO)：
 *    否则未命中的查找会阻塞满隐式超时（常见 10s+），彻底摧毁 250ms 短轮询设计。
 * 4. session 创建超时时捕获并输出定向中文诊断，不抛裸异常堆栈。
 * <p>
 * 扩展点说明：
 * 如果闸门 A 实测发现设备是 HarmonyOS NEXT/5.x（adb 完全不可用），
 * 新增 HdcUiTestDriverFactory 实现 DriverFactory 接口即可，上层零感知。
 * 该类是唯一需要替换的组件，PageState / StateDetector / LocatorRegistry /
 * AdWatchStateMachine / RunLogger 五层 100% 复用。
 */
public class AppiumUiAutomator2DriverFactory implements DriverFactory {

    private static final Logger log = LoggerFactory.getLogger(AppiumUiAutomator2DriverFactory.class);

    private final AutomationConfig config;
    private volatile AppiumDriver driver;

    public AppiumUiAutomator2DriverFactory(AutomationConfig config) {
        this.config = config;
    }

    @Override
    public AppiumDriver create() {
        if (driver != null) {
            log.warn("[DriverFactory] driver 已存在，跳过重复创建");
            return driver;
        }

        try {
            URL serverUrl = new URL(config.appiumServerUrl());
            UiAutomator2Options options = buildCapabilities();

            log.info("[DriverFactory] 正在创建 Appium session...");
            log.info("[DriverFactory] Server: {}", serverUrl);
            log.info("[DriverFactory] 包名: {}, UDID: {}",
                    config.appPackage(),
                    config.deviceUdid().isEmpty() ? "(自动选择)" : config.deviceUdid());

            driver = new AndroidDriver(serverUrl, options);

            // === 关键：必须立即设置 implicitlyWait = 0 ===
            // 否则未命中的查找会阻塞满隐式超时（常见 10s+），彻底摧毁 250ms 短轮询设计。
            // 全部等待逻辑走 WebDriverWait 显式等待。
            driver.manage().timeouts().implicitlyWait(Duration.ZERO);

            log.info("[DriverFactory] Session 创建成功, sessionId={}", driver.getSessionId());
            return driver;

        } catch (Exception e) {
            driver = null;
            printSessionCreationDiagnostics(e);
            throw new RuntimeException("Appium session 创建失败，请根据上方诊断信息排查", e);
        }
    }

    @Override
    public AppiumDriver recreate() {
        log.info("[DriverFactory] 正在重建 session...");
        quit(); // 先安全退出旧 session
        return create();
    }

    @Override
    public boolean healthCheck() {
        if (driver == null) return false;
        try {
            // 低成本探活：getWindowSize() 是最轻量的命令之一
            driver.manage().window().getSize();
            return true;
        } catch (NoSuchSessionException e) {
            log.warn("[DriverFactory] 健康检查失败: session 不存在");
            return false;
        } catch (WebDriverException e) {
            // InvalidSessionIdException 是 WebDriverException 的子类
            log.warn("[DriverFactory] 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void quit() {
        if (driver == null) return;
        try {
            driver.quit();
            log.info("[DriverFactory] Session 已正常退出");
        } catch (Throwable e) {
            // 捕获 Throwable 而非 Exception：selenium-devtools 缺失时 quit() 会抛 NoClassDefFoundError
            log.warn("[DriverFactory] 退出 session 时异常（可忽略）: {}", e.getMessage());
        } finally {
            driver = null;
        }
    }

    @Override
    public AppiumDriver getDriver() {
        return driver;
    }

    /**
     * 构建 UiAutomator2Options capabilities。
     * <p>
     * 绝不设置 appActivity / app：改用运行时 mobile: activateApp 按包名激活。
     * 绝不设置任何 uninstall / clearApp / reset 相关 capability。
     */
    private UiAutomator2Options buildCapabilities() {
        UiAutomator2Options options = new UiAutomator2Options();

        // 基础配置
        options.setPlatformName(config.platformName());
        options.setAutomationName(config.automationName());

        // 设备序列号：留空则不设，让 Appium 自动取第一个在线设备
        String udid = config.deviceUdid();
        if (!udid.isEmpty()) {
            options.setUdid(udid);
        }

        // noReset=true：保留登录态与书架数据，是长任务必需的底线配置
        options.setNoReset(config.noReset());

        // newCommandTimeout=1800s：配合心跳保活，彻底规避广告视频 30s 静默期触发断连
        options.setNewCommandTimeout(Duration.ofSeconds(config.newCommandTimeoutSec()));

        // 华为/鸿蒙设备特殊配置
        options.setAutoGrantPermissions(true);
        // ignoreHiddenApiPolicyError：华为/鸿蒙常见 hidden API 拦截
        options.setCapability("appium:ignoreHiddenApiPolicyError", true);
        // suppressKillServer：防止 Appium 杀死 adb server（多任务并行时重要）
        options.setCapability("appium:suppressKillServer", true);

        // 性能优化
        // disableIdLocatorAutocompletion：跳过 resource-id 自动补全，提速
        options.setCapability("appium:disableIdLocatorAutocompletion", true);
        // UiAutomator2 Server 启动超时：华为设备首启慢，给足 60s
        options.setCapability("appium:uiautomator2ServerLaunchTimeout", config.uia2ServerLaunchTimeoutMs());
        // UiAutomator2 Server 安装超时：Appium 默认 20s 对华为太短（首装 + 「监控 ADB 安装应用」弹窗），放宽到配置值（默认 120s）
        options.setCapability("appium:uiautomator2ServerInstallTimeout", config.uia2ServerInstallTimeoutMs());

        // 保持屏幕常亮
        if (config.keepScreenOn()) {
            options.setCapability("appium:keepScreenOn", true);
        }

        // 按配置决定是否跳过安装（首轮成功后可改为 true 加速）
        if (config.skipServerInstallation()) {
            options.setCapability("appium:skipServerInstallation", true);
        }
        if (config.skipDeviceInitialization()) {
            options.setCapability("appium:skipDeviceInitialization", true);
        }

        // === 绝不设置 appActivity / app ===
        // 改用运行时 mobile: activateApp 按包名激活
        // 有一手资料指出番茄小说启动 Activity 硬编码会报权限拒绝

        return options;
    }

    /**
     * session 创建失败时输出定向中文诊断，不抛裸异常堆栈。
     * 覆盖最常见的华为设备失败场景。
     */
    private void printSessionCreationDiagnostics(Exception e) {
        log.error("╔══════════════════════════════════════════════════════════════╗");
        log.error("║          Appium Session 创建失败 —— 定向诊断                 ║");
        log.error("╠══════════════════════════════════════════════════════════════╣");
        log.error("║ 异常信息: {}", e.getMessage());
        log.error("╠══════════════════════════════════════════════════════════════╣");
        log.error("║ 请逐项排查：");
        log.error("║ 1. 手机是否有待确认的 APK 安装弹窗？");
        log.error("║    → 华为「监控 ADB 安装应用」开启时，Appium 推送的 3 个 APK 会弹确认框");
        log.error("║    → 请在手机上逐个点击「允许安装」");
        log.error("║ 2. 是否开启了「仅充电模式下允许 ADB 调试」？");
        log.error("║    → 设置 → 系统和更新 → 开发人员选项 → 仅充电模式下允许 ADB 调试");
        log.error("║    → 这是华为/鸿蒙特有且最容易漏掉的一项");
        log.error("║ 3. USB 调试是否已开启？");
        log.error("║    → 设置 → 系统和更新 → 开发人员选项 → USB 调试");
        log.error("║ 4. 手机是否已授权本电脑？");
        log.error("║    → 重新插拔 USB 线，在手机上勾选「始终允许来自这台计算机的调试」");
        log.error("║ 5. Appium Server 是否已启动？");
        log.error("║    → 终端执行: appium --base-path /");
        log.error("║    → 检查 http://127.0.0.1:4723/status 是否返回 ready");
        log.error("║ 6. adb 是否能识别设备？");
        log.error("║    → 终端执行: adb devices -l");
        log.error("║    → 期望看到: UJN0220C17010611   device");
        log.error("╚══════════════════════════════════════════════════════════════╝");
    }
}
