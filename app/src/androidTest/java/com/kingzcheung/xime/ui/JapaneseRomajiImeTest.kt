package com.kingzcheung.xime.ui

import android.content.Context
import android.graphics.Bitmap
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
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.settings.SettingsPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 26 键日语（japanese 罗马字方案）在真实输入框里的打字与转换。 */
class JapaneseRomajiImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun romajiTypesKanaConvertsAndCommits(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val saved = prefs.all.toMap()
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val engine = RimeEngine.getInstance()
        lateinit var editor: EditText
        try {
            LanguagePreferences.save(context, InputLanguage.JAPANESE, true)
            val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
            engine.initialize(user, shared)
            assertTrue(RimeConfigHelper.ensureDeployment(context))
            assertTrue(engine.ensureSession())
            assertTrue(engine.switchSchema("japanese"))
            engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            SettingsPreferences.setCurrentSchema(context, "japanese")
            SettingsPreferences.setFloatingMode(context, false)
            SettingsPreferences.setKeyboardHeightDp(context, 300)
            SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
            shell("ime enable ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            shell("ime set ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            rule.setContent {
                AndroidView(factory = { EditText(it).also { edit -> editor = edit; edit.hint = "日语26键" } },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            rule.runOnUiThread {
                editor.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            rule.waitUntil(30000) { rule.onAllNodesWithTag("qwerty-key:n").fetchSemanticsNodes().isNotEmpty() }
            fun text(): String { var result = ""; rule.runOnUiThread { result = editor.text.toString() }; return result }
            fun waitText(expected: String) {
                rule.waitUntil(8000) { text().replace(" ", "") == expected }
            }
            fun tap(tag: String) {
                rule.onNodeWithTag(tag).performTouchInput { down(center); up() }
                rule.waitForIdle()
            }
            // "nihongo" → にほんご
            listOf("n", "i", "h", "o", "n", "g", "o").forEach { tap("qwerty-key:$it") }
            waitText("にほんご")
            screenshot("romaji-hiragana")
            tap("space-key")
            rule.waitUntil(8000) { text().contains("日本語") }
            screenshot("romaji-candidate")
            tap("qwerty-enter-key")
            rule.waitUntil(8000) { text().trim() == "日本語" }
            assertEquals("", engine.getInput())
        } catch (t: Throwable) {
            screenshot("romaji-failure")
            throw t
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

    private fun screenshot(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val dir = File(i.targetContext.getExternalFilesDir(null), "japanese-romaji").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().also { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
