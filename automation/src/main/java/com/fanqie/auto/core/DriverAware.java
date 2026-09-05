package com.fanqie.auto.core;

import io.appium.java_client.AppiumDriver;

/**
 * Driver 感知接口：session 重建后统一刷新各组件持有的 driver 引用。
 * <p>
 * 所有直接持有 {@code AppiumDriver} 字段的组件都应实现本接口，
 * 由 {@link DriverRegistry} 在 recreate 成功后批量回调 {@link #refreshDriver(AppiumDriver)}，
 * 确保旧 driver 引用不会导致 NoSuchSessionException。
 */
public interface DriverAware {

    /**
     * 刷新内部持有的 driver 引用为新实例。
     *
     * @param newDriver 重建后的新 AppiumDriver 实例，不应为 null
     */
    void refreshDriver(AppiumDriver newDriver);
}
