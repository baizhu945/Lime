package com.kingzcheung.xime.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.theme.VisualStyle
import com.kingzcheung.xime.ui.theme.VisualStyles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExpandedCandidateLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun expandedSideRailsKeepOriginalBoundsAndFullKeySurfaces() = preservingRailMaterial {
        data class Size(val width: Int, val height: Int, val landscape: Boolean)
        val sizes = listOf(Size(360, 300, false), Size(640, 300, true),
            Size(240, 144, false), Size(240, 144, true))
        var size by mutableStateOf(sizes.first())
        var selectedPinyin = -1
        var filterClicks = 0
        var deleteClicks = 0
        var enterClicks = 0
        val keyColor = Color(0xFF38333F)
        val functionColor = Color(0xFF58576D)
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 1800
                orientation = if (size.landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(1f),
                LocalKeyCornerRadius provides 12.dp,
                // Host keyboard sizing must not replace the expanded page's original controls.
                LocalKeyVisualPadding provides PaddingValues(8.dp),
                LocalKeyboardKeyContentScale provides 1.7f,
                LocalFunctionKeyColors provides KeyboardKeyColors(functionColor, Color.White),
                LocalEnterKeyColors provides KeyboardKeyColors(functionColor, Color.White)) {
                MaterialTheme {
                    CandidatePage(
                        state = CandidatePageState(backgroundColor = Color.Black, textColor = Color.White,
                            keyBackgroundColor = keyColor, bottomPaddingDp = 16,
                            candidates = List(120) { CandidateEntry("测试", globalIndex = it) },
                            railPinyinOptions = listOf("j", "k", "l")),
                        callbacks = CandidatePageCallbacks(onCandidateSelect = {},
                            onRailPinyinSelect = { selectedPinyin = it },
                            onToggleSingleCharFilter = { filterClicks++ },
                            onDelete = { deleteClicks++ }, onEnter = { enterClicks++ }),
                        modifier = Modifier.size(size.width.dp, size.height.dp).testTag("rail-comparison"),
                    )
                }
            }
        }

        fun paintedBounds(node: SemanticsNodeInteraction, surfaceColor: Color, material: VisualStyle): Rect {
            val pixels = node.captureToImage().toPixelMap()
            var left = pixels.width
            var top = pixels.height
            var right = -1
            var bottom = -1
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                val painted = if (material == VisualStyle.ORIGINAL) {
                    kotlin.math.abs(pixel.red - surfaceColor.red) < .01f &&
                        kotlin.math.abs(pixel.green - surfaceColor.green) < .01f &&
                        kotlin.math.abs(pixel.blue - surfaceColor.blue) < .01f
                } else {
                    // Decorative materials change the edge colour. Their complete painted silhouette
                    // is measured against this fixture's known black backdrop, not its unstyled tint.
                    pixel.red > .01f || pixel.green > .01f || pixel.blue > .01f
                }
                if (painted) {
                    left = minOf(left, x); top = minOf(top, y)
                    right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
            assertTrue("Actual key surface must be visible", right >= left && bottom >= top)
            return Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
        }
        for (material in VisualStyle.entries) for (current in sizes) {
            rule.runOnIdle { VisualStyles.current = material; size = current }
            val panel = rule.onNodeWithTag("rail-comparison").fetchSemanticsNode().boundsInRoot
            val bodyHeight = current.height - 16f
            val left = rule.onNodeWithTag("candidate-left-rail").fetchSemanticsNode().boundsInRoot
            val right = rule.onNodeWithTag("candidate-right-rail").fetchSemanticsNode().boundsInRoot
            assertEquals("$current original left width", if (current.landscape) 48f else 40f, left.width, 1f)
            assertEquals("$current original right width", if (current.landscape) 40f else 46f, right.width, 1f)
            assertEquals("$current page left inset", panel.left + 8f, left.left, 1f)
            assertEquals("$current page right inset", panel.right - 8f, right.right, 1f)
            val pinyin = rule.onNodeWithTag("expanded-pinyin-options").fetchSemanticsNode().boundsInRoot
            val filter = rule.onNodeWithTag("expanded-filter-key").fetchSemanticsNode().boundsInRoot
            assertEquals(left.width, pinyin.width, 1f)
            assertEquals(left.width, filter.width, 1f)
            assertEquals(panel.top + 6f, pinyin.top, 1f)
            assertEquals((bodyHeight - 16f) * .75f, pinyin.height, 1f)
            assertEquals("Connected list and filter keep their original gap", pinyin.bottom + 4f, filter.top, 1f)
            assertEquals(panel.top + bodyHeight - 6f, filter.bottom, 1f)

            val controls = listOf("expanded-delete-key", "expanded-page-previous",
                "expanded-page-next", "expanded-enter-key").map { rule.onNodeWithTag(it) }
            val bounds = controls.map { it.fetchSemanticsNode().boundsInRoot }
            val compact = current.landscape || bodyHeight < 226f
            val gap = if (compact) 4f else 10f
            val expectedHeight = if (compact) (bodyHeight - 12f - 3f * gap) / 4f else 46f
            bounds.forEachIndexed { index, rect ->
                assertEquals("$current control $index width", right.width, rect.width, 1f)
                assertEquals("$current control $index height", expectedHeight, rect.height, 1f)
                assertEquals(right.left, rect.left, 1f)
                assertTrue("All four controls remain inside the panel", rect.top >= panel.top + 5f &&
                    rect.bottom <= panel.top + bodyHeight - 5f)
                if (index > 0) assertEquals("$current control gap", gap, rect.top - bounds[index - 1].bottom, 1f)
            }
            assertEquals("Original portrait controls are vertically centered",
                panel.top + bodyHeight / 2f, (bounds.first().top + bounds.last().bottom) / 2f, 1f)
            listOf(0, 2, 3).forEach { index ->
                val paint = paintedBounds(controls[index], if (index == 2) keyColor else functionColor, material)
                assertEquals("$material/$current control $index has no added visual inset", 0f, paint.left, 1f)
                assertEquals(0f, paint.top, 1f)
                assertEquals(bounds[index].width, paint.width, 1f)
                assertEquals(bounds[index].height, paint.height, 1f)
            }
            listOf("上一页", "下一页", "回车").forEach { label ->
                val icon = rule.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertEquals("Original $label glyph width", 20f, icon.width, 1f)
                assertEquals("Original $label glyph height", 20f, icon.height, 1f)
            }
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText("j", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals("Pinyin retains the original 13sp size", 13f, results.single().layoutInput.style.fontSize.value, .01f)
        }
        rule.onNodeWithTag("expanded-page-previous").assertIsNotEnabled()
        rule.onNodeWithTag("expanded-page-next").assertHasClickAction()
        rule.onNodeWithText("k").performClick()
        rule.onNodeWithTag("expanded-filter-key").performClick()
        rule.onNodeWithTag("expanded-delete-key").performTouchInput { click() }
        rule.onNodeWithTag("expanded-enter-key").performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(1, selectedPinyin)
            assertEquals(1, filterClicks)
            assertEquals(1, deleteClicks)
            assertEquals(1, enterClicks)
        }
    }

    @Test fun floatingCandidatesReflowByTheirViewportAndKeepSelectionIndices() {
        val width = mutableStateOf(240)
        val fontScale = mutableStateOf(1f)
        val landscape = mutableStateOf(true)
        var selected = -1
        val words = listOf("那", "吗", "没", "哦", "面", "年", "女", "明", "你们", "哪里", "明天", "没有关系")
        val entries = List(80) { i -> CandidateEntry(words[i % words.size], "pinyin", 100 + i * 2) }
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 1800 // A tablet screen must not determine a floating panel's columns.
                orientation = if (landscape.value) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalConfiguration provides config,
                LocalDensity provides Density(1f, fontScale.value)) {
                MaterialTheme {
                    CandidatePage(
                        state = CandidatePageState(candidates = entries, backgroundColor = Color(0xFF211D29),
                            textColor = Color.White, railPinyinOptions = listOf("o", "m", "n")),
                        callbacks = CandidatePageCallbacks(onCandidateSelect = { selected = it.globalIndex }, onDelete = {}, onEnter = {}),
                        modifier = Modifier.size(width.value.dp, 300.dp).testTag("candidate-panel"))
                }
            }
        }
        fun firstRowCount(): Int {
            val nodes = rule.onAllNodes(hasTestTagPrefix("expanded-candidate:")).fetchSemanticsNodes()
            val centerY = rule.onNodeWithTag("expanded-candidate:100").fetchSemanticsNode().boundsInRoot.center.y
            return nodes.count { it.boundsInRoot.width > 0f && kotlin.math.abs(it.boundsInRoot.center.y - centerY) < 1f }
        }
        fun checkVisible() {
            rule.waitForIdle()
            val panel = rule.onNodeWithTag("candidate-panel").fetchSemanticsNode().boundsInRoot
            val rail = rule.onNodeWithTag("candidate-left-rail").fetchSemanticsNode().boundsInRoot
            val center = rule.onNodeWithTag("expanded-candidates").fetchSemanticsNode().boundsInRoot
            assertTrue("Side rail must not take half of a floating panel", rail.width < panel.width * .25f)
            val sideWidths = if (landscape.value) 48f + 40f else 40f + 46f
            // Original sides occupy fixed widths plus 16dp page padding, 15dp left separator,
            // and 8dp right gutter. The remaining viewport still drives candidate reflow.
            assertEquals("Candidates use the full remaining viewport", panel.width - sideWidths - 39f, center.width, 1f)
            val nodes = rule.onAllNodes(hasTestTagPrefix("expanded-candidate:")).fetchSemanticsNodes()
            assertTrue(nodes.isNotEmpty())
            nodes.forEach { node ->
                val tag = node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]
                val results = mutableListOf<TextLayoutResult>()
                rule.onNodeWithTag(tag).onChildren().onFirst().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                assertTrue("$tag has readable text", results.isNotEmpty())
                results.forEach { assertFalse("$tag must not be squeezed into ellipsis", it.isLineEllipsized(0)) }
                assertTrue(node.boundsInRoot.left >= center.left - 1f)
                assertTrue(node.boundsInRoot.right <= center.right + 1f)
            }
        }
        checkVisible()
        val narrowCount = firstRowCount()
        rule.runOnIdle { width.value = 360 }
        checkVisible()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "candidate-wide.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        assertTrue("Widening the panel must fit more words: narrow=$narrowCount wide=${firstRowCount()}", firstRowCount() > narrowCount)
        rule.onNodeWithTag("expanded-candidate:106").assertExists()
        rule.onNodeWithTag("expanded-candidate:108").assertExists()
        rule.onNodeWithTag("expanded-candidate:110").assertExists()
        rule.runOnIdle { width.value = 320; fontScale.value = 1.5f; landscape.value = false }
        checkVisible()
        rule.onNodeWithTag("expanded-candidate:100").performTouchInput { click() }
        rule.runOnIdle { assertEquals(100, selected); fontScale.value = 1f }
        checkVisible()
        rule.onNodeWithTag("expanded-candidates").performScrollToNode(hasTestTag("expanded-candidate:258"))
        rule.onNodeWithTag("expanded-candidate:258").performTouchInput { click() }
        rule.runOnIdle { assertEquals("Scrolling must preserve the original global index", 258, selected) }
    }

    private fun preservingRailMaterial(block: () -> Unit) {
        val previous = VisualStyles.current
        try {
            block()
        } finally {
            rule.runOnIdle { VisualStyles.current = previous }
        }
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
