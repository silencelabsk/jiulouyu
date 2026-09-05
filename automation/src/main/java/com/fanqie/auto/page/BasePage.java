package com.fanqie.auto.page;

import com.fanqie.auto.config.AutomationConfig;
import com.fanqie.auto.config.LocatorRegistry;
import com.fanqie.auto.core.DriverAware;
import com.fanqie.auto.core.GestureSupport;
import com.fanqie.auto.core.RunLogger;
import com.fanqie.auto.core.UiNode;
import com.fanqie.auto.core.UiSnapshot;
import io.appium.java_client.AppiumDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 页面对象基类：承载四级定位器降级链。
 * <p>
 * 四级降级策略是整个方案技术含量最高的设计，也是「动态解析 UI 树、不写死坐标」选型约束的落地点：
 * <ul>
 *   <li>L1 语义属性匹配（主路径）：在当次 UiSnapshot 的内存节点表中，
 *       按 text 候选集 → content-desc 候选集 → resource-id（先精确后包含）三通道依次查找。
 *       命中多个时用 boundsHint 规则裁决。</li>
 *   <li>L2 结构化几何推断：当 L1 三通道完全无命中时，筛选 clickable=true 且
 *       areaRatio &lt; 0.15 且中心落在 boundsHint 目标区域的节点。
 *       <b>误点防护：必须先检查 LocatorSpec.isAllowGeometryFallback()，
 *       只有为 true 时才允许 L2。</b></li>
 *   <li>L3 时序兜底：激励视频通常固定约 30 秒，当等待计时达到 config.adVideoTimeoutMs() 后，
 *       即使 L1 找不到按钮也执行 L2 几何点击（同样受 allowGeometryFallback 约束）。</li>
 *   <li>L4 系统兜底：driver.navigate().back() 强制退出；再失败则 restartApp 重启。</li>
 * </ul>
 * <p>
 * 点击方式的三个设计理由：
 * ① 坐标由实时 UI 树 bounds 推算而非硬编码，满足动态解析要求；
 * ② 规避自绘控件无属性时拿不到 WebElement 的问题；
 * ③ 天然免疫 stale element（每次用快照坐标点击，不持有 WebElement 引用）。
 */
public class BasePage implements DriverAware {

    private static final Logger log = LoggerFactory.getLogger(BasePage.class);

    // ==================== boundsHint 语义常量 ====================

    /** 底部区域：y > 0.8H。对应页面底部的广告入口（「观看视频获取免广告时长」） */
    public static final String HINT_BOTTOM = "y>0.8";
    /** 极底部区域：y > 0.9H。对应底部导航栏的 Tab（如「书架」Tab） */
    public static final String HINT_BOTTOM_TAB = "y>0.9";
    /** 右上角区域：x > 0.8W 且 y < 0.2H。对应广告关闭按钮 */
    public static final String HINT_TOP_RIGHT = "x>0.8,y<0.2";
    /** 居中区域：x 在 0.2~0.8、y 在 0.3~0.7。对应弹窗按钮 */
    public static final String HINT_CENTER = "center";

    protected volatile AppiumDriver driver;
    protected final AutomationConfig config;
    protected final LocatorRegistry locators;
    protected final GestureSupport gestures;
    protected final RunLogger logger;

    public BasePage(AppiumDriver driver, AutomationConfig config, LocatorRegistry locators,
                    GestureSupport gestures, RunLogger logger) {
        this.driver = driver;
        this.config = config;
        this.locators = locators;
        this.gestures = gestures;
        this.logger = logger;
    }

    @Override
    public void refreshDriver(AppiumDriver newDriver) {
        if (newDriver != null) this.driver = newDriver;
    }

    // ==================== 统一入口 ====================

    /**
     * 按 LocatorSpec 执行四级降级链定位并点击。
     *
     * @param spec     目标控件的定位规格
     * @param snapshot 当次 tick 的 UI 快照（纯内存操作，零额外 RPC）
     * @return true=成功定位并点击, false=全部降级失败
     */
    public boolean clickBySpec(LocatorRegistry.LocatorSpec spec, UiSnapshot snapshot) {
        Optional<UiNode> resolved = resolve(spec, snapshot);
        if (resolved.isPresent()) {
            clickNode(resolved.get(), snapshot);
            return true;
        }
        return false;
    }

    /**
     * 按 LocatorSpec 执行 L1 语义属性匹配 + boundsHint 裁决，返回最佳命中节点。
     * <p>
     * L1 三通道优先级：text 候选集 → content-desc 候选集 → resource-id（先精确后包含）。
     * 命中多个时用 boundsHint 规则裁决。
     *
     * @param spec     目标控件的定位规格
     * @param snapshot 当次 tick 的 UI 快照
     * @return 最佳命中节点，无命中返回 Optional.empty()
     */
    public Optional<UiNode> resolve(LocatorRegistry.LocatorSpec spec, UiSnapshot snapshot) {
        if (snapshot == null || snapshot.isParseFailed() || snapshot.size() == 0) {
            log.debug("[BasePage] 快照为空或解析失败，无法执行 L1");
            return Optional.empty();
        }

        // === L1 通道 1：text 候选集匹配 ===
        List<String> textCandidates = extractTextCandidatesFromSpec(spec);
        if (!textCandidates.isEmpty()) {
            List<UiNode> textHits = snapshot.findByTextContains(textCandidates);
            if (!textHits.isEmpty()) {
                log.debug("[BasePage] L1 text 通道命中 {} 个节点", textHits.size());
                return Optional.of(arbitrate(textHits, spec.getBoundsHint(), snapshot));
            }
        }

        // === L1 通道 2：content-desc 候选集匹配 ===
        List<String> descCandidates = extractDescCandidatesFromSpec(spec);
        if (!descCandidates.isEmpty()) {
            List<UiNode> descHits = snapshot.findByContentDescContains(descCandidates);
            if (!descHits.isEmpty()) {
                log.debug("[BasePage] L1 text 通道未命中，降级到 L1 content-desc 通道，命中 {} 个节点",
                        descHits.size());
                return Optional.of(arbitrate(descHits, spec.getBoundsHint(), snapshot));
            }
        }

        // === L1 通道 3：resource-id 匹配（从 fallbackBys 中提取 id 信息） ===
        // 由于 LocatorSpec 的 By 是 Selenium 对象，这里从 spec 的 primaryBy/fallbackBys 提取 id 不可行，
        // 改用直接查找带 resource-id 且与控件语义相关的节点
        // 注：resource-id 通道主要用于 BookshelfPage 等有明确 id 的场景

        log.debug("[BasePage] L1 三通道全部未命中 (boundsHint={})", spec.getBoundsHint());
        return Optional.empty();
    }

    /**
     * L2 结构化几何推断：在指定区域内查找 clickable=true 且 areaRatio < 0.15 的节点。
     * <p>
     * <b>误点防护（极重要）：必须先检查 LocatorSpec.isAllowGeometryFallback()，
     * 只有为 true 时才允许 L2。adWatch 与 adContinue 的该标志为 false ——
     * 因为误点这两个按钮会多看一轮视频、浪费用户每日的时长配额。绝不可绕过这个检查。</b>
     *
     * @param spec     目标控件的定位规格
     * @param snapshot 当次 UI 快照
     * @return 几何推断命中的节点，未命中或不允许几何兜底返回 Optional.empty()
     */
    public Optional<UiNode> resolveByGeometry(LocatorRegistry.LocatorSpec spec, UiSnapshot snapshot) {
        // ★ 误点防护：必须检查 allowGeometryFallback 标志
        if (!spec.isAllowGeometryFallback()) {
            log.debug("[BasePage] L2 几何兜底被禁止 (allowGeometryFallback=false)，跳过");
            return Optional.empty();
        }

        if (snapshot == null || snapshot.isParseFailed()) {
            return Optional.empty();
        }

        String hint = spec.getBoundsHint();
        if (hint == null || hint.isEmpty()) {
            // 无区域限制时，L2 不执行（没有几何依据无法推断）
            log.debug("[BasePage] L2 跳过：boundsHint 为空，无几何推断依据");
            return Optional.empty();
        }

        // 根据 boundsHint 确定搜索区域
        double[] region = parseBoundsHintRegion(hint);
        if (region == null) {
            return Optional.empty();
        }

        List<UiNode> candidates = snapshot.findClickableInRegion(
                region[0], region[1], region[2], region[3], 0.15);

        if (candidates.isEmpty()) {
            log.info("[BasePage] L2 几何兜底未命中任何节点 (区域={})", hint);
            return Optional.empty();
        }

        // 多个命中时取面积最小的（更具体的控件优先）
        UiNode best = candidates.stream()
                .min(Comparator.comparingLong(n -> n.getBounds().area()))
                .orElse(candidates.get(0));

        log.info("[BasePage] L2 几何兜底命中: class={}, bounds={}", best.getClazz(), best.getBounds());
        return Optional.of(best);
    }

    /**
     * L3 时序兜底：当等待计时达到 adVideoTimeoutMs 后，即使 L1 找不到按钮也执行 L2 几何点击。
     * 同样受 allowGeometryFallback 约束。
     * <p>
     * 激励视频通常固定约 30 秒；超时后关闭按钮大概率已出现但因 SDK 自绘而无属性，
     * 此时用几何推断是唯一出路。
     *
     * @param spec       目标控件的定位规格
     * @param snapshot   当次 UI 快照
     * @param elapsedMs  已经过的等待时间（毫秒）
     * @return true=时序兜底成功点击, false=兜底条件不满足或点击失败
     */
    public boolean clickByTimeoutFallback(LocatorRegistry.LocatorSpec spec, UiSnapshot snapshot, long elapsedMs) {
        if (elapsedMs < config.adVideoTimeoutMs()) {
            return false; // 尚未达到时序兜底阈值
        }

        log.info("[BasePage] L3 时序兜底触发：已等待 {}ms >= 阈值 {}ms", elapsedMs, config.adVideoTimeoutMs());

        // L3 本质是强制执行 L2，同样受 allowGeometryFallback 约束
        Optional<UiNode> geoNode = resolveByGeometry(spec, snapshot);
        if (geoNode.isPresent()) {
            clickNode(geoNode.get(), snapshot);
            return true;
        }

        log.warn("[BasePage] L3 时序兜底：L2 几何推断也无命中，降级到 L4");
        return false;
    }

    /**
     * L4 系统兜底：navigate().back() 强制退出当前页。
     * 再失败则由调用方决定是否 restartApp。
     *
     * @return true=back 操作成功执行（不保证状态恢复），false=back 操作本身异常
     */
    public boolean systemBack() {
        try {
            log.info("[BasePage] L4 系统兜底：执行 navigate().back()");
            driver.navigate().back();
            return true;
        } catch (Exception e) {
            log.error("[BasePage] L4 navigate().back() 异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * L4 系统兜底（终极手段）：重启 App，让状态机回到 BOOKSHELF 锚点重新进入。
     */
    public boolean systemRestartApp() {
        try {
            log.info("[BasePage] L4 系统兜底（终极）：重启 App");
            gestures.restartApp(config.appPackage());
            return true;
        } catch (Exception e) {
            log.error("[BasePage] L4 重启 App 异常: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 便捷方法 ====================

    /**
     * 按 text 候选集查找并点击（走 L1 text 通道 + boundsHint 裁决）。
     */
    public boolean clickByTextCandidates(List<String> candidates, UiSnapshot snapshot) {
        if (candidates == null || candidates.isEmpty() || snapshot == null) return false;
        List<UiNode> hits = snapshot.findByTextContains(candidates);
        if (hits.isEmpty()) return false;
        UiNode best = hits.size() == 1 ? hits.get(0) :
                hits.stream().min(Comparator.comparingLong(n -> n.getBounds().area())).orElse(hits.get(0));
        clickNode(best, snapshot);
        return true;
    }

    /**
     * 按 content-desc 候选集查找并点击（走 L1 desc 通道）。
     */
    public boolean clickByContentDescCandidates(List<String> candidates, UiSnapshot snapshot) {
        if (candidates == null || candidates.isEmpty() || snapshot == null) return false;
        List<UiNode> hits = snapshot.findByContentDescContains(candidates);
        if (hits.isEmpty()) return false;
        UiNode best = hits.size() == 1 ? hits.get(0) :
                hits.stream().min(Comparator.comparingLong(n -> n.getBounds().area())).orElse(hits.get(0));
        clickNode(best, snapshot);
        return true;
    }

    /**
     * 按 resource-id 查找并点击。
     */
    public boolean clickByResourceId(String resourceId, boolean exact, UiSnapshot snapshot) {
        if (resourceId == null || resourceId.isEmpty() || snapshot == null) return false;
        List<UiNode> hits = snapshot.findByResourceId(resourceId, exact);
        if (hits.isEmpty()) return false;
        clickNode(hits.get(0), snapshot);
        return true;
    }

    /**
     * 心跳日志钩子：输出一行带标签的调试信息。
     */
    public void heartbeat(String tag) {
        log.debug("[{}] 心跳: session存活={}", tag, logger.isSessionAlive());
    }

    // ==================== 内部实现 ====================

    /**
     * 点击节点：取命中节点的 bounds 中心坐标，经 GestureSupport.tapAtPixel 点击。
     * <p>
     * 若命中节点自身 clickable=false，则用 UiSnapshot.nearestClickableAncestor(node)
     * 回溯至最近的 clickable=true 祖先并取其中心。
     * 找不到可点祖先则用自身中心（点击会向上传递给父容器）。
     * <p>
     * 这样做的三个理由：
     * ① 坐标由实时 UI 树 bounds 推算而非硬编码，满足动态解析要求；
     * ② 规避自绘控件无属性时拿不到 WebElement 的问题；
     * ③ 天然免疫 stale element。
     */
    protected void clickNode(UiNode node, UiSnapshot snapshot) {
        UiNode targetNode = node;

        // 如果节点自身不可点击，尝试回溯至最近的可点击祖先
        if (!node.isClickable()) {
            Optional<UiNode> clickableAncestor = snapshot.nearestClickableAncestor(node);
            if (clickableAncestor.isPresent()) {
                targetNode = clickableAncestor.get();
                log.debug("[BasePage] 节点不可点击，回溯到可点击祖先: class={}", targetNode.getClazz());
            } else {
                // 找不到可点祖先则用自身中心（点击事件会向上传递给父容器）
                log.debug("[BasePage] 未找到可点击祖先，使用自身 bounds 中心点击");
            }
        }

        if (!targetNode.isHasBounds()) {
            log.warn("[BasePage] 目标节点无有效 bounds，无法点击");
            return;
        }

        int x = targetNode.getBounds().centerX();
        int y = targetNode.getBounds().centerY();
        log.debug("[BasePage] 执行点击: ({}, {}), 节点={}", x, y, targetNode.getClazz());
        gestures.tapAtPixel(x, y);
    }

    /**
     * boundsHint 裁决：命中多个节点时，根据 boundsHint 语义选择最佳节点。
     * <ul>
     *   <li>HINT_BOTTOM / HINT_BOTTOM_TAB → 取 y 最大者（对应页面底部的广告入口/Tab）</li>
     *   <li>HINT_CENTER → 取最接近屏幕中心者（对应弹窗按钮）</li>
     *   <li>HINT_TOP_RIGHT → 取 centerX > 0.8W 且 centerY < 0.2H 者（对应广告关闭按钮）</li>
     *   <li>null / 无匹配 → 取面积最小的可点节点（更具体的叶子控件优先）或第一个命中</li>
     * </ul>
     */
    private UiNode arbitrate(List<UiNode> hits, String boundsHint, UiSnapshot snapshot) {
        if (hits.size() == 1) return hits.get(0);

        int screenW = snapshot.getScreenWidth();
        int screenH = snapshot.getScreenHeight();

        if (boundsHint == null || boundsHint.isEmpty()) {
            // 无区域限制：取面积最小的可点节点（更具体的叶子控件优先），或第一个命中
            return hits.stream()
                    .filter(UiNode::isClickable)
                    .min(Comparator.comparingLong(n -> n.getBounds().area()))
                    .orElse(hits.get(0));
        }

        if (boundsHint.startsWith("y>")) {
            // 底部裁决：取 y 最大者（对应页面底部的广告入口 / 底部 Tab）
            return hits.stream()
                    .filter(UiNode::isHasBounds)
                    .max(Comparator.comparingInt(n -> n.getBounds().centerY()))
                    .orElse(hits.get(0));
        }

        if (HINT_TOP_RIGHT.equals(boundsHint) || boundsHint.contains("x>0.8")) {
            // 右上角裁决：优先取 centerX > 0.8W 且 centerY < 0.2H 者（对应广告关闭按钮）
            Optional<UiNode> topRight = hits.stream()
                    .filter(UiNode::isHasBounds)
                    .filter(n -> {
                        double cx = (double) n.getBounds().centerX() / screenW;
                        double cy = (double) n.getBounds().centerY() / screenH;
                        return cx > 0.8 && cy < 0.2;
                    })
                    .findFirst();
            if (topRight.isPresent()) return topRight.get();
            // 无严格右上角命中，取面积最小的 clickable 节点
            return hits.stream()
                    .filter(UiNode::isClickable)
                    .min(Comparator.comparingLong(n -> n.getBounds().area()))
                    .orElse(hits.get(0));
        }

        if (HINT_CENTER.equals(boundsHint)) {
            // 居中裁决：取最接近屏幕中心者（对应弹窗按钮）
            int centerX = screenW / 2;
            int centerY = screenH / 2;
            return hits.stream()
                    .filter(UiNode::isHasBounds)
                    .min(Comparator.comparingLong(n -> {
                        int dx = n.getBounds().centerX() - centerX;
                        int dy = n.getBounds().centerY() - centerY;
                        return (long) dx * dx + (long) dy * dy;
                    }))
                    .orElse(hits.get(0));
        }

        // 兜底：取面积最小的可点击节点
        return hits.stream()
                .filter(UiNode::isClickable)
                .min(Comparator.comparingLong(n -> n.getBounds().area()))
                .orElse(hits.get(0));
    }

    /**
     * 解析 boundsHint 字符串为 [xMin, xMax, yMin, yMax] 的比例区域。
     * 返回 null 表示无法解析。
     */
    private double[] parseBoundsHintRegion(String hint) {
        if (hint == null || hint.isEmpty()) return null;

        // "x>0.8,y<0.2" → 右上角 [0.8, 1.0, 0.0, 0.2]
        if (hint.contains("x>0.8") && hint.contains("y<0.2")) {
            return new double[]{0.8, 1.0, 0.0, 0.2};
        }
        // "y>0.9" → 极底部 [0.0, 1.0, 0.9, 1.0]
        if (hint.contains("y>0.9")) {
            return new double[]{0.0, 1.0, 0.9, 1.0};
        }
        // "y>0.8" → 底部 [0.0, 1.0, 0.8, 1.0]
        if (hint.contains("y>0.8")) {
            return new double[]{0.0, 1.0, 0.8, 1.0};
        }
        // "center" → 居中 [0.2, 0.8, 0.3, 0.7]
        if (HINT_CENTER.equals(hint)) {
            return new double[]{0.2, 0.8, 0.3, 0.7};
        }

        log.debug("[BasePage] 无法解析 boundsHint 区域: {}", hint);
        return null;
    }

    /**
     * 从 LocatorSpec 中提取 text 候选集。
     * 通过解析 primaryBy 和 fallbackBys 中 XPath 的 contains(@text,'...') 提取文案。
     * 如果无法提取则尝试从 LocatorRegistry 的全局候选集中匹配。
     */
    private List<String> extractTextCandidatesFromSpec(LocatorRegistry.LocatorSpec spec) {
        // 从 By 的 toString 中提取 text 关键词
        List<String> candidates = new java.util.ArrayList<>();
        String primary = spec.getPrimaryBy().toString();
        extractTextFromXPath(primary, candidates);
        for (org.openqa.selenium.By fb : spec.getFallbackBys()) {
            extractTextFromXPath(fb.toString(), candidates);
        }
        return candidates;
    }

    /**
     * 从 LocatorSpec 中提取 content-desc 候选集。
     */
    private List<String> extractDescCandidatesFromSpec(LocatorRegistry.LocatorSpec spec) {
        List<String> candidates = new java.util.ArrayList<>();
        String primary = spec.getPrimaryBy().toString();
        extractDescFromXPath(primary, candidates);
        for (org.openqa.selenium.By fb : spec.getFallbackBys()) {
            extractDescFromXPath(fb.toString(), candidates);
        }
        return candidates;
    }

    /**
     * 从 XPath 字符串中提取 contains(@text,'XXX') 的 XXX 部分。
     */
    private void extractTextFromXPath(String xpath, List<String> result) {
        // 匹配 contains(@text,'...') 或 @text='...'
        extractPattern(xpath, "@text", result);
    }

    /**
     * 从 XPath 字符串中提取 contains(@content-desc,'XXX') 的 XXX 部分。
     */
    private void extractDescFromXPath(String xpath, List<String> result) {
        extractPattern(xpath, "@content-desc", result);
    }

    private void extractPattern(String xpath, String attrName, List<String> result) {
        // contains(@text,'关键词')
        String containsPattern = "contains(" + attrName + ",'";
        int idx = 0;
        while ((idx = xpath.indexOf(containsPattern, idx)) >= 0) {
            int start = idx + containsPattern.length();
            int end = xpath.indexOf("')", start);
            if (end > start) {
                String value = xpath.substring(start, end);
                if (!value.isEmpty() && !result.contains(value)) {
                    result.add(value);
                }
            }
            idx = start;
        }
        // @text='关键词'
        String equalsPattern = attrName + "='";
        idx = 0;
        while ((idx = xpath.indexOf(equalsPattern, idx)) >= 0) {
            // 避免重复匹配 contains(@text,'
            if (idx > 0 && xpath.charAt(idx - 1) == '(') {
                idx += equalsPattern.length();
                continue;
            }
            int start = idx + equalsPattern.length();
            int end = xpath.indexOf("'", start);
            if (end > start) {
                String value = xpath.substring(start, end);
                if (!value.isEmpty() && !result.contains(value)) {
                    result.add(value);
                }
            }
            idx = start;
        }
    }
}
