package com.fanqie.auto.core;

import com.fanqie.auto.core.UiNode.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UiNode} 与内嵌 {@link Rect} 的离线单元测试。
 * <p>
 * 覆盖：bounds 字符串解析（合法/畸形）、中心点/宽高/面积计算、
 * areaRatio 面积占比、containsRatio / centerInRegion 区域判定，
 * 以及 UiNode 各字段的 null 安全与 hasXxx 判定。
 * <p>
 * 纯内存计算，不依赖 driver / snapshot / Appium。
 */
class UiNodeTest {

    // ==================== Rect.parse：合法输入 ====================

    @Test
    @DisplayName("Rect.parse 解析标准 [x1,y1][x2,y2] 格式应得到正确四边")
    void parse_validBounds_returnsCorrectRect() {
        Rect r = Rect.parse("[100,200][300,500]");
        assertEquals(100, r.getLeft());
        assertEquals(200, r.getTop());
        assertEquals(300, r.getRight());
        assertEquals(500, r.getBottom());
        assertFalse(r.equals(Rect.EMPTY), "合法 bounds 不应等于 EMPTY");
    }

    @Test
    @DisplayName("Rect.parse 应容忍坐标周围空格")
    void parse_boundsWithSpaces_isTolerant() {
        Rect r = Rect.parse("  [ 10 , 20 ][ 30 , 40 ]  ");
        // 注意：内部按逗号 split 后 trim，空格应被吸收
        assertEquals(10, r.getLeft());
        assertEquals(20, r.getTop());
        assertEquals(30, r.getRight());
        assertEquals(40, r.getBottom());
    }

    // ==================== Rect.parse：畸形/非法输入必须返回 EMPTY 且不抛异常 ====================

    @Test
    @DisplayName("Rect.parse 对 null / 空串 / 非法格式 / right<left / 全零 均安全返回 EMPTY")
    void parse_malformedBounds_returnsEmptyWithoutThrowing() {
        assertSame(Rect.EMPTY, Rect.parse(null), "null 应返回 EMPTY");
        assertSame(Rect.EMPTY, Rect.parse(""), "空串应返回 EMPTY");
        assertSame(Rect.EMPTY, Rect.parse("   "), "纯空白应返回 EMPTY");
        assertSame(Rect.EMPTY, Rect.parse("100,200,300,500"), "缺少方括号应返回 EMPTY");
        assertSame(Rect.EMPTY, Rect.parse("[100,200]"), "只有一段坐标应返回 EMPTY");
        assertSame(Rect.EMPTY, Rect.parse("[abc,def][ghi,jkl]"), "非数字应返回 EMPTY");
        assertSame(Rect.EMPTY, Rect.parse("[300,500][100,200]"), "right<left 应返回 EMPTY");
        assertSame(Rect.EMPTY, Rect.parse("[0,0][0,0]"), "全零无意义应返回 EMPTY");
    }

    // ==================== 几何计算 ====================

    @Test
    @DisplayName("centerX/centerY/width/height/area 计算正确")
    void geometry_computations_areCorrect() {
        Rect r = new Rect(100, 200, 300, 500);
        assertEquals(200, r.centerX(), "centerX = (100+300)/2");
        assertEquals(350, r.centerY(), "centerY = (200+500)/2");
        assertEquals(200, r.width());
        assertEquals(300, r.height());
        assertEquals(200L * 300L, r.area());
    }

    @Test
    @DisplayName("areaRatio 计算面积占屏比，且对非正屏幕尺寸返回 0")
    void areaRatio_computesProportion_andGuardsNonPositiveScreen() {
        Rect r = new Rect(0, 0, 540, 1170); // 恰好半屏（1080x2340）
        double ratio = r.areaRatio(1080, 2340);
        assertEquals(0.25, ratio, 1e-9, "1/4 屏面积占比应为 0.25");

        assertEquals(0.0, r.areaRatio(0, 2340), 1e-9, "屏幕宽为 0 时应返回 0，不抛异常");
        assertEquals(0.0, r.areaRatio(1080, -1), 1e-9, "屏幕高为负时应返回 0");
    }

    @Test
    @DisplayName("centerInRegion 判定中心点是否落在给定比例区域内（右上角关闭按钮场景）")
    void centerInRegion_topRightDetection() {
        // 屏幕 1080x2340，节点位于右上角
        Rect topRight = new Rect(980, 120, 1050, 190);
        assertTrue(topRight.centerInRegion(0.8, 1.0, 0.0, 0.2, 1080, 2340),
                "右上角节点中心应落在 x>0.8W && y<0.2H 区域");

        // 屏幕中央的节点不应命中右上角区域
        Rect center = new Rect(440, 1070, 640, 1270);
        assertFalse(center.centerInRegion(0.8, 1.0, 0.0, 0.2, 1080, 2340),
                "屏幕中央节点不应命中右上角区域");
    }

    @Test
    @DisplayName("containsRatio 判定比例坐标点是否落在矩形内")
    void containsRatio_hitsInsidePoint() {
        Rect r = new Rect(100, 100, 300, 300);
        // 屏幕 1000x1000，比例 (0.2,0.2) → 像素 (200,200) 落在矩形内
        assertTrue(r.containsRatio(0.2, 0.2, 1000, 1000));
        // 比例 (0.9,0.9) → 像素 (900,900) 不在矩形内
        assertFalse(r.containsRatio(0.9, 0.9, 1000, 1000));
    }

    @Test
    @DisplayName("Rect.equals / hashCode 基于四边值相等")
    void rectEquals_andHashCode_byValue() {
        Rect a = new Rect(1, 2, 3, 4);
        Rect b = new Rect(1, 2, 3, 4);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(new Rect(1, 2, 3, 5)));
    }

    // ==================== UiNode 字段与 null 安全 ====================

    @Test
    @DisplayName("UiNode 构造对 null 字符串字段做空安全兜底为空串")
    void uiNode_nullSafeFields() {
        UiNode node = new UiNode(null, null, null, null, null,
                "[10,20][30,40]", true, 1, 0, 5);
        assertEquals("", node.getClazz());
        assertEquals("", node.getText());
        assertEquals("", node.getContentDesc());
        assertEquals("", node.getResourceId());
        assertEquals("", node.getPackageName());
        assertTrue(node.isClickable());
        assertEquals(1, node.getDepth());
        assertEquals(0, node.getParentIndex());
        assertEquals(5, node.getIndex());
        assertFalse(node.hasText());
        assertFalse(node.hasContentDesc());
        assertFalse(node.hasResourceId());
    }

    @Test
    @DisplayName("UiNode 合法 bounds 时 isHasBounds=true 且 getBounds 可计算中心")
    void uiNode_validBounds_hasBoundsTrue() {
        UiNode node = new UiNode("android.widget.TextView", "关闭", "close",
                "com.dragon.read:id/btn", "com.dragon.read",
                "[900,120][1000,220]", true, 2, 1, 3);
        assertTrue(node.isHasBounds());
        assertTrue(node.hasText());
        assertTrue(node.hasContentDesc());
        assertTrue(node.hasResourceId());
        assertEquals(950, node.getBounds().centerX());
        assertEquals(170, node.getBounds().centerY());
    }

    @Test
    @DisplayName("UiNode 畸形 bounds 时 isHasBounds=false 且 bounds 为 EMPTY，不抛异常")
    void uiNode_malformedBounds_hasBoundsFalse() {
        UiNode node = new UiNode("android.view.View", "", "", "", "",
                "not-a-bounds", false, 0, -1, 0);
        assertFalse(node.isHasBounds());
        assertSame(Rect.EMPTY, node.getBounds());
        assertFalse(node.isClickable());
    }
}
