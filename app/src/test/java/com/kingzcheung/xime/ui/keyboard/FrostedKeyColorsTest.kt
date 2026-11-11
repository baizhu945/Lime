package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.kingzcheung.xime.settings.FrostedGlassConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrostedKeyColorsTest {
    private val darkKey = Color(0xFF525252)
    private val enabled = FrostedGlassConfig(enabled = true, keyOpacity = 0.22f)
    // sRGB colors use 8-bit alpha channels.
    private val tolerance = 1f / 255f

    @Test fun disabledEffectPreservesExistingNormalAndHighlightedColors() {
        val legacyColors = listOf(darkKey, darkKey.copy(alpha = 0.7f), Color(0xFF414141))
        legacyColors.forEach { legacy ->
            assertEquals(legacy, frostedKeyColor(darkKey, Color.White,
                enabled.copy(enabled = false), legacyStateColor = legacy, highlighted = true))
        }
    }

    @Test fun disabledEffectUsesOnePressTintForEveryKeyType() {
        // 所有键型按下都一样：向本键文字色靠 PRESSED_KEY_TINT，深色主题变亮、浅色主题变暗。
        val pressedDark = frostedKeyColor(darkKey, Color.White, enabled.copy(enabled = false),
            legacyStateColor = darkKey.copy(alpha = 0.7f), pressed = true)
        assertEquals(androidx.compose.ui.graphics.lerp(darkKey, Color.White, PRESSED_KEY_TINT), pressedDark)
        val lightKey = Color(0xFFF2F2F2)
        val pressedLight = frostedKeyColor(lightKey, Color.Black, enabled.copy(enabled = false), pressed = true)
        assertTrue(pressedLight.red < lightKey.red)
    }

    @Test fun darkNeutralKeycapsKeepTheirChannelRatiosAndConfiguredOpacity() {
        val result = frostedKeyColor(darkKey, Color.White, enabled)
        assertTrue(result.red > darkKey.red)
        assertEquals(result.red, result.green, tolerance)
        assertEquals(result.green, result.blue, tolerance)
        assertEquals(0.22f, result.alpha, tolerance)
    }

    @Test fun coloredActionKeysKeepTheirThemeHue() {
        val blue = Color(0xFF3079F6)
        val result = frostedKeyColor(blue, Color.White, enabled)
        assertEquals(blue.red / blue.blue, result.red / result.blue, tolerance)
        assertEquals(blue.green / blue.blue, result.green / result.blue, tolerance)
    }

    @Test fun lightThemeFillIsNotInverted() {
        val lightKey = Color(0xFFF4F4F4)
        val result = frostedKeyColor(lightKey, Color.Black, enabled)
        assertTrue(result.red > 0.75f)
        assertEquals(result.red, result.green, tolerance)
        assertEquals(0.22f, result.alpha, tolerance)
    }

    @Test fun opaqueDarkKeycapsRetainTheirOriginalContrast() {
        assertEquals(darkKey, frostedKeyColor(darkKey, Color.White, enabled.copy(keyOpacity = 1f)))
    }

    @Test fun interactionFeedbackPreservesConfiguredOpacityAndChangesOnlyTint() {
        val feedbackConfig = enabled.copy(keyOpacity = 0.6f)
        val normal = frostedKeyColor(darkKey, Color.White, feedbackConfig)
        val pressed = frostedKeyColor(darkKey, Color.White, feedbackConfig,
            legacyStateColor = darkKey.copy(alpha = 0.7f), pressed = true)
        val highlighted = frostedKeyColor(darkKey, Color.White, feedbackConfig,
            legacyStateColor = darkKey.copy(alpha = 0.8f), highlighted = true)
        assertEquals(0.6f, pressed.alpha, tolerance)
        assertEquals(0.6f, highlighted.alpha, tolerance)
        assertTrue(pressed.red > highlighted.red)
        assertTrue(highlighted.red > normal.red)
    }

    @Test fun nearBlackKeysStayDarkUnderGlass() {
        // 2026-10-22 用户：黑白主题深色的近黑键帽在玻璃下只稍微提亮——比背景亮一档，不发灰；字仍要清楚。
        val normalKey = Color(0xFF1C1C1E)
        val functionKey = Color(0xFF29292B)
        val glass = FrostedGlassConfig.defaults(true, true)
        for (host in listOf(Color.Black, Color.White)) {
            val panel = Color.Black.copy(alpha = glass.backgroundOpacity).compositeOver(host)
            for (key in listOf(normalKey, functionKey)) {
                val fill = frostedKeyColor(key, Color.White, glass).compositeOver(panel)
                assertTrue("near-black key stays dark on $host", fill.red - panel.red < 0.12f)
                assertTrue("but is still told apart from the panel on $host", fill.red - panel.red >= 16f / 255f)
                assertTrue(textContrast(Color.White, fill) >= 4.5f)
            }
        }
        // The original lift remains a floor; a white host can require a slightly
        // brighter tint to retain the same visible edge without changing opacity.
        assertTrue(frostedKeyColor(normalKey, Color.White, glass).red >= normalKey.red * glassNearBlackLift)
        // 其他主题的深色键帽仍按原规则提亮，保持层次。
        val softBlueKey = Color(0xFF2E323A)
        assertTrue(frostedKeyColor(softBlueKey, Color.White, glass).red > softBlueKey.red + 0.1f)
    }

    @Test fun neutralCorrectionFadesAtOpacityEndpointsAndPreservesOpacityScale() {
        val cap = Color(0xFF1C1C1C)
        fun tint(opacity: Float, scale: Float = 1f) = frostedKeyColor(cap, Color.White,
            enabled.copy(keyOpacity = opacity), opacityScale = scale)
        for (opacity in listOf(0f, 0.001f, 0.01f, 0.1f, 0.2f, 0.48f, 0.8f, 0.99f, 0.999f, 1f)) {
            val fill = tint(opacity)
            assertEquals(opacity, fill.alpha, tolerance)
            assertEquals(opacity * 0.4f, tint(opacity, 0.4f).alpha, tolerance)
            assertEquals(fill.red, tint(opacity, 0.4f).red, tolerance)
        }
        assertTrue(kotlin.math.abs(tint(0.999f).red - tint(1f).red) < 0.01f)
        // At zero opacity the visible result, not the unobservable tint, is continuous.
        for (host in listOf(Color.Black, Color.White)) {
            val zero = tint(0f).compositeOver(host)
            val almost = tint(0.001f).compositeOver(host)
            assertTrue(kotlin.math.abs(zero.red - almost.red) <= tolerance)
        }
    }

    @Test fun colouredAndZeroChannelCapsKeepHueAndAvoidNeutralCorrection() {
        val config = enabled.copy(keyOpacity = 0.2f)
        val red = Color(0xFF2D0101)
        val zero = Color(0xFF2D0000)
        val a = frostedKeyColor(red, Color.White, config)
        val b = frostedKeyColor(zero, Color.White, config)
        assertTrue(kotlin.math.abs(a.red - b.red) <= tolerance)
        assertEquals(0f, b.green, 0f)
        assertEquals(0f, b.blue, 0f)
        assertEquals(red.green / red.red, a.green / a.red, tolerance)
        assertTrue("saturated red must not become pink", a.green < 0.03f)
    }

    @Test fun greyNearBlackThresholdAndNeutralityBoundariesAreContinuous() {
        val config = enabled.copy(keyOpacity = 0.2f)
        // Extended sRGB retains sub-8-bit inputs, so this measures the transition
        // rather than jumps caused by quantising the test inputs themselves.
        fun tint(r: Float, g: Float, b: Float) = frostedKeyColor(
            Color(r, g, b, 1f, androidx.compose.ui.graphics.colorspace.ColorSpaces.ExtendedSrgb), Color.White, config)
        var previous = tint(0.14f, 0.14f, 0.14f).red
        for (i in 1..140) {
            val c = 0.14f + i * 0.0005f
            val current = tint(c, c, c).red
            assertTrue("peak=$c: $previous -> $current", kotlin.math.abs(current - previous) < 0.012f)
            previous = current
        }
        previous = tint(0.12f, 0.12f, 0.12f).red
        for (i in 1..100) {
            val delta = i * 0.0005f
            val current = tint(0.12f, 0.12f - delta, 0.12f - delta).red
            assertTrue("chroma=$delta: $previous -> $current", kotlin.math.abs(current - previous) < 0.012f)
            previous = current
        }
    }

    @Test fun defaultNeutralLetterAndFunctionStaySeparatedOnBothHosts() {
        val config = FrostedGlassConfig.defaults(true, true)
        for (host in listOf(Color.Black, Color.White)) {
            val panel = Color.Black.copy(alpha = config.backgroundOpacity).compositeOver(host)
            val letter = frostedKeyColor(Color(0xFF1C1C1C), Color.White, config).compositeOver(panel)
            val function = frostedKeyColor(Color(0xFF353535), Color.White, config).compositeOver(panel)
            assertTrue(letter.red - panel.red >= 16f / 255f)
            assertTrue(function.red - letter.red >= 16f / 255f)
        }
    }

    @Test fun zeroOpacityRemainsTransparentDuringAllInteractionStates() {
        listOf(false, true).forEach { pressed ->
            listOf(false, true).forEach { highlighted ->
                val result = frostedKeyColor(darkKey, Color.White, enabled.copy(keyOpacity = 0f),
                    pressed = pressed, highlighted = highlighted)
                assertEquals(0f, result.alpha, 0f)
            }
        }
    }

    @Test fun liftedFillStillShowsPressFeedbackWhenTintReachesWhite() {
        val cap = Color(0xFF353535)
        val config = enabled.copy(keyOpacity = 0.2f)
        val normal = frostedKeyColor(cap, Color.White, config)
        val pressed = frostedKeyColor(cap, Color.White, config, pressed = true)
        assertTrue(normal != pressed)
        assertEquals(normal.alpha, pressed.alpha, 0f)
    }

    @Test fun importedThemeTransparencyDoesNotOverrideTheIndependentOpacitySetting() {
        listOf(0f, 0.5f, 1f).forEach { themeOpacity ->
            val themeKey = darkKey.copy(alpha = themeOpacity)
            assertEquals(0.22f, frostedKeyColor(themeKey, Color.White, enabled).alpha, tolerance)
            assertEquals(1f, frostedKeyColor(themeKey, Color.White, enabled.copy(keyOpacity = 1f)).alpha, 0f)
        }
    }

    @Test fun explicitStateScaleKeepsTransparentHitRegionsAndDisabledControlsDistinct() {
        assertEquals(0f, frostedKeyColor(darkKey, Color.White, enabled, opacityScale = 0f).alpha, 0f)
        assertEquals(0.22f * 0.4f,
            frostedKeyColor(darkKey, Color.White, enabled, opacityScale = 0.4f).alpha, tolerance)
    }

    @Test fun glassHintsKeepThemeRgbAndFullOpacityAcrossAppearanceAndGestureStates() {
        val texts = listOf(Color(0xFF20242C), Color(0xFFF0EAF5), Color(0xFF685191))
        texts.forEach { text ->
            listOf(0.5f, 0.6f, 0.7f, 0.85f, 1f).forEach { legacyAlpha ->
                assertEquals(text, keyHintColor(text.copy(alpha = 0.5f), enabled, legacyAlpha))
                assertEquals(text.copy(alpha = legacyAlpha),
                    keyHintColor(text, enabled.copy(enabled = false), legacyAlpha))
            }
        }
    }

    @Test fun defaultLightHintsRemainReadableOverBlackAndWhiteHostContent() {
        val text = Color(0xFF20242C)
        val glass = enabled.copy(backgroundOpacity = 0.2f, keyOpacity = 0.8f)
        listOf(Color.Black, Color.White).forEach { host ->
            val panel = Color(0xFFF4F6FA).copy(alpha = glass.backgroundOpacity).compositeOver(host)
            listOf(false, true).forEach { pressed ->
                val key = frostedKeyColor(Color.White, text, glass, pressed = pressed).compositeOver(panel)
                val hint = keyHintColor(text, glass, 0.5f).compositeOver(key)
                assertTrue("small hints must remain readable over $host, pressed=$pressed",
                    textContrast(hint, key) >= 4.5f)
            }
        }
    }
    @Test fun invalidOpacityIsBounded() {
        assertEquals(1f, frostedKeyColor(darkKey, Color.White, enabled.copy(keyOpacity = 2f)).alpha, 0f)
        assertEquals(0f, frostedKeyColor(darkKey, Color.White, enabled.copy(keyOpacity = -1f)).alpha, 0f)
        assertEquals(0.22f,
            frostedKeyColor(darkKey, Color.White, enabled.copy(keyOpacity = Float.NaN)).alpha, tolerance)
    }

    @Test fun iosGradientGlassUsesTheEarlyNearBlackKeys() {
        // 玻璃下 iOS 渐变深色键按早期近黑键提亮：字母键 #1C1C1C → #8C8C8C，功能键更亮（2026-10-30 用户反馈）。
        val glass = FrostedGlassConfig.defaults(isDark = true, enabled = true)
        val letter = frostedKeyColor(Color(0xFF55555A), Color.White, glass)
        val function = frostedKeyColor(Color(0xFF3A3A3C), Color.White, glass)
        assertEquals(0x8C / 255f, letter.red, tolerance)
        assertEquals(0.2f, letter.alpha, tolerance)
        assertTrue(function.red > letter.red + 0.3f)
        // 不开玻璃或按键不透明时仍用灰键，保留与渐变底的对比。
        assertEquals(Color(0xFF55555A), frostedKeyColor(Color(0xFF55555A), Color.White, glass.copy(enabled = false)))
        assertEquals(Color(0xFF55555A), frostedKeyColor(Color(0xFF55555A), Color.White, glass.copy(keyOpacity = 1f)))
    }
}
