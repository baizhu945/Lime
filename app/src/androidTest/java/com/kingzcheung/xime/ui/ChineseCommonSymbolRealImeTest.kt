package com.kingzcheung.xime.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.keyboard.ModeChangeTarget
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 使用系统选中的真实 Lime 服务和 EditText，不直接构造键盘视图。 */
class ChineseCommonSymbolRealImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun configuredCommonSymbolsReturnToChinese() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        // Only fixture-owned settings; service migrations and user records are not ours to replay.
        val ownedKeys = setOf("current_schema", "current_schema_dual", "last_input_mode_zh",
            "floating_mode", "input_text_location", "mode_change_target", "deployment_done", "deployment_hash",
            "qwerty_comma_key", "qwerty_period_key_v2", "reverse_symbol_swipe", "symbol_input_mode",
            SettingsPreferences.KEY_PUNCTUATION_FULL_WIDTH, "english_punctuation_space",
            com.kingzcheung.xime.settings.LanguageSwitchPreferences.MODE_KEY,
            "last_input_mode_en", "selected_input_profile_zh", "selected_input_profile_en")
        val saved = ownedKeys.associateWith { prefs.all[it] }
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val previousSubtype = Settings.Secure.getInt(context.contentResolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, -1)
        val imeComponent = ComponentName(context, "com.kingzcheung.xime.service.XimeInputMethodService")
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val previouslyEnabled = imm.enabledInputMethodList.any { ComponentName.unflattenFromString(it.id) == imeComponent }
        val engine = RimeEngine.getInstance()
        // Capture a live session before stopKeyboardService destroys it, if one exists.
        var oldSchema: String? = if (RimeEngine.isInitialized()) engine.getCurrentSchema().takeIf { it.isNotBlank() } else null
        var oldAscii: Boolean? = if (oldSchema != null) engine.isAsciiMode() else null
        var oldUserAscii: Boolean? = if (oldSchema != null) engine.getUserConfigBool("var/option/ascii_mode") else null
        var oldFullShape: Boolean? = if (oldSchema != null) engine.getOption("full_shape") else null
        var oldUserFullShape: Boolean? = if (oldSchema != null) engine.getUserConfigBool("var/option/full_shape") else null
        var oldAsciiPunct: Boolean? = if (oldSchema != null) engine.getOption("ascii_punct") else null
        var oldUserAsciiPunct: Boolean? = if (oldSchema != null) engine.getUserConfigBool("var/option/ascii_punct") else null
        lateinit var editor: EditText
        var editorCreated = false
        try {
            // A previous test's service ComposeView may retain its disposed test recomposer.
            stopKeyboardService(context, imeComponent, ime)
            val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
            engine.initialize(user, shared)
            assertTrue(RimeConfigHelper.ensureDeployment(context))
            assertTrue(engine.ensureSession())
            if (oldSchema == null) oldSchema = engine.getCurrentSchema()
            if (oldAscii == null) oldAscii = engine.isAsciiMode()
            if (oldUserAscii == null) oldUserAscii = engine.getUserConfigBool("var/option/ascii_mode")
            if (oldFullShape == null) oldFullShape = engine.getOption("full_shape")
            if (oldUserFullShape == null) oldUserFullShape = engine.getUserConfigBool("var/option/full_shape")
            if (oldAsciiPunct == null) oldAsciiPunct = engine.getOption("ascii_punct")
            if (oldUserAsciiPunct == null) oldUserAsciiPunct = engine.getUserConfigBool("var/option/ascii_punct")
            assertTrue(engine.switchSchema("rime_ice"))
            engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            SettingsPreferences.setCurrentSchema(context, "rime_ice")
            SettingsPreferences.setFloatingMode(context, false)
            SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
            SettingsPreferences.setModeChangeTarget(context, ModeChangeTarget.COMMON_SYMBOL)
            shell("ime enable $ime")
            shell("ime set $ime")
            assertEquals("系统未选中 Lime", ime,
                Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            rule.setContent {
                AndroidView(factory = { EditText(it).also { view -> editor = view; editorCreated = true } },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            rule.waitUntil(10_000) {
                var ready = false
                rule.runOnUiThread { ready = editor.isAttachedToWindow && editor.hasWindowFocus() }
                ready
            }
            rule.runOnUiThread {
                assertTrue("测试输入框必须取得焦点", editor.requestFocus())
                imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            // The emulator may expose a physical keyboard. Use the real toolbar action to
            // expand the screen keyboard, rather than changing device configuration or service state.
            rule.waitUntil(60_000) { nodes("hardware-toolbar-keyboard") > 0 || nodes("configured-mode-key") > 0 }
            if (nodes("hardware-toolbar-keyboard") > 0) {
                rule.onNodeWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).performClick()
            }
            rule.waitUntil(15_000) { nodes("configured-mode-key") > 0 }
            checkChinese(engine)
            rule.onNodeWithTag("configured-mode-key", useUnmergedTree = true).performTouchInput { click() }
            rule.waitUntil(15_000) { nodes("common-symbol-panel") > 0 }
            checkChinese(engine)
            rule.onNodeWithText("，", useUnmergedTree = true).assertExists()
            rule.onNodeWithText("。", useUnmergedTree = true).assertExists()
            rule.onNodeWithTag("common-symbol-return", useUnmergedTree = true)
                .assert(hasAnyDescendant(hasText("中文")))
            rule.onNodeWithTag("common-symbol-return", useUnmergedTree = true).performTouchInput { click() }
            rule.waitUntil(15_000) { nodes("configured-mode-key") > 0 && nodes("common-symbol-panel") == 0 }
            // Sample across the transition and the settled state; a transient between samples can be missed.
            repeat(20) {
                checkChinese(engine)
                Thread.sleep(25)
            }

            // Append real-host checks only after the original navigation/settling contract.
            assertTrue(prefs.edit().putBoolean("qwerty_comma_key", true)
                .putBoolean("qwerty_period_key_v2", true)
                .putBoolean("reverse_symbol_swipe", false)
                .putBoolean("english_punctuation_space", false)
                .putString(com.kingzcheung.xime.settings.LanguageSwitchPreferences.MODE_KEY,
                    com.kingzcheung.xime.settings.LanguageSwitchMode.CURRENT_ENGLISH.id)
                .putString("symbol_input_mode", "SWIPE_UP").commit())
            // Native-only setup above deliberately leaves the user's chosen Chinese profile alone.
            // The new language-roundtrip fixture must explicitly select its own profile too.
            val profileDeadline = android.os.SystemClock.uptimeMillis() + 5_000
            val service = rule.awaitRealImeInputReady(editor, imm, engine,
                (profileDeadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1))
            rule.runOnUiThread { service.schemaController.switchSchema("rime_ice") }
            rule.waitUntil((profileDeadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1)) {
                service.uiState.value.currentSchemaId == "rime_ice" && !service.uiState.value.isAsciiMode &&
                    prefs.getString("selected_input_profile_zh", null) == "rime_ice"
            }
            val period = "qwerty-period-key"
            val comma = "qwerty-punctuation-key"
            fun checkChineseSnapshot() {
                // New asynchronous typing cases read both native fields under one real lock;
                // an unqueued getCurrentSchema() otherwise returns "" when merely busy.
                engine.editPinyinAndReadState { checkChinese(engine); false }
            }
            fun key(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)
            fun hostText(): String {
                var text = ""
                rule.runOnUiThread { text = editor.text.toString() }
                return text
            }
            var expected = hostText()
            fun settled(suffix: String) {
                expected += suffix
                rule.waitUntil(5_000) { hostText() == expected && engine.getInput().isEmpty() }
                assertEquals("必须原样提交到真实输入框，不能选择首候选", expected, hostText())
                assertEquals("标点提交后不能残留组码", "", engine.getInput())
            }
            fun tap(tag: String) = key(tag).performTouchInput { click() }
            fun alternate(tag: String) = key(tag).performTouchInput {
                down(center)
                moveTo(center + Offset(0f, 80f * context.resources.displayMetrics.density), delayMillis = 120)
                up()
            }
            fun ready(primary: String, secondary: String, commaPrimary: String, commaSecondary: String) {
                try {
                    rule.waitUntil(5_000) {
                        nodes("configured-control-row") == 1 && nodes(period) == 1 && nodes(comma) == 1 &&
                            rule.onAllNodes(hasTestTag(period) and hasAnyDescendant(hasText(primary)) and
                                hasAnyDescendant(hasText(secondary)), true).fetchSemanticsNodes().size == 1 &&
                            rule.onAllNodes(hasTestTag(comma) and hasAnyDescendant(hasText(commaPrimary)) and
                                hasAnyDescendant(hasText(commaSecondary)), true).fetchSemanticsNodes().size == 1
                    }
                } catch (failure: Throwable) {
                    println("PUNCTUATION_READY expected=$primary/$secondary,$commaPrimary/$commaSecondary")
                    println(key(period).printToString())
                    println(key(comma).printToString())
                    engine.editPinyinAndReadState {
                        println("PUNCTUATION_READY schema=${engine.getCurrentSchema()} ascii=${engine.isAsciiMode()} full=${engine.getOption("full_shape")}")
                        false
                    }
                    throw failure
                }
                key(period).assert(hasAnyAncestor(hasTestTag("configured-control-row")))
                    .assert(hasAnyDescendant(hasText(secondary)))
                key(comma).assert(hasAnyAncestor(hasTestTag("configured-control-row")))
                    .assert(hasAnyDescendant(hasText(commaSecondary)))
            }
            fun width(full: Boolean) {
                // Exercise the existing real IME width action, not a synthetic keyboard/model.
                tap("toolbar-leading")
                val tag = "menu-item:full_shape"
                rule.waitUntil(5_000) { nodes(tag) == 1 }
                val desired = if (full) "全角" else "半角"
                fun shown() = key(tag).fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
                if (shown() != desired) key(tag).performClick()
                rule.waitUntil(5_000) { shown() == desired }
                tap("toolbar-leading")
            }
            fun composing() {
                "nihao".forEach { tap("qwerty-key:$it") }
                rule.waitUntil(5_000) { engine.getInput() == "nihao" }
                checkChineseSnapshot()
            }
            fun chinesePunctuation(primary: String, secondary: String, commaPrimary: String, commaSecondary: String) {
                ready(primary, secondary, commaPrimary, commaSecondary)
                tap(period); settled(primary)
                alternate(period); settled(secondary)
                tap(comma); settled(commaPrimary)
                alternate(comma); settled(commaSecondary)
                composing(); tap(period); settled("nihao$primary")
                composing(); alternate(period); settled("nihao$secondary")
                composing(); tap(comma); settled("nihao$commaPrimary")
                composing(); alternate(comma); settled("nihao$commaSecondary")
                checkChineseSnapshot()
            }
            width(true)
            chinesePunctuation("。", ".", "，", ",")
            width(false)
            chinesePunctuation(".", "。", ",", "，")
            tap("configured-earth-key")
            rule.waitUntil(5_000) { engine.isAsciiMode() }
            ready(".", ".", ",", ",")
            key(period).assert(hasAnyDescendant(hasText("。")).not())
            key(comma).assert(hasAnyDescendant(hasText("，")).not())
            tap(period); settled(".")
            tap(comma); settled(",")
            tap("configured-earth-key")
            // The existing language switch may restore that language's width preference.
            // Select the intended half-width fixture again rather than assume it stayed latched.
            width(false)
            ready(".", "。", ",", "，")
            checkChineseSnapshot()

            // Opt-out retains the old single-comma key and its period alternate.
            assertTrue(prefs.edit().putBoolean("qwerty_period_key_v2", false).commit())
            width(true)
            rule.waitUntil(5_000) { nodes(period) == 0 && nodes(comma) == 1 }
            key("configured-mode-key").assertExists()
            key(comma).assert(hasAnyDescendant(hasText("，")))
                .assert(hasAnyDescendant(hasText("。")))
            tap(comma); settled("，")
            alternate(comma); settled("。")
            checkChineseSnapshot()
        } finally {
            runCatching { engine.clearComposition() }
            oldSchema?.let { runCatching { engine.switchSchema(it) } }
            oldAscii?.let { runCatching { engine.setOption("ascii_mode", it) } }
            oldUserAscii?.let { runCatching { engine.setUserConfigBool("var/option/ascii_mode", it) } }
            oldFullShape?.let { runCatching { engine.setOption("full_shape", it) } }
            oldUserFullShape?.let { runCatching { engine.setUserConfigBool("var/option/full_shape", it) } }
            oldAsciiPunct?.let { runCatching { engine.setOption("ascii_punct", it) } }
            oldUserAsciiPunct?.let { runCatching { engine.setUserConfigBool("var/option/ascii_punct", it) } }
            try {
                if (editorCreated) rule.runOnUiThread {
                    imm.hideSoftInputFromWindow(editor.windowToken, 0)
                    editor.clearFocus()
                }
                stopKeyboardService(context, imeComponent, ime)
            } finally {
            prefs.edit().also { edit ->
                saved.forEach { (k, v) ->
                    when (v) {
                        null -> edit.remove(k)
                        is String -> edit.putString(k, v)
                        is Int -> edit.putInt(k, v)
                        is Float -> edit.putFloat(k, v)
                        is Long -> edit.putLong(k, v)
                        is Boolean -> edit.putBoolean(k, v)
                        is Set<*> -> edit.putStringSet(k, v.filterIsInstance<String>().toSet())
                    }
                }
            }.commit()
            if (previouslyEnabled) shell("ime enable $ime")
            if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
            shell("settings put secure selected_input_method_subtype $previousSubtype")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopKeyboardService(context: Context, component: ComponentName, ime: String) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val waitForEngineDestroy = RimeEngine.isInitialized() &&
            activityManager.getRunningServices(Int.MAX_VALUE).any { it.service == component }
        shell("ime disable $ime")
        rule.waitUntil(10_000) {
            activityManager.getRunningServices(Int.MAX_VALUE).none { it.service == component } &&
                (!waitForEngineDestroy || !RimeEngine.isInitialized())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun nodes(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNodes().size

    private fun checkChinese(engine: RimeEngine) {
        assertEquals("方案发生变化", "rime_ice", engine.getCurrentSchema())
        assertFalse("引擎进入英文模式", engine.isAsciiMode())
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
