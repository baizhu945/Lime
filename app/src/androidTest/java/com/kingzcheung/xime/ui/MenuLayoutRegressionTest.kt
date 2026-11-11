package com.kingzcheung.xime.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.keyboard.*
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.viewmodel.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MenuLayoutRegressionTest {
    @get:Rule val rule = createComposeRule()
    private val firstPage = listOf(com.kingzcheung.xime.keyboard.ToolbarButton.FLOAT, com.kingzcheung.xime.keyboard.ToolbarButton.OCR,
        com.kingzcheung.xime.keyboard.ToolbarButton.ARRANGE, com.kingzcheung.xime.keyboard.ToolbarButton.SETTINGS)

    @Test fun compactPortraitGridDoesNotClipOrOverlapAndSettingsRemainReachable() {
        assertGrid(width = 360, height = 180, landscape = false, fontScale = 1f, expected = firstPage)
    }

    @Test fun floatingLargeTextUsesPagesInsteadOfClippingTwoRows() {
        assertGrid(width = 280, height = 150, landscape = false, fontScale = 1.5f, expected = firstPage.take(2))
    }

    @Test fun landscapeGridFitsTheAvailableHeight() {
        assertGrid(width = 800, height = 120, landscape = true, fontScale = 1.2f, expected = firstPage)
    }

    private fun assertGrid(width: Int, height: Int, landscape: Boolean, fontScale: Float, expected: List<com.kingzcheung.xime.keyboard.ToolbarButton>) {
        var settings = 0
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            // 固定逻辑密度便于横屏尺寸在测试窗口内显示；字体缩放仍独立覆盖。
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme {
                    MenuBar(MenuBarState(true, true, 1, Color.Black, Color.DarkGray, Color.White),
                        quickActions(firstPage) { if (it == com.kingzcheung.xime.keyboard.ToolbarButton.SETTINGS) settings++ },
                        Modifier.size(width.dp, height.dp).testTag("menu-panel"))
                }
            }
        }
        rule.onNodeWithContentDescription("关闭菜单").assertDoesNotExist()
        val panel = rule.onNodeWithTag("menu-panel").fetchSemanticsNode().boundsInRoot
        val cards = expected.map { button ->
            val label = button.label
            val card = rule.onNodeWithTag("menu-item:${button.id}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$label 必须在菜单内部", card.top >= panel.top && card.bottom <= panel.bottom && card.left >= panel.left && card.right <= panel.right)
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("$label 文字不可垂直裁切", layouts.isNotEmpty() && layouts.none { it.didOverflowHeight })
            card
        }
        cards.forEachIndexed { i, first -> cards.drop(i + 1).forEach { second ->
            assertFalse("菜单格不可重叠", first.overlaps(second))
        } }
        // Short panels keep one row; narrow large-text panels page horizontally.
        assertTrue(cards.map { it.top }.distinct().size == 1)
        if (expected.size < firstPage.size) rule.onNodeWithTag("menu-pages").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("menu-item:settings").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, settings) }
    }

    @Test fun menuUsesTheSameToolbarReturnSlot() {
        val vm = KeyboardViewModel(ApplicationProvider.getApplicationContext<Application>())
        val feedback = mutableListOf<String>()
        rule.setContent { MaterialTheme {
            KeyboardView(vm, KeyboardUiState(), KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
                onKeyPressDown = { feedback += it }), Modifier.width(360.dp).height(224.dp))
        } }
        val initial = rule.onNodeWithTag("toolbar-leading").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithContentDescription("Lime Logo").performTouchInput { down(center); up() }
        rule.onNodeWithContentDescription("Lime Logo").assertDoesNotExist()
        rule.onAllNodesWithContentDescription("返回").assertCountEquals(1)
        assertEquals(initial, rule.onNodeWithTag("toolbar-leading").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithContentDescription("Lime Logo").assertIsDisplayed()
        rule.onNodeWithTag("keyboard-overlay").assertDoesNotExist()
    }
}
