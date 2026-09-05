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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 广告流程页面对象——最高风险区。
 * <p>
 * 穿山甲广告 SDK 的视频页常自绘，控件可能无 text/id/desc，因此四级兜底全开（仅对关闭按钮）。
 * <p>
 * 关键设计决策：
 * <ul>
 *   <li>clickWatchAd()：仅 L1（allowGeometryFallback=false），禁用几何兜底防误点浪费配额</li>
 *   <li>waitCountdownFinished()：不做任何点击，仅心跳轮询等待状态转变。
 *       <b>视频播放期间任何点击都可能误触广告落地页</b>，导致跳转到浏览器/应用商店，
 *       流程将彻底偏离，恢复代价极大。</li>
 *   <li>clickClose()：L1 → L2（右上角几何）→ L3（时序）→ L4（back/重启）全链路。
 *       关闭按钮是本流程中唯一允许激进兜底的控件——漏点导致卡死的后果远大于误点。</li>
 *   <li>clickContinueGetFreeTime()：仅 L1，理由同 clickWatchAd()</li>
 *   <li>readGainedMinutes()：用正则从快照提取本次获得的分钟数</li>
 * </ul>
 */
public class AdFlowPage extends BasePage {

    private static final Logger log = LoggerFactory.getLogger(AdFlowPage.class);

    private final StateDetector detector;
    private final WaitSupport waitSupport;

    /**
     * M4：奖励语义关键词——只有命中这些动词/名词的文案才视为本次奖励。
     * 可根据实际运营文案调整。
     */
    private static final List<String> REWARD_KEYWORDS =
            java.util.Arrays.asList("获得", "领取", "奖励", "到账", "已领", "恭喜", "发放", "赠");

    /**
     * M4：非奖励语义（排除词）——命中时即使带数字也不当作奖励，
     * 避免把「剩余90分钟」误计为本次获得导致严重高估。
     */
    private static final List<String> NEGATIVE_MINUTE_KEYWORDS =
            java.util.Arrays.asList("剩余", "还剩", "仅剩", "倒计时", "尚需");

    public AdFlowPage(AppiumDriver driver, AutomationConfig config, LocatorRegistry locators,
                      GestureSupport gestures, RunLogger logger,
                      StateDetector detector, WaitSupport waitSupport) {
        super(driver, config, locators, gestures, logger);
        this.detector = detector;
        this.waitSupport = waitSupport;
    }

    /**
     * 点击「观看广告」按钮。
     * <p>
     * <b>仅使用 L1 语义属性匹配</b>（allowGeometryFallback=false）。
     * 禁用几何兜底的原因：误点此按钮会多看一轮视频、浪费用户每日的时长配额。
     * 宁可返回 false 让上层走重试/恢复，也绝不冒误点风险。
     *
     * @return true=成功点击「观看广告」, false=L1 未命中
     */
    public boolean clickWatchAd() {
        log.info("[AdFlowPage] 尝试点击「观看广告」按钮（仅 L1）");

        UiSnapshot snapshot = detector.tick();
        LocatorRegistry.LocatorSpec adWatchSpec = locators.getAdWatch();

        // L1：语义属性匹配
        Optional<UiNode> watchNode = resolve(adWatchSpec, snapshot);
        if (watchNode.isPresent()) {
            clickNode(watchNode.get(), snapshot);
            log.info("[AdFlowPage] L1 命中「观看广告」按钮并点击");
            return true;
        }

        // L1 补充：直接用 adWatchTexts() 文案匹配（兜底 resolve 中 XPath 解析不到的情况）
        List<String> watchTexts = locators.adWatchTexts();
        boolean clicked = clickByTextCandidates(watchTexts, snapshot);
        if (clicked) {
            log.info("[AdFlowPage] 通过 adWatchTexts 文案直接匹配点击成功");
            return true;
        }

        // ★ 不执行 L2 几何兜底：allowGeometryFallback=false
        log.warn("[AdFlowPage] L1 未命中「观看广告」按钮，且几何兜底被禁止（防误点浪费配额）");
        return false;
    }

    /**
     * 等待广告视频倒计时结束。
     * <p>
     * <b>不做任何点击</b>，仅按 config.adVideoTimeoutMs()（45s）做心跳轮询，
     * 等待状态从 AD_VIDEO_PLAYING 变为 AD_CLOSE_READY。
     * <p>
     * <b>视频播放期间任何点击都可能误触广告落地页</b>——穿山甲 SDK 的视频区域
     * 通常整体可点击（跳转到广告主落地页/应用商店），一旦误触将离开当前 App，
     * 恢复代价极大（需 back 多次或重启 App），且会浪费一次广告展示配额。
     * <p>
     * 此阶段用较稀疏的轮询间隔（pollIntervalMs * 4）降低 CPU 与 RPC 压力，
     * 因为视频通常固定播放约 30 秒，无需高频探测。
     */
    public void waitCountdownFinished() {
        log.info("[AdFlowPage] 等待广告视频倒计时结束（不做任何点击）");

        long timeoutMs = config.adVideoTimeoutMs();
        // 使用较稀疏的轮询间隔：视频播放期间无需高频探测
        long sparseIntervalMs = config.pollIntervalMs() * 4;
        long startTime = System.currentTimeMillis();
        // E4：用「上次心跳时间戳」判定心跳，消除取模近似的漏打/重复打问题
        long heartbeatIntervalMs = config.heartbeatIntervalSec() * 1000L;
        long lastHeartbeatAt = startTime;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            UiSnapshot snapshot = detector.tick();
            PageState state = detector.detect(snapshot);

            if (state == PageState.AD_CLOSE_READY) {
                logger.updateState(state);
                log.info("[AdFlowPage] 广告视频播放完毕，关闭按钮已就绪 (耗时={}ms)",
                        System.currentTimeMillis() - startTime);
                return;
            }

            if (state != PageState.AD_VIDEO_PLAYING) {
                // 状态意外变化（可能已经跳到了其他状态）
                logger.updateState(state);
                log.info("[AdFlowPage] 视频播放期间状态意外变化: {}，退出等待", state);
                return;
            }

            // E4：心跳日志——「当前时间 - 上次心跳时间 ≥ interval」时才打，不再用取模近似
            long now = System.currentTimeMillis();
            if (now - lastHeartbeatAt >= heartbeatIntervalMs) {
                heartbeat("AdFlow-视频等待");
                lastHeartbeatAt = now;
            }

            // 稀疏轮询间隔
            try {
                Thread.sleep(sparseIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[AdFlowPage] 视频等待被中断");
                return;
            }
        }

        log.warn("[AdFlowPage] 等待广告视频倒计时超时 ({}ms)，将尝试强制关闭", timeoutMs);
    }

    /**
     * 点击「关闭」按钮——L1 → L2（右上角几何）→ L3（时序）→ L4（back/重启）全链路。
     * <p>
     * 关闭按钮是本流程中唯一允许激进兜底的控件：
     * - 漏点导致卡死（永远停留在广告页面）的后果远大于误点（最多回到阅读页多翻一页）。
     * - allowGeometryFallback=true，因此 L2/L3 均可启用。
     * <p>
     * 四级降级策略：
     * 1. L1：语义属性匹配（text 候选集「关闭|跳过|×」+ content-desc「关闭|close|skip」）
     * 2. L2：右上角几何推断（x>0.8W && y<0.2H 区域内的小面积 clickable 节点）
     * 3. L3：时序兜底（等待超过 adVideoTimeoutMs 后强制 L2）
     * 4. L4：系统兜底（navigate().back()，再失败则 restartApp）
     *
     * @return true=成功关闭广告, false=全部降级失败
     */
    public boolean clickClose() {
        log.info("[AdFlowPage] 尝试点击「关闭」按钮（L1→L2→L3→L4 全链路）");

        long startTime = System.currentTimeMillis();
        LocatorRegistry.LocatorSpec adCloseSpec = locators.getAdClose();

        // 在 adCloseReadyTimeoutMs 内轮询尝试
        long closeTimeoutMs = config.adCloseReadyTimeoutMs();

        while (System.currentTimeMillis() - startTime < closeTimeoutMs) {
            UiSnapshot snapshot = detector.tick();
            PageState state = detector.detect(snapshot);

            // === L1：语义属性匹配 ===
            Optional<UiNode> closeNode = resolve(adCloseSpec, snapshot);
            if (closeNode.isPresent()) {
                clickNode(closeNode.get(), snapshot);
                log.info("[AdFlowPage] L1 命中「关闭」按钮并点击");
                return true;
            }

            // L1 补充：直接用 adCloseTexts() 和 adCloseDescs() 文案匹配
            List<String> closeTexts = locators.adCloseTexts();
            if (clickByTextCandidates(closeTexts, snapshot)) {
                log.info("[AdFlowPage] L1 通过 adCloseTexts 直接匹配点击成功");
                return true;
            }
            List<String> closeDescs = locators.adCloseDescs();
            if (clickByContentDescCandidates(closeDescs, snapshot)) {
                log.info("[AdFlowPage] L1 通过 adCloseDescs 直接匹配点击成功");
                return true;
            }

            log.debug("[AdFlowPage] L1 全部未命中，启用 L2 几何兜底（右上角区域）");

            // === L2：右上角几何推断 ===
            // ★ adClose 的 allowGeometryFallback=true，允许执行 L2
            Optional<UiNode> geoNode = resolveByGeometry(adCloseSpec, snapshot);
            if (geoNode.isPresent()) {
                clickNode(geoNode.get(), snapshot);
                log.info("[AdFlowPage] L2 几何兜底命中右上角关闭按钮并点击");
                return true;
            }

            // === L3：时序兜底 ===
            long elapsed = System.currentTimeMillis() - startTime;
            if (clickByTimeoutFallback(adCloseSpec, snapshot, elapsed)) {
                log.info("[AdFlowPage] L3 时序兜底成功");
                return true;
            }

            // 等待下一次轮询
            try {
                Thread.sleep(config.pollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // === L4：系统兜底 ===
        log.warn("[AdFlowPage] L1/L2/L3 全部未命中，启用 L4 系统兜底");
        if (systemBack()) {
            // back 后检测状态是否已离开广告页
            try {
                Thread.sleep(config.pollIntervalMs() * 2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            UiSnapshot afterBack = detector.tick();
            PageState afterState = detector.detect(afterBack);
            if (afterState != PageState.AD_CLOSE_READY && afterState != PageState.AD_VIDEO_PLAYING) {
                log.info("[AdFlowPage] L4 back 成功退出广告页，当前状态: {}", afterState);
                logger.updateState(afterState);
                return true;
            }
        }

        // back 无效，重启 App
        log.warn("[AdFlowPage] L4 back 无效，执行 restartApp");
        systemRestartApp();
        return false; // 重启后状态需要上层重新检测
    }

    /**
     * 点击「继续获取免费时长」按钮。
     * <p>
     * <b>仅使用 L1 语义属性匹配</b>（allowGeometryFallback=false）。
     * 禁用几何兜底的原因：误点此按钮会多看一轮视频、浪费用户每日的时长配额。
     *
     * @return true=成功点击, false=L1 未命中
     */
    public boolean clickContinueGetFreeTime() {
        log.info("[AdFlowPage] 尝试点击「继续获取免费时长」按钮（仅 L1）");

        UiSnapshot snapshot = detector.tick();
        LocatorRegistry.LocatorSpec continueSpec = locators.getAdContinue();

        // L1：语义属性匹配
        Optional<UiNode> continueNode = resolve(continueSpec, snapshot);
        if (continueNode.isPresent()) {
            clickNode(continueNode.get(), snapshot);
            log.info("[AdFlowPage] L1 命中「继续获取免费时长」按钮并点击");
            return true;
        }

        // L1 补充：直接用 adContinueTexts() 文案匹配
        List<String> continueTexts = locators.adContinueTexts();
        boolean clicked = clickByTextCandidates(continueTexts, snapshot);
        if (clicked) {
            log.info("[AdFlowPage] 通过 adContinueTexts 文案直接匹配点击成功");
            return true;
        }

        // ★ 不执行 L2 几何兜底：allowGeometryFallback=false
        log.warn("[AdFlowPage] L1 未命中「继续获取免费时长」按钮，且几何兜底被禁止（防误点浪费配额）");
        return false;
    }

    /**
     * 从快照中提取本次获得的免广告分钟数。
     * <p>
     * <b>M4 修复：只认带奖励语义的文案。</b>旧实现用正则 {@code (\d+)\s*分钟} 全局取最大值，
     * 会把「剩余90分钟」等非奖励文案当作奖励，造成严重高估。
     * <p>
     * 新策略：
     * <ul>
     *   <li>逐节点扫描，跳过带「剩余/还剩」等排除词的节点；</li>
     *   <li>仅当节点自身（text/desc）或同一父节点下的兄弟节点带奖励语义（如「恭喜获得」标题）时，
     *       才从该节点提取数字（兼容「标题+数值」分离的弹窗）；</li>
     *   <li>若无明确奖励语义命中，<b>返回 0</b>（交由状态机 rewardFallbackMinutes 兜底），
     *       而非取可能严重偏大的全局最大值。</li>
     * </ul>
     *
     * @param snapshot 当次 UI 快照
     * @return 本次获得的分钟数，无奖励语义命中时返回 0
     */
    public int readGainedMinutes(UiSnapshot snapshot) {
        if (snapshot == null) return 0;
    
        String regex = locators.getRewardRegex();
        Pattern rewardPattern = Pattern.compile(regex);
        Pattern numPattern = Pattern.compile("(\\d+)");
        List<UiNode> nodes = snapshot.nodes();
    
        int maxMinutes = 0;
        boolean matched = false;
        for (UiNode node : nodes) {
            String text = node.getText();
            // M4：跳过带明确非奖励语义（剩余/还剩等）的节点
            if (containsAny(text, NEGATIVE_MINUTE_KEYWORDS)
                    || containsAny(node.getContentDesc(), NEGATIVE_MINUTE_KEYWORDS)) {
                continue;
            }
            // M4：节点自身或同父兄弟节点带奖励语义时才计入（兼容标题与数值分离的弹窗）
            boolean rewardContext = containsAny(text, REWARD_KEYWORDS)
                    || containsAny(node.getContentDesc(), REWARD_KEYWORDS)
                    || hasRewardSemanticSibling(nodes, node);
            if (!rewardContext) continue;
    
            Matcher rm = rewardPattern.matcher(text);
            while (rm.find()) {
                Matcher nm = numPattern.matcher(rm.group());
                if (nm.find()) {
                    try {
                        int value = Integer.parseInt(nm.group(1));
                        matched = true;
                        if (value > maxMinutes) maxMinutes = value;
                    } catch (NumberFormatException ignore) {
                        // 忽略无法解析的数字
                    }
                }
            }
        }
    
        if (!matched || maxMinutes <= 0) {
            // M4：无明确奖励语义命中，返回 0 交由兜底估值处理，绝不取全局最大值
            log.debug("[AdFlowPage] 未从快照中匹配到带奖励语义的分钟数，返回 0 交由兜底处理 (regex={})", regex);
            return 0;
        }
    
        log.info("[AdFlowPage] 从快照中提取到奖励(带奖励语义): {} 分钟", maxMinutes);
        return maxMinutes;
    }
    
    /**
     * M4：text 是否包含 keywords 中任一关键词（null/空安全）。
     */
    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isEmpty()) return false;
        return keywords.stream().anyMatch(text::contains);
    }
    
    /**
     * M4：判断节点的同一父节点下是否存在带奖励语义的兄弟节点。
     * 用于兼容「恭喜获得」标题与「30分钟」数值分处不同节点的奖励弹窗。
     */
    private boolean hasRewardSemanticSibling(List<UiNode> nodes, UiNode node) {
        int parentIdx = node.getParentIndex();
        if (parentIdx < 0 || parentIdx >= nodes.size()) return false;
        for (UiNode other : nodes) {
            if (other.getParentIndex() != parentIdx) continue;
            if (containsAny(other.getText(), REWARD_KEYWORDS)
                    || containsAny(other.getContentDesc(), REWARD_KEYWORDS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 点击「关闭/放弃」按钮——用于 AD_CONTINUE_PROMPT 状态下选择不继续观看。
     * <p>
     * 在「继续获取免费时长」弹窗中，通常同时存在「继续」和「关闭/放弃」两个按钮。
     * 当达到轮次上限或已满足目标时长时，需要点击「关闭/放弃」退出此弹窗。
     *
     * @return true=成功点击关闭/放弃, false=未找到
     */
    public boolean clickDismissContinuePrompt() {
        log.info("[AdFlowPage] 尝试关闭「继续获取时长」弹窗（点击关闭/放弃）");

        UiSnapshot snapshot = detector.tick();

        // 尝试用 adCloseTexts 匹配关闭按钮
        List<String> closeTexts = locators.adCloseTexts();
        if (clickByTextCandidates(closeTexts, snapshot)) {
            log.info("[AdFlowPage] 已通过 adCloseTexts 关闭继续弹窗");
            return true;
        }

        // 尝试用 commonDismissTexts 匹配
        List<String> dismissTexts = locators.commonDismissTexts();
        if (clickByTextCandidates(dismissTexts, snapshot)) {
            log.info("[AdFlowPage] 已通过 commonDismissTexts 关闭继续弹窗");
            return true;
        }

        // 尝试 L2 几何兜底（右上角关闭按钮）
        LocatorRegistry.LocatorSpec adCloseSpec = locators.getAdClose();
        Optional<UiNode> geoNode = resolveByGeometry(adCloseSpec, snapshot);
        if (geoNode.isPresent()) {
            clickNode(geoNode.get(), snapshot);
            log.info("[AdFlowPage] 通过 L2 几何兜底关闭继续弹窗");
            return true;
        }

        log.warn("[AdFlowPage] 无法关闭「继续获取时长」弹窗");
        return false;
    }
}
