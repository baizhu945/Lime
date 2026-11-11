package com.kingzcheung.xime.ui

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.viewmodel.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Rendered keycaps, not just a theme data object: every real input layout shares the roles. */
class KeyColorRolesTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val folder = File(app.cacheDir, "key-color-roles-fixture")
    private val context = object : ContextWrapper(app) {
        override fun getFilesDir() = folder
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("key_roles_test_$name", mode)
    }
    @Before fun before() {
        SettingsPreferences.getPrefsPublic(context).edit().clear().commit()
        File(folder, "rime").mkdirs()
        File(folder, "rime/xime.custom.yaml").writeText("""
            color_schemes:
              test_color_roles:
                name: Test roles
                primary_color: 0xC3CDDF
                special_key_bg_color: 0x6D717C
                special_key_bg_color_dark: 0x6D717C
                enter_key_bg_color: 0x3F4E68
                enter_key_bg_color_dark: 0x3F4E68
                key_text_color: 0xFFFFFF
                key_text_color_dark: 0xFFFFFF
                special_key_text_color: 0xFFFFFF
                special_key_text_color_dark: 0xFFFFFF
                candidate_text_color: 0xE0E0E0
                candidate_text_color_dark: 0xE0E0E0
                keyboard_background:
                  type: solid
                  color: 0x292929
                  color_dark: 0x292929
                key_background:
                  type: solid
                  color: 0x525252
                  color_dark: 0x525252
        """.trimIndent())
        KeysConfigHelper.loadConfig(context)
        KeyboardThemes.reload(context)
    }
    @After fun after() {
        SettingsPreferences.getPrefsPublic(context).edit().clear().commit()
        File(folder, "rime/xime.custom.yaml").delete()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.reload(app)
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
    }
    private data class Mode(val name: String, val schema: String = "rime_ice", val ascii: Boolean = false,
        val layout: KeyboardLayoutState? = null, val split: Boolean = false, val handwriting: Boolean = false,
        val enterText: String = "回车")

    @Test fun allInputModesUseNormalSpaceAndSeparateEnterColor() = verifyModes(true, listOf(
        Mode("english", ascii = true), Mode("pinyin26"), Mode("pinyin14", "pinyin_14jian"),
        Mode("t9", "t9_pinyin"), Mode("japanese26", "japanese"), Mode("kana", "japanese_kana"),
        Mode("stroke", "stroke"), Mode("number", layout = KeyboardLayoutState.Number),
        Mode("number-done", layout = KeyboardLayoutState.Number, enterText = "完成"),
        Mode("symbols", layout = KeyboardLayoutState.CommonSymbol), Mode("handwriting", handwriting = true),
        Mode("split", ascii = true, split = true)))

    @Test fun customPaletteRetainsExplicitColorsInLightSystemMode() = verifyModes(false,
        listOf(Mode("light-english", ascii = true), Mode("light-kana", "japanese_kana")))

    private fun verifyModes(dark: Boolean, modes: List<Mode>) {
        val palette = com.kingzcheung.xime.ui.theme.resolveKeyboardPalette("test_color_roles", dark)
        val current = mutableStateOf(modes.first())
        var currentVm: KeyboardViewModel? = null
        rule.setContent {
            val mode = current.value
            val config = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 640; screenHeightDp = 900; orientation = Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context,
                LocalDensity provides Density(1f)) {
                XimeTheme(darkTheme = dark, themeId = "test_color_roles") {
                    // Fixed IME draws one background behind KeyboardView in the service.
                    // Reproduce that real container; KeyboardView deliberately stays transparent.
                    val theme = KeyboardThemes.getThemeById("test_color_roles")
                    Box(Modifier.size(640.dp, 360.dp).testTag("key-colors-root")
                        .keyboardBackground(theme.keyboardBackground, dark,
                            KeyboardThemes.getKeyboardBackgroundColor(theme.id, dark))) {
                    key(mode) {
                        val vm = remember { KeyboardViewModel(app) }
                        SideEffect { currentVm = vm }
                        KeyboardView(vm, KeyboardUiState(currentSchemaId = mode.schema, isAsciiMode = mode.ascii,
                            isDarkTheme = dark, themeId = "test_color_roles", keyboardHeightDp = 320, enterKeyText = mode.enterText),
                            KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                            modifier = Modifier.fillMaxSize())
                    }
                    }
                }
            }
        }
        for (mode in modes) {
            rule.runOnIdle {
                KeysConfigHelper.setActiveKeyboardSchema(mode.schema)
                SettingsPreferences.setSplitKeyboardEnabled(context, mode.split)
                current.value = mode
            }
            rule.waitForIdle()
            rule.runOnIdle {
                mode.layout?.let { currentVm!!.setKeyboardState(it) }
                if (mode.handwriting) currentVm!!.enterTemporaryHandwriting()
            }
            rule.waitForIdle()
            val root = rule.onNodeWithTag("key-colors-root", useUnmergedTree = true)
            val bitmap = root.captureToImage().asAndroidBitmap()
            save("custom-roles-${mode.name}", bitmap)
            val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
            listOf(20, bitmap.height / 2, bitmap.height - 2).forEach { y ->
                assertEquals("${mode.name} background at (1,$y)", palette.background.toArgb(), bitmap.getPixel(1, y))
            }
            assertTrue("${mode.name} toolbar needs visible gray icons on its dark background",
                countColor(bitmap, palette.candidateText.toArgb(), 0, 0, bitmap.width, 40) >= 10)
            val spaces = rule.onAllNodesWithContentDescription("空格", useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("${mode.name} has no space control", spaces.isNotEmpty())
            spaces.forEach { assertColorNear(bitmap, it.boundsInRoot.center - origin, palette.key.toArgb(), "${mode.name} space") }
            // Stroke has a fixed confirmation action; number and other layouts use the editor action label.
            val enterText = if (mode.schema == "stroke") "确定" else mode.enterText
            val enter = rule.onNodeWithContentDescription(enterText, useUnmergedTree = true).fetchSemanticsNode()
            assertColorNear(bitmap, enter.boundsInRoot.center - origin, palette.enter.toArgb(), "${mode.name} enter")
            listOf(enter, spaces.first()).forEach { node ->
                val bounds = node.boundsInRoot.translate(-origin)
                assertTrue("${mode.name} key foreground must contain white text or icon pixels",
                    countColor(bitmap, palette.text.toArgb(), bounds.left.toInt(), bounds.top.toInt(),
                        bounds.right.toInt(), bounds.bottom.toInt()) >= 5)
            }
            val language = rule.onAllNodesWithTag("language-key-control", useUnmergedTree = true).fetchSemanticsNodes()
            if (language.isNotEmpty()) language.forEach {
                assertColorNear(bitmap, it.boundsInRoot.center - origin, palette.function.toArgb(), "${mode.name} function")
            }
        }
    }
    private fun assertColorNear(bitmap: Bitmap, center: androidx.compose.ui.geometry.Offset, expected: Int, label: String) {
        var total = 0; var matching = 0
        val left = (center.x.toInt() - 18).coerceAtLeast(0)
        val top = (center.y.toInt() - 18).coerceAtLeast(0)
        for (y in top until (center.y.toInt() + 18).coerceAtMost(bitmap.height)) {
            for (x in left until (center.x.toInt() + 18).coerceAtMost(bitmap.width)) {
                total++
                if (bitmap.getPixel(x, y) == expected) matching++
            }
        }
        assertTrue("$label expected ${Integer.toHexString(expected)}, matched $matching / $total pixels", total > 0 && matching > total / 3)
    }
    private fun countColor(bitmap: Bitmap, color: Int, left: Int, top: Int, right: Int, bottom: Int): Int {
        var count = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(bitmap.height))
            for (x in left.coerceAtLeast(0) until right.coerceAtMost(bitmap.width))
                // Thin vector strokes may never cover a full pixel; allow 3/255 antialias rounding.
                if (listOf(0, 8, 16).all { shift ->
                    kotlin.math.abs(((bitmap.getPixel(x, y) ushr shift) and 255) - ((color ushr shift) and 255)) <= 3
                }) count++
        return count
    }
    private fun save(name: String, bitmap: Bitmap) {
        val folder = File(app.getExternalFilesDir(null), "cyime").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
