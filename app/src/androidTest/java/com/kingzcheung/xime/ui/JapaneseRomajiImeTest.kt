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
        // Only fixture settings (including language-helper writes), never records or models.
        val ownedKeys = setOf(
            "enabled_input_languages", "input_language_order", "japanese_language_added_v1",
            "current_schema", "current_schema_dual", "floating_mode", "keyboard_height_dp", "input_text_location",
            "builtin_schemas_merged", "japanese_schemas_added_v1", "cyime_chinese_defaults_v1",
            "cyime_double_pinyin_defaults_v1", "deployment_done", "deployment_hash",
            RimeConfigHelper.DEPLOYMENT_REVISION, RimeConfigHelper.DEPLOYMENT_PROBLEM,
        ) + InputLanguage.entries.map { "last_input_mode_${it.id}" }
        val saved = ownedKeys.associateWith { key ->
            prefs.all[key].let { if (it is Set<*>) it.toSet() else it }
        }
        val imeId = android.content.ComponentName(
            context.packageName, "com.kingzcheung.xime.service.XimeInputMethodService",
        ).flattenToShortString()
        val inputMethods = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val wasImeEnabled = inputMethods.enabledInputMethodList.any { it.id == imeId }
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val engine = RimeEngine.getInstance()
        var previousSchema: String? = null
        var previousSelectedSchema: String? = null
        var previousAscii: Boolean? = null
        var previousPersistentAscii: Boolean? = null
        fun captureNativeState() {
            if (previousAscii != null || engine.getCurrentSchema().isBlank()) return
            previousSchema = engine.getCurrentSchema()
            previousSelectedSchema = engine.getUserConfigString("var/previously_selected_schema")
            previousAscii = engine.getOption("ascii_mode")
            previousPersistentAscii = engine.getUserConfigBool("var/option/ascii_mode")
        }
        if (RimeEngine.isInitialized()) captureNativeState()
        lateinit var editor: EditText
        var expandedCompactToolbar = false
        fun has(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        try {
            LanguagePreferences.save(context, InputLanguage.JAPANESE, true)
            val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
            engine.initialize(user, shared)
            assertTrue(RimeConfigHelper.ensureDeployment(context))
            assertTrue(engine.ensureSession())
            captureNativeState()
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
            rule.waitUntil(30000) { has("qwerty-key:n") || has("hardware-toolbar-keyboard") }
            if (!has("qwerty-key:n")) {
                rule.onNodeWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).performClick()
                rule.waitUntil(15000) { has("qwerty-key:n") }
                expandedCompactToolbar = true
            }
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
            if (expandedCompactToolbar) runCatching { shell("input keyevent KEYCODE_BACK") }

            runCatching { engine.clearComposition() }
            runCatching {
                previousSchema?.takeIf { it.isNotBlank() && it != engine.getCurrentSchema() }
                    ?.let { engine.switchSchema(it) }
                previousSelectedSchema?.let {
                    if (engine.getUserConfigString("var/previously_selected_schema") != it)
                        engine.setUserConfigString("var/previously_selected_schema", it)
                }
                previousPersistentAscii?.let {
                    if (engine.getUserConfigBool("var/option/ascii_mode") != it)
                        engine.setUserConfigBool("var/option/ascii_mode", it)
                }
                previousAscii?.let { if (engine.getOption("ascii_mode") != it) engine.setOption("ascii_mode", it) }
            }
            prefs.edit().also { edit ->
                saved.forEach { (k, v) ->
                    if (prefs.all[k] == v) return@forEach
                    when (v) {
                        null -> edit.remove(k)
                        is String -> edit.putString(k, v)
                        is Int -> edit.putInt(k, v)
                        is Float -> edit.putFloat(k, v)
                        is Long -> edit.putLong(k, v)
                        is Boolean -> edit.putBoolean(k, v)
                        is Set<*> -> edit.putStringSet(k, v.map { it as String }.toSet())
                    }
                }
            }.commit()
            try {
                if (!previousIme.isNullOrBlank() && Settings.Secure.getString(
                        context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) != previousIme) {
                    shell("ime set $previousIme")
                }
            } finally {
                if (!wasImeEnabled && inputMethods.enabledInputMethodList.any { it.id == imeId }) {
                    shell("ime disable $imeId")
                }
            }
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
