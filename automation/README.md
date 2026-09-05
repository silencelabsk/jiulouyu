# 番茄免费小说 UI 自动化 · 鸿蒙版本确认 + 环境搭建 + 真机联调校准 操作手册

> 适用工程：`automation/`（Java + Appium 2 + UiAutomator2）
> 目标 App：番茄免费小说（包名 `com.dragon.read`）
> 入口类：`com.fanqie.auto.FanqieRunner`
> 构建产物：`target/fanqie-automation-1.0.0.jar`（fat-jar）
>
> 本手册所有命令均基于 **Windows PowerShell**，多命令请用 `;` 分隔，**不要**用 `&&`。

---

## 目录

1. [项目简介](#1-项目简介)
2. [第一步：确认鸿蒙版本与 adb 兼容性（关键决策闸门）](#2-第一步确认鸿蒙版本与-adb-兼容性关键决策闸门)
3. [环境搭建清单](#3-环境搭建清单)
4. [构建与自检](#4-构建与自检)
5. [真机 dry-run 校准（核心）](#5-真机-dry-run-校准核心)
6. [小步联调到长跑](#6-小步联调到长跑)
7. [故障排查](#7-故障排查)
8. [鸿蒙 NEXT 后续路线（如适用）](#8-鸿蒙-next-后续路线如适用)

---

## 1. 项目简介

### 1.1 功能

自动化完成番茄免费小说「看视频领免广告时长」全流程：

1. 自动激活 App，回到**书架页**；
2. 打开一本书进入**阅读页**，按策略翻页；
3. 翻页若干页后识别底部「观看视频获取免广告时长」**入口**；
4. 点击入口 → 确认「观看广告」→ 等待激励视频播放 → 关闭广告 → 领取奖励；
5. 累计领取到目标免广告时长（默认 120 分钟）后**优雅收尾**，输出统计汇总。

### 1.2 技术栈

| 项 | 版本/说明 |
| --- | --- |
| JDK | 11（`maven.compiler.release=11`） |
| Appium Java Client | 9.5.0 |
| Selenium BOM | 4.34.0（POM 强制 pin，防止开放版本区间拉到不兼容 patch） |
| 自动化引擎 | UiAutomator2（Android 原生 View 体系唯一可靠选择） |
| 日志 | SLF4J 2.0.16（slf4j-simple） |
| 测试 | JUnit 5.10.2 + Surefire 3.2.5（离线回放，不连真机） |
| 打包 | maven-shade-plugin 3.6.0（产出可执行 fat-jar） |
| 仓库镜像 | 阿里云 `https://maven.aliyun.com/repository/public` |

### 1.3 分层架构简述

工程采用严格的分层抽象，唯一需要为鸿蒙 NEXT 替换的组件是 `DriverFactory`，其余五层可 100% 复用：

```
FanqieRunner (main 入口 / CLI 解析 / 组件装配 / ShutdownHook)
   │
   ├── config 层    AutomationConfig（读 automation.properties）
   │                LocatorRegistry（读 locators.properties 文案候选集）
   │
   ├── core 层      EnvDoctor（环境自检）  DriverFactory（接口，可扩展点）
   │                └─ AppiumUiAutomator2DriverFactory（当前唯一实现）
   │                StateDetector（UI 树 → PageState 状态识别）
   │                PageState（枚举：BOOKSHELF/READER/AD_*/COMMON_POPUP...）
   │                RunLogger（心跳保活 + 日志/截图/快照落盘 + 统计）
   │                GestureSupport / WaitSupport / UiSnapshot / UiNode
   │                DriverRegistry（session 重建后统一刷新 driver 引用）
   │
   ├── page 层      BookshelfPage / ReaderPage / AdFlowPage（继承 BasePage）
   │
   └── task 层      AdWatchStateMachine（状态机主循环）
                    FanqieAdWatchTask（外层编排）
                    RecoveryHandler（多级自愈）
```

设计原则：**改行为不改代码**——所有超时、轮次、熔断阈值、文案候选集都集中在
`automation.properties` 与 `locators.properties`，代码中不出现魔法数字。App 改版改文案时只改配置文件即可。

### 1.4 合规提示 ⚠

> 本程序会**真实播放广告并自动领取免广告时长**，属于对平台激励机制的自动化利用，
> **可能违反 App 用户协议**，存在账号风控/受限的可能。
>
> 建议：
> - 用**小号**验证，不要在主力账号长跑；
> - **限制轮次**（通过 `flow.max.total.ad.rounds`、`flow.max.ad.rounds.per.entry`、`flow.max.wall.clock.ms` 等熔断阈值）；
> - 程序启动时会主动打印上述合规提示（`FanqieRunner.printComplianceNotice`）。

---

## 2. 第一步：确认鸿蒙版本与 adb 兼容性（关键决策闸门）

> **这是整个工程能否走现有零改动路线的决定性判据。请务必先做这一步。**
>
> - adb 能识别设备 → HarmonyOS ≤ 4.x（AOSP 兼容）→ **走现有 UiAutomator2 路线，零代码改动**。
> - adb 完全不识别 / 判定为 HarmonyOS NEXT(5.x) → 需后续实现 `HdcUiTestDriverFactory`（见第 8 章）。

### 2.1 用 adb 判断（首选）

在 PowerShell 中依次执行（手机已 USB 连接并完成第 3.7 节的手机侧设置）：

```powershell
# 1) 列出设备，看是否识别到、状态是否为 device
adb devices -l

# 2) 读取系统版本增量号
adb shell getprop ro.build.version.incremental

# 3) 读取品牌（华为/荣耀应为 HUAWEI / HONOR）
adb shell getprop ro.product.brand

# 4) 读取 Android SDK API level（AOSP 兼容层）
adb shell getprop ro.build.version.sdk
```

**判据：**

| `adb devices -l` 输出 | 结论 | 路线 |
| --- | --- | --- |
| 有设备行且状态列为 `device`（如 `UJN0220C17010611  device ...`），且 `ro.build.version.sdk` 返回数字 | HarmonyOS ≤ 4.x（AOSP 兼容） | ✅ **现有 UiAutomator2 路线，零代码改动** |
| 状态为 `unauthorized` | 已识别但未授权 | 手机上确认授权弹窗后重试（见 3.7） |
| 状态为 `offline` | 已识别但离线 | `adb kill-server; adb start-server` 或重插线 |
| `List of devices attached` 后**空**（无任何设备行），adb 始终不识别 | 极可能是 HarmonyOS NEXT / 5.x（无 AOSP 兼容层） | ⚠ 转 2.2 用 HDC 复核，若确认 NEXT 走第 8 章 |

> 记下这里识别到的**设备序列号**（UDID），第 3.8 节要填入 `appium.device.udid`。

### 2.2 adb 完全不识别时，用鸿蒙 HDC 复核是否为 NEXT

HarmonyOS NEXT / 5.x 移除了 AOSP 兼容层，adb 无法工作，改用华为官方 **HDC**（HarmonyOS Device Connector）：

```powershell
# 1) 列出 HDC 可识别的目标设备
hdc list targets

# 2) 读取鸿蒙 API 版本（NEXT 通常 >= 12；数值越高越可能是纯血鸿蒙）
hdc shell param get const.ohos.apiversion

# 3) 读取软件版本号
hdc shell param get const.product.software.version
```

**判据：**

- `hdc list targets` 能列出设备，但 `adb devices` 完全空 → 基本确认是 **HarmonyOS NEXT / 5.x（adb 不可用）**。
- `const.ohos.apiversion` / `const.product.software.version` 显示为 HarmonyOS NEXT / 5.x 系列 → 确认走 **HDC 兜底路线**（第 8 章）。
- 若 `hdc` 命令也不可用 → 说明尚未安装 HarmonyOS SDK / DevEco 工具链，需先安装再判断。

### 2.3 决策结论落地

- **判定为 ≤4.x（adb OK）**：直接跳到第 3 章，按现有路线搭建即可，`driver.type` 保持默认 `uiautomator2`。
- **判定为 NEXT（adb 不识别）**：先完成第 3、4、5 章的环境与构建自检（这些与设备路线无关），再按第 8 章新增 `HdcUiTestDriverFactory`，并把 `driver.type` 改为 `harmony_hdc`。工程已为该扩展点预留：`DriverFactory` 接口 + `driver.type` 配置化已就绪。

---

## 3. 环境搭建清单

> 已知本机现状：Java 11 已装；node/appium 已装但可能不在 PATH；adb/platform-tools 与 mvn 不在 PATH；automation 模块未导入 IntelliJ。
>
> **重要**：IDE 内的 PATH 与系统 shell 的 PATH 可能不一致。凡涉及 PATH 的改动，务必改**系统级环境变量**，改完后**重启 IDE 与所有终端**才生效。

### 3.1 安装 Android platform-tools（提供 adb）

方式一（winget，推荐）：

```powershell
winget install --id Google.PlatformTools -e
```

方式二：从 Android 官网下载 platform-tools 压缩包，解压到固定目录（如 `D:\Android\platform-tools`）。

### 3.2 设置 ANDROID_HOME 与 PATH（系统级）

`EnvDoctor` 会检查 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）：uiautomator2 driver 依赖它找到 adb。建议设为 platform-tools 的**父目录**（如 `D:\Android\Sdk`）。

在 PowerShell（当前用户级永久设置，随后**重启终端/IDE**）：

```powershell
# ANDROID_HOME 指向 SDK 根目录（platform-tools 的父目录）
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "D:\Android\Sdk", "User")

# 把 node、npm 全局目录、platform-tools 追加到用户 PATH
$add = "D:\Android\Sdk\platform-tools;$env:APPDATA\npm;C:\Program Files\nodejs"
$cur = [Environment]::GetEnvironmentVariable("Path", "User")
[Environment]::SetEnvironmentVariable("Path", "$cur;$add", "User")
```

> 请把上面的路径替换为你机器上 node / platform-tools 的**实际安装路径**。
> 验证（**新开一个终端**再执行）：
>
> ```powershell
> adb version ; node --version ; npm --version
> ```

### 3.3 启动 Appium Server 并确认就绪

```powershell
# 确认 appium 已全局安装
npm list -g appium

# 确认 uiautomator2 driver 已安装
appium driver list --installed

# 若未安装 uiautomator2 driver：
appium driver install uiautomator2

# 启动 Server（--base-path / 是关键：EnvDoctor 探测的是 http://127.0.0.1:4723/status）
appium --base-path /
```

在**另一个终端**验证 Server 就绪（期望返回 JSON 且包含 `"ready": true`）：

```powershell
curl http://127.0.0.1:4723/status
```

> `EnvDoctor.checkAppiumServer()` 正是对 `<appium.server.url>/status` 发 GET，判定响应体是否包含 `"ready": true`/`"ready":true`。
> Server 必须**持续在后台运行**，程序运行期间不要关闭该终端。

### 3.4 导入 Maven 模块 / 准备 mvn

本机 `mvn` 不在 PATH，两种选择：

**选择 A：把 automation/pom.xml 作为 Maven 模块导入 IntelliJ IDEA**

- IDEA → `File` → `New` → `Module from Existing Sources...` → 选中
  `d:\Install\Program Files\JetBrains\java\Student\automation\pom.xml` → 以 Maven 项目导入。
- 导入后 IDEA 使用其**内置 Maven**，无需单独安装 mvn，直接在 Maven 面板执行 `clean`/`package`/`test`。

**选择 B：直接使用 JetBrains 内置 mvn 命令行**

本机 IDEA 内置 Maven 路径：

```
D:\Install\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.1.2\plugins\maven\lib\maven3\bin\mvn.cmd
```

由于路径含空格，PowerShell 中需用 `&` 调用运算符。建议先设个别名变量：

```powershell
cd "d:\Install\Program Files\JetBrains\java\Student\automation"
$mvn = "D:\Install\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.1.2\plugins\maven\lib\maven3\bin\mvn.cmd"

# 用法示例：查看版本
& $mvn -v
```

> 也可选装独立 Maven 并加入 PATH，效果等价。本手册后续命令统一用 `& $mvn ...` 形式。

### 3.5 安装 Java 11（已具备可跳过）

工程要求 JDK 11（`maven.compiler.release=11`）。验证：

```powershell
java -version
```

### 3.6 安装 Appium Java Client 依赖

无需手动，`mvn package` 会自动从阿里云镜像拉取（见第 4 章）。

### 3.7 手机侧 7 项设置核对清单

程序非 `--doctor` 模式启动时会自动打印此清单（`FanqieRunner.printDeviceChecklist`）。逐项确认：

| # | 设置项 | 路径/说明 |
| --- | --- | --- |
| 1 | 解锁开发者选项 | 设置 → 关于手机 → 连续点击「HarmonyOS 版本」7 次（**必须在机主模式/主空间**下操作，子用户/隐私空间无法解锁） |
| 2 | 打开 USB 调试 | 设置 → 系统和更新 → 开发人员选项 → USB 调试 |
| 3 | 打开「仅充电模式下允许 ADB 调试」 | 同一页面。**华为/鸿蒙特有且最容易漏掉的一项！** |
| 4 | 处理「监控 ADB 安装应用」 | 开发人员选项底部。开启时 Appium 推送 APK 会弹确认框，**建议首轮临时关闭**，或人在手机旁逐个点「允许安装」 |
| 5 | USB 连接方式选「传输文件」 | 非「仅充电」；华为官方明确非原装线常只充电不传数据，**用原装数据线** |
| 6 | 首次插线授权 | 手机上勾选「始终允许来自这台计算机的调试」 |
| 7 | 保持唤醒 | 关闭自动锁屏 / 接通电源 / 开启「充电时保持唤醒」，把番茄小说加入电池优化白名单（完整流程可能运行 1–3 小时） |

### 3.8 安装番茄免费小说并核对 UDID

```powershell
# 确认 App 已安装（EnvDoctor.checkAppInstalled 也是查这个包名）
adb shell pm list packages | findstr dragon
```

- 在手机上安装「番茄免费小说」，包名应为 `com.dragon.read`。
- 打开 `src/main/resources/automation.properties`，核对 `appium.device.udid`：
  - 当前值为 `UJN0220C17010611`，请改成**你在 2.1 步 `adb devices -l` 实际看到的序列号**；
  - **不确定或多设备场景可留空**（`appium.device.udid=`），留空时 Appium 自动取第一个在线设备（`EnvDoctor`/工厂会打印「(自动选择)」）。多设备并行时**必须填写**。

> 提示：修改 `automation.properties` 后需要重新 `mvn package`（配置打进 jar），或用 `--config <外部路径>` 指定外部配置文件覆盖，无需重新编译（见 6.4）。

---

## 4. 构建与自检

### 4.1 构建 fat-jar

```powershell
cd "d:\Install\Program Files\JetBrains\java\Student\automation"
$mvn = "D:\Install\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.1.2\plugins\maven\lib\maven3\bin\mvn.cmd"

# 标准构建（含离线单元测试 + shade 打 fat-jar）
& $mvn clean package
```

构建成功后产物：`target/fanqie-automation-1.0.0.jar`（fat-jar，含全部依赖，可脱离 IDE 长跑）。

**应急只出包（跳过测试）：**

```powershell
& $mvn -DskipTests package
```

### 4.2 离线单元测试

本工程所有单测均为**离线回放**（fixture XML + 手写测试替身），不连真机、不启动 Appium session：

```powershell
& $mvn test
```

### 4.3 环境自检（--doctor，不创建 session）

`--doctor` 模式只跑 `EnvDoctor` 环境自检，**不创建 Appium session、不打印手机清单**，自检完即以 0 退出：

```powershell
java -jar target/fanqie-automation-1.0.0.jar --doctor
```

`EnvDoctor.checkAll()` 依次执行 **5 项检查**：

| # | 检查项 | 方法 | 失败/警告行为 |
| --- | --- | --- | --- |
| 1 | ANDROID_HOME | `checkAndroidHome()` | **仅警告**（非硬性）：未设置时提示 uiautomator2 依赖它找 adb；`ANDROID_SDK_ROOT` 存在也算通过 |
| 2 | adb 可用性 | `checkAdbVersion()` | 硬性：`adb version` 不可用 → 失败，提示 `winget install --id Google.PlatformTools -e` |
| 3 | 设备在线 | `checkAdbDevices()` | 硬性：列表空/未授权/离线/无法解析 → 失败，打印逐项排查指引 |
| 4 | Appium Server | `checkAppiumServer()` | 硬性：`/status` 无法连接或非 ready → 失败，提示 `appium --base-path /` |
| 5 | App 已安装 | `checkAppInstalled()` | 硬性：`pm list packages` 不含 `com.dragon.read` → 失败，提示安装 App |

- **任一硬性检查失败**：打印完整清单并返回 false，`FanqieRunner` 以**退出码 1** 结束（不抛裸异常堆栈）。
- **全部通过**：打印「✓ 全部检查通过，可以创建 Appium session」，`--doctor` 以**退出码 0** 结束。

---

## 5. 真机 dry-run 校准（核心）

> dry-run 是「只连接、只 dump、只识别，**绝不点击任何广告/观看视频/领取时长按钮**」的安全探针。
> 它不会消耗真实每日配额、不会播放视频，是校准 `locators.properties` 与 `StateDetector` resource-id 的唯一可靠依据。

### 5.1 运行 dry-run

命令行参数方式（推荐）：

```powershell
java -jar target/fanqie-automation-1.0.0.jar --dry-run
```

或用配置文件方式（把 `automation.properties` 中 `runtime.dry.run=true`）。命令行 `--dry-run` 会覆盖配置文件。

### 5.2 DryRunProbe 探测流程（5 步）

`DryRunProbe.probe()` 执行的**无害**流程：

1. **激活 App**：`gestures.activateApp(com.dragon.read)`，等待进入 `BOOKSHELF / APP_LAUNCHING / SPLASH_AD / COMMON_POPUP`；
2. **抓书架页快照**：`detector.tick()` → `saveSnapshot(..., "dryrun-bookshelf")`，打印节点统计与关键词节点；
3. **打开一本书**：**只允许**「切书架 Tab」与「点开一本书」两类无害操作（`openFirstBook`，按 clickable + 面积 + 位置过滤）；
4. **抓阅读页快照**：`saveSnapshot(..., "dryrun-reader")`，打印统计与关键词节点；
5. **输出结论建议**：判定各控件能否用 L1 语义定位，还是必须靠 L2 几何兜底 / L3 时序兜底。

探测关键词（用于高亮相关节点，不硬编码业务文案）：
`视频 / 广告 / 时长 / 关闭 / 继续 / 免费 / 书架 / 下一章 / 观看 / 领取 / 跳过 / 分钟 / 免广告`。

### 5.3 产物落盘位置

> 目录由系统属性 `automation.logs.dir` 决定，**默认 `automation/logs`**（相对当前工作目录，即你在哪个目录 `java -jar` 就落在那里的 `automation/logs`）。

| 产物 | 路径 | 说明 |
| --- | --- | --- |
| dry-run XML 快照 | `automation/logs/snapshots/<时间戳>_dryrun-bookshelf.xml` 与 `..._dryrun-reader.xml` | 书架页/阅读页原始 UI 树（`DryRunProbe.saveSnapshot`） |
| 运行文本日志 | `automation/logs/run-<时间戳>.log` | `RunLogger` 落盘（UTF-8） |
| 异常/恢复截图+快照 | `automation/logs/snapshots/<时间戳>_<tag>.png` / `.xml` | `RunLogger.captureSnapshot(tag)`（长跑阶段用） |

如需自定义落盘根目录：

```powershell
java "-Dautomation.logs.dir=D:\fanqie-logs" -jar target/fanqie-automation-1.0.0.jar --dry-run
```

### 5.4 用真实 UI 树校准 locators.properties 与 StateDetector

dry-run 控制台会打印每个页面的**节点属性统计**（总节点数、有 text / resource-id / content-desc / clickable / bounds 的数量与占比）和**关键词节点明细**（class / text / content-desc / resource-id / clickable / bounds / depth）。据此校准：

**（1）校准 `locators.properties` 文案候选集**

打开 `src/main/resources/locators.properties`，文件顶部即注明「阶段 A3 实测后，用真实 dump 结果校准本文件」。多个候选用 `|` 分隔，匹配时按顺序尝试，命中任一即成功。需重点核对的键：

| 配置键 | 用途 | 校准方法 |
| --- | --- | --- |
| `shelf.tab.text` | 书架 Tab 文案 | 在书架页 dump 里找底部导航栏实际文案 |
| `ad.entry.text` | 阅读页底部「观看视频获取免广告时长」入口 | 翻页后 dump 里找入口实际文案（运营文案会随版本/AB 漂移，多留变体） |
| `ad.watch.text` | 确认弹窗「观看广告」按钮 | dump 里核对实际按钮文案 |
| `ad.close.text` / `ad.close.desc` | 广告关闭按钮 text / content-desc | 穿山甲 SDK 关闭按钮常用 content-desc 而非 text，两者都要核对 |
| `ad.continue.text` | 「继续获取免费时长」二次确认按钮 | dump 核对 |
| `ad.reward.regex` | 从文本提取「N 分钟」的正则（默认 `(\d+)\s*分钟`，properties 中反斜杠需双写） | 核对奖励弹窗实际文案格式 |
| `chapter.next.text` | 「下一章」按钮 | dump 核对 |
| `common.dismiss.text` | 通用弹窗关闭文案（青少年模式/权限/登录/更新等） | dump 核对，顺序已按「弱→强」排列 |
| `reader.menu.text` | 阅读页菜单工具栏特征（**待真机 dump 校准**） | dump 核对 |
| `splash.skip.text` | 开屏广告跳过（**待真机 dump 校准**；已收窄为开屏专有长词，不含裸「跳过」） | dump 核对 |
| `daily.quota.exhausted.text` | 每日配额耗尽提示（**待真机校准**，命中走优雅收尾） | dump 核对当日弹窗实际文案 |

**（2）校准 `StateDetector` 中猜测的 resource-id 占位值**

`StateDetector.detectByResourceId()` 目前使用的是**待校准的占位 resource-id**：

| 占位值（源码硬编码） | 用途 | 说明 |
| --- | --- | --- |
| `com.dragon.read:id/tab_shelf` | 书架页锚点 | **占位，待真机 dump 校准** |
| `recycler` | 书籍列表可见性二次确认 | **占位，待真机 dump 校准** |
| `com.dragon.read:id/reader` | 阅读页容器锚点 | **占位，待真机 dump 校准** |

校准方法：在 dry-run dump 的 XML / 控制台关键词节点明细里，找到书架页、书籍列表、阅读页容器的**真实 resource-id**，替换 `StateDetector.java` 中上述三处占位值。resource-id 匹配是**最高确定性、最低成本**的识别方式（由开发者显式设定，不受运营文案变化影响）。

> ⚠ **注意（与「改配置不改代码」原则的边界）**：文案候选集（`locators.properties`）与所有阈值（`automation.properties`）都是**改配置不改代码**；但上述 3 个 resource-id 锚点目前是**硬编码在 `StateDetector.java`** 里的占位值，校准它们需要**改这一处代码常量**（本手册仅新增文档，不改代码；实际校准时由开发者按需修改源码这三行）。若真机页面**完全没有稳定 resource-id**，则保持占位不命中，识别会自动降级到 text / content-desc / 几何兜底（这正是分层设计的容错）。

### 5.5 DryRunProbe 结论建议解读

- 底部「观看视频获取免广告时长」入口：有 text/content-desc → L1 语义匹配即可；只有 bounds 无属性 → 必须 L2 几何兜底。
- 广告关闭按钮（穿山甲 SDK）：有 `content-desc='关闭'` → L1；裸 View 无属性 → L2(右上角) + L3(时序)。
- 「观看广告」/「继续获取免费时长」按钮：通常有 text → L1；★ **这两个按钮禁止 L2 几何兜底**（误点会浪费配额）。
- 阅读页节点数极少（<20）且无交互元素：正文为自绘 Canvas，翻页只能用坐标策略；底部入口可能是浮层，需翻页若干页后才出现。

---

## 6. 小步联调到长跑

> 原则：**先最小参数跑通，再逐步放大**。不要一上来就长跑。

### 6.1 最小参数联调

```powershell
# 只跑 1 个外层循环、每循环翻 2 页，快速验证全链路是否打通
java -jar target/fanqie-automation-1.0.0.jar --cycles 1 --pages 2
```

- `--cycles N` 覆盖 `flow.outer.cycles`（外层「翻页→再领时长」循环次数，默认 2）；
- `--pages N` 覆盖 `flow.pages.per.cycle`（每轮翻页数，默认 5）。

跑通后逐步放大到业务目标（`flow.target.free.minutes`，默认 120 分钟）：

```powershell
java -jar target/fanqie-automation-1.0.0.jar --cycles 2 --pages 5
```

### 6.2 --reset-progress 用途

```powershell
java -jar target/fanqie-automation-1.0.0.jar --reset-progress
```

- 清除 `progress.properties` 中的累计进度（`earnedMinutes` / `totalAdRounds` / `usedBooks`），从零开始；
- **典型场景**：上一次跑到一半残留了进度，重新启动时会「开局即判定已达标」而立刻退出。用 `--reset-progress` 清残留避免误判。
- 程序启动时会先打印「从 progress.properties 恢复的进度」，据此判断是否需要 reset。

### 6.3 CLI 参数全集

`FanqieRunner` 支持的全部命令行参数（未知参数会打印用法并退出）：

| 参数 | 说明 |
| --- | --- |
| `--doctor` | 只运行环境自检，不创建 session（自检完以 0 退出；失败以 1 退出） |
| `--dry-run` | 只连接并 dump UI 树，不执行任何点击（覆盖 `runtime.dry.run`） |
| `--config <path>` | 使用外部配置文件覆盖默认值（先加载 classpath 默认，再用外部文件覆盖） |
| `--cycles <N>` | 覆盖 `flow.outer.cycles` |
| `--pages <N>` | 覆盖 `flow.pages.per.cycle` |
| `--reset-progress` | 清除 `progress.properties` 累计进度，从零开始 |

**退出码约定**：`0` = 正常完成 / `--doctor` 自检通过；`1` = 环境自检未通过；`2` = 运行异常（会先 `captureSnapshot("fatal_error")` 留现场）。

### 6.4 关键配置项说明（automation.properties）

> 用 `--config <外部文件>` 可不重新编译即调整以下配置（`AutomationConfig(String externalPath)` 先加载 classpath 默认再覆盖）。

**驱动类型（可扩展性）**

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `driver.type` | `uiautomator2` | 决定装配哪个 `DriverFactory`。当前仅实现 `uiautomator2`；预留 `harmony_hdc`（NEXT 兜底）。未知值会记 warn 并**回退到 uiautomator2** |

**Appium 连接**

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `appium.server.url` | `http://127.0.0.1:4723` | Server HTTP 端点 |
| `appium.platform.name` | `Android` | 目标平台 |
| `appium.automation.name` | `UiAutomator2` | 自动化引擎 |
| `appium.device.udid` | `UJN0220C17010611` | 设备序列号；留空则自动取第一个在线设备 |
| `appium.app.package` | `com.dragon.read` | 番茄免费小说包名 |
| `appium.no.reset` | `true` | **底线配置**：保留登录态与书架数据；设 false 会清 App 数据 |
| `appium.new.command.timeout.sec` | `1800` | session 无命令多久断开（配合心跳保活规避广告 30s 静默断连） |
| `appium.uia2.server.launch.timeout.ms` | `60000` | UiAutomator2 Server 首装超时（华为首启慢给足 60s） |
| `appium.skip.server.installation` | `false` | 首轮成功后改 true 可省 20–40s 初始化 |
| `appium.skip.device.initialization` | `false` | io.appium.settings APK 跳过开关 |
| `appium.keep.screen.on` | `true` | 保持屏幕常亮 |

**时序控制**

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `timing.poll.interval.ms` | `250` | 状态轮询最小间隔 |
| `timing.state.detect.timeout.ms` | `3000` | 单次状态识别超时 |
| `timing.action.timeout.ms` | `8000` | 单次点击/滑动超时 |
| `timing.app.launch.timeout.ms` | `30000` | App 冷启动超时 |
| `timing.ad.video.timeout.ms` | `45000` | 广告视频播放超时 |
| `timing.ad.close.ready.timeout.ms` | `15000` | 等待关闭按钮出现超时 |
| `timing.max.state.dwell.ms` | `90000` | 单状态最大滞留，超过判定卡死触发恢复 |
| `timing.heartbeat.interval.sec` | `30` | 心跳保活间隔 |

**业务流程与熔断阈值**

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `flow.pages.per.cycle` | `5` | 每轮翻页数（`--pages` 可覆盖） |
| `flow.max.ad.rounds.per.entry` | `4` | **单入口**视频上限（对应 `config.maxAdRoundsPerEntry()`） |
| `flow.max.total.ad.rounds` | `40` | **全局**视频上限，防无限消耗每日配额 |
| `flow.target.free.minutes` | `120` | 业务目标（主退出条件）：累计免广告分钟数 |
| `flow.reward.fallback.minutes` | `30` | 奖励兜底估值：弹窗未提取到「N 分钟」时按此保守累加，防进度无法推进 |
| `flow.max.wall.clock.ms` | `10800000` | 墙钟预算（3 小时），达到即优雅收尾；≤0 表示不启用 |
| `flow.outer.cycles` | `2` | 外层循环次数（`--cycles` 可覆盖） |
| `flow.max.consecutive.errors` | `3` | 连续错误达此值触发 session 重建（第 5 级自愈） |
| `flow.max.recovery.retry` | `3` | 单次恢复最大重试，超过则保留现场退出 |

**阅读页翻页策略**

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `reader.turn.strategy` | `tap` | `tap`（点右侧热区，最快最稳）或 `swipe`（滑动，备选） |
| `reader.next.tap.ratio.x` / `.y` | `0.85` / `0.50` | 下一页点击位置（屏宽 85%、屏高 50%） |
| `reader.prev.tap.ratio.x` | `0.15` | 上一页点击位置（屏宽 15%） |
| `reader.swipe.percent` | `0.6` | swipe 距离百分比 |
| `reader.swipe.duration.ms` | `300` | swipe 持续时间（过快可能被系统忽略） |

**验证与调试**

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `verify.page.turned.by.screenshot` | `false` | 截图哈希校验翻页（有 200–500ms 开销，默认关） |
| `runtime.dry.run` | `false` | dry-run 模式（`--dry-run` 可覆盖） |

### 6.5 多本书轮换与每日配额耗尽的优雅收尾

- **多本书轮换**：`AdWatchStateMachine` 维护 `usedBooks` 集合（per-book 进度维度）。在书架页调 `bookshelfPage.openNextBook(usedBooks)` 打开**下一本未使用**的书，成功后 `markBookUsed(bookId)`；无未使用书籍时兜底 `openAnyBook()`，避免在少数书间打转或在书架空转。
- **每日配额耗尽优雅收尾**：命中 `daily.quota.exhausted.text` 文案且满足弹窗上下文（连续多个 tick 命中）时，`dailyQuotaExhausted=true`，走**优雅收尾**分支——正常结束并 `saveProgress()` + 输出统计汇总，而**不是**落入 UNKNOWN 反复重试。

### 6.6 进度持久化（progress.properties）

- 路径：`automation/logs/progress.properties`（与日志同根目录，受 `automation.logs.dir` 影响）。
- 持久化内容：`earnedMinutes`、`totalAdRounds`、`usedBooks`、`lastUpdateDate`。每轮广告子循环结束、配额耗尽、熔断退出时都会 `saveProgress()`。
- **断点续跑**：session 重建/程序重启后 `loadProgress()` 读回 `earnedMinutes`、`totalAdRounds`，不重复已完成轮次。
- **per-book usedBooks 的时效（当天有效、跨天清空）**：只有当持久化的 `lastUpdateDate` 等于**当天**（`LocalDate.now()`）时才恢复 `usedBooks`；否则清空——跨天残留会导致续跑首次轮换即无书可换。旧文件缺 `lastUpdateDate` 字段时保守清空。

---

## 7. 故障排查

### 7.1 Session 创建失败定向诊断

`AppiumUiAutomator2DriverFactory.printSessionCreationDiagnostics()` 在 session 创建失败时输出以下**逐项排查**清单（不抛裸堆栈）：

1. **手机是否有待确认的 APK 安装弹窗？** 华为「监控 ADB 安装应用」开启时，Appium 推送的 3 个 APK 会弹确认框 → 在手机上逐个点「允许安装」（首轮建议临时关闭该开关）。
2. **是否开启「仅充电模式下允许 ADB 调试」？** 设置 → 系统和更新 → 开发人员选项。**华为/鸿蒙特有、最易漏。**
3. **USB 调试是否开启？** 设置 → 系统和更新 → 开发人员选项 → USB 调试。
4. **手机是否已授权本电脑？** 重插 USB 线，勾选「始终允许来自这台计算机的调试」。
5. **Appium Server 是否已启动？** 终端执行 `appium --base-path /`，检查 `http://127.0.0.1:4723/status` 是否返回 ready。
6. **adb 是否能识别设备？** 终端执行 `adb devices -l`，期望看到 `<你的UDID>   device`。

### 7.2 EnvDoctor 常见失败与处置

| 现象 | 处置 |
| --- | --- |
| `adb 命令不可用` | `winget install --id Google.PlatformTools -e`，安装后刷新 PATH 或重启终端（见 3.1/3.2） |
| `未检测到任何设备（列表为空）` | 逐项排查：解锁开发者选项（主空间下点 HarmonyOS 版本 7 次）→ 开 USB 调试 → 开「仅充电模式允许 ADB 调试」→ USB 选「传输文件」→ 插线授权 → 处理「监控 ADB 安装应用」→ 用原装数据线 |
| `设备 unauthorized` | 手机上确认 USB 调试授权弹窗，勾选「始终允许」 |
| `设备 offline` | 重插 USB 线，或 `adb kill-server; adb start-server` |
| `Appium Server 无法连接 / 非 ready` | 确认 `node --version`、`npm list -g appium`、`appium driver list --installed`，再 `appium --base-path /` |
| `com.dragon.read 未安装` | 手机安装番茄免费小说，或核对包名：`adb shell pm list packages \| findstr dragon` |
| `ANDROID_HOME 未设置`（**仅警告**） | 设为 platform-tools 父目录（如 `D:\Android\Sdk`），见 3.2 |

### 7.3 Session 断开后的多级自愈与心跳保活

**心跳保活**（`RunLogger`）：独立 daemon 线程每 `timing.heartbeat.interval.sec=30` 秒执行一次轻量 `driver.getWindowSize()` 主动保活——即使业务处于广告视频 30s 长静默期也不会触发 `newCommandTimeout`（默认 1800s）。保活线程内部吞掉所有异常，绝不让异常杀死调度线程；探活失败时把 `sessionAlive` 置 false 供上层感知。session 重建后 `refreshDriver()` 会**复位保活标志**，避免 recreate 窗口期内旧 driver 抛异常导致永久失效。

**多级自愈**（`RecoveryHandler`）：由弱到强逐级尝试，超过 `flow.max.recovery.retry=3` 则停止并保留现场，**绝不盲目连点**。实现上的各级：

| 级别 | 手段 | 说明 |
| --- | --- | --- |
| 第 1 级 | COMMON_POPUP 处理 | 按 `common.dismiss.text` 候选文案**由弱到强**尝试关闭（先「我知道了/以后再说/暂不」等无副作用项，最后才「取消/不同意」）。恢复判定收窄为显式锚点白名单（READER 系列 或 BOOKSHELF） |
| 第 2 级 | 逐级 `back()` | 最多 `maxRecoveryRetry` 次，每次 back 后重新 `detect()` 确认是否回到 READER/BOOKSHELF 锚点；遇弹窗则关闭 |
| 第 3 级 | 重启 App | `terminateApp` + `activateApp`，等待启动完成 |
| 第 4 级 | 导航到书架 | 重启后若状态非 BOOKSHELF，主动 `switchToShelfTab()`；SPLASH_AD/COMMON_POPUP 先关闭 |
| 第 5 级 | 重建 session | 连续失败达 `flow.max.consecutive.errors=3` 时 `driverFactory.recreate()`（`noReset=true` 保证书架/登录不丢）→ 通过 `DriverRegistry.refreshAll(newDriver)` 刷新所有组件 driver 引用 → 从断点继续 |
| 兜底 | 落盘诊断退出 | 重建仍失败 → `captureSnapshot("recovery-failed")` 保存现场 + `markSessionDead()` 后退出 |

> **命名说明（源码与本手册需注意的不一致点）**：`RecoveryHandler` 类注释标题写作「五级自愈处理器」，但类内 `<ol>` 列了 6 条、代码注释也标到「第 6 级」。实际可理解为 **5 个恢复级别 + 1 个落盘诊断兜底**。本手册按源码实际的「第 1~5 级 + 兜底」呈现。

### 7.4 长跑现场取证

- 运行日志：`automation/logs/run-<时间戳>.log`；
- 快照/截图：`automation/logs/snapshots/`（tag 如 `recovery-failed`、`fatal_error`、`recovery_attempt_*`）；
- 结束时 `RunLogger.printFinalStatistics()` 输出结构化统计：总运行时间、累计免广告时长/目标、总广告轮次、总翻页数、状态识别次数与平均耗时、最终连续错误数、异常分布。
- XML 体积超 1MB 会输出「UI 树泄漏」预警。

---

## 8. 鸿蒙 NEXT 后续路线（如适用）

> **仅当第 2 章判定设备为 HarmonyOS NEXT / 5.x（adb 完全不可用）时才需要本章。** 若 adb 可识别（≤4.x），忽略本章，直接用现有路线。

### 8.1 为什么现有路线不适用

HarmonyOS NEXT / 5.x 移除了 AOSP 兼容层，adb 与 UiAutomator2 均无法工作。工程已在架构上预留兜底扩展点：

- `core/DriverFactory.java` 是接口，注释明确预留 `HdcUiTestDriverFactory` 的位置；
- `driver.type` 已配置化（`automation.properties` / `AutomationConfig.driverType()`），`FanqieRunner.createDriverFactory()` 已用 switch 分发，只需新增 `case "harmony_hdc"` 分支；
- 上层五层与 driver 实现解耦，可 100% 复用。

### 8.2 需要新增的实现

新增 `HdcUiTestDriverFactory implements DriverFactory`，实现接口的**五个方法**：

| 方法 | 职责 |
| --- | --- |
| `AppiumDriver create()` | 创建新 session 并返回 driver（首次执行完整创建流程） |
| `AppiumDriver recreate()` | 销毁当前 session 并重建（用于断开恢复） |
| `boolean healthCheck()` | 低成本探活（当前实现用 `getWindowSize()`，捕获 NoSuchSession/InvalidSessionId） |
| `void quit()` | 优雅退出，幂等 |
| `AppiumDriver getDriver()` | 获取当前 driver（可能为 null） |

并在 `FanqieRunner.createDriverFactory()` 增加分支（源码已留注释占位）：

```java
// 未来: case "harmony_hdc" -> new HdcUiTestDriverFactory(config);
```

然后把配置改为：

```properties
driver.type=harmony_hdc
```

> 注意：`getDriver()`/`create()` 返回类型是 `io.appium.java_client.AppiumDriver`。若鸿蒙 NEXT 的 UiTest 通道不是标准 Appium，需要在 `HdcUiTestDriverFactory` 内部做适配（例如封装一个满足 `AppiumDriver` 接口契约的桥接实现），以保证上层拿到的仍是 `AppiumDriver`。

### 8.3 可 100% 复用的上层（无需改动）

- `StateDetector`（UI 树 → PageState）
- `PageState`（状态枚举）
- `LocatorRegistry`（文案候选集）
- `AdWatchStateMachine`（状态机主循环）
- `RunLogger`（心跳/日志/统计）
- `page` 层：`BookshelfPage` / `ReaderPage` / `AdFlowPage` / `BasePage`

这正是分层抽象的价值兑现点：**唯一改动点是 `DriverFactory` 实现 + `driver.type` 配置**，上层零感知。

---

## 附录：命令速查

```powershell
# 进入工程目录 + 设定内置 mvn 别名（每个新终端执行一次）
cd "d:\Install\Program Files\JetBrains\java\Student\automation"
$mvn = "D:\Install\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.1.2\plugins\maven\lib\maven3\bin\mvn.cmd"

# 版本/兼容性判断
adb devices -l ; adb shell getprop ro.build.version.sdk ; adb shell getprop ro.product.brand
hdc list targets ; hdc shell param get const.ohos.apiversion

# 启动 Appium Server（保持后台运行）
appium --base-path /

# 构建 + 自检 + 校准 + 联调
& $mvn clean package
& $mvn test
java -jar target/fanqie-automation-1.0.0.jar --doctor
java -jar target/fanqie-automation-1.0.0.jar --dry-run
java -jar target/fanqie-automation-1.0.0.jar --cycles 1 --pages 2
java -jar target/fanqie-automation-1.0.0.jar --reset-progress
```
