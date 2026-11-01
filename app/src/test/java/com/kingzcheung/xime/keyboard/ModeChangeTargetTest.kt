package com.kingzcheung.xime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 底排 ?123 单键的纯逻辑：偏好默认「数字」、认不出的值回默认、长按两项的映射和路由。
 * 界面只负责把这里的结论接到既有按键路由上。
 */
class ModeChangeTargetTest {
    @Test fun missingOrUnknownPreferenceFallsBackToNumber() {
        // 新装、旧版本没写过、或别的输入法写进来的值，都不能把键按坏。
        for (value in listOf(null, "", "number_typo", "NUMBER", "common", "0")) {
            assertEquals(value, ModeChangeTarget.NUMBER, modeChangeTargetFromPreference(value))
        }
        assertEquals(ModeChangeTarget.DEFAULT, ModeChangeTarget.NUMBER)
    }

    @Test fun preferenceRoundTripKeepsBothPages() {
        for (target in ModeChangeTarget.entries) {
            assertEquals(target, modeChangeTargetFromPreference(target.prefValue))
        }
        assertEquals("number", ModeChangeTarget.NUMBER.prefValue)
        assertEquals("common_symbol", ModeChangeTarget.COMMON_SYMBOL.prefValue)
    }

    @Test fun longPressOffersNumberThenCommonSymbol() {
        assertEquals(listOf("数字", "常用符号"), MODE_CHANGE_LONG_PRESS_LABELS)
        assertEquals(ModeChangeTarget.NUMBER, modeChangeTargetForSelection("数字"))
        assertEquals(ModeChangeTarget.COMMON_SYMBOL, modeChangeTargetForSelection("常用符号"))
        // 配置里写的是动作名时也认（长按气泡的标签来自用户配置）。
        assertEquals(ModeChangeTarget.NUMBER, modeChangeTargetForSelection(MODE_CHANGE_NUMBER))
        assertEquals(ModeChangeTarget.COMMON_SYMBOL, modeChangeTargetForSelection(MODE_CHANGE_COMMON_SYMBOL))
        // 认不出的选项不猜、不发键。
        assertNull(modeChangeTargetForSelection(""))
        assertNull(modeChangeTargetForSelection("符号"))
        assertNull(modeChangeActionForSelection(""))
    }

    @Test fun selectingAnItemSendsTheExistingRoutes() {
        assertEquals("mode_change_number", modeChangeActionForSelection("数字"))
        assertEquals("mode_change_common_symbol", modeChangeActionForSelection("常用符号"))
        // 既有路由的名字不能变：老配置、老测试和输入法服务都按这两个串认。
        assertEquals("mode_change_number", ModeChangeTarget.NUMBER.action)
        assertEquals("mode_change_common_symbol", ModeChangeTarget.COMMON_SYMBOL.action)
        assertEquals("mode_change_last", MODE_CHANGE_LAST)
    }

    @Test fun eachTargetMapsToItsPanel() {
        assertEquals(PanelType.NUMBER, ModeChangeTarget.NUMBER.panel)
        assertEquals(PanelType.COMMON_SYMBOL, ModeChangeTarget.COMMON_SYMBOL.panel)
    }
}
