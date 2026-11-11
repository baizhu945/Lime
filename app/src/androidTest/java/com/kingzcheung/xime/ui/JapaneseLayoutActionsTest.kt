package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class JapaneseLayoutActionsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun numberPageKeepsKanaKeypadGeometryAndSpacePosition() {
        var numbers by mutableStateOf(false)
        var wide by mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                val size = if (wide) Modifier.size(760.dp, 320.dp) else Modifier.size(360.dp, 260.dp)
                if (numbers) NumberKeyboardLayout(
                    onKeyPress = {}, keyBackgroundColor = Color.DarkGray, keyTextColor = Color.White,
                    specialKeyBackgroundColor = Color.Blue, modifier = size, isJapaneseKana = true,
                    keySpacingX = 3.dp, keySpacingY = 4.dp,
                ) else JapaneseKanaKeyboardLayout(
                    onKanaAction = {}, onKeyPress = {}, keyBackgroundColor = Color.DarkGray,
                    keyTextColor = Color.White, specialKeyBackgroundColor = Color.Blue, modifier = size,
                    keySpacingX = 3.dp, keySpacingY = 4.dp,
                )
            }
        }

        for (wideSize in listOf(false, true)) {
            rule.runOnIdle { wide = wideSize; numbers = false }
            val commonTags = listOf("kana-left", "kana-number", "kana-symbol", "kana-language",
                "kana-delete", "kana-right", "kana-space", "kana-enter")
            val commonBounds = commonTags.associateWith { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
            val kanaBounds = japaneseKanaKeys.take(9).map {
                rule.onNodeWithTag("kana-key:${it.center.romaji}").fetchSemanticsNode().boundsInRoot
            }
            val waBounds = rule.onNodeWithTag("kana-key:wa").fetchSemanticsNode().boundsInRoot
            val punctuationBounds = rule.onNodeWithTag("kana-punctuation").fetchSemanticsNode().boundsInRoot
            rule.runOnIdle { numbers = true }
            commonBounds.forEach { (tag, original) ->
                assertEquals("$tag must keep its position when opening numbers", original,
                    rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
            }
            kanaBounds.forEachIndexed { index, original ->
                assertEquals("Digit ${index + 1} must use the original kana cell", original,
                    rule.onNodeWithTag("kana-digit:${index + 1}").fetchSemanticsNode().boundsInRoot)
            }
            assertEquals(waBounds, rule.onNodeWithTag("kana-digit:0").fetchSemanticsNode().boundsInRoot)
            assertEquals(punctuationBounds, rule.onNodeWithTag("kana-decimal").fetchSemanticsNode().boundsInRoot)
            rule.onNodeWithTag("number-actions").assertDoesNotExist()
        }
    }

    @Test fun modeLabelsStayCompactWhileNumericLiteralsRespectWidthSetting() {
        val actions = mutableListOf<String>()
        var numbers by mutableStateOf(false)
        var full by mutableStateOf(true)
        val onKey: (String) -> Unit = { key ->
            actions += key
            when (key) {
                "mode_change_number" -> numbers = true
                "abc" -> numbers = false
            }
        }
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalKeyboardPunctuation provides KeyboardPunctuation(full, japanese = true)) {
                    if (numbers) NumberKeyboardLayout(
                        onKeyPress = onKey, keyBackgroundColor = Color.DarkGray, keyTextColor = Color.White,
                        specialKeyBackgroundColor = Color.Blue, modifier = Modifier.size(360.dp, 260.dp),
                        isJapaneseKana = true,
                    ) else JapaneseKanaKeyboardLayout(
                        onKanaAction = { fail("Mode/literal keys must not enter the kana composer") },
                        onKeyPress = onKey, keyBackgroundColor = Color.DarkGray, keyTextColor = Color.White,
                        specialKeyBackgroundColor = Color.Blue, modifier = Modifier.size(360.dp, 260.dp),
                    )
                }
            }
        }

        rule.onNodeWithText("123").assertIsDisplayed()
        rule.onNodeWithText("１２３").assertDoesNotExist()
        rule.onNodeWithText("!@#").assertIsDisplayed()
        rule.onNodeWithTag("kana-symbol").performTouchInput { click() }
        assertEquals("symbol", actions.last())
        rule.onNodeWithTag("kana-number").performTouchInput { click() }
        rule.onNodeWithText("あいう").assertIsDisplayed()
        rule.onNodeWithText("!@#").assertIsDisplayed()
        rule.onNodeWithText("１").assertIsDisplayed()
        rule.onNodeWithText("．").assertIsDisplayed()
        rule.onNodeWithTag("kana-digit:1").performTouchInput { click() }
        rule.onNodeWithTag("kana-decimal").performTouchInput { click() }
        rule.onNodeWithTag("kana-space").performTouchInput { click() }
        assertEquals(listOf("1", ".", "space"), actions.takeLast(3))
        rule.onNodeWithTag("kana-operators").performClick()
        assertEquals("+", actions.last())
        rule.onNodeWithTag("kana-operators").performTouchInput {
            down(center); moveTo(center + Offset(0f, -100f)); up()
        }
        assertEquals("*", actions.last())

        rule.runOnIdle { full = false }
        rule.onNodeWithText("1").assertIsDisplayed()
        rule.onNodeWithText(".").assertIsDisplayed()
        rule.onNodeWithText("!@#").assertIsDisplayed()
        rule.onNodeWithTag("kana-number").performTouchInput { click() }
        assertEquals("abc", actions.last())
        rule.onNodeWithText("123").assertIsDisplayed()
        rule.onNodeWithTag("kana-key:a").assertIsDisplayed()
    }

    @Test fun fourEqualRowsWithConversionFlickAndContextModifier() {
        val actions = mutableListOf<String>()
        var composing by mutableStateOf(false)
        rule.setContent { MaterialTheme { JapaneseKanaKeyboardLayout(
            onKanaAction = { if (it is JapaneseKanaAction.Modify) actions += "modify" },
            onKeyPress = { actions += it }, keyBackgroundColor = Color.DarkGray,
            keyTextColor = Color.White, specialKeyBackgroundColor = Color.Blue,
            modifier = Modifier.size(360.dp, 260.dp), hasKanaInput = composing,
        ) } }
        val left = listOf(rule.onNodeWithTag("kana-convert"), rule.onNodeWithTag("kana-left"), rule.onNodeWithTag("kana-number"), rule.onNodeWithTag("kana-symbol"))
        val right = listOf(rule.onNodeWithTag("kana-delete"), rule.onNodeWithTag("kana-right"), rule.onNodeWithTag("kana-space"), rule.onNodeWithTag("kana-enter"))
        left.zip(right).forEach { (a, b) ->
            val aa = a.fetchSemanticsNode().boundsInRoot
            val bb = b.fetchSemanticsNode().boundsInRoot
            assertEquals(aa.center.y, bb.center.y, 1f)
            assertEquals(aa.width, bb.width, 1f)
            assertEquals(aa.height, bb.height, 1f)
        }
        val punct = rule.onNodeWithTag("kana-punctuation").fetchSemanticsNode().boundsInRoot
        val wa = rule.onNodeWithTag("kana-key:wa").fetchSemanticsNode().boundsInRoot
        assertEquals(wa.height, punct.height, 1f)
        assertEquals(wa.width, punct.width, 1f)
        assertEquals(wa.width, left.first().fetchSemanticsNode().boundsInRoot.width, 1f)
        assertTrue(punct.right <= wa.left)
        rule.onNodeWithTag("kana-convert").performClick()
        assertEquals("japanese_convert", actions.last())
        val flick = with(rule.density) { 50.dp.toPx() }
        rule.onNodeWithTag("kana-convert").performTouchInput {
            down(center); moveTo(center + Offset(0f, flick)); moveTo(center + Offset(0f, flick * 2)); up()
        }
        assertEquals("japanese_undo", actions.last())
        rule.onNodeWithTag("kana-left").performTouchInput { longClick(durationMillis = 850) }
        assertTrue(actions.count { it == "japanese_left" } > 1)
        rule.runOnIdle { composing = true }
        rule.onNodeWithTag("kana-punctuation").assertDoesNotExist()
        rule.onNodeWithTag("kana-modifier").performClick()
        assertEquals("modify", actions.last())
    }
}
