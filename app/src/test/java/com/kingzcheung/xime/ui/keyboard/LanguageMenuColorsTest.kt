package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.ui.theme.KeyboardPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageMenuColorsTest {
    private fun palette(background: Long, key: Long, function: Long, text: Long, accent: Long) = KeyboardPalette(
        background = Color(background), key = Color(key), text = Color(text), accent = Color(accent),
        function = Color(function), functionText = Color(text), enter = Color(function),
        candidateText = Color(text), selectedText = Color(text))

    private val palettes = listOf(
        palette(0xFF000000, 0xFF1C1C1E, 0xFF29292B, 0xFFFFFFFF, 0xFFE0E0E0), // 黑白·深
        palette(0xFFE8E8EA, 0xFFFFFFFF, 0xFFD4D4D8, 0xFF202020, 0xFF454545), // 黑白·浅
        palette(0xFF33333A, 0xFF55555A, 0xFF3A3A3C, 0xFFFFFFFF, 0xFFE0E0E0), // iOS 渐变·深
        palette(0xFFF5F0FA, 0xFFFFFFFF, 0xFFDCE6FA, 0xFFB0B0B0, 0xFF42639C), // 浅色主题但文字太浅
    )
    private val glassConfigs = listOf(FrostedGlassConfig(true, 10f, .22f, .29f),
        FrostedGlassConfig(true, 10f, 0f, 0f), FrostedGlassConfig(true, 10f, .8f, .2f))

    @Test fun `without glass or live blur the menu is opaque and readable`() {
        for (p in palettes) for ((glass, blur) in listOf(FrostedGlassConfig(false) to true,
            FrostedGlassConfig(true, 10f, .22f, .29f) to false)) {
            val colors = languageMenuColors(p, Color.White, Color.Black, Color.Blue, glass, blur)
            assertEquals(1f, colors.surface.alpha, 0f)
            assertTrue(textContrast(colors.text, colors.surface) >= 4.5f)
        }
    }

    @Test fun `glass menu over live blur is translucent yet readable over white and black pages`() {
        for (p in palettes) for (glass in glassConfigs) {
            val colors = languageMenuColors(p, Color.White, Color.Black, Color.Blue, glass, blurBehind = true)
            for (page in listOf(Color.White, Color.Black)) {
                val seen = colors.surface.compositeOver(page)
                assertTrue("contrast over $page for $glass", textContrast(colors.text, seen) >= 4.5f)
            }
            assertEquals("menu text itself is never faded", 1f, colors.text.alpha, 0f)
            assertEquals(1f, colors.secondary.alpha, 0f)
        }
        // 默认玻璃参数下确实透出后面（不是悄悄变回不透明）。
        val dark = languageMenuColors(palettes[0], Color.White, Color.Black, Color.Blue, glassConfigs[0], true)
        assertTrue(dark.surface.alpha < 1f)
    }

    @Test fun `all labels remain readable on normal and selected surfaces over both hosts`() {
        for (p in palettes) for (glass in glassConfigs + FrostedGlassConfig(false)) for (blur in listOf(false, true)) {
            val colors = languageMenuColors(p, Color.White, Color.Black, Color.Blue, glass, blur)
            for (fill in listOf(colors.surface, colors.selectedSurface)) for (host in listOf(Color.Black, Color.White)) {
                val seen = fill.compositeOver(host)
                for (foreground in listOf(colors.text, colors.secondary)) {
                    assertEquals(1f, foreground.alpha, 0f)
                    assertTrue("palette=$p glass=$glass blur=$blur fill=$fill host=$host foreground=$foreground",
                        textContrast(foreground, seen) >= 4.5f)
                }
            }
        }
    }

    @Test fun `menu keeps the theme surface instead of falling back to a white material surface`() {
        val p = palettes[3]
        val colors = languageMenuColors(p, Color.White, Color.Black, Color.Blue, FrostedGlassConfig(false), false)
        assertTrue(colors.surface != Color.White)
        assertTrue(textContrast(colors.text, colors.surface) >= 4.5f)
    }
}
