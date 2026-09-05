package com.fanqie.auto.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AutomationConfig} 与 {@link LocatorRegistry} 的离线单元测试。
 * <p>
 * 验证：
 * <ul>
 *   <li>AutomationConfig 从 classpath 加载的真实配置值（driverType/rewardFallbackMinutes/maxWallClockMs 等新增键）</li>
 *   <li>缺项 / 非法值的默认值兜底（不抛异常）</li>
 *   <li>外部路径加载覆盖 classpath 默认值（--config 场景）</li>
 *   <li>LocatorRegistry 各候选集正确加载且中文无乱码（UTF-8 Reader）</li>
 *   <li>LocatorSpec 的 allowGeometryFallback 约束（adWatch/adContinue=false，adClose=true）</li>
 * </ul>
 * 全程不连接真机、不启动 Appium session。
 */
class ConfigTest {

    // ==================== AutomationConfig：classpath 默认加载 ====================

    @Test
    @DisplayName("AutomationConfig 加载 classpath 配置：新增键 driverType/rewardFallbackMinutes/maxWallClockMs 正确")
    void automationConfig_loadsClasspathValues() {
        AutomationConfig config = new AutomationConfig();
        assertEquals("uiautomator2", config.driverType(), "driver.type 默认应为 uiautomator2");
        assertEquals(30, config.rewardFallbackMinutes(), "flow.reward.fallback.minutes 应为 30");
        assertEquals(10800000L, config.maxWallClockMs(), "flow.max.wall.clock.ms 应为 3 小时");
        assertEquals(120, config.targetFreeMinutes());
        assertEquals(4, config.maxAdRoundsPerEntry());
        assertEquals(40, config.maxTotalAdRounds());
        assertEquals("com.dragon.read", config.appPackage());
    }

    @Test
    @DisplayName("AutomationConfig 缺失键使用内置默认值兜底，不抛异常")
    void automationConfig_missingKeysUseDefaults() {
        AutomationConfig config = new AutomationConfig();
        assertEquals(42, config.getInt("no.such.key", 42));
        assertEquals(99L, config.getLong("no.such.key", 99L));
        assertEquals(1.5, config.getDouble("no.such.key", 1.5), 1e-9);
        assertEquals(true, config.getBoolean("no.such.key", true));
        assertEquals("fallback", config.getString("no.such.key", "fallback"));
    }

    @Test
    @DisplayName("AutomationConfig 外部路径覆盖 classpath 默认值，且非法值回退默认（--config 场景）")
    void automationConfig_externalOverrideAndMalformedFallback(@TempDir Path tempDir) throws IOException {
        Path external = tempDir.resolve("override.properties");
        // driver.type 合法覆盖；flow.target.free.minutes 故意写非法值验证兜底
        String content = "driver.type=harmony_hdc\n"
                + "flow.target.free.minutes=abc\n"
                + "flow.reward.fallback.minutes=15\n";
        Files.write(external, content.getBytes(StandardCharsets.UTF_8));

        AutomationConfig config = new AutomationConfig(external.toString());
        assertEquals("harmony_hdc", config.driverType(), "外部文件应覆盖 driver.type");
        assertEquals(15, config.rewardFallbackMinutes(), "外部文件应覆盖合法整数");
        assertEquals(120, config.targetFreeMinutes(), "非法整数值应回退到内置默认 120");
    }

    @Test
    @DisplayName("AutomationConfig 加载不存在的外部路径时不抛异常，仍保留 classpath 默认值")
    void automationConfig_missingExternalFileIsSafe() {
        AutomationConfig config = new AutomationConfig("/path/that/does/not/exist.properties");
        assertEquals(120, config.targetFreeMinutes(), "外部文件缺失应保留 classpath 默认值");
        assertNotNull(config.rawProperties());
    }

    // ==================== LocatorRegistry：候选集加载与编码 ====================

    @Test
    @DisplayName("LocatorRegistry 各候选集正确加载且中文无乱码")
    void locatorRegistry_loadsCandidateTexts() {
        LocatorRegistry locators = new LocatorRegistry();

        assertTrue(locators.shelfTabTexts().contains("书架"), "书架 Tab 候选应含「书架」");
        // C1.2：splash.skip.text 已收窄为开屏专有词，移除裸「跳过」（裸「跳过」归 ad.close.text），
        // 避免带「跳过」文案的广告关闭页/可跳过激励视频被误判为 SPLASH_AD 抢占 AD_CLOSE_READY。
        assertTrue(locators.splashSkipTexts().contains("跳过广告"), "开屏跳过候选应含专有词「跳过广告」");
        assertFalse(locators.splashSkipTexts().contains("跳过"),
                "C1.2：开屏跳过候选不应再含裸「跳过」（已收窄，裸「跳过」归 ad.close.text）");
        assertTrue(locators.readerMenuTexts().contains("目录"), "阅读菜单候选应含「目录」");
        assertTrue(locators.adWatchTexts().contains("观看广告"), "观看广告候选应含「观看广告」");
        assertTrue(locators.adCloseTexts().contains("关闭"), "关闭文案候选应含「关闭」");
        assertTrue(locators.adCloseDescs().contains("close"), "关闭 desc 候选应含「close」");

        // D4：每日配额耗尽候选集
        List<String> quota = locators.dailyQuotaExhaustedTexts();
        assertFalse(quota.isEmpty(), "配额耗尽候选集不应为空");
        assertTrue(quota.contains("今日已达上限"), "配额耗尽候选应含「今日已达上限」");

        assertNotNull(locators.getRewardRegex());
        assertFalse(locators.getRewardRegex().isEmpty(), "奖励正则不应为空");
    }

    @Test
    @DisplayName("LocatorRegistry getCandidates 对未知键返回空表")
    void locatorRegistry_unknownKeyReturnsEmpty() {
        LocatorRegistry locators = new LocatorRegistry();
        assertTrue(locators.getCandidates("no.such.key").isEmpty());
    }

    @Test
    @DisplayName("LocatorSpec 的 allowGeometryFallback 约束正确（防误点浪费配额）")
    void locatorSpec_geometryFallbackFlags() {
        LocatorRegistry locators = new LocatorRegistry();
        assertTrue(locators.getAdClose().isAllowGeometryFallback(),
                "adClose 应允许几何兜底（漏点后果大于误点）");
        assertFalse(locators.getAdWatch().isAllowGeometryFallback(),
                "adWatch 必须禁用几何兜底（误点浪费配额）");
        assertFalse(locators.getAdContinue().isAllowGeometryFallback(),
                "adContinue 必须禁用几何兜底（误点浪费配额）");
        assertEquals("x>0.8,y<0.2", locators.getAdClose().getBoundsHint(),
                "adClose 的 boundsHint 应为右上角区域");
        assertNotNull(locators.getAdClose().getPrimaryBy());
        assertNotNull(locators.getShelfTab().getFallbackBys());
    }
}
