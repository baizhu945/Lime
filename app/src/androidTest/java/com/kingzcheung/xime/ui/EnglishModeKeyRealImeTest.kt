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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 真正的 IME 英文（ASCII）状态下点 ?123：只开数字页，不循环语言。 */
class EnglishModeKeyRealImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun asciiModeChangeKeyOpensNumberPanelWithoutSwitchingLanguage(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val saved = prefs.all.toMap()
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val previousSubtype = Settings.Secure.getInt(context.contentResolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, -1)
        val component = ComponentName(context, "com.kingzcheung.xime.service.XimeInputMethodService")
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val previouslyEnabled = imm.enabledInputMethodList.any { ComponentName.unflattenFromString(it.id) == component }
        val engine = RimeEngine.getInstance()
        var oldSchema: String? = null
        var oldAscii: Boolean? = null
        var oldUserAscii: Boolean? = null
        var oldUserSchema: String? = null
        var editor: EditText? = null
        try {
            stopKeyboardService(context, component, ime)
            val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
            engine.initialize(user, shared)
            assertTrue(RimeConfigHelper.ensureDeployment(context))
            assertTrue(engine.ensureSession())
            oldSchema = engine.getCurrentSchema()
            oldAscii = engine.isAsciiMode()
            oldUserAscii = engine.getUserConfigBool("var/option/ascii_mode")
            oldUserSchema = engine.getUserConfigString("var/previously_selected_schema")
            assertTrue(engine.switchSchema("rime_ice"))
            engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            SettingsPreferences.setCurrentSchema(context, "rime_ice")
            // 让语言键进入英语 ASCII 模式但不切换 schema，避免循环模式的异步后续切换干扰 ?123 测试。
            prefs.edit().putString("language_key_switch_mode", "current_english").commit()
            SettingsPreferences.setFloatingMode(context, false)
            SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
            SettingsPreferences.setModeChangeTarget(context, ModeChangeTarget.NUMBER)
            shell("ime enable $ime")
            shell("ime set $ime")
            assertEquals("系统未选中 Lime", ime, Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            rule.setContent {
                AndroidView(factory = { EditText(it).also { edit -> editor = edit } },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            rule.waitUntil(10_000) {
                var ready = false
                rule.runOnUiThread { ready = editor?.let { it.isAttachedToWindow && it.hasWindowFocus() } == true }
                ready
            }
            rule.runOnUiThread {
                val view = requireNotNull(editor)
                assertTrue("测试输入框必须取得焦点", view.requestFocus())
                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
            rule.waitUntil(60_000) { nodes("hardware-toolbar-keyboard") > 0 || nodes("configured-mode-key") > 0 }
            if (nodes("hardware-toolbar-keyboard") > 0) {
                rule.onNodeWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).performClick()
            }
            rule.waitUntil(15_000) { nodes("configured-mode-key") > 0 }
            assertFalse("地球键之前必须是中文模式", engine.isAsciiMode())
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("configured-earth-key", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            // 从真实中文 IME 状态按地球键切到英文，而不是仅改引擎选项。
            rule.onNodeWithTag("configured-earth-key", useUnmergedTree = true).performTouchInput { click() }
            rule.waitUntil(10_000) { engine.isAsciiMode() }
            assertTrue("地球键应切到 ASCII/英文模式", engine.isAsciiMode())
            assertEquals("测试应稳定在同一英文 schema", "rime_ice", engine.getCurrentSchema())
            rule.onNodeWithTag("configured-mode-key", useUnmergedTree = true).performTouchInput { click() }
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("number-delete-key", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("?123 不应切换到其他 Rime 方案", "rime_ice", engine.getCurrentSchema())
            assertTrue("?123 不应切换语言/ASCII 状态", engine.isAsciiMode())
            rule.onNodeWithContentDescription("返回键盘", useUnmergedTree = true).performTouchInput { click() }
            rule.waitUntil(15_000) { nodes("configured-mode-key") > 0 && nodes("number-delete-key") == 0 }
            assertEquals("返回后方案不变", "rime_ice", engine.getCurrentSchema())
            assertTrue("返回后仍为英文", engine.isAsciiMode())
        } finally {
            runCatching { engine.clearComposition() }
            oldSchema?.let { runCatching { engine.switchSchema(it) } }
            oldAscii?.let { runCatching { engine.setOption("ascii_mode", it) } }
            oldUserAscii?.let { runCatching { engine.setUserConfigBool("var/option/ascii_mode", it) } }
            oldUserSchema?.let { runCatching { engine.setUserConfigString("var/previously_selected_schema", it) } }
            try {
                editor?.let { view -> rule.runOnUiThread { imm.hideSoftInputFromWindow(view.windowToken, 0); view.clearFocus() } }
                stopKeyboardService(context, component, ime)
            } finally {
            val keys = setOf("current_schema", "current_schema_dual", "last_input_mode_zh", "floating_mode", "input_text_location", "language_key_switch_mode", "mode_change_target")
            prefs.edit().also { edit ->
                keys.forEach { k ->
                    when (val v = saved[k]) {
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
        val am = context.getSystemService(ActivityManager::class.java)
        val waitForEngineDestroy = RimeEngine.isInitialized() && am.getRunningServices(Int.MAX_VALUE).any { it.service == component }
        shell("ime disable $ime")
        rule.waitUntil(10_000) {
            am.getRunningServices(Int.MAX_VALUE).none { it.service == component } &&
                (!waitForEngineDestroy || !RimeEngine.isInitialized())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun nodes(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun shell(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
    ).bufferedReader().use { it.readText() }
}
