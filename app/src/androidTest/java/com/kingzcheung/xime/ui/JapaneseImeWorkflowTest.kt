package com.kingzcheung.xime.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.*
import com.kingzcheung.xime.settings.SettingsPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class JapaneseImeWorkflowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun conversionDeletionUndoAndBoundaryArrowsInRealEditor(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val saved = prefs.all.toMap()
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val engine = RimeEngine.getInstance()
        lateinit var editor: EditText
        try {
            // 日语不是默认启用的语言；未启用时输入准备会拒绝 japanese_kana 并回落到中文方案。
            com.kingzcheung.xime.settings.LanguagePreferences.save(
                context, com.kingzcheung.xime.settings.InputLanguage.JAPANESE, true)
            val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
            engine.initialize(user, shared); assertTrue(RimeConfigHelper.ensureDeployment(context)); assertTrue(engine.ensureSession())
            assertTrue(engine.switchSchema("japanese_kana")); engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            SettingsPreferences.setCurrentSchema(context, "japanese_kana")
            SettingsPreferences.setFloatingMode(context, false)
            SettingsPreferences.setKeyboardHeightDp(context, 300)
            SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
            shell("ime enable ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            shell("ime set ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            rule.setContent { AndroidView(factory = { EditText(it).also { editor = it; it.hint = "日语连续流程" } }, modifier = Modifier.fillMaxWidth().height(120.dp)) }
            rule.runOnUiThread { editor.requestFocus(); (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            rule.waitUntil(30000) { rule.onAllNodesWithTag("kana-key:na").fetchSemanticsNodes().isNotEmpty() }
            fun text(): String { var result = ""; rule.runOnUiThread { result = editor.text.toString() }; return result }
            fun waitText(expected: String) { rule.waitUntil(5000) { text().replace(" ", "") == expected } }
            fun tap(tag: String) { rule.onNodeWithTag(tag).performTouchInput { down(center); up() }; rule.waitForIdle() }
            fun kana(romaji: String, dx: Float = 0f, dy: Float = 0f) {
                rule.onNodeWithTag("kana-key:$romaji").performTouchInput { down(center); moveTo(center + Offset(dx, dy) * context.resources.displayMetrics.density); up() }
                rule.waitForIdle()
            }
            kana("na"); waitText("な")
            tap("kana-delete"); waitText(""); assertEquals("", engine.getInput())
            kana("na"); kana("na", -30f); kana("wa", -30f); kana("sa", -30f); kana("ma"); kana("sa", 0f, -30f); kana("ka")
            waitText("なにをしますか")
            tap("kana-convert")
            rule.waitUntil(5000) { text().contains("何") }
            repeat(4) { tap("kana-left") }
            rule.waitUntil(5000) { text().startsWith("何") && text().endsWith("しますか") }
            screenshot("japanese-range")
            tap("kana-convert")
            rule.onNodeWithTag("kana-convert").performTouchInput { down(center); moveTo(center + Offset(0f, 50f)); moveTo(center + Offset(0f, 100f)); up() }
            waitText("なにをしますか")
            tap("kana-convert"); tap("kana-delete")
            waitText("なにをします")
            tap("kana-enter")
            assertTrue(engine.getInput().isEmpty())
            val committed = text()
            rule.runOnUiThread { editor.setSelection(0) }
            rule.onNodeWithTag("kana-left").performTouchInput { longClick(durationMillis = 900) }
            rule.runOnUiThread { assertTrue(editor.hasFocus()); assertEquals(0, editor.selectionStart); editor.setSelection(editor.length()) }
            rule.onNodeWithTag("kana-right").performTouchInput { longClick(durationMillis = 900) }
            rule.waitUntil(5000) { var end = false; rule.runOnUiThread { end = editor.selectionStart == editor.length() }; end }
            rule.runOnUiThread { assertTrue(editor.hasFocus()); assertEquals(editor.length(), editor.selectionStart); assertEquals(committed, editor.text.toString()) }
            rule.onNodeWithTag("kana-punctuation").assertIsDisplayed()
            screenshot("japanese-idle")
        } catch (t: Throwable) { screenshot("japanese-failure"); throw t }
        finally {
            engine.clearComposition()
            prefs.edit().clear().also { edit -> saved.forEach { (k,v) -> when(v) { is String -> edit.putString(k,v); is Int -> edit.putInt(k,v); is Float -> edit.putFloat(k,v); is Long -> edit.putLong(k,v); is Boolean -> edit.putBoolean(k,v); is Set<*> -> edit.putStringSet(k,v.filterIsInstance<String>().toSet()) } } }.commit()
            if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
        }
    }
    private fun shell(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }
    private fun screenshot(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val dir = File(i.targetContext.getExternalFilesDir(null), "round4").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().also { bitmap -> File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
    }
}
