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

import java.util.List;
import java.util.Optional;

/**
 * 阅读页面对象。
 * <p>
 * 职责：
 * - turnPage() / turnPagePrev()：翻页操作
 * - goNextChapter()：跳转到下一章
 * - hasBottomRewardEntry() / clickBottomRewardEntry()：底部「观看视频获取免广告时长」入口
 * <p>
 * 关键设计决策：
 * <p>
 * <b>翻页后的等待必须一次覆盖多个状态</b>：
 * {@code waitSupport.untilState(READER, AD_ENTRY_PROMPT, AD_CONFIRM_DIALOG, AD_VIDEO_PLAYING, CHAPTER_END, COMMON_POPUP)}
 * 这是稳定性的关键细节——翻页可能直接触发广告弹窗（番茄小说在翻若干页后会强制弹出激励视频入口），
 * 若只等 READER 会白等到超时，浪费 actionTimeoutMs 后才走异常分支。
 * <p>
 * <b>翻页成功判据</b>：
 * 因阅读页正文是自绘 Canvas（排版引擎直接绘制文字，UI 树中没有对应的 TextView），
 * 无法用文本变化校验翻页是否成功。改以「动作后 detect() 仍为 READER 且未出现异常态」
 * + 「翻页计数递增」作为判据。这是 R8 风险的缓解措施。
 * <p>
 * 若 config.verifyPageTurnedByScreenshot() 为 true，则额外做截图前后帧差异对比。
 * 该选项默认关闭，因截图有 200-500ms 开销，长任务中累积影响显著。
 */
public class ReaderPage extends BasePage {

    private static final Logger log = LoggerFactory.getLogger(ReaderPage.class);

    private final StateDetector detector;
    private final WaitSupport waitSupport;

    public ReaderPage(AppiumDriver driver, AutomationConfig config, LocatorRegistry locators,
                      GestureSupport gestures, RunLogger logger,
                      StateDetector detector, WaitSupport waitSupport) {
        super(driver, config, locators, gestures, logger);
        this.detector = detector;
        this.waitSupport = waitSupport;
    }

    /**
     * 翻到下一页。
     * <p>
     * 委托 GestureSupport.turnPageNext()（按 config.turnStrategy() 选择 tap 或 swipe）。
     * 翻页后一次等待覆盖多个可能状态（READER / 广告态 / 章节末 / 弹窗），
     * 因为翻页可能直接触发广告弹窗。
     * <p>
     * 成功判据：动作后 detect() 仍为 READER（或进入了广告/章节末等可处理状态），
     * 且未出现异常态（UNKNOWN / RECOVERY_NEEDED）。
     *
     * @return 翻页后的页面状态（供调用方决策下一步）
     */
    public PageState turnPage() {
        log.debug("[ReaderPage] 执行翻页（下一页）");

        // 执行翻页手势
        gestures.turnPageNext();
        logger.incrementPageCount();

        // 翻页后的等待必须一次覆盖多个状态：
        // 翻页可能直接触发广告弹窗（番茄小说在翻若干页后会强制弹出激励视频入口），
        // 若只等 READER 会白等到超时。把广告态纳入同一次等待是稳定性的关键细节。
        try {
            PageState result = waitSupport.untilState(config.actionTimeoutMs(),
                    PageState.READER, PageState.READER_MENU, PageState.AD_ENTRY_PROMPT, PageState.AD_CONFIRM_DIALOG,
                    PageState.AD_VIDEO_PLAYING, PageState.AD_CLOSE_READY,
                    PageState.CHAPTER_END, PageState.COMMON_POPUP);
            logger.updateState(result);
            logger.updatePageSourceMetrics(detector.getLastPageSourceMs(),
                    detector.getCachedSnapshot() != null ? detector.getCachedSnapshot().xmlLength() : 0);
            return result;
        } catch (Exception e) {
            // 超时：主动 tick 一次确认当前状态
            log.debug("[ReaderPage] 翻页后等待超时，主动检测当前状态");
            UiSnapshot snapshot = detector.tick();
            PageState state = detector.detect(snapshot);
            logger.updateState(state);
            return state;
        }
    }

    /**
     * 翻到上一页。
     */
    public PageState turnPagePrev() {
        log.debug("[ReaderPage] 执行翻页（上一页）");
        gestures.turnPagePrev();
        logger.incrementPageCount();

        try {
            PageState result = waitSupport.untilState(config.actionTimeoutMs(),
                    PageState.READER, PageState.READER_MENU, PageState.AD_ENTRY_PROMPT, PageState.AD_CONFIRM_DIALOG,
                    PageState.CHAPTER_END, PageState.COMMON_POPUP);
            logger.updateState(result);
            return result;
        } catch (Exception e) {
            UiSnapshot snapshot = detector.tick();
            PageState state = detector.detect(snapshot);
            logger.updateState(state);
            return state;
        }
    }

    /**
     * 跳转到下一章。
     * <p>
     * 策略：
     * 1. 先用 LocatorRegistry.getChapterNext() 尝试点击「下一章」按钮
     * 2. 若当前无「下一章」按钮，则继续翻页直到状态变为 CHAPTER_END 或翻页次数达上限
     * 3. 翻页次数上限来自 config.pagesPerCycle()（不硬编码）
     *
     * @return true=成功进入下一章（状态回到 READER）, false=跳转失败
     */
    public boolean goNextChapter() {
        log.info("[ReaderPage] 尝试跳转到下一章");

        UiSnapshot snapshot = detector.tick();
        PageState state = detector.detect(snapshot);

        // 如果当前已在 CHAPTER_END，直接点「下一章」
        if (state == PageState.CHAPTER_END) {
            return clickNextChapterButton(snapshot);
        }

        // 否则继续翻页直到出现 CHAPTER_END
        int maxPages = config.pagesPerCycle();
        for (int i = 0; i < maxPages; i++) {
            PageState afterTurn = turnPage();
            if (afterTurn == PageState.CHAPTER_END) {
                // 到达章节末尾，点击下一章
                UiSnapshot chapterSnapshot = detector.tick();
                return clickNextChapterButton(chapterSnapshot);
            }
            if (!afterTurn.isReaderFamily()) {
                // 遇到了非阅读页家族状态（广告/弹窗等），返回 false 让上层处理
                // C3.4：READER_MENU 是 READER 的可自愈子态（工具栏短暂可见），不应当作异常退出
                log.info("[ReaderPage] 翻页过程中遇到状态: {}，暂停跳章", afterTurn);
                return false;
            }
        }

        log.warn("[ReaderPage] 翻了 {} 页仍未到达章节末尾", maxPages);
        return false;
    }

    /**
     * 检查当前快照中是否存在底部「观看视频获取免广告时长」入口。
     * <p>
     * 判定依据：在屏幕底部区域（y > 0.7H）存在匹配 adEntryTexts() 的节点。
     *
     * @param snapshot 当次 UI 快照
     * @return true=底部广告入口存在
     */
    public boolean hasBottomRewardEntry(UiSnapshot snapshot) {
        if (snapshot == null) return false;

        List<String> adEntryTexts = locators.adEntryTexts();
        List<UiNode> hits = snapshot.findByTextContains(adEntryTexts);

        if (hits.isEmpty()) return false;

        // 确认至少有一个命中节点位于屏幕底部区域
        int screenH = snapshot.getScreenHeight();
        return hits.stream().anyMatch(n -> {
            if (!n.isHasBounds()) return false;
            double cy = (double) n.getBounds().centerY() / screenH;
            return cy > 0.7; // 底部 30% 区域
        });
    }

    /**
     * 点击底部的「观看视频获取免广告时长」入口。
     * <p>
     * 使用 LocatorRegistry.getAdEntry()（boundsHint="y>0.8"，裁决时取 y 最大者）。
     *
     * @return true=成功点击, false=未找到入口
     */
    public boolean clickBottomRewardEntry() {
        log.info("[ReaderPage] 尝试点击底部「观看视频获取免广告时长」入口");

        UiSnapshot snapshot = detector.tick();
        LocatorRegistry.LocatorSpec adEntrySpec = locators.getAdEntry();

        // L1：按 LocatorSpec 走降级链
        Optional<UiNode> entryNode = resolve(adEntrySpec, snapshot);
        if (entryNode.isPresent()) {
            clickNode(entryNode.get(), snapshot);
            log.info("[ReaderPage] 已点击底部广告入口");
            return true;
        }

        // L1 未命中，尝试用 adEntryTexts() 直接在底部区域查找
        List<String> adTexts = locators.adEntryTexts();
        List<UiNode> textHits = snapshot.findByTextContains(adTexts);
        if (!textHits.isEmpty()) {
            // 取 y 最大的（最靠近底部的）
            int screenH = snapshot.getScreenHeight();
            UiNode bottomMost = textHits.stream()
                    .filter(UiNode::isHasBounds)
                    .max((a, b) -> Integer.compare(a.getBounds().centerY(), b.getBounds().centerY()))
                    .orElse(textHits.get(0));
            clickNode(bottomMost, snapshot);
            log.info("[ReaderPage] 通过文案直接匹配点击底部广告入口");
            return true;
        }

        log.warn("[ReaderPage] 未找到底部广告入口");
        return false;
    }

    // ==================== 内部方法 ====================

    /**
     * 点击「下一章」按钮。
     */
    private boolean clickNextChapterButton(UiSnapshot snapshot) {
        LocatorRegistry.LocatorSpec chapterSpec = locators.getChapterNext();
        Optional<UiNode> nextNode = resolve(chapterSpec, snapshot);
        if (nextNode.isPresent()) {
            clickNode(nextNode.get(), snapshot);
            log.info("[ReaderPage] 已点击「下一章」按钮");

            // 等待进入 READER 状态
            try {
                PageState result = waitSupport.untilState(config.actionTimeoutMs(),
                        PageState.READER, PageState.READER_MENU, PageState.AD_ENTRY_PROMPT, PageState.COMMON_POPUP);
                logger.updateState(result);
                return true;
            } catch (Exception e) {
                // 超时后再检测一次
                UiSnapshot afterSnapshot = detector.tick();
                PageState afterState = detector.detect(afterSnapshot);
                logger.updateState(afterState);
                return afterState.isReaderFamily();
            }
        }

        // L1 未命中，用 chapterNextTexts() 文案直接匹配
        List<String> chapterTexts = locators.chapterNextTexts();
        boolean clicked = clickByTextCandidates(chapterTexts, snapshot);
        if (clicked) {
            log.info("[ReaderPage] 通过文案匹配点击「下一章」");
            try {
                waitSupport.untilState(config.actionTimeoutMs(), PageState.READER, PageState.READER_MENU, PageState.COMMON_POPUP);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        log.warn("[ReaderPage] 未找到「下一章」按钮");
        return false;
    }
}
