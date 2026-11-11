package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TabletKeySizingTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tabletLabelsScaleTogetherWithCompactModeKeys() = checkLayout(760, 420, 1f, true)
    @Test fun phoneKanaAndFunctionLabelsRetainTheirNormalProportions() = checkLayout(360, 240, 1f, false)
    @Test fun smallFloatingLayoutWithLargeSystemFontRemainsReadableAndUnclipped() = checkLayout(280, 200, 1.3f, false)

    private fun checkLayout(width: Int, height: Int, fontScale: Float, tablet: Boolean) {
        var gridMetrics: KeyVisualMetrics? = null
        rule.setContent {
            // Physical pixel bounds permit a tablet-sized layout on the dedicated phone emulator.
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale),
                LocalKeyboardMetricsProbe provides { gridMetrics = it },
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(keyTextScale = 1f)) {
                MaterialTheme {
                    JapaneseKanaKeyboardLayout({}, {}, Color(0xFF2C3036), Color.White, Color(0xFF405F91),
                        modifier = Modifier.size(width.dp, height.dp).testTag("sizing-keyboard"), shadowEnabled = false)
                }
            }
        }
        val labels = listOf("変換", "123", "!@#", "あ", "わ")
        val sizes = labels.mapIndexed { index, label ->
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            assertFalse("$label overflow at $width x $height: ${layout.size}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, constraints=${layout.layoutInput.constraints}, style=${layout.layoutInput.style}", layout.hasVisualOverflow)
            val metrics = requireNotNull(gridMetrics)
            val sharedScale = KeyboardKeyMetrics.contentScale(metrics.capShortEdge, metrics.capShortEdge)
            val expected = KeyboardKeyMetrics.labelSizeSp(label, if (index in 1..2) 16f else 18f,
                layout.layoutInput.constraints.maxWidth.toFloat(), layout.layoutInput.constraints.maxHeight.toFloat(),
                fontScale, scaleOverride = sharedScale)
            assertEquals("$label must use the common grid scale and its real fitting bounds", expected,
                layout.layoutInput.style.fontSize.value, 0.1f)
            layout.layoutInput.style.fontSize.value
        }
        val kanaSize = sizes[3]
        assertEquals(kanaSize, sizes[4], 0.1f)
        assertEquals("Mode labels share a compact size", sizes[1], sizes[2], 0.1f)
        assertTrue("Conversion label remains readable without clipping", sizes[0] in kanaSize * .8f..kanaSize)
        assertTrue("Mode labels are compact without becoming tiny", sizes[1] in kanaSize * .8f..kanaSize)
        if (tablet) assertTrue("tablet labels remain phone sized: $sizes", minOf(sizes.first(), kanaSize) >= 24f)
        else assertTrue("phone/floating labels enlarged unexpectedly: $sizes", maxOf(sizes.first(), kanaSize) <= 18f)
        val cells = listOf("kana-convert", "kana-number", "kana-symbol", "kana-key:a", "kana-key:wa", "kana-delete", "kana-space", "kana-enter")
            .map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        cells.forEach { assertEquals(cells.first().width, it.width, 1f); assertEquals(cells.first().height, it.height, 1f) }
        val language = rule.onNodeWithContentDescription("语言切换", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val enter = rule.onNodeWithContentDescription("回车", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(language.width, enter.width, 1f)
        if (tablet) assertTrue("tablet function icon is fixed at 20dp", language.width >= 30f)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "cyime").apply { mkdirs() }
        val output = File(directory, "tablet-key-sizing-$width-$height.png")
        output.outputStream().use { rule.onNodeWithTag("sizing-keyboard").captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
