package com.fanqie.auto.core;

import com.fanqie.auto.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UiSnapshot} 的离线单元测试。
 * <p>
 * 用录制的 fixture XML 回放，验证纯内存查询 API：
 * findByTextContains / findByResourceId / findByContentDescContains /
 * findClickableInRegion / nearestClickableAncestor / extractByRegex，
 * 以及畸形 XML 的健壮性（不抛异常、isParseFailed=true、size=0）。
 * <p>
 * 全程不连接真机、不启动 Appium session。
 */
class UiSnapshotTest {

    private UiSnapshot snapshotOf(String fixtureName) {
        return new UiSnapshot(TestFixtures.load(fixtureName),
                TestFixtures.SCREEN_WIDTH, TestFixtures.SCREEN_HEIGHT);
    }

    // ==================== 基本解析 ====================

    @Test
    @DisplayName("正常 XML 解析成功：parseFailed=false，节点数>0，xmlLength 与原文一致")
    void parse_validXml_succeeds() {
        String xml = TestFixtures.load("bookshelf.xml");
        UiSnapshot snap = new UiSnapshot(xml, 1080, 2340);
        assertFalse(snap.isParseFailed());
        assertTrue(snap.size() > 0);
        assertEquals(xml.length(), snap.xmlLength());
        assertEquals(1080, snap.getScreenWidth());
        assertEquals(2340, snap.getScreenHeight());
        assertNotNull(snap.nodes());
    }

    @Test
    @DisplayName("畸形/截断 XML 不抛异常：isParseFailed=true，size=0")
    void parse_malformedXml_doesNotThrow() {
        UiSnapshot snap = snapshotOf("malformed.xml");
        assertTrue(snap.isParseFailed(), "畸形 XML 应标记 parseFailed");
        assertEquals(0, snap.size(), "解析失败应返回空节点表");
        assertTrue(snap.nodes().isEmpty());
    }

    @Test
    @DisplayName("null / 空 XML 安全：不抛异常，size=0，parseFailed=false（空串走空表分支）")
    void parse_nullOrEmptyXml_isSafe() {
        UiSnapshot nullSnap = new UiSnapshot(null, 1080, 2340);
        assertEquals(0, nullSnap.size());
        assertFalse(nullSnap.isParseFailed());

        UiSnapshot emptySnap = new UiSnapshot("", 1080, 2340);
        assertEquals(0, emptySnap.size());
        assertFalse(emptySnap.isParseFailed());
    }

    @Test
    @DisplayName("nodes() 返回不可变列表，外部修改应抛 UnsupportedOperationException")
    void nodes_areImmutable() {
        UiSnapshot snap = snapshotOf("reader.xml");
        assertThrows(UnsupportedOperationException.class,
                () -> snap.nodes().add(null),
                "nodes() 应为不可变列表");
    }

    // ==================== findByTextContains ====================

    @Test
    @DisplayName("findByTextContains 命中含候选文案的节点；空/null 候选集返回空表")
    void findByTextContains_matchesCandidates() {
        UiSnapshot snap = snapshotOf("reader_menu.xml");
        List<UiNode> hits = snap.findByTextContains(Arrays.asList("目录", "夜间"));
        assertFalse(hits.isEmpty(), "菜单文案应命中");
        assertTrue(hits.stream().anyMatch(n -> n.getText().contains("目录")));

        assertTrue(snap.findByTextContains(null).isEmpty());
        assertTrue(snap.findByTextContains(Collections.emptyList()).isEmpty());
        assertTrue(snap.findByTextContains(Collections.singletonList("不存在的文案")).isEmpty());
    }

    // ==================== findByResourceId ====================

    @Test
    @DisplayName("findByResourceId 精确匹配与包含匹配行为正确")
    void findByResourceId_exactAndContains() {
        UiSnapshot snap = snapshotOf("bookshelf.xml");

        // 包含匹配：'recycler' 命中 'com.dragon.read:id/book_recycler'
        List<UiNode> contains = snap.findByResourceId("recycler", false);
        assertFalse(contains.isEmpty());
        assertTrue(contains.get(0).getResourceId().contains("recycler"));

        // 精确匹配：完整 id 命中
        List<UiNode> exact = snap.findByResourceId("com.dragon.read:id/tab_shelf", true);
        assertEquals(1, exact.size());

        // 精确匹配：部分 id 不命中
        assertTrue(snap.findByResourceId("recycler", true).isEmpty(),
                "精确模式下部分字符串不应命中");

        // null / 空 id 返回空表
        assertTrue(snap.findByResourceId(null, false).isEmpty());
        assertTrue(snap.findByResourceId("", false).isEmpty());
    }

    // ==================== findByContentDescContains ====================

    @Test
    @DisplayName("findByContentDescContains 命中含候选描述的节点")
    void findByContentDescContains_matches() {
        UiSnapshot snap = snapshotOf("splash_ad.xml");
        // splash_ad.xml 中 ImageView 的 content-desc="广告"
        List<UiNode> hits = snap.findByContentDescContains(Collections.singletonList("广告"));
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).getContentDesc().contains("广告"));

        assertTrue(snap.findByContentDescContains(null).isEmpty());
        assertTrue(snap.findByContentDescContains(Collections.emptyList()).isEmpty());
    }

    // ==================== findClickableInRegion ====================

    @Test
    @DisplayName("findClickableInRegion 命中右上角小面积 clickable 节点，排除大面积与非 clickable")
    void findClickableInRegion_topRightSmall() {
        UiSnapshot snap = snapshotOf("ad_close_ready.xml");
        // 右上角区域 x∈[0.8,1.0], y∈[0.0,0.2]，最大面积占比 0.15
        List<UiNode> hits = snap.findClickableInRegion(0.8, 1.0, 0.0, 0.2, 0.15);
        assertEquals(1, hits.size(), "应只命中右上角关闭按钮 ImageView");
        assertTrue(hits.get(0).isClickable());
        assertTrue(hits.get(0).getBounds().centerInRegion(0.8, 1.0, 0.0, 0.2, 1080, 2340));

        // 全屏容器虽 clickable=false 或面积过大，均不应命中
        UiSnapshot videoSnap = snapshotOf("ad_video_playing.xml");
        assertTrue(videoSnap.findClickableInRegion(0.8, 1.0, 0.0, 0.2, 0.15).isEmpty(),
                "视频播放页无右上角 clickable 节点");
    }

    // ==================== nearestClickableAncestor ====================

    @Test
    @DisplayName("nearestClickableAncestor 沿 parentIndex 回溯至最近 clickable 祖先")
    void nearestClickableAncestor_walksUp() {
        UiSnapshot snap = snapshotOf("nested_clickable.xml");
        // 找到 text="按钮文字" 的不可点击 TextView
        List<UiNode> textNodes = snap.findByTextContains(Collections.singletonList("按钮文字"));
        assertEquals(1, textNodes.size());
        UiNode leaf = textNodes.get(0);
        assertFalse(leaf.isClickable());

        Optional<UiNode> ancestor = snap.nearestClickableAncestor(leaf);
        assertTrue(ancestor.isPresent(), "应找到可点击祖先");
        assertTrue(ancestor.get().isClickable());
        assertEquals("android.widget.LinearLayout", ancestor.get().getClazz(),
                "应跳过不可点击的 FrameLayout，回溯到 clickable 的 LinearLayout");
    }

    @Test
    @DisplayName("nearestClickableAncestor 对 null 与无可点击祖先的情况返回 empty")
    void nearestClickableAncestor_edgeCases() {
        UiSnapshot snap = snapshotOf("reader.xml");
        assertFalse(snap.nearestClickableAncestor(null).isPresent(), "null 入参应返回 empty");

        // reader.xml 全树无 clickable 节点，回溯应失败
        List<UiNode> content = snap.findByResourceId("reader_content", false);
        assertFalse(content.isEmpty());
        assertFalse(snap.nearestClickableAncestor(content.get(0)).isPresent(),
                "无可点击祖先时应返回 empty");
    }

    // ==================== extractByRegex ====================

    @Test
    @DisplayName("extractByRegex 从 text/content-desc 提取「N分钟」匹配")
    void extractByRegex_findsRewardPattern() {
        UiSnapshot snap = snapshotOf("reward_granted.xml");
        List<String> matches = snap.extractByRegex("(\\d+)\\s*分钟");
        assertFalse(matches.isEmpty(), "应匹配到「30分钟」");
        assertTrue(matches.contains("30分钟"));

        assertTrue(snap.extractByRegex(null).isEmpty());
        assertTrue(snap.extractByRegex("").isEmpty());
    }
}
