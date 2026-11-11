package com.kingzcheung.xime.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.settings.VisualStylePicker
import com.kingzcheung.xime.ui.theme.*
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VisualStyleIntegrationTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun preservingAppearance(block: () -> Unit) {
        // Snapshot the configured catalogue, not the process's not-yet-loaded fallback themes.
        rule.runOnIdle {
            KeysConfigHelper.loadConfig(context)
            KeyboardThemes.reload(context)
        }
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val saved = prefs.getString(SettingsPreferences.KEY_VISUAL_STYLE, null)
        val savedIcon = prefs.getString(IconAppearance.KEY_STYLE, null)
        val savedLinked = prefs.getBoolean(IconAppearance.KEY_LINKED, true)
        try { block() } finally {
            rule.runOnIdle {
                prefs.edit().putBoolean(IconAppearance.KEY_LINKED, savedLinked).also {
                    if (savedIcon == null) it.remove(IconAppearance.KEY_STYLE) else it.putString(IconAppearance.KEY_STYLE, savedIcon)
                    if (saved == null) it.remove(SettingsPreferences.KEY_VISUAL_STYLE)
                    else it.putString(SettingsPreferences.KEY_VISUAL_STYLE, saved) }.commit()
                VisualStyles.current = VisualStyle.fromId(saved)
                IconAppearance.reload(context)
                LauncherIcons.apply(context, IconAppearance.effective)
            }
        }
    }

    @Test fun pickerPersistsEachPresetAndRestoresOriginalThemeUnchanged() = preservingAppearance {
        val originalId = SettingsPreferences.getKeyboardTheme(context)
        val originalDark = SettingsPreferences.getDarkMode(context)
        val originalTheme = KeyboardThemes.getThemeById(originalId)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                XimeTheme { Box(Modifier.size(460.dp, 700.dp).testTag("visual-picker")) {
                    VisualStylePicker(VisualStyles.current) { SettingsPreferences.setVisualStyle(context, it) }
                } }
            }
        }
        VisualStyle.entries.filter { it != VisualStyle.ORIGINAL }.forEach { style ->
            rule.onNodeWithTag("visual-style-${style.id}").performClick().assertIsSelected()
            assertEquals(style, SettingsPreferences.getVisualStyle(context))
            assertEquals(originalId, SettingsPreferences.getKeyboardTheme(context))
            assertEquals(originalDark, SettingsPreferences.getDarkMode(context))
            assertEquals(originalDark, SettingsPreferences.getEffectiveDarkMode(context))
            assertEquals(originalTheme, KeyboardThemes.getRenderingScheme(originalId))
            assertEquals(originalTheme, KeyboardThemes.getThemeById(originalId))
            rule.runOnIdle {
                VisualStyles.current = VisualStyle.ORIGINAL
                KeyboardThemes.reload(context)
            }
            assertEquals(style, VisualStyles.current)
            rule.assertGeometry("visual-picker", "visual material ${style.id}")
        }
        rule.onNodeWithTag("visual-style-original").performClick()
        assertEquals(VisualStyle.ORIGINAL, SettingsPreferences.getVisualStyle(context))
        assertEquals(originalId, KeyboardThemes.getRenderingScheme(originalId).id)
    }

    @Test fun actualKeyboardSwitchesAllMaterialsWithoutChangingKeyActions() = preservingAppearance {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        KeysConfigHelper.loadConfig(app)
        val vm = KeyboardViewModel(app)
        vm.setKeyboardState(KeyboardLayoutState.Chinese)
        val keys = mutableListOf<String>()
        val dark = mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                XimeTheme(darkTheme = dark.value) {
                    KeyboardView(vm, KeyboardUiState(currentSchemaId = "rime_ice", themeId = "soft_blue",
                        isDarkTheme = dark.value),
                        KeyboardCallbacks(onKeyPress = { key, _ -> keys += key }, onCandidateSelect = {}),
                        modifier = Modifier.size(420.dp, 320.dp).testTag("styled-keyboard"))
                }
            }
        }
        VisualStyle.entries.filter { it != VisualStyle.ORIGINAL }.forEach { style ->
          for (isDark in listOf(true, false)) {
            rule.runOnIdle { dark.value = isDark; SettingsPreferences.setVisualStyle(context, style); keys.clear() }
            rule.onNodeWithTag("qwerty-key:a").performTouchInput { click() }
            rule.onNodeWithTag("qwerty-delete-key").performTouchInput { click() }
            rule.onNodeWithTag("qwerty-enter-key").performTouchInput { click() }
            rule.runOnIdle { assertEquals(listOf("a", "delete", "enter"), keys) }
            val cap = rule.onNodeWithTag("qwerty-delete-key").captureToImage().toPixelMap()
            val actual = cap[(cap.width * .2f).toInt(), cap.height / 2]
            // KeyboardLayout's function labels are white in dark mode, theme-resolved in light mode.
            val foreground = if (isDark) androidx.compose.ui.graphics.Color.White else KeyboardThemes.getSpecialKeyTextColor("soft_blue", false)
            File(context.getExternalFilesDir(null), "visual-${style.id}-${if (isDark) "dark" else "light"}.png").outputStream().use {
                rule.onNodeWithTag("styled-keyboard").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue("${style.id} dark=$isDark background=$actual text=$foreground: material must preserve readable function labels",
                (maxOf(actual.luminance(), foreground.luminance()) + .05f) /
                    (minOf(actual.luminance(), foreground.luminance()) + .05f) >= 4.5f)
            File(context.getExternalFilesDir(null), "visual-${style.id}.png").outputStream().use {
                rule.onNodeWithTag("styled-keyboard").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
          }
        }
    }

    @Test fun keyboardMaterialUsesItsOwnThemeWhenHostThemeChanges() = preservingAppearance {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        KeysConfigHelper.loadConfig(app)
        val vm = KeyboardViewModel(app)
        vm.setKeyboardState(KeyboardLayoutState.Chinese)
        val host = mutableStateOf("lavender_purple")
        val keyboard = mutableStateOf("soft_blue")
        rule.runOnIdle { VisualStyles.current = VisualStyle.GLASS }
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                XimeTheme(darkTheme = true, themeId = host.value) {
                    KeyboardView(vm, KeyboardUiState(currentSchemaId = "rime_ice", themeId = keyboard.value,
                        isDarkTheme = true), KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                        modifier = Modifier.size(420.dp, 320.dp).testTag("theme-owner-keyboard"))
                }
            }
        }
        fun pixels(): IntArray {
            val b = rule.onNodeWithTag("theme-owner-keyboard").captureToImage().asAndroidBitmap()
            return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        }
        val original = pixels()
        rule.runOnIdle { host.value = "soft_blue" }
        assertArrayEquals("An unrelated host theme must not recolor the keyboard", original, pixels())
        rule.runOnIdle { keyboard.value = "lavender_purple" }
        assertFalse("Changing the selected keyboard theme must update its material", original.contentEquals(pixels()))
    }
}
