package com.kingzcheung.xime.ui

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
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SchemaManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 在真正的 IME/编辑框链路中验证中文、日语点 ?123 只开数字页，不触发语言切换。 */
class ModeKeyAllLanguagesImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun chineseModeKeyOpensNumberPanelAndKeepsSchema() = runBlocking {
        verifyModeKey(schema = "rime_ice", enableJapanese = false, expectedAscii = false)
    }

    @Test fun japaneseModeKeyOpensNumberPanelAndKeepsSchema() = runBlocking {
        verifyModeKey(schema = "japanese", enableJapanese = true, expectedAscii = false)
    }

    private suspend fun verifyModeKey(schema: String, enableJapanese: Boolean, expectedAscii: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val addJapanese = enableJapanese && InputLanguage.JAPANESE !in LanguagePreferences.enabled(context)
        val ownedKeys = setOf("current_schema", "current_schema_dual",
            "last_input_mode_${InputLanguage.forSchema(schema).id}", "floating_mode",
            "input_text_location", "mode_change_target", "deployment_done", "deployment_hash") +
            if (addJapanese) setOf(LanguagePreferences.KEY, "input_language_order") else emptySet()
        val saved = ownedKeys.associateWith { prefs.all[it] }.toMutableMap()
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val previousSubtype = Settings.Secure.getInt(context.contentResolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, -1)
        val imeComponent = ComponentName(context, "com.kingzcheung.xime.service.XimeInputMethodService")
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val previouslyEnabled = imm.enabledInputMethodList.any { ComponentName.unflattenFromString(it.id) == imeComponent }
        val engine = RimeEngine.getInstance()
        var oldSchema: String? = if (RimeEngine.isInitialized()) engine.getCurrentSchema().takeIf { it.isNotBlank() } else null
        var oldAscii: Boolean? = if (oldSchema != null) engine.isAsciiMode() else null
        var oldUserAscii: Boolean? = if (oldSchema != null) engine.getUserConfigBool("var/option/ascii_mode") else null
        var oldEnabledSchemas: List<String>? = null
        lateinit var editor: EditText
        var editorCreated = false
        try {
            // ?123 returns to the last-used page; this fixture specifically exercises numbers.
            SettingsPreferences.setModeChangeTarget(context, com.kingzcheung.xime.keyboard.ModeChangeTarget.NUMBER)
            if (addJapanese) {
                val schemas = SchemaManager.getEnabledSchemas(context)
                oldEnabledSchemas = schemas
                // save() may choose a native fallback and remember its language too.
                val nextLanguages = LanguagePreferences.enabled(context) + InputLanguage.JAPANESE
                val current = SettingsPreferences.getCurrentSchema(context)
                if (current == com.kingzcheung.xime.settings.InputModes.ENGLISH ||
                    InputLanguage.forSchema(current) !in nextLanguages) {
                    val available = (schemas + com.kingzcheung.xime.settings.JapaneseSchemas.ids).distinct()
                    val fallback = available.firstOrNull {
                        it.isNotBlank() && it != com.kingzcheung.xime.settings.InputModes.ENGLISH &&
                            InputLanguage.forSchema(it) in nextLanguages
                    } ?: available.firstOrNull {
                        it.isNotBlank() && it != com.kingzcheung.xime.settings.InputModes.ENGLISH
                    } ?: "rime_ice"
                    val key = "last_input_mode_${InputLanguage.forSchema(fallback).id}"
                    if (key !in saved) saved[key] = prefs.all[key]
                }
                LanguagePreferences.save(context, InputLanguage.JAPANESE, true)
            }
            val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
            engine.initialize(user, shared)
            assertTrue("部署失败", RimeConfigHelper.ensureDeployment(context))
            assertTrue("Rime session 未就绪", engine.ensureSession())
            if (oldSchema == null) oldSchema = engine.getCurrentSchema()
            if (oldAscii == null) oldAscii = engine.isAsciiMode()
            if (oldUserAscii == null) oldUserAscii = engine.getUserConfigBool("var/option/ascii_mode")
            assertTrue("方案不可用: $schema", engine.switchSchema(schema))
            engine.setOption("ascii_mode", expectedAscii)
            engine.setUserConfigBool("var/option/ascii_mode", expectedAscii)
            SettingsPreferences.setCurrentSchema(context, schema)
            SettingsPreferences.setFloatingMode(context, false)
            SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
            shell("ime enable ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            shell("ime set ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            rule.setContent {
                AndroidView(factory = { EditText(it).also { edit -> editor = edit; editorCreated = true } },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            rule.runOnUiThread {
                editor.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            rule.waitUntil(60_000) {
                rule.onAllNodesWithTag("configured-mode-key", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
            }
            if (rule.onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).performClick()
            }
            rule.waitUntil(60_000) {
                rule.onAllNodesWithTag("configured-mode-key", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("测试起始方案", schema, engine.getCurrentSchema())
            assertEquals("测试起始 ASCII 状态", expectedAscii, engine.isAsciiMode())
            rule.onNodeWithTag("configured-mode-key", useUnmergedTree = true).performTouchInput { click() }
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("number-delete-key", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("?123 只开数字页，不应切换方案", schema, engine.getCurrentSchema())
            assertEquals("?123 不应切换输入语言/ASCII 状态", expectedAscii, engine.isAsciiMode())
        } finally {
            runCatching { engine.clearComposition() }
            runCatching {
                if (editorCreated) rule.runOnUiThread {
                    imm.hideSoftInputFromWindow(editor.windowToken, 0)
                    editor.clearFocus()
                }
            }
            oldEnabledSchemas?.let { runCatching { SchemaManager.setEnabledSchemas(context, it) } }
            oldSchema?.let { runCatching { engine.switchSchema(it) } }
            oldAscii?.let { runCatching { engine.setOption("ascii_mode", it) } }
            oldUserAscii?.let { runCatching { engine.setUserConfigBool("var/option/ascii_mode", it) } }
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
            if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
            if (!previouslyEnabled) shell("ime disable $ime")
            shell("settings put secure selected_input_method_subtype $previousSubtype")
        }
    }

    private fun shell(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
    ).bufferedReader().use { it.readText() }
}
