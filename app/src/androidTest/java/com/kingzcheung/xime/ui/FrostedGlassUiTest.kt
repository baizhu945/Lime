package com.kingzcheung.xime.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import org.junit.Before

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.SharedPreferencesTestRule
import com.kingzcheung.xime.settings.BackgroundConfig
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.ui.keyboard.KeyButton
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.ui.theme.KeyboardBackdropHost
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pixel-level checks of the real modifiers and key. No IME service or Rime engine is started. */
class FrostedGlassUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepFixtureVisible() {
        rule.runOnUiThread { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @get:Rule val preferences = SharedPreferencesTestRule()
    private val context by lazy {
        object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = preferences.getSharedPreferences()
        }
    }

    @Test fun darkGlassKeysRemainSeparatedFromBlackAndWhiteHosts() {
        var host by mutableStateOf(Color.Black)
        val config = FrostedGlassConfig.defaults(true, true)
        FrostedGlassPreferences.save(context, config, true)
        val effective = com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.read(context, true).frostedGlass
        val savedStyle = com.kingzcheung.xime.ui.theme.VisualStyles.current
        rule.runOnUiThread { com.kingzcheung.xime.ui.theme.VisualStyles.current = com.kingzcheung.xime.ui.theme.VisualStyle.ORIGINAL }
        try {
            rule.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f)) {
                    val saved = rememberKeyboardInputPreferences(true)
                    CompositionLocalProvider(LocalKeyboardInputPreferences provides saved) {
                        MaterialTheme {
                            Box(Modifier.size(320.dp, 160.dp).background(host).testTag("raised-key-panel")
                                .keyboardBackground(null, true, Color.Black, saved.frostedGlass)) {
                                Row(Modifier.padding(16.dp)) {
                                    KeyButton("A", {}, Color(0xFF1C1C1C), Color.White,
                                        Modifier.size(88.dp, 128.dp).testTag("raised-letter"), shadowEnabled = false)
                                    KeyButton("123", {}, Color(0xFF353535), Color.White,
                                        Modifier.size(120.dp, 128.dp).testTag("raised-function"), shadowEnabled = false)
                                }
                            }
                        }
                    }
                }
            }
            for (backdrop in listOf(Color.Black, Color.White)) {
                rule.runOnIdle { host = backdrop }
                val expectedPanel = if (backdrop == Color.Black) 0 else ((1f - effective.backgroundOpacity) * 255f).toInt()
                val panel = awaitImage("raised-key-panel") {
                    abs(android.graphics.Color.red(it.getPixel(4, 4)) - expectedPanel) <= 2
                }
                val panelRed = android.graphics.Color.red(panel.getPixel(4, 4))
                val letterImage = snapshot("raised-letter")
                val functionImage = snapshot("raised-function")
                val letterRed = android.graphics.Color.red(sample(letterImage))
                val functionRed = android.graphics.Color.red(sample(functionImage))
                assertTrue("letter key must remain visible on $backdrop: panel=$panelRed letter=$letterRed", letterRed - panelRed >= 16)
                assertTrue("function key remains distinct on $backdrop: letter=$letterRed function=$functionRed", functionRed - letterRed >= 16)
                save("raised-keys-${if (backdrop == Color.Black) "black" else "white"}", panel)
            }
        } finally {
            rule.runOnUiThread { com.kingzcheung.xime.ui.theme.VisualStyles.current = savedStyle }
        }
    }

    @Test fun keyboardAndNavigationReconstructOneGradientWhileNarrowMarginsStayTransparent() {
        var dark by mutableStateOf(true)
        var narrow by mutableStateOf(false)
        val glass = FrostedGlassConfig(enabled = true, blurRadiusDp = 10f, backgroundOpacity = 0.2f)
        val background = BackgroundConfig(type = "gradient", angle = 90,
            colors = listOf(0x214D8AL, 0xD38650L, 0x346A48L), colorsDark = listOf(0x6739A0L, 0xB58745L, 0x134958L))
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f)) {
                Column {
                    KeyboardBackdropHost(true, background, dark, Color.Black, glass, 200.dp,
                        Modifier.size(320.dp, 200.dp).background(Color.Magenta).testTag("joined-glass")) {
                        Box(Modifier.align(Alignment.TopCenter).width(if (narrow) 160.dp else 320.dp).height(160.dp)
                            .keyboardBackground(background, dark, Color.Black, glass))
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp)
                            .keyboardBackground(background, dark, Color.Black, glass))
                    }
                    Box(Modifier.size(320.dp, 200.dp).background(Color.Magenta).testTag("whole-glass")
                        .keyboardBackground(background, dark, Color.Black, glass))
                }
            }
        }
        fun awaitJoined() {
            awaitImage("whole-glass") { colorDistance(it.getPixel(160, 20), it.getPixel(160, 180)) > 5 }
            awaitImage("joined-glass") { joined ->
                val whole = snapshot("whole-glass")
                (8 until 192 step 8).all { y ->
                    colorDistance(joined.getPixel(160, y), whole.getPixel(160, y)) <= 2
                }
            }
            val joined = snapshot("joined-glass")
            val whole = snapshot("whole-glass")
            for (y in 150..170) for (x in 85..234 step 13) {
                assertTrue("same texture on both sides of nav seam ($x,$y)",
                    colorDistance(joined.getPixel(x, y), whole.getPixel(x, y)) <= 2)
            }
        }
        awaitJoined()
        rule.runOnIdle { narrow = true }
        awaitJoined()
        assertEquals("shared image must not paint outside keyboard", android.graphics.Color.MAGENTA,
            snapshot("joined-glass").getPixel(10, 70))
        rule.runOnIdle { dark = false }
        awaitJoined()
    }

    @Test
    fun changingBlurSoftensBackgroundWhileForegroundTextStaysSharp() {
        var config by mutableStateOf(FrostedGlassConfig(enabled = true, blurRadiusDp = 0f, backgroundOpacity = 1f))
        // A repeatable high-frequency background makes the blur measurable without an external image.
        val background = BackgroundConfig(type = "gradient", angle = 0,
            colors = List(33) { if (it % 2 == 0) 0x183653L else 0xAE7353L })
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.size(320.dp, 160.dp).testTag("blur-panel")
                        .keyboardBackground(background, true, Color(0xFF030608), config),
                        contentAlignment = Alignment.Center) {
                        Text("Aa", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        val sharp = awaitImage("blur-panel") { horizontalVariation(it) > 4.0 }
        val sharpVariation = horizontalVariation(sharp)
        rule.runOnIdle { config = config.copy(blurRadiusDp = 40f) }
        val blurred = awaitImage("blur-panel") { horizontalVariation(it) < sharpVariation * 0.35 }
        assertSameWhiteForeground(sharp, blurred)
        save("01-blur-zero", sharp)
        save("02-blur-forty", blurred)
    }

    @Test
    fun materialOpacityEndpointsWorkInBothThemesAndDisablingRestoresOriginal() {
        var config by mutableStateOf(FrostedGlassConfig(enabled = false, blurRadiusDp = 0f, backgroundOpacity = 0f))
        var dark by mutableStateOf(true)
        var host by mutableStateOf(0xFF173E52.toInt())
        val background = BackgroundConfig(type = "gradient", colors = listOf(0x9B7351L, 0x9B7351L),
            colorsDark = listOf(0x334E64L, 0x334E64L), angle = 0)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f)) {
                Box(Modifier.size(320.dp, 160.dp).background(Color(host)).testTag("tint-panel")
                    .keyboardBackground(background, dark, Color(0xFF030608), config))
            }
        }
        for (isDark in listOf(true, false)) {
            rule.runOnIdle { dark = isDark; config = config.copy(enabled = false); host = 0xFF173E52.toInt() }
            val themeColor = if (isDark) 0xFF334E64.toInt() else 0xFF9B7351.toInt()
            val original = awaitImage("tint-panel") { colorDistance(sample(it), themeColor) <= 2 }
            rule.runOnIdle { config = config.copy(enabled = true, backgroundOpacity = 0f) }
            val transparent = awaitImage("tint-panel") { sample(it) == host }
            assertEquals("zero opacity reveals the actual host", host, sample(transparent))
            rule.runOnIdle { host = 0xFFE82030.toInt() }
            val changedHost = awaitImage("tint-panel") { sample(it) == host }
            assertEquals("unchanged glass parameters follow changing host content", host, sample(changedHost))
            rule.runOnIdle { config = config.copy(backgroundOpacity = 0.5f) }
            awaitImage("tint-panel") { colorDistance(sample(it), blend(themeColor, host, 0.5f)) <= 2 }
            rule.runOnIdle { config = config.copy(backgroundOpacity = 1f) }
            val opaque = awaitImage("tint-panel") { colorDistance(sample(it), themeColor) <= 2 }
            save("opacity-original-$isDark", original)
            save("opacity-glass-$isDark", opaque)
            val maxDelta = (0 until original.height).maxOf { y ->
                (0 until original.width).maxOf { x -> colorDistance(original.getPixel(x, y), opaque.getPixel(x, y)) }
            }
            // The GPU gradient dithers 1/255 on a few pixels; the cached opaque bitmap
            // contains the exact theme RGB. Compare RGB within one quantization step,
            // while requiring every alpha byte to match exactly (real device evidence).
            assertTrue("full opacity is the original theme color, not a black/white overlay; maxRGBDelta=$maxDelta; " +
                "original=${original.config}/${original.colorSpace}; glass=${opaque.config}/${opaque.colorSpace}", maxDelta <= 1)
            for (y in 0 until original.height) for (x in 0 until original.width) {
                assertEquals("opacity must match at every pixel", original.getPixel(x, y) ushr 24, opaque.getPixel(x, y) ushr 24)
            }
            rule.runOnIdle { config = config.copy(enabled = false, backgroundOpacity = 0f) }
            val disabled = awaitImage("tint-panel") { it.sameAs(original) }
            assertTrue("disabled effect must restore the original theme even at zero material opacity", original.sameAs(disabled))
            val appearance = if (isDark) "dark" else "light"
            save("03-material-$appearance-transparent", transparent)
            save("04-material-$appearance-changing-host", changedHost)
            save("05-material-$appearance-original-opaque", opaque)
            save("06-effect-$appearance-disabled", disabled)
        }
    }

    @Test
    fun preferenceUpdatesChangeOnlyKeyFillAndKeepTextAndTapWorking() = withOriginalKeyMaterial {
        val backdrop = Color(0xFF173E52)
        val cap = Color(0xFF49516A)
        var taps = 0
        // An opaque dark material is already legible over either host, so the
        // production legibility policy can leave both key-opacity endpoints intact.
        val initial = FrostedGlassConfig(enabled = false, backgroundOpacity = 1f, keyOpacity = 0f)
        FrostedGlassPreferences.save(context, initial, true)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f)) {
                val saved = rememberKeyboardInputPreferences(true)
                MaterialTheme {
                    CompositionLocalProvider(LocalKeyboardInputPreferences provides saved) {
                        Box(Modifier.size(180.dp, 100.dp).background(backdrop).testTag("key-panel")) {
                            KeyButton("A", { taps++ }, cap, Color.White,
                                Modifier.testTag("glass-key"), fontSize = 40.sp, shadowEnabled = false)
                        }
                    }
                }
            }
        }
        val original = snapshot("key-panel")
        assertEquals(0xFF49516A.toInt(), sample(original))
        rule.runOnIdle { FrostedGlassPreferences.save(context, initial.copy(enabled = true), true) }
        val transparent = awaitImage("key-panel") { sample(it) == 0xFF173E52.toInt() }
        assertSameWhiteForeground(original, transparent)
        rule.runOnIdle { FrostedGlassPreferences.save(context, initial.copy(enabled = true, keyOpacity = 1f), true) }
        val opaque = awaitImage("key-panel") { sample(it) == 0xFF49516A.toInt() }
        assertSameWhiteForeground(transparent, opaque)
        rule.onNodeWithTag("glass-key").performTouchInput { click() }
        rule.runOnIdle { assertEquals("frosted decoration must preserve key gestures", 1, taps) }
        rule.runOnIdle { FrostedGlassPreferences.save(context, initial, true) }
        val disabled = awaitImage("key-panel") { it.sameAs(original) }
        assertTrue("disabling via preferences restores the original cap", disabled.sameAs(original))
        save("07-key-transparent", transparent)
        save("08-key-opaque", opaque)
        save("09-key-disabled", disabled)
    }

    @Test
    fun floatingBackdropMatchesKeyboardTintAndPreservesTransparencyAndText() {
        var dark by mutableStateOf(true)
        var config by mutableStateOf(FrostedGlassConfig(enabled = true, blurRadiusDp = 0f, backgroundOpacity = 0.55f))
        // Equal gradient endpoints avoid interpolation/cropping differences between hosts.
        val base = 0xFF9CACCC.toInt()
        val host = 0xFF173E52.toInt()
        val background = BackgroundConfig(type = "gradient", colors = listOf(0x9CACCCL, 0x9CACCCL))
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f)) {
                Column {
                    Box(Modifier.size(320.dp, 100.dp).background(Color(host)).testTag("keyboard-glass")
                        .keyboardBackground(background, dark, Color.Black, config))
                    Box(Modifier.size(320.dp, 100.dp).background(Color(host)).testTag("floating-glass")) {
                        Box(Modifier.size(320.dp, 100.dp)
                            .keyboardBackground(background, dark, Color.Black, config, translucentSurface = true),
                            contentAlignment = Alignment.Center) {
                            Text("Aa", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        val expected = blend(base, host, 0.55f)
        awaitImage("keyboard-glass") { colorDistance(sample(it), expected) <= 2 }
        val darkFloating = awaitImage("floating-glass") {
            colorDistance(sample(it), expected) <= 2
        }
        save("10-floating-dark-matches-keyboard", darkFloating)
        rule.runOnIdle { dark = false }
        awaitImage("keyboard-glass") { colorDistance(sample(it), expected) <= 2 }
        val lightFloating = awaitImage("floating-glass") {
            colorDistance(sample(it), expected) <= 2
        }
        assertSameWhiteForeground(darkFloating, lightFloating)
        save("11-floating-light-matches-keyboard", lightFloating)
        rule.runOnIdle { dark = true; config = config.copy(backgroundOpacity = 1f) }
        awaitImage("floating-glass") { colorDistance(sample(it), base) <= 2 }
        rule.runOnIdle { config = config.copy(backgroundOpacity = 0f) }
        val transparent = awaitImage("floating-glass") { sample(it) == host }
        assertSameWhiteForeground(darkFloating, transparent)
        save("12-floating-transparent-opaque-text", transparent)
    }

    private fun withOriginalKeyMaterial(block: () -> Unit) {
        // This endpoint oracle is the undecorated cap RGB. Independent visual-material overlays
        // are not part of frosted opacity, just as in darkGlassKeysRemainSeparatedFromBlackAndWhiteHosts.
        val previous = com.kingzcheung.xime.ui.theme.VisualStyles.current
        try {
            rule.runOnUiThread {
                com.kingzcheung.xime.ui.theme.VisualStyles.current = com.kingzcheung.xime.ui.theme.VisualStyle.ORIGINAL
            }
            block()
        } finally {
            rule.runOnUiThread { com.kingzcheung.xime.ui.theme.VisualStyles.current = previous }
        }
    }

    private fun snapshot(tag: String): Bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()

    /** Await the asynchronous background result itself, without fixed sleeps or assumptions about device speed. */
    private fun awaitImage(tag: String, ready: (Bitmap) -> Boolean): Bitmap {
        var result: Bitmap? = null
        rule.waitUntil(timeoutMillis = 15_000) {
            val current = snapshot(tag)
            if (ready(current)) { result = current; true } else false
        }
        return checkNotNull(result)
    }

    private fun sample(bitmap: Bitmap): Int = bitmap.getPixel(bitmap.width / 4, bitmap.height / 4)

    private fun blend(foreground: Int, backdrop: Int, alpha: Float): Int {
        fun channel(shift: Int): Int = (((foreground ushr shift and 255) * alpha) +
            ((backdrop ushr shift and 255) * (1f - alpha))).toInt()
        return android.graphics.Color.rgb(channel(16), channel(8), channel(0))
    }

    private fun horizontalVariation(bitmap: Bitmap): Double {
        val y = bitmap.height * 4 / 5
        var sum = 0
        for (x in 12 until bitmap.width - 12) {
            sum += abs(android.graphics.Color.red(bitmap.getPixel(x, y)) -
                android.graphics.Color.red(bitmap.getPixel(x - 1, y)))
        }
        return sum.toDouble() / (bitmap.width - 24)
    }

    private fun colorDistance(first: Int, second: Int): Int = maxOf(
        abs(android.graphics.Color.red(first) - android.graphics.Color.red(second)),
        abs(android.graphics.Color.green(first) - android.graphics.Color.green(second)),
        abs(android.graphics.Color.blue(first) - android.graphics.Color.blue(second)),
    )

    private fun assertSameWhiteForeground(before: Bitmap, after: Bitmap) {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        fun mask(bitmap: Bitmap) = BooleanArray(bitmap.width * bitmap.height) { index ->
            bitmap.getPixel(index % bitmap.width, index / bitmap.width) == android.graphics.Color.WHITE
        }
        val beforeMask = mask(before)
        val afterMask = mask(after)
        val beforeCount = beforeMask.count { it }
        assertTrue("test must contain solid foreground text pixels", beforeCount > 50)
        // Anti-aliased boundary pixels blend with the changed background. Compare
        // every solid interior pixel and allow only a small edge-rounding tolerance.
        assertTrue("foreground must retain its sharp text coverage",
            abs(beforeCount - afterMask.count { it }) <= maxOf(8, beforeCount / 20))
        for (y in 1 until before.height - 1) for (x in 1 until before.width - 1) {
            val index = y * before.width + x
            if (beforeMask[index] && beforeMask[index - 1] && beforeMask[index + 1] &&
                beforeMask[index - before.width] && beforeMask[index + before.width]) {
                assertTrue("foreground interior must stay opaque at ($x, $y)", afterMask[index])
            }
        }
    }

    private fun save(name: String, bitmap: Bitmap) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(target.getExternalFilesDir(null)), "frosted-glass").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
