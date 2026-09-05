package com.fanqie.auto.core;

import com.fanqie.auto.config.AutomationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 环境自检器：session 创建前的可执行诊断（替代 README 文档，把指引变成程序输出）。
 * <p>
 * 关键设计决策：
 * 1. 经 ProcessBuilder 执行 adb 命令，解析设备状态，每种失败给出精确到手机设置路径的中文处置指引。
 * 2. HTTP GET <appium.server.url>/status 探测 Appium Server 是否在线（用 JDK 11 自带的 HttpClient）。
 * 3. 校验 ANDROID_HOME 环境变量、校验 appPackage 是否已安装。
 * 4. 任一硬性检查失败 → 打印完整清单并返回 false（供 Runner 以非零码退出），不抛裸异常堆栈。
 * 5. ProcessBuilder 调用必须设置超时并正确消费 stdout/stderr（否则 Windows 下缓冲区满会死锁）。
 */
public class EnvDoctor {

    private static final Logger log = LoggerFactory.getLogger(EnvDoctor.class);
    /** 外部命令超时（秒） */
    private static final int COMMAND_TIMEOUT_SEC = 15;

    private final AutomationConfig config;
    private final List<String> diagnostics = new ArrayList<>();
    private boolean allPassed = true;

    public EnvDoctor(AutomationConfig config) {
        this.config = config;
    }

    /**
     * 执行全部环境检查。
     *
     * @return true=全部通过，false=有硬性检查失败
     */
    public boolean checkAll() {
        diagnostics.clear();
        allPassed = true;

        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║              环境自检 (EnvDoctor)                            ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");

        checkAndroidHome();
        checkAdbVersion();
        checkAdbDevices();
        checkAppiumServer();
        checkAppInstalled();

        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        if (allPassed) {
            System.out.println("║ ✓ 全部检查通过，可以创建 Appium session                      ║");
        } else {
            System.out.println("║ ✗ 存在失败项，请按上方指引逐一排查后重试                      ║");
        }
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        return allPassed;
    }

    // ==================== 各项检查 ====================

    private void checkAndroidHome() {
        String androidHome = System.getenv("ANDROID_HOME");
        String androidSdkRoot = System.getenv("ANDROID_SDK_ROOT");

        if (androidHome != null && !androidHome.isEmpty()) {
            pass("ANDROID_HOME", androidHome);
        } else if (androidSdkRoot != null && !androidSdkRoot.isEmpty()) {
            pass("ANDROID_SDK_ROOT", androidSdkRoot + " (ANDROID_HOME 未设置，但 SDK_ROOT 可用)");
        } else {
            // ANDROID_HOME 非硬性要求（只要 adb 在 PATH 中即可），但警告
            warn("ANDROID_HOME", "未设置。uiautomator2 driver 依赖此变量找到 adb。" +
                    "建议设置为 platform-tools 的父目录（如 D:\\Android\\Sdk）");
        }
    }

    private void checkAdbVersion() {
        ProcessResult result = executeCommand("adb", "version");
        if (result.success && result.stdout.contains("Android Debug Bridge")) {
            String version = result.stdout.lines().findFirst().orElse("unknown");
            pass("adb version", version);
        } else if (result.success) {
            pass("adb version", result.stdout.lines().findFirst().orElse("(输出非标准)"));
        } else {
            fail("adb version", "adb 命令不可用: " + result.errorSummary());
            System.out.println("║   处置: 执行 winget install --id Google.PlatformTools -e");
            System.out.println("║         安装后刷新 PATH 或重启终端");
        }
    }

    private void checkAdbDevices() {
        ProcessResult result = executeCommand("adb", "devices", "-l");
        if (!result.success) {
            fail("adb devices", "命令执行失败: " + result.errorSummary());
            return;
        }

        String output = result.stdout;
        String expectedUdid = config.deviceUdid();

        if (output.contains("List of devices attached") && output.trim().endsWith("attached")) {
            // 设备列表为空
            fail("adb devices", "未检测到任何设备（列表为空）");
            printDeviceTroubleshooting();
        } else if (!expectedUdid.isEmpty() && output.contains(expectedUdid)) {
            if (output.contains(expectedUdid + "  ") && output.contains("device")) {
                pass("adb devices", "设备 " + expectedUdid + " 在线 (状态=device)");
            } else if (output.contains("unauthorized")) {
                fail("adb devices", "设备 " + expectedUdid + " 未授权 (状态=unauthorized)");
                System.out.println("║   处置: 在手机上确认 USB 调试授权弹窗，勾选「始终允许来自这台计算机的调试」");
            } else if (output.contains("offline")) {
                fail("adb devices", "设备 " + expectedUdid + " 离线 (状态=offline)");
                System.out.println("║   处置: 重新插拔 USB 线，或执行 adb kill-server; adb start-server");
            } else {
                warn("adb devices", "设备 " + expectedUdid + " 状态不明确");
            }
        } else if (output.contains("device") || output.contains("model:")) {
            // 有设备在线但不是期望的 UDID
            warn("adb devices", "检测到设备但非期望的 UDID " + expectedUdid);
            pass("adb devices", output.lines()
                    .filter(l -> !l.startsWith("List") && !l.trim().isEmpty())
                    .findFirst().orElse("(unknown)"));
        } else {
            fail("adb devices", "无法解析设备列表");
            printDeviceTroubleshooting();
        }
    }

    private void checkAppiumServer() {
        String serverUrl = config.appiumServerUrl();
        String statusUrl = serverUrl + "/status";

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                if (body.contains("\"ready\": true") || body.contains("\"ready\":true")) {
                    pass("Appium Server", serverUrl + " 在线且 ready");
                } else {
                    warn("Appium Server", serverUrl + " 响应 200 但 ready 状态不明确: " +
                            body.substring(0, Math.min(100, body.length())));
                }
            } else {
                fail("Appium Server", serverUrl + " 响应 HTTP " + response.statusCode());
                System.out.println("║   处置: 在另一个终端执行 appium --base-path / 启动 Server");
            }
        } catch (Exception e) {
            fail("Appium Server", serverUrl + " 无法连接: " + e.getMessage());
            System.out.println("║   处置: 1. 确认 Node.js 已安装: node --version");
            System.out.println("║         2. 确认 Appium 已安装: npm list -g appium");
            System.out.println("║         3. 启动 Server: appium --base-path /");
            System.out.println("║         4. 确认 uiautomator2 driver: appium driver list --installed");
        }
    }

    private void checkAppInstalled() {
        String appPackage = config.appPackage();
        ProcessResult result = executeCommand("adb", "shell", "pm", "list", "packages");

        if (!result.success) {
            warn("App 安装检查", "无法执行 pm list packages: " + result.errorSummary());
            return;
        }

        if (result.stdout.contains(appPackage)) {
            pass("App 安装", appPackage + " 已安装");
        } else {
            fail("App 安装", appPackage + " 未安装");
            System.out.println("║   处置: 在手机上安装「番茄免费小说」App");
            System.out.println("║         或确认包名是否正确: adb shell pm list packages | findstr dragon");
        }
    }

    // ==================== 设备故障排查指引 ====================

    private void printDeviceTroubleshooting() {
        System.out.println("║   处置（逐项排查）:");
        System.out.println("║   1. 解锁开发者选项: 设置 → 关于手机 → 连续点击「HarmonyOS 版本」7 次");
        System.out.println("║      （必须在机主模式/主空间下操作，子用户/隐私空间无法解锁）");
        System.out.println("║   2. 打开 USB 调试: 设置 → 系统和更新 → 开发人员选项 → USB 调试");
        System.out.println("║   3. 打开「仅充电模式下允许 ADB 调试」（同一页面）");
        System.out.println("║      ★ 这是华为/鸿蒙特有且最容易漏掉的一项！");
        System.out.println("║   4. USB 连接方式选「传输文件」（非「仅充电」）");
        System.out.println("║   5. 首次插线时手机上勾选「始终允许来自这台计算机的调试」");
        System.out.println("║   6. 处理「监控 ADB 安装应用」（开发人员选项底部）");
        System.out.println("║      开启时 Appium 推送 APK 会弹确认框，建议首轮临时关闭");
        System.out.println("║   7. 使用原装数据线（华为官方明确非原装线常只充电不传数据）");
    }

    // ==================== 命令执行 ====================

    /**
     * 执行外部命令并正确消费 stdout/stderr。
     * ProcessBuilder 调用必须设置超时并正确消费输出流（否则 Windows 下缓冲区满会死锁）。
     */
    private ProcessResult executeCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false); // 分别消费 stdout 和 stderr
            Process process = pb.start();

            // 必须消费 stdout 和 stderr，否则缓冲区满会死锁
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (Exception e) { /* 忽略 */ }
            });

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (Exception e) { /* 忽略 */ }
            });

            stdoutThread.start();
            stderrThread.start();

            boolean finished = process.waitFor(COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessResult(false, "", "命令超时 (" + COMMAND_TIMEOUT_SEC + "s)");
            }

            stdoutThread.join(3000);
            stderrThread.join(3000);

            int exitCode = process.exitValue();
            return new ProcessResult(exitCode == 0, stdout.toString(), stderr.toString());
        } catch (Exception e) {
            return new ProcessResult(false, "", e.getMessage());
        }
    }

    // ==================== 输出格式化 ====================

    private void pass(String item, String detail) {
        System.out.printf("║ [✓] %-18s %s%n", item, truncate(detail, 35));
    }

    private void warn(String item, String detail) {
        System.out.printf("║ [!] %-18s %s%n", item, truncate(detail, 35));
        diagnostics.add("[WARN] " + item + ": " + detail);
    }

    private void fail(String item, String detail) {
        allPassed = false;
        System.out.printf("║ [✗] %-18s %s%n", item, truncate(detail, 35));
        diagnostics.add("[FAIL] " + item + ": " + detail);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /** 获取诊断信息列表 */
    public List<String> getDiagnostics() {
        return new ArrayList<>(diagnostics);
    }

    // ==================== 命令执行结果 ====================

    private static class ProcessResult {
        final boolean success;
        final String stdout;
        final String stderr;

        ProcessResult(boolean success, String stdout, String stderr) {
            this.success = success;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }

        String errorSummary() {
            if (!stderr.isEmpty()) return stderr.trim();
            return "exit code != 0";
        }
    }
}
