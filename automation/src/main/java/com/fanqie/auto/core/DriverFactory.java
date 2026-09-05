package com.fanqie.auto.core;

import io.appium.java_client.AppiumDriver;

/**
 * Driver 工厂接口：可扩展性核心，唯一负责创建/销毁 AndroidDriver。
 * <p>
 * 设计为接口 + 实现的原因：
 * 预留 HdcUiTestDriverFactory 的位置（鸿蒙 NEXT 兜底路线的唯一改动点，上层零感知）。
 * 如果闸门 A 实测发现设备是 HarmonyOS NEXT/5.x（adb 完全不可用），
 * 只需新增一个 HdcUiTestDriverFactory 实现此接口，
 * StateDetector / PageState / LocatorRegistry / AdWatchStateMachine / RunLogger 五层 100% 复用。
 * 这正是分层抽象的价值兑现点。
 * <p>
 * 当前唯一实现：AppiumUiAutomator2DriverFactory（标准 Appium 2 + UiAutomator2 路线）。
 */
public interface DriverFactory {

    /**
     * 创建新的 Appium session 并返回 driver。
     * 首次调用时执行完整的 session 创建流程（安装 UiAutomator2 Server 等）。
     *
     * @return 已连接的 AppiumDriver 实例
     * @throws RuntimeException 如果 session 创建失败（内部已捕获并输出定向中文诊断）
     */
    AppiumDriver create();

    /**
     * 销毁当前 session 并重新创建。
     * 用于 session 断开后的恢复（noReset=true 保证书架与登录态不丢）。
     *
     * @return 新创建的 AppiumDriver 实例
     */
    AppiumDriver recreate();

    /**
     * 低成本探活：检查当前 session 是否仍然有效。
     * 用 driver.getWindowSize() 作为心跳探测，捕获 NoSuchSessionException / InvalidSessionIdException。
     *
     * @return true=session 正常，false=session 已断开或异常
     */
    boolean healthCheck();

    /**
     * 优雅退出：关闭当前 session。
     * 幂等：多次调用安全。
     */
    void quit();

    /**
     * 获取当前 driver 实例（可能为 null，如尚未 create 或已 quit）。
     */
    AppiumDriver getDriver();
}
