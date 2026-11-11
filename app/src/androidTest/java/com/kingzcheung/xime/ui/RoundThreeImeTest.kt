package com.kingzcheung.xime.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputModes
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.settings.LanguageSwitchPreferences
import com.kingzcheung.xime.settings.LanguageSwitchMode
import com.kingzcheung.xime.settings.LetterLayouts
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.keyboard.QuickActionLayout
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.service.XimeInputMethodService
import com.kingzcheung.xime.service.getActiveBottomInsetPx
import com.kingzcheung.xime.service.tryGetVisibleNavBarHeightDp
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 在真实 IME 窗口中覆盖服务层高度/底部留白；只用于隔离模拟器。 */
class RoundThreeImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun roundThreeHandwritingKanaCursorAndNavigationBounds(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val keys = listOf(SettingsPreferences.KEY_SHOW_CANDIDATE_CANCEL_BUTTON, "floating_mode", "floating_offset_x", "floating_offset_y",
            "keyboard_height_dp", "keyboard_bottom_padding_dp", "keyboard_opacity",
            "current_schema", "current_schema_dual", "mode_change_target", "input_mode_order", "toolbar_buttons",
            "menu_buttons", "input_language_order", "setup_completed", LanguagePreferences.KEY, SettingsPreferences.KEY_PUNCTUATION_FULL_WIDTH,
            LanguageSwitchPreferences.MODE_KEY, LanguageSwitchPreferences.LANGUAGE_KEY, "hardware_keyboard_ctrl_space",
            "space_hold_action", "cursor_gesture_mode", "cursor_hold_seconds",
            "flick_direction_disc_v2", "fourteen_letter_swipe_dp",
            "floating_height_dp", "floating_width_dp", "floating_rotation", "fixed_width_dp", "fixed_offset_x",
            "quick_actions_full_shape_restored", "quick_actions_caption_inserted") +
            (QuickActionLayout.MENU_TOGGLES_1031 + ToolbarButton.LAYOUT_GESTURES).map { "quick_actions_${it.id}_inserted" } +
            listOf("zh", "en", "ja").flatMap { language ->
                listOf("selected_input_profile_$language", "last_input_mode_$language", "letter_layout_$language")
            }
        val saved = keys.associateWith { prefs.all[it] }
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val enabledBefore = shell("ime list -s").lineSequence().any { it == ime }
        val engine = RimeEngine.getInstance()
        var previousSchema = ""
        var previousAscii = false
        var previousSavedAscii = false
        var previousFullShape = false
        var previousSavedFullShape = false
        lateinit var editor: EditText
        try {
            SettingsPreferences.setShowCandidateCancelButton(context, true)
            prefs.edit().putStringSet(LanguagePreferences.KEY, setOf("zh", "en", "ja"))
                .putBoolean("setup_completed", true)
                .putBoolean(SettingsPreferences.KEY_PUNCTUATION_FULL_WIDTH, false)
                .putString(LanguageSwitchPreferences.MODE_KEY, LanguageSwitchMode.CURRENT_ENGLISH.id)
                .putBoolean("hardware_keyboard_ctrl_space", true)
                .putString("space_hold_action", "CURSOR").putString("cursor_gesture_mode", "SPACE")
                .putFloat("cursor_hold_seconds", 0.2f).putBoolean("flick_direction_disc_v2", true)
                .putFloat("fourteen_letter_swipe_dp", 24f)
                .putInt("floating_height_dp", 300).putInt("floating_width_dp", 280)
                .putFloat("floating_rotation", 0f).putInt("fixed_width_dp", 0).putInt("fixed_offset_x", 0)
                .putBoolean("quick_actions_full_shape_restored", true)
                .putBoolean("quick_actions_caption_inserted", true).also { edit ->
                    (QuickActionLayout.MENU_TOGGLES_1031 + ToolbarButton.LAYOUT_GESTURES).forEach {
                        edit.putBoolean("quick_actions_${it.id}_inserted", true)
                    }
                }.commit()
            SettingsPreferences.setQuickActions(context, QuickActionLayout(
                ToolbarButton.DEFAULT_VISIBLE.map { it.id }, QuickActionLayout.defaultMenu(ToolbarButton.DEFAULT_VISIBLE.map { it.id })))
            InputModes.saveLanguageOrder(context, listOf("zh", "en", "ja"))
            assertTrue(RimeConfigHelper.prepareEngine(context))
            assertTrue(engine.ensureSession())
            previousSchema = engine.getCurrentSchema()
            previousAscii = engine.isAsciiMode()
            previousSavedAscii = engine.getUserConfigBool("var/option/ascii_mode")
            previousFullShape = engine.getOption("full_shape")
            previousSavedFullShape = engine.getUserConfigBool("var/option/full_shape")
            engine.setOption("full_shape", false)
            engine.setUserConfigBool("var/option/full_shape", false)
            SettingsPreferences.setKeyboardHeightDp(context, 300)
            SettingsPreferences.setKeyboardBottomPaddingDp(context, 80)
            SettingsPreferences.setKeyboardOpacity(context, 1f)
            SettingsPreferences.setFloatingMode(context, true)
            SettingsPreferences.setFloatingOffsetX(context, 0)
            SettingsPreferences.setFloatingOffsetY(context, 120)
            shell("ime enable $ime")
            shell("ime set $ime")
            rule.setContent {
                AndroidView(factory = { EditText(it).also { view -> editor = view; view.hint = "真实输入法回归输入框" } },
                    modifier = Modifier.fillMaxWidth().height(80.dp))
            }
            val density = context.resources.displayMetrics.density
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            lateinit var shownIme: XimeInputMethodService
            fun showSchema(schema: String) {
                val deadline = SystemClock.uptimeMillis() + 30_000
                fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
                val service = rule.awaitRealImeInputReady(editor, imm, engine, remaining())
                shownIme = service
                // Use the same serialized, field-aware transaction as a real scheme choice.
                // Direct native option writes followed by restartInput can restore stale ASCII
                // memory (or skip the user-config write while the engine is busy).
                rule.runOnUiThread { service.schemaController.switchSchema(schema) }
                var expandedHardwareToolbar = false
                rule.waitUntil(remaining()) {
                    val expand = rule.onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true)
                    if (!expandedHardwareToolbar && expand.fetchSemanticsNodes().isNotEmpty()) {
                        expandedHardwareToolbar = true
                        expand.onFirst().performClick()
                    }
                    service.inputReadiness.ticket() != null && service.uiState.value.currentSchemaId == schema &&
                        !service.uiState.value.isAsciiMode && engine.getCurrentSchema() == schema && !engine.isAsciiMode() &&
                        rule.onAllNodesWithTag("floating-drag-bar").fetchSemanticsNodes().isNotEmpty()
                }
            }
            fun chooseMode(schemaId: String) {
                rule.chooseModeThroughLanguageAndPanel(schemaId)
            }
            fun switchHandwritingToEnglishKeyboard() {
                val deadline = SystemClock.uptimeMillis() + 10_000
                fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
                rule.onNodeWithTag("handwriting-key:ime_switch").performTouchInput { down(center); up() }
                rule.waitUntil(remaining()) {
                    val hint = rule.onAllNodesWithTag("handwriting-language").fetchSemanticsNodes().singleOrNull()
                        ?.config?.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
                    engine.isAsciiMode() && hint.startsWith("英文手写")
                }
                rule.onNodeWithTag("handwriting-canvas").assertIsDisplayed()
                rule.onNodeWithTag("handwriting-language").assertTextContains("英文手写", substring = true)
                rule.onNodeWithContentDescription("返回").performClick()
                rule.waitUntil(remaining()) {
                    engine.isAsciiMode() && rule.onAllNodesWithText("英文").fetchSemanticsNodes().isNotEmpty() &&
                        rule.onAllNodesWithTag("handwriting-canvas").fetchSemanticsNodes().isEmpty()
                }
            }
            showSchema("t9_pinyin")
            rule.waitUntil(30_000) { rule.onAllNodesWithText("!@#").fetchSemanticsNodes().isNotEmpty() }
            val symbol = rule.onNodeWithText("!@#").fetchSemanticsNode()
            val bar = rule.onNodeWithTag("floating-drag-bar").fetchSemanticsNode()
            assertTrue("九键末行与拖条之间不能残留80dp留白",
                bar.positionOnScreen.y - (symbol.positionOnScreen.y + symbol.size.height) < 55f * density)
            screenshot("round3-floating-t9")
            rule.onNodeWithTag("floating-drag-bar").performTouchInput {
                down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 100f * density)); up()
            }
            rule.waitForIdle()
            val bottomBar = rule.onNodeWithTag("floating-drag-bar").fetchSemanticsNode()
            val physicalBottom = instrumentation.uiAutomation.takeScreenshot().height
            val navInset = maxOf(kotlin.math.ceil(getActiveBottomInsetPx(shownIme.window.window).toDouble()).toInt(),
                kotlin.math.ceil(tryGetVisibleNavBarHeightDp(shownIme, shownIme.window.window) * density).toInt())
            assertTrue("浮动拖条必须在系统导航栏上方",
                bottomBar.positionOnScreen.y + bottomBar.size.height <= physicalBottom - navInset + 1)
            screenshot("round3-floating-bottom")
            rule.onNodeWithTag("floating-drag-bar").performTouchInput {
                down(center); moveBy(androidx.compose.ui.geometry.Offset(24f * density, 0f))
            }
            rule.waitUntil(3000) {
                rule.onAllNodesWithTag("floating-dock-preview").fetchSemanticsNodes().singleOrNull()
                    ?.config?.getOrNull(SemanticsProperties.StateDescription) == "ready"
            }
            val dockPreview = rule.onNodeWithTag("floating-dock-preview").fetchSemanticsNode()
            assertEquals("恢复框底边必须贴导航栏上沿", (physicalBottom - navInset).toFloat(),
                dockPreview.positionOnScreen.y + dockPreview.size.height, 2f)
            screenshot("audit-bottom-dock-preview")
            rule.onNodeWithTag("floating-drag-bar").performTouchInput { cancel() }


            showSchema("rime_ice")
            rule.waitUntil(30_000) { rule.onAllNodesWithText("Q").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("q").fetchSemanticsNodes().isNotEmpty() }
            val space = rule.onNodeWithContentDescription("空格").fetchSemanticsNode()
            val qwertyBar = rule.onNodeWithTag("floating-drag-bar").fetchSemanticsNode()
            assertTrue("26键末行与拖条之间不能残留底部留白",
                qwertyBar.positionOnScreen.y - (space.positionOnScreen.y + space.size.height) < 55f * density)
            screenshot("floating-qwerty")

            SettingsPreferences.setKeyboardBottomPaddingDp(context, 0)
            SettingsPreferences.setFloatingMode(context, false)
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("floating-drag-bar").fetchSemanticsNodes().isEmpty() }
            val dockedSpace = rule.onNodeWithContentDescription("空格").fetchSemanticsNode()
            val screenBottom = instrumentation.uiAutomation.takeScreenshot().height
            assertTrue("恢复普通键盘后只保留末行半高与系统导航栏",
                screenBottom - dockedSpace.positionOnScreen.y - dockedSpace.size.height < 85f * density)
            screenshot("round3-restored-qwerty")
            rule.mainClock.autoAdvance = false
            rule.onNodeWithContentDescription("空格").performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(350)
            rule.onNodeWithText("光标调整").assertIsDisplayed()
            screenshot("round3-cursor-control")
            rule.onNodeWithContentDescription("空格").performTouchInput { up() }
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("cursor-control-overlay").assertDoesNotExist()
            rule.mainClock.autoAdvance = true

            assertTrue(engine.switchSchema("japanese_kana"))
            SettingsPreferences.setCurrentSchema(context, "japanese_kana")
            rule.runOnUiThread { imm.restartInput(editor); imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            rule.waitUntil(30_000) { rule.onAllNodesWithTag("kana-key:a").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("语言切换").assertIsDisplayed()
            rule.onNodeWithText("123").assertIsDisplayed()
            val kana = rule.onNodeWithTag("kana-key:a").fetchSemanticsNode()
            val ka = rule.onNodeWithTag("kana-key:ka").fetchSemanticsNode()
            val ta = rule.onNodeWithTag("kana-key:ta").fetchSemanticsNode()
            assertEquals(kana.boundsInRoot.top, ka.boundsInRoot.top, 1f)
            assertTrue(kana.boundsInRoot.right <= ka.boundsInRoot.left + 1f)
            assertTrue(kana.boundsInRoot.bottom <= ta.boundsInRoot.top + 1f)
            screenshot("round3-japanese-kana")
            val flickKey = rule.onNodeWithTag("kana-key:ta")
            flickKey.performTouchInput { down(center) }
            val preview = rule.onNodeWithTag("kana-flick-preview")
            preview.assertIsDisplayed()
            val popupNode = preview.fetchSemanticsNode()
            val pressedKey = flickKey.fetchSemanticsNode()
            assertTrue("气泡必须悬浮在当前按键上方", popupNode.positionOnScreen.y + popupNode.size.height <= pressedKey.positionOnScreen.y)
            screenshot("kana-preview-center")
            for ((name, delta) in listOf("left" to androidx.compose.ui.geometry.Offset(-38f, 0f),
                "up" to androidx.compose.ui.geometry.Offset(0f, -38f), "right" to androidx.compose.ui.geometry.Offset(38f, 0f),
                "down" to androidx.compose.ui.geometry.Offset(0f, 38f))) {
                flickKey.performTouchInput { moveTo(center + delta * density) }
                rule.onNodeWithTag("kana-direction:${name.uppercase()}", useUnmergedTree = true).assertIsDisplayed()
                screenshot("kana-preview-$name")
            }
            flickKey.performTouchInput { moveTo(center); cancel() }
            preview.assertDoesNotExist()
            assertTrue("取消手势不可输入", engine.getInput().isEmpty())
            val punctuationKey = rule.onNodeWithTag("kana-punctuation")
            punctuationKey.performTouchInput { down(center) }
            preview.assertIsDisplayed()
            val punctuationPopup = preview.fetchSemanticsNode()
            assertTrue("左侧标点气泡不能越出屏幕", punctuationPopup.positionOnScreen.x >= 0)
            screenshot("kana-preview-punctuation")
            punctuationKey.performTouchInput { cancel() }


            assertTrue("本回归需先安装市场手写模型", com.kingzcheung.xime.handwriting.HandwritingEngine.hasModel(context))
            val toolbarTop = rule.onNodeWithContentDescription("手写").fetchSemanticsNode().positionOnScreen.y
            val stableToolbarLabels = listOf("输入方式", "表情", "编辑", "剪贴板", "手写", "语音")
            val beforeHandwritingToolbar = stableToolbarLabels.map {
                rule.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot
            }
            rule.onNodeWithContentDescription("手写").performClick()
            rule.onNodeWithContentDescription("手写").assertIsSelected()
            assertEquals("手写不能改变键盘高度", toolbarTop,
                rule.onNodeWithContentDescription("手写").fetchSemanticsNode().positionOnScreen.y, 1f)
            assertEquals("工具栏临时手写不改变已选方案", "japanese_kana", SettingsPreferences.getCurrentSchema(context))
            rule.onNodeWithTag("kana-key:a").assertDoesNotExist()
            assertEquals("进入手写不能挤动工具栏图标", beforeHandwritingToolbar, stableToolbarLabels.map {
                rule.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot
            })
            screenshot("round3-handwriting-toggle")
            rule.onAllNodesWithContentDescription("返回").assertCountEquals(1)
            fun touchFunction(action: String) {
                rule.onNodeWithTag("handwriting-key:$action").performTouchInput {
                    down(center)
                    moveTo(center + androidx.compose.ui.geometry.Offset(13f, 0f))
                    moveTo(center + androidx.compose.ui.geometry.Offset(13f, 5f))
                    up()
                }
            }
            touchFunction("space")
            var observedSpace = ""
            try {
                rule.waitUntil(5000) {
                    rule.runOnUiThread { observedSpace = editor.text.toString() }
                    observedSpace == " "
                }
            } catch (error: Throwable) {
                throw AssertionError("Handwriting space: codepoints=${observedSpace.map { "U+%04X".format(it.code) }}; " +
                    "fullShape=${engine.getOption("full_shape")}", error)
            }
            rule.runOnUiThread { assertEquals("空白手写页的空格正常输入", " ", editor.text.toString()) }
            touchFunction("delete")
            rule.waitUntil(5000) {
                var value = ""
                rule.runOnUiThread { value = editor.text.toString() }
                value.isEmpty()
            }
            rule.runOnUiThread { assertEquals("没有笔迹时删除宿主文字", "", editor.text.toString()) }
            touchFunction("number")
            rule.onNodeWithTag("handwriting-canvas").assertDoesNotExist()
            rule.onNodeWithText("1").assertIsDisplayed()
            rule.onNodeWithTag("kana-number").performTouchInput { down(center); up() }
            rule.onNodeWithTag("handwriting-canvas").assertIsDisplayed()
            assertEquals("数字返回不应切换手写语言方案", "japanese_kana", SettingsPreferences.getCurrentSchema(context))
            touchFunction("symbol")
            rule.onNodeWithTag("handwriting-canvas").assertDoesNotExist()
            rule.onNodeWithTag("keyboard-overlay").assertIsDisplayed()
            rule.onNodeWithTag("symbol-category:recentSymbols").assertIsDisplayed()
            screenshot("toolbar-symbol-categories")
            // 符号返回是图标子节点；未合并树才能触摸图标坐标，避免点到整页语义容器中心。
            rule.onNodeWithContentDescription("返回键盘", useUnmergedTree = true).performTouchInput { down(center); up() }
            rule.onNodeWithTag("keyboard-overlay").assertDoesNotExist()
            rule.onNodeWithTag("handwriting-canvas").assertIsDisplayed()
            assertEquals("符号返回不改变临时手写的方案", "japanese_kana", SettingsPreferences.getCurrentSchema(context))
            val writing = rule.onNodeWithTag("handwriting-canvas")
            fun writeLine(y: Float) {
                writing.performTouchInput {
                    down(androidx.compose.ui.geometry.Offset(60f, y))
                    moveTo(androidx.compose.ui.geometry.Offset(90f, y))
                    moveTo(androidx.compose.ui.geometry.Offset(240f, y))
                    up()
                }
            }
            rule.runOnUiThread { editor.setText("甲") }
            rule.mainClock.autoAdvance = false
            writeLine(100f)
            touchFunction("delete")
            rule.mainClock.advanceTimeBy(1500)
            rule.runOnUiThread { assertEquals("尚未出候选时删除仅清笔迹", "甲", editor.text.toString()); editor.setText("") }
            rule.mainClock.autoAdvance = true
            fun hostText(): String {
                var text = ""
                rule.runOnUiThread { text = editor.text.toString() }
                return text
            }
            fun awaitHandwritingCandidate(deadline: Long) {
                rule.waitUntil((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)) {
                    rule.onAllNodesWithTag("bar-association:0").fetchSemanticsNodes().isNotEmpty() &&
                        rule.onAllNodesWithContentDescription("取消输入").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("bar-association:0").assertIsDisplayed()
                rule.onNodeWithTag("handwriting-canvas").assertIsDisplayed()
            }
            var handwritingDeadline = SystemClock.uptimeMillis() + 20_000
            writeLine(100f)
            awaitHandwritingCandidate(handwritingDeadline)
            rule.onAllNodesWithContentDescription("取消输入").assertCountEquals(1)
            rule.onNodeWithContentDescription("返回").assertDoesNotExist()
            assertEquals("未确认的真实识别候选不改宿主", "", hostText())
            touchFunction("space")
            rule.waitUntil((handwritingDeadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)) { hostText().isNotEmpty() }
            val firstWritten = hostText()
            assertTrue("原模型首候选显式确认后上屏", firstWritten.isNotEmpty())

            handwritingDeadline = SystemClock.uptimeMillis() + 20_000
            writeLine(180f)
            awaitHandwritingCandidate(handwritingDeadline)
            assertEquals("新笔迹确认前保留已写文字", firstWritten, hostText())
            screenshot("handwriting-two-characters")
            rule.onNodeWithContentDescription("取消输入").performClick()
            rule.waitUntil((handwritingDeadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)) {
                rule.onAllNodesWithTag("bar-association:0").fetchSemanticsNodes().isEmpty()
            }
            assertEquals("撤销仅清未确认候选，不删已确认文字", firstWritten, hostText())
            rule.onAllNodesWithContentDescription("返回").assertCountEquals(1)
            // The real model's first candidate varies; explicitly confirm it before checking preservation.
            handwritingDeadline = SystemClock.uptimeMillis() + 20_000
            writeLine(100f)
            awaitHandwritingCandidate(handwritingDeadline)
            assertEquals(firstWritten, hostText())
            touchFunction("space")
            rule.waitUntil((handwritingDeadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)) {
                val text = hostText()
                text.startsWith(firstWritten) && text.length > firstWritten.length
            }
            val beforeReturn = hostText()
            assertTrue("显式确认追加而不替换旧字", beforeReturn.startsWith(firstWritten) && beforeReturn.length > firstWritten.length)
            rule.onNodeWithContentDescription("手写").performClick()
            rule.onNodeWithTag("kana-key:a").assertIsDisplayed()
            rule.onNodeWithContentDescription("手写").performClick()
            rule.onNodeWithContentDescription("返回").assertIsDisplayed()
            rule.onNodeWithContentDescription("取消输入").assertDoesNotExist()
            rule.onNodeWithTag("bar-association:0").assertDoesNotExist()
            rule.runOnUiThread { assertEquals("退出手写保留已写文字", beforeReturn, editor.text.toString()); editor.setText("") }

            rule.onNodeWithContentDescription("手写").performClick()
            rule.onNodeWithTag("kana-key:a").assertIsDisplayed()
            rule.onNodeWithContentDescription("手写").assertIsNotSelected()
            rule.onNodeWithContentDescription("手写").performClick()
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithTag("kana-key:a").assertIsDisplayed()

            rule.onNodeWithContentDescription("手写").performClick()
            switchHandwritingToEnglishKeyboard()
            rule.onNodeWithText("英文").assertIsDisplayed()
            chooseMode("t9_pinyin")
            rule.waitUntil(10_000) {
                engine.getCurrentSchema() == "t9_pinyin" && !engine.isAsciiMode() &&
                    rule.onAllNodesWithText("ABC").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("ABC").assertIsDisplayed()
            rule.onNodeWithTag("shift-key").assertDoesNotExist()
            screenshot("round3-handwriting-english-t9")
            // 从九键再进入手写：英文与九键共用同一个 Rime schema 时也必须正确退出 ASCII。
            rule.onNodeWithContentDescription("手写").performClick()
            switchHandwritingToEnglishKeyboard()
            chooseMode("t9_pinyin")
            rule.waitUntil(10_000) {
                engine.getCurrentSchema() == "t9_pinyin" && !engine.isAsciiMode() &&
                    rule.onAllNodesWithText("ABC").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("ABC").assertIsDisplayed()
            screenshot("round3-same-schema-return-t9")
            assertTrue(engine.switchSchema("japanese_kana"))
            engine.setOption("ascii_mode", false)
                engine.setUserConfigBool("var/option/ascii_mode", false)
            SettingsPreferences.setCurrentSchema(context, "japanese_kana")
            rule.runOnUiThread { imm.restartInput(editor); imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("kana-key:a").fetchSemanticsNodes().isNotEmpty() }

            chooseMode(com.kingzcheung.xime.settings.InputModes.ENGLISH)
            rule.waitUntil(10_000) { engine.isAsciiMode() && rule.onAllNodesWithText("英文").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("kana-key:a").assertDoesNotExist()
            rule.onNodeWithText("英文").assertIsDisplayed()
            screenshot("round3-english-mode")
            // 语言选择发生在数字面板内，随后再次进入/退出数字面板应返回新选的语言。
            chooseMode("t9_pinyin")
            rule.waitUntil(10_000) { !engine.isAsciiMode() && rule.onAllNodesWithText("分词").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("123").performTouchInput { down(center); up() }
            val numberLanguageDeadline = SystemClock.uptimeMillis() + 10_000
            shell("input keyboard keycombination KEYCODE_CTRL_LEFT KEYCODE_SPACE")
            rule.waitUntil((numberLanguageDeadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)) { engine.isAsciiMode() }
            rule.onNodeWithTag("number-zero").assertIsDisplayed()
            rule.onNodeWithTag("mode-slot-2", useUnmergedTree = true).performTouchInput { down(center); up() }
            rule.waitUntil((numberLanguageDeadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)) {
                engine.isAsciiMode() && rule.onAllNodesWithText("英文").fetchSemanticsNodes().isNotEmpty()
            }
            SettingsPreferences.setModeChangeTarget(context, com.kingzcheung.xime.keyboard.ModeChangeTarget.NUMBER)
            rule.onNodeWithTag("configured-mode-key", useUnmergedTree = true).performTouchInput { down(center); up() }
            rule.onNodeWithText("0").assertIsDisplayed()
            rule.onNodeWithTag("mode-slot-2", useUnmergedTree = true).performTouchInput { down(center); up() }
            rule.onNodeWithText("英文").assertIsDisplayed()
            rule.onNodeWithText("分词").assertDoesNotExist()
            assertTrue("面板返回不能改变刚选定的英文模式", engine.isAsciiMode())


            rule.runOnUiThread { editor.setText("") }
            chooseMode("japanese")
            rule.waitUntil(10_000) { !engine.isAsciiMode() && rule.onAllNodesWithText("日语26键").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("shift-key").performTouchInput { down(center); up() }
            for (letter in "KATAKANA") rule.onNodeWithText(letter.toString()).performTouchInput { down(center); up() }
            rule.waitUntil(10_000) { engine.getComposition().preedit.replace(" ", "") == "カタカナ" }
            rule.onNodeWithText("K").assertIsDisplayed()
            screenshot("round3-katakana")
            rule.onNodeWithContentDescription("取消输入").performClick()
            rule.waitUntil(10_000) { engine.getInput().isEmpty() }
            rule.runOnUiThread { assertEquals("取消候选不能把编码提交到输入框", "", editor.text.toString()) }
            engine.clearComposition()
            rule.runOnUiThread { imm.restartInput(editor); imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            rule.waitForIdle()
            rule.onNodeWithContentDescription("Lime Logo").performClick()
            rule.onNodeWithContentDescription("键盘调节").performClick()
            val reset = rule.onNodeWithContentDescription("重置").fetchSemanticsNode().boundsInRoot
            val floating = rule.onNodeWithContentDescription("悬浮键盘").fetchSemanticsNode().boundsInRoot
            val confirm = rule.onNodeWithContentDescription("确认").fetchSemanticsNode().boundsInRoot
            val slider = rule.onNodeWithTag("keyboard-opacity-slider").fetchSemanticsNode().boundsInRoot
            assertTrue(reset.right < floating.left && floating.right < confirm.left)
            assertTrue(slider.bottom < confirm.top)
            screenshot("round3-resize")
            rule.onNodeWithContentDescription("确认").performClick()
            // 进入调节时菜单已关闭；确认后直接使用恢复的工具栏。
            rule.onNodeWithContentDescription("编辑").assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("向上").fetchSemanticsNodes().isNotEmpty() }
            val grid = rule.onNodeWithTag("editor-grid").fetchSemanticsNode().boundsInRoot
            val pad = rule.onNodeWithTag("editor-pad-controls").fetchSemanticsNode().boundsInRoot
            val upLabel = rule.onNodeWithTag("editor-label-向上", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val home = rule.onNodeWithContentDescription("段首").fetchSemanticsNode().boundsInRoot
            assertEquals("段首保持原首行触控格中心", grid.top + grid.height / 6f, home.center.y, 1f)
            assertEquals("向上图标位于实际圆盘上扇区", pad.center.y - pad.height / 3f, upLabel.center.y, 1f)
            rule.onAllNodesWithContentDescription("返回").assertCountEquals(1)
            rule.onAllNodesWithContentDescription("返回键盘").assertCountEquals(0)
            val topBack = rule.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot
            val toolbarRow = rule.onNodeWithTag("toolbar-order-row").fetchSemanticsNode().boundsInRoot
            assertEquals(toolbarRow.center.y, topBack.center.y, 1f)
            Thread.sleep(2200) // 等高度提示消失后再保存布局证据。
            screenshot("round3-edit")
            rule.runOnUiThread {
                // 用真实连接输入，避免 TextView.setText 重建 Editable 后重启IME，清掉编辑面板。
                val input = editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
                input.setSelection(0, editor.text.length); input.commitText("abc", 1)
            }
            repeat(10) { rule.onNodeWithTag("editor-label-向右", useUnmergedTree = true).performTouchInput { click() } }
            rule.runOnUiThread { assertTrue(editor.hasFocus()); assertEquals(3, editor.selectionStart) }
            rule.onNodeWithTag("keyboard-overlay").assertIsDisplayed()
            repeat(10) { rule.onNodeWithTag("editor-label-向左", useUnmergedTree = true).performTouchInput { click() } }
            rule.runOnUiThread { assertTrue(editor.hasFocus()); assertEquals(0, editor.selectionStart); editor.text.clear() }
            rule.onNodeWithTag("keyboard-overlay").assertIsDisplayed()
            rule.onNodeWithContentDescription("编辑").performClick()
            rule.onNodeWithTag("keyboard-overlay").assertDoesNotExist()
            SettingsPreferences.setKeyboardOpacity(context, 0.5f)
            rule.onNodeWithContentDescription("表情").performTouchInput { down(center); up() }
            rule.waitForIdle()
            Thread.sleep(500) // 等独立IME窗口把透明度和面板的新帧送到屏幕。
            rule.onNodeWithContentDescription("表情").assertIsSelected()
            val toolbarBounds = rule.onNodeWithTag("toolbar-order-row").fetchSemanticsNode().boundsInRoot
            val emojiBounds = rule.onNodeWithTag("keyboard-overlay").fetchSemanticsNode().boundsInRoot
            assertTrue("真实IME透明表情面板不得覆盖工具栏", emojiBounds.top >= toolbarBounds.bottom)
            screenshot("audit-transparent-emoji")
            rule.onNodeWithContentDescription("表情").performTouchInput { down(center); up() }
            rule.onNodeWithTag("keyboard-overlay").assertDoesNotExist()

            // The current product orders languages in Settings, not backend combinations in the IME panel.
            InputModes.saveLanguageOrder(context, listOf("zh", "en", "ja"))
            fun openLanguageSettings() {
                rule.runOnUiThread {
                    context.startActivity(com.kingzcheung.xime.service.settingsActivityIntent(context, "languages"))
                }
                rule.waitUntil(10_000) { rule.onAllNodesWithTag("language-settings").fetchSemanticsNodes().isNotEmpty() }
            }
            fun closeLanguageSettings() {
                // The real settings graph keeps Main underneath explicit language shortcuts.
                shell("input keyevent KEYCODE_BACK")
                rule.waitUntil(5000) { rule.onAllNodesWithTag("language-settings").fetchSemanticsNodes().isEmpty() }
                shell("input keyevent KEYCODE_BACK")
                rule.waitUntil(5000) { editor.hasWindowFocus() }
            }
            openLanguageSettings()
            rule.onAllNodesWithContentDescription("返回").assertCountEquals(1)
            val englishCard = rule.onNodeWithTag("language-drag:en").performScrollTo()
            val englishCenter = englishCard.fetchSemanticsNode().boundsInRoot.center
            val chineseCenter = rule.onNodeWithTag("language-drag:zh").performScrollTo()
                .fetchSemanticsNode().boundsInRoot.center
            rule.mainClock.autoAdvance = false
            try {
                englishCard.performTouchInput { down(androidx.compose.ui.geometry.Offset(width * .25f, centerY)) }
                rule.mainClock.advanceTimeBy(700)
                englishCard.performTouchInput {
                    moveBy(androidx.compose.ui.geometry.Offset(0f, chineseCenter.y - englishCenter.y)); up()
                }
            } finally { rule.mainClock.autoAdvance = true }
            rule.waitForIdle()
            val savedOrder = prefs.getString("input_language_order", "").orEmpty().lines()
            assertEquals(1, savedOrder.count { it == "en" })
            assertTrue("英文可调整位置而不被移除", savedOrder.indexOf("en") < savedOrder.indexOf("zh"))
            screenshot("toolbar-mode-order")
            closeLanguageSettings()
            openLanguageSettings()
            assertTrue("语言排序关闭重开仍保留", rule.onNodeWithTag("language-drag:en").fetchSemanticsNode().boundsInRoot.top <
                rule.onNodeWithTag("language-drag:zh").fetchSemanticsNode().boundsInRoot.top)
            screenshot("toolbar-schema-single-back")
            closeLanguageSettings()
            rule.awaitRealImeInputReady(editor, imm, engine, 10_000)
            val expandKeyboard = rule.onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true)
            if (expandKeyboard.fetchSemanticsNodes().isNotEmpty()) expandKeyboard.onFirst().performClick()
            val languageKey = rule.onAllNodesWithTag("language-key-control", useUnmergedTree = true).onLast()
            languageKey.performTouchInput { down(center) }
            rule.waitUntil(5000) { rule.onAllNodesWithContentDescription("选择英文").fetchSemanticsNodes().isNotEmpty() }
            assertTrue("真实键盘语言菜单遵循已保存排序",
                rule.onNodeWithContentDescription("选择英文").fetchSemanticsNode().boundsInRoot.top <
                    rule.onNodeWithContentDescription("选择中文").fetchSemanticsNode().boundsInRoot.top)
            languageKey.performTouchInput { cancel() }
            if (rule.onAllNodesWithContentDescription("选择英文").fetchSemanticsNodes().isNotEmpty())
                shell("input keyevent KEYCODE_BACK")
            rule.waitUntil(5000) { rule.onAllNodesWithContentDescription("选择英文").fetchSemanticsNodes().isEmpty() }
            // 使用定制工具栏中的调节入口，验证服务层也会撤下当前表情面板。
            SettingsPreferences.setToolbarButtons(context, listOf("schema", "emoji", "edit", "clipboard", "handwriting_lookup", "float"))
            // 测试直接写偏好，重启输入以模拟重新加载；实际定制UI通过回调立即刷新。
            rule.runOnUiThread { imm.restartInput(editor); imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("键盘调节").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("表情").performClick()
            rule.onNodeWithContentDescription("键盘调节").performClick()
            rule.onNodeWithTag("keyboard-overlay").assertDoesNotExist()
            rule.onNodeWithTag("keyboard-opacity-slider").assertIsDisplayed()
            screenshot("toolbar-resize-exclusive")
            rule.onNodeWithContentDescription("确认").performClick()

            // Explicit two-page menu: actions cannot simultaneously live in the toolbar.
            SettingsPreferences.setQuickActions(context, QuickActionLayout(
                listOf("edit", "clipboard", "handwriting_lookup", "voice"),
                listOf("emoji", "schema", "float", "ocr", "sound", "glass", "incognito", "full_shape", "settings", "arrange")))
            // 与用户截图一致：调低键盘后展开菜单，返回和设置不再挤占独立顶行。
            SettingsPreferences.setKeyboardHeightDp(context, 220)
            SettingsPreferences.setKeyboardOpacity(context, 1f)
            rule.runOnUiThread { imm.restartInput(editor); imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            rule.waitForIdle()
            rule.onNodeWithContentDescription("Lime Logo").performClick()
            rule.onAllNodesWithContentDescription("返回").assertCountEquals(1)
            rule.onNodeWithContentDescription("关闭菜单").assertDoesNotExist()
            rule.onNodeWithContentDescription("Lime Logo").assertDoesNotExist()
            rule.onNodeWithTag("menu-item:emoji").assertIsDisplayed()
            Thread.sleep(2200) // 等高度调整Toast退场，截图完整显示两排菜单。
            screenshot("menu-compact-grid")
            rule.onNodeWithTag("menu-item:schema").performClick()
            screenshot("audit-short-schema-grid")
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithTag("menu-item:schema").assertIsDisplayed()
            rule.onNodeWithTag("menu-pages", useUnmergedTree = true).performTouchInput { swipeLeft() }
            rule.onNodeWithTag("menu-item:settings").assertIsDisplayed()
            screenshot("menu-settings-page")
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithTag("keyboard-overlay").assertDoesNotExist()

            // 走真实剪贴板更新收集路径，防止初次加载完整、后续复制却只留8字。
            val clipboardText = "复制的完整原文应占满预览空间并且可以横向查看全部文字，不能只保留前面八个字。\n第二行也必须完整粘贴。"
            rule.runOnUiThread { editor.text.clear() }
            com.kingzcheung.xime.clipboard.ClipboardManager.getInstance(context).addItem(clipboardText)
            rule.waitUntil(10000) { rule.onAllNodesWithText(clipboardText.replace("\n", " ")).fetchSemanticsNodes().isNotEmpty() }
            screenshot("audit-clipboard-full-preview")
            rule.onNodeWithTag("clipboard-preview-scroll", useUnmergedTree = true).performTouchInput { down(center); up() }
            rule.waitUntil(5000) {
                var pasted = ""
                rule.runOnUiThread { pasted = editor.text.toString() }
                pasted == clipboardText
            }
            // consumed 标记通过 Room Flow 异步回到工具栏；输入框收到文字不代表数据库已完成。
            rule.waitUntil(5000) { rule.onAllNodesWithContentDescription("返回工具栏").fetchSemanticsNodes().isEmpty() }
            screenshot("audit-clipboard-pasted")
            // 同一段原文再次复制，应重新出现预览并能继续完整粘贴。
            com.kingzcheung.xime.clipboard.ClipboardManager.getInstance(context).addItem(clipboardText)
            rule.waitUntil(5000) { rule.onAllNodesWithText(clipboardText.replace("\n", " ")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("clipboard-preview-scroll", useUnmergedTree = true).performTouchInput { down(center); up() }
            rule.waitUntil(5000) {
                var pasted = ""
                rule.runOnUiThread { pasted = editor.text.toString() }
                pasted == clipboardText + clipboardText
            }
            rule.waitUntil(5000) { rule.onAllNodesWithContentDescription("返回工具栏").fetchSemanticsNodes().isEmpty() }


        } catch (failure: Throwable) {
            screenshot("failure")
            throw failure
        } finally {
            if (previousSchema.isNotEmpty()) {
                engine.switchSchema(previousSchema)
                engine.setOption("ascii_mode", previousAscii)
                engine.setUserConfigBool("var/option/ascii_mode", previousSavedAscii)
                engine.setOption("full_shape", previousFullShape)
                engine.setUserConfigBool("var/option/full_shape", previousSavedFullShape)
            }
            prefs.edit().also { edit ->
                saved.forEach { (key, value) ->
                    when (value) {
                        null -> edit.remove(key)
                        is Boolean -> edit.putBoolean(key, value)
                        is Int -> edit.putInt(key, value)
                        is Float -> edit.putFloat(key, value)
                        is String -> edit.putString(key, value)
                        is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }.commit()
            LetterLayouts.load(context)
            KeysConfigHelper.applyLetterLayouts()
            if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
            if (!enabledBefore) shell("ime disable $ime")
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ime-regression").apply { mkdirs() }
        rule.waitForIdle()
        instrumentation.waitForIdleSync()
        Thread.sleep(160)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
