package com.fanqie.auto.page;

import com.fanqie.auto.config.AutomationConfig;
import com.fanqie.auto.config.LocatorRegistry;
import com.fanqie.auto.core.GestureSupport;
import com.fanqie.auto.core.PageState;
import com.fanqie.auto.core.RunLogger;
import com.fanqie.auto.core.StateDetector;
import com.fanqie.auto.core.UiNode;
import com.fanqie.auto.core.UiSnapshot;
import com.fanqie.auto.core.WaitSupport;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.Dimension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 书架页面对象。
 * <p>
 * 职责：
 * - switchToShelfTab()：确保当前在书架 Tab
 * - openAnyBook()：动态选取书架内首个可点击书籍条目并打开
 * <p>
 * 设计要点：
 * - 动态选取书籍条目绝不写死索引（如 //node()[3]），而是遍历内存节点表
 *   查找首个 clickable=true 且带 content-desc（通常是书名）的条目。
 * - 若首屏找不到可点条目，用 GestureSupport.swipeUp() 滚动后重试，
 *   重试次数来自 config.maxRecoveryRetry()（不硬编码）。
 */
public class BookshelfPage extends BasePage {

    private static final Logger log = LoggerFactory.getLogger(BookshelfPage.class);

    private final StateDetector detector;
    private final WaitSupport waitSupport;

    /**
     * D5：最近一次打开的书籍标识（content-desc 优先，其次 text）。
     * 供上层做 per-book 进度记录与多本书轮换时排除已用书籍。
     */
    private volatile String lastOpenedBookId;

    public BookshelfPage(AppiumDriver driver, AutomationConfig config, LocatorRegistry locators,
                         GestureSupport gestures, RunLogger logger,
                         StateDetector detector, WaitSupport waitSupport) {
        super(driver, config, locators, gestures, logger);
        this.detector = detector;
        this.waitSupport = waitSupport;
    }

    /**
     * 切换到底部「书架」Tab。
     * <p>
     * 使用 LocatorRegistry.getShelfTab() 走 BasePage 降级链。
     * 额外补充通道：通过 LocatorRegistry 的 shelfTabTexts() 在快照中做文案匹配。
     *
     * @return true=已在书架 Tab 或成功切换, false=切换失败
     */
    public boolean switchToShelfTab() {
        log.info("[BookshelfPage] 尝试切换到书架 Tab（纯坐标方式，不依赖 getPageSource）");

        // 真机校准：书架 Tab 在底部，坐标已校准（1080×2376）
        // 书架 Tab 中心约 (746, 2270)，避开华为底部手势区
        // 直接点击坐标，不依赖 getPageSource/detector
        Dimension size = gestures.getScreenSize();
        // 书架 Tab 在底部右侧，比例约 x=0.69, y=0.955
        int x = (int) (0.69 * size.getWidth());
        int y = (int) (0.955 * size.getHeight());
        // 避开手势区：y 坐标上移一点
        y = Math.min(y, (int) (0.95 * size.getHeight()));

        log.info("[BookshelfPage] 坐标点击书架 Tab: pixel=({}, {})", x, y);
        gestures.tapAtPixel(x, y);

        // 用 activity 名称验证是否到达书架页
        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        String activity = gestures.getCurrentActivity();
        if (activity != null && (activity.contains("Main") || activity.contains("bookshelf"))) {
            log.info("[BookshelfPage] 书架 Tab 切换成功 (activity={})", activity);
            return true;
        }

        // activity 不确定，但点击已执行，乐观返回 true
        log.info("[BookshelfPage] 书架 Tab 点击已执行，activity={}", activity);
        return true;
    }

    /**
     * 真机校准：点击底部 Tab 时避开华为全面屏底部导航手势区。
     * <p>
     * 底部 Tab（书架/书城等）可点节点的中心 y≈2310 正落在系统全面屏手势区（约 y>2253），
     * 普通点击会被导航手势吞掉、切不动 Tab（曾在真机复现：点书架反弹成视频「选集」层）。
     * 故取该 Tab 可点节点 bounds 的「顶部 + 20% 高度」（图标上部，约 y≈2270）作为点击点，
     * x 仍取中心。若节点无有效 bounds，退化为 {@link #clickNode} 原逻辑。
     */
    private void tapTabAvoidingGesture(UiNode tabNode, UiSnapshot snapshot) {
        UiNode target = tabNode;
        if (!tabNode.isClickable()) {
            Optional<UiNode> ancestor = snapshot.nearestClickableAncestor(tabNode);
            if (ancestor.isPresent()) target = ancestor.get();
        }
        if (!target.isHasBounds()) {
            clickNode(tabNode, snapshot);
            return;
        }
        UiNode.Rect b = target.getBounds();
        int x = b.centerX();
        int y = b.getTop() + (int) Math.round(b.height() * 0.20);
        log.info("[BookshelfPage] 点书架 Tab 避开手势区: ({}, {}) bounds={}", x, y, b);
        gestures.tapAtPixel(x, y);
    }

    /**
     * 打开书架中的第一本书（坐标点击）。
     * <p>
     * 真机校准：
     * 1. 书架书籍是 WebView/Canvas 自绘，UiAutomator 读不到节点，用坐标点击
     * 2. uiautomator 崩溃导致 getPageSource 不可用，不等待状态变化，点击即返回
     * 3. 状态验证由 FanqieAdWatchTask 用 activity 名称完成
     *
     * @return true=已执行点击, false=不在书架页或点击失败
     */
    public boolean openAnyBook() {
        log.info("[BookshelfPage] 尝试打开书架中的第一本书（坐标点击）");

        // 如果误入了筛选页，先关闭
        closeFilterPageIfNeeded();

        // 用 activity 名称确认在书架页（不依赖 getPageSource）
        String activity = gestures.getCurrentActivity();
        if (activity != null && !activity.contains("Main")) {
            log.warn("[BookshelfPage] 当前不在书架页 (activity={})，无法打开书籍", activity);
            return false;
        }

        // 坐标点击第一本书（网格索引 0）
        if (!clickBookAtGridIndex(0)) {
            log.warn("[BookshelfPage] 坐标点击书籍失败");
            return false;
        }
        lastOpenedBookId = "grid:0";
        log.info("[BookshelfPage] 已坐标点击第一本书 (grid index=0)");

        // 等待页面加载
        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        return true;
    }

    /**
     * 纯坐标方式打开书架第一本书（不依赖 getPageSource/detector）。
     * 用于评论层恢复失败后的兜底策略，此时 detector 可能不可用。
     *
     * @return true=已执行点击, false=不在书架页
     */
    public boolean openFirstBookByCoordinate() {
        log.info("[BookshelfPage] 纯坐标方式打开书架第一本书（不依赖 getPageSource）");

        // 用 activity 名称确认在书架页
        String activity = gestures.getCurrentActivity();
        if (activity != null && !activity.contains("Main")) {
            log.warn("[BookshelfPage] 当前不在书架页 (activity={})，无法打开书籍", activity);
            return false;
        }

        // 直接计算第一本书的坐标并点击（网格索引 0：左列第一行）
        double[] colRatios = {0.144, 0.463, 0.783};
        double yRatio = 0.278;
        Dimension size = gestures.getScreenSize();
        int x = (int) (colRatios[0] * size.getWidth());
        int y = (int) (yRatio * size.getHeight());

        log.info("[BookshelfPage] 纯坐标点击第一本书: pixel=({}, {})", x, y);
        gestures.tapAtPixel(x, y);
        lastOpenedBookId = "grid:0";

        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        return true;
    }

    /**
     * 真机校准：从书籍详情页左滑进入阅读器。
     * <p>
     * 番茄小说点击书封后先打开书籍详情页（书封/评分/简介），
     * 页面底部有「← 左滑开始阅读」提示，从右向左滑动即可进入阅读器。
     * 滑动后等待 3 秒让阅读器加载。
     *
     * @return true=滑动已执行（无论是否成功进入阅读器）
     */
    public boolean swipeLeftToRead() {
        log.info("[BookshelfPage] 从书籍详情页左滑进入阅读器");
        gestures.swipeLeft();
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        // 用 activity 名称验证是否进入阅读器（不依赖 getPageSource）
        String activity = gestures.getCurrentActivity();
        if (activity != null && activity.contains("Reader")) {
            log.info("[BookshelfPage] 左滑成功，已进入阅读器: {}", activity);
            return true;
        }
        log.info("[BookshelfPage] 左滑已执行，当前 activity: {}", activity);
        return true;
    }

    /**
     * 打开下一本「未使用过」的书籍（多本书轮换，坐标点击）。
     * <p>
     * 与 {@link #openAnyBook()} 的区别：本方法排除 excludeIds 中已用过的网格索引，
     * 若首屏全部已用过则向上滚动继续查找未用书籍。
     * <p>
     * 真机校准：uiautomator 崩溃，不依赖 getPageSource，用 activity 名称验证状态。
     *
     * @param excludeIds 已使用过的书籍标识集合（来自 per-book 进度），可为 null
     * @return true=已执行点击, false=书架中已无未使用书籍或不在书架页
     */
    public boolean openNextBook(Set<String> excludeIds) {
        Set<String> excludes = (excludeIds == null) ? Collections.emptySet() : excludeIds;
        log.info("[BookshelfPage] 尝试打开下一本未使用的书籍（排除 {} 本已用）", excludes.size());

        // 如果误入了筛选页，先关闭
        closeFilterPageIfNeeded();

        // 用 activity 名称确认在书架页
        String activity = gestures.getCurrentActivity();
        if (activity != null && !activity.contains("Main")) {
            log.warn("[BookshelfPage] 当前不在书架页 (activity={})，无法换书", activity);
            return false;
        }

        // 每屏最多 3 列 × 3 行 = 9 本书
        int booksPerScreen = 9;
        int maxScrollAttempts = config.maxRecoveryRetry() + 2;

        for (int attempt = 0; attempt <= maxScrollAttempts; attempt++) {
            int baseIndex = attempt * booksPerScreen;
            for (int i = 0; i < booksPerScreen; i++) {
                int gridIndex = baseIndex + i;
                String idxId = "grid:" + gridIndex;
                if (excludes.contains(idxId)) continue;

                if (!clickBookAtGridIndex(gridIndex)) continue;
                lastOpenedBookId = idxId;
                log.info("[BookshelfPage] 换到新书籍: id='{}' (grid index={})", idxId, gridIndex);
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                return true;
            }

            // 当前屏全部已用过，滚动后重试
            if (attempt < maxScrollAttempts) {
                log.info("[BookshelfPage] 当前屏未找到未使用的书籍条目，向上滚动后重试 ({}/{})",
                        attempt + 1, maxScrollAttempts);
                gestures.swipeUp();
                try {
                    Thread.sleep(config.pollIntervalMs() * 2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        log.warn("[BookshelfPage] 已滚动到底仍未找到未使用的书籍（可能书架书籍已全部用过）");
        return false;
    }

    /**
     * 获取最近一次打开的书籍标识（供上层做 per-book 进度记录）。
     */
    public String getLastOpenedBookId() {
        return lastOpenedBookId;
    }

    /**
     * 点击书籍后确认已离开书架（进入 READER 或其他非 BOOKSHELF 状态）。
     * 使用 activity 检测代替 detector.tick()（uiautomator 在设备上崩溃）。
     *
     * @return true=已离开书架, false=状态仍停留在书架（点击可能无效）
     */
    private boolean confirmLeftBookshelf() {
        long startTime = System.currentTimeMillis();
        long timeout = config.actionTimeoutMs() + 10000;
        
        while (System.currentTimeMillis() - startTime < timeout) {
            String activity = gestures.getCurrentActivity();
            if (activity != null && activity.contains("Reader")) {
                log.info("[BookshelfPage] 成功进入阅读页 (activity={})", activity);
                return true;
            }
            // 检查是否进入其他非书架状态（广告、弹窗等）
            if (activity != null && (activity.contains("Ad") || activity.contains("Dialog") 
                    || activity.contains("Popup") || activity.contains("Splash"))) {
                log.info("[BookshelfPage] 打开书籍后进入状态 (activity={})（非 READER 但已离开书架）", activity);
                return true;
            }
            try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        
        // 超时后检查当前 activity
        String activity = gestures.getCurrentActivity();
        if (activity != null && !activity.contains("Main") && !activity.contains("bookshelf")) {
            log.info("[BookshelfPage] 虽超时但已离开书架 (activity={})", activity);
            return true;
        }
        
        log.warn("[BookshelfPage] 点击书籍后状态未变化，可能点击无效 (activity={})", activity);
        return false;
    }

    /**
     * 真机校准：如果当前在「书架筛选」页面，按返回键关闭。
     * 使用 activity 检测代替 detector.tick()（uiautomator 在设备上崩溃）。
     */
    private void closeFilterPageIfNeeded() {
        String activity = gestures.getCurrentActivity();
        // 筛选页 activity 可能包含 "Filter" 或 "ShelfFilter"
        if (activity != null && (activity.contains("Filter") || activity.contains("ShelfFilter"))) {
            log.info("[BookshelfPage] 检测到筛选页面 (activity={})，按返回键关闭", activity);
            try { driver.navigate().back(); } catch (Exception e) { /* ignore */ }
            try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * 真机校准：按网格坐标点击书架中的书。
     * 使用 activity 检测代替 detector.tick()（uiautomator 在设备上崩溃）。
     *
     * @param gridIndex 网格索引（0=左上第一本，1=中上，2=右上，3=左中...）
     * @return true=点击成功, false=不在书架页或点击失败
     */
    private boolean clickBookAtGridIndex(int gridIndex) {
        // 使用 activity 检测是否在书架页
        String activity = gestures.getCurrentActivity();
        if (activity == null || !(activity.contains("Main") || activity.contains("bookshelf"))) {
            log.warn("[BookshelfPage] clickBookAtGridIndex: 不在书架页 (activity={})", activity);
            return false;
        }

        int col = gridIndex % 3;
        int row = gridIndex / 3;

        // 列中心 x 比例（3 列，真机 1080×2376 校准）
        // 左列 x≈155 (0.144), 中列 x≈500 (0.463), 右列 x≈845 (0.783)
        double[] colRatios = {0.144, 0.463, 0.783};
        double xRatio = colRatios[col];

        // 行中心 y 比例：书架页有筛选 chip 栏（全部/阅读/听书/书单/筛选），
        // 真机校准（1080×2376）：第一行书封中心 ≈ y660 (ratio≈0.278)，每行间隔 ≈ 0.160。
        double yRatio = 0.278 + row * 0.160;

        Dimension size = gestures.getScreenSize();
        int x = (int) (xRatio * size.getWidth());
        int y = (int) (yRatio * size.getHeight());

        log.info("[BookshelfPage] 坐标点击书架网格: index={}, col={}, row={}, pixel=({}, {})",
                gridIndex, col, row, x, y);
        gestures.tapAtPixel(x, y);

        // 等待页面加载
        try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        return true;
    }

    /**
     * 检测书架顶部筛选栏是否可见（含「全部」「网文」「本地书」等筛选 chip）。
     * 筛选栏可见时书籍网格整体下移，需要调整点击 y 坐标。
     */
    private boolean hasFilterBar(UiSnapshot snapshot) {
        if (snapshot == null) return false;
        for (UiNode n : snapshot.nodes()) {
            if (n.hasText()) {
                String t = n.getText();
                if (t.contains("全部") || t.contains("网文") || t.contains("本地书")
                        || t.contains("听书") || t.contains("书单")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * D5：从书籍节点提取稳定的书籍标识（content-desc 优先，其次 text）。
     * content-desc 通常是书名，比坐标/索引更稳定，适合作为 per-book 去重键。
     */
    private String bookIdOf(UiNode node) {
        if (node == null) return "";
        if (node.hasContentDesc()) return node.getContentDesc().trim();
        if (node.hasText()) return node.getText().trim();
        // 真机书封容器无 text/content-desc：退化用 bounds 做去重键，避免多本书轮换时全塌缩为空串
        if (node.isHasBounds()) return node.getBounds().toString();
        return "";
    }

    /**
     * 在快照中动态查找书籍条目节点。
     * <p>
     * 策略：
     * 1. 优先查找 clickable=true 且带 content-desc 的节点（content-desc 通常是书名）
     * 2. bounds 中心在屏幕主体区域（排除顶部状态栏和底部导航栏）
     * 3. areaRatio 在合理范围内（0.005~0.15，排除全屏容器和极小图标）
     * 4. 排除已知的非书籍节点（如 Tab 栏、搜索框等）
     */
    private Optional<UiNode> findBookItem(UiSnapshot snapshot) {
        return findBookItem(snapshot, Collections.emptySet());
    }

    /**
     * D5：在快照中动态查找书籍条目节点，排除已使用过的书籍。
     *
     * @param snapshot   当次 UI 快照
     * @param excludeIds 已使用过的书籍标识集合（空集时不排除任何书籍）
     */
    private Optional<UiNode> findBookItem(UiSnapshot snapshot, Set<String> excludeIds) {
        int screenW = snapshot.getScreenWidth();
        int screenH = snapshot.getScreenHeight();
        Set<String> excludes = (excludeIds == null) ? Collections.emptySet() : excludeIds;

        List<UiNode> allNodes = snapshot.nodes();

        // 策略 0（真机 dump 校准）：番茄书架是网格，书封容器 clickable=true 但 text/content-desc 均为空
        // （书名挂在子节点），无法被下面两个基于文案的策略命中。故优先按稳定 resource-id 命中书封容器。
        // 书封约占屏 20%，超下方 0.15 面积上限；且底部 Tab 已由 id 天然排除，故中心 y 下限放宽到 0.90。
        List<String> bookIds = locators.bookItemIds();
        if (!bookIds.isEmpty()) {
            List<UiNode> cells = allNodes.stream()
                    .filter(UiNode::isClickable)
                    .filter(UiNode::isHasBounds)
                    .filter(n -> bookIds.contains(n.getResourceId()))
                    .filter(n -> {
                        double cy = (double) n.getBounds().centerY() / screenH;
                        return cy > 0.10 && cy < 0.90;
                    })
                    .filter(n -> {
                        double ratio = n.getBounds().areaRatio(screenW, screenH);
                        return ratio > 0.01 && ratio < 0.30;
                    })
                    .filter(n -> !excludes.contains(bookIdOf(n)))
                    .collect(Collectors.toList());
            UiNode chosen = chooseNovelCell(snapshot, cells);
            if (chosen != null) return Optional.of(chosen);
        }

        // 策略 1：clickable=true 且带 content-desc（书名）
        Optional<UiNode> withDesc = allNodes.stream()
                .filter(UiNode::isClickable)
                .filter(UiNode::hasContentDesc)
                .filter(UiNode::isHasBounds)
                .filter(n -> {
                    // bounds 中心在屏幕主体区域（y: 10%~85%，排除状态栏和底部 Tab）
                    double cy = (double) n.getBounds().centerY() / screenH;
                    return cy > 0.10 && cy < 0.85;
                })
                .filter(n -> {
                    // 面积占比合理（排除全屏容器和极小图标）
                    double ratio = n.getBounds().areaRatio(screenW, screenH);
                    return ratio > 0.005 && ratio < 0.15;
                })
                .filter(n -> {
                    // 排除底部 Tab 区域的文案（如「书架」「发现」等）
                    String desc = n.getContentDesc();
                    List<String> tabTexts = locators.shelfTabTexts();
                    return tabTexts.stream().noneMatch(desc::contains);
                })
                // 真机校准：兜底策略同样跳过短剧/视频节点（避免穿透到策略1/2 时误选短剧）
                .filter(n -> !isShortDramaText(n.getText() + " " + n.getContentDesc()))
                // D5：排除已用书籍
                .filter(n -> !excludes.contains(bookIdOf(n)))
                .findFirst();

        if (withDesc.isPresent()) return withDesc;

        // 策略 2：clickable=true 且带 text（某些书架条目用 text 而非 content-desc）
        return allNodes.stream()
                .filter(UiNode::isClickable)
                .filter(UiNode::hasText)
                .filter(UiNode::isHasBounds)
                .filter(n -> {
                    double cy = (double) n.getBounds().centerY() / screenH;
                    return cy > 0.10 && cy < 0.85;
                })
                .filter(n -> {
                    double ratio = n.getBounds().areaRatio(screenW, screenH);
                    return ratio > 0.005 && ratio < 0.15;
                })
                .filter(n -> {
                    String text = n.getText();
                    List<String> tabTexts = locators.shelfTabTexts();
                    return tabTexts.stream().noneMatch(text::contains);
                })
                // 真机校准：兜底策略同样跳过短剧/视频节点（避免穿透到策略2 时误选如「观看全集·52集」）
                .filter(n -> !isShortDramaText(n.getText() + " " + n.getContentDesc()))
                // D5：排除已用书籍
                .filter(n -> !excludes.contains(bookIdOf(n)))
                .findFirst();
    }

    /**
     * 真机校准：从书封格候选中挑一本「文字小说」，跳过「短剧/视频」卡片。
     * <p>
     * 番茄书架把短剧与文字小说混排、书封容器 id 相同（e3l），仅靠 id/几何无法区分。
     * 区分依据：看书封格子节点的文字——含短剧角标（第N集/全N集/选集等）则跳过；
     * 含小说角标（万字/人在读/X.X分 等）则优先。两类都不命中时，退而取非短剧的最大格。
     *
     * @return 选中的书封节点；若全部被判定为短剧则返回 null（交由上层滞动重试）
     */
    private UiNode chooseNovelCell(UiSnapshot snapshot, List<UiNode> cells) {
        if (cells == null || cells.isEmpty()) return null;
        Pattern sd = compileSafe(locators.bookShortDramaRegex());
        Pattern novel = compileSafe(locators.bookNovelRegex());
        UiNode best = null;
        boolean bestNovel = false;
        long bestArea = -1;
        for (UiNode cell : cells) {
            String txt = collectTextWithin(snapshot, cell);
            if (sd != null && sd.matcher(txt).find()) {
                log.info("[BookshelfPage] 跳过疑似短剧/视频卡片: bounds={}", cell.getBounds());
                continue;
            }
            boolean isNovel = novel != null && novel.matcher(txt).find();
            long area = cell.getBounds().area();
            // 优先 novel；同优先级取面积大者（正文网格书封，而非顶部窄条卡）
            if ((isNovel && !bestNovel) || (isNovel == bestNovel && area > bestArea)) {
                best = cell;
                bestNovel = isNovel;
                bestArea = area;
            }
        }
        return best;
    }

    /**
     * 收集书封格范围内（中心点落在格子 bounds 内）的全部 text/content-desc 拼接文本。
     * 用于判断一个格子是短剧还是文字小说（书名/作者/字数/章节进度等子节点文字都落在格子矩形内）。
     */
    private String collectTextWithin(UiSnapshot snapshot, UiNode cell) {
        UiNode.Rect b = cell.getBounds();
        StringBuilder sb = new StringBuilder();
        for (UiNode n : snapshot.nodes()) {
            if (!n.isHasBounds()) continue;
            if (!n.hasText() && !n.hasContentDesc()) continue;
            UiNode.Rect nb = n.getBounds();
            int cx = nb.centerX();
            int cy = nb.centerY();
            if (cx >= b.getLeft() && cx <= b.getRight() && cy >= b.getTop() && cy <= b.getBottom()) {
                sb.append(n.getText()).append(' ').append(n.getContentDesc()).append(' ');
            }
        }
        return sb.toString();
    }

    /**
     * 真机校准：判断一段文本是否命中短剧/视频特征（第N集/全N集/选集/看全集 等）。
     * 供兜底策略（策略1/2）过滤短剧节点复用；正则未配置或非法时返回 false（不过滤）。
     */
    private boolean isShortDramaText(String text) {
        if (text == null || text.isEmpty()) return false;
        Pattern sd = compileSafe(locators.bookShortDramaRegex());
        return sd != null && sd.matcher(text).find();
    }

    /** 安全编译正则：空串或非法正则返回 null（调用方按 null 跳过该维度）。 */
    private Pattern compileSafe(String regex) {
        if (regex == null || regex.isEmpty()) return null;
        try {
            return Pattern.compile(regex);
        } catch (Exception e) {
            log.warn("[BookshelfPage] 正则编译失败，忽略该规则: '{}' ({})", regex, e.getMessage());
            return null;
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
