package com.kingzcheung.xime.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class QwertyExpandedRailGeometryTest {
    @get:Rule val rule = createComposeRule()
    private val rowFields = listOf("_zhRowsLayout", "_zhRowsBase", "_zhRows").map { name ->
        KeysConfigHelper::class.java.getDeclaredField(name).apply { isAccessible = true }
    }
    private var previousRows = emptyList<Any?>()
    private var rowLayoutOverridden = false
    private var restoreModernBindings: (() -> Unit)? = null
    @After fun restoreLayout() {
        restoreModernBindings?.invoke()
        if (rowLayoutOverridden) rowFields.zip(previousRows).forEach { (field, value) ->
            field.set(KeysConfigHelper, value)
        }
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
    }

    @Test fun t9PagingKeepsItsRowSizeAndDisabledStateInBothMaterials() {
        KeysConfigHelper.loadConfig(ApplicationProvider.getApplicationContext<Application>())
        var width by mutableIntStateOf(360)
        var glass by mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.5f),
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(
                    frostedGlass = FrostedGlassConfig(enabled = glass))) {
                MaterialTheme {
                    // Keep the Android host fixed while only the keyboard viewport changes.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CandidatePage(CandidatePageState(backgroundColor = Color.Black, textColor = Color.White,
                        keyBackgroundColor = Color.DarkGray, matchT9Geometry = true,
                        candidates = List(300) { CandidateEntry("候选$it", globalIndex = it) }),
                        CandidatePageCallbacks(onCandidateSelect = {}), Modifier.requiredSize(width.dp, 300.dp))
                    }
                }
            }
        }
        for (size in listOf(280, 360, 800)) for (material in listOf(false, true)) {
            rule.runOnIdle { width = size; glass = material }
            val delete = rule.onNodeWithTag("expanded-delete-key").fetchSemanticsNode().boundsInRoot
            val enter = rule.onNodeWithTag("expanded-enter-key").fetchSemanticsNode().boundsInRoot
            for (tag in listOf("expanded-page-previous", "expanded-page-next")) {
                val page = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals(delete.left, page.left, 1f)
                assertEquals(delete.width, page.width, 1f)
                assertEquals(delete.height, page.height, 1f)
                assertTrue(page.top >= delete.bottom - 1f && page.bottom <= enter.top + 1f)
            }
            rule.onNodeWithTag("expanded-page-previous").assertIsNotEnabled()
            rule.onNodeWithTag("expanded-page-next").assertIsEnabled()
        }
    }

    @Test fun expandedControlsMatchTheActualTypingKeysAcrossWindowSizesAndThemes() {
        data class Window(val width: Int, val height: Int, val floating: Boolean = false,
            val explicit: Boolean = false, val split: Boolean = false, val glass: Boolean = false)
        val windows = listOf(Window(360, 260), Window(800, 420), Window(280, 180, floating = true),
            Window(1080, 480, explicit = true), Window(720, 220, split = true),
            Window(360, 500, glass = true), Window(800, 280, explicit = true, glass = true))
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
        // This audit measures one legacy mode slot, not the combined ?123 span.
        // Keep its original geometry contract on the supported two-key layout.
        previousRows = rowFields.map { it.get(KeysConfigHelper) }
        rowLayoutOverridden = true
        val letterRows = KeysConfigHelper.getKeyRows(false).take(3)
        rowFields.first().set(KeysConfigHelper, com.kingzcheung.xime.settings.KeyboardRowsLayout(
            letterRows.map { com.kingzcheung.xime.settings.KeyboardRow(it) }))
        rowFields.drop(1).forEach { it.set(KeysConfigHelper, letterRows) }
        val vm = KeyboardViewModel(app)
        var window by mutableStateOf(windows.first())
        var expanded by mutableStateOf(false)
        var modernFooter by mutableStateOf<ConfiguredFooterWeights?>(null)
        var deletes = 0
        var enters = 0
        var filters = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.5f),
                LocalKeyboardExplicitWidth provides window.explicit,
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(
                    splitKeyboardEnabled = window.split,
                    frostedGlass = FrostedGlassConfig(enabled = window.glass))) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val modifier = Modifier.requiredSize(window.width.dp, window.height.dp).testTag("comparison-body")
                    if (!expanded) KeyboardLayout({}, vm,
                        KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                        KeyboardUiState(currentSchemaId = "rime_ice", isFloatingMode = window.floating), false, modifier)
                    else CandidatePage(CandidatePageState(backgroundColor = Color.Black, textColor = Color.White,
                        keyBackgroundColor = Color.DarkGray, floating = window.floating,
                        candidates = List(300) { CandidateEntry("候选$it", globalIndex = it) },
                        qwertyGeometry = candidateQwertyGeometry(KeysConfigHelper.getKeyRows(false), false, false,
                            window.split, configuredFooter = modernFooter)),
                        CandidatePageCallbacks(onCandidateSelect = {}, onDelete = { deletes++ },
                            onEnter = { enters++ }, onToggleSingleCharFilter = { filters++ }), modifier)
                    }
                }
            }
        }
        fun bounds(tag: String): Rect {
            val origin = rule.onNodeWithTag("comparison-body", true).fetchSemanticsNode().boundsInRoot.topLeft
            return rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot.translate(-origin)
        }
        for (size in windows) {
            rule.runOnIdle { window = size; expanded = false }
            rule.waitForIdle()
            val imageName = "${size.width}-${size.height}-split-${size.split}-glass-${size.glass}"
            saveUiEvidence("typing-$imageName", rule.onNodeWithTag("comparison-body", true))
            val symbol = bounds("mode-slot-1")
            val delete = bounds("qwerty-delete-key")
            val enter = bounds("qwerty-enter-key")
            rule.runOnIdle { expanded = true }
            saveUiEvidence("expanded-$imageName", rule.onNodeWithTag("comparison-body", true))
            val filter = bounds("expanded-filter-key")
            val expandedDelete = bounds("expanded-delete-key")
            val expandedEnter = bounds("expanded-enter-key")
            assertEquals("$size left column aligns with symbol key", symbol.left, filter.left, 1f)
            assertEquals("$size left width", symbol.width, filter.width, 1f)
            assertEquals("$size bottom row top", symbol.top, filter.top, 1f)
            assertEquals("$size bottom row height", symbol.height, filter.height, 1f)
            assertEquals("$size delete right edge", enter.right, expandedDelete.right, 1f)
            assertEquals("$size expanded delete uses the return column", enter.width, expandedDelete.width, 1f)
            assertEquals("$size delete height", delete.height, expandedDelete.height, 1f)
            assertEquals("$size enter right edge", enter.right, expandedEnter.right, 1f)
            assertEquals("$size enter width", enter.width, expandedEnter.width, 1f)
            assertEquals("$size enter top", enter.top, expandedEnter.top, 1f)
            assertEquals("$size enter height", enter.height, expandedEnter.height, 1f)
            for (tag in listOf("expanded-delete-key", "expanded-page-previous", "expanded-page-next", "expanded-enter-key")) {
                val key = bounds(tag)
                assertTrue("$size $tag stays in the panel", key.left >= 0f && key.top >= 0f &&
                    key.right <= size.width * .5f + 1f && key.bottom <= size.height * .5f + 1f)
                assertEquals("$size paging uses the same row height", enter.height, key.height, 1f)
            }
            rule.onNodeWithTag("expanded-page-previous").assertIsNotEnabled()
            rule.onNodeWithTag("expanded-page-next").performClick()
            rule.onNodeWithTag("expanded-page-previous").assertIsEnabled()
            rule.onNodeWithTag("expanded-filter-key").performClick()
            rule.onNodeWithTag("expanded-delete-key").performTouchInput { click() }
            rule.onNodeWithTag("expanded-enter-key").performTouchInput { click() }
        }
        rule.runOnIdle {
            assertEquals(windows.size, deletes)
            assertEquals(windows.size, enters)
            assertEquals(windows.size, filters)
        }

        // Independent modern fixture: canonical configured controls, default comma + period,
        // and deliberately empty bindings (not the default Xime width overrides).
        val modernRows = listOf(
            "qwertyuiop".map(Char::toString), "asdfghjkl".map(Char::toString),
            listOf("shift") + "zxcvbnm".map(Char::toString) + "delete",
            listOf("mode_change", "comma", "space", "earth", "enter"),
        )
        val modernLayout = com.kingzcheung.xime.settings.KeyboardRowsLayout(
            modernRows.map { com.kingzcheung.xime.settings.KeyboardRow(it) })
        val gesturesField = KeysConfigHelper::class.java.getDeclaredField("_keyGestureConfig")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val gestures = gesturesField.get(KeysConfigHelper) as
            MutableState<Map<String, com.kingzcheung.xime.settings.KeyGestureConfig>>
        val previousGestures = gestures.value
        restoreModernBindings = { gestures.value = previousGestures }
        rule.runOnIdle {
            rowFields.first().set(KeysConfigHelper, modernLayout)
            rowFields.drop(1).forEach { it.set(KeysConfigHelper, modernRows) }
            gestures.value = emptyMap()
            val bindings = { id: String -> KeysConfigHelper.getKeyGesture(id, false) }
            val configured = requireNotNull(resolveTypingConfiguredControlRow(
                modernLayout, KeyboardUiState(currentSchemaId = "rime_ice").inputProfile,
                customLetters = false, split = false, comma = true, voice = false, bindings = bindings))
            modernFooter = configuredFooterWeights(
                letterKeyboardBottomRow(configured.letterRows, false), true, bindings).also { footer ->
                // Empty bindings: 1.4 mode + 1 comma + 1 period + 2.9 space + 1 earth + 1.7 enter.
                assertEquals(1.4f, footer.mode, .0001f)
                assertEquals(1f, footer.period, .0001f)
                assertEquals(9f, footer.total, .0001f)
            }
        }
        fun modernIntegerWidths(width: Int): List<Int> {
            // Independent reference for Row's nearest-pixel children and signed residual allocation.
            val weights = listOf(1.4f, 1f, 1f, 2.9f, 1f, 1.7f)
            val pixels = weights.map { (width * it / 9f).roundToInt() }.toMutableList()
            var residual = width - pixels.sum()
            for (index in pixels.indices) {
                val correction = residual.compareTo(0)
                pixels[index] += correction
                residual -= correction
            }
            assertEquals(0, residual)
            return pixels
        }
        assertEquals(listOf(28, 19, 19, 55, 19, 32), modernIntegerWidths(172))
        for (size in windows.filterNot { it.split }) {
            rule.runOnIdle { window = size; expanded = false }
            rule.onNodeWithTag("configured-control-row", true).assertExists()
            rule.onNodeWithTag("mode-slot-1", true).assertDoesNotExist()
            rule.onNodeWithTag("mode-slot-2", true).assertDoesNotExist()
            val mode = bounds(MODE_KEY_TAG)
            val comma = bounds("qwerty-punctuation-key")
            val period = bounds("qwerty-period-key")
            val space = bounds("space-key")
            val delete = bounds("qwerty-delete-key")
            val enter = bounds("qwerty-enter-key")
            val row = bounds("configured-control-row")
            val integerWidths = modernIntegerWidths(row.width.roundToInt())
            assertEquals("$size modern mode uses the integer nine-weight footer", integerWidths.first().toFloat(), mode.width, 1f)
            assertEquals("$size modern enter uses the integer nine-weight footer", integerWidths.last().toFloat(), enter.width, 1f)
            assertEquals("$size period is beside comma", comma.right, period.left, 1f)
            assertEquals("$size period is before space", period.right, space.left, 1f)
            assertEquals("$size punctuation widths", comma.width, period.width, 1f)
            rule.runOnIdle { expanded = true }
            val filter = bounds("expanded-filter-key")
            val expandedDelete = bounds("expanded-delete-key")
            val expandedEnter = bounds("expanded-enter-key")
            assertEquals("$size modern left edge", mode.left, filter.left, 1f)
            assertEquals("$size modern left width", mode.width, filter.width, 1f)
            assertEquals("$size modern bottom row top", mode.top, filter.top, 1f)
            assertEquals("$size modern bottom row height", mode.height, filter.height, 1f)
            assertEquals("$size modern delete right edge", enter.right, expandedDelete.right, 1f)
            assertEquals("$size modern delete width", enter.width, expandedDelete.width, 1f)
            assertEquals("$size modern delete height", delete.height, expandedDelete.height, 1f)
            assertEquals("$size modern enter right edge", enter.right, expandedEnter.right, 1f)
            assertEquals("$size modern enter width", enter.width, expandedEnter.width, 1f)
            assertEquals("$size modern enter top", enter.top, expandedEnter.top, 1f)
            assertEquals("$size modern enter height", enter.height, expandedEnter.height, 1f)
            for (tag in listOf("expanded-delete-key", "expanded-page-previous", "expanded-page-next", "expanded-enter-key")) {
                val key = bounds(tag)
                assertEquals("$size modern right rail edge", enter.right, key.right, 1f)
                assertEquals("$size modern right rail width", enter.width, key.width, 1f)
                assertEquals("$size modern right rail height", enter.height, key.height, 1f)
            }
        }
    }
}
