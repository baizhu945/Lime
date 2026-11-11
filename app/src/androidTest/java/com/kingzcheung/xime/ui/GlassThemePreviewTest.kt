package com.kingzcheung.xime.ui

import android.content.ContextWrapper
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.kingzcheung.xime.SharedPreferencesTestRule
import com.kingzcheung.xime.settings.FrostedGlassProfiles
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.settings.ThemePreviewSheet
import com.kingzcheung.xime.ui.settings.ThemeSettingsContent
import com.kingzcheung.xime.viewmodel.ThemeSettingsViewModel
import com.kingzcheung.xime.ui.settings.FrostedThemeControls
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.TransparentGlassTheme
import com.kingzcheung.xime.settings.BackgroundConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class GlassThemePreviewTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val preferences = SharedPreferencesTestRule()
    private val context by lazy {
        object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = preferences.getSharedPreferences()
        }
    }

    @Test fun fixedGradientPreviewHasNoGlassControlsAndApplyPreservesMaterial() {
        SettingsPreferences.setDarkMode(context, 1)
        SettingsPreferences.setKeyboardTheme(context, "pure_black")
        FrostedGlassPreferences.saveProfiles(context, FrostedGlassProfiles(
            FrostedGlassConfig.defaults(false).copy(enabled = false, blurRadiusDp = 19f),
            FrostedGlassConfig.defaults(true).copy(enabled = false, blurRadiusDp = 17f)))
        val original = FrostedGlassPreferences.readProfiles(context)
        var showing by mutableStateOf(true)
        var applied = false
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    if (showing) ThemePreviewSheet(TransparentGlassTheme.create(), onApply = {
                        applied = true
                        SettingsPreferences.setKeyboardTheme(context, TransparentGlassTheme.ID)
                        showing = false
                    }, onDismiss = { showing = false })
                }
            }
        }
        rule.onNodeWithTag("theme-preview-glass-switch").assertDoesNotExist()
        rule.onNodeWithText("玻璃效果").assertDoesNotExist()
        rule.onNodeWithContentDescription("模糊强度").assertDoesNotExist()
        rule.onNodeWithContentDescription("背景不透明度").assertDoesNotExist()
        rule.onNodeWithContentDescription("按键不透明度").assertDoesNotExist()
        saveScreen("ios-gradient-preview")
        selectAppearance(false)
        rule.runOnIdle {
            assertEquals("pure_black", SettingsPreferences.getKeyboardTheme(context))
            assertEquals(original, FrostedGlassPreferences.readProfiles(context))
        }
        rule.onNodeWithText("取消").performScrollTo().performClick()
        rule.runOnIdle { assertFalse(applied); showing = true }
        rule.onNodeWithContentDescription("模糊强度").assertDoesNotExist()
        rule.onNodeWithText("应用").performScrollTo().performClick()
        rule.runOnIdle {
            assertTrue(applied)
            assertEquals(original, FrostedGlassPreferences.readProfiles(context))
            assertEquals(TransparentGlassTheme.ID, SettingsPreferences.getKeyboardTheme(context))
            SettingsPreferences.setKeyboardTheme(context, "lavender_purple")
            assertEquals(original, FrostedGlassPreferences.readProfiles(context))
        }
    }

    private fun sliderValue(label: String): Float = rule.onNodeWithContentDescription(label)
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun changeSlider(label: String, value: Float): Float {
        rule.onNodeWithContentDescription(label).performScrollTo().performTouchInput {
            swipe(Offset(width * 0.25f, centerY), Offset(width * value, centerY), durationMillis = 250)
        }
        return sliderValue(label)
    }

    private fun selectAppearance(isDark: Boolean) {
        rule.onNodeWithTag(if (isDark) "theme-preview-dark" else "theme-preview-light")
            .performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun saveScreen(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "glass-theme-settings")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun settingsEntryOpensTouchEditableDraftWithoutChangingAppearanceOrColour() {
        SettingsPreferences.setDarkMode(context, 1)
        SettingsPreferences.setKeyboardTheme(context, "pure_black")
        FrostedGlassPreferences.save(context, FrostedGlassConfig(true, 12f, 0.64f, 0.27f), true)
        val original = FrostedGlassPreferences.readProfiles(context)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val application = object : Application() { override fun getApplicationContext(): Context = context }
        rule.runOnIdle {
            ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ThemeSettingsViewModel(application) as T
            })[ThemeSettingsViewModel::class.java]
        }
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalViewModelStoreOwner provides owner) {
                MaterialTheme { ThemeSettingsContent(onBack = {}) }
            }
        }
        rule.onNodeWithText("透明度与模糊").performScrollTo()
        saveScreen("theme-settings")
        rule.onNodeWithContentDescription("背景不透明度").assertDoesNotExist()
        rule.onNodeWithText("透明度与模糊").performClick()
        rule.onNodeWithTag("theme-preview-glass-switch").assertDoesNotExist()
        assertEquals(0.64f, sliderValue("背景不透明度"))
        val changedDark = changeSlider("背景不透明度", 0.48f)
        assertNotEquals(0.64f, changedDark)
        assertEquals(0.27f, sliderValue("按键不透明度"))
        selectAppearance(false)
        assertEquals(0.27f, sliderValue("背景不透明度"))
        assertEquals(changedDark, sliderValue("按键不透明度"))
        val changedLight = changeSlider("背景不透明度", 0.71f)
        selectAppearance(true)
        assertEquals(changedLight, sliderValue("按键不透明度"))
        assertEquals(changedDark, sliderValue("背景不透明度"))
        rule.runOnIdle { assertEquals(original, FrostedGlassPreferences.readProfiles(context)) }
        rule.onNodeWithText("取消").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(original, FrostedGlassPreferences.readProfiles(context)) }
        rule.onNodeWithText("透明度与模糊").performScrollTo().performClick()
        assertEquals(0.64f, sliderValue("背景不透明度")) // canceled draft is discarded
        assertEquals(0.27f, sliderValue("按键不透明度"))
        val background = changeSlider("背景不透明度", 0.73f)
        val key = changeSlider("按键不透明度", 0.43f)
        rule.onNodeWithContentDescription("按键不透明度").assertIsDisplayed()
        saveScreen("glass-settings")
        rule.onNodeWithText("应用").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1, SettingsPreferences.getDarkMode(context))
            assertEquals("pure_black", SettingsPreferences.getKeyboardTheme(context))
            assertEquals(background, FrostedGlassPreferences.read(context).backgroundOpacity)
            assertEquals(key, FrostedGlassPreferences.read(context).keyOpacity)
            assertEquals(key, FrostedGlassPreferences.read(context, false).backgroundOpacity)
            assertEquals(background, FrostedGlassPreferences.read(context, false).keyOpacity)
            assertEquals(original.light.blurRadiusDp, FrostedGlassPreferences.read(context, false).blurRadiusDp)
            assertEquals(original.dark.blurRadiusDp, FrostedGlassPreferences.read(context, true).blurRadiusDp)
            owner.viewModelStore.clear()
        }
    }

    @Test fun unavailableSystemBlurPreservesValueAndLeavesOpacityEditable() {
        var config by mutableStateOf(FrostedGlassConfig(true, 17f, 0.4f, 0.3f))
        rule.setContent { MaterialTheme {
            FrostedThemeControls(config, isDark = true, blurAvailable = false) { config = it }
        } }
        rule.onNodeWithContentDescription("模糊强度").assertIsNotEnabled()
        rule.onNodeWithText("17 dp · 当前系统不可用").assertIsDisplayed()
        rule.onNodeWithContentDescription("背景不透明度").assertIsEnabled()
            .performTouchInput {
                swipe(Offset(width * 0.25f, centerY), Offset(width * 0.7f, centerY), durationMillis = 250)
            }
        rule.runOnIdle {
            assertEquals(17f, config.blurRadiusDp)
            assertTrue(config.backgroundOpacity > 0.6f)
            assertEquals(0.3f, config.keyOpacity)
        }
    }

    @Test fun catalogueIncludesFixedIosGradientWithOriginalPalette() {
        assertEquals(listOf("transparent_glass", "pure_black", "lavender_purple", "soft_blue"),
            KeyboardThemes.themes.take(4).map { it.id })
        val gradient = KeyboardThemes.getThemeById(TransparentGlassTheme.ID)
        assertEquals(TransparentGlassTheme.ID, gradient.id)
        assertEquals("iOS 渐变", gradient.name)
        assertEquals(BackgroundConfig(type = "gradient", angle = 135,
            colors = listOf(0xB0BDD6L, 0xDEC9D4L, 0x9DCCCCL),
            colorsDark = listOf(0x1F2229L, 0x2B2628L, 0x1B2626L)), gradient.keyboardBackground)
        val blackWhite = KeyboardThemes.getThemeById("pure_black")
        assertNotEquals(blackWhite.keyboardBackground, gradient.keyboardBackground)
        assertEquals(blackWhite.keyBgLight, gradient.keyBgLight)
        // The opaque iOS gradient deliberately uses lighter letter keys and darker
        // function keys than the black theme; glass rendering has its own tint.
        assertEquals(Color(0xFF1C1C1E), blackWhite.keyBgDark)
        assertEquals(Color(0xFF55555A), gradient.keyBgDark)
        assertEquals(Color(0xFF29292B), blackWhite.specialKeyDark)
        assertEquals(Color(0xFF3A3A3C), gradient.specialKeyDark)
        assertEquals(gradient.specialKeyDark, gradient.enterKeyDark)
        assertEquals(blackWhite.keyTextColorLight, gradient.keyTextColorLight)
        assertEquals(blackWhite.keyTextColorDark, gradient.keyTextColorDark)
    }
}
