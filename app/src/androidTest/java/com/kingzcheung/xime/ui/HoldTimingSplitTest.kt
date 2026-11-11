package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.ui.keyboard.CursorGestureMode
import com.kingzcheung.xime.ui.keyboard.KeyboardInputActions
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LanguageKeyButton
import com.kingzcheung.xime.ui.keyboard.LanguageMenuPanel
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputActions
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.SpaceHoldAction
import com.kingzcheung.xime.ui.keyboard.SpaceKeyButton
import com.kingzcheung.xime.ui.theme.KeyboardPalette
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 语言键长按（默认 0.15 秒）与空格长按（默认 0.2 秒）各按自己的时长触发。 */
class HoldTimingSplitTest {
    @get:Rule val rule = createComposeRule()

    @Test fun languageKeyOpensAtItsOwnDefaultAndSpaceRepeatsAtItsOwn() {
        var spaces = 0
        val prefs = KeyboardInputPreferences(spaceHold = SpaceHoldAction.REPEAT, cursorGesture = CursorGestureMode.NONE)
        assertEquals(0.15f, prefs.languageMenuHoldSeconds, 0f)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f),
                LocalKeyboardPalette provides KeyboardPalette(
                    background = Color(0xFF202124), key = Color(0xFF30343C), text = Color.White,
                    accent = Color(0xFF8AB4F8), function = Color(0xFF454C5B), functionText = Color.White,
                    enter = Color(0xFF454C5B), candidateText = Color.White, selectedText = Color.White),
                LocalKeyboardInputPreferences provides prefs,
                LocalKeyboardInputActions provides KeyboardInputActions(
                    schemas = listOf(SchemaInfo("first", "中文", "", "", "", language = InputLanguage.CHINESE),
                        SchemaInfo("second", "日语", "", "", "", language = InputLanguage.JAPANESE)),
                    currentInputModeId = "first", onSwitchSchema = {})) {
                MaterialTheme {
                    Box(Modifier.size(360.dp, 280.dp)) {
                        LanguageMenuPanel {
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                                Box(Modifier.size(200.dp, 44.dp)) {
                                    SpaceKeyButton(onClick = { spaces++ }, backgroundColor = Color.Gray, textColor = Color.White)
                                }
                                LanguageKeyButton(onClick = {}, backgroundColor = Color.Gray,
                                    textColor = Color.White, iconColor = Color.White,
                                    modifier = Modifier.size(44.dp).testTag("language-key"))
                            }
                        }
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("language-key").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(120)
        assertTrue("not before 0.15 s", rule.onAllNodesWithTag("language-menu").fetchSemanticsNodes().isEmpty())
        rule.mainClock.advanceTimeBy(60)
        rule.mainClock.autoAdvance = true
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("language-menu").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("language-key").performTouchInput { cancel() }
        rule.waitForIdle()

        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space-key").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(170)
        rule.runOnIdle { assertEquals("space does not repeat before 0.2 s", 0, spaces) }
        rule.mainClock.advanceTimeBy(60)
        rule.runOnIdle { assertTrue("space repeats once 0.2 s passes", spaces >= 1) }
        rule.onNodeWithTag("space-key").performTouchInput { up() }
        rule.mainClock.autoAdvance = true
    }
}
