package com.kingzcheung.xime.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.keyboard.ToolbarAction
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.ui.keyboard.MenuBar
import com.kingzcheung.xime.ui.keyboard.MenuBarState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class XimeMenuGridTest {
    @get:Rule val rule = createComposeRule()

    @Test fun portraitGridKeepsSquareCellsAndLaterActionsReachable() {
        val buttons = ToolbarButton.entries.take(9)
        var clicked: ToolbarButton? = null
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                orientation = Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalConfiguration provides config) {
                MenuBar(MenuBarState(true, false, backgroundColor = Color.White),
                    buttons.map { button -> ToolbarAction(ToolbarButtonItem.Builtin(button)) { clicked = button } },
                    Modifier.size(360.dp, 260.dp))
            }
        }
        val first = rule.onNodeWithTag("menu-item:${buttons[0].id}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val next = rule.onNodeWithTag("menu-item:${buttons[1].id}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val below = rule.onNodeWithTag("menu-item:${buttons[4].id}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(73f, first.width, 0.1f)
        assertEquals(first.width, first.height, 0.1f)
        assertEquals(12f, next.left - first.right, 0.1f)
        assertEquals(12f, below.top - first.bottom, 0.1f)
        rule.onNodeWithTag("menu-pages").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("menu-item:${buttons[8].id}").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(buttons[8], clicked) }
    }
}
