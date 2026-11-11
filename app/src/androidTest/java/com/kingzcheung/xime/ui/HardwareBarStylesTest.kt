package com.kingzcheung.xime.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.keyboard.HardwareCandidateRow
import com.kingzcheung.xime.ui.keyboard.HardwareKeyboardCandidateBar
import com.kingzcheung.xime.ui.keyboard.HardwareKeyboardToolbar
import com.kingzcheung.xime.ui.keyboard.HardwareToolbarPosition
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Bottom-dock styles (current bar, Samsung strip), round buttons, and the floating grid. */
class HardwareBarStylesTest {
    @get:Rule val rule = createComposeRule()
    private val words = listOf("测试", "侧视", "侧室", "策士", "测", "侧", "册", "策", "厕", "恻")

    @Test fun bottomDockStylesAndFloatingGrid() {
        val samsung = mutableStateOf(false)
        val floating = mutableStateOf(false)
        var hidden = 0
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF2B2F36)).systemBarsPadding().testTag("bars-host")) {
                    if (!floating.value) HardwareKeyboardToolbar(onShowKeyboard = {}, onEmoji = {}, onClipboard = {},
                        onSwitchLanguage = {}, backgroundColor = Color(0xFF3A3F47), contentColor = Color.White,
                        onBoundsChanged = {}, position = HardwareToolbarPosition(), onPositionChange = {},
                        bottomDocked = true, onVoice = {}, samsungBottomBar = samsung.value, onHideKeyboard = { hidden++ },
                        bottomCandidateContent = {
                            HardwareCandidateRow(if (samsung.value) words.take(9) else words, emptyList(), 0, false, true,
                                CandidateBarVisuals(Color(0xFF3A3F47), Color.White, Color.Transparent, Color(0xFF8AB4F8), Color(0xFF8AB4F8)),
                                {}, {}, {})
                        })
                    else HardwareKeyboardCandidateBar(inputText = "ceshi", preeditText = "ce shi", candidates = words,
                        hasNextPage = true, hasPrevPage = false, cursorAnchor = null, highlightIndex = 0,
                        cardBackgroundColor = Color(0xFF3A3F47), candidateTextColor = Color.White, activeColor = Color(0xFF8AB4F8))
                }
            }
        }
        // On a phone-width docked strip the secondary actions live behind “更多”.
        if (rule.onAllNodesWithTag("hardware-toolbar-more", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithTag("hardware-toolbar-more", useUnmergedTree = true).performClick()
        }
        rule.onNodeWithTag("hardware-toolbar-voice").assertExists()
        save("dock-current")
        rule.runOnIdle { samsung.value = true }
        rule.onNodeWithTag("hardware-samsung-bar").assertIsDisplayed()
        val root = rule.onNodeWithTag("bars-host").fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithTag("hardware-keyboard-toolbar").fetchSemanticsNode().boundsInRoot
        assertEquals("Samsung strip spans the full width", root.width, bar.width, 2f)
        assertEquals("and sits on the bottom edge", root.bottom, bar.bottom, 2f)
        save("dock-samsung")
        rule.onNodeWithTag("hardware-toolbar-hide").performClick()
        rule.runOnIdle { assertEquals(1, hidden) }
        rule.runOnIdle { floating.value = true }
        rule.onNodeWithTag("hardware-candidate-expand").performClick()
        rule.onNodeWithTag("hardware-candidate-grid").assertIsDisplayed()
        rule.onNodeWithTag("hardware-grid-candidate:9").assertExists()
        save("floating-grid")
        assertTrue(true)
    }

    private fun save(name: String) {
        rule.waitForIdle()
        Thread.sleep(300)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "hardware-bars").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 80, it)) }
        image.recycle()
    }
}
