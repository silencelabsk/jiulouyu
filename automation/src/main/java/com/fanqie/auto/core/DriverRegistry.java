package com.fanqie.auto.core;

import io.appium.java_client.AppiumDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Driver 刷新注册表：集中管理所有实现了 {@link DriverAware} 的组件。
 * <p>
 * 当 {@link DriverFactory#recreate()} 成功创建新 session 后，
 * 调用 {@link #refreshAll(AppiumDriver)} 一次性把新 driver 引用传播到所有组件，
 * 杜绝旧 driver 导致的 NoSuchSessionException。
 * <p>
 * 内部使用 {@link CopyOnWriteArrayList} 保证注册与遍历的线程安全。
 */
public class DriverRegistry {

    private static final Logger log = LoggerFactory.getLogger(DriverRegistry.class);

    private final List<DriverAware> awares = new CopyOnWriteArrayList<>();

    /**
     * 注册一个 DriverAware 组件。null 安全，避免重复注册同一实例。
     *
     * @param aware 需要接收 driver 刷新的组件
     */
    public void register(DriverAware aware) {
        if (aware == null) return;
        if (!awares.contains(aware)) {
            awares.add(aware);
        }
    }

    /**
     * 遍历所有已注册组件，刷新其 driver 引用。
     * 单个组件刷新失败不影响其余组件（try-catch 隔离）。
     *
     * @param newDriver 重建后的新 AppiumDriver 实例
     */
    public void refreshAll(AppiumDriver newDriver) {
        int success = 0;
        for (DriverAware aware : awares) {
            try {
                aware.refreshDriver(newDriver);
                success++;
            } catch (Exception e) {
                log.warn("[DriverRegistry] 刷新组件 {} 失败: {}",
                        aware.getClass().getSimpleName(), e.getMessage());
            }
        }
        log.info("[DriverRegistry] 已刷新 {}/{} 个组件的 driver 引用", success, awares.size());
    }
}
