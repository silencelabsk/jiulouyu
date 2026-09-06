package com.fanqie.auto;

import com.fanqie.auto.config.AutomationConfig;
import com.fanqie.auto.config.LocatorRegistry;
import com.fanqie.auto.core.DriverAware;
import com.fanqie.auto.core.GestureSupport;
import com.fanqie.auto.core.PageState;
import com.fanqie.auto.core.RunLogger;
import com.fanqie.auto.core.StateDetector;
import com.fanqie.auto.core.UiNode;
import com.fanqie.auto.core.UiSnapshot;
import com.fanqie.auto.core.WaitSupport;
import io.appium.java_client.AppiumDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 安全探针：只连接 Appium、启动 App、在书架页与阅读页各抓一次 UiSnapshot 并落盘。
 * <p>
 * 这是闸门 B 在代码侧的复核工具，用于判定是否需要启用 L2/L3 兜底。
 * <p>
 * <b>安全约束</b>：
 * - 从书架导航到阅读页的过程中只允许切 Tab 与点开一本书这两类无害操作
 * - <b>绝不点击任何广告/观看视频/领取时长按钮</b>（会消耗真实每日配额并播放视频）
 * - 绝不执行 uninstall / clearApp / reset 等破坏性操作
 * <p>
 * 输出内容：
 * - 节点属性统计：总节点数、有 text/resource-id/content-desc/clickable 的数量及占比
 * - 所有含关键词（视频/广告/时长/关闭/继续/免费/书架/下一章）的节点及其完整属性与 bounds
 * - 结论建议：哪些控件可直接用属性定位、哪些必须靠 L2 几何兜底
 */
public class DryRunProbe implements DriverAware {

    private static final Logger log = LoggerFactory.getLogger(DryRunProbe.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 探测关键词列表（不硬编码业务文案，这里只用于 dry-run 探测输出） */
    private static final List<String> PROBE_KEYWORDS = Arrays.asList(
            "视频", "广告", "时长", "关闭", "继续", "免费", "书架", "下一章",
            "观看", "领取", "跳过", "分钟", "免广告"
    );

    private volatile AppiumDriver driver;
    private final AutomationConfig config;
    private final LocatorRegistry locators;
    private final StateDetector detector;
    private final WaitSupport waitSupport;
    private final GestureSupport gestures;
    private final RunLogger logger;
    private final Path snapshotsDir;

    public DryRunProbe(AppiumDriver driver, AutomationConfig config, LocatorRegistry locators,
                       StateDetector detector, WaitSupport waitSupport, GestureSupport gestures,
                       RunLogger logger) {
        this.driver = driver;
        this.config = config;
        this.locators = locators;
        this.detector = detector;
        this.waitSupport = waitSupport;
        this.gestures = gestures;
        this.logger = logger;

        String baseDir = System.getProperty("automation.logs.dir", "automation/logs");
        this.snapshotsDir = Path.of(baseDir, "snapshots");
        try {
            Files.createDirectories(snapshotsDir);
        } catch (IOException e) {
            log.warn("[DryRunProbe] 无法创建快照目录: {}", e.getMessage());
        }
    }

    @Override
    public void refreshDriver(AppiumDriver newDriver) {
        if (newDriver != null) this.driver = newDriver;
    }

    /**
     * 执行探测流程。
     *
     * @return true=探测完成, false=探测过程中遇到阻塞性错误
     */
    public boolean probe() {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  DryRunProbe：安全探测模式（不点击任何广告/领时长按钮）       ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");

        // === 步骤 1：启动 App ===
        log.info("[DryRun] 步骤 1：激活 App");
        try {
            gestures.activateApp(config.appPackage());
        } catch (Exception e) {
            log.error("[DryRun] 激活 App 失败: {}", e.getMessage());
            return false;
        }

        // 等待 App 启动
        try {
            PageState launchState = waitSupport.untilState(config.appLaunchTimeoutMs(),
                    PageState.BOOKSHELF, PageState.APP_LAUNCHING, PageState.SPLASH_AD,
                    PageState.COMMON_POPUP);
            log.info("[DryRun] App 启动后状态: {}", launchState);
        } catch (Exception e) {
            log.warn("[DryRun] 等待 App 启动超时: {}", e.getMessage());
        }

        // === 步骤 2：在书架页抓取快照 ===
        log.info("[DryRun] 步骤 2：抓取书架页 UI 快照");
        UiSnapshot shelfSnapshot = detector.tick();
        PageState shelfState = detector.detect(shelfSnapshot);
        log.info("[DryRun] 书架页状态: {}, 节点数: {}", shelfState, shelfSnapshot.size());

        // XML 落盘
        saveSnapshot(shelfSnapshot, "dryrun-bookshelf");
        // 打印统计
        printNodeStatistics(shelfSnapshot, "书架页");
        // 打印关键词节点
        printKeywordNodes(shelfSnapshot, "书架页");

        // === 步骤 3：打开一本书进入阅读页 ===
        log.info("[DryRun] 步骤 3：尝试打开一本书（仅允许切 Tab 与点开书籍两类无害操作）");

        // 确保真正落在书架页：BOOKSHELF 检测依赖底部「书架」Tab 文案，书城/短剧等主页同样含该文案，
        // 冷启可能停在书城/短剧却被误判为书架。故无条件点一次底部「书架」Tab（已在书架时为幂等空操作）。
        List<String> shelfTexts = locators.shelfTabTexts();
        List<UiNode> tabHits = shelfSnapshot.findByTextContains(shelfTexts);
        UiNode shelfTab = tabHits.stream()
                .filter(UiNode::isHasBounds)
                .max(java.util.Comparator.comparingInt(n -> n.getBounds().centerY()))
                .orElse(null);
        if (shelfTab != null) {
            gestures.tapAtPixel(shelfTab.getBounds().centerX(), shelfTab.getBounds().centerY());
            log.info("[DryRun] 已无条件点击底部书架 Tab: bounds={}", shelfTab.getBounds());
            try {
                Thread.sleep(config.pollIntervalMs() * 4);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        // 在书架中找一个可点击的书籍条目（无害操作）
        UiSnapshot currentSnapshot = detector.tick();
        boolean openedBook = openFirstBook(currentSnapshot);

        if (openedBook) {
            // 等待进入阅读页
            try {
                Thread.sleep(config.pollIntervalMs() * 8);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            // === 步骤 4：在阅读页抓取快照 ===
            log.info("[DryRun] 步骤 4：抓取阅读页 UI 快照");
            UiSnapshot readerSnapshot = detector.tick();
            PageState readerState = detector.detect(readerSnapshot);
            log.info("[DryRun] 阅读页状态: {}, 节点数: {}", readerState, readerSnapshot.size());

            // XML 落盘
            saveSnapshot(readerSnapshot, "dryrun-reader");
            // 打印统计
            printNodeStatistics(readerSnapshot, "阅读页");
            // 打印关键词节点
            printKeywordNodes(readerSnapshot, "阅读页");

            // === 步骤 4b：连翻 2 页后再抓一次，观察底部「看视频领时长」广告是否随翻页出现 ===
            log.info("[DryRun] 步骤 4b：连续翻页后再次抓取阅读页快照（观察底部广告）");
            for (int p = 0; p < 2; p++) {
                gestures.turnPageNext();
                try {
                    Thread.sleep(config.pollIntervalMs() * 4);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            UiSnapshot afterTurn = detector.tick();
            PageState afterTurnState = detector.detect(afterTurn);
            log.info("[DryRun] 翻页后状态: {}, 节点数: {}", afterTurnState, afterTurn.size());
            saveSnapshot(afterTurn, "dryrun-reader-afterturn");
            printKeywordNodes(afterTurn, "翻页后阅读页");
        } else {
            log.warn("[DryRun] 未能打开书籍，仅输出书架页的探测结果");
        }

        // === 步骤 5：输出结论建议 ===
        printConclusion();
        return true;
    }

    // ==================== 内部方法 ====================

    /**
     * 在书架快照中找到第一个可点击的书籍条目并点击。
     * 这是无害操作（只是打开一本书进入阅读页）。
     */
    private boolean openFirstBook(UiSnapshot snapshot) {
        int screenH = snapshot.getScreenHeight();
        int screenW = snapshot.getScreenWidth();

        // 优先按书封容器 resource-id 命中（真机校准：番茄书封 clickable 但 text/content-desc 均为空）
        // 与主流程 BookshelfPage 对齐：收集 e3l 格子后跳过短剧、优先文字小说，避免 dry-run 误开短剧导致快照无效
        List<String> bookIds = locators.bookItemIds();
        UiNode book = null;
        List<UiNode> cells = snapshot.nodes().stream()
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
                .collect(java.util.stream.Collectors.toList());
        UiNode chosen = chooseNovelCell(snapshot, cells);
        if (chosen != null) {
            book = chosen;
            log.info("[DryRun] 按 resource-id 命中书封(已跳过短剧): {}", book.getResourceId());
        }

        // 退回基于文案的旧策略：clickable=true 且带 content-desc 或 text，位于屏幕主体区域
        if (book == null) {
            List<UiNode> candidates = snapshot.nodes().stream()
                    .filter(UiNode::isClickable)
                    .filter(UiNode::isHasBounds)
                    .filter(n -> n.hasContentDesc() || n.hasText())
                    .filter(n -> {
                        double cy = (double) n.getBounds().centerY() / screenH;
                        return cy > 0.10 && cy < 0.85; // 排除状态栏和底部 Tab
                    })
                    .filter(n -> {
                        double ratio = n.getBounds().areaRatio(screenW, screenH);
                        return ratio > 0.005 && ratio < 0.15; // 合理面积
                    })
                    .filter(n -> {
                        // 排除书架 Tab 文案
                        String text = n.getText() + n.getContentDesc();
                        return locators.shelfTabTexts().stream().noneMatch(text::contains);
                    })
                    .collect(java.util.stream.Collectors.toList());
            if (!candidates.isEmpty()) book = candidates.get(0);
        }

        if (book == null) {
            log.warn("[DryRun] 书架中未找到可点击的书籍条目");
            return false;
        }

        log.info("[DryRun] 点击书籍条目: desc='{}', text='{}', bounds={}",
                book.getContentDesc(), book.getText(), book.getBounds());
        gestures.tapAtPixel(book.getBounds().centerX(), book.getBounds().centerY());
        return true;
    }

    /**
     * 保存快照 XML 到 logs/snapshots/ 目录。
     */
    private void saveSnapshot(UiSnapshot snapshot, String tag) {
        String timestamp = LocalDateTime.now().format(TS_FMT);
        Path xmlFile = snapshotsDir.resolve(timestamp + "_" + tag + ".xml");
        try {
            Files.write(xmlFile, snapshot.rawXml().getBytes(StandardCharsets.UTF_8));
            log.info("[DryRun] 快照已保存: {}", xmlFile.toAbsolutePath());
        } catch (IOException e) {
            log.error("[DryRun] 保存快照失败: {}", e.getMessage());
        }
    }

    /**
     * 打印节点属性统计。
     */
    private void printNodeStatistics(UiSnapshot snapshot, String pageName) {
        List<UiNode> nodes = snapshot.nodes();
        int total = nodes.size();
        if (total == 0) {
            log.info("[DryRun] === {} 节点统计：快照为空 ===", pageName);
            return;
        }

        long withText = nodes.stream().filter(UiNode::hasText).count();
        long withResId = nodes.stream().filter(UiNode::hasResourceId).count();
        long withDesc = nodes.stream().filter(UiNode::hasContentDesc).count();
        long clickable = nodes.stream().filter(UiNode::isClickable).count();
        long withBounds = nodes.stream().filter(UiNode::isHasBounds).count();

        System.out.println();
        System.out.println("┌──────────────────────────────────────────────────────────────┐");
        System.out.printf("│  %s 节点属性统计                                              %n", pageName);
        System.out.println("├──────────────────────────────────────────────────────────────┤");
        System.out.printf("│  总节点数:          %-8d                                      %n", total);
        System.out.printf("│  有 text:           %-8d (%.1f%%)                               %n", withText, pct(withText, total));
        System.out.printf("│  有 resource-id:    %-8d (%.1f%%)                               %n", withResId, pct(withResId, total));
        System.out.printf("│  有 content-desc:   %-8d (%.1f%%)                               %n", withDesc, pct(withDesc, total));
        System.out.printf("│  clickable=true:    %-8d (%.1f%%)                               %n", clickable, pct(clickable, total));
        System.out.printf("│  有 bounds:         %-8d (%.1f%%)                               %n", withBounds, pct(withBounds, total));
        System.out.println("└──────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    /**
     * 打印所有含关键词的节点及其完整属性与 bounds。
     */
    private void printKeywordNodes(UiSnapshot snapshot, String pageName) {
        List<UiNode> nodes = snapshot.nodes();

        System.out.println("┌──────────────────────────────────────────────────────────────┐");
        System.out.printf("│  %s 关键词节点（含: %s）%n", pageName, String.join("/", PROBE_KEYWORDS));
        System.out.println("├──────────────────────────────────────────────────────────────┤");

        int matchCount = 0;
        for (UiNode node : nodes) {
            String combined = node.getText() + "|" + node.getContentDesc() + "|" + node.getResourceId();
            boolean matches = PROBE_KEYWORDS.stream().anyMatch(combined::contains);
            if (matches) {
                matchCount++;
                System.out.printf("│  [%d] class=%s%n", matchCount, node.getClazz());
                System.out.printf("│      text='%s'%n", node.getText());
                System.out.printf("│      content-desc='%s'%n", node.getContentDesc());
                System.out.printf("│      resource-id='%s'%n", node.getResourceId());
                System.out.printf("│      clickable=%s, bounds=%s%n", node.isClickable(), node.getBounds());
                System.out.printf("│      depth=%d, parentIdx=%d, idx=%d%n",
                        node.getDepth(), node.getParentIndex(), node.getIndex());
                System.out.println("│      ─────────────────────────────────────────");
            }
        }

        if (matchCount == 0) {
            System.out.println("│  (无匹配关键词的节点)");
        } else {
            System.out.printf("│  共 %d 个节点匹配关键词%n", matchCount);
        }
        System.out.println("└──────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    /**
     * 输出结论建议。
     */
    private void printConclusion() {
        System.out.println("┌──────────────────────────────────────────────────────────────┐");
        System.out.println("│  DryRunProbe 结论建议                                         │");
        System.out.println("├──────────────────────────────────────────────────────────────┤");
        System.out.println("│  请根据上方输出判断：                                         │");
        System.out.println("│  1. 底部「观看视频获取免广告时长」入口：                       │");
        System.out.println("│     - 若有 text/content-desc → L1 语义匹配即可定位            │");
        System.out.println("│     - 若只有 bounds 无属性 → 必须依赖 L2 几何兜底             │");
        System.out.println("│  2. 广告关闭按钮（穿山甲 SDK）：                              │");
        System.out.println("│     - 若有 content-desc='关闭' → L1 即可                      │");
        System.out.println("│     - 若为裸 View 无属性 → 必须 L2(右上角) + L3(时序)         │");
        System.out.println("│  3. 「观看广告」/「继续获取免费时长」按钮：                    │");
        System.out.println("│     - 通常有 text → L1 即可                                   │");
        System.out.println("│     - ★ 这两个按钮禁止 L2 几何兜底（误点浪费配额）            │");
        System.out.println("│  4. 书架 Tab / 书籍条目：                                     │");
        System.out.println("│     - 通常有 text/content-desc → L1 即可                      │");
        System.out.println("│  5. 若阅读页节点数极少（<20）且无交互元素：                    │");
        System.out.println("│     - 说明正文为自绘 Canvas，翻页只能用坐标策略               │");
        System.out.println("│     - 底部入口可能是浮层，需在翻页后若干页才出现              │");
        System.out.println("│                                                              │");
        System.out.println("│  下一步：用上方 dump 结果校准 locators.properties             │");
        System.out.println("└──────────────────────────────────────────────────────────────┘");
    }

    private double pct(long part, long total) {
        return total == 0 ? 0.0 : (double) part / total * 100.0;
    }

    /**
     * 与 BookshelfPage.chooseNovelCell 对齐：从 e3l 书封格候选中跳过短剧、优先文字小说。
     */
    private UiNode chooseNovelCell(UiSnapshot snapshot, List<UiNode> cells) {
        if (cells == null || cells.isEmpty()) return null;
        java.util.regex.Pattern sd = compileSafe(locators.bookShortDramaRegex());
        java.util.regex.Pattern novel = compileSafe(locators.bookNovelRegex());
        UiNode best = null;
        boolean bestNovel = false;
        long bestArea = -1;
        for (UiNode cell : cells) {
            String txt = collectTextWithin(snapshot, cell);
            if (sd != null && sd.matcher(txt).find()) {
                log.info("[DryRun] 跳过疑似短剧/视频卡片: bounds={}", cell.getBounds());
                continue;
            }
            boolean isNovel = novel != null && novel.matcher(txt).find();
            long area = cell.getBounds().area();
            if ((isNovel && !bestNovel) || (isNovel == bestNovel && area > bestArea)) {
                best = cell;
                bestNovel = isNovel;
                bestArea = area;
            }
        }
        return best;
    }

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

    private java.util.regex.Pattern compileSafe(String regex) {
        if (regex == null || regex.isEmpty()) return null;
        try {
            return java.util.regex.Pattern.compile(regex);
        } catch (Exception e) {
            log.warn("[DryRun] 正则编译失败，忽略: '{}' ({})", regex, e.getMessage());
            return null;
        }
    }
}
