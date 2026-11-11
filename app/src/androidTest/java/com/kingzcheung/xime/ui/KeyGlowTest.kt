package com.kingzcheung.xime.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class KeyGlowTest {
    @get:Rule val rule = createComposeRule()
    private fun snapshot(tag: String): Bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
    private fun save(name: String, bitmap: Bitmap) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun quickTapShrinksWholeCapThenRecoversAndDisappearsAtHalfSecond() {
        var taps = 0
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(keyGlowEnabled = true, keyAnimationEnabled = true)) {
            Box(Modifier.size(160.dp, 110.dp).background(Color.White).testTag("frame").padding(24.dp)) {
                KeyButton("A", { taps++ }, Color.DarkGray, Color.White, Modifier.testTag("key"), shadowEnabled = false)
            }
        } } }
        rule.mainClock.autoAdvance = false
        val before = snapshot("frame")
        rule.onNodeWithTag("key").performTouchInput { down(center); up() }
        rule.mainClock.advanceTimeBy(96)
        val glow = snapshot("frame")
        assertFalse(before.sameAs(glow))
        // Outer margin and rounded corners stay unchanged: no particles may escape the cap.
        val occupied = (0 until before.width).filter { x -> (0 until before.height).any { y ->
            before.getPixel(x, y) != android.graphics.Color.WHITE
        } }
        for (x in 0 until before.width) for (y in 0 until before.height) {
            if (x < occupied.first() || x > occupied.last()) assertEquals(before.getPixel(x, y), glow.getPixel(x, y))
        }
        save("key-glow-active.png", glow)
        fun coloredWidth(bitmap: Bitmap): Int {
            val cap = (0 until bitmap.width).filter { bitmap.getPixel(it, bitmap.height / 2) != android.graphics.Color.WHITE }
            return cap.last() - cap.first() + 1
        }
        assertTrue("the background must shrink with the letters", coloredWidth(glow) < coloredWidth(before))
        rule.mainClock.advanceTimeBy(160)
        val recovered = snapshot("frame")
        assertEquals("cap must recover by 235 ms", coloredWidth(before), coloredWidth(recovered))
        assertFalse(before.sameAs(recovered))
        save("key-glow-recovered.png", recovered)
        rule.mainClock.advanceTimeBy(288)
        assertTrue("animation must finish at half a second (plus frame scheduling)", before.sameAs(snapshot("frame")))
        rule.runOnIdle { assertEquals(1, taps) }
    }

    @Test fun heldKeyKeepsItsColourUntilReleaseThenFades() {
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(keyGlowEnabled = true, keyAnimationEnabled = true)) {
            Box(Modifier.size(160.dp, 110.dp).background(Color(0xFF191C22)).testTag("held-frame").padding(24.dp)) {
                KeyButton("←", {}, Color(0xFF525252), Color.White, Modifier.testTag("held-key"), shadowEnabled = false)
            }
        } } }
        rule.mainClock.autoAdvance = false
        val before = snapshot("held-frame")
        rule.onNodeWithTag("held-key").performTouchInput { down(center) }
        for (ms in listOf(100L, 600L, 1500L)) {
            rule.mainClock.advanceTimeBy(if (ms == 100L) 100L else ms - (if (ms == 600L) 100L else 600L))
            val frame = snapshot("held-frame")
            save("held-glow-${ms.toString().padStart(4, '0')}.png", frame)
            assertFalse("a held key stays lit at $ms ms", before.sameAs(frame))
        }
        rule.onNodeWithTag("held-key").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(80)
        save("held-glow-release-080.png", snapshot("held-frame"))
        rule.mainClock.advanceTimeBy(220)
        val released = snapshot("held-frame")
        save("held-glow-release-300.png", released)
        assertTrue("released key returns to rest", before.sameAs(released))
    }

    @Test fun exportActualSquareGlowFrames() {
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(keyGlowEnabled = true, keyAnimationEnabled = true)) {
            Box(Modifier.size(180.dp, 130.dp).background(Color(0xFF191C22)).testTag("square-frame").padding(24.dp)) {
                KeyButton("ABC", {}, Color(0xFF525252), Color.White, Modifier.testTag("square-key"), shadowEnabled = false)
            }
        } } }
        rule.mainClock.autoAdvance = false
        val before = snapshot("square-frame")
        save("square-glow-000.png", before)
        rule.onNodeWithTag("square-key").performTouchInput { down(center); up() }
        for (frame in 1..34) {
            rule.mainClock.advanceTimeByFrame()
            val image = snapshot("square-frame")
            save("square-glow-${(frame * 16).toString().padStart(3, '0')}.png", image)
            if (frame == 34) assertTrue("finished glow restores original pixels", before.sameAs(image))
        }
    }

    @Test fun featheredParticleRetainsSquareCornersInsteadOfBecomingACircle() {
        rule.setContent {
            Canvas(Modifier.size(100.dp).background(Color.Black).testTag("square-shape")) {
                drawKeyGlowSquare(Color.White, center, size.minDimension * .6f, 1f)
            }
        }
        val image = snapshot("square-shape")
        fun red(x: Float, y: Float) = android.graphics.Color.red(image.getPixel(
            (image.width * x).toInt(), (image.height * y).toInt()))
        // These points are inside the square corners but outside a radius-.3 circle.
        for (x in listOf(.25f, .75f)) for (y in listOf(.25f, .75f))
            assertTrue("the four square corners must be visible", red(x, y) > 50)
        assertEquals("narrow feather must not spill far outside the square", 0, red(.85f, .5f))
    }

    @Test fun exportActualSquareGlowGrid() {
        val labels = listOf("分词", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")
        rule.setContent { MaterialTheme { CompositionLocalProvider(
            LocalKeyboardInputPreferences provides KeyboardInputPreferences(keyGlowEnabled = true, keyAnimationEnabled = true),
            LocalKeyCornerRadius provides 5.dp,
        ) {
            Column(Modifier.size(338.dp, 199.dp).background(Color.Black).testTag("glow-grid")) {
                repeat(3) { row -> Row(Modifier.weight(1f)) {
                    repeat(3) { col ->
                        val key = row * 3 + col
                        KeyButton(labels[key], {}, Color(0xFF38383A), Color.White,
                            Modifier.weight(1f).fillMaxHeight().testTag("glow-grid-$key"),
                            swipeText = (key + 1).toString(), shadowEnabled = false)
                    }
                } }
            }
        } } }
        rule.mainClock.autoAdvance = false
        val before = snapshot("glow-grid")
        save("square-grid-000.png", before)
        val tapFrames = listOf(6, 28, 50, 66, 81, 97, 115, 137, 162)
        for (frame in 1..200) {
            val key = tapFrames.indexOf(frame)
            if (key >= 0) rule.onNodeWithTag("glow-grid-$key").performTouchInput { down(center); up() }
            rule.mainClock.advanceTimeByFrame()
            val image = snapshot("glow-grid")
            save("square-grid-${frame.toString().padStart(3, '0')}.png", image)
            if (frame == 200) assertTrue(before.sameAs(image))
        }
    }

    @Test fun repeatedPressesRestartDecorationAndDisableRemovesItImmediately() {
        var enabled by mutableStateOf(true)
        var taps = 0
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(keyGlowEnabled = enabled, keyAnimationEnabled = enabled)) {
            KeyButton("A", { taps++ }, Color.DarkGray, Color.White, Modifier.size(100.dp, 60.dp).testTag("key"), shadowEnabled = false)
        } } }
        rule.mainClock.autoAdvance = false
        val before = snapshot("key")
        repeat(2) {
            rule.onNodeWithTag("key").performTouchInput { down(center); up() }
            rule.mainClock.advanceTimeBy(112)
            assertFalse(before.sameAs(snapshot("key")))
        }
        rule.runOnIdle { enabled = false }
        rule.mainClock.advanceTimeByFrame()
        assertTrue(before.sameAs(snapshot("key")))
        rule.onNodeWithTag("key").performTouchInput { down(center); up() }
        rule.mainClock.advanceTimeBy(112)
        assertTrue(before.sameAs(snapshot("key")))
        rule.runOnIdle { assertEquals(3, taps) }
    }

    @Test fun textIconSwipeKanaAndSpaceKeysAllUseTheSameDecoration() {
        var taps = 0
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(keyGlowEnabled = true, keyAnimationEnabled = true)) { Column(Modifier.width(160.dp)) {
            val color = Color.DarkGray
            KeyButton("A", { taps++ }, color, Color.White, Modifier.height(55.dp).testTag("plain"), shadowEnabled = false)
            SwipeableKeyButton("Q", { taps++ }, color, Color.White, Modifier.height(55.dp).testTag("swipe"), shadowEnabled = false)
            IconKeyButton(rememberVectorPainter(Icons.Default.Language), { taps++ }, color, Color.White, Modifier.height(55.dp).testTag("icon"), shadowEnabled = false)
            SwipeableIconKeyButton(rememberVectorPainter(Icons.Default.Language), { taps++ }, color, Color.White, Modifier.height(55.dp).testTag("swipe-icon"), shadowEnabled = false)
            KanaFlickButton(japaneseKanaKeys.first(), { taps++ }, color, Color.White, Modifier.height(55.dp).testTag("kana"), shadowEnabled = false)
            SpaceKeyButton({ taps++ }, color, Color.White, modifier = Modifier.height(55.dp).testTag("space"), shadowEnabled = false)
        } } } }
        rule.mainClock.autoAdvance = false
        listOf("plain", "swipe", "icon", "swipe-icon", "kana", "space").forEach { tag ->
            val before = snapshot(tag)
            rule.onNodeWithTag(tag).performTouchInput { down(center); up() }
            rule.mainClock.advanceTimeBy(96)
            assertFalse("$tag missing glow", before.sameAs(snapshot(tag)))
            rule.mainClock.advanceTimeBy(448)
            assertTrue("$tag did not fade", before.sameAs(snapshot(tag)))
        }
        rule.runOnIdle { assertEquals(6, taps) }
    }

    @Test fun defaultKeyboardHasNoDecorationAfterRapidTaps() {
        var taps = 0
        rule.setContent { MaterialTheme {
            KeyButton("A", { taps++ }, Color.DarkGray, Color.White,
                Modifier.size(100.dp, 60.dp).testTag("key"), shadowEnabled = false)
        } }
        rule.mainClock.autoAdvance = false
        val before = snapshot("key")
        repeat(20) { rule.onNodeWithTag("key").performTouchInput { down(center); up() } }
        rule.mainClock.advanceTimeBy(112)
        assertTrue(before.sameAs(snapshot("key")))
        rule.runOnIdle { assertEquals(20, taps) }
    }

    @Test fun glowAndMotionOperateIndependentlyAndKeepEdgeTapsUsable() {
        var glow by mutableStateOf(false)
        var motion by mutableStateOf(false)
        var taps = 0
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalKeyboardInputPreferences provides
            KeyboardInputPreferences(keyGlowEnabled = glow, keyAnimationEnabled = motion)) {
            Box(Modifier.size(160.dp, 110.dp).background(Color.White).testTag("frame").padding(24.dp)) {
                KeyButton("A", { taps++ }, Color.DarkGray, Color.White, Modifier.testTag("key"), shadowEnabled = false)
            }
        } } }
        rule.mainClock.autoAdvance = false
        fun capWidth(bitmap: Bitmap): Int {
            val xs = (0 until bitmap.width).filter { bitmap.getPixel(it, bitmap.height / 2) != android.graphics.Color.WHITE }
            return xs.last() - xs.first() + 1
        }
        val before = snapshot("frame")
        val keyBounds = rule.onNodeWithTag("key").fetchSemanticsNode().boundsInRoot
        for (g in listOf(false, true)) for (m in listOf(false, true)) {
            rule.runOnIdle { glow = g; motion = m }
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("key").performTouchInput { down(center); up() }
            rule.mainClock.advanceTimeBy(96)
            val active = snapshot("frame")
            assertEquals(g || m, !before.sameAs(active))
            assertEquals(m, capWidth(active) < capWidth(before))
            // An edge tap during shrink must still enter the key's full original target.
            rule.onNodeWithTag("key").performTouchInput {
                down(androidx.compose.ui.geometry.Offset(width * .02f, height * .5f)); up()
            }
            rule.mainClock.advanceTimeBy(272)
            val recovered = snapshot("frame")
            assertEquals(capWidth(before), capWidth(recovered))
            assertEquals("only glow remains after cap recovery", g, !before.sameAs(recovered))
            rule.mainClock.advanceTimeBy(288)
            assertTrue(before.sameAs(snapshot("frame")))
            val afterBounds = rule.onNodeWithTag("key").fetchSemanticsNode().boundsInRoot
            assertEquals(keyBounds.width, afterBounds.width, .1f)
            assertEquals(keyBounds.height, afterBounds.height, .1f)
        }
        rule.runOnIdle { assertEquals(8, taps) }
        // Toggling during an active press must remove the old clock and scaled layer.
        rule.runOnIdle { glow = true; motion = true }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("key").performTouchInput { down(center); up() }
        rule.mainClock.advanceTimeBy(96)
        rule.runOnIdle { glow = false; motion = false }
        rule.mainClock.advanceTimeByFrame()
        assertTrue(before.sameAs(snapshot("frame")))
    }

    @Test fun animationNeverShrinksEmojiHitTargetsDuringRapidEdgeTaps() {
        var taps = 0
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(keyGlowEnabled = true, keyAnimationEnabled = true)) {
            EmojiButton("🙂", { taps++ }, Modifier.size(100.dp).testTag("emoji"))
        } } }
        rule.mainClock.autoAdvance = false
        repeat(3) {
            rule.onNodeWithTag("emoji").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .02f, height * .5f)) }
            rule.mainClock.advanceTimeBy(80)
        }
        rule.runOnIdle { assertEquals(3, taps) }
    }

    @Test fun japaneseRowsShareCellSizesAndReadableLabelScale() = withOriginalKanaMaterial {
        rule.setContent { MaterialTheme { JapaneseKanaKeyboardLayout({}, {}, Color(0xFF2C2D32), Color.White,
            Color(0xFF4A587E), modifier = Modifier.size(360.dp, 260.dp).testTag("japanese")) } }
        val sizes = listOf("kana-convert", "kana-number", "kana-symbol", "kana-key:a", "kana-key:wa", "kana-delete", "kana-space", "kana-enter")
            .map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        sizes.forEach { assertEquals(sizes[0].width, it.width, 1f); assertEquals(sizes[0].height, it.height, 1f) }
        val fontSizes = listOf("変換", "123", "!@#", "あ", "わ").map { label ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onNodeWithText(label).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.single().layoutInput.style.fontSize.value
        }
        listOf(fontSizes[3], fontSizes[4]).forEach { assertEquals(fontSizes[0], it, 0.1f) }
        assertEquals("Mode labels share a compact size", fontSizes[1], fontSizes[2], 0.1f)
        assertTrue("Mode labels remain smaller than the kana keys", fontSizes[1] < fontSizes[0])
        assertTrue("Mode labels must remain readable", fontSizes[1] >= fontSizes[0] * .8f)
        rule.onNodeWithText("、").assertExists()
        rule.onNodeWithText("。").assertDoesNotExist()
        rule.onNodeWithText("？").assertDoesNotExist()
        val cap = snapshot("kana-punctuation")
        assertEquals("punctuation is an ordinary gray key", Color(0xFF2C2D32).value,
            Color(cap.getPixel(cap.width / 4, cap.height / 4)).value)
        save("japanese-key-proportions.png", snapshot("japanese"))
    }

    private fun withOriginalKanaMaterial(block: () -> Unit) {
        // This colour-role calibration expects the undecorated gray cap exactly.
        val previous = com.kingzcheung.xime.ui.theme.VisualStyles.current
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                com.kingzcheung.xime.ui.theme.VisualStyles.current = com.kingzcheung.xime.ui.theme.VisualStyle.ORIGINAL
            }
            block()
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                com.kingzcheung.xime.ui.theme.VisualStyles.current = previous
            }
        }
    }
}
