package com.fanqie.auto.task;

import com.fanqie.auto.TestFixtures;
import com.fanqie.auto.config.AutomationConfig;
import com.fanqie.auto.config.LocatorRegistry;
import com.fanqie.auto.core.UiSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdWatchStateMachine} 中<b>可离线抽离</b>的纯状态逻辑测试。
 * <p>
 * 构造函数不解引用任何依赖（仅赋值 + loadProgress 文件读 + buildTransitionTable 注册 lambda），
 * 因此可用全 null 依赖离线构造，验证进度计数与 per-book 集合等纯内存状态。
 * <p>
 * <b>无法离线覆盖的部分（已在汇报中说明）：</b>
 * <ul>
 *   <li>墙钟熔断判定 shouldExitSubLoop()：private，且依赖 final runStartAt = 构造时刻，
 *       与真实系统时钟强耦合，离线无法在不使用反射/等待的前提下稳定触发。</li>
 *   <li>奖励兜底去重 rewardCountedThisRound.compareAndSet：位于 AD_REWARD_GRANTED 转移表 lambda 内，
 *       需驱动 handler.handle() 并 mock adFlowPage/waitSupport/detector/logger，属集成范畴。</li>
 * </ul>
 * 配额耗尽识别（matchesDailyQuotaExhausted 为 private）通过其等价的公开组合
 * {@code locators.dailyQuotaExhaustedTexts() + snapshot.findByTextContains} 离线验证。
 */
class AdWatchStateMachineTest {

    private AutomationConfig config;
    private LocatorRegistry locators;
    private AdWatchStateMachine sm;
    private String savedLogsDir;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        config = new AutomationConfig();
        locators = new LocatorRegistry();
        // 隔离进度持久化文件到临时目录，避免读到真实 automation/logs/progress.properties
        savedLogsDir = System.getProperty("automation.logs.dir");
        System.setProperty("automation.logs.dir", tempDir.toString());
        // 全 null 依赖离线构造：构造函数不解引用它们，仅赋值 + 注册转移表 lambda
        sm = new AdWatchStateMachine(null, config, locators, null, null,
                null, null, null, null, null, null);
    }

    @AfterEach
    void tearDown() {
        if (savedLogsDir == null) {
            System.clearProperty("automation.logs.dir");
        } else {
            System.setProperty("automation.logs.dir", savedLogsDir);
        }
    }

    @Test
    @DisplayName("全 null 依赖离线构造不抛异常（构造函数不解引用依赖、不连接设备）")
    void construction_isOfflineSafe() {
        assertDoesNotThrow(() -> new AdWatchStateMachine(null, config, locators, null, null,
                null, null, null, null, null, null));
    }

    @Test
    @DisplayName("进度计数 setter/getter 一致（earnedMinutes / totalAdRounds）")
    void progressCounters_areConsistent() {
        sm.setEarnedMinutes(75);
        sm.setTotalAdRounds(3);
        assertEquals(75, sm.getEarnedMinutes());
        assertEquals(3, sm.getTotalAdRounds());
    }

    @Test
    @DisplayName("markBookUsed 忽略 null/空白，收录合法书籍标识（D5 per-book 进度）")
    void markBookUsed_ignoresBlankAndCollectsValid() {
        sm.markBookUsed(null);
        sm.markBookUsed("");
        sm.markBookUsed("   ");
        assertTrue(sm.getUsedBooks().isEmpty(), "null/空白标识应被忽略");

        sm.markBookUsed("book_1001");
        sm.markBookUsed(" book_1002 "); // 应被 trim
        assertTrue(sm.getUsedBooks().contains("book_1001"));
        assertTrue(sm.getUsedBooks().contains("book_1002"), "标识应被 trim 后收录");
        assertEquals(2, sm.getUsedBooks().size());
    }

    @Test
    @DisplayName("初始状态：每日配额未耗尽、当前入口轮次为 0")
    void initialState_isClean() {
        assertFalse(sm.isDailyQuotaExhausted());
        assertEquals(0, sm.getCurrentEntryRound());
    }

    @Test
    @DisplayName("resetProgress 清空累计分钟/轮次/已用书籍集合与配额标志")
    void resetProgress_clearsAllState() {
        sm.setEarnedMinutes(100);
        sm.setTotalAdRounds(5);
        sm.markBookUsed("book_x");

        sm.resetProgress();

        assertEquals(0, sm.getEarnedMinutes());
        assertEquals(0, sm.getTotalAdRounds());
        assertEquals(0, sm.getCurrentEntryRound());
        assertTrue(sm.getUsedBooks().isEmpty());
        assertFalse(sm.isDailyQuotaExhausted());
    }

    // ==================== 配额耗尽识别的纯判定（matchesDailyQuotaExhausted 等价逻辑） ====================

    @Test
    @DisplayName("配额耗尽纯判定：命中 dailyQuotaExhaustedTexts 的快照应被判为耗尽")
    void quotaExhausted_hitsOnQuotaFixture() {
        List<String> quotaTexts = locators.dailyQuotaExhaustedTexts();
        assertFalse(quotaTexts.isEmpty(), "前置：配额耗尽候选集不应为空");

        UiSnapshot quotaSnap = new UiSnapshot(TestFixtures.load("daily_quota.xml"),
                TestFixtures.SCREEN_WIDTH, TestFixtures.SCREEN_HEIGHT);
        // matchesDailyQuotaExhausted 的等价公开组合：候选集非空 且 findByTextContains 命中
        boolean exhausted = !quotaTexts.isEmpty() && !quotaSnap.findByTextContains(quotaTexts).isEmpty();
        assertTrue(exhausted, "含「今日已达上限」的快照应判为配额耗尽");
    }

    @Test
    @DisplayName("配额耗尽纯判定：普通阅读页快照不应误判为耗尽")
    void quotaExhausted_noFalsePositiveOnReader() {
        List<String> quotaTexts = locators.dailyQuotaExhaustedTexts();
        UiSnapshot readerSnap = new UiSnapshot(TestFixtures.load("reader.xml"),
                TestFixtures.SCREEN_WIDTH, TestFixtures.SCREEN_HEIGHT);
        boolean exhausted = !quotaTexts.isEmpty() && !readerSnap.findByTextContains(quotaTexts).isEmpty();
        assertFalse(exhausted, "阅读页无配额耗尽文案，不应误判");
    }
}
