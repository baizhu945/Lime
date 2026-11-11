package com.kingzcheung.xime.ui

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.LetterLayouts
import com.kingzcheung.xime.settings.KeyboardRow
import com.kingzcheung.xime.settings.KeyboardRowsLayout
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual key bounds and renders, with the calibrated display and no preference writes. */
class WideQwertyRenderingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun compactAndWideLayoutsUseTheExpectedCentresAndEqualTouchCells() = withStandardLetterFrame { app ->
        assertEquals(listOf(35, 35, 35, 35, 34, 34, 34, 34, 34, 34),
            allocateWeightedRowPixels(344, List(10) { 1f }))
        assertEquals(listOf(53, 35, 34, 34, 34, 34, 34, 34, 52),
            allocateWeightedRowPixels(344, listOf(51.6f) + List(7) { 34.4f } + 51.6f))
        assertEquals(listOf(51, 241, 52), allocateWeightedRowPixels(344, listOf(1.5f, 7f, 1.5f)))
        val oldOrientation = rule.activity.requestedOrientation
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        try {
            rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            val target = requireNotNull(keyboardPhysicalMetrics(rule.activity, rule.activity.display, 1f))
                .dpFromMillimeters(CALIBRATED_QWERTY_PITCH_MM)
            val calibratedWidth = ceil(16f + target * 10f).toInt()
            val dimensions = mutableIntStateOf(360)
            val ascii = mutableStateOf(false)
            var gridMetrics: KeyVisualMetrics? = null
            rule.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalKeyboardExplicitWidth provides true,
                    LocalKeyboardMetricsProbe provides { gridMetrics = it },
                    LocalKeyboardInputPreferences provides KeyboardInputPreferences(splitKeyboardEnabled = false)) {
                    MaterialTheme {
                        val vm = remember { KeyboardViewModel(app) }
                        KeyboardLayout({}, vm, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                            KeyboardUiState(currentSchemaId = "rime_ice", isAsciiMode = ascii.value, isDarkTheme = true),
                            ascii.value, Modifier.requiredSize(dimensions.intValue.dp, 389.dp).testTag("wide-qwerty-render"))
                    }
                }
            }
            for (english in listOf(false, true)) for (width in listOf(360, (calibratedWidth * .8f).toInt(), calibratedWidth,
                (calibratedWidth * 1.25f).toInt())) {
                rule.runOnIdle { KeysConfigHelper.setActiveKeyboardSchema("rime_ice"); ascii.value = english; dimensions.intValue = width }
                rule.waitForIdle()
                val frame = rule.onNodeWithTag("wide-qwerty-render", true)
                assertEquals("fixture must not silently clamp", width, frame.fetchSemanticsNode().size.width)
                fun measuredBounds(node: androidx.compose.ui.semantics.SemanticsNode) =
                    Rect(node.positionInRoot, Size(node.size.width.toFloat(), node.size.height.toFloat()))
                fun bounds(key: String) = measuredBounds(rule.onNodeWithTag("qwerty-key:$key", true).fetchSemanticsNode())
                val metrics = requireNotNull(gridMetrics)
                val host = measuredBounds(frame.fetchSemanticsNode())
                val gutter = metrics.gutterX.roundToInt()
                val contentWidth = width - gutter * 2
                val left = host.left + gutter
                // Independent reference for the documented physical interpolation and integer Row allocation.
                val start = target * (KeyVisualPolicy.Qwerty.referenceCellWidth * KeyVisualPolicy.ScaleMax /
                    CALIBRATED_QWERTY_REFERENCE_CELL_DP)
                val t = ((metrics.cellWidthDp - start) / (target - start)).coerceIn(0f, 1f)
                val progress = t * t * (3f - 2f * t)
                val leadingFraction = .5f - .25f * progress
                val trailingFraction = .5f + .25f * progress
                val shiftWeight = 1.5f - .75f * progress
                val deleteWeight = 1.5f + .75f * progress
                if (width == 360) {
                    assertEquals(.5f, leadingFraction, 0.000001f)
                    assertEquals(1.5f, shiftWeight, 0.000001f)
                } else if (width >= calibratedWidth) {
                    assertEquals(.25f, leadingFraction, 0.000001f)
                    assertEquals(.75f, shiftWeight, 0.000001f)
                    assertEquals(.5f, shiftWeight - leadingFraction, 0.000001f)
                    assertEquals(2.25f, deleteWeight, 0.000001f)
                }
                fun checkRow(keys: String, widths: List<Int>, origin: Float) {
                    var edge = origin
                    keys.forEachIndexed { index, key ->
                        val actual = bounds(key.toString())
                        val expectedWidth = widths[index].toFloat()
                        val details = "$width/${if (english) "en" else "zh"}/$key: $actual, allocation=$widths"
                        assertEquals("$details width", expectedWidth, actual.width, 1f)
                        assertEquals("$details left", edge, actual.left, 1f)
                        assertEquals("$details right", edge + expectedWidth, actual.right, 1f)
                        assertEquals("$details centre", edge + expectedWidth / 2f, actual.center.x, 1f)
                        edge += expectedWidth
                    }
                }
                val top = allocateWeightedRowPixels(contentWidth, List(10) { 1f })
                checkRow("qwertyuiop", top, left)
                val leadingInset = metrics.cellWidthDp * leadingFraction
                val trailingInset = metrics.cellWidthDp * trailingFraction
                val homeUnit = (contentWidth - leadingInset - trailingInset) / 9f
                val home = allocateWeightedRowPixels(contentWidth,
                    listOf(homeUnit + leadingInset) + List(7) { homeUnit } + (homeUnit + trailingInset))
                checkRow("asdfghjkl", home, left)
                val aCenter = measuredBounds(rule.onNodeWithText("a", useUnmergedTree = true).fetchSemanticsNode()).center.x
                val lCenter = measuredBounds(rule.onNodeWithText("l", useUnmergedTree = true).fetchSemanticsNode()).center.x
                val leadingPadding = leadingInset.roundToInt()
                val trailingPadding = trailingInset.roundToInt()
                assertEquals("A stays centred in its actual unpadded content cell",
                    left + leadingPadding + (home.first() - leadingPadding) / 2f, aCenter, 1f)
                assertEquals("L stays centred in its actual unpadded content cell",
                    left + home.dropLast(1).sum() + (home.last() - trailingPadding) / 2f, lCenter, 1f)
                val bottom = allocateWeightedRowPixels(contentWidth, listOf(shiftWeight, 7f, deleteWeight))
                checkRow("zxcvbnm", allocateWeightedRowPixels(bottom[1], List(7) { 1f }), left + bottom[0])
                val delete = measuredBounds(rule.onNodeWithTag("qwerty-delete-key", true).fetchSemanticsNode())
                assertEquals("Delete retains its allocated outside width", bottom[2].toFloat(), delete.width, 1f)
                assertEquals("Delete retains its allocated outside edge", left + contentWidth, delete.right, 1f)
                for (key in "qwertyuiopsdfghjkzxcvbnm") {
                    assertEquals("$width/$key equal touch width", bounds("q").width, bounds(key.toString()).width, 1f)
                }
                val folder = File(app.getExternalFilesDir(null), "wide-qwerty-calibration").apply { mkdirs() }
                File(folder, "qwerty-$width-${if (english) "en" else "zh"}.png").outputStream().use {
                    frame.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally {
            KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
            rule.runOnUiThread { rule.activity.requestedOrientation = oldOrientation }
        }
    }

    /** Weighted Row rounds each child, then assigns the signed residual in child order. */
    private fun allocateWeightedRowPixels(width: Int, weights: List<Float>): List<Int> {
        val unit = width / weights.sum()
        val allocated = weights.map { (unit * it).roundToInt() }.toMutableList()
        var remainder = width - allocated.sum()
        allocated.indices.forEach { index ->
            val correction = when { remainder > 0 -> 1; remainder < 0 -> -1; else -> 0 }
            allocated[index] += correction
            remainder -= correction
        }
        check(remainder == 0) { "Reference row did not allocate its complete constraint" }
        return allocated
    }

    private fun withStandardLetterFrame(block: (Application) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        val fields = listOf("_zhRowsLayout", "_zhRowsBase", "_zhRows", "_enRowsLayout", "_enRowsBase", "_enRows")
            .map { name -> KeysConfigHelper::class.java.getDeclaredField(name).apply { isAccessible = true } }
        val savedRows = fields.map { it.get(KeysConfigHelper) }
        val active = KeysConfigHelper::class.java.getDeclaredField("_activeSchemaId").apply { isAccessible = true }
        val savedSchema = active.get(KeysConfigHelper) as String
        val chosen = LetterLayouts::class.java.getDeclaredField("chosen\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val choices = chosen.get(LetterLayouts) as MutableState<Map<InputLanguage, String>>
        val savedChoices = choices.value
        try {
            choices.value = savedChoices - InputLanguage.CHINESE - InputLanguage.ENGLISH
            val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { it.map(Char::toString) }
            fields.forEach { field ->
                field.set(KeysConfigHelper, if (field.name.endsWith("Layout"))
                    KeyboardRowsLayout(rows.map { KeyboardRow(it) }) else rows)
            }
            block(app)
        } finally {
            choices.value = savedChoices
            KeysConfigHelper.setActiveKeyboardSchema(savedSchema)
            fields.zip(savedRows).forEach { (field, value) -> field.set(KeysConfigHelper, value) }
        }
    }
}
