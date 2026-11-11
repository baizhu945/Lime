package com.kingzcheung.xime.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.keyboard.HandwritingKeyboardLayout
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HandwritingPanelGeometryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun handwritingSharesQwertyColumnsAndGapsButKeepsFiveEqualRows() {
        val handwriting = mutableStateOf(false)
        val size = mutableStateOf(360.dp to 260.dp)
        val floating = mutableStateOf(false)
        val explicit = mutableStateOf(true)
        val app = rule.activity.application
        KeysConfigHelper.loadConfig(app)
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
        val vm = KeyboardViewModel(app)
        try {
            rule.setContent {
                CompositionLocalProvider(LocalDensity provides Density(.5f), LocalKeyboardExplicitWidth provides explicit.value,
                    LocalKeyboardPunctuation provides KeyboardPunctuation(full = true, japanese = false)) {
                    MaterialTheme { Box(Modifier.requiredSize(size.value.first, size.value.second).background(Color(0xFF202128)).testTag("comparison-body")) {
                        if (handwriting.value) HandwritingKeyboardLayout(bottomPaddingDp = 0, floating = floating.value, modifier = Modifier.fillMaxSize(),
                            keyTextColor = Color.White, specialKeyTextColor = Color.White,
                            keyBackgroundColor = Color(0xFF404148), specialKeyBackgroundColor = Color(0xFF505158))
                        else KeyboardLayout({}, vm, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                            KeyboardUiState(currentSchemaId = "rime_ice", isFloatingMode = floating.value), false, Modifier.fillMaxSize())
                    } }
                }
            }
            for (window in listOf(280.dp to 164.dp, 360.dp to 260.dp, 360.dp to 500.dp, 800.dp to 420.dp)) {
              for (isFloating in listOf(false, true)) {
                rule.runOnIdle { size.value = window; floating.value = isFloating; explicit.value = isFloating; handwriting.value = false }
                fun bounds(tag: String): androidx.compose.ui.geometry.Rect {
                    // Switching the whole layout can expose an existing semantics node
                    // before its replacement has been placed (bounds are temporarily zero).
                    rule.waitUntil(5_000) {
                        rule.onAllNodesWithTag(tag, true).fetchSemanticsNodes().singleOrNull()
                            ?.boundsInRoot?.let { it.width > 0 && it.height > 0 } == true
                    }
                    return rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot
                }
                // Handwriting retains its original symbol+number area. With the new period
                // footer, ?123 + comma occupy that area and period occupies the old comma slot.
                val configuredMode = rule.onAllNodesWithTag("configured-mode-key", true)
                    .fetchSemanticsNodes().isNotEmpty()
                val modeReference = if (configuredMode) bounds("configured-mode-key") else bounds("mode-slot-1")
                val modernPeriod = configuredMode && rule.onAllNodesWithTag("qwerty-period-key", true)
                    .fetchSemanticsNodes().isNotEmpty()
                val secondModeReference = if (modernPeriod) bounds("qwerty-punctuation-key")
                    else if (configuredMode) modeReference else bounds("mode-slot-2")
                val punctuationReference = bounds(if (modernPeriod) "qwerty-period-key" else "qwerty-punctuation-key")
                val enterReference = bounds("qwerty-enter-key")
                val name = "${window.first.value.toInt()}-${window.second.value.toInt()}-$isFloating"
                saveUiEvidence("handwriting-reference-$name", rule.onNodeWithTag("comparison-body"))
                rule.runOnIdle { handwriting.value = true }
                val actual = bounds("handwriting-key:symbol")
                val body = bounds("comparison-body")
                val symbol = actual
                val number = bounds("handwriting-key:number")
                assertEquals("$name symbol left", modeReference.left, symbol.left, 1f)
                assertEquals("$name number right", secondModeReference.right, number.right, 1f)
                assertEquals("$name symbol bottom", modeReference.bottom, symbol.bottom, 1f)
                assertEquals("$name number bottom", secondModeReference.bottom, number.bottom, 1f)
                val expand = bounds("handwriting-key:expand")
                val enter = bounds("handwriting-key:enter")
                // Five equal handwriting rows are shorter than QWERTY's four rows; the
                // shared contract is horizontal alignment and the common bottom edge.
                assertEquals("$name expand left", punctuationReference.left, expand.left, 1f)
                assertEquals("$name expand right", punctuationReference.right, expand.right, 1f)
                assertEquals("$name expand bottom", punctuationReference.bottom, expand.bottom, 1f)
                assertEquals("$name enter left", enterReference.left, enter.left, 1f)
                assertEquals("$name enter right", enterReference.right, enter.right, 1f)
                assertEquals("$name enter bottom", enterReference.bottom, enter.bottom, 1f)
                val space = bounds("handwriting-key:space")
                val language = bounds("handwriting-key:ime_switch")
                assertEquals(punctuationReference.right, space.left, 1f)
                assertEquals(space.right, language.left, 1f)
                assertEquals(punctuationReference.width, language.width, 1f)
                assertEquals(enterReference.left, language.right, 1f)
                // Five rows within the same content body and bottom inset as QWERTY.
                assertEquals((body.height - (body.bottom - modeReference.bottom)) / 5f, actual.height, 1f)
                saveUiEvidence("handwriting-aligned-$name", rule.onNodeWithTag("comparison-body"))
              }
            }
        } finally { KeysConfigHelper.setActiveKeyboardSchema("rime_ice") }
    }

    @Test fun sideKeysAndFooterKeepFiveEqualRowsAtSmallAndLargeSizes() {
        val height = mutableStateOf(220.dp)
        val width = mutableStateOf(280.dp)
        rule.setContent { MaterialTheme {
            HandwritingKeyboardLayout(bottomPaddingDp = 0, modifier = Modifier.width(width.value).height(height.value))
        } }
        for ((w, h) in listOf(280.dp to 180.dp, 360.dp to 300.dp, 360.dp to 220.dp, 1000.dp to 300.dp)) {
            rule.runOnIdle { width.value = w; height.value = h }
            rule.waitForIdle()
            val footer = rule.onNodeWithTag("handwriting-bottom-row").fetchSemanticsNode().boundsInRoot
            val side = rule.onNodeWithTag("handwriting-side-keys").fetchSemanticsNode().boundsInRoot
            assertEquals((side.height + footer.height) / 5f, footer.height, 1f)
            val enter = rule.onNodeWithTag("handwriting-key:enter").fetchSemanticsNode().boundsInRoot
            for (key in listOf("delete", "？", "，", "。")) {
                val bounds = rule.onNodeWithTag("handwriting-key:$key").fetchSemanticsNode().boundsInRoot
                assertEquals(footer.height, bounds.height, 1f)
                assertEquals(enter.left, bounds.left, 1f)
                assertEquals(enter.right, bounds.right, 1f)
            }
            for (key in listOf("symbol", "number", "space", "expand", "ime_switch", "enter")) {
                val bounds = rule.onNodeWithTag("handwriting-key:$key").fetchSemanticsNode().boundsInRoot
                assertEquals(footer.top, bounds.top, 1f)
                assertEquals(footer.bottom, bounds.bottom, 1f)
            }
        }
    }

    @Test fun expandedPanelHasTwoAlignedRowsAndCanRestoreNormalControls() {
        val expanded = mutableStateOf(false)
        rule.setContent { MaterialTheme {
            HandwritingKeyboardLayout(expanded = expanded.value, bottomPaddingDp = 0,
                onKeyPress = { if (it == "expand") expanded.value = true else if (it == "collapse") expanded.value = false },
                modifier = Modifier.width(1000.dp).height(if (expanded.value) 650.dp else 300.dp))
        } }
        val original = rule.onNodeWithTag("handwriting-canvas").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("handwriting-key:expand").performClick()
        rule.onNodeWithTag("handwriting-side-keys").assertDoesNotExist()
        rule.onNodeWithTag("handwriting-key:number").assertExists()
        val canvas = rule.onNodeWithTag("handwriting-canvas").fetchSemanticsNode().boundsInRoot
        assertTrue(canvas.height > original.height * 1.5f)
        val bottom = rule.onNodeWithTag("handwriting-bottom-row").fetchSemanticsNode().boundsInRoot
        val symbols = rule.onNodeWithTag("handwriting-symbol-row").fetchSemanticsNode().boundsInRoot
        assertEquals(symbols.bottom, bottom.top, 1f)
        assertEquals(symbols.height, bottom.height, 1f)
        assertEquals(symbols.left, bottom.left, 1f)
        assertEquals(symbols.right, bottom.right, 1f)
        for (key in listOf("；", "：", "！", "？", "，", "。", "delete")) {
            val bounds = rule.onNodeWithTag("handwriting-key:$key").fetchSemanticsNode().boundsInRoot
            assertEquals(symbols.top, bounds.top, 1f)
            assertEquals(symbols.bottom, bounds.bottom, 1f)
        }
        val delete = rule.onNodeWithTag("handwriting-key:delete").fetchSemanticsNode().boundsInRoot
        val enter = rule.onNodeWithTag("handwriting-key:enter").fetchSemanticsNode().boundsInRoot
        assertEquals(delete.left, enter.left, 1f)
        assertEquals(delete.right, enter.right, 1f)
        for (key in listOf("symbol", "number", "space", "collapse", "ime_switch", "enter")) {
            val bounds = rule.onNodeWithTag("handwriting-key:$key").fetchSemanticsNode().boundsInRoot
            assertEquals(bottom.top, bounds.top, 1f)
            assertEquals(bottom.bottom, bounds.bottom, 1f)
        }
        rule.onNodeWithTag("handwriting-key:collapse").performClick()
        rule.onNodeWithTag("handwriting-key:number").assertExists()
        assertEquals(original, rule.onNodeWithTag("handwriting-canvas").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun fullscreenInkRetainsTheSavedFooterWidthAndHorizontalPosition() {
        val offset = mutableStateOf(-200)
        val width = mutableStateOf(360)
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f)) {
                MaterialTheme { Box(Modifier.requiredSize(800.dp, 600.dp)) {
                    HandwritingKeyboardLayout(expanded = true, bottomPaddingDp = 0,
                        expandedControlsWidthDp = width.value, expandedControlsOffsetX = offset.value,
                        modifier = Modifier.fillMaxSize(), expandedCandidateBar = {
                            Box(Modifier.fillMaxWidth().height(44.dp).testTag("handwriting-test-candidates"))
                        })
                } }
            }
        }
        for (x in listOf(-200, 0, 200, 1000)) {
            rule.runOnIdle { offset.value = x }
            rule.waitForIdle()
            val canvas = rule.onNodeWithTag("handwriting-canvas").fetchSemanticsNode().boundsInRoot
            val footer = rule.onNodeWithTag("handwriting-controls").fetchSemanticsNode().boundsInRoot
            assertEquals(344f, footer.width, 1f)
            assertEquals(canvas.center.x + x.coerceIn(-228, 228), footer.center.x, 1f)
            assertTrue(canvas.width > footer.width * 2)
            for (tag in listOf("handwriting-test-candidates", "handwriting-symbol-row", "handwriting-bottom-row")) {
                val row = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertEquals(footer.left, row.left, 1f); assertEquals(footer.right, row.right, 1f)
            }
            rule.onNodeWithTag("handwriting-key:collapse").assertIsDisplayed()
        }
        rule.runOnIdle { width.value = 1200 }
        rule.waitForIdle()
        val canvas = rule.onNodeWithTag("handwriting-canvas").fetchSemanticsNode().boundsInRoot
        val footer = rule.onNodeWithTag("handwriting-controls").fetchSemanticsNode().boundsInRoot
        assertEquals(canvas.left, footer.left, 1f); assertEquals(canvas.right, footer.right, 1f)
    }
}
