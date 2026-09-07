package com.fanqie.auto.core;

import com.fanqie.auto.config.AutomationConfig;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.OutputType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行日志器：长任务可观测性 + Appium session 保活双职责。
 * <p>
 * 关键设计决策：
 * 1. 独立 daemon ScheduledExecutorService，每 heartbeat.interval.sec=30 秒执行一次
 *    轻量 driver.getWindowSize() 主动保活（即使业务逻辑处于广告视频的 30s 长静默期
 *    也不会触发 newCommandTimeout）。
 * 2. 保活线程内部必须自己捕获所有异常，绝不能让异常杀死调度线程。
 * 3. session 已断开时把状态标记出来供上层感知。
 * 4. 文本日志落盘 automation/logs/run-<时间戳>.log；
 *    异常与恢复时截图 + XML 快照落盘 automation/logs/snapshots/。
 * 5. 只写 automation/logs，绝不写工程根或 src。目录需自动创建。文件写入统一 UTF-8。
 * 6. XML 长度异常膨胀（如超 1MB）即输出预警。
 * 7. stop() 必须幂等（可能被 finally 与 ShutdownHook 各调一次）。
 */
public class RunLogger implements DriverAware {

    private static final Logger log = LoggerFactory.getLogger(RunLogger.class);
    private static final DateTimeFormatter FILE_TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter LOG_TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** XML 膨胀预警阈值：1MB */
    private static final int XML_SIZE_WARNING_THRESHOLD = 1024 * 1024;

    private volatile AppiumDriver driver;
    private final AutomationConfig config;
    private final Path logsDir;
    private final Path snapshotsDir;
    private final Path logFile;
    private PrintWriter fileWriter;

    // ==================== 可被状态机更新的计数器 ====================
    private volatile PageState currentState = PageState.UNKNOWN;
    private final AtomicInteger currentAdRound = new AtomicInteger(0);
    private final AtomicInteger maxAdRoundPerEntry;
    private final AtomicInteger earnedMinutes = new AtomicInteger(0);
    private final AtomicInteger targetFreeMinutes;
    private final AtomicInteger pageCount = new AtomicInteger(0);
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private final AtomicLong lastPageSourceMs = new AtomicLong(0);
    private final AtomicLong lastXmlSize = new AtomicLong(0);
    private final AtomicInteger totalAdRounds = new AtomicInteger(0);

    // 异常分布统计
    private final Map<String, AtomicInteger> errorDistribution = new ConcurrentHashMap<>();

    // 状态识别耗时统计
    private final AtomicLong totalDetectMs = new AtomicLong(0);
    private final AtomicInteger detectCount = new AtomicInteger(0);

    // ==================== 保活调度 ====================
    private final ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> heartbeatFuture;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean sessionAlive = new AtomicBoolean(true);
    private final long startTimeMs;

    public RunLogger(AppiumDriver driver, AutomationConfig config) {
        this.driver = driver;
        this.config = config;
        this.startTimeMs = System.currentTimeMillis();
        this.maxAdRoundPerEntry = new AtomicInteger(config.maxAdRoundsPerEntry());
        this.targetFreeMinutes = new AtomicInteger(config.targetFreeMinutes());

        // 日志目录：automation/logs/ 和 automation/logs/snapshots/
        // 基于 user.dir 或类加载路径推算 automation 模块根目录
        String baseDir = System.getProperty("automation.logs.dir", "automation/logs");
        this.logsDir = Paths.get(baseDir);
        this.snapshotsDir = logsDir.resolve("snapshots");

        // 自动创建目录
        try {
            Files.createDirectories(logsDir);
            Files.createDirectories(snapshotsDir);
        } catch (IOException e) {
            System.err.println("[RunLogger] 无法创建日志目录: " + e.getMessage());
        }

        // 创建日志文件
        String timestamp = LocalDateTime.now().format(FILE_TS_FMT);
        this.logFile = logsDir.resolve("run-" + timestamp + ".log");
        try {
            this.fileWriter = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(logFile.toFile(), true), StandardCharsets.UTF_8),
                    true);
        } catch (IOException e) {
            System.err.println("[RunLogger] 无法创建日志文件: " + e.getMessage());
            this.fileWriter = null;
        }

        // 初始化心跳调度器（daemon 线程，不阻止 JVM 退出）
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RunLogger-Heartbeat");
            t.setDaemon(true);
            return t;
        });

        writeLine("[启动] 番茄免费小说 UI 自动化开始运行");
        writeLine("[启动] 目标: 累计 " + config.targetFreeMinutes() + " 分钟免广告时长");
        writeLine("[启动] 日志文件: " + logFile.toAbsolutePath());
    }

    @Override
    public void refreshDriver(AppiumDriver newDriver) {
        if (newDriver != null) {
            this.driver = newDriver;
            // C2：session 重建后必须复位保活标志。否则 recreate 的 quit()~refreshAll() 窗口内
            // 心跳线程可能用旧 driver 抛异常把 sessionAlive 置 false，之后永久无法恢复，
            // 导致心跳保活与 captureSnapshot 落盘双双静默失效。
            this.sessionAlive.set(true);
            log.info("[心跳] session 已重建，保活恢复");
        }
    }

    /**
     * 启动心跳保活调度。
     * 每 heartbeat.interval.sec 秒执行一次：
     * ① 轻量 driver.getWindowSize() 主动保活
     * ② 输出一行心跳摘要
     */
    public void start() {
        if (stopped.get()) return;
        // 启动时清理过期日志和快照
        cleanupOldFiles();
        int intervalSec = config.heartbeatIntervalSec();
        heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(
                this::heartbeatTask, intervalSec, intervalSec, TimeUnit.SECONDS);
        writeLine("[心跳] 保活调度已启动，间隔=" + intervalSec + "s");
    }

    /**
     * 清理过期日志和快照文件。
     * 根据 log.retention.days 配置，删除超过保留天数的文件。
     * 清理范围：logsDir 下的所有文件和 snapshotsDir 下的所有文件。
     */
    private void cleanupOldFiles() {
        int retentionDays = config.logRetentionDays();
        if (retentionDays <= 0) {
            writeLine("[清理] 日志清理已禁用 (retention=0)");
            return;
        }
        long cutoffMs = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000;
        int deletedCount = 0;
        long freedBytes = 0;
        // 清理 logsDir 下的文件（不含 snapshots 子目录，单独处理）
        try {
            File[] files = logsDir.toFile().listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.lastModified() < cutoffMs) {
                        long size = f.length();
                        if (f.delete()) {
                            deletedCount++;
                            freedBytes += size;
                        }
                    }
                }
            }
        } catch (Exception e) {
            writeLine("[清理] logsDir 清理异常: " + e.getMessage());
        }
        // 清理 snapshotsDir 下的文件
        try {
            File[] snapFiles = snapshotsDir.toFile().listFiles();
            if (snapFiles != null) {
                for (File f : snapFiles) {
                    if (f.isFile() && f.lastModified() < cutoffMs) {
                        long size = f.length();
                        if (f.delete()) {
                            deletedCount++;
                            freedBytes += size;
                        }
                    }
                }
            }
        } catch (Exception e) {
            writeLine("[清理] snapshotsDir 清理异常: " + e.getMessage());
        }
        if (deletedCount > 0) {
            writeLine(String.format("[清理] 已删除 %d 个过期文件 (保留 %d 天)，释放 %.1f KB",
                    deletedCount, retentionDays, freedBytes / 1024.0));
        } else {
            writeLine("[清理] 无需清理 (所有文件在保留期内)");
        }
    }

    /**
     * 心跳任务：保活 + 状态输出。
     * 内部必须自己捕获所有异常，绝不能让异常杀死调度线程。
     */
    private void heartbeatTask() {
        try {
            // ① 轻量保活：getWindowSize() 是最轻量的 Appium 命令之一
            if (driver != null && sessionAlive.get()) {
                try {
                    driver.manage().window().getSize();
                } catch (Exception e) {
                    sessionAlive.set(false);
                    writeLine("[警告] session 保活失败，连接可能已断开: " + e.getMessage());
                }
            }

            // ② 输出心跳摘要
            String elapsed = formatElapsed(System.currentTimeMillis() - startTimeMs);
            String heartbeat = String.format(
                    "[心跳] 已运行=%s | 状态=%s | 本轮视频=%d/%d | 累计时长=%d/%dmin | 翻页数=%d | 连续错误=%d | getPageSource=%dms | XML=%dKB",
                    elapsed,
                    currentState.name(),
                    currentAdRound.get(), maxAdRoundPerEntry.get(),
                    earnedMinutes.get(), targetFreeMinutes.get(),
                    pageCount.get(),
                    consecutiveErrors.get(),
                    lastPageSourceMs.get(),
                    lastXmlSize.get() / 1024);
            writeLine(heartbeat);

            // XML 膨胀预警
            if (lastXmlSize.get() > XML_SIZE_WARNING_THRESHOLD) {
                writeLine("[预警] XML 体积异常膨胀: " + lastXmlSize.get() / 1024 + "KB (阈值=" +
                        XML_SIZE_WARNING_THRESHOLD / 1024 + "KB)，可能存在 UI 树泄漏");
            }
        } catch (Exception e) {
            // 绝不能让异常杀死调度线程
            System.err.println("[RunLogger] 心跳任务异常（已吞没）: " + e.getMessage());
        }
    }

    // ==================== 状态更新方法（供状态机调用） ====================

    /**
     * 更新当前状态并输出转移日志。
     */
    public void updateState(PageState newState) {
        PageState oldState = this.currentState;
        this.currentState = newState;
        if (oldState != newState) {
            writeLine("[状态转移] " + oldState.name() + " -> " + newState.name() +
                    " (" + newState.getDescription() + ")");
        }
    }

    public void updateAdRound(int round) { this.currentAdRound.set(round); }
    public void incrementTotalAdRounds() { this.totalAdRounds.incrementAndGet(); }
    public void addEarnedMinutes(int minutes) { this.earnedMinutes.addAndGet(minutes); }
    public void incrementPageCount() { this.pageCount.incrementAndGet(); }
    public void setConsecutiveErrors(int errors) { this.consecutiveErrors.set(errors); }
    public void resetConsecutiveErrors() { this.consecutiveErrors.set(0); }
    public void updatePageSourceMetrics(long elapsedMs, long xmlSize) {
        this.lastPageSourceMs.set(elapsedMs);
        this.lastXmlSize.set(xmlSize);
    }
    public void recordDetectTime(long ms) {
        this.totalDetectMs.addAndGet(ms);
        this.detectCount.incrementAndGet();
    }
    public void recordError(String errorType) {
        errorDistribution.computeIfAbsent(errorType, k -> new AtomicInteger(0)).incrementAndGet();
    }

    // ==================== 截图与快照落盘 ====================

    /**
     * 异常/恢复时截图 + XML 快照落盘到 automation/logs/snapshots/。
     * 供 RecoveryHandler 与状态机在关键节点调用。
     *
     * @param tag 标签（如 "recovery_attempt_1"、"error_ad_close"）
     */
    public void captureSnapshot(String tag) {
        // C2：仅当 driver==null 时跳过；sessionAlive=false 时仍尝试落盘并用 try-catch 吞异常
        // （现场取证本就应在异常态工作，不能因保活标志为 false 而静默放弃取证）。
        if (driver == null) return;
        try {
            String timestamp = LocalDateTime.now().format(FILE_TS_FMT);
            String prefix = timestamp + "_" + tag.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "_");

            // 截图
            File screenshot = driver.getScreenshotAs(OutputType.FILE);
            Path screenshotDest = snapshotsDir.resolve(prefix + ".png");
            Files.copy(screenshot.toPath(), screenshotDest);

            // XML 快照
            String xml = driver.getPageSource();
            Path xmlDest = snapshotsDir.resolve(prefix + ".xml");
            Files.write(xmlDest, xml.getBytes(StandardCharsets.UTF_8));

            writeLine("[快照] 已保存: " + screenshotDest.getFileName() + " + " + xmlDest.getFileName());
        } catch (Exception e) {
            writeLine("[快照] 保存失败: " + e.getMessage());
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 是否 session 仍然存活。
     */
    public boolean isSessionAlive() {
        return sessionAlive.get();
    }

    /**
     * 标记 session 已断开（供 DriverFactory.healthCheck() 失败时调用）。
     */
    public void markSessionDead() {
        sessionAlive.set(false);
        writeLine("[警告] session 已标记为断开");
    }

    /**
     * 停止心跳 + 输出统计 + 关闭文件。
     * 必须幂等：可能被 finally 与 ShutdownHook 各调一次。
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return; // 已停止，幂等返回
        }

        // 取消心跳调度
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        heartbeatScheduler.shutdown();
        try {
            heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 输出结构化统计
        printFinalStatistics();

        // 关闭文件写入器
        if (fileWriter != null) {
            fileWriter.close();
        }
    }

    /**
     * 结束时输出结构化统计。
     */
    private void printFinalStatistics() {
        long totalMs = System.currentTimeMillis() - startTimeMs;
        String elapsed = formatElapsed(totalMs);
        double avgDetectMs = detectCount.get() > 0 ?
                (double) totalDetectMs.get() / detectCount.get() : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("╔══════════════════════════════════════════════════════════════╗\n");
        sb.append("║                    运行统计汇总                              ║\n");
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║ 总运行时间: %-45s ║%n", elapsed));
        sb.append(String.format("║ 累计免广告时长: %d/%d 分钟%-32s ║%n",
                earnedMinutes.get(), targetFreeMinutes.get(), ""));
        sb.append(String.format("║ 总广告轮次: %d%-44s ║%n", totalAdRounds.get(), ""));
        sb.append(String.format("║ 总翻页数: %d%-46s ║%n", pageCount.get(), ""));
        sb.append(String.format("║ 状态识别次数: %d%-42s ║%n", detectCount.get(), ""));
        sb.append(String.format("║ 平均状态识别耗时: %.1fms%-35s ║%n", avgDetectMs, ""));
        sb.append(String.format("║ 最终连续错误数: %d%-40s ║%n", consecutiveErrors.get(), ""));
        sb.append("╠══════════════════════════════════════════════════════════════╣\n");
        sb.append("║ 异常分布:\n");
        if (errorDistribution.isEmpty()) {
            sb.append("║   (无异常记录)\n");
        } else {
            for (Map.Entry<String, AtomicInteger> entry : errorDistribution.entrySet()) {
                sb.append(String.format("║   %s: %d 次%n", entry.getKey(), entry.getValue()));
            }
        }
        sb.append("╚══════════════════════════════════════════════════════════════╝\n");

        String stats = sb.toString();
        System.out.println(stats);
        writeLine(stats);
    }

    // ==================== 内部工具方法 ====================

    private void writeLine(String message) {
        String timestamp = LocalDateTime.now().format(LOG_TS_FMT);
        String line = timestamp + " " + message;
        System.out.println(line);
        if (fileWriter != null) {
            fileWriter.println(line);
        }
    }

    /**
     * 格式化运行时长为 HH:mm:ss。
     */
    private String formatElapsed(long ms) {
        Duration d = Duration.ofMillis(ms);
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    /** 获取日志文件路径 */
    public Path getLogFile() { return logFile; }
    /** 获取快照目录路径 */
    public Path getSnapshotsDir() { return snapshotsDir; }
}
