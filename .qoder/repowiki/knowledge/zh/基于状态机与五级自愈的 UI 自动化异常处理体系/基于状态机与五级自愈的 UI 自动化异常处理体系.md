---
kind: error_handling
name: 基于状态机与五级自愈的 UI 自动化异常处理体系
category: error_handling
scope:
    - '**'
source_files:
    - automation/src/main/java/com/fanqie/auto/task/RecoveryHandler.java
    - automation/src/main/java/com/fanqie/auto/core/PageState.java
    - automation/src/main/java/com/fanqie/auto/FanqieRunner.java
    - automation/src/main/java/com/fanqie/auto/DryRunProbe.java
    - automation/src/main/java/com/fanqie/auto/config/AutomationConfig.java
    - automation/src/main/java/com/fanqie/auto/core/DriverRegistry.java
---

## 1. 总体方案

该仓库是一个基于 Appium 2 + UiAutomator2 的番茄免费小说 UI 自动化框架，错误处理不是通过传统 try-catch 向上冒泡，而是围绕「页面状态机 + 多级自愈恢复」构建：所有 UI 交互失败都被视为「状态偏离」，由 `RecoveryHandler` 按固定顺序逐级恢复，最终统一落盘诊断并退出。

核心思路：
- 用 `PageState` 枚举（`UNKNOWN / APP_LAUNCHING / SPLASH_AD / BOOKSHELF / READER / READER_MENU / AD_* / COMMON_POPUP / RECOVERY_NEEDED`）描述当前界面，而非假设步骤顺序。
- 任何步骤抛出的异常或检测到的异常状态，都交给 `RecoveryHandler.recover(currentState)` 执行五级恢复。
- 超过阈值后不再盲目重试，而是记录快照、标记 session 死亡并退出。

## 2. 关键文件与职责

| 文件 | 作用 |
|---|---|
| `core/PageState.java` | 定义全部页面/广告/弹窗状态，提供 `isAbnormal()`、`isReaderFamily()` 等判断，作为状态机驱动 |
| `task/RecoveryHandler.java` | 五级自愈处理器：关闭通用弹窗 → 逐级 back() → 重启 App → 导航到书架 → 重建 session → 落盘诊断退出 |
| `FanqieRunner.java` | 唯一 main 入口，集中捕获顶层 `Exception`，调用 `logger.captureSnapshot("fatal_error")` 后以非零码退出 |
| `DryRunProbe.java` | dry-run 安全探针，对 I/O、正则编译、网络等异常做局部 catch 并降级输出 |
| `config/AutomationConfig.java` | 配置加载时 catch `IOException`，回退默认值 |
| `core/DriverRegistry.java` | 维护 `DriverAware` 组件列表，session 重建后统一刷新 driver 引用 |
| `core/RunLogger.java` | 记录连续错误计数、状态变更、错误事件、会话生死 |

## 3. 架构与约定

### 3.1 状态机驱动的错误分类
`PageState` 把异常分为三类：
- 可自愈子态：如 `READER_MENU` 被 `isReaderFamily()` 归入阅读页家族，不触发 recover。
- 异常态：`UNKNOWN`、`COMMON_POPUP`、`RECOVERY_NEEDED` 由 `isAbnormal()` 识别，进入 RecoveryHandler。
- 正常锚点：`BOOKSHELF`、`READER` 是恢复目标。

### 3.2 五级恢复策略（由弱到强）
1. **关闭通用弹窗**：按 `LocatorRegistry.commonDismissTexts()` 中「弱→强」顺序尝试点击（先试「我知道了/跳过」，最后才试「取消/不同意」），避免副作用。
2. **逐级 back()**：最多 `maxRecoveryRetry` 次，每次 back 后重新 `detect()` 确认是否回到锚点。
3. **重启 App**：`terminateApp + activateApp`，等待回到 `BOOKSHELF`/`SPLASH_AD`/`COMMON_POPUP`/`APP_LAUNCHING`。
4. **主动导航到书架**：若重启后不在书架，调用 `BookshelfPage.switchToShelfTab()`。
5. **重建 session**：连续失败达 `maxConsecutiveErrors` 次时调用 `driverFactory.recreate(noReset=true)`，并通过 `DriverRegistry.refreshAll(newDriver)` 刷新所有组件引用，防止 `NoSuchSessionException`。
6. **落盘诊断**：所有手段失败则 `recordError("recovery_failed")` + `captureSnapshot("recovery-failed")` + `markSessionDead()`，返回 false。

### 3.3 顶层异常处理
`FanqieRunner.main` 仅有一处 `catch (Exception e)`：
- 打印堆栈日志；
- 调用 `logger.captureSnapshot("fatal_error")` 保存现场；
- `System.exit(2)` 非零退出码。
其他业务异常应在 task/page 层被 recover 吸收，不应冒泡至此。

### 3.4 参数解析异常
命令行参数解析中对 `NumberFormatException` 单独 catch，打印到 `System.err` 并忽略该参数，不中断启动。

### 3.5 资源清理与优雅退出
- `try { ... } finally { logger.stop(); driverFactory.quit(); }` 保证 session 释放。
- 注册 JVM ShutdownHook，使用 `AtomicBoolean cleaned` 守护幂等性，防止 Hook 与 finally 重复 quit。

### 3.6 无自定义异常类型
代码库未定义业务异常类（如 `AutomationException`），也没有抛出受检异常给上层。I/O 异常在配置加载处被吞掉并回退默认值；UI 操作异常由 `RecoveryHandler` 内部 catch 并降级为 warn/error 日志。这是一种「异常即状态」的设计：异常不被当作返回值传播，而是转化为状态转移或恢复动作。

## 4. 约定与约束

- **禁止盲目连点**：RecoveryHandler 注释明确「超过 maxRecoveryRetry(3) 则停止并保留现场，绝不盲目连点」。
- **恢复成功判定收窄**：第 1 级弹窗关闭成功后，必须显式匹配白名单状态（`isReaderFamily()` 或 `BOOKSHELF`），不再用宽泛的 `!isAbnormal()` 判定，避免误判 `READER_MENU/SPLASH_AD/APP_LAUNCHING` 为已恢复。
- **连续失败计数跨 recover 调用累积**：`consecutiveFailures` 为 `AtomicInteger`，每次第 5 级失败递增，达到 `maxConsecutiveErrors` 才触发 session 重建。
- **Driver 一致性**：session 重建后必须通过 `DriverRegistry.refreshAll(newDriver)` 刷新所有 `DriverAware` 组件引用，否则后续操作会因旧 driver 失效而抛 `NoSuchSessionException`。
- **中断信号处理**：`Thread.sleep` 被 `InterruptedException` 捕获后恢复线程中断标志并返回 false，不吞掉中断。
- **dry-run 安全约束**：`DryRunProbe` 明确禁止点击广告/领时长按钮、禁止 uninstall/clearApp/reset 等破坏性操作，只允许切 Tab 和点开书籍两类无害操作。
- **日志优先于异常**：所有恢复步骤均通过 SLF4J (`org.slf4j.Logger`) 记录 `[Recovery]` 前缀日志，便于定位恢复路径。

## 5. 适用边界

该错误处理体系专门针对 UI 自动化场景设计（Appium Driver 不稳定、弹窗不可预测、页面跳转非线性），并不适用于纯后端 API 服务。对于根目录 `src/Main.java` 与 `demo01/` 下的教学示例，未见统一的错误处理模式，本分析仅覆盖 `automation/` 模块。