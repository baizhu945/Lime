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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 键盘语言键：中文 → 日语 → 中文，并在切换后直接输入。 */
class LanguageSwitchTypingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun globeCyclesChineseJapaneseAndTypingFollows(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val saved = prefs.all.toMap()
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val engine = RimeEngine.getInstance()
        lateinit var editor: EditText
        try {
            // 不手工开启日语：默认配置就应让语言键能循环到日语。
            if (InputLanguage.JAPANESE !in LanguagePreferences.enabled(context)) {
                LanguagePreferences.initialize(context)
            }
            val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
            engine.initialize(user, shared)
            assertTrue(RimeConfigHelper.ensureDeployment(context))
            assertTrue(engine.ensureSession())
            assertTrue(engine.switchSchema("rime_ice"))
            SettingsPreferences.setCurrentSchema(context, "rime_ice")
            SettingsPreferences.setFloatingMode(context, false)
            SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
            shell("ime enable ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            shell("ime set ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            rule.setContent {
                AndroidView(factory = { EditText(it).also { edit -> editor = edit; edit.hint = "中→日切换" } },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            rule.runOnUiThread {
                editor.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            rule.waitUntil(60_000) {
                rule.onAllNodesWithTag("configured-earth-key").fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodesWithTag("qwerty-key:q").fetchSemanticsNodes().isNotEmpty()
            }
            fun tap(tag: String) {
                rule.onNodeWithTag(tag).performTouchInput { down(center); up() }
                rule.waitForIdle()
            }
            fun languageKeyTag(): String? = listOf(
                "configured-earth-key", "kana-language", "language-key-control", "qwerty-punctuation-key",
            ).firstOrNull { rule.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
            // 中 → 英 → 日 → 中，点到日语方案为止（语言键按启用语言循环）。
            val seen = mutableListOf(engine.getCurrentSchema())
            var switched = false
            repeat(6) {
                if (engine.getCurrentSchema().startsWith("japanese")) {
                    switched = true
                    return@repeat
                }
                val tag = languageKeyTag()
                if (tag == null) {
                    screenshot("no-language-key")
                    throw AssertionError("找不到语言键；已见方案=$seen")
                }
                tap(tag)
                rule.waitForIdle()
                Thread.sleep(800)
                seen += engine.getCurrentSchema()
            }
            assertTrue("语言键应能切到日语，实际经过=$seen", switched)
            screenshot("japanese-schema")
            // 罗马字输入在日语方案下应出假名与汉字候选。
            val letters = listOf("n", "i", "h", "o", "n", "g", "o")
            letters.forEach { letter ->
                val tag = "qwerty-key:$letter"
                if (rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) tap(tag)
            }
            rule.waitUntil(15_000) { engine.getInput().isNotEmpty() || editor.text.isNotEmpty() }
            screenshot("japanese-typed")
            println("SWITCH typed editor=${editor.text} input=${engine.getInput()} schema=${engine.getCurrentSchema()}")
            // 再切回中文，确认可以来回切。
            repeat(6) {
                if (engine.getCurrentSchema().startsWith("rime")) return@repeat
                val tag = languageKeyTag() ?: return@repeat
                tap(tag)
                rule.waitForIdle()
                Thread.sleep(800)
            }
            screenshot("back-to-chinese")
            assertTrue("应能切回中文，实际=${engine.getCurrentSchema()}",
                !engine.getCurrentSchema().startsWith("japanese"))
        } catch (t: Throwable) {
            screenshot("failure")
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
        val dir = File(i.targetContext.getExternalFilesDir(null), "switch").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().also { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
