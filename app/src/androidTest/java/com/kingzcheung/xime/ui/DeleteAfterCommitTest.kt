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
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 提交文字后，第一次退格就必须删字（不能被联想吞掉）。 */
class DeleteAfterCommitTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstBackspaceDeletesCommittedCharacter(): Unit = runBlocking {
        withRealIme { engine, editor ->
            "nihao".forEach { tap("qwerty-key:$it") }
            rule.waitUntil(15_000) { engine.getInput() == "nihao" && nodes("bar-candidate:0") > 0 }
            tap("bar-candidate:0")
            rule.waitUntil(15_000) { text(editor).isNotEmpty() }
            val candidateCommit = text(editor)
            assertTrue("候选上屏必须包含中文: $candidateCommit", candidateCommit.any { it in '\u4e00'..'\u9fff' })
            Thread.sleep(1_500)
            tap("qwerty-delete-key")
            rule.waitUntil(15_000) { text(editor).length < candidateCommit.length }
            assertEquals("候选上屏后第一次退格应删掉一个字", candidateCommit.length - 1, text(editor).length)

            rule.runOnUiThread { editor.text.clear() }
            "nihao".forEach { tap("qwerty-key:$it") }
            tap("space-key")
            rule.waitUntil(15_000) { text(editor).isNotEmpty() }
            val committed = text(editor)
            Thread.sleep(1_500)
            tap("qwerty-delete-key")
            Thread.sleep(1_000)
            val afterFirst = text(editor)
            println("DELETE committed=$committed afterFirst=$afterFirst")
            assertEquals("第一次退格应删掉一个字：$committed -> $afterFirst",
                (committed.length - 1).coerceAtLeast(0), afterFirst.length)
        }
    }

    @Test fun firstBackspaceDeletesLastComposingLetter(): Unit = runBlocking {
        withRealIme { engine, _ ->
            "nihao".forEach { tap("qwerty-key:$it") }
            rule.waitUntil(15_000) { engine.getInput() == "nihao" }
            tap("qwerty-delete-key")
            rule.waitUntil(15_000) { engine.getInput() != "nihao" }
            assertEquals("niha", engine.getInput())
        }
    }

    private suspend fun withRealIme(block: suspend (RimeEngine, EditText) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val saved = prefs.all.toMap()
        val keys = setOf("current_schema", "current_schema_dual", "last_input_mode_zh", "floating_mode", "input_text_location")
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
            SettingsPreferences.setFloatingMode(context, false)
            SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
            shell("ime enable $ime")
            shell("ime set $ime")
            assertEquals("系统未选中 Lime", ime, Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            rule.setContent {
                AndroidView(factory = { EditText(it).also { view -> editor = view } },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            rule.waitUntil(10_000) {
                var ready = false
                rule.runOnUiThread { ready = editor?.let { it.isAttachedToWindow && it.hasWindowFocus() } == true }
                ready
            }
            val view = requireNotNull(editor)
            rule.runOnUiThread {
                assertTrue("测试输入框必须取得焦点", view.requestFocus())
                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
            rule.waitUntil(60_000) { nodes("hardware-toolbar-keyboard") > 0 || nodes("qwerty-key:n") > 0 }
            if (nodes("hardware-toolbar-keyboard") > 0) {
                rule.onNodeWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).performClick()
            }
            rule.waitUntil(15_000) { nodes("qwerty-key:n") > 0 }
            assertEquals("rime_ice", engine.getCurrentSchema())
            assertFalse("引擎进入英文模式", engine.isAsciiMode())
            block(engine, view)
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
                val edit = prefs.edit()
                keys.forEach { key ->
                    when (val v = saved[key]) {
                        null -> edit.remove(key)
                        is String -> edit.putString(key, v)
                        is Boolean -> edit.putBoolean(key, v)
                        is Int -> edit.putInt(key, v)
                        is Long -> edit.putLong(key, v)
                        is Float -> edit.putFloat(key, v)
                        is Set<*> -> edit.putStringSet(key, v.filterIsInstance<String>().toSet())
                    }
                }
                edit.commit()
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
    private fun tap(tag: String) {
        rule.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { down(center); up() }
        rule.waitForIdle()
    }
    private fun text(editor: EditText): String {
        var result = ""
        rule.runOnUiThread { result = editor.text.toString() }
        return result
    }
    private fun shell(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
    ).bufferedReader().use { it.readText() }
}
