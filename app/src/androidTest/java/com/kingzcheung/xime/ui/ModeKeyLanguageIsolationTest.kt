package com.kingzcheung.xime.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.PanelType
import com.kingzcheung.xime.settings.KeyboardRow
import com.kingzcheung.xime.settings.KeyboardRowsLayout
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** ?123 是面板导航，不论当前语言都不能把它解释为 language-key/IME switch。 */
class ModeKeyLanguageIsolationTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun assertModeKeyOnlyOpensNumber(schema: String, ascii: Boolean) {
        val vm = KeyboardViewModel(app)
        if (ascii) vm.setKeyboardState(com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.English)
        // 不受测试前其他用例保存的 ?123 记忆页影响。
        com.kingzcheung.xime.settings.SettingsPreferences.setModeChangeTarget(app, com.kingzcheung.xime.keyboard.ModeChangeTarget.NUMBER)
        val calls = mutableListOf<String>()
        val prefs = KeyboardInputPreferences(qwertyCommaKey = true, qwertyPeriodKey = false)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalKeyboardInputPreferences provides prefs) {
                XimeTheme(darkTheme = false, themeId = "lavender_purple") {
                    Box(Modifier.size(400.dp, 340.dp).background(KeyboardThemes.getKeyboardBackgroundColor("lavender_purple", false))) {
                        KeyboardView(
                            viewModel = vm,
                            state = KeyboardUiState(currentSchemaId = schema, isAsciiMode = ascii,
                                themeId = "lavender_purple", keyboardHeightDp = 320),
                            callbacks = KeyboardCallbacks(onKeyPress = { key, _ -> calls += key }, onCandidateSelect = {}),
                            modifier = Modifier.fillMaxSize().testTag("keyboard"),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val tag = "configured-mode-key"
        rule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { click() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(PanelType.NUMBER, (vm.page.value as? KeyboardPage.Panel)?.type)
            assertFalse("?123 不应发语言切换按键: $calls", calls.contains("ime_switch"))
        }
    }

    private fun installXimeRows(): Pair<java.lang.reflect.Field, Any?> {
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.reload(app)
        val f = KeysConfigHelper::class.java.getDeclaredField("_zhRowsLayout").apply { isAccessible = true }
        val previous = f.get(KeysConfigHelper)
        val rows = listOf(
            "qwertyuiop".map(Char::toString), "asdfghjkl".map(Char::toString),
            listOf("shift") + "zxcvbnm".map(Char::toString) + "delete",
            listOf("mode_change", "comma", "space", "earth", "enter"),
        )
        rule.runOnIdle { f.set(KeysConfigHelper, KeyboardRowsLayout(rows.map { KeyboardRow(it) })) }
        return f to previous
    }

    private fun restoreRows(saved: Pair<java.lang.reflect.Field, Any?>) {
        rule.runOnIdle { saved.first.set(KeysConfigHelper, saved.second) }
    }

    @Test fun chineseModeKeyOnlyOpensPanel() {
        val saved = installXimeRows()
        try { assertModeKeyOnlyOpensNumber("rime_ice", false) } finally { restoreRows(saved) }
    }

    @Test fun englishModeKeyOnlyOpensPanel() {
        val saved = installXimeRows()
        try { assertModeKeyOnlyOpensNumber("rime_ice", true) } finally { restoreRows(saved) }
    }

    @Test fun japaneseModeKeyOnlyOpensNumberWithoutChangingLanguage() {
        val saved = installXimeRows()
        try { assertModeKeyOnlyOpensNumber("japanese", false) } finally { restoreRows(saved) }
    }
}
