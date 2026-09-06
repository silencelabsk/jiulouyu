package com.fanqie.auto.config;

import org.openqa.selenium.By;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * 定位器注册表：管理所有逻辑控件的定位策略。
 * <p>
 * 关键设计决策：
 * 1. Properties.load(InputStream) 默认用 ISO-8859-1 解码，中文会变乱码导致 text 匹配全失效。
 *    必须用 InputStreamReader(inputStream, StandardCharsets.UTF_8) 配合 Properties.load(Reader)。
 * 2. 每个逻辑控件对应一个 LocatorSpec：primaryBy + fallbackBy 列表 + boundsHint + allowGeometryFallback。
 * 3. ad.watch 与 ad.continue 的 allowGeometryFallback 必须为 false（误点会多看一轮视频、浪费每日配额）；
 *    只有 ad.close 允许为 true（关闭按钮误点的后果远小于漏点导致卡死）。
 */
public class LocatorRegistry {

    private final Properties locatorsProps;

    // ==================== 逻辑控件的 LocatorSpec ====================

    /** 书架 Tab（底部导航栏） */
    private final LocatorSpec shelfTab;
    /** 书籍条目（书架列表中可点击的书籍） */
    private final LocatorSpec bookItem;
    /** 底部广告入口（阅读页底部「观看视频获取免广告时长」） */
    private final LocatorSpec adEntry;
    /** 观看广告按钮（确认弹窗中） */
    private final LocatorSpec adWatch;
    /** 关闭按钮（广告视频结束后） */
    private final LocatorSpec adClose;
    /** 继续获取免费时长按钮 */
    private final LocatorSpec adContinue;
    /** 下一章按钮 */
    private final LocatorSpec chapterNext;
    /** 通用关闭弹窗按钮 */
    private final LocatorSpec commonDismiss;

    /** 奖励提取正则 */
    private final String rewardRegex;

    public LocatorRegistry() {
        this.locatorsProps = new Properties();
        loadFromClasspath("locators.properties");
        // 初始化各控件的 LocatorSpec
        this.shelfTab = buildShelfTab();
        this.bookItem = buildBookItem();
        this.adEntry = buildAdEntry();
        this.adWatch = buildAdWatch();
        this.adClose = buildAdClose();
        this.adContinue = buildAdContinue();
        this.chapterNext = buildChapterNext();
        this.commonDismiss = buildCommonDismiss();
        this.rewardRegex = locatorsProps.getProperty("ad.reward.regex", "(\\d+)\\s*分钟");
    }

    /**
     * 关键：必须用 UTF-8 Reader 加载，否则中文全部乱码。
     * Properties.load(InputStream) 默认 ISO-8859-1，这是本任务最容易踩的坑。
     */
    private void loadFromClasspath(String resourceName) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (is != null) {
                locatorsProps.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            } else {
                System.err.println("[LocatorRegistry] 错误：classpath 中未找到 " + resourceName);
            }
        } catch (IOException e) {
            System.err.println("[LocatorRegistry] 错误：加载 " + resourceName + " 失败: " + e.getMessage());
        }
    }

    /**
     * 打印读到的中文候选集，供人工核验无乱码。
     * 在程序启动时调用一次，确认 properties 文件编码正确。
     */
    public void dumpToConsole() {
        System.out.println("====== LocatorRegistry 候选集核验 ======");
        for (String key : locatorsProps.stringPropertyNames()) {
            System.out.println("  " + key + " = " + locatorsProps.getProperty(key));
        }
        System.out.println("====== 核验完毕（若上方中文显示正常则编码无误） ======");
    }

    // ==================== 获取候选文案列表 ====================

    /**
     * 获取指定 key 的候选文案列表（用 | 分隔）。
     */
    public List<String> getCandidates(String key) {
        String raw = locatorsProps.getProperty(key, "");
        if (raw.isEmpty()) return Collections.emptyList();
        return Arrays.stream(raw.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public List<String> shelfTabTexts() { return getCandidates("shelf.tab.text"); }
    /**
     * 书封容器的稳定 resource-id 候选集（真机 dump 校准）。
     * 书封 clickable 但 text/content-desc 为空时，靠此 id 识别书籍条目。
     */
    public List<String> bookItemIds() { return getCandidates("book.item.id"); }
    /** 阅读页章节进度文本正则（如「1/21388」），用于在 resource-id 混淆时识别阅读页 */
    public String readerProgressRegex() { return locatorsProps.getProperty("reader.progress.regex", ""); }
    /** 阅读页根容器混淆 resource-id 候选集（真机 dump 校准） */
    public List<String> readerContainerIds() { return getCandidates("reader.container.id"); }
    /** 短剧/视频内容识别正则（命中则选书时跳过，避免误开短剧） */
    public String bookShortDramaRegex() { return locatorsProps.getProperty("book.shortdrama.regex", ""); }
    /** 文字小说内容识别正则（命中则优先选取） */
    public String bookNovelRegex() { return locatorsProps.getProperty("book.novel.regex", ""); }
    public List<String> adEntryTexts() { return getCandidates("ad.entry.text"); }
    public List<String> adWatchTexts() { return getCandidates("ad.watch.text"); }
    public List<String> adCloseTexts() { return getCandidates("ad.close.text"); }
    public List<String> adCloseDescs() { return getCandidates("ad.close.desc"); }
    public List<String> adContinueTexts() { return getCandidates("ad.continue.text"); }
    public List<String> chapterNextTexts() { return getCandidates("chapter.next.text"); }
    public List<String> commonDismissTexts() { return getCandidates("common.dismiss.text"); }
    public List<String> readerMenuTexts() { return getCandidates("reader.menu.text"); }
    public List<String> splashSkipTexts() { return getCandidates("splash.skip.text"); }
    /**
     * D4：每日配额耗尽提示文案候选集。
     * 命中时上层走「优雅收尾」分支（正常结束并汇总统计），而非落入 UNKNOWN 反复重试。
     */
    public List<String> dailyQuotaExhaustedTexts() { return getCandidates("daily.quota.exhausted.text"); }
    public String getRewardRegex() { return rewardRegex; }

    // ==================== LocatorSpec Getter ====================

    public LocatorSpec getShelfTab() { return shelfTab; }
    public LocatorSpec getBookItem() { return bookItem; }
    public LocatorSpec getAdEntry() { return adEntry; }
    public LocatorSpec getAdWatch() { return adWatch; }
    public LocatorSpec getAdClose() { return adClose; }
    public LocatorSpec getAdContinue() { return adContinue; }
    public LocatorSpec getChapterNext() { return chapterNext; }
    public LocatorSpec getCommonDismiss() { return commonDismiss; }

    // ==================== 构建 LocatorSpec ====================

    private LocatorSpec buildShelfTab() {
        List<String> texts = shelfTabTexts();
        By primary = texts.isEmpty() ? By.xpath("//android.widget.TextView") :
                By.xpath("//android.widget.TextView[contains(@text,'" + texts.get(0) + "')]");
        List<By> fallbacks = new ArrayList<>();
        for (int i = 1; i < texts.size(); i++) {
            fallbacks.add(By.xpath("//android.widget.TextView[contains(@text,'" + texts.get(i) + "')]"));
        }
        // 书架 Tab 在底部导航栏，boundsHint: y > 0.9H
        return new LocatorSpec(primary, fallbacks, "y>0.9", false);
    }

    private LocatorSpec buildBookItem() {
        // 书籍条目通常是 RecyclerView 内 clickable=true 的子节点
        By primary = By.xpath("//androidx.recyclerview.widget.RecyclerView//android.widget.FrameLayout[@clickable='true']");
        List<By> fallbacks = Arrays.asList(
                By.xpath("//androidx.recyclerview.widget.RecyclerView//*[@clickable='true']"),
                By.id("com.dragon.read:id/book_item")
        );
        return new LocatorSpec(primary, fallbacks, null, false);
    }

    private LocatorSpec buildAdEntry() {
        List<String> texts = adEntryTexts();
        By primary = texts.isEmpty() ? By.xpath("//android.widget.TextView") :
                By.xpath("//android.widget.TextView[contains(@text,'" + texts.get(0) + "')]");
        List<By> fallbacks = new ArrayList<>();
        for (int i = 1; i < texts.size(); i++) {
            fallbacks.add(By.xpath("//android.widget.TextView[contains(@text,'" + texts.get(i) + "')]"));
        }
        // 底部入口：y > 0.8H
        return new LocatorSpec(primary, fallbacks, "y>0.8", false);
    }

    private LocatorSpec buildAdWatch() {
        List<String> texts = adWatchTexts();
        By primary = texts.isEmpty() ? By.xpath("//android.widget.Button") :
                By.xpath("//android.widget.Button[contains(@text,'" + texts.get(0) + "')]");
        List<By> fallbacks = new ArrayList<>();
        for (int i = 1; i < texts.size(); i++) {
            fallbacks.add(By.xpath("//android.widget.Button[contains(@text,'" + texts.get(i) + "')]"));
        }
        // 也尝试 TextView（某些弹窗用 TextView 模拟按钮）
        if (!texts.isEmpty()) {
            fallbacks.add(By.xpath("//android.widget.TextView[contains(@text,'" + texts.get(0) + "')]"));
        }
        // allowGeometryFallback = false：误点会多看一轮视频、浪费每日配额
        return new LocatorSpec(primary, fallbacks, null, false);
    }

    private LocatorSpec buildAdClose() {
        List<String> texts = adCloseTexts();
        List<String> descs = adCloseDescs();
        By primary = !texts.isEmpty() ?
                By.xpath("//android.widget.TextView[contains(@text,'" + texts.get(0) + "')]") :
                By.xpath("//*[@content-desc='" + (descs.isEmpty() ? "关闭" : descs.get(0)) + "']");
        List<By> fallbacks = new ArrayList<>();
        for (int i = 1; i < texts.size(); i++) {
            fallbacks.add(By.xpath("//android.widget.TextView[contains(@text,'" + texts.get(i) + "')]"));
        }
        for (String desc : descs) {
            fallbacks.add(By.xpath("//*[@content-desc='" + desc + "']"));
        }
        // allowGeometryFallback = true：关闭按钮允许几何兜底（右上角小区域 clickable 节点）
        // boundsHint: 右上角 x>0.8W && y<0.2H
        return new LocatorSpec(primary, fallbacks, "x>0.8,y<0.2", true);
    }

    private LocatorSpec buildAdContinue() {
        List<String> texts = adContinueTexts();
        By primary = texts.isEmpty() ? By.xpath("//android.widget.Button") :
                By.xpath("//android.widget.Button[contains(@text,'" + texts.get(0) + "')]");
        List<By> fallbacks = new ArrayList<>();
        for (int i = 1; i < texts.size(); i++) {
            fallbacks.add(By.xpath("//android.widget.Button[contains(@text,'" + texts.get(i) + "')]"));
        }
        if (!texts.isEmpty()) {
            fallbacks.add(By.xpath("//android.widget.TextView[contains(@text,'" + texts.get(0) + "')]"));
        }
        // allowGeometryFallback = false：误点会多看一轮视频、浪费每日配额
        return new LocatorSpec(primary, fallbacks, null, false);
    }

    private LocatorSpec buildChapterNext() {
        List<String> texts = chapterNextTexts();
        By primary = texts.isEmpty() ? By.xpath("//android.widget.TextView") :
                By.xpath("//android.widget.TextView[contains(@text,'" + texts.get(0) + "')]");
        List<By> fallbacks = new ArrayList<>();
        for (int i = 1; i < texts.size(); i++) {
            fallbacks.add(By.xpath("//android.widget.TextView[contains(@text,'" + texts.get(i) + "')]"));
        }
        return new LocatorSpec(primary, fallbacks, null, false);
    }

    private LocatorSpec buildCommonDismiss() {
        List<String> texts = commonDismissTexts();
        By primary = texts.isEmpty() ? By.xpath("//android.widget.Button") :
                By.xpath("//android.widget.Button[contains(@text,'" + texts.get(0) + "')]");
        List<By> fallbacks = new ArrayList<>();
        for (int i = 1; i < texts.size(); i++) {
            fallbacks.add(By.xpath("//android.widget.Button[contains(@text,'" + texts.get(i) + "')]"));
            fallbacks.add(By.xpath("//android.widget.TextView[contains(@text,'" + texts.get(i) + "')]"));
        }
        return new LocatorSpec(primary, fallbacks, null, false);
    }

    // ==================== LocatorSpec 内部类 ====================

    /**
     * 单个逻辑控件的定位规格。
     * <p>
     * 设计为不可变数据类，包含：
     * - primaryBy: 主定位器（最高优先级）
     * - fallbackBys: 备选定位器列表（主定位器无命中时逐级尝试）
     * - boundsHint: 目标区域比例描述（如 "x>0.8,y<0.2" 表示右上角），用比例而非绝对像素
     * - allowGeometryFallback: 是否允许启用 L2 几何兜底
     *   ad.watch 与 ad.continue 必须为 false（误点会多看一轮视频、浪费每日配额）
     *   只有 ad.close 允许为 true（关闭按钮的误点后果远小于漏点导致卡死）
     */
    public static class LocatorSpec {
        private final By primaryBy;
        private final List<By> fallbackBys;
        private final String boundsHint;
        private final boolean allowGeometryFallback;

        public LocatorSpec(By primaryBy, List<By> fallbackBys, String boundsHint, boolean allowGeometryFallback) {
            this.primaryBy = primaryBy;
            this.fallbackBys = Collections.unmodifiableList(new ArrayList<>(fallbackBys));
            this.boundsHint = boundsHint;
            this.allowGeometryFallback = allowGeometryFallback;
        }

        public By getPrimaryBy() { return primaryBy; }
        public List<By> getFallbackBys() { return fallbackBys; }
        /** 目标区域比例描述，null 表示无区域限制 */
        public String getBoundsHint() { return boundsHint; }
        /** 是否允许 L2 几何兜底 */
        public boolean isAllowGeometryFallback() { return allowGeometryFallback; }

        @Override
        public String toString() {
            return "LocatorSpec{primary=" + primaryBy +
                    ", fallbacks=" + fallbackBys.size() +
                    ", boundsHint='" + boundsHint + '\'' +
                    ", geoFallback=" + allowGeometryFallback + '}';
        }
    }
}
