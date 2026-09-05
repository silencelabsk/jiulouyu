package com.fanqie.auto.core;

import com.fanqie.auto.config.AutomationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunLogger} 中<b>可离线抽离</b>的保活标志与快照落盘短路逻辑测试。
 * <p>
 * 重点覆盖 C2：captureSnapshot 的短路条件收窄为「仅当 driver==null 时跳过」，
 * 以及 sessionAlive 相关的可离线部分。日志目录隔离到临时目录，避免污染工程 logs。
 * <p>
 * <b>无法离线覆盖的部分（已在汇报中说明）：</b>
 * refreshDriver(newDriver!=null) 复位 sessionAlive.set(true) 的正向路径——
 * AppiumDriver 无法离线实例化（无 Mockito 依赖），故仅能验证 null 入参不误复位；
 * 正向复位路径属集成范畴。
 */
class RunLoggerTest {

    @TempDir
    Path tempDir;

    private String savedLogsDir;
    private RunLogger logger;

    @BeforeEach
    void setUp() {
        savedLogsDir = System.getProperty("automation.logs.dir");
        System.setProperty("automation.logs.dir", tempDir.toString());
        // driver 传 null：本测试只验证保活标志与落盘短路逻辑，不触发真实 Appium 调用
        logger = new RunLogger(null, new AutomationConfig());
    }

    @AfterEach
    void tearDown() {
        if (logger != null) {
            logger.stop(); // 幂等，关闭心跳调度器避免资源泄漏
        }
        if (savedLogsDir == null) {
            System.clearProperty("automation.logs.dir");
        } else {
            System.setProperty("automation.logs.dir", savedLogsDir);
        }
    }

    @Test
    @DisplayName("初始状态 sessionAlive 为 true")
    void initialState_sessionAlive() {
        assertTrue(logger.isSessionAlive());
    }

    @Test
    @DisplayName("markSessionDead 将 sessionAlive 置 false")
    void markSessionDead_flipsFlag() {
        logger.markSessionDead();
        assertFalse(logger.isSessionAlive());
    }

    @Test
    @DisplayName("C2：refreshDriver(null) 不误复位 sessionAlive（仅 newDriver!=null 才复位）")
    void refreshDriver_null_doesNotResurrect() {
        logger.markSessionDead();
        logger.refreshDriver(null);
        assertFalse(logger.isSessionAlive(), "null driver 不应触发保活复位");
    }

    @Test
    @DisplayName("C2：driver==null 时 captureSnapshot 安全短路，不抛异常")
    void captureSnapshot_nullDriver_isSafe() {
        assertDoesNotThrow(() -> logger.captureSnapshot("test-tag"));
    }

    @Test
    @DisplayName("C2：sessionAlive=false（driver==null）时 captureSnapshot 仍安全，不抛异常")
    void captureSnapshot_afterSessionDead_isSafe() {
        logger.markSessionDead();
        // C2：captureSnapshot 短路条件已收窄为仅 driver==null，sessionAlive=false 不再阻止落盘尝试；
        // 此处 driver 恰为 null 故走短路，无论哪条路径都不应抛异常。
        assertDoesNotThrow(() -> logger.captureSnapshot("recovery-failed"));
    }
}
