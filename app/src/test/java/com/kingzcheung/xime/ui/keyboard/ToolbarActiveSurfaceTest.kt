package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test

class ToolbarActiveSurfaceTest {
    @Test fun activeSurfaceIsAnOpaqueThemeBlend() {
        val surface = toolbarActiveSurface(Color.Black, Color.White)
        assertEquals(1f, surface.alpha, 0f)
        assertEquals(0xFF737373.toInt(), surface.toArgb())
    }

    @Test fun incomingRoleAlphaDoesNotLeakIntoTheSelectedSurface() {
        val background = Color(0xFF292929)
        val accent = Color(0xFF1A73E8)
        assertEquals(toolbarActiveSurface(background, accent),
            toolbarActiveSurface(background.copy(alpha = .1f), accent.copy(alpha = .2f)))
    }

    @Test fun underlyingToolbarReflectionCannotChangeTheSelectedFill() {
        val surface = toolbarActiveSurface(Color.Black, Color(0xFFDDDDDD))
        assertEquals(surface, surface.compositeOver(Color.Black))
        assertEquals(surface, surface.compositeOver(Color.White))
        val channel = (surface.toArgb() ushr 16) and 255
        // The captured decorated black toolbar spans #0F..#19 at the original sample row.
        assertTrue(channel - 0x19 >= 60)
    }

    @Test fun selectedGlyphRetainsAtLeastFourPointFiveContrastAcrossThemeRoles() {
        val backgrounds = listOf(Color.Black, Color(0xFF292929), Color.White, Color(0xFFF0F0F0))
        val accents = listOf(Color(0xFFDDDDDD), Color(0xFF1A73E8), Color(0xFF808080), Color(0xFF101010))
        for (background in backgrounds) for (accent in accents) {
            val surface = toolbarActiveSurface(background, accent)
            val foreground = toolbarActiveForeground(surface)
            assertTrue("background=$background accent=$accent surface=$surface foreground=$foreground",
                textContrast(foreground, surface) >= 4.5f)
        }
    }
}
