package com.fanqie.auto.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PageState} 便捷判定方法的离线单元测试。
 * <p>
 * 重点覆盖 C3 新增的 {@link PageState#isReaderFamily()}：READER 与其可自愈子态 READER_MENU
 * 同属「阅读页家族」，task 层与检测层据此统一语义，避免工具栏短暂可见被判异常并 back() 退出。
 */
class PageStateTest {

    @Test
    @DisplayName("C3：isReaderFamily 对 READER 与 READER_MENU 均为 true")
    void isReaderFamily_readerAndMenu() {
        assertTrue(PageState.READER.isReaderFamily(), "READER 属于阅读页家族");
        assertTrue(PageState.READER_MENU.isReaderFamily(), "READER_MENU 是 READER 的可自愈子态");
    }

    @Test
    @DisplayName("C3：isReaderFamily 对非阅读页状态均为 false")
    void isReaderFamily_otherStatesFalse() {
        assertFalse(PageState.BOOKSHELF.isReaderFamily());
        assertFalse(PageState.UNKNOWN.isReaderFamily());
        assertFalse(PageState.AD_CLOSE_READY.isReaderFamily());
        assertFalse(PageState.SPLASH_AD.isReaderFamily());
        assertFalse(PageState.APP_LAUNCHING.isReaderFamily());
        assertFalse(PageState.COMMON_POPUP.isReaderFamily());
        assertFalse(PageState.RECOVERY_NEEDED.isReaderFamily());
    }

    @Test
    @DisplayName("isAbnormal 仅覆盖 UNKNOWN / RECOVERY_NEEDED / COMMON_POPUP")
    void isAbnormal_coverage() {
        assertTrue(PageState.UNKNOWN.isAbnormal());
        assertTrue(PageState.RECOVERY_NEEDED.isAbnormal());
        assertTrue(PageState.COMMON_POPUP.isAbnormal());
        // N2 相关：新增状态不在 isAbnormal 覆盖内，故第1级恢复判定改用显式锚点白名单
        assertFalse(PageState.READER_MENU.isAbnormal(), "READER_MENU 不属于 isAbnormal 覆盖范围");
        assertFalse(PageState.SPLASH_AD.isAbnormal());
        assertFalse(PageState.READER.isAbnormal());
    }

    @Test
    @DisplayName("isAdFlowState 覆盖广告流程各状态且不含阅读页家族")
    void isAdFlowState_coverage() {
        assertTrue(PageState.AD_ENTRY_PROMPT.isAdFlowState());
        assertTrue(PageState.AD_CONFIRM_DIALOG.isAdFlowState());
        assertTrue(PageState.AD_VIDEO_PLAYING.isAdFlowState());
        assertTrue(PageState.AD_CLOSE_READY.isAdFlowState());
        assertTrue(PageState.AD_CONTINUE_PROMPT.isAdFlowState());
        assertTrue(PageState.AD_REWARD_GRANTED.isAdFlowState());
        assertFalse(PageState.READER.isAdFlowState());
        assertFalse(PageState.READER_MENU.isAdFlowState());
    }
}
