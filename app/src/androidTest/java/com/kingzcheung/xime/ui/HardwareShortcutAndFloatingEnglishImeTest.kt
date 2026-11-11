package com.kingzcheung.xime.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Short real-IME check; all typing stays in an application-owned disposable EditText. */
class HardwareShortcutAndFloatingEnglishImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun fixedShortcutsAndFloatingEnglishSuggestionsUseTheRealEditor() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Connect a physical keyboard before running this device check",
            context.resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS)
        val prefs = SettingsPreferences.getPrefsPublic(context)
        fun fixtureKey(key: String) = key.startsWith(HardwareKeyboardPreferences.PREFIX) ||
            key.startsWith("current_schema") || key.startsWith("last_input_mode_") ||
            key.startsWith("selected_input_profile_") || key == LanguagePreferences.KEY ||
            key == LanguageSwitchPreferences.MODE_KEY || key == LanguageSwitchPreferences.LANGUAGE_KEY ||
            key == "input_language_order"
        val savedPrefs = prefs.all.filterKeys(::fixtureKey).mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        assertTrue("The real engine must be prepared", RimeConfigHelper.prepareEngine(context))
        val engine = RimeEngine.getInstance()
        val savedSchema = engine.getCurrentSchema()
        val savedOptions = listOf("ascii_mode", "full_shape", "ascii_punct").associateWith(engine::getOption)
        val savedAscii = engine.getUserConfigBool("var/option/ascii_mode")
        val savedPrevious = engine.getUserConfigString("var/previously_selected_schema")
        val schemaFiles = listOf("default.custom.yaml", "default.yaml").associate { name ->
            val file = File(context.filesDir, "rime/$name")
            file to file.takeIf { it.isFile }?.readBytes()
        }
        lateinit var editor: EditText
        var editorReady = false
        fun has(tag: String) = rule.onAllNodesWithTag(tag, true).fetchSemanticsNodes().isNotEmpty()
        fun languageIs(label: String) = rule.onAllNodesWithTag("hardware-toolbar-language", true)
            .fetchSemanticsNodes().any { node ->
                SemanticsProperties.ContentDescription in node.config &&
                    node.config[SemanticsProperties.ContentDescription].any { it.startsWith("当前语言：$label，") }
            }
        fun awaitLanguage(label: String) = rule.waitUntil(10_000) {
            languageIs(label) && engine.isAsciiMode() == (label == "EN")
        }
        fun document(): String {
            var text = ""
            rule.runOnUiThread { text = editor.text.toString() }
            return text
        }
        fun completion(): String? {
            val node = rule.onAllNodesWithTag("bar-candidate:1").fetchSemanticsNodes().firstOrNull() ?: return null
            if (SemanticsProperties.Text !in node.config) return null
            return node.config[SemanticsProperties.Text].joinToString("") { it.text }
                .takeIf { it.startsWith("hel", true) && it.length > 3 }
        }
        fun selection(): Pair<Int, Int> {
            var selected = -1 to -1
            rule.runOnUiThread { selected = editor.selectionStart to editor.selectionEnd }
            return selected
        }
        fun committedDocument(): String {
            var committed = ""
            rule.runOnUiThread {
                val value = editor.text
                val start = BaseInputConnection.getComposingSpanStart(value)
                val end = BaseInputConnection.getComposingSpanEnd(value)
                committed = if (start >= 0 && end >= start) value.toString().removeRange(start, end)
                    else value.toString()
            }
            return committed
        }
        fun highlighted(index: Int): Boolean = rule.onAllNodesWithTag("bar-candidate:$index", true)
            .fetchSemanticsNodes().any { node ->
                SemanticsProperties.Selected in node.config && node.config[SemanticsProperties.Selected]
            }
        fun nativeCandidate(index: Int): String {
            val texts = rule.onNodeWithTag("bar-candidate:$index").fetchSemanticsNode()
                .config[SemanticsProperties.Text]
            return texts.first().text.removePrefix("${(index + 1) % 10} ")
        }
        fun resetEditor(prefix: String, language: String) {
            shell("input keyboard keyevent KEYCODE_ESCAPE")
            rule.runOnUiThread {
                editor.setText(prefix)
                editor.setSelection(editor.length())
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(editor)
            }
            rule.waitUntil(10_000) {
                document() == prefix && engine.getInput().isEmpty() &&
                    has("hardware-keyboard-toolbar") && languageIs(language) && !has("hardware-docked-candidates")
            }
        }
        fun screenshot(name: String) {
            instrumentation.uiAutomation.takeScreenshot()?.also { bitmap ->
                File(context.getExternalFilesDir(null), name).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        try {
            val schemas = SchemaManager.discoverSchemas(context).map { it.toSchemaInfo() }
            val compiled = schemas.filter { SchemaManager.isSchemaCompiled(context, it.schemaId) }
            val compiledIds = compiled.map { it.schemaId }.toSet()
            val canonicalCompiledIds = CyimeInputDefaults.canonicalIds(
                compiledIds.toList(), schemas.map { it.schemaId }.toSet()
            ).toSet()
            assertTrue("Compiled Chinese pinyin is required", compiled.any { it.schemaId == "rime_ice" })
            // Discovery includes legacy aliases (jaroomaji); use the same visible
            // product profiles as the language selector and switching controller.
            val japanese = InputModes.available(schemas).firstOrNull {
                it.schemaId in canonicalCompiledIds && it.schemaId in compiledIds &&
                    it.profile.language == InputLanguage.JAPANESE && it.profile.mode == InputMode.KEYBOARD
            }?.schemaId
            assertNotNull("A compiled Japanese backend is required; do not count a skipped cycle as passing", japanese)
            SchemaManager.setEnabledSchemas(context, (SchemaManager.getEnabledSchemas(context) +
                listOf("rime_ice", requireNotNull(japanese))).distinct())
            // Deliberately choose a different globe policy to prove shortcuts are independent.
            prefs.edit().putStringSet(LanguagePreferences.KEY, setOf("zh", "en", "ja"))
                .putString("input_language_order", "zh\nen\nja")
                .putString("selected_input_profile_zh", "rime_ice")
                .putString("selected_input_profile_ja", japanese)
                .putString(LanguageSwitchPreferences.MODE_KEY, LanguageSwitchMode.SPECIFIC_ENGLISH.id)
                .putString(LanguageSwitchPreferences.LANGUAGE_KEY, InputLanguage.JAPANESE.id)
                .putBoolean(HardwareKeyboardPreferences.PREFIX + "shift_tap", true)
                .putBoolean(HardwareKeyboardPreferences.PREFIX + "ctrl_space", true)
                .putBoolean(HardwareKeyboardPreferences.PREFIX + "dock_edge", true).commit()
            assertTrue(engine.switchSchema("rime_ice"))
            engine.clearComposition()
            engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            SettingsPreferences.setCurrentSchema(context, "rime_ice")
            shell("ime set ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            rule.setContent {
                AndroidView(factory = { ctx ->
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(80, 160, 80, 0)
                        editor = EditText(ctx).apply {
                            hint = "Hardware shortcuts — disposable verification editor"
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            minLines = 2
                        }
                        editorReady = true
                        addView(editor, LinearLayout.LayoutParams(-1, 220))
                    }
                }, modifier = Modifier.fillMaxSize())
            }
            rule.runOnUiThread {
                editor.requestFocus()
                editor.post {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.restartInput(editor)
                    imm.showSoftInput(editor, InputMethodManager.SHOW_FORCED)
                }
            }
            rule.waitUntil(20_000) { has("hardware-keyboard-toolbar") && languageIs("中") }
            shell("input keyboard keyevent KEYCODE_SHIFT_LEFT")
            awaitLanguage("EN")
            shell("input keyboard keyevent KEYCODE_SHIFT_RIGHT")
            awaitLanguage("中")
            shell("input keyboard keycombination KEYCODE_CTRL_LEFT KEYCODE_SPACE")
            awaitLanguage("EN")
            shell("input keyboard keycombination KEYCODE_CTRL_LEFT KEYCODE_SPACE")
            awaitLanguage("あ")
            assertEquals(japanese, engine.getCurrentSchema())
            shell("input keyboard keyevent KEYCODE_SHIFT_LEFT")
            awaitLanguage("EN")
            shell("input keyboard keyevent KEYCODE_SHIFT_LEFT")
            awaitLanguage("あ")
            assertEquals(japanese, engine.getCurrentSchema())
            shell("input keyboard keycombination KEYCODE_CTRL_LEFT KEYCODE_SPACE")
            awaitLanguage("中")
            assertEquals("Ctrl+Space must not insert spaces", "", document())
            assertEquals(LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, InputLanguage.JAPANESE),
                LanguageSwitchPreferences.read(context))

            shell("input keyboard keyevent KEYCODE_SHIFT_LEFT")
            awaitLanguage("EN")
            val dock = rule.onNodeWithTag("hardware-toolbar-drag", true).fetchSemanticsNode()
                .config[SemanticsActions.CustomActions].first { it.label == "停靠底部" }
            rule.runOnIdle { assertTrue(dock.action()) }
            shell("input keyboard keyevent KEYCODE_H KEYCODE_E KEYCODE_L")
            rule.waitUntil(10_000) { document() == "hel" && has("hardware-docked-candidates") && completion() != null }
            val word = requireNotNull(completion())
            assertTrue("Use a real bundled English completion", context.assets.open("english.txt").bufferedReader().useLines { lines ->
                lines.any { it.trim().equals(word, true) }
            })
            rule.waitForIdle()
            val density = context.resources.displayMetrics.density
            val before = rule.onNodeWithTag("hardware-keyboard-toolbar", true).fetchSemanticsNode().positionOnScreen.y
            rule.onNodeWithTag("hardware-toolbar-drag", true).performTouchInput {
                swipe(center, center - Offset(0f, 150f * density), durationMillis = 450)
            }
            rule.waitUntil(5_000) {
                val top = rule.onNodeWithTag("hardware-keyboard-toolbar", true).fetchSemanticsNode().positionOnScreen.y
                before - top > 100f * density && has("hardware-docked-candidates") && completion() == word
            }
            assertFalse("Floating English must not open an independent candidate card", has("hardware-candidate-card"))
            assertFalse("English must not display a pinyin card", has("hardware-preedit-card"))
            rule.onNodeWithTag("bar-candidate:1", true)
                .assert(hasAnyAncestor(hasTestTag("hardware-docked-candidates"))).assertIsDisplayed()
            screenshot("hardware-shortcuts-floating-english.png")
            rule.onNodeWithTag("bar-candidate:1", true).performClick()
            rule.waitUntil(10_000) { document() == word && !has("hardware-docked-candidates") }
            assertEquals("Selecting a completion must replace the pending prefix exactly once", word, document())

            // Space remains literal until an arrow explicitly selects an English suggestion.
            resetEditor("", "EN")
            shell("input keyboard keyevent KEYCODE_H KEYCODE_E KEYCODE_L")
            rule.waitUntil(10_000) { document() == "hel" && completion() != null }
            shell("input keyboard keyevent KEYCODE_SPACE")
            rule.waitUntil(10_000) { document() == "hel " }
            assertEquals("An ordinary English space must not accept a suggested completion", "hel ", document())
            shell("input keyboard keyevent KEYCODE_H KEYCODE_E KEYCODE_L")
            rule.waitUntil(10_000) { document() == "hel hel" && completion() != null }
            // Physical arrows belong to the editor, not English suggestions (covered in
            // HardwareArrowNavigationImeTest); keep one real caret check here.
            val caretAtEnd = selection()
            shell("input keyboard keyevent KEYCODE_DPAD_LEFT")
            rule.waitUntil(10_000) { selection() == (caretAtEnd.first - 1 to caretAtEnd.second - 1) }
            assertEquals("Editor arrows must not duplicate English text", "hel hel", document())
        } finally {
            if (editorReady) rule.runOnUiThread {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(editor.windowToken, 0)
                editor.clearFocus()
            }
            engine.clearComposition()
            if (savedSchema.isNotBlank()) engine.switchSchema(savedSchema)
            savedOptions.forEach { (key, value) -> engine.setOption(key, value) }
            engine.setUserConfigBool("var/option/ascii_mode", savedAscii)
            if (savedPrevious != null) engine.setUserConfigString("var/previously_selected_schema", savedPrevious)
            schemaFiles.forEach { (file, bytes) -> if (bytes == null) file.delete() else file.writeBytes(bytes) }
            val edit = prefs.edit()
            (prefs.all.keys.filter(::fixtureKey) + savedPrefs.keys).distinct().forEach { key -> restore(edit, key, savedPrefs[key]) }
            edit.commit()
            if (!oldIme.isNullOrBlank() && oldIme != "null") shell("ime set $oldIme")
        }
    }

    private fun restore(edit: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> edit.remove(key)
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
