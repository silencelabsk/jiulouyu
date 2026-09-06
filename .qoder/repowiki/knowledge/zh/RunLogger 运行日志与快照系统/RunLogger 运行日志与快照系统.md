---
kind: logging_system
name: RunLogger 运行日志与快照系统
category: logging_system
scope:
    - '**'
source_files:
    - automation/src/main/java/com/fanqie/auto/core/RunLogger.java
    - automation/src/main/java/com/fanqie/auto/FanqieRunner.java
    - automation/src/main/java/com/fanqie/auto/config/AutomationConfig.java
    - automation/src/main/resources/automation.properties
    - automation/src/test/java/com/fanqie/auto/core/RunLoggerTest.java
---

## 1. 使用的系统与框架

- **日志门面**：使用 `org.slf4j.Logger`（通过 `LoggerFactory.getLogger(...)` 获取），但仅用于顶层 Runner 的少量结构化输出（如 dry-run、异常、进度恢复等）。
- **核心日志实现**：自定义 `com.fanqie.auto.core.RunLogger`，不依赖 logback/log4j2 等后端。它直接通过 `System.out.println` 输出到控制台，并追加写入文件；同时承担 Appium session 保活心跳职责。
- **配置文件**：`automation/src/main/resources/automation.properties` 提供运行时参数（如 `heartbeat.interval.sec`、`maxAdRoundsPerEntry`、`targetFreeMinutes`、`driverType` 等），由 `AutomationConfig` 加载后驱动日志行为。

## 2. 关键文件与包

| 文件 | 作用 |
|---|---|
| `automation/src/main/java/com/fanqie/auto/core/RunLogger.java` | 唯一日志中心：构造日志目录、写行、心跳调度、截图/XML 快照、统计汇总 |
| `automation/src/main/java/com/fanqie/auto/FanqieRunner.java` | 装配入口：创建 `RunLogger`、注册 ShutdownHook、调用 `start()/stop()` |
| `automation/src/main/java/com/fanqie/auto/config/AutomationConfig.java` | 读取 `automation.properties`，暴露 `heartbeatIntervalSec()` 等配置项 |
| `automation/src/main/resources/automation.properties` | 运行时配置（心跳间隔、目标时长、driver 类型等） |
| `automation/logs/run-*.log` | 每次运行生成的文本日志文件 |
| `automation/logs/snapshots/*.png, *.xml` | 异常/恢复时保存的截图与 UI XML 快照 |
| `automation/src/test/java/com/fanqie/auto/core/RunLoggerTest.java` | 对 `RunLogger` 的单元测试 |

## 3. 架构与设计约定

### 3.1 双职责设计
`RunLogger` 同时负责两件事：
1. **运行日志**：以 `HH:mm:ss` 时间戳 + 消息的形式，统一输出到控制台和文件。
2. **Session 保活心跳**：通过 `ScheduledExecutorService`（daemon 线程，名为 `RunLogger-Heartbeat`）每 `heartbeat.interval.sec` 秒执行一次轻量 `driver.manage().window().getSize()`，避免 Appium 的 `newCommandTimeout` 导致长静默期断开。

### 3.2 日志落盘策略
- **日志目录**：默认 `automation/logs/`，可通过 JVM 属性 `automation.logs.dir` 覆盖；子目录 `snapshots/` 存放截图与 XML。
- **日志文件命名**：`run-yyyyMMdd-HHmmss.log`，每次启动生成新文件，按时间戳区分不同运行实例。
- **编码**：统一 `StandardCharsets.UTF_8` 写入。
- **目录创建**：构造时自动 `Files.createDirectories(logsDir)` 与 `snapshotsDir`，失败时回退到 `System.err` 打印错误。

### 3.3 日志级别与内容
代码中未定义细粒度日志级别（无 debug/info/warn/error 分级），而是通过前缀标签区分语义：
- `[启动]`：运行开始、目标、日志路径
- `[心跳]`：保活调度启动、session 重建、周期性摘要（已运行时长、当前状态、本轮视频轮次、累计时长、翻页数、连续错误、getPageSource 耗时、XML 大小）
- `[状态转移]`：状态机状态变化（如 `UNKNOWN -> AD_VIDEO_PLAYING`）
- `[警告]`：session 保活失败、session 标记断开
- `[预警]`：XML 体积超过 1MB 阈值，提示可能存在 UI 树泄漏
- `[快照]`：截图与 XML 保存成功/失败
- 结束时输出 ASCII 表格形式的「运行统计汇总」，包含总运行时间、累计免广告时长、总广告轮次、总翻页数、状态识别次数、平均识别耗时、最终连续错误数、异常分布。

### 3.4 快照取证机制
- 在异常或恢复场景下，通过 `captureSnapshot(tag)` 保存 `<timestamp>_<tag>.png` 与 `.xml` 到 `automation/logs/snapshots/`。
- tag 会做字符清洗（仅保留字母、数字、下划线、中文），防止非法文件名。
- 即使 `sessionAlive=false`，仍尝试落盘（注释强调“现场取证本就应在异常态工作”）。

### 3.5 生命周期与幂等性
- `start()` 启动心跳调度；`stop()` 取消调度、等待终止、输出统计、关闭文件。
- `stop()` 使用 `AtomicBoolean stopped` 保证幂等——可能被 `finally` 块与 `Runtime.addShutdownHook` 各调用一次。
- `refreshDriver(newDriver)` 在 session 重建后重置 `sessionAlive=true`，避免旧 driver 引用导致后续心跳误判。

### 3.6 指标采集
`RunLogger` 维护一组可被状态机更新的原子计数器：
- 当前页面状态 `PageState`、当前广告轮次、累计免广告分钟、目标分钟、翻页数、连续错误数
- 最近一次 `getPageSource` 耗时与 XML 大小
- 状态识别总耗时与次数
- 异常分布 Map<String, AtomicInteger>
这些指标在心跳摘要和最终统计中呈现。

## 4. 约定与约束

| 约定/约束 | 来源 |
|---|---|
| 日志只写 `automation/logs` 及其子目录，绝不写工程根或 src | `RunLogger` 类头注释 C1-C5 |
| 日志文件 UTF-8 编码 | `StandardCharsets.UTF_8` 显式指定 |
| 心跳任务内部必须捕获所有异常，绝不能让异常杀死调度线程 | `RunLogger` 注释及 `heartbeatTask` try-catch |
| `stop()` 必须幂等 | 注释说明 + `AtomicBoolean` 实现 |
| XML 长度超 1MB 输出预警 | `XML_SIZE_WARNING_THRESHOLD = 1024*1024` 常量 |
| 心跳间隔由 `heartbeat.interval.sec` 配置驱动 | `AutomationConfig` + `RunLogger.start()` |
| 日志目录可通过 JVM 属性 `automation.logs.dir` 覆盖 | 构造时读取 `System.getProperty("automation.logs.dir", "automation/logs")` |
| 顶层 Runner 使用 SLF4J Logger 记录结构化信息（dry-run、异常、进度恢复） | `FanqieRunner` 中 `LoggerFactory.getLogger(FanqieRunner.class)` |
| 其他业务组件通过 `System.out/System.err` 直接输出诊断信息（如 `EnvDoctor`、`LocatorRegistry.dumpToConsole`） | 多处 `System.out.println` / `System.err.println` 调用 |

## 5. 总结

该仓库没有采用通用日志框架（logback/log4j2）作为主输出通道，而是围绕自研的 `RunLogger` 构建了一套面向 UI 自动化场景的专用日志系统：以固定格式的行式文本日志为核心，辅以异常时的截图+XML 快照取证、周期性的 session 保活心跳、以及运行结束的结构化统计报表。SLF4J 仅在顶层 Runner 中用于少量结构化日志。日志输出遵循“控制台 + 文件双写”的模式，并通过 `automation.properties` 进行运行时配置。