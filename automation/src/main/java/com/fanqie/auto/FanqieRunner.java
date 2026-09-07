package com.fanqie.auto;

import com.fanqie.auto.config.AutomationConfig;
import com.fanqie.auto.config.LocatorRegistry;
import com.fanqie.auto.core.AppiumUiAutomator2DriverFactory;
import com.fanqie.auto.core.DriverFactory;
import com.fanqie.auto.core.DriverRegistry;
import com.fanqie.auto.core.EnvDoctor;
import com.fanqie.auto.core.GestureSupport;
import com.fanqie.auto.core.RunLogger;
import com.fanqie.auto.core.StateDetector;
import com.fanqie.auto.core.WaitSupport;
import com.fanqie.auto.page.AdFlowPage;
import com.fanqie.auto.page.BookshelfPage;
import com.fanqie.auto.page.ReaderPage;
import com.fanqie.auto.task.AdWatchStateMachine;
import com.fanqie.auto.task.FanqieAdWatchTask;
import com.fanqie.auto.task.RecoveryHandler;
import io.appium.java_client.AppiumDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 唯一 main() 入口：番茄免费小说 UI 自动化。
 * <p>
 * 类名刻意避开 Main（现有默认包的 Main.java），防止混淆。
 * <p>
 * 职责：
 * 1. 解析命令行参数：--dry-run / --cycles N / --pages N / --config <path> / --doctor
 * 2. 装配各组件：AutomationConfig → LocatorRegistry → EnvDoctor → DriverFactory → RunLogger
 * 3. try-finally 强制 logger.stop() + driver.quit()，防 session 泄漏
 * 4. 注册 JVM ShutdownHook 处理 Ctrl+C，保证优雅退出
 * 5. 启动时打印手机侧设置核对清单（7 项），把文档变成可执行输出
 */
public class FanqieRunner {

    private static final Logger log = LoggerFactory.getLogger(FanqieRunner.class);

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     番茄免费小说「看视频领免广告时长」UI 自动化              ║");
        System.out.println("║     FanqieRunner v1.0.0 | Appium 2 + Java Client 9.5        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // === 解析命令行参数 ===
        boolean doctorOnly = false;
        boolean dryRun = false;
        boolean resetProgress = false;
        String configPath = null;
        Integer cyclesOverride = null;
        Integer pagesOverride = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--doctor":
                    doctorOnly = true;
                    break;
                case "--dry-run":
                    dryRun = true;
                    break;
                case "--reset-progress":
                    resetProgress = true;
                    break;
                case "--config":
                    if (i + 1 < args.length) configPath = args[++i];
                    break;
                case "--cycles":
                    if (i + 1 < args.length) {
                        try { cyclesOverride = Integer.parseInt(args[++i]); }
                        catch (NumberFormatException e) { System.err.println("--cycles 参数需要整数值"); }
                    }
                    break;
                case "--pages":
                    if (i + 1 < args.length) {
                        try { pagesOverride = Integer.parseInt(args[++i]); }
                        catch (NumberFormatException e) { System.err.println("--pages 参数需要整数值"); }
                    }
                    break;
                default:
                    System.err.println("未知参数: " + args[i]);
                    printUsage();
                    return;
            }
        }

        // === 装配配置 ===
        AutomationConfig config = (configPath != null) ? new AutomationConfig(configPath) : new AutomationConfig();
        if (dryRun) {
            // --dry-run 命令行参数覆盖配置文件
            System.out.println("[Runner] --dry-run 模式：只连接并 dump UI 树，不执行任何点击");
        }

        // === 打印手机侧设置核对清单 ===
        if (!doctorOnly) {
            printDeviceChecklist();
        }

        // === 自动确保 Appium Server 已启动(探活→拉起→等待就绪) ===
        ensureAppiumServerRunning();

        // === 环境自检 ===
        EnvDoctor doctor = new EnvDoctor(config);
        boolean envOk = doctor.checkAll();
        if (!envOk) {
            System.err.println("\n[Runner] 环境自检未通过，请以非零码退出。修复后重试。");
            System.exit(1);
            return;
        }

        if (doctorOnly) {
            System.out.println("\n[Runner] --doctor 模式：环境自检完成，不创建 session。");
            System.exit(0);
            return;
        }

        // === 初始化 LocatorRegistry 并核验中文编码 ===
        LocatorRegistry locators = new LocatorRegistry();
        locators.dumpToConsole(); // 打印候选集确认无乱码

        // === 创建 Driver（F：按 config.driverType() 选择工厂，未知值回退 uiautomator2） ===
        DriverFactory driverFactory = createDriverFactory(config);
        AppiumDriver driver = null;
        RunLogger logger = null;
        // AtomicBoolean 守护 quit 幂等性：ShutdownHook 与 finally 可能都触发清理
        final AtomicBoolean cleaned = new AtomicBoolean(false);

        try {
            driver = driverFactory.create();
            logger = new RunLogger(driver, config);

            // 注册 ShutdownHook 处理 Ctrl+C，保证优雅退出
            // 注意：ShutdownHook 与 finally 可能都触发清理，
            // RunLogger.stop() 已是幂等的，quit() 也用 AtomicBoolean 守护避免重复 quit 抛异常
            final RunLogger finalLogger = logger;
            final DriverFactory finalFactory = driverFactory;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (cleaned.compareAndSet(false, true)) {
                    System.out.println("\n[Runner] 收到退出信号，正在优雅关闭...");
                    finalLogger.stop();
                    finalFactory.quit();
                }
            }, "ShutdownHook"));

            // 启动心跳保活
            logger.start();

            // === 装配核心组件 ===
            GestureSupport gestures = new GestureSupport(driver, config);
            StateDetector detector = new StateDetector(driver, config, locators);
            // 阶段2遗留接线：注入 RunLogger 以启用状态识别耗时上报（E1，null 安全）
            detector.setLogger(logger);
            WaitSupport waitSupport = new WaitSupport(driver, config, detector);

            // === 创建 DriverRegistry 并注册所有实现了 DriverAware 的组件 ===
            // session 重建后由 RecoveryHandler 调用 registry.refreshAll(newDriver) 统一刷新引用
            DriverRegistry registry = new DriverRegistry();
            registry.register(gestures);
            registry.register(detector);
            registry.register(waitSupport);
            registry.register(logger);

            // === Dry-run 模式：只探测不点击 ===
            if (dryRun || config.dryRun()) {
                log.info("[Runner] Dry-run 模式：委托 DryRunProbe 执行安全探测");
                DryRunProbe probe = new DryRunProbe(driver, config, locators,
                        detector, waitSupport, gestures, logger);
                // dry-run 不触发 recreate，但仍注册 probe 以保证 driver 一致性
                registry.register(probe);
                probe.probe();
                return;
            }

            // === 装配 page 层 ===
            BookshelfPage bookshelfPage = new BookshelfPage(driver, config, locators,
                    gestures, logger, detector, waitSupport);
            ReaderPage readerPage = new ReaderPage(driver, config, locators,
                    gestures, logger, detector, waitSupport);
            AdFlowPage adFlowPage = new AdFlowPage(driver, config, locators,
                    gestures, logger, detector, waitSupport);

            // === 装配 task 层 ===
            RecoveryHandler recoveryHandler = new RecoveryHandler(driver, config, locators,
                    detector, waitSupport, gestures, logger, driverFactory, registry);
            // 延迟注入 BookshelfPage，避免构造时循环依赖
            recoveryHandler.setBookshelfPage(bookshelfPage);

            AdWatchStateMachine stateMachine = new AdWatchStateMachine(driver, config, locators,
                    detector, waitSupport, gestures, logger, adFlowPage, readerPage,
                    bookshelfPage, recoveryHandler);

            FanqieAdWatchTask task = new FanqieAdWatchTask(driver, config, locators,
                    detector, waitSupport, gestures, logger, bookshelfPage, readerPage,
                    adFlowPage, stateMachine, recoveryHandler);

            // 注册剩余 DriverAware 组件（page 层继承 BasePage 已实现，task 层单独实现）
            registry.register(bookshelfPage);
            registry.register(readerPage);
            registry.register(adFlowPage);
            registry.register(stateMachine);
            registry.register(task);
            registry.register(recoveryHandler);

            // 应用命令行参数覆盖
            if (cyclesOverride != null) task.setCyclesOverride(cyclesOverride);
            if (pagesOverride != null) task.setPagesOverride(pagesOverride);

            // === C2：打印将从 progress.properties 恢复的进度，并处理 --reset-progress ===
            log.info("[Runner] 从 progress.properties 恢复的进度: earnedMinutes={}, totalAdRounds={}, 已用书籍={} 本",
                    stateMachine.getEarnedMinutes(), stateMachine.getTotalAdRounds(),
                    stateMachine.getUsedBooks().size());
            if (resetProgress) {
                log.info("[Runner] --reset-progress 指定：清除进度并从零开始");
                stateMachine.resetProgress();
            }

            // === 打印合规提示 ===
            printComplianceNotice();

            // === 执行主任务 ===
            log.info("[Runner] 所有组件装配完毕，开始执行主任务");
            boolean taskSuccess = task.run();
            if (taskSuccess) {
                log.info("[Runner] 任务正常完成");
            } else {
                log.error("[Runner] 任务异常终止");
            }

        } catch (Exception e) {
            log.error("[Runner] 运行异常: {}", e.getMessage(), e);
            if (logger != null) logger.captureSnapshot("fatal_error");
            System.exit(2);
        } finally {
            // try-finally 强制 logger.stop() + driver.quit()，防 session 泄漏
            if (cleaned.compareAndSet(false, true)) {
                if (logger != null) logger.stop();
                driverFactory.quit();
            }
        }
    }

    private static void printUsage() {
        System.out.println("用法: FanqieRunner [选项]");
        System.out.println("  --doctor          只运行环境自检，不创建 session");
        System.out.println("  --dry-run         只连接并 dump UI 树，不执行任何点击");
        System.out.println("  --config <path>   使用外部配置文件覆盖默认值");
        System.out.println("  --cycles <N>      覆盖 flow.outer.cycles");
        System.out.println("  --pages <N>       覆盖 flow.pages.per.cycle");
        System.out.println("  --reset-progress  清除 progress.properties 中的累计进度，从零开始");
    }

    /**
     * F：按 config.driverType() 选择 DriverFactory 实现。
     * <p>
     * 当前仅实现 uiautomator2（标准 Appium 2 + UiAutomator2 路线）；
     * 未知值记 warn 并回退到 uiautomator2。
     * 未来鸿蒙 NEXT 兜底路线只需新增 case "harmony_hdc"，上层零改动。
     */
    private static DriverFactory createDriverFactory(AutomationConfig config) {
        String type = config.driverType();
        switch (type) {
            case "uiautomator2":
                return new AppiumUiAutomator2DriverFactory(config);
            // 未来: case "harmony_hdc" -> new HdcUiTestDriverFactory(config);
            default:
                log.warn("[Runner] 未知 driver.type='{}'，回退到 uiautomator2", type);
                return new AppiumUiAutomator2DriverFactory(config);
        }
    }

    /**
     * 启动时打印手机侧设置核对清单（7 项），把文档变成可执行输出。
     */
    private static void printDeviceChecklist() {
        System.out.println("┌──────────────────────────────────────────────────────────────┐");
        System.out.println("│  手机侧设置核对清单（请确认以下 7 项已完成）                 │");
        System.out.println("├──────────────────────────────────────────────────────────────┤");
        System.out.println("│  □ 1. 解锁开发者选项:                                        │");
        System.out.println("│       设置 → 关于手机 → 连续点击「HarmonyOS 版本」7 次       │");
        System.out.println("│  □ 2. 打开 USB 调试:                                         │");
        System.out.println("│       设置 → 系统和更新 → 开发人员选项 → USB 调试            │");
        System.out.println("│  □ 3. 打开「仅充电模式下允许 ADB 调试」（华为特有！）        │");
        System.out.println("│       同一页面                                               │");
        System.out.println("│  □ 4. 处理「监控 ADB 安装应用」:                             │");
        System.out.println("│       建议首轮临时关闭，或人在手机旁逐个确认 APK 安装弹窗    │");
        System.out.println("│  □ 5. USB 连接方式选「传输文件」                             │");
        System.out.println("│  □ 6. 首次插线授权: 勾选「始终允许来自这台计算机的调试」     │");
        System.out.println("│  □ 7. 建议: 关闭自动锁屏 / 接通电源 / 原装数据线            │");
        System.out.println("│       完整流程可能运行 1-3 小时，建议接通电源并开启          │");
        System.out.println("│       「充电时保持唤醒」，把番茄小说加入电池优化白名单       │");
        System.out.println("└──────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    /**
     * 自动确保 Appium Server 已就绪。
     * <p>
     * 幂等设计：先 HTTP GET /status 探活，已 running 则直接返回；
     * 未运行时通过 ProcessBuilder 后台启动 appium --base-path /，
     * 再轮询 /status 直到 ready:true 或超时(30s)。
     * <p>
     * 这样用户无需手动执行 start-appium.ps1，点运行按钮即可。
     */
    private static void ensureAppiumServerRunning() {
        String serverUrl = "http://127.0.0.1:4723";
        String statusUrl = serverUrl + "/status";
        int timeoutSec = 30;
        int pollIntervalMs = 1000;

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        // Step 1: 探活
        if (isAppiumReady(http, statusUrl)) {
            System.out.println("[Runner] Appium Server 已在运行且就绪 (" + serverUrl + ")");
            return;
        }

        System.out.println("[Runner] Appium Server 未运行，正在自动启动...");

        // Step 2: 后台启动
        try {
            ProcessBuilder pb = new ProcessBuilder("appium", "--base-path", "/");
            pb.redirectOutput(ProcessBuilder.Redirect.toFile(
                    new java.io.File(System.getProperty("java.io.tmpdir"), "appium-stdout.log")));
            pb.redirectError(ProcessBuilder.Redirect.toFile(
                    new java.io.File(System.getProperty("java.io.tmpdir"), "appium-stderr.log")));
            pb.start();
            System.out.println("[Runner] Appium 进程已启动(后台)，等待就绪...");
        } catch (Exception e) {
            System.err.println("[Runner] 启动 Appium Server 失败: " + e.getMessage());
            System.err.println("[Runner] 请手动执行: appium --base-path /");
            System.err.println("[Runner] 或检查 appium 是否已安装: npm list -g appium");
            System.exit(1);
            return;
        }

        // Step 3: 轮询等待就绪
        for (int i = 0; i < timeoutSec; i++) {
            try { Thread.sleep(pollIntervalMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            if (isAppiumReady(http, statusUrl)) {
                System.out.println("[Runner] Appium Server 已就绪 (" + serverUrl + ")");
                return;
            }
            System.out.print(".");
        }
        System.out.println();
        System.err.println("[Runner] Appium Server 在 " + timeoutSec + "s 内未就绪，请检查日志或手动启动");
        System.exit(1);
    }

    /**
     * HTTP GET /status 探测 Appium Server 是否 ready。
     */
    private static boolean isAppiumReady(HttpClient http, String statusUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return resp.body().contains("\"ready\":true");
            }
        } catch (Exception ignored) {
            // 连接失败 = 未运行
        }
        return false;
    }

    /**
     * 打印合规提示：自动化领取广告激励时长可能违反 App 用户协议。
     */
    private static void printComplianceNotice() {
        System.out.println();
        System.out.println("┌──────────────────────────────────────────────────────────────┐");
        System.out.println("│  ⚠ 合规提示                                                  │");
        System.out.println("├──────────────────────────────────────────────────────────────┤");
        System.out.println("│  本程序会真实播放广告并自动领取免广告时长，                   │");
        System.out.println("│  属对平台激励机制的自动化利用，可能违反 App 用户协议，       │");
        System.out.println("│  存在账号风控/受限可能。建议用小号验证，不要在主力账号长跑。 │");
        System.out.println("└──────────────────────────────────────────────────────────────┘");
        System.out.println();
    }
}
