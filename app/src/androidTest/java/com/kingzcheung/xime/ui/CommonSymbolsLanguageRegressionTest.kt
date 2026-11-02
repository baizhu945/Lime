package com.kingzcheung.xime.ui

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.ModeChangeTarget
import com.kingzcheung.xime.keyboard.PanelType
import com.kingzcheung.xime.settings.KeyboardRow
import com.kingzcheung.xime.settings.KeyboardRowsLayout
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.ASCII_CONTEXT_OFF
import com.kingzcheung.xime.viewmodel.ASCII_CONTEXT_ON
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** ?123 opens the common-symbol *panel*, not the separate categorized symbol overlay. */
class CommonSymbolsLanguageRegressionTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val context = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("common_symbols_language_test_$name", mode)
    }
    private lateinit var rowsField: java.lang.reflect.Field
    private var previousRows: Any? = null
    private lateinit var vm: KeyboardViewModel
    private val calls = mutableListOf<String>()

    @Before fun prepare() {
        SettingsPreferences.getPrefsPublic(context).edit().clear().commit()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.reload(app)
        rowsField = KeysConfigHelper::class.java.getDeclaredField("_zhRowsLayout").apply { isAccessible = true }
        previousRows = rowsField.get(KeysConfigHelper)
        val rows = listOf(
            "qwertyuiop".map(Char::toString), "asdfghjkl".map(Char::toString),
            listOf("shift") + "zxcvbnm".map(Char::toString) + "delete",
            listOf("mode_change", "comma", "space", "earth", "enter"),
        )
        rowsField.set(KeysConfigHelper, KeyboardRowsLayout(rows.map { KeyboardRow(it) }))
        SettingsPreferences.getPrefsPublic(context).edit()
            .putBoolean("qwerty_comma_key", true).putBoolean("qwerty_period_key_v2", false).commit()
        SettingsPreferences.setModeChangeTarget(context, ModeChangeTarget.COMMON_SYMBOL)
    }

    @After fun cleanup() {
        if (::rowsField.isInitialized) rowsField.set(KeysConfigHelper, previousRows)
        SettingsPreferences.getPrefsPublic(context).edit().clear().commit()
    }

    private fun verify(english: Boolean, landscape: Boolean, visitNumbers: Boolean) {
        vm = KeyboardViewModel(app)
        if (english) vm.setKeyboardState(KeyboardLayoutState.English)
        val original = vm.keyboardState.value
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = if (landscape) 800 else 400
                screenHeightDp = if (landscape) 400 else 850
            }
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context,
                LocalDensity provides Density(1f)) {
                XimeTheme(darkTheme = true, themeId = "soft_blue") {
                    Box(Modifier.size(400.dp, 340.dp)) {
                        KeyboardView(vm, KeyboardUiState(currentSchemaId = "rime_ice", isAsciiMode = english,
                            isDarkTheme = true, themeId = "soft_blue", keyboardHeightDp = 320),
                            KeyboardCallbacks(onKeyPress = { key, _ -> calls += key }, onCandidateSelect = {}),
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        rule.onNodeWithTag("configured-mode-key", true).assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle { assertEquals(PanelType.COMMON_SYMBOL, (vm.page.value as? KeyboardPage.Panel)?.type) }
        rule.onNodeWithTag("common-symbol-panel", true).assertExists()
        rule.onNodeWithTag("common-symbol-return", true)
            .assert(hasAnyDescendant(hasText(if (english) "ABC" else "中文")))
        val panel = hasAnyAncestor(hasTestTag("common-symbol-panel"))
        val expected = if (english) listOf("@", ",", ".", "!", "?") else listOf("＠", "，", "。", "！", "？")
        val opposite = if (english) listOf("＠", "，", "。", "！", "？") else listOf("@", ",", ".", "!", "?")
        expected.forEach { rule.onNode(panel and hasText(it), true).assertExists() }
        opposite.forEach { rule.onNode(panel and hasText(it), true).assertDoesNotExist() }
        assertNoLanguageActions()
        val comma = if (english) "," else "，"
        rule.onNode(panel and hasText(comma), true).performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(listOf(comma), calls)
            calls.clear()
        }
        rule.onNodeWithTag("common-symbol-return", true).performTouchInput { click() }
        assertMain(original)

        if (visitNumbers) {
            // Reopen the remembered common panel, then use its own 123 key.
            rule.onNodeWithTag("configured-mode-key", true).performTouchInput { click() }
            rule.onNode(panel and hasText("123"), true).performTouchInput { click() }
            rule.runOnIdle { assertEquals(PanelType.NUMBER, (vm.page.value as? KeyboardPage.Panel)?.type) }
            rule.onNodeWithText("1", true).assertExists()
            rule.onNodeWithTag("mode-slot-2", true).performTouchInput { click() }
            assertMain(original)
        }
    }

    private fun assertNoLanguageActions() = rule.runOnIdle {
        assertFalse("Unexpected ASCII on: $calls", ASCII_CONTEXT_ON in calls)
        assertFalse("Unexpected ASCII off: $calls", ASCII_CONTEXT_OFF in calls)
        assertFalse("Unexpected language switch: $calls", "ime_switch" in calls)
        assertTrue("Navigation should not send any keys: $calls", calls.isEmpty())
    }

    private fun assertMain(original: KeyboardLayoutState) {
        rule.runOnIdle {
            assertTrue(vm.page.value is KeyboardPage.Main)
            assertEquals(original, vm.keyboardState.value)
        }
        assertNoLanguageActions()
    }

    @Test fun chinesePortrait() = verify(false, false, false)
    @Test fun englishPortrait() = verify(true, false, false)
    @Test fun chineseLandscapeAndNumber() = verify(false, true, true)
    @Test fun englishLandscapeAndNumber() = verify(true, true, true)
}
