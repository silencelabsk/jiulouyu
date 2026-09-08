# ScreenLocator 使用指南

## 概述

`ScreenLocator` 是一个智能屏幕元素定位器，通过 **UI 层级分析** 和 **OCR 文字识别** 来定位屏幕上的元素，替代传统的固定坐标点击方式。

### 优势

- **不依赖固定坐标**：通过文字内容定位，不受设备分辨率影响
- **自动降级策略**：UI 层级分析 → OCR 识别 → 区域点击
- **更可靠**：基于实际内容定位，而非猜测坐标

## 快速开始

### 1. 基本用法

```java
// 创建 ScreenLocator
ScreenLocator screenLocator = new ScreenLocator(driver, config, locators, gestures, logger);

// 通过文字查找并点击
boolean clicked = screenLocator.findAndClickByText("领取奖励");

// 使用候选文字列表
List<String> candidates = Arrays.asList("继续观看", "继续获取", "再看一个");
clicked = screenLocator.findAndClickByCandidates(candidates);

// 在指定区域内查找可点击元素（右上角区域）
clicked = screenLocator.findAndClickInRegion(0.8, 1.0, 0.0, 0.2);
```

### 2. 定位策略

ScreenLocator 按以下优先级自动选择定位策略：

1. **UI 层级分析**（首选）
   - 通过 `uiautomator` 获取 UI 树
   - 按 `text`、`content-desc` 查找匹配节点
   - 自动查找可点击的祖先节点
   - 快速、准确，但依赖 UI 树完整性

2. **OCR 文字识别**（降级方案）
   - 当 UI 层级无法定位时自动启用
   - 截图 → Tesseract OCR 识别 → 获取文字坐标
   - 需要安装 Tesseract 和训练数据

3. **区域点击**（最终降级）
   - 在指定屏幕比例区域内查找可点击元素
   - 如果找不到可点击元素，点击区域中心

### 3. OCR 功能配置（可选）

OCR 功能需要安装 Tesseract OCR：

#### Windows 安装

1. 下载 Tesseract 安装包：
   - https://github.com/UB-Mannheim/tesseract/wiki

2. 安装时勾选 **Chinese Simplified** 语言包

3. 设置环境变量（可选）：
   ```
   TESSDATA_PREFIX=C:\Program Files\Tesseract-OCR\tessdata
   ```

#### 训练数据

ScreenLocator 会自动查找以下位置的 tessdata 目录：

1. `automation/tessdata`（项目内）
2. `~/tessdata`（用户目录）
3. `C:\Program Files\Tesseract-OCR\tessdata`（Windows 默认）
4. `TESSDATA_PREFIX` 环境变量指定的目录

需要的训练数据文件：
- `chi_sim.traineddata`（中文简体）
- `eng.traineddata`（英文）

## API 参考

### 核心方法

| 方法 | 说明 |
|------|------|
| `findAndClickByText(String text)` | 查找文字并点击（包含匹配） |
| `findAndClickByText(String text, boolean exactMatch)` | 查找文字并点击（可指定精确匹配） |
| `findAndClickByCandidates(List<String> candidates)` | 查找候选文字列表中的第一个匹配项并点击 |
| `findAndClickInRegion(double x1, double x2, double y1, double y2)` | 在指定屏幕比例区域内查找并点击 |
| `isOcrAvailable()` | 检查 OCR 功能是否可用 |

### 区域坐标说明

`findAndClickInRegion` 使用屏幕比例坐标（0.0 ~ 1.0）：

- `(0.0, 0.0)` = 屏幕左上角
- `(1.0, 1.0)` = 屏幕右下角
- `(0.8, 1.0, 0.0, 0.2)` = 右上角 20% 区域

## 实际应用示例

### 广告流程自动化

```java
// 1. 等待视频播放完毕
adFlowPage.waitCountdownFinished();

// 2. 点击右上角关闭按钮（使用区域定位）
screenLocator.findAndClickInRegion(0.8, 1.0, 0.0, 0.2);

// 3. 点击"领取奖励"按钮（使用文字定位）
screenLocator.findAndClickByText("领取奖励");

// 4. 点击"继续观看"按钮（使用候选文字）
List<String> candidates = Arrays.asList("继续观看", "继续获取", "再看一个");
screenLocator.findAndClickByCandidates(candidates);
```

## 故障排查

### OCR 不可用

如果日志显示 "OCR 不可用"：

1. 检查是否已安装 Tesseract
2. 检查 tessdata 目录是否存在
3. 检查是否包含 `chi_sim.traineddata` 和 `eng.traineddata`

### 定位失败

如果 `findAndClickByText` 返回 false：

1. 检查文字是否正确（使用 `uiautomator dump` 查看 UI 树）
2. 尝试使用包含匹配而非精确匹配
3. 使用候选文字列表增加匹配机会
4. 检查 UI 层级是否完整（某些自绘控件可能不在 UI 树中）

## 性能考虑

- **UI 层级分析**：约 0.5-2 秒（取决于 UI 树大小）
- **OCR 识别**：约 3-10 秒（取决于屏幕复杂度和设备性能）

建议：
- 优先使用 UI 层级分析
- 仅在必要时启用 OCR
- 避免在循环中频繁调用 OCR

## 与现有代码集成

ScreenLocator 已集成到 FanqieRunner 中，可以通过以下方式获取：

```java
// 在 FanqieRunner 中已初始化
ScreenLocator screenLocator = new ScreenLocator(driver, config, locators, gestures, logger);

// 注册到 DriverRegistry
registry.register(screenLocator);
```

在 page 层或 task 层中，可以通过构造函数注入 ScreenLocator 来使用。
