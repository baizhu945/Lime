package com.kingzcheung.xime.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class KeyboardPaletteTest {
    @Test fun pureBlackFollowsDisplayModeAndKeepsKeyboardAndSettingsReadable() {
        val theme = PureBlackTheme.create()
        assertTrue(theme.keyboardBgLight.luminance() > 0.8f)
        assertEquals(Color.Black, theme.keyboardBgDark)
        assertEquals(theme.keyboardBgLight, theme.candidateBarBgLight)
        assertEquals(Color.Black, theme.candidateBarBgDark)
        assertEquals(theme.keyboardBgLight, theme.surfaceLight)
        assertEquals(Color.Black, theme.surfaceDark)
        assertTrue(contrast(theme.keyTextColorLight, theme.surfaceLight) >= 4.5f)
        assertTrue(contrast(theme.keyTextColorDark, theme.surfaceDark) >= 4.5f)
        assertTrue(contrast(theme.candidateTextColorLight, theme.surfaceLight) >= 4.5f)
        assertTrue(contrast(theme.candidateTextColorDark, theme.surfaceDark) >= 4.5f)
        assertTrue(contrast(theme.keyTextColorLight, theme.keyBgLight) >= 4.5f)
        assertTrue(contrast(theme.keyTextColorDark, theme.keyBgDark) >= 4.5f)
        assertTrue(contrast(theme.specialKeyTextColorLight!!, theme.specialKeyLight) >= 4.5f)
        assertTrue(contrast(theme.specialKeyTextColorDark!!, theme.specialKeyDark) >= 4.5f)
    }

    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }

    @Test fun fixedBlueHasTheSamePaletteAcrossNamesAndFallbackEntries() {
        val fixed = SoftBlueTheme.create()
        val olderAndroid = SoftBlueTheme.create("dynamic", "跟随系统动态配色")
        assertFalse(fixed.isDynamic)
        assertTrue(fixed.useThemeColors)
        assertTrue(olderAndroid.useThemeColors)
        assertEquals(fixed, olderAndroid.copy(id = fixed.id, name = fixed.name))
    }

    @Test fun fixedBlueRetainsReadableNormalAndFunctionKeysInBothModes() {
        val theme = SoftBlueTheme.create()
        assertTrue(contrast(theme.keyTextColorDark, theme.keyBgDark) >= 4.5f)
        assertTrue(contrast(theme.keyTextColorLight, theme.keyBgLight) >= 4.5f)
        assertTrue(contrast(Color.White, theme.specialKeyDark) >= 4.5f)
        val lightText = Color(theme.accentLight.red * 0.6f, theme.accentLight.green * 0.6f, theme.accentLight.blue * 0.6f)
        assertTrue(contrast(lightText, theme.specialKeyLight) >= 4.5f)
        assertEquals(theme.primaryContainerDark, theme.specialKeyDark)
    }

    @Test fun darkContainersStayReadableEvenWithExtremeImportedThemeColors() {
        val colors = listOf(Color.White, Color.Black, Color.Red, Color.Green, Color.Blue,
            Color.Cyan, Color.Magenta, Color.Yellow, Color(0xFFD0BCFF), Color(0xFF8ACFDF))
        colors.forEach { accent ->
            val container = softDarkKeyContainer(accent)
            assertTrue("white text contrast for $accent", contrast(Color.White, container) >= 4.5f)
            assertTrue("container must remain opaque", container.alpha == 1f)
        }
    }

    @Test fun darkContainersReducePurpleAndCyanIntensityWithoutLosingTheirHue() {
        val purple = softDarkKeyContainer(Color(0xFFD0BCFF))
        val cyan = softDarkKeyContainer(Color(0xFF8ACFDF))
        assertTrue(purple.blue > purple.red && purple.red > purple.green)
        assertTrue(cyan.blue > cyan.red && cyan.green > cyan.red)
        listOf(purple, cyan).forEach {
            val spread = maxOf(it.red, it.green, it.blue) - minOf(it.red, it.green, it.blue)
            assertTrue("dark keys should be softly tinted", spread < 0.2f)
        }
    }
    @Test fun completePalettesIgnoreLegacyGlobalFunctionKeyColors() {
        val full = SoftBlueTheme.create()
        assertEquals(full.specialKeyDark, resolvedSpecialKeyColor(full, true, Color.Red))
        assertEquals(full.specialKeyLight, resolvedSpecialKeyColor(full, false, Color.Red))
        val legacy = full.copy(useThemeColors = false)
        assertEquals(Color.Red, resolvedSpecialKeyColor(legacy, true, Color.Red))
        assertEquals(legacy.specialKeyDark, resolvedSpecialKeyColor(legacy, true, null))
    }

    @Test fun ximeLavenderSupportsLegacyFunctionKeyOverrides() {
        val lavender = SoftLavenderTheme.create()
        assertFalse(lavender.useThemeColors)
        assertEquals(Color.Red, resolvedSpecialKeyColor(lavender, false, Color.Red))
        assertEquals(Color.Red, resolvedSpecialKeyColor(lavender, true, Color.Red))
        assertEquals(lavender.specialKeyDark, resolvedSpecialKeyColor(lavender, true, null))
    }

    @Test fun retiredThemeFallsBackToDayNightCapableDefault() {
        assertFalse(KeyboardThemes.themes.any { it.id == "858AdvanceColor" })
        val theme = KeyboardThemes.getThemeById("858AdvanceColor")
        assertEquals("soft_blue", theme.id)
        assertNotEquals(theme.keyboardBgLight, theme.keyboardBgDark)
    }
}
