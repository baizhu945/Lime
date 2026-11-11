package com.kingzcheung.xime.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.legibleForTheme
import com.kingzcheung.xime.ui.settings.ThemeKeyboardPreview
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GlassPreviewProductionCompositingTest {
    @get:Rule val rule = createComposeRule()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(base.filesDir, "glass-comparison-${UUID.randomUUID()}")
    private lateinit var prefs: SharedPreferences
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir() = folder
        override fun getSharedPreferences(name: String, mode: Int) = prefs
    }
    private data class Case(val id: String, val dark: Boolean)
    private val host = 0xFF304050.toInt()

    @Before fun setup() {
        prefs = base.getSharedPreferences("glass-comparison-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        check(File(folder, "rime").mkdirs())
        File(folder, "rime/xime.custom.yaml").writeText("""
            color_schemes:
              compare_different:
                name: Compare different
                primary_color: 0x778899
                special_key_bg_color: 0xFFFFFF
                special_key_bg_color_dark: 0xFFFFFF
                enter_key_bg_color: 0xFFFFFF
                enter_key_bg_color_dark: 0xFFFFFF
                special_key_text_color: 0x000000
                special_key_text_color_dark: 0x000000
                key_text_color: 0x000000
                key_text_color_dark: 0x000000
                key_background: {type: solid, color: 0x808080, color_dark: 0x808080}
                keyboard_background: {type: solid, color: 0xF0F0F0, color_dark: 0xF0F0F0}
              compare_equal:
                name: Compare equal
                primary_color: 0x778899
                special_key_bg_color: 0xFFFFFF
                special_key_bg_color_dark: 0xFFFFFF
                enter_key_bg_color: 0xFFFFFF
                enter_key_bg_color_dark: 0xFFFFFF
                special_key_text_color: 0x000000
                special_key_text_color_dark: 0x000000
                key_text_color: 0x000000
                key_text_color_dark: 0x000000
                key_background: {type: solid, color: 0xFFFFFF, color_dark: 0xFFFFFF}
                keyboard_background: {type: solid, color: 0xF0F0F0, color_dark: 0xF0F0F0}
              transparent_glass:
                keyboard_background: {type: solid, color: 0xF0F0F0, color_dark: 0x101010}
        """.trimIndent())
        KeysConfigHelper.loadConfig(context)
        KeyboardThemes.reload(context)
    }

    @After fun restore() {
        try { KeysConfigHelper.loadConfig(base); KeyboardThemes.reload(base); KeysConfigHelper.setActiveKeyboardSchema("rime_ice") }
        finally { check(folder.deleteRecursively()) }
    }

    @Test fun previewAndKeyboardUseSameGlassCompositionForBothAppearances() {
        val cases = listOf("compare_different", "compare_equal", "transparent_glass")
            .flatMap { id -> listOf(Case(id, false), Case(id, true)) }
        val current = mutableStateOf(cases.first())
        val requested = FrostedGlassConfig(true, 0f, 0.8f, 0.5f)
        fun configure(c: Case) {
            SettingsPreferences.setKeyboardTheme(context, c.id)
            FrostedGlassPreferences.save(context, requested, c.dark)
        }
        configure(cases.first())
        val app = base.applicationContext as Application
        rule.setContent {
            val c = current.value
            key(c) {
                val effective = requested.legibleForTheme(c.id, c.dark)
                val config = Configuration(LocalConfiguration.current).apply { screenWidthDp = 400; screenHeightDp = 800 }
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                    LocalDensity provides Density(1f), LocalKeyboardInputPreferences provides
                        KeyboardInputPreferences(frostedGlass = effective)) {
                    XimeTheme(darkTheme = c.dark, themeId = c.id) {
                        val theme = KeyboardThemes.getThemeById(c.id)
                        Column {
                            Box(Modifier.size(400.dp, 240.dp).background(Color(host)).testTag("preview-root")) {
                                ThemeKeyboardPreview(theme, c.dark, Modifier.fillMaxSize())
                            }
                            Box(Modifier.size(400.dp, 320.dp).background(Color(host)).testTag("production-root")) {
                                val vm = remember { KeyboardViewModel(app) }
                                KeyboardView(vm, KeyboardUiState(currentSchemaId = "rime_ice", isDarkTheme = c.dark,
                                    themeId = c.id, keyboardHeightDp = 320, enterKeyText = "回车"),
                                    KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                                    modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        for (c in cases) {
            rule.runOnIdle { configure(c); current.value = c }
            rule.waitForIdle()
            val effective = requested.legibleForTheme(c.id, c.dark)
            assertTrue("effective key alpha outside expected range: $c", effective.keyOpacity in 0.5f..1f)
            assertTrue("effective panel alpha outside expected range: $c", effective.backgroundOpacity in 0.8f..1f)
            assertEquals(effective, FrostedGlassPreferences.read(context, c.dark).legibleForTheme(c.id, c.dark))
            val panel = if (c.id == "transparent_glass" && c.dark) 0xFF101010.toInt() else 0xFFF0F0F0.toInt()
            val scale = if (c.id == "compare_different" || (c.id == "transparent_glass" && !c.dark)) 0.5f else 1f
            // The effective opacity may be raised by the real contrast guard. Independently
            // calculate the material lift: dark #3A3A3C maps to #353535 below full
            // opacity; the light #D4D4D8 moves away from white by 1/opacity.
            val opacity = effective.keyOpacity
            fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            val tint = when {
                c.id == "transparent_glass" && c.dark && opacity < 1f -> {
                    val v = (53f / opacity + 0.5f).toInt().coerceAtMost(255); rgb(v, v, v)
                }
                c.id == "transparent_glass" && c.dark -> 0xFF3A3A3C.toInt()
                c.id == "transparent_glass" -> {
                    fun lift(v: Int) = (255f - (255-v) / opacity + 0.5f).toInt().coerceIn(0, 255)
                    rgb(lift(212), lift(212), lift(216))
                }
                else -> 0xFFFFFFFF.toInt()
            }
            val expected = blend(tint, blend(panel, host, effective.backgroundOpacity), opacity * scale)
            val preview = sample("preview-root", "?123", false)
            val production = sample("production-root", "回车", true)
            assertColor(expected, preview, "$c preview")
            assertColor(expected, production, "$c production")
            assertColor(preview, production, "$c preview vs production")
        }
    }

    private fun sample(rootTag: String, label: String, description: Boolean): Int {
        val root = rule.onNodeWithTag(rootTag, useUnmergedTree = true)
        val node = if (description) rule.onNodeWithContentDescription(label, useUnmergedTree = true)
            else rule.onAllNodesWithText(label, useUnmergedTree = true)
                .filter(hasAnyAncestor(hasTestTag(rootTag)))[0]
        val bounds = node.fetchSemanticsNode().boundsInRoot.translate(-root.fetchSemanticsNode().boundsInRoot.topLeft)
        val bitmap = root.captureToImage().asAndroidBitmap()
        // Text bounds are wholly inside the preview key; production's semantics bounds cover
        // the key itself, so sample only its central 20px. Mode ignores glyph antialiasing.
        val area = if (description) androidx.compose.ui.geometry.Rect(bounds.center.x - 10, bounds.center.y - 10,
            bounds.center.x + 10, bounds.center.y + 10) else bounds
        val counts = mutableMapOf<Int, Int>()
        for (y in area.top.toInt().coerceAtLeast(0) until area.bottom.toInt().coerceAtMost(bitmap.height))
            for (x in area.left.toInt().coerceAtLeast(0) until area.right.toInt().coerceAtMost(bitmap.width)) {
                val p = bitmap.getPixel(x, y); counts[p] = (counts[p] ?: 0) + 1
            }
        return checkNotNull(counts.maxByOrNull { it.value }).key
    }
    private fun blend(fg: Int, bg: Int, alpha: Float): Int {
        fun channel(shift: Int) = (((fg ushr shift and 255) * alpha + (bg ushr shift and 255) * (1-alpha)) + 0.5f).toInt()
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    private fun assertColor(expected: Int, actual: Int, message: String) {
        assertTrue("$message expected ${Integer.toHexString(expected)} got ${Integer.toHexString(actual)}",
            listOf(0, 8, 16).all { kotlin.math.abs((expected ushr it and 255) - (actual ushr it and 255)) <= 3 })
    }
}
