package com.kingzcheung.xime.ui

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
        val saved = prefs.all.toMap()
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val engine = RimeEngine.getInstance()
        var oldSchema: String? = null
        var oldAscii: Boolean? = null
        var oldUserAscii: Boolean? = null
        lateinit var editor: EditText
        try {
            val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
            engine.initialize(user, shared)
            assertTrue(RimeConfigHelper.ensureDeployment(context))
            assertTrue(engine.ensureSession())
            oldSchema = engine.getCurrentSchema()
            oldAscii = engine.isAsciiMode()
            oldUserAscii = engine.getUserConfigBool("var/option/ascii_mode")
            assertTrue(engine.switchSchema("rime_ice"))
            engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            SettingsPreferences.setCurrentSchema(context, "rime_ice")
            SettingsPreferences.setFloatingMode(context, false)
            SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
            SettingsPreferences.setModeChangeTarget(context, ModeChangeTarget.COMMON_SYMBOL)
            val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
            shell("ime enable $ime")
            shell("ime set $ime")
            assertEquals("系统未选中 Lime", ime,
                Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            rule.setContent {
                AndroidView(factory = { EditText(it).also { view -> editor = view } },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            rule.runOnUiThread {
                editor.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            rule.waitUntil(60_000) { nodes("configured-mode-key") > 0 }
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
            prefs.edit().clear().also { edit ->
                saved.forEach { (k, v) ->
                    when (v) {
                        is String -> edit.putString(k, v)
                        is Int -> edit.putInt(k, v)
                        is Float -> edit.putFloat(k, v)
                        is Long -> edit.putLong(k, v)
                        is Boolean -> edit.putBoolean(k, v)
                        is Set<*> -> edit.putStringSet(k, v.filterIsInstance<String>().toSet())
                    }
                }
            }.commit()
            if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
        }
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
