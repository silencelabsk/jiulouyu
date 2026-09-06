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
        log.info("[BookshelfPage] 尝试切换到书架 Tab");

        UiSnapshot snapshot = detector.tick();
        PageState currentState = detector.detect(snapshot);

        // 注意：BOOKSHELF 检测依赖底部「书架」Tab 文案，而书城/短剧/赚钱等主 Tab 页同样含该文案，
        // 故 currentState==BOOKSHELF 并不可靠（冷启动常停在书城首页却被误判为书架）。
        // 这里始终主动点一次「书架」Tab 以真正确认落在书架页（已在书架时该点击为幂等空操作）。
        if (currentState == PageState.BOOKSHELF) {
            log.info("[BookshelfPage] 检测到书架态，但底部 Tab 文案多页共享，仍主动点一次「书架」以确保");
        }

        // 使用 LocatorSpec 走降级链定位书架 Tab 节点
        LocatorRegistry.LocatorSpec shelfSpec = locators.getShelfTab();
        Optional<UiNode> tabNode = resolve(shelfSpec, snapshot);

        // L1 未命中，尝试用 shelfTabTexts() 直接做文案匹配
        if (tabNode.isEmpty()) {
            List<String> shelfTexts = locators.shelfTabTexts();
            List<UiNode> textHits = snapshot.findByTextContains(shelfTexts);
            if (!textHits.isEmpty()) {
                tabNode = Optional.of(textHits.get(0));
            }
        }

        // 文案匹配也未命中，尝试在底部区域做几何查找带「书架」文案的节点
        if (tabNode.isEmpty()) {
            log.warn("[BookshelfPage] L1 未命中书架 Tab，尝试底部区域几何查找");
            List<String> shelfTexts = locators.shelfTabTexts();
            List<UiNode> bottomNodes = snapshot.findClickableInRegion(0.0, 1.0, 0.85, 1.0, 0.15);
            tabNode = bottomNodes.stream()
                    .filter(n -> shelfTexts.stream().anyMatch(t ->
                            n.getText().contains(t) || n.getContentDesc().contains(t)))
                    .findFirst();
        }

        if (tabNode.isEmpty()) {
            log.error("[BookshelfPage] 无法定位书架 Tab");
            return false;
        }

        // 关键：华为全面屏底部 Tab 中心 y 落在系统导航手势区会被吞，改点图标上部避开手势区
        tapTabAvoidingGesture(tabNode.get(), snapshot);
        log.info("[BookshelfPage] 已点击书架 Tab 节点（避开底部手势区）");

        // 等待状态变为 BOOKSHELF
        try {
            PageState result = waitSupport.untilState(config.actionTimeoutMs(), PageState.BOOKSHELF);
            logger.updateState(result);
            log.info("[BookshelfPage] 书架 Tab 切换成功");
            return true;
        } catch (Exception e) {
            // 超时不一定失败，再检测一次当前状态
            UiSnapshot afterSnapshot = detector.tick();
            PageState afterState = detector.detect(afterSnapshot);
            if (afterState == PageState.BOOKSHELF) {
                logger.updateState(afterState);
                return true;
            }
            log.warn("[BookshelfPage] 切换到书架 Tab 后状态未确认: {}", afterState);
            return false;
        }
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
     * 动态选取书架内首个可点击的书籍条目并打开。
     * <p>
     * 选取策略：遍历内存节点表，查找首个满足以下条件的节点：
     * - clickable=true
     * - 带 content-desc（通常是书名）或 text 非空
     * - bounds 中心在屏幕主体区域（y 在 0.1~0.85 之间，排除顶部状态栏和底部 Tab）
     * - areaRatio 在合理范围（不能太大=容器，不能太小=图标）
     * <p>
     * 绝不写死索引（如 //node()[3]），因为书架内容随用户操作变化。
     * 若首屏找不到可点条目，用 GestureSupport.swipeUp() 滚动后重试。
     *
     * @return true=成功打开一本书（状态离开 BOOKSHELF）, false=打开失败
     */
    public boolean openAnyBook() {
        log.info("[BookshelfPage] 尝试打开书架中的任意一本书");

        int maxScrollAttempts = config.maxRecoveryRetry(); // 重试次数来自 config，不硬编码

        for (int attempt = 0; attempt <= maxScrollAttempts; attempt++) {
            UiSnapshot snapshot = detector.tick();
            PageState state = detector.detect(snapshot);

            if (state != PageState.BOOKSHELF) {
                log.warn("[BookshelfPage] 当前不在书架页 (state={})，无法打开书籍", state);
                return false;
            }

            // 动态选取书籍条目
            Optional<UiNode> bookNode = findBookItem(snapshot);
            if (bookNode.isPresent()) {
                UiNode book = bookNode.get();
                // D5：记录本次打开的书籍标识，供 per-book 进度与轮换排除使用
                lastOpenedBookId = bookIdOf(book);
                log.info("[BookshelfPage] 找到书籍条目: id='{}', bounds={}",
                        truncate(lastOpenedBookId, 20), book.getBounds());
                clickNode(book, snapshot);

                // 等待状态离开 BOOKSHELF（进入 READER 或其他状态）
                if (confirmLeftBookshelf()) {
                    return true;
                }
            }

            // 未找到可点条目，滚动后重试
            if (attempt < maxScrollAttempts) {
                log.info("[BookshelfPage] 首屏未找到可点击书籍条目，向上滚动后重试 ({}/{})",
                        attempt + 1, maxScrollAttempts);
                gestures.swipeUp();
                // 滚动后短暂等待 UI 稳定（退避策略，非轮询）
                try {
                    Thread.sleep(config.pollIntervalMs() * 2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        log.error("[BookshelfPage] 多次滚动后仍未找到可打开的书籍条目");
        return false;
    }

    /**
     * D5：打开下一本「未使用过」的书籍（多本书轮换）。
     * <p>
     * 与 {@link #openAnyBook()} 的区别：本方法在动态选取书籍条目时排除 excludeIds 中已用过的书籍，
     * 若首屏全部已用过则向上滚动继续查找未用书籍，滚动次数略多于 openAnyBook（轮换需要翻更多屏）。
     * <p>
     * 向后兼容：excludeIds 为 null 或空集时行为等价于 openAnyBook（取首个可点书籍）。
     *
     * @param excludeIds 已使用过的书籍标识集合（来自 per-book 进度），可为 null
     * @return true=成功打开一本未使用的书, false=书架中已无未使用书籍或打开失败
     */
    public boolean openNextBook(Set<String> excludeIds) {
        Set<String> excludes = (excludeIds == null) ? Collections.emptySet() : excludeIds;
        log.info("[BookshelfPage] 尝试打开下一本未使用的书籍（排除 {} 本已用）", excludes.size());

        // 轮换需要翻更多屏，滚动次数在 maxRecoveryRetry 基础上额外多给 2 次
        int maxScrollAttempts = config.maxRecoveryRetry() + 2;

        for (int attempt = 0; attempt <= maxScrollAttempts; attempt++) {
            UiSnapshot snapshot = detector.tick();
            PageState state = detector.detect(snapshot);

            if (state != PageState.BOOKSHELF) {
                log.warn("[BookshelfPage] 当前不在书架页 (state={})，无法换书", state);
                return false;
            }

            // 动态选取「未使用过」的书籍条目
            Optional<UiNode> bookNode = findBookItem(snapshot, excludes);
            if (bookNode.isPresent()) {
                UiNode book = bookNode.get();
                lastOpenedBookId = bookIdOf(book);
                log.info("[BookshelfPage] 换到新书籍: id='{}', bounds={}",
                        truncate(lastOpenedBookId, 20), book.getBounds());
                clickNode(book, snapshot);

                if (confirmLeftBookshelf()) {
                    return true;
                }
            }

            // 未找到未使用条目，滚动后重试
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
     * openAnyBook / openNextBook 共用此收尾判定，避免重复代码。
     *
     * @return true=已离开书架, false=状态仍停留在书架（点击可能无效）
     */
    private boolean confirmLeftBookshelf() {
        try {
            PageState result = waitSupport.untilState(config.actionTimeoutMs(),
                    PageState.READER, PageState.READER_MENU, PageState.COMMON_POPUP, PageState.AD_CONFIRM_DIALOG,
                    PageState.APP_LAUNCHING);
            logger.updateState(result);
            // C3.4：打开书后工具栏常短暂可见（READER_MENU），同样属于“已离开书架进入阅读页”
            if (result.isReaderFamily()) {
                log.info("[BookshelfPage] 成功进入阅读页: {}", result);
            } else {
                log.info("[BookshelfPage] 打开书籍后进入状态: {}（非 READER 但已离开书架）", result);
            }
            return true;
        } catch (Exception e) {
            // 超时：再检测一次，可能已经进入了 READER 但 detect 未命中
            UiSnapshot afterSnapshot = detector.tick();
            PageState afterState = detector.detect(afterSnapshot);
            if (afterState != PageState.BOOKSHELF) {
                logger.updateState(afterState);
                log.info("[BookshelfPage] 虽超时但已离开书架: state={}", afterState);
                return true;
            }
            log.warn("[BookshelfPage] 点击书籍后状态未变化，可能点击无效");
            return false;
        }
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
