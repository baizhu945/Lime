package com.kingzcheung.xime.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.keyboard.SymbolInputMode
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Collections

/**
 * 实际 KeyboardView 上的英文滑行与九键拂动：滑行出整词，同时点按、竖滑一行出角标、长按照旧；
 * 九键拂动左上右下依次输入字母，长按输入数字。
 */
class GlideAndFlickGestureTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = SettingsPreferences.getPrefsPublic(app)
    private val preferenceKeys = listOf("cursor_gesture_mode", "symbol_input_mode", "reverse_symbol_swipe",
        "neighbor_correction", "glide_typing", "glide_typing_v2", "t9_flick_letters", "fourteen_letter_swipe_dp",
        SettingsPreferences.KEY_SPLIT_KEYBOARD, SettingsPreferences.KEY_SHOW_PRESS_BUBBLE)
    private var originalPreferences: Map<String, Any?> = emptyMap()
    private val schema = mutableStateOf("rime_ice")
    private val ascii = mutableStateOf(true)
    private val commits: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before fun before() {
        originalPreferences = preferenceKeys.associateWith { prefs.all[it] }
        prefs.edit().putString("cursor_gesture_mode", "SPACE")
            .putString("symbol_input_mode", SymbolInputMode.SWIPE_UP.name)
            .putBoolean("reverse_symbol_swipe", true).putBoolean("neighbor_correction", false)
            .putBoolean("glide_typing", true).putBoolean("glide_typing_v2", true).putBoolean("t9_flick_letters", true)
            .putFloat("fourteen_letter_swipe_dp", 24f)
            .putBoolean(SettingsPreferences.KEY_SPLIT_KEYBOARD, false)
            .putBoolean(SettingsPreferences.KEY_SHOW_PRESS_BUBBLE, false).commit()
        KeysConfigHelper.loadConfig(app)
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
    }

    @After fun after() {
        rule.mainClock.autoAdvance = true
        prefs.edit().also { edit -> originalPreferences.forEach { (name, value) ->
            when (value) {
                is String -> edit.putString(name, value)
                is Boolean -> edit.putBoolean(name, value)
                is Float -> edit.putFloat(name, value)
                else -> edit.remove(name)
            }
        } }.commit()
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
    }

    private fun show() {
        rule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 600; screenHeightDp = 800
                orientation = Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalConfiguration provides configuration) {
                MaterialTheme {
                    Box(Modifier.requiredSize(600.dp, 700.dp).testTag("glide-host"), contentAlignment = Alignment.Center) {
                        key(schema.value, ascii.value) {
                            val vm = remember { KeyboardViewModel(app) }
                            KeyboardView(vm,
                                KeyboardUiState(currentSchemaId = schema.value, isAsciiMode = ascii.value,
                                    keyboardHeightDp = 320, keyboardWidthDp = 520, fixedWidthDp = 520, isDarkTheme = true),
                                KeyboardCallbacks(onKeyPress = { text, _ -> commits += "key:$text" },
                                    onCandidateSelect = {}, onCommitText = { commits += "literal:$it" },
                                    onPreeditKeyInput = { commits += "preedit:$it"; true },
                                    onGlideWord = { words, case -> commits += "glide:${case(words.first().word)}" },
                                    onCursorMove = { commits += "cursor" }),
                                modifier = Modifier.size(520.dp, 320.dp))
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun center(tag: String): Offset {
        val host = rule.onNodeWithTag("glide-host").fetchSemanticsNode().boundsInRoot
        return rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center - host.topLeft
    }

    private fun letter(char: Char) = center("qwerty-key:$char")

    private fun awaitCommit(timeoutMs: Long = 3000) {
        rule.waitUntil(timeoutMs) { commits.isNotEmpty() }
        rule.waitForIdle()
    }

    @Test fun glidingThroughLettersCommitsTheWordOnly() {
        show()
        val path = "hello".map(::letter)
        rule.onNodeWithTag("glide-host").performTouchInput {
            down(path.first())
            path.zipWithNext().forEach { (a, b) -> for (step in 1..6) moveTo(a + (b - a) * (step / 6f)) }
            up()
        }
        awaitCommit()
        assertEquals(listOf("glide:hello"), commits.toList())
    }

    @Test fun tapsVerticalSymbolsAndLongPressStillWork() {
        show()
        val f = letter('f')
        rule.onNodeWithTag("glide-host").performTouchInput { down(f); up() }
        rule.runOnIdle { assertEquals(listOf("key:f"), commits.toList()); commits.clear() }
        // 竖着滑一行：仍是角标符号，不当成滑行。
        rule.onNodeWithTag("glide-host").performTouchInput { down(f); moveTo(f + Offset(4f, -40f)); moveTo(f + Offset(6f, -75f)); up() }
        rule.runOnIdle {
            assertEquals(1, commits.size)
            assertTrue("vertical flick must stay a symbol: $commits", commits.single().startsWith("literal:"))
            commits.clear()
        }
        // 按住后才移动：长按菜单，不接管为滑行。
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("glide-host").performTouchInput { down(f) }
        rule.mainClock.advanceTimeBy(450L)
        rule.onNodeWithTag("glide-host").performTouchInput { moveTo(f + Offset(-120f, 0f)); up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.runOnIdle { assertTrue("hold-then-move must not glide: $commits", commits.none { it.startsWith("glide:") }) }
    }

    private fun glideThrough(word: String) {
        val path = word.map(::letter)
        rule.onNodeWithTag("glide-host").performTouchInput {
            down(path.first())
            path.zipWithNext().forEach { (a, b) -> for (step in 1..6) moveTo(a + (b - a) * (step / 6f)) }
            up()
        }
    }

    /** 用户反馈：设置里关掉滑行无效。键盘仍在屏幕上时开关，必须立即生效，不能等输入法重启。 */
    @Test fun glideSwitchAppliesToTheKeyboardAlreadyOnScreen() {
        show()
        glideThrough("hello")
        awaitCommit()
        rule.runOnIdle { assertEquals(listOf("glide:hello"), commits.toList()); commits.clear() }
        // 与设置页 LayoutInputSettingsScreen 同一写法保存。
        KeyboardInputPreferences.read(app).copy(glideTyping = false).save(app)
        rule.waitForIdle()
        glideThrough("hello")
        rule.waitForIdle()
        Thread.sleep(500)
        rule.runOnIdle {
            assertTrue("glide must stop once switched off: $commits", commits.none { it.startsWith("glide:") })
            commits.clear()
        }
        KeyboardInputPreferences.read(app).copy(glideTyping = true).save(app)
        rule.waitForIdle()
        glideThrough("hello")
        awaitCommit()
        rule.runOnIdle { assertEquals(listOf("glide:hello"), commits.toList()) }
    }

    @Test fun t9FlickSwitchAppliesToTheKeyboardAlreadyOnScreen() {
        schema.value = "t9_pinyin"; ascii.value = false
        KeysConfigHelper.setActiveKeyboardSchema("t9_pinyin")
        show()
        val abc = rule.onNodeWithText("ABC", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center -
            rule.onNodeWithTag("glide-host").fetchSemanticsNode().boundsInRoot.topLeft
        val left = Offset(-40f, 0f)
        rule.onNodeWithTag("glide-host").performTouchInput { down(abc); moveTo(abc + left * 0.5f); moveTo(abc + left); up() }
        rule.runOnIdle { assertEquals(listOf("key:A"), commits.toList()); commits.clear() }
        KeyboardInputPreferences.read(app).copy(t9FlickLetters = false).save(app)
        rule.waitForIdle()
        rule.onNodeWithTag("glide-host").performTouchInput { down(abc); moveTo(abc + left * 0.5f); moveTo(abc + left); up() }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue("flick letters must stop once switched off: $commits", "key:A" !in commits) }
    }

    @Test fun keyboardCursorModeDisablesGlide() {
        prefs.edit().putString("cursor_gesture_mode", "KEYBOARD").commit()
        show()
        val path = "hello".map(::letter)
        rule.onNodeWithTag("glide-host").performTouchInput {
            down(path.first())
            path.zipWithNext().forEach { (a, b) -> for (step in 1..6) moveTo(a + (b - a) * (step / 6f)) }
            up()
        }
        rule.waitForIdle()
        Thread.sleep(500)
        rule.runOnIdle { assertTrue(commits.none { it.startsWith("glide:") }) }
    }

    @Test fun t9FlickTypesLettersAndLongPressTypesTheDigit() {
        schema.value = "t9_pinyin"; ascii.value = false
        KeysConfigHelper.setActiveKeyboardSchema("t9_pinyin")
        show()
        val abc = rule.onNodeWithText("ABC", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center -
            rule.onNodeWithTag("glide-host").fetchSemanticsNode().boundsInRoot.topLeft
        // 与原长按选字母同一路径：精确字母经 onKeyPress 进入九键。
        val flicks = listOf(Offset(-40f, 0f) to "key:A", Offset(0f, -40f) to "key:B", Offset(40f, 0f) to "key:C")
        for ((delta, expected) in flicks) {
            rule.runOnIdle { commits.clear() }
            rule.onNodeWithTag("glide-host").performTouchInput { down(abc); moveTo(abc + delta * 0.5f); moveTo(abc + delta); up() }
            rule.runOnIdle { assertEquals(listOf(expected), commits.toList()) }
        }
        rule.runOnIdle { commits.clear() }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("glide-host").performTouchInput { down(abc) }
        rule.mainClock.advanceTimeBy(350L)
        rule.onNodeWithTag("glide-host").performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { assertEquals(listOf("literal:2"), commits.toList()) }
    }
}
