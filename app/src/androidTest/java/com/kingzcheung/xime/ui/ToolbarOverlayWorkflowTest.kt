package com.kingzcheung.xime.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ToolbarOverlayWorkflowTest {
    @get:Rule val rule = createComposeRule()
    @Test fun transparentPanelsStayBelowToolbarAndSecondTapReturnsWithEditingFeedback() {
        val vm = KeyboardViewModel(ApplicationProvider.getApplicationContext<Application>())
        val feedback = mutableListOf<String>()
        val edits = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    KeyboardView(vm, KeyboardUiState(isDarkTheme = true),
                        KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
                            onKeyPressDown = { feedback += it }, onToolbarEditingAction = { edits += it }),
                        modifier = Modifier.fillMaxWidth().height(330.dp).graphicsLayer {
                            alpha = 0.5f; compositingStrategy = CompositingStrategy.ModulateAlpha
                        })
                }
            }
        }
        for (tool in listOf(com.kingzcheung.xime.keyboard.ToolbarButton.SCHEMA.label, "编辑", "表情", "剪贴板")) {
            rule.onNodeWithContentDescription(tool).performTouchInput { down(center); up() }
            rule.onNodeWithContentDescription(tool).assertIsDisplayed().assertIsSelected()
            val toolbar = rule.onNodeWithTag("toolbar-order-row").fetchSemanticsNode().boundsInRoot
            val overlay = rule.onNodeWithTag("keyboard-overlay").fetchSemanticsNode().boundsInRoot
            assertTrue("半透明 $tool 面板必须位于工具栏下方", overlay.top >= toolbar.bottom)
            rule.onAllNodesWithContentDescription("返回").assertCountEquals(1)
            val back = rule.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot
            assertEquals("返回必须在工具栏内", toolbar.center.y, back.center.y, 1f)
            if (tool == "编辑") {
                rule.onAllNodesWithContentDescription("返回键盘").assertCountEquals(0)
                rule.runOnIdle { feedback.clear(); edits.clear() }
                // Direction sectors share the full disk bounds; its centre is the separate selection key.
                rule.onNodeWithContentDescription("向左").performTouchInput {
                    down(androidx.compose.ui.geometry.Offset(width * 0.15f, height * 0.5f)); up()
                }
                rule.runOnIdle { assertEquals(listOf("arrow_left"), feedback); assertEquals(listOf("arrow_left"), edits) }
                rule.onNodeWithContentDescription("选择").performClick()
                rule.onNodeWithContentDescription("取消选择").performClick()
                rule.runOnIdle { assertEquals(listOf("arrow_left", "select_begin", "select_end"), feedback) }
            }
            rule.onNodeWithContentDescription(tool).performTouchInput { down(center); up() }
            rule.onNodeWithTag("keyboard-overlay").assertDoesNotExist()
            rule.runOnIdle { assertEquals(KeyboardPage.Main(MainType.FULL), vm.page.value) }
            rule.onNodeWithContentDescription(tool).performClick()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithTag("keyboard-overlay").assertDoesNotExist()
        }
    }
}
