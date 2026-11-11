package com.kingzcheung.xime.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.menubar.ClipboardBoardView
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises disappearing lazy items, not only an alpha helper or an expanded end state. */
class ClipboardPinnedMotionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun extraPinnedRowsFadeInAndRemainPaintedDuringFadeOut() {
        val records = (1L..6L).map {
            ClipboardItem(it, "固定记录 $it\n第一行内容\n第二行内容\n第三行内容", timestamp = 10 - it)
        }
        // Pins fold only when unpinned history sits below them.
        val history = records + ClipboardItem(7L, "最近复制", timestamp = 1)
        var pasted = 0
        rule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(),
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.requiredSize(360.dp, 400.dp)) {
                        ClipboardBoardView(history, emptyList(), false, false,
                            records.mapTo(mutableSetOf()) { "text:${it.id}" }, false, null,
                            onBack = {}, onQuickSend = {}, onSelectText = { pasted++ }, onSelectImage = {},
                            onSplit = { _, _ -> }, onAddQuick = {}, onPinsChange = {}, onRemove = {},
                            onPhotoAccess = {}, onPick = {}, onSystemPaste = {}, onShare = { _, _ -> },
                            onPullRemote = null)
                    }
                }
            }
        }
        rule.waitForIdle()
        val controls = rule.onNodeWithTag("clipboard-controls").fetchSemanticsNode().boundsInRoot
        val collapsed = capture("pins-collapsed.png")
        rule.onNodeWithTag("clipboard-card:text:3").assertDoesNotExist()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("clipboard-pins-toggle").performClick()
        // Lazy item animations are registered during layout, after the click's
        // recomposition. Render that layout before starting the sampling interval.
        rule.mainClock.advanceTimeByFrame()
        capture("pins-opening-start.png")
        rule.mainClock.advanceTimeBy(80)
        val opening = capture("pins-opening.png")
        rule.mainClock.advanceTimeBy(320)
        val expanded = capture("pins-expanded.png")
        val board = rule.onNodeWithTag("clipboard-board").fetchSemanticsNode().boundsInRoot
        // Right column: the recent record slides down the left column while pins open.
        val secondRow = rule.onNodeWithTag("clipboard-card:text:4").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        // Sample blank card background, away from text, border, and rounded corners.
        val x = (secondRow.left - board.left + 4f).roundToInt()
        val y = (secondRow.center.y - board.top).roundToInt()
        val emptyColor = collapsed.getPixel(x, y)
        val fullColor = expanded.getPixel(x, y)
        val openingColor = opening.getPixel(x, y)
        assertTrue("fixture distinguishes empty background from a pinned card", distance(emptyColor, fullColor) > 12)
        assertTrue("opening must show a partial fade rather than the final row immediately: x=$x y=$y empty=${Integer.toHexString(emptyColor)} opening=${Integer.toHexString(openingColor)} full=${Integer.toHexString(fullColor)}",
            distance(openingColor, emptyColor) > 2 && distance(openingColor, fullColor) > 2)
        assertEquals(controls, rule.onNodeWithTag("clipboard-controls").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("clipboard-pins-toggle").performClick()
        rule.mainClock.advanceTimeByFrame()
        capture("pins-closing-start.png")
        rule.mainClock.advanceTimeBy(80)
        val closing = capture("pins-closing.png")
        val closingColor = closing.getPixel(x, y)
        android.util.Log.i("ClipboardPinnedMotion", "x=$x y=$y empty=${Integer.toHexString(emptyColor)} opening=${Integer.toHexString(openingColor)} full=${Integer.toHexString(fullColor)} closing=${Integer.toHexString(closingColor)}")
        assertTrue("removed pin must remain painted during fade-out: x=$x y=$y empty=${Integer.toHexString(emptyColor)} closing=${Integer.toHexString(closingColor)} full=${Integer.toHexString(fullColor)}",
            distance(closingColor, emptyColor) > 2 && distance(closingColor, fullColor) > 2)
        rule.mainClock.advanceTimeBy(320)
        val collapsedAgain = capture("pins-collapsed-again.png")
        assertTrue("finished collapse removes the painted extra row", distance(emptyColor, collapsedAgain.getPixel(x, y)) <= 2)
        rule.onNodeWithTag("clipboard-card:text:3").assertDoesNotExist()
        assertEquals(controls, rule.onNodeWithTag("clipboard-controls").fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { assertEquals("expansion must not paste any item", 0, pasted) }
        rule.mainClock.autoAdvance = true
    }

    private fun distance(a: Int, b: Int): Int = maxOf(
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
        abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
        abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)),
    )

    private fun capture(name: String): Bitmap {
        val bitmap = rule.onNodeWithTag("clipboard-board").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "clipboard-pinned-motion").apply { mkdirs() }
        File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bitmap
    }
}