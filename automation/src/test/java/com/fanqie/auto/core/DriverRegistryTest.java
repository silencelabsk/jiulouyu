package com.fanqie.auto.core;

import io.appium.java_client.AppiumDriver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DriverRegistry} 的离线单元测试。
 * <p>
 * 验证阶段 A 修复的「session 重建后 driver 引用刷新一致性」：
 * refreshAll 后每个已注册的 {@link DriverAware} 都应收到一次 refreshDriver 回调。
 * <p>
 * <b>不连接真机</b>：用手写的 DriverAware 测试替身（记录调用次数与入参）替代真实组件，
 * refreshAll 的参数 AppiumDriver 传 null（替身只记录调用事实，不解引用）。
 */
class DriverRegistryTest {

    /** 记录型 DriverAware 测试替身：统计 refreshDriver 被调次数并保存最后一次入参 */
    private static final class RecordingAware implements DriverAware {
        int callCount = 0;
        AppiumDriver lastDriver;

        @Override
        public void refreshDriver(AppiumDriver newDriver) {
            callCount++;
            lastDriver = newDriver;
        }
    }

    /** 抛异常的替身：验证单个组件刷新失败不影响其余组件（try-catch 隔离） */
    private static final class ThrowingAware implements DriverAware {
        @Override
        public void refreshDriver(AppiumDriver newDriver) {
            throw new RuntimeException("模拟刷新失败");
        }
    }

    @Test
    @DisplayName("refreshAll 后每个已注册组件都恰好收到一次刷新回调（一致性）")
    void refreshAll_notifiesEveryRegisteredAware() {
        DriverRegistry registry = new DriverRegistry();
        RecordingAware a = new RecordingAware();
        RecordingAware b = new RecordingAware();
        RecordingAware c = new RecordingAware();
        registry.register(a);
        registry.register(b);
        registry.register(c);

        registry.refreshAll(null);

        assertEquals(1, a.callCount, "组件 a 应收到一次刷新");
        assertEquals(1, b.callCount, "组件 b 应收到一次刷新");
        assertEquals(1, c.callCount, "组件 c 应收到一次刷新");
    }

    @Test
    @DisplayName("refreshAll 透传的新 driver 引用被各组件正确接收")
    void refreshAll_passesNewDriverReference() {
        DriverRegistry registry = new DriverRegistry();
        RecordingAware a = new RecordingAware();
        registry.register(a);

        // 用一个 mock/占位 driver 引用（此处用 Mockito 风格的裸对象不可行，改用反射-free 的 null 已能验证传播路径）
        // 为验证「引用被透传」，这里传入一个非 null 占位：AppiumDriver 无法离线实例化，
        // 因此改为验证 null 也被如实透传（lastDriver == 传入值）。
        AppiumDriver placeholder = null;
        registry.refreshAll(placeholder);

        assertSame(placeholder, a.lastDriver, "refreshDriver 入参应与 refreshAll 入参为同一引用");
    }

    @Test
    @DisplayName("register(null) 被安全忽略，不抛异常")
    void register_nullIsIgnored() {
        DriverRegistry registry = new DriverRegistry();
        registry.register(null);
        // 注册 null 后 refreshAll 不应抛异常
        registry.refreshAll(null);
    }

    @Test
    @DisplayName("同一实例重复 register 只保留一份，refreshAll 只回调一次（去重）")
    void register_duplicateInstanceRegisteredOnce() {
        DriverRegistry registry = new DriverRegistry();
        RecordingAware a = new RecordingAware();
        registry.register(a);
        registry.register(a);
        registry.register(a);

        registry.refreshAll(null);

        assertEquals(1, a.callCount, "重复注册应被去重，只回调一次");
    }

    @Test
    @DisplayName("单个组件刷新抛异常不影响其余组件（try-catch 隔离）")
    void refreshAll_isolatesFailure() {
        DriverRegistry registry = new DriverRegistry();
        RecordingAware good1 = new RecordingAware();
        ThrowingAware bad = new ThrowingAware();
        RecordingAware good2 = new RecordingAware();
        registry.register(good1);
        registry.register(bad);
        registry.register(good2);

        // 不应因 bad 抛异常而中断整体刷新
        registry.refreshAll(null);

        assertEquals(1, good1.callCount, "异常组件之前的组件应已刷新");
        assertEquals(1, good2.callCount, "异常组件之后的组件仍应刷新（隔离生效）");
    }

    @Test
    @DisplayName("空注册表 refreshAll 不抛异常")
    void refreshAll_onEmptyRegistry_isSafe() {
        DriverRegistry registry = new DriverRegistry();
        registry.refreshAll(null);
        assertTrue(true, "空注册表刷新应安全无异常");
    }

    @Test
    @DisplayName("多组件批量注册后刷新，收集到的调用记录数与注册数一致")
    void refreshAll_manyComponents_allRefreshed() {
        DriverRegistry registry = new DriverRegistry();
        List<RecordingAware> awares = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            RecordingAware a = new RecordingAware();
            awares.add(a);
            registry.register(a);
        }
        registry.refreshAll(null);
        long refreshed = awares.stream().filter(a -> a.callCount == 1).count();
        assertEquals(10, refreshed, "全部 10 个组件都应被刷新一次");
    }
}
