package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.settings.SettingsRoutes
import com.kingzcheung.xime.ui.settings.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MenuSettingsShortcutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun documentOcrAndSettingsAreOnTheFirstPage() {
        val opened = mutableListOf<ToolbarButton>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(320.dp, 144.dp).testTag("menu-host")) {
                    MenuBar(MenuBarState(true, true, backgroundColor = Color.Black),
                        quickActions(ToolbarButton.DEFAULT_MENU) { opened += it })
                }
            }
        }
        rule.onNodeWithTag("menu-item:ocr").assertIsDisplayed().performClick()
        rule.onNodeWithTag("menu-item:settings").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(listOf(ToolbarButton.OCR, ToolbarButton.SETTINGS), opened) }
        rule.assertGeometry("menu-host", "OCR 首屏入口")
    }

    @Test fun eightDefaultItemsFitOnePageAtMinimumNormalFontHeight() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.requiredSize(320.dp, 144.dp).testTag("menu-host")) {
                    MenuBar(MenuBarState(true, true, backgroundColor = Color.Black), quickActions(ToolbarButton.DEFAULT_MENU))
                }
            }
        }
        ToolbarButton.DEFAULT_MENU.forEach { rule.onNodeWithTag("menu-item:${it.id}").assertIsDisplayed() }
        rule.assertGeometry("menu-host", "八项菜单单页")
    }

    @Test fun themeShortcutOpensRealPageAndBackReturnsToSettingsHome() {
        var homeTitle = ""
        rule.setContent {
            homeTitle = androidx.compose.ui.res.stringResource(com.kingzcheung.xime.R.string.settings_title)
            androidx.compose.material3.MaterialTheme { SettingsScreen(initialRoute = SettingsRoutes.Theme) }
        }
        rule.onNodeWithContentDescription("返回").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(homeTitle).assertIsDisplayed()
    }

    @Test fun everyEntryStaysReachableAcrossMinimumPhoneAndTabletPanels() {
        val viewport = mutableStateOf(Triple(320, 144, 1f))
        val opened = mutableListOf<ToolbarButton>()
        rule.setContent {
            val (width, height, font) = viewport.value
            CompositionLocalProvider(LocalDensity provides Density(1f, font)) {
                Box(Modifier.requiredSize(width.dp, height.dp).testTag("menu-host")) {
                    MenuBar(MenuBarState(true, true, backgroundColor = Color.Black),
                        quickActions(ToolbarButton.DEFAULT_MENU) { opened += it })
                }
            }
        }
        for (size in listOf(Triple(320, 144, 1f), Triple(280, 180, 2f), Triple(640, 140, 1f))) {
            rule.runOnIdle { viewport.value = size }
            for (button in ToolbarButton.DEFAULT_MENU) {
                // A compact panel paginates instead of squeezing or dropping entries.
                rule.onNodeWithTag("menu-pages").performScrollToIndex(0)
                rule.waitForIdle()
                var page = 0
                // Adjacent pager pages can be measured but remain outside the viewport.
                while (!rule.onNodeWithTag("menu-item:${button.id}").isDisplayed() && page < 7) {
                    rule.onNodeWithTag("menu-pages").performScrollToIndex(++page)
                    rule.waitForIdle()
                }
                rule.onNodeWithTag("menu-item:${button.id}").assertIsDisplayed().performClick()
                rule.runOnIdle { assertEquals(button, opened.last()) }
            }
            rule.assertGeometry("menu-host", "menu $size")
        }
    }
}
