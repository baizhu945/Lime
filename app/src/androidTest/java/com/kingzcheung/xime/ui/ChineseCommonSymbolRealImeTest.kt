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
            "floating_mode", "input_text_location", "mode_change_target", "deployment_done", "deployment_hash")
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
        } finally {
            runCatching { engine.clearComposition() }
            oldSchema?.let { runCatching { engine.switchSchema(it) } }
            oldAscii?.let { runCatching { engine.setOption("ascii_mode", it) } }
            oldUserAscii?.let { runCatching { engine.setUserConfigBool("var/option/ascii_mode", it) } }
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
