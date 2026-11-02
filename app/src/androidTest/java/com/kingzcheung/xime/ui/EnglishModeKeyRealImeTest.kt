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
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        val engine = RimeEngine.getInstance()
        lateinit var editor: EditText
        try {
            val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
            engine.initialize(user, shared)
            assertTrue(RimeConfigHelper.ensureDeployment(context))
            assertTrue(engine.ensureSession())
            assertTrue(engine.switchSchema("rime_ice"))
            engine.setOption("ascii_mode", true)
            SettingsPreferences.setCurrentSchema(context, "rime_ice")
            // 让语言键进入英语 ASCII 模式但不切换 schema，避免循环模式的异步后续切换干扰 ?123 测试。
            prefs.edit().putString("language_key_switch_mode", "current_english").commit()
            SettingsPreferences.setFloatingMode(context, false)
            shell("ime enable ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            shell("ime set ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            rule.setContent {
                AndroidView(factory = { EditText(it).also { edit -> editor = edit } },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            rule.runOnUiThread {
                editor.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            rule.waitUntil(60_000) {
                rule.onAllNodesWithTag("configured-mode-key").fetchSemanticsNodes().isNotEmpty()
            }
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
        } finally {
            runCatching { engine.clearComposition() }
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

    private fun shell(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
    ).bufferedReader().use { it.readText() }
}
