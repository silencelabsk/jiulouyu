---
kind: external_dependency
name: Appium 2 + UiAutomator2 自动化驱动
slug: appium-uiautomator2
category: external_dependency
category_hints:
    - vendor_identity
    - sdk_real_api
    - framework_behavior
scope:
    - '**'
---

### 项目中的角色

### 集成方式
- `core/DriverFactory` 是抽象接口，当前唯一实现为 `AppiumUiAutomator2DriverFactory`；`driver.type` 配置项用于分发不同驱动实现（当前仅 `uiautomator2`，预留 `harmony_hdc`）。
- 构建产物为 fat-jar（maven-shade-plugin），依赖通过阿里云 Maven 镜像拉取。

### 关键约束
- 必须安装并启动 Appium Server 及 uiautomator2 driver（`appium driver install uiautomator2`）。
- 鸿蒙 NEXT（5.x）无 AOSP 兼容层时 adb/UiAutomator2 不可用，需按 README 第 8 章新增 `HdcUiTestDriverFactory` 并切换 `driver.type=harmony_hdc`。