package com.kingzcheung.xime.ui

import android.graphics.Bitmap
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.ui.keyboard.HandwritingKeyboardLayout
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.keyboardPanelBackground
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HandwritingFullscreenGlassTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fullscreenFooterSharesTheGlassMaterialWithoutPaintingTheWritingAreaOrUnusedWidth() {
        var dark by mutableStateOf(true)
        var glassEnabled by mutableStateOf(true)
        var offset by mutableIntStateOf(-40)
        var hostColor by mutableStateOf(Color.Red)
        var action = ""
        val baseContext = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val fixedContext = object : ContextWrapper(baseContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val original = super.getSharedPreferences(name, mode)
                if (name != "kime_settings") return original
                return object : SharedPreferences by original {
                    override fun getString(key: String?, defValue: String?): String? =
                        if (key == "keyboard_theme") "pure_black" else original.getString(key, defValue)
                }
            }
        }
        rule.setContent {
            val background = if (dark) Color.Black else Color.White
            val foreground = if (dark) Color.White else Color.Black
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalContext provides fixedContext,
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(
                    frostedGlass = FrostedGlassConfig.defaults(dark, glassEnabled)),
            ) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Column {
                        Box(Modifier.requiredSize(360.dp, 480.dp).background(hostColor)
                            .testTag("fullscreen-handwriting-host")) {
                            HandwritingKeyboardLayout(
                                expanded = true,
                                expandedControlsWidthDp = 240,
                                expandedControlsOffsetX = offset,
                                bottomPaddingDp = 0,
                                panelBackgroundColor = background,
                                keyBackgroundColor = background,
                                specialKeyBackgroundColor = background,
                                keyTextColor = foreground,
                                specialKeyTextColor = foreground,
                                onKeyPress = { action = it },
                                expandedCandidateBar = {
                                    Box(Modifier.fillMaxWidth().height(44.dp))
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        // Exact same-size material: catch solid covers on any footer row,
                        // not just a modifier that exists somewhere in the composition.
                        Box(Modifier.requiredSize(224.dp, 148.dp).background(hostColor)
                            .keyboardPanelBackground(background).testTag("handwriting-glass-reference"))
                    }
                }
            }
        }

        for (isDark in listOf(true, false)) {
            rule.runOnIdle { dark = isDark; glassEnabled = true }
            val plain = if (isDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            val reference = awaitImage("handwriting-glass-reference") { distance(it.getPixel(4, 4), plain) > 5 }
            for (position in listOf(-40, 40)) {
              var previousReference: Bitmap? = null
              var previousFooter: Bitmap? = null
              for (substrate in listOf(Color.Red, Color.Blue)) {
                rule.runOnIdle { offset = position; hostColor = substrate }
                val matchedReference = awaitImage("handwriting-glass-reference") {
                    substrate == Color.Red || distance(it.getPixel(4, 4), reference.getPixel(4, 4)) > 20
                }
                val footer = try {
                    awaitImage("handwriting-controls") { image ->
                        listOf(4, 50, 105).all { y -> distance(image.getPixel(4, y), matchedReference.getPixel(4, y)) <= 2 }
                    }
                } finally {
                    val actual = rule.onNodeWithTag("handwriting-controls").captureToImage().asAndroidBitmap()
                    val name = "handwriting-${if (isDark) "dark" else "light"}-$position-${if (substrate == Color.Red) "red" else "blue"}"
                    val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                    for ((label, bitmap) in listOf("reference" to matchedReference, "actual" to actual)) {
                        java.io.File(context.getExternalFilesDir(null), "$name-$label.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    android.util.Log.i("HandwritingGlass", "$name glass=${FrostedGlassConfig.defaults(isDark, true)} samples=" +
                        listOf(4, 50, 105).joinToString { y -> "$y:%08x/%08x".format(matchedReference.getPixel(4, y), actual.getPixel(4, y)) })
                }
                if (previousReference != null && previousFooter != null) {
                    val expected = (255 * (1f - FrostedGlassConfig.defaults(isDark, true).backgroundOpacity)).toInt()
                    for ((red, blue) in listOf(previousReference!! to matchedReference, previousFooter!! to footer)) {
                        for (y in listOf(4, 50, 105)) {
                            assertTrue(abs((android.graphics.Color.red(red.getPixel(4, y)) - android.graphics.Color.red(blue.getPixel(4, y))) - expected) <= 2)
                            assertTrue(abs((android.graphics.Color.blue(blue.getPixel(4, y)) - android.graphics.Color.blue(red.getPixel(4, y))) - expected) <= 2)
                        }
                    }
                }
                previousReference = matchedReference
                previousFooter = footer
                assertEquals(224, footer.width)
                assertEquals(148, footer.height)

                val paper = rule.onNodeWithTag("handwriting-canvas").fetchSemanticsNode().boundsInRoot
                val controls = rule.onNodeWithTag("handwriting-controls").fetchSemanticsNode().boundsInRoot
                assertEquals(paper.center.x + position, controls.center.x, 1f)
                assertTrue(paper.width > controls.width)
                val host = rule.onNodeWithTag("fullscreen-handwriting-host").captureToImage().asAndroidBitmap()
                val hostPixel = if (substrate == Color.Red) android.graphics.Color.RED else android.graphics.Color.BLUE
                assertEquals(hostPixel, host.getPixel(180, 100))
                assertEquals(hostPixel, host.getPixel(8, 440))
                assertEquals(hostPixel, host.getPixel(351, 440))
              }
              rule.runOnIdle { hostColor = Color.Red }
            }
            rule.runOnIdle { glassEnabled = false }
            // Sample inside the material, not on the antialiased outer edge: x=0 on a
            // rounded edge blends ~1% of the red/blue host even when the fill is opaque.
            awaitImage("handwriting-controls") { image ->
                listOf(4, 50, 105).all { y -> distance(image.getPixel(4, y), plain) <= 2 }
            }
        }
        rule.onNodeWithTag("handwriting-key:，").performClick()
        rule.runOnIdle { assertEquals("，", action) }
        rule.onNodeWithTag("handwriting-key:collapse").performClick()
        rule.runOnIdle { assertEquals("collapse", action) }
    }

    private fun distance(a: Int, b: Int) = maxOf(
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
        abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
        abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)),
    )

    private fun awaitImage(tag: String, condition: (Bitmap) -> Boolean): Bitmap {
        var result: Bitmap? = null
        rule.waitUntil(timeoutMillis = 15_000) {
            val current = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
            if (condition(current)) { result = current; true } else false
        }
        return checkNotNull(result)
    }
}
