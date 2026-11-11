package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.KeyGestureConfig
import com.kingzcheung.xime.settings.KeyboardRow
import com.kingzcheung.xime.settings.KeyboardRowsLayout
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Xime 配置行的底排 ?123 单键：原来这一格是 !@# / 123 两颗（mode-slot-1、mode-slot-2），
 * 现在一颗键占同样的总宽度；点按发上次页面，长按气泡两项各自直达，按住拖到字母仍然是 Ctrl 拖选。
 */
class ConfiguredModeKeyComposeTest {
    @get:Rule val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val keys = mutableListOf<String>()
    private val commands = mutableListOf<String>()
    private lateinit var layoutField: java.lang.reflect.Field
    private lateinit var gesturesField: java.lang.reflect.Field
    private var previousLayout: Any? = null
    private var previousGestures: Map<String, KeyGestureConfig> = emptyMap()
    private lateinit var enLayoutField: java.lang.reflect.Field
    private lateinit var enGesturesField: java.lang.reflect.Field
    private var previousEnLayout: Any? = null
    private var previousEnGestures: Map<String, KeyGestureConfig> = emptyMap()
    private val periodEnabled = mutableStateOf(false)
    private val ascii = mutableStateOf(false)
    private val fullWidth = mutableStateOf(true)
    private val reverse = mutableStateOf(false)
    private val symbolMode = mutableStateOf(SymbolInputMode.SWIPE_UP)

    @Suppress("UNCHECKED_CAST")
    private val gestures: MutableState<Map<String, KeyGestureConfig>>
        get() = gesturesField.get(KeysConfigHelper) as MutableState<Map<String, KeyGestureConfig>>

    @Suppress("UNCHECKED_CAST")
    private val enGestures: MutableState<Map<String, KeyGestureConfig>>
        get() = enGesturesField.get(KeysConfigHelper) as MutableState<Map<String, KeyGestureConfig>>

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun mount() {
        layoutField = KeysConfigHelper::class.java.getDeclaredField("_zhRowsLayout").apply { isAccessible = true }
        gesturesField = KeysConfigHelper::class.java.getDeclaredField("_keyGestureConfig").apply { isAccessible = true }
        enLayoutField = KeysConfigHelper::class.java.getDeclaredField("_enRowsLayout").apply { isAccessible = true }
        enGesturesField = KeysConfigHelper::class.java.getDeclaredField("_keyGestureConfigEn").apply { isAccessible = true }
        previousLayout = layoutField.get(KeysConfigHelper)
        previousGestures = gestures.value
        previousEnLayout = enLayoutField.get(KeysConfigHelper)
        previousEnGestures = enGestures.value
        val rows = listOf(
            "qwertyuiop".map(Char::toString), "asdfghjkl".map(Char::toString),
            listOf("shift") + "zxcvbnm".map(Char::toString) + "delete",
            listOf("mode_change", "comma", "space", "earth", "enter"),
        )
        rule.runOnIdle {
            layoutField.set(KeysConfigHelper, KeyboardRowsLayout(rows.map { KeyboardRow(it) }))
            enLayoutField.set(KeysConfigHelper, KeyboardRowsLayout(rows.map { KeyboardRow(it) }))
            gestures.value = emptyMap()
            enGestures.value = emptyMap()
        }
        val vm = KeyboardViewModel(app)
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(
                qwertyCommaKey = true, qwertyPeriodKey = periodEnabled.value, splitKeyboardEnabled = false,
                reverseSymbolSwipe = reverse.value, symbolInputMode = symbolMode.value,
            ), LocalKeyboardPunctuation provides KeyboardPunctuation(fullWidth.value, japanese = false,
                chinese = !ascii.value)) {
                MaterialTheme {
                    KeyboardLayout(
                        onKeyPress = { keys.add(it) }, viewModel = vm,
                        callbacks = KeyboardCallbacks(
                            onKeyPress = { key, _ -> keys.add(key) }, onCandidateSelect = {},
                            onGestureAction = { action, value -> if (action == GestureAction.COMMAND) commands.add(value) },
                        ),
                        uiState = KeyboardUiState(currentSchemaId = "rime_ice"), isAsciiMode = ascii.value,
                        modifier = Modifier.requiredSize(360.dp, 240.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @After fun restore() {
        if (!::layoutField.isInitialized || !::gesturesField.isInitialized) return
        rule.runOnIdle {
            layoutField.set(KeysConfigHelper, previousLayout)
            gestures.value = previousGestures
            enLayoutField.set(KeysConfigHelper, previousEnLayout)
            enGestures.value = previousEnGestures
        }
    }

    @Test fun oneKeyReplacesTheTwoModeSlotsAndKeepsTheOldTotalWidth() {
        mount()
        rule.onNodeWithTag("configured-control-row", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(MODE_KEY_TAG, useUnmergedTree = true).assertExists()
        assertTrue("?123 键面", rule.onAllNodesWithText(MODE_KEY_LABEL, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty())
        // 原来的两颗键不复存在：整行只剩一颗模式键。
        assertTrue(rule.onAllNodesWithTag("mode-slot-1", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("mode-slot-2", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        val row = bounds("configured-control-row")
        val mode = bounds(MODE_KEY_TAG)
        val comma = bounds("qwerty-punctuation-key")
        val space = bounds("space-key")
        val earth = bounds("configured-earth-key")
        val enter = bounds("qwerty-enter-key")
        // 宽度还是原来的 bottom.mode * 2f（Standard 1.2 → 2.4 份，逗号 1 份）。
        assertEquals("模式键占两倍多逗号键宽", 2.4f, mode.width / comma.width, 0.05f)
        assertEquals("模式键从左边缘开始", row.left, mode.left, 1f)
        assertEquals("底排仍然铺满", row.width, mode.width + comma.width + space.width + earth.width + enter.width, 2f)
        assertTrue("顺序仍和配置一致", mode.right <= comma.left + 1f && comma.right <= space.left + 1f &&
            space.right <= earth.left + 1f && earth.right <= enter.left + 1f)

        rule.runOnIdle { periodEnabled.value = true }
        rule.waitForIdle()
        val shortened = bounds(MODE_KEY_TAG)
        val newComma = bounds("qwerty-punctuation-key")
        val period = bounds("qwerty-period-key")
        val newSpace = bounds("space-key")
        val newEarth = bounds("configured-earth-key")
        val newEnter = bounds("qwerty-enter-key")
        assertEquals("句号和逗号同宽", newComma.width, period.width, 1f)
        assertEquals("模式键只让出句号的份额", 2.4f, (shortened.width + period.width) / newComma.width, 0.05f)
        assertEquals("缩短后的模式键", 1.4f, shortened.width / newComma.width, 0.05f)
        assertEquals("空格仍保留已启用句号设置的原权重", 2.9f, newSpace.width / newComma.width, 0.05f)
        assertEquals("语言键权重不变", 1f, newEarth.width / newComma.width, 0.05f)
        assertEquals("回车权重不变", 1.7f, newEnter.width / newComma.width, 0.05f)
        assertEquals("句号紧邻逗号", newComma.right, period.left, 1f)
        assertEquals("句号在空格前", period.right, newSpace.left, 1f)
        assertEquals("完整底排仍铺满", row.width,
            shortened.width + newComma.width + period.width + newSpace.width + newEarth.width + newEnter.width, 2f)

        fun expectPeriod(value: String, gesture: Int = 0) {
            rule.runOnIdle { keys.clear() }
            val distance = 80f * app.resources.displayMetrics.density
            rule.onNodeWithTag("qwerty-period-key", useUnmergedTree = true).performTouchInput {
                if (gesture == 0) click() else {
                    down(center)
                    moveTo(center + Offset(0f, distance * gesture), delayMillis = 120)
                    up()
                }
            }
            rule.runOnIdle { assertEquals(listOf(value), keys) }
        }
        expectPeriod("。")
        expectPeriod(".", 1)
        rule.runOnIdle { reverse.value = true }
        expectPeriod(".", -1)
        rule.runOnIdle { symbolMode.value = SymbolInputMode.LONG_PRESS; keys.clear() }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("qwerty-period-key", useUnmergedTree = true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(350)
        rule.onNodeWithTag("qwerty-period-key", useUnmergedTree = true).performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { assertEquals("长按副键只提交一次", listOf("."), keys) }
        rule.runOnIdle { symbolMode.value = SymbolInputMode.SWIPE_UP; fullWidth.value = false }
        expectPeriod(".")
        expectPeriod("。", -1)
        rule.runOnIdle { ascii.value = true; reverse.value = false }
        expectPeriod(".")
        rule.onNodeWithTag("qwerty-period-key", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("。")).not())
        rule.runOnIdle { keys.clear() }
        rule.onNodeWithTag("qwerty-period-key", useUnmergedTree = true).performTouchInput {
            down(center); moveTo(center + Offset(0f, 80f * app.resources.displayMetrics.density), delayMillis = 120); up()
        }
        rule.runOnIdle { assertEquals("无副键时保留逗号同样的点按回退", listOf("."), keys); keys.clear() }
        rule.onNodeWithTag("qwerty-punctuation-key", useUnmergedTree = true).performTouchInput {
            down(center); moveTo(center + Offset(0f, 80f * app.resources.displayMetrics.density), delayMillis = 120); up()
        }
        rule.runOnIdle { assertEquals(listOf(","), keys) }
        rule.runOnIdle { periodEnabled.value = false; ascii.value = false; fullWidth.value = true }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("qwerty-period-key", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals("显式关闭句号恢复旧模式键宽度", mode.width, bounds(MODE_KEY_TAG).width, 1f)
    }

    @Test fun tapSendsLastPageActionOnly() {
        mount()
        for (enabled in listOf(false, true)) {
            rule.runOnIdle { periodEnabled.value = enabled; keys.clear(); commands.clear() }
            rule.waitForIdle()
            rule.onNodeWithTag(MODE_KEY_TAG, useUnmergedTree = true).performTouchInput { click() }
            rule.runOnIdle {
                assertEquals(listOf("mode_change_last"), keys)
                assertTrue(commands.isEmpty())
            }
        }
    }

    @Test fun longPressOffersBothPagesDirectly() {
        mount()
        for (enabled in listOf(false, true)) {
        rule.runOnIdle { periodEnabled.value = enabled; keys.clear(); commands.clear() }
        rule.waitForIdle()
        // 长按不拖：第一项「数字」直达。
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(MODE_KEY_TAG, useUnmergedTree = true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag(MODE_KEY_TAG, useUnmergedTree = true).performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("mode_change_number"), keys)
            keys.clear()
        }
        // 长按后往右拖过半项：第二项「常用符号」直达。
        val width = bounds(MODE_KEY_TAG).width
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(MODE_KEY_TAG, useUnmergedTree = true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag(MODE_KEY_TAG, useUnmergedTree = true).performTouchInput {
            moveBy(Offset(width * 0.5f, 0f)); up()
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("mode_change_common_symbol"), keys) }
        }
    }

    @Test fun holdingAndDraggingToALetterIsStillCtrl() {
        mount()
        for (enabled in listOf(false, true)) {
        rule.runOnIdle { periodEnabled.value = enabled; keys.clear(); commands.clear() }
        rule.waitForIdle()
        val mode = bounds(MODE_KEY_TAG)
        val c = bounds("qwerty-key:c")
        val target = c.center - mode.topLeft
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(MODE_KEY_TAG, useUnmergedTree = true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(16)
        // 逐帧送移动，和真手指一样；全程不到长按菜单的 400 毫秒，仍然是按住当 Ctrl。
        for (step in 1..8) {
            rule.onNodeWithTag(MODE_KEY_TAG, useUnmergedTree = true).performTouchInput {
                moveTo(center + (target - center) * (step / 8f))
            }
            rule.mainClock.advanceTimeBy(16)
        }
        rule.onNodeWithTag(MODE_KEY_TAG, useUnmergedTree = true).performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("ctrl:c"), commands)
            assertTrue("拖到字母不算点按，不进面板：$keys", keys.isEmpty())
        }
        }
    }
}
