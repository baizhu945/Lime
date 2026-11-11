package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.ui.theme.resolveKeyboardPalette
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassOpacityDiagnosticTest {
    @Test fun pureBlackDarkOpacityMatrix() {
        val p = resolveKeyboardPalette("pure_black", true)
        val scale = glassFunctionKeyScale(p.function, p.key, p.function)
        val keys = listOf(p.key to p.text, p.function to p.functionText)
        for (background in listOf(0f, .35f, .6f, .8f, 1f)) for (key in listOf(0f, .15f, .6f, 1f)) {
            val raw = FrostedGlassConfig(enabled = true, backgroundOpacity = background, keyOpacity = key)
            val effective = raw.legibleForTheme("pure_black", true)
            val unscaled = raw.legibleOver(p.background, keys)
            val ratios = listOf(Color.White, Color.Black).flatMap { backdrop ->
                val panel = p.background.copy(alpha = effective.backgroundOpacity).compositeOver(backdrop)
                keys.mapIndexed { i, (color, text) ->
                    val fill = frostedKeyColor(color, text, effective, opacityScale = if (i == 1) scale else 1f).compositeOver(panel)
                    (maxOf(text.luminance(), fill.luminance()) + .05f) / (minOf(text.luminance(), fill.luminance()) + .05f)
                }
            }
            println("OPACITY_DIAG raw=$background,$key effective=${effective.backgroundOpacity},${effective.keyOpacity} unscaled=${unscaled.backgroundOpacity},${unscaled.keyOpacity} scale=$scale worst=${ratios.min()}")
            assertTrue("contrast $raw $effective $ratios", ratios.min() >= GLASS_LABEL_CONTRAST - .0001f)
        }
    }
}
