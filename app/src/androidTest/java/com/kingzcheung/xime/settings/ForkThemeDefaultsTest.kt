package com.kingzcheung.xime.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.ui.theme.DynamicThemes
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

class ForkThemeDefaultsTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("theme_test_$name", mode)
    }
    @Before fun before() { SettingsPreferences.getPrefsPublic(context).edit().clear().commit() }
    @After fun after() { SettingsPreferences.getPrefsPublic(context).edit().clear().commit() }

    @Test fun freshInstallUsesConfiguredDefaults() {
        // Application startup loads these from merged xime.yaml / xime.custom.yaml.
        // The preference wrapper isolates storage, not the application singleton's defaults.
        val theme = SettingsPreferences.defaultKeyboardTheme
        val darkMode = SettingsPreferences.defaultDarkMode
        SettingsPreferences.applyForkThemeDefaults(context)
        assertEquals(theme, SettingsPreferences.getKeyboardTheme(context))
        assertEquals(darkMode, SettingsPreferences.getDarkMode(context))
        assertFalse(SettingsPreferences.shouldShowPressBubble(context))
        assertFalse(com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.read(context).keyGlowEnabled)
        assertFalse(SettingsPreferences.isVerboseLoggingEnabled(context))
        assertTrue(KeyboardThemes.getThemeById("dynamic").isDynamic)
    }
    @Test fun configuredDefaultsAreAppliedAndLaterChoicesPersist() {
        val originalTheme = SettingsPreferences.defaultKeyboardTheme
        val originalDarkMode = SettingsPreferences.defaultDarkMode
        try {
            SettingsPreferences.defaultKeyboardTheme = "configured_theme"
            SettingsPreferences.defaultDarkMode = 2
            SettingsPreferences.applyForkThemeDefaults(context)
            assertEquals("configured_theme", SettingsPreferences.getKeyboardTheme(context))
            assertEquals(2, SettingsPreferences.getDarkMode(context))
            SettingsPreferences.setKeyboardTheme(context, "user_theme")
            SettingsPreferences.setDarkMode(context, 0)
            SettingsPreferences.applyForkThemeDefaults(context)
            assertEquals("user_theme", SettingsPreferences.getKeyboardTheme(context))
            assertEquals(0, SettingsPreferences.getDarkMode(context))
        } finally {
            SettingsPreferences.defaultKeyboardTheme = originalTheme
            SettingsPreferences.defaultDarkMode = originalDarkMode
        }
    }
    @Test fun existingPurpleAndLaterUserChoicesPersist() {
        SettingsPreferences.setKeyboardTheme(context, "lavender_purple")
        SettingsPreferences.applyForkThemeDefaults(context)
        assertEquals("lavender_purple", SettingsPreferences.getKeyboardTheme(context))
        SettingsPreferences.setKeyboardTheme(context, "lavender_purple")
        SettingsPreferences.applyForkThemeDefaults(context)
        assertEquals("lavender_purple", SettingsPreferences.getKeyboardTheme(context))
    }
    @Test fun unrelatedCustomThemeAndDarkModeRemainIntact() {
        SettingsPreferences.setKeyboardTheme(context, "user_blue")
        SettingsPreferences.setDarkMode(context, 0)
        SettingsPreferences.applyForkThemeDefaults(context)
        assertEquals("user_blue", SettingsPreferences.getKeyboardTheme(context))
        assertEquals(0, SettingsPreferences.getDarkMode(context))
    }
    @Test fun olderAndroidFallbackRetainsDynamicEntryWithoutPurple() {
        val fallback = DynamicThemes.fallback()
        assertEquals("dynamic", fallback.id)
        assertFalse(fallback.isDynamic)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF191C22), fallback.keyboardBgDark)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF495A7D), fallback.specialKeyDark)
    }
    @Test fun existingDynamicThemeIsNotReplacedByNewDefault() {
        SettingsPreferences.setKeyboardTheme(context, "dynamic")
        SettingsPreferences.applyForkThemeDefaults(context)
        assertEquals("dynamic", SettingsPreferences.getKeyboardTheme(context))
        SettingsPreferences.setKeyboardTheme(context, "dynamic")
        SettingsPreferences.applyForkThemeDefaults(context)
        assertEquals("dynamic", SettingsPreferences.getKeyboardTheme(context))
    }

}
