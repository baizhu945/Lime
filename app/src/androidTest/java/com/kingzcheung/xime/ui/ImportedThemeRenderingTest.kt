package com.kingzcheung.xime.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyButton
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.ui.settings.ThemePreviewSheet
import com.kingzcheung.xime.ui.settings.ThemeKeyboardPreview
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.resolveKeyboardPalette
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The YAML is loaded through the same merge/reload path as an imported custom theme. */
class ImportedThemeRenderingTest {
    @get:Rule val rule = createComposeRule()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(base.filesDir, "theme-render-${UUID.randomUUID()}")
    private lateinit var preferences: SharedPreferences
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir() = folder
        override fun getSharedPreferences(name: String, mode: Int) = preferences
    }

    @Before fun loadFixture() {
        preferences = base.getSharedPreferences("theme-render-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        check(File(folder, "rime").mkdirs())
        File(folder, "rime/xime.custom.yaml").writeText("""
            color_schemes:
              imported_render_test:
                name: Imported rendering test
                primary_color: 0x778899
                special_key_text_color: 0xFF087020
                special_key_text_color_dark: 0xFFF020A0
                special_key_bg_color: 0xFFE5E5E5
                special_key_bg_color_dark: 0xFF202020
                enter_key_bg_color: 0xFFE5E5E5
                enter_key_bg_color_dark: 0xFF202020
              legacy_render_test:
                name: Legacy rendering test
                primary_color: 0x778899
        """.trimIndent())
        KeysConfigHelper.loadConfig(context)
        KeyboardThemes.reload(context)
        SettingsPreferences.setDarkMode(context, 1)
        FrostedGlassPreferences.save(context, FrostedGlassConfig.defaults(true, false), true)
        FrostedGlassPreferences.save(context, FrostedGlassConfig.defaults(false, false), false)
    }

    @After fun restore() {
        try {
            KeysConfigHelper.loadConfig(base)
            KeyboardThemes.reload(base)
            KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
        } finally {
            check(folder.deleteRecursively())
        }
    }

    @Test fun importedColorsReachPaletteAndActualPreviewInBothAppearances() {
        val theme = KeyboardThemes.getThemeById("imported_render_test")
        assertEquals("imported_render_test", theme.id)
        assertEquals(Color(0xFFF020A0), theme.specialKeyTextColorDark)
        assertEquals(Color(0xFFF020A0), resolveKeyboardPalette(theme.id, true).functionText)
        assertEquals(Color(0xFF087020), resolveKeyboardPalette(theme.id, false).functionText)
        assertEquals(Color.White, resolveKeyboardPalette("legacy_render_test", true).functionText)
        assertEquals(KeyboardThemes.getSpecialKeyTextColor("legacy_render_test", false),
            resolveKeyboardPalette("legacy_render_test", false).functionText)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { ThemePreviewSheet(theme, onApply = {}, onDismiss = {}) }
            }
        }
        assertTextContainsColor("?123", 0xFFF020A0.toInt())
        rule.onNodeWithTag("theme-preview-light").performScrollTo().performClick()
        rule.waitForIdle()
        assertTextContainsColor("?123", 0xFF087020.toInt())
    }

    @Test fun unsavedImagePreviewResolvesColorsFromSelectedBaseTheme() {
        val baseTheme = KeyboardThemes.getThemeById("imported_render_test")
        val unsaved = baseTheme.copy(id = "unsaved-image-fixture")
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    ThemeKeyboardPreview(unsaved, isDark = true,
                        modifier = Modifier.size(400.dp, 240.dp), colorThemeId = baseTheme.id)
                }
            }
        }
        assertTextContainsColor("?123", 0xFFF020A0.toInt())
    }

    @Test fun importedPaletteRendersOnRealProductionKeyButton() {
        val palette = resolveKeyboardPalette("imported_render_test", true)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    Box(Modifier.size(180.dp, 80.dp)) {
                        KeyButton("?123", {}, palette.function, palette.functionText, shadowEnabled = false)
                    }
                }
            }
        }
        assertTextContainsColor("?123", 0xFFF020A0.toInt())
    }

    @Test fun importedForegroundFlowsThroughRealStandardAndT9KeyboardLayouts() {
        val schema = mutableStateOf("rime_ice")
        val dark = mutableStateOf(true)
        val app = base.applicationContext as Application
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 400; screenHeightDp = 800
            }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                LocalDensity provides Density(1f)) {
                XimeTheme(darkTheme = dark.value, themeId = "imported_render_test") {
                    Box(Modifier.size(400.dp, 320.dp).testTag("keyboard-root")) {
                        key(schema.value) {
                            val vm = remember { KeyboardViewModel(app) }
                            KeyboardView(vm, KeyboardUiState(currentSchemaId = schema.value,
                                isDarkTheme = dark.value, themeId = "imported_render_test",
                                keyboardHeightDp = 320, enterKeyText = "回车"),
                                KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                                modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        for (mode in listOf("rime_ice", "t9_pinyin")) for (isDark in listOf(true, false)) {
            rule.runOnIdle { KeysConfigHelper.setActiveKeyboardSchema(mode); schema.value = mode; dark.value = isDark }
            rule.waitForIdle()
            val root = rule.onNodeWithTag("keyboard-root", useUnmergedTree = true)
            val bounds = rule.onNodeWithContentDescription("回车", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.translate(-root.fetchSemanticsNode().boundsInRoot.topLeft)
            val bitmap = root.captureToImage().asAndroidBitmap()
            val expected = if (isDark) 0xFFF020A0.toInt() else 0xFF087020.toInt()
            var count = 0
            for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height))
                for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width))
                    if (near(bitmap.getPixel(x, y), expected)) count++
            assertTrue("$mode dark=$isDark: expected function foreground, found $count pixels", count >= 3)
        }
    }

    private fun near(pixel: Int, expected: Int): Boolean = listOf(0, 8, 16).all { shift ->
        kotlin.math.abs(((pixel ushr shift) and 255) - ((expected ushr shift) and 255)) <= 12
    }

    private fun assertTextContainsColor(label: String, expected: Int) {
        val bitmap = rule.onNodeWithText(label).captureToImage().asAndroidBitmap()
        val er = android.graphics.Color.red(expected)
        val eg = android.graphics.Color.green(expected)
        val eb = android.graphics.Color.blue(expected)
        var found = false
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            if (kotlin.math.abs(android.graphics.Color.red(p) - er) <= 12 &&
                kotlin.math.abs(android.graphics.Color.green(p) - eg) <= 12 &&
                kotlin.math.abs(android.graphics.Color.blue(p) - eb) <= 12) found = true
        }
        assertTrue("Rendered $label does not contain expected foreground ${Integer.toHexString(expected)}", found)
    }
}
