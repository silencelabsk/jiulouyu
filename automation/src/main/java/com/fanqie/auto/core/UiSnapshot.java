package com.fanqie.auto.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 一次 getPageSource() 的内存快照表示。
 * <p>
 * 性能核心设计：
 * - 由一次 driver.getPageSource() 构造，用 JDK 内置 DocumentBuilderFactory 解析一次 XML，
 *   深度优先遍历产出 List&lt;UiNode&gt;，之后全部查询都是纯内存操作，零 RPC。
 * - 同一 tick 内快照缓存复用，杜绝重复抓取（由 StateDetector.tick() 保证）。
 * - 单 tick 状态识别目标约 0.5s（对比"每步 uiautomator dump"的 2-6s，快 4-10 倍）。
 * <p>
 * 安全设计：
 * - 必须防御 XXE：setFeature("disallow-doctype-decl", true) 等标准加固。
 * - DocumentBuilderFactory 非线程安全，每次解析新建实例。
 * - XML 可能畸形/截断（设备端偶发），解析失败时不抛异常，返回空快照并置 parseFailed=true，
 *   让上层状态机走 UNKNOWN 分支。
 */
public final class UiSnapshot {

    private final String rawXml;
    private final int screenWidth;
    private final int screenHeight;
    private final List<UiNode> nodes;
    private final boolean parseFailed;
    private final long parseElapsedMs;

    /**
     * 构造快照并解析 XML。
     *
     * @param rawXml       driver.getPageSource() 返回的原始 XML 字符串
     * @param screenWidth  屏幕宽度像素（由 driver.manage().window().getSize() 获取）
     * @param screenHeight 屏幕高度像素
     */
    public UiSnapshot(String rawXml, int screenWidth, int screenHeight) {
        this.rawXml = rawXml == null ? "" : rawXml;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;

        long start = System.currentTimeMillis();
        List<UiNode> parsed = new ArrayList<>();
        boolean failed = false;

        try {
            parsed = parseXml(this.rawXml);
        } catch (Exception e) {
            // XML 畸形/截断（设备端偶发），不抛异常，返回空快照
            failed = true;
            System.err.println("[UiSnapshot] XML 解析失败（将返回空快照）: " + e.getMessage());
        }

        this.nodes = Collections.unmodifiableList(parsed);
        this.parseFailed = failed;
        this.parseElapsedMs = System.currentTimeMillis() - start;
    }

    /**
     * 防御 XXE 的安全 XML 解析。
     * DocumentBuilderFactory 非线程安全，每次新建实例。
     */
    private List<UiNode> parseXml(String xml) throws Exception {
        if (xml.isEmpty()) return Collections.emptyList();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // === XXE 防御：标准加固措施 ===
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));
        doc.getDocumentElement().normalize();

        List<UiNode> result = new ArrayList<>();
        // 深度优先遍历，维护 depth 和 parentIndex
        traverseNode(doc.getDocumentElement(), -1, 0, result);
        return result;
    }

    /**
     * 深度优先遍历 DOM 树，将每个 Element 转换为 UiNode。
     * 正确维护 depth（层级深度）与 parentIndex（父节点在 List 中的下标，根节点为 -1）。
     */
    private void traverseNode(Node domNode, int parentIndex, int depth, List<UiNode> result) {
        if (domNode.getNodeType() != Node.ELEMENT_NODE) return;

        Element elem = (Element) domNode;
        int myIndex = result.size();

        // 提取属性
        String clazz = elem.getAttribute("class");
        String text = elem.getAttribute("text");
        String contentDesc = elem.getAttribute("content-desc");
        String resourceId = elem.getAttribute("resource-id");
        String packageName = elem.getAttribute("package");
        String boundsStr = elem.getAttribute("bounds");
        String clickableStr = elem.getAttribute("clickable");
        boolean clickable = "true".equalsIgnoreCase(clickableStr);

        UiNode uiNode = new UiNode(clazz, text, contentDesc, resourceId, packageName,
                boundsStr, clickable, depth, parentIndex, myIndex);
        result.add(uiNode);

        // 递归处理子节点
        NodeList children = elem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            traverseNode(children.item(i), myIndex, depth + 1, result);
        }
    }

    // ==================== 内存查询 API（纯内存操作，零 RPC） ====================

    /**
     * 按 text 包含匹配查找节点（任一候选文案命中即返回）。
     * 文案匹配使用 contains 而非 equals，因为 App 可能在文案前后附加空格或装饰字符。
     */
    public List<UiNode> findByTextContains(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        return nodes.stream()
                .filter(n -> {
                    String t = n.getText();
                    if (t.isEmpty()) return false;
                    return candidates.stream().anyMatch(t::contains);
                })
                .collect(Collectors.toList());
    }

    /**
     * 按 content-desc 包含匹配查找节点。
     * 穿山甲 SDK 常给关闭按钮设置 content-desc 而非 text。
     */
    public List<UiNode> findByContentDescContains(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        return nodes.stream()
                .filter(n -> {
                    String d = n.getContentDesc();
                    if (d.isEmpty()) return false;
                    return candidates.stream().anyMatch(d::contains);
                })
                .collect(Collectors.toList());
    }

    /**
     * 按 resource-id 查找节点。
     *
     * @param resourceId 目标 resource-id
     * @param exact      true=精确匹配，false=包含匹配
     */
    public List<UiNode> findByResourceId(String resourceId, boolean exact) {
        if (resourceId == null || resourceId.isEmpty()) return Collections.emptyList();
        return nodes.stream()
                .filter(n -> {
                    String rid = n.getResourceId();
                    if (rid.isEmpty()) return false;
                    return exact ? rid.equals(resourceId) : rid.contains(resourceId);
                })
                .collect(Collectors.toList());
    }

    /**
     * 在指定屏幕比例区域内查找可点击节点。
     * 用于 L2 几何兜底：如筛选"右上角小面积 clickable 节点"作为关闭按钮。
     *
     * @param xRatioMin   区域左边界比例 (0.0~1.0)
     * @param xRatioMax   区域右边界比例
     * @param yRatioMin   区域上边界比例
     * @param yRatioMax   区域下边界比例
     * @param maxAreaRatio 最大面积占比（过滤掉全屏容器等大面积节点）
     */
    public List<UiNode> findClickableInRegion(double xRatioMin, double xRatioMax,
                                              double yRatioMin, double yRatioMax,
                                              double maxAreaRatio) {
        return nodes.stream()
                .filter(UiNode::isClickable)
                .filter(UiNode::isHasBounds)
                .filter(n -> n.getBounds().centerInRegion(xRatioMin, xRatioMax,
                        yRatioMin, yRatioMax, screenWidth, screenHeight))
                .filter(n -> n.getBounds().areaRatio(screenWidth, screenHeight) <= maxAreaRatio)
                .collect(Collectors.toList());
    }

    /**
     * 用正则表达式从所有节点的 text 中提取匹配内容。
     * 供 readGainedMinutes() 提取「N 分钟」使用。
     *
     * @return 所有匹配的完整结果列表
     */
    public List<String> extractByRegex(String regex) {
        if (regex == null || regex.isEmpty()) return Collections.emptyList();
        Pattern pattern = Pattern.compile(regex);
        List<String> results = new ArrayList<>();
        for (UiNode node : nodes) {
            if (node.hasText()) {
                Matcher m = pattern.matcher(node.getText());
                while (m.find()) {
                    results.add(m.group());
                }
            }
            // 也搜索 content-desc
            if (node.hasContentDesc()) {
                Matcher m = pattern.matcher(node.getContentDesc());
                while (m.find()) {
                    results.add(m.group());
                }
            }
        }
        return results;
    }

    /**
     * 沿 parentIndex 向上回溯至最近的 clickable=true 祖先。
     * <p>
     * 为什么需要：文案常挂在不可点的 TextView 上，而其容器（FrameLayout/LinearLayout）才是可点击的。
     * 点击时应取可点击祖先的 bounds 中心坐标。
     *
     * @param node 起始节点
     * @return 最近的可点击祖先，找不到返回 Optional.empty()
     */
    public Optional<UiNode> nearestClickableAncestor(UiNode node) {
        if (node == null) return Optional.empty();
        int parentIdx = node.getParentIndex();
        // 防止无限循环（parentIndex 异常时）
        int maxIterations = nodes.size();
        int iterations = 0;
        while (parentIdx >= 0 && parentIdx < nodes.size() && iterations < maxIterations) {
            UiNode parent = nodes.get(parentIdx);
            if (parent.isClickable()) {
                return Optional.of(parent);
            }
            parentIdx = parent.getParentIndex();
            iterations++;
        }
        return Optional.empty();
    }

    // ==================== 基础访问器 ====================

    /** 所有节点的不可变列表 */
    public List<UiNode> nodes() { return nodes; }
    /** 节点总数 */
    public int size() { return nodes.size(); }
    /** 原始 XML 字符串 */
    public String rawXml() { return rawXml; }
    /** XML 解析耗时（毫秒） */
    public long parseElapsedMs() { return parseElapsedMs; }
    /** XML 字符串长度 */
    public int xmlLength() { return rawXml.length(); }
    /** XML 是否解析失败 */
    public boolean isParseFailed() { return parseFailed; }
    /** 屏幕宽度 */
    public int getScreenWidth() { return screenWidth; }
    /** 屏幕高度 */
    public int getScreenHeight() { return screenHeight; }

    @Override
    public String toString() {
        return "UiSnapshot{nodes=" + nodes.size() +
                ", parseFailed=" + parseFailed +
                ", parseMs=" + parseElapsedMs +
                ", xmlLen=" + rawXml.length() +
                ", screen=" + screenWidth + "x" + screenHeight + '}';
    }
}
