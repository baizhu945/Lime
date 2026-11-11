package com.kingzcheung.xime.ui

import android.content.ContextWrapper
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.theme.DynamicThemes
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.SoftBlueTheme
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class KeyboardThemePaletteTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(base.cacheDir, "palette-test-${UUID.randomUUID()}")
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir(): File = folder
    }

    @Before fun reload() {
        check(File(folder, "rime").mkdirs())
        KeysConfigHelper.loadConfig(context)
        KeyboardThemes.reload(context)
    }

    @After fun restore() {
        try {
            KeysConfigHelper.loadConfig(base)
            KeyboardThemes.reload(base)
        } finally {
            check(folder.deleteRecursively())
        }
    }

    @Test fun fixedThemeUsesItsCompletePaletteInsteadOfOldGlobalDefaults() {
        val theme = KeyboardThemes.getThemeById(SoftBlueTheme.ID)
        assertEquals(SoftBlueTheme.ID, theme.id)
        assertFalse(theme.isDynamic)
        for (dark in listOf(false, true)) {
            assertEquals(KeyboardThemes.getKeyBackgroundColor(theme.id, dark), KeyboardThemes.getKeyBgColorOverride(theme.id, dark))
            assertEquals(KeyboardThemes.getKeyTextColor(theme.id, dark), KeyboardThemes.getKeyTextColorOverride(theme.id, dark))
            assertEquals(KeyboardThemes.getCandidateTextColor(theme.id, dark), KeyboardThemes.getCandidateTextColorOverride(theme.id, dark))
        }
    }

    @Test fun packagedStaticThemesRetainTheirOwnConfiguredPalettes() {
        assertEquals("sakura_pink", KeyboardThemes.getThemeById("sakura_pink").id)
        for ((id, background, special) in listOf(
            Triple("lavender_purple", Color(0xFF1E1838), Color(0xFF8D79D1)),
            Triple("ocean_blue", Color(0xFF0D1B33), Color(0xFF4478C4)),
        )) {
            val theme = KeyboardThemes.getThemeById(id)
            assertEquals(id, theme.id)
            assertEquals(background, theme.keyboardBgDark)
            assertEquals(special, theme.specialKeyDark)
        }
        assertEquals(SoftBlueTheme.ID, KeyboardThemes.getThemeById("unknown_theme").id)
    }

    @Test fun oldDeployedCatalogDoesNotReplacePackagedOceanTheme() {
        File(folder, "rime/xime.yaml").writeText("""
            color_schemes:
              ocean_blue:
                keyboard_background: {type: solid, color_dark: 0xFF123456}
        """.trimIndent())
        KeyboardThemes.reload(context)
        assertEquals(Color(0xFF0D1B33), KeyboardThemes.getThemeById("ocean_blue").keyboardBgDark)
    }

    @Test fun dynamicThemeUsesSystemAccentContainer() {
        val theme = KeyboardThemes.getThemeById(DynamicThemes.THEME_ID)
        assertEquals("跟随系统动态配色", theme.name)
        if (DynamicThemes.isSupported()) {
            assertTrue(theme.isDynamic)
            val id = base.resources.getIdentifier("system_accent1_700", "color", "android")
            val expected = if (id == 0) Color(0xFF405F91) else Color(base.getColor(id))
            assertEquals(expected, theme.specialKeyDark)
            assertEquals(expected, theme.primaryContainerDark)
        } else {
            assertFalse(theme.isDynamic)
        }
    }
}
