---
kind: configuration_system
name: 自动化框架配置系统（Properties + 类型化 Getter + 定位器注册表）
category: configuration_system
scope:
    - '**'
source_files:
    - automation/src/main/java/com/fanqie/auto/config/AutomationConfig.java
    - automation/src/main/java/com/fanqie/auto/config/LocatorRegistry.java
    - automation/src/main/resources/automation.properties
    - automation/src/main/resources/locators.properties
    - automation/src/test/java/com/fanqie/auto/config/ConfigTest.java
---

## 1. 使用的系统与方案

该工程采用**纯 Java Properties 文件 + 类型化 Getter 访问器**的轻量级配置方案，未引入任何第三方配置框架（如 Spring Boot、Hikari、YAML/TOML 解析器等）。核心由两个类组成：

- `AutomationConfig`：全局运行时配置加载器，负责从 classpath 与外部文件两级加载 `automation.properties`，并提供强类型的 getter（`getString/getInt/getLong/getDouble/getBoolean`），所有超时、轮次、阈值等“魔法数字”统一从此处获取。
- `LocatorRegistry`：UI 控件定位器注册表，负责从 `locators.properties` 加载中文文案候选集、正则表达式与 UI 元素定位策略，并构建不可变的 `LocatorSpec`（主定位器 + 备选定位器 + boundsHint + allowGeometryFallback）。

此外，项目通过 JUnit 5 单元测试 (`ConfigTest`) 对配置加载、默认值兜底、外部覆盖以及 LocatorSpec 约束进行断言验证。

## 2. 关键文件与包

- `automation/src/main/java/com/fanqie/auto/config/AutomationConfig.java` — 全局配置加载与类型化访问入口
- `automation/src/main/java/com/fanqie/auto/config/LocatorRegistry.java` — UI 控件定位策略与候选文案管理
- `automation/src/main/resources/automation.properties` — 运行时参数（Appium 连接、时序、流程、阅读页翻页策略、dry-run 开关等）
- `automation/src/main/resources/locators.properties` — UI 控件文案候选集、正则、资源 ID 等
- `automation/src/test/java/com/fanqie/auto/config/ConfigTest.java` — 配置加载与默认值兜底的单元测试

## 3. 架构与设计约定

### 3.1 双级配置加载（classpath 默认 + 外部覆盖）

`AutomationConfig` 提供两个构造器：
- 无参构造：仅从 classpath 加载 `automation.properties`，缺失键时返回内置默认值。
- 带路径构造：先加载 classpath 默认值作为兜底，再用指定外部 `.properties` 文件覆盖。用于支持 `--config` 命令行场景，允许用户不重新编译即可调整配置。

加载过程使用 `InputStreamReader(is, StandardCharsets.UTF_8)` 显式指定 UTF-8 编码，避免 `Properties.load(InputStream)` 默认的 ISO-889-1 导致的中文乱码问题。

### 3.2 类型化 Getter 与默认值兜底

所有 getter 均接受 `(key, defaultValue)` 形式，当 key 不存在或值为空时直接返回默认值；当值存在但类型不合法（如整数解析失败）时，打印警告日志并回退到默认值，**不会抛出异常**。这保证了即使配置文件损坏或缺失，框架仍能运行。

配置项按语义分组命名：
- `appium.*`：Appium 服务器地址、平台名、自动化引擎、设备 UDID、应用包名、是否 reset、命令超时、UiAutomator2 Server 安装/启动超时等
- `driver.type`：驱动类型（当前支持 `uiautomator2`，预留 `harmony_hdc` 等未来值）
- `timing.*`：轮询间隔、状态检测超时、动作超时、应用启动超时、广告视频超时、心跳间隔等
- `flow.*`：每轮页面数、最大广告轮次、目标免广告时长、奖励兜底估值、墙钟熔断时间、外循环次数、最大连续错误数、最大恢复重试次数
- `reader.*`：翻页策略（tap/swipe）、点击坐标比例、滑动百分比与时长
- `verify.*` / `runtime.*`：截图验证翻页、dry-run 模式开关

### 3.3 定位器注册表（LocatorRegistry）

`LocatorRegistry` 将 UI 控件的识别逻辑抽象为 `LocatorSpec`，每个控件包含：
- `primaryBy`：主定位器（最高优先级）
- `fallbackBys`：备选定位器列表（主定位器无命中时逐级尝试）
- `boundsHint`：目标区域比例描述（如 `y>0.9`、`x>0.8,y<0.2`），用屏幕比例而非绝对像素
- `allowGeometryFallback`：是否允许 L2 几何兜底

关键约束（在代码注释与测试中双重保证）：
- `ad.watch` 与 `ad.continue` 的 `allowGeometryFallback` **必须为 false**，因为误点会多看一轮视频、浪费每日配额
- 只有 `ad.close` 允许 `allowGeometryFallback = true`，且限定右上角区域（`x>0.8,y<0.2`），因为漏点导致卡死的后果远大于误点
- 所有候选文案通过 `|` 分隔的多值形式配置，读取时自动 split 并 trim

### 3.4 扩展点设计

- `driver.type` 字段预留了未来驱动类型（如 `harmony_hdc`），新增实现时无需修改上层调用方
- 新增配置项只需添加 getter 并在 properties 文件中声明默认值，无需改动其他模块

## 4. 约定与约束

- **所有运行时参数必须通过 `AutomationConfig` 获取**，禁止在业务代码中硬编码超时、轮次、阈值等数值（见类注释：“所有超时、轮次、阈值一律从此类获取，代码中不出现魔法数字”）
- **配置文件必须使用 UTF-8 编码保存**，否则中文文案（如“书架”“观看广告”“关闭”）会被 ISO-8859-1 解码为乱码，导致 UI 匹配失败（`LocatorRegistry` 注释中明确标注此为“最容易踩的坑”）
- **外部覆盖文件优先于 classpath 默认值**，但非法值（非预期类型）会静默回退到内置默认值，不会中断执行
- **缺失的外部配置文件是安全的**：加载失败时仅打印警告，仍保留 classpath 默认值
- **定位器的几何兜底策略受严格约束**：只有 `ad.close` 允许启用，其余关键操作按钮禁用，防止误触造成业务损失（由 `ConfigTest` 中的 `locatorSpec_geometryFallbackFlags` 断言强制验证）
- **候选文案以多值管道符分隔**：新增文案变体时直接在对应 key 后追加 `|新文案`，无需修改代码
- **dry-run 模式**：通过 `runtime.dry.run` 开关控制，配合 `--config` 外部覆盖可实现离线验证流程而不实际操控设备