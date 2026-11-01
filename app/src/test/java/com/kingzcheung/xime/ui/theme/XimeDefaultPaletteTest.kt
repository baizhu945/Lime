package com.kingzcheung.xime.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XimeDefaultPaletteTest {
    @Test
    fun lavenderFallbackUsesXimeColorsAndLegacyOverrides() {
        val theme = SoftLavenderTheme.create()
        assertEquals("lavender_purple", theme.id)
        assertEquals(Color(0xFFE8DEF8), theme.specialKeyLight)
        assertEquals(Color(0xFF6750A4), theme.specialKeyDark)
        assertEquals(Color(0xFFEADDFF), theme.primaryContainerLight)
        assertEquals(Color(0xFF4F378B), theme.primaryContainerDark)
        assertEquals(Color(0xFFFAF8FC), theme.surfaceLight)
        assertFalse(theme.useThemeColors)
        assertEquals(Color.Red, resolvedSpecialKeyColor(theme, false, Color.Red))
    }

    @Test
    fun cyimeCompletePalettesRetainTheirOwnColors() {
        val theme = PureBlackTheme.create()
        assertTrue(theme.useThemeColors)
        assertEquals(theme.specialKeyLight, resolvedSpecialKeyColor(theme, false, Color.Red))
        assertEquals("transparent_glass", TransparentGlassTheme.create().id)
        assertEquals("soft_blue", SoftBlueTheme.create().id)
    }
}
