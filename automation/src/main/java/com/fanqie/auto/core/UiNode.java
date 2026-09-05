package com.fanqie.auto.core;

/**
 * UI 树中单个节点的不可变数据表示。
 * <p>
 * 由 UiSnapshot 解析 XML 后构造，承载该节点的全部关键属性。
 * 所有字段在构造后不可变，线程安全，可被多个查询方法共享。
 * <p>
 * bounds 字符串格式为 "[x1,y1][x2,y2]"，解析必须健壮：
 * 空字符串、null、非法格式、负数、right<left 等情况都不能抛异常，
 * 应返回 Rect.EMPTY 并标记 hasBounds=false。
 */
public final class UiNode {

    private final String clazz;
    private final String text;
    private final String contentDesc;
    private final String resourceId;
    private final String packageName;
    private final Rect bounds;
    private final boolean hasBounds;
    private final boolean clickable;
    private final int depth;
    private final int parentIndex;
    private final int index;

    public UiNode(String clazz, String text, String contentDesc, String resourceId,
                  String packageName, String boundsStr, boolean clickable,
                  int depth, int parentIndex, int index) {
        this.clazz = nullSafe(clazz);
        this.text = nullSafe(text);
        this.contentDesc = nullSafe(contentDesc);
        this.resourceId = nullSafe(resourceId);
        this.packageName = nullSafe(packageName);
        this.clickable = clickable;
        this.depth = depth;
        this.parentIndex = parentIndex;
        this.index = index;

        // 健壮解析 bounds：任何异常情况返回 EMPTY，不抛异常
        Rect parsed = Rect.parse(boundsStr);
        this.bounds = parsed;
        this.hasBounds = !parsed.equals(Rect.EMPTY);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    // ==================== Getter ====================

    public String getClazz() { return clazz; }
    public String getText() { return text; }
    public String getContentDesc() { return contentDesc; }
    public String getResourceId() { return resourceId; }
    public String getPackageName() { return packageName; }
    public Rect getBounds() { return bounds; }
    public boolean isHasBounds() { return hasBounds; }
    public boolean isClickable() { return clickable; }
    public int getDepth() { return depth; }
    /** 父节点在 List<UiNode> 中的下标，根节点为 -1 */
    public int getParentIndex() { return parentIndex; }
    /** 自身在 List<UiNode> 中的下标 */
    public int getIndex() { return index; }

    /** text 是否非空 */
    public boolean hasText() { return !text.isEmpty(); }
    /** content-desc 是否非空 */
    public boolean hasContentDesc() { return !contentDesc.isEmpty(); }
    /** resource-id 是否非空 */
    public boolean hasResourceId() { return !resourceId.isEmpty(); }

    @Override
    public String toString() {
        return "UiNode{clazz='" + clazz + '\'' +
                ", text='" + (text.length() > 30 ? text.substring(0, 30) + "..." : text) + '\'' +
                ", desc='" + (contentDesc.length() > 20 ? contentDesc.substring(0, 20) + "..." : contentDesc) + '\'' +
                ", resId='" + resourceId + '\'' +
                ", clickable=" + clickable +
                ", bounds=" + bounds +
                ", depth=" + depth +
                ", idx=" + index + '}';
    }

    // ==================== Rect 内嵌类 ====================

    /**
     * 矩形区域，表示 UI 节点的屏幕坐标范围。
     * <p>
     * 提供 centerX/centerY/width/height/area/areaRatio/containsRatio 等计算方法，
     * 供 StateDetector 的几何特征匹配与 GestureSupport 的比例坐标计算使用。
     */
    public static final class Rect {
        /** 空矩形单例，用于表示 bounds 解析失败或无 bounds 的情况 */
        public static final Rect EMPTY = new Rect(0, 0, 0, 0);

        private final int left;
        private final int top;
        private final int right;
        private final int bottom;

        public Rect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        /**
         * 从 "[x1,y1][x2,y2]" 格式字符串解析 Rect。
         * 健壮性要求：空字符串、null、非法格式、负数、right<left 等都不能抛异常。
         */
        public static Rect parse(String boundsStr) {
            if (boundsStr == null || boundsStr.trim().isEmpty()) {
                return EMPTY;
            }
            try {
                // 期望格式: [x1,y1][x2,y2]
                String s = boundsStr.trim();
                if (!s.startsWith("[") || !s.contains("][")) {
                    return EMPTY;
                }
                // 分割为两部分
                int mid = s.indexOf("][");
                String part1 = s.substring(1, mid);       // "x1,y1"
                String part2 = s.substring(mid + 2, s.length() - 1); // "x2,y2"

                String[] coords1 = part1.split(",");
                String[] coords2 = part2.split(",");
                if (coords1.length != 2 || coords2.length != 2) {
                    return EMPTY;
                }

                int x1 = Integer.parseInt(coords1[0].trim());
                int y1 = Integer.parseInt(coords1[1].trim());
                int x2 = Integer.parseInt(coords2[0].trim());
                int y2 = Integer.parseInt(coords2[1].trim());

                // 防御 right < left 或 bottom < top 的畸形数据
                if (x2 < x1 || y2 < y1) {
                    return EMPTY;
                }
                // 防御全零（无意义）
                if (x1 == 0 && y1 == 0 && x2 == 0 && y2 == 0) {
                    return EMPTY;
                }
                return new Rect(x1, y1, x2, y2);
            } catch (Exception e) {
                // 任何解析异常都安全返回 EMPTY，不向上抛出
                return EMPTY;
            }
        }

        public int getLeft() { return left; }
        public int getTop() { return top; }
        public int getRight() { return right; }
        public int getBottom() { return bottom; }

        public int centerX() { return (left + right) / 2; }
        public int centerY() { return (top + bottom) / 2; }
        public int width() { return right - left; }
        public int height() { return bottom - top; }
        public long area() { return (long) width() * height(); }

        /**
         * 计算该矩形面积占屏幕总面积的比例。
         * 用于几何特征匹配：如"areaRatio < 0.15 的小节点"筛选关闭按钮。
         */
        public double areaRatio(int screenWidth, int screenHeight) {
            if (screenWidth <= 0 || screenHeight <= 0) return 0.0;
            return (double) area() / ((double) screenWidth * screenHeight);
        }

        /**
         * 判断给定的比例坐标点是否落在本矩形内。
         * @param xRatio 相对于屏幕宽度的 x 比例 (0.0~1.0)
         * @param yRatio 相对于屏幕高度的 y 比例 (0.0~1.0)
         * @param screenWidth  屏幕宽度像素
         * @param screenHeight 屏幕高度像素
         */
        public boolean containsRatio(double xRatio, double yRatio, int screenWidth, int screenHeight) {
            int px = (int) (xRatio * screenWidth);
            int py = (int) (yRatio * screenHeight);
            return px >= left && px <= right && py >= top && py <= bottom;
        }

        /**
         * 判断中心点是否落在给定的比例区域内。
         * 用于 StateDetector 的几何特征判定（如"右上角关闭按钮"）。
         */
        public boolean centerInRegion(double xRatioMin, double xRatioMax,
                                      double yRatioMin, double yRatioMax,
                                      int screenWidth, int screenHeight) {
            double cx = (double) centerX() / screenWidth;
            double cy = (double) centerY() / screenHeight;
            return cx >= xRatioMin && cx <= xRatioMax && cy >= yRatioMin && cy <= yRatioMax;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Rect)) return false;
            Rect r = (Rect) o;
            return left == r.left && top == r.top && right == r.right && bottom == r.bottom;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * (31 * left + top) + right) + bottom;
        }

        @Override
        public String toString() {
            return "[" + left + "," + top + "][" + right + "," + bottom + "]";
        }
    }
}
