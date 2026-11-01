package com.kingzcheung.xime.ui

import android.app.Application
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 底排 ?123 单键在真实 KeyboardView 里的路由：点按进上次用过的页面（默认数字），
 * 长按两项各自直达并把“上次页面”记下来；只切面板，不碰输入引擎和按键队列。
 */
class ConfiguredModeKeyRoutingTest {
    @get:Rule val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    /** 独立的一份 SharedPreferences，不碰设备上的真实设置。 */
    private val context = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("configured_mode_key_test_$name", mode)
    }
    private val keys = mutableListOf<String>()
    private lateinit var vm: KeyboardViewModel
    private lateinit var layoutField: java.lang.reflect.Field
    private var previousLayout: Any? = null

    @Before fun prepare() {
        SettingsPreferences.getPrefsPublic(context).edit().clear().commit()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.reload(app)
        layoutField = KeysConfigHelper::class.java.getDeclaredField("_zhRowsLayout").apply { isAccessible = true }
        previousLayout = layoutField.get(KeysConfigHelper)
        val rows = listOf(
            "qwertyuiop".map(Char::toString), "asdfghjkl".map(Char::toString),
            listOf("shift") + "zxcvbnm".map(Char::toString) + "delete",
            listOf("mode_change", "comma", "space", "earth", "enter"),
        )
        layoutField.set(KeysConfigHelper, KeyboardRowsLayout(rows.map { KeyboardRow(it) }))
        // ?123 单键只在「只开逗号键」的 Xime 配置行里出现。
        SettingsPreferences.getPrefsPublic(context).edit()
            .putBoolean("qwerty_comma_key", true).putBoolean("qwerty_period_key_v2", false).commit()
    }

    @After fun cleanup() {
        if (::layoutField.isInitialized) layoutField.set(KeysConfigHelper, previousLayout)
        SettingsPreferences.getPrefsPublic(context).edit().clear().commit()
    }

    private fun mount() {
        vm = KeyboardViewModel(app)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f)) {
                XimeTheme(darkTheme = true, themeId = "soft_blue") {
                    Box(Modifier.size(400.dp, 360.dp).testTag("mode-routing-keyboard")
                        .background(KeyboardThemes.getKeyboardBackgroundColor("soft_blue", true))) {
                        KeyboardView(
                            viewModel = vm,
                            state = KeyboardUiState(currentSchemaId = "rime_ice", isDarkTheme = true,
                                themeId = "soft_blue", keyboardHeightDp = 330, keyboardBottomPaddingDp = 12),
                            callbacks = KeyboardCallbacks(onKeyPress = { key, _ -> keys.add(key) },
                                onCandidateSelect = {}),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun page(): KeyboardPage = vm.page.value

    private fun modeKeyWidth(): Float =
        rule.onNodeWithTag("configured-mode-key", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width

    @Test fun tapOpensTheLastPageAndLongPressRemembersTheOther() {
        mount()
        rule.onNodeWithTag("configured-mode-key", useUnmergedTree = true).assertExists()
        // 1) 没设置过：点按进数字页，偏好记成数字。
        rule.onNodeWithTag("configured-mode-key", useUnmergedTree = true).performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(PanelType.NUMBER, (page() as? KeyboardPage.Panel)?.type)
            assertEquals(ModeChangeTarget.NUMBER, SettingsPreferences.modeChangeTarget(context))
            assertTrue("点按只切面板，不往引擎送键：$keys", keys.isEmpty())
        }
        // 2) 回到主键盘，长按往右选「常用符号」：直达常用符号页并记下来。
        rule.runOnIdle { vm.returnToTextKeyboard() }
        rule.waitForIdle()
        val half = modeKeyWidth() * 0.5f
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("configured-mode-key", useUnmergedTree = true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("configured-mode-key", useUnmergedTree = true).performTouchInput {
            moveBy(Offset(half, 0f)); up()
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(PanelType.COMMON_SYMBOL, (page() as? KeyboardPage.Panel)?.type)
            assertEquals(ModeChangeTarget.COMMON_SYMBOL, SettingsPreferences.modeChangeTarget(context))
        }
        // 3) 再点一次 ?123：这次直接进上次的常用符号页。
        rule.runOnIdle { vm.returnToTextKeyboard() }
        rule.waitForIdle()
        rule.onNodeWithTag("configured-mode-key", useUnmergedTree = true).performTouchInput { click() }
        rule.runOnIdle { assertEquals(PanelType.COMMON_SYMBOL, (page() as? KeyboardPage.Panel)?.type) }
    }
}
