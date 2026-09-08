package com.fanqie.auto.core;

import com.fanqie.auto.config.AutomationConfig;
import com.fanqie.auto.config.LocatorRegistry;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.Dimension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 屏幕元素定位器：智能定位屏幕上的 UI 元素并执行点击。
 * <p>
 * 定位策略优先级：
 * 1. UI 层级分析（uiautomator）- 快速、准确，但依赖 UI 树完整性
 * 2. OCR 文字识别 - 当 UI 层级无法定位时的降级方案
 * 3. 图像模板匹配 - 当 OCR 也不可用时的最终降级方案
 * <p>
 * 设计原则：
 * - 不依赖固定坐标，通过文字/图像内容定位
 * - 支持多种定位策略自动降级
 * - 提供详细的定位日志
 */
public class ScreenLocator implements DriverAware {

    private static final Logger log = LoggerFactory.getLogger(ScreenLocator.class);

    private volatile AppiumDriver driver;
    private final AutomationConfig config;
    private final GestureSupport gestureSupport;
    private final RunLogger runLogger;

    /** OCR 支持（可选，可能不可用） */
    private OcrSupport ocrSupport;

    public ScreenLocator(AppiumDriver driver, AutomationConfig config,
                         LocatorRegistry locatorRegistry, GestureSupport gestureSupport,
                         RunLogger runLogger) {
        this.driver = driver;
        this.config = config;
        this.gestureSupport = gestureSupport;
        this.runLogger = runLogger;

        // 尝试初始化 OCR（可能失败，不影响主流程）
        try {
            this.ocrSupport = new OcrSupport(driver, config, gestureSupport);
            if (!ocrSupport.isOcrAvailable()) {
                log.info("[ScreenLocator] OCR 不可用，将使用 UI 层级分析");
                this.ocrSupport = null;
            }
        } catch (Exception e) {
            log.warn("[ScreenLocator] OCR 初始化失败，将使用 UI 层级分析: {}", e.getMessage());
            this.ocrSupport = null;
        }
    }

    @Override
    public void refreshDriver(AppiumDriver newDriver) {
        if (newDriver != null) {
            this.driver = newDriver;
            this.gestureSupport.refreshDriver(newDriver);
            if (ocrSupport != null) {
                ocrSupport.refreshDriver(newDriver);
            }
        }
    }

    /**
     * 获取屏幕尺寸。
     */
    public Dimension getScreenSize() {
        return driver.manage().window().getSize();
    }

    // ==================== 核心定位 API ====================

    /**
     * 在屏幕上查找指定文字并点击。
     * 自动选择最佳定位策略。
     *
     * @param targetText 要查找的文字（如"领取奖励"、"继续观看"）
     * @return 是否找到并点击成功
     */
    public boolean findAndClickByText(String targetText) {
        return findAndClickByText(targetText, false);
    }

    /**
     * 在屏幕上查找指定文字并点击。
     *
     * @param targetText 要查找的文字
     * @param exactMatch 是否精确匹配
     * @return 是否找到并点击成功
     */
    public boolean findAndClickByText(String targetText, boolean exactMatch) {
        log.info("[ScreenLocator] 查找文字: '{}' (精确匹配={})", targetText, exactMatch);

        // 策略 1: UI 层级分析
        LocateResult result = locateByTextInUiTree(targetText, exactMatch);
        if (result != null && result.clickable) {
            log.info("[ScreenLocator] UI 层级定位成功: '{}' at ({}, {})",
                    targetText, result.centerX, result.centerY);
            gestureSupport.tapAtPixel(result.centerX, result.centerY);
            return true;
        }

        // 策略 2: OCR 识别
        if (ocrSupport != null && ocrSupport.isOcrAvailable()) {
            log.info("[ScreenLocator] 尝试 OCR 定位: '{}'", targetText);
            if (ocrSupport.findAndClick(targetText, exactMatch)) {
                return true;
            }
        }

        log.warn("[ScreenLocator] 未找到文字: '{}'", targetText);
        return false;
    }

    /**
     * 在屏幕上查找多个候选文字并点击第一个找到的。
     *
     * @param candidates 候选文字列表
     * @return 是否找到并点击成功
     */
    public boolean findAndClickByCandidates(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }

        for (String candidate : candidates) {
            if (findAndClickByText(candidate)) {
                return true;
            }
        }

        log.warn("[ScreenLocator] 所有候选文字均未找到: {}", candidates);
        return false;
    }

    /**
     * 在指定屏幕比例区域内查找可点击元素。
     * 用于定位"右上角关闭按钮"等特定区域的元素。
     *
     * @param xRatioMin 区域左边界比例 (0.0~1.0)
     * @param xRatioMax 区域右边界比例
     * @param yRatioMin 区域上边界比例
     * @param yRatioMax 区域下边界比例
     * @return 是否找到并点击成功
     */
    public boolean findAndClickInRegion(double xRatioMin, double xRatioMax,
                                        double yRatioMin, double yRatioMax) {
        Dimension size = getScreenSize();
        int x1 = (int) (xRatioMin * size.getWidth());
        int x2 = (int) (xRatioMax * size.getWidth());
        int y1 = (int) (yRatioMin * size.getHeight());
        int y2 = (int) (yRatioMax * size.getHeight());

        // 在区域内查找可点击元素
        List<UiNode> clickableNodes = findClickableInRegion(xRatioMin, xRatioMax, yRatioMin, yRatioMax);

        if (!clickableNodes.isEmpty()) {
            // 选择面积最小的可点击元素（更精确）
            UiNode target = clickableNodes.stream()
                    .min(Comparator.comparingLong(n -> n.getBounds().area()))
                    .orElse(null);

            if (target != null && target.isHasBounds()) {
                int centerX = (target.getBounds().getLeft() + target.getBounds().getRight()) / 2;
                int centerY = (target.getBounds().getTop() + target.getBounds().getBottom()) / 2;
                log.info("[ScreenLocator] 区域内定位成功: {} at ({}, {})",
                        target.getResourceId(), centerX, centerY);
                gestureSupport.tapAtPixel(centerX, centerY);
                return true;
            }
        }

        // 如果没找到可点击元素，点击区域中心
        int centerX = (x1 + x2) / 2;
        int centerY = (y1 + y2) / 2;
        log.info("[ScreenLocator] 区域内未找到可点击元素，点击中心: ({}, {})", centerX, centerY);
        gestureSupport.tapAtPixel(centerX, centerY);
        return true;
    }

    // ==================== UI 层级分析 ====================

    /**
     * 通过 UI 层级分析查找文字。
     */
    private LocateResult locateByTextInUiTree(String targetText, boolean exactMatch) {
        try {
            // 获取当前页面的 UI 层级
            String xml = driver.getPageSource();
            if (xml == null || xml.isEmpty()) {
                return null;
            }

            Dimension size = getScreenSize();
            UiSnapshot snapshot = new UiSnapshot(xml, size.getWidth(), size.getHeight());

            // 查找匹配文字的节点
            List<UiNode> matchedNodes = new ArrayList<>();

            // 1. 按 text 查找
            List<UiNode> textNodes = snapshot.findByTextContains(Collections.singletonList(targetText));
            matchedNodes.addAll(textNodes);

            // 2. 按 content-desc 查找
            List<UiNode> descNodes = snapshot.findByContentDescContains(Collections.singletonList(targetText));
            matchedNodes.addAll(descNodes);

            // 3. 查找可点击的祖先节点
            for (UiNode node : matchedNodes) {
                if (node.isClickable() && node.isHasBounds()) {
                    return new LocateResult(
                            node.getText().isEmpty() ? node.getContentDesc() : node.getText(),
                            (node.getBounds().getLeft() + node.getBounds().getRight()) / 2,
                            (node.getBounds().getTop() + node.getBounds().getBottom()) / 2,
                            true
                    );
                }

                // 查找可点击的祖先
                Optional<UiNode> clickableAncestor = snapshot.nearestClickableAncestor(node);
                if (clickableAncestor.isPresent() && clickableAncestor.get().isHasBounds()) {
                    UiNode ancestor = clickableAncestor.get();
                    return new LocateResult(
                            node.getText().isEmpty() ? node.getContentDesc() : node.getText(),
                            (ancestor.getBounds().getLeft() + ancestor.getBounds().getRight()) / 2,
                            (ancestor.getBounds().getTop() + ancestor.getBounds().getBottom()) / 2,
                            true
                    );
                }
            }

            return null;
        } catch (Exception e) {
            log.error("[ScreenLocator] UI 层级分析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 在指定区域内查找可点击节点。
     */
    private List<UiNode> findClickableInRegion(double xRatioMin, double xRatioMax,
                                               double yRatioMin, double yRatioMax) {
        try {
            String xml = driver.getPageSource();
            if (xml == null || xml.isEmpty()) {
                return Collections.emptyList();
            }

            Dimension size = getScreenSize();
            UiSnapshot snapshot = new UiSnapshot(xml, size.getWidth(), size.getHeight());

            return snapshot.findClickableInRegion(xRatioMin, xRatioMax, yRatioMin, yRatioMax, 0.1);
        } catch (Exception e) {
            log.error("[ScreenLocator] 区域内查找失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查 OCR 是否可用。
     */
    public boolean isOcrAvailable() {
        return ocrSupport != null && ocrSupport.isOcrAvailable();
    }

    /**
     * 定位结果。
     */
    private static class LocateResult {
        final String text;
        final int centerX;
        final int centerY;
        final boolean clickable;

        LocateResult(String text, int centerX, int centerY, boolean clickable) {
            this.text = text;
            this.centerX = centerX;
            this.centerY = centerY;
            this.clickable = clickable;
        }
    }
}
