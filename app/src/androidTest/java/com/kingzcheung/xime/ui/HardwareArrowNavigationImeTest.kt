package com.kingzcheung.xime.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.text.InputType
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

/** Actual hardware events, native candidates and host selection; no candidate-state injection. */
class HardwareArrowNavigationImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativePinyinNumbersSelectAndArrowsMoveEditorInBothSurfaces() = withIme("rime_ice") {
        verifyChineseNavigationInBothSurfaces()
    }

    @Test fun physicalLettersWithT9LayoutUseNumbersAndEditorArrowsInBothSurfaces() = withIme("t9_pinyin") {
        verifyChineseNavigationInBothSurfaces()
    }

    @Test fun englishSuggestionsNeverStealArrowsSpacesOrDigits() = withIme("rime_ice") {
        key("SHIFT_LEFT")
        rule.waitUntil(10_000) { engine.isAsciiMode() && languageIs("EN") }
        for (full in listOf(false, true)) {
            resetEditor("before ")
            surface(full)
            key("H", "E", "L")
            rule.waitUntil(10_000) { document() == "before hel" && englishCompletion() != null }
            key("DPAD_LEFT")
            rule.waitUntil(10_000) { selection() == (9 to 9) && englishCompletion() == null }
            assertEquals("before hel", document())
            key("SPACE")
            rule.waitUntil(10_000) { document() == "before he l" }

            resetEditor("before ")
            surface(full)
            key("H", "E", "L")
            rule.waitUntil(10_000) { document() == "before hel" }
            key("1", "2", "3", "SPACE")
            rule.waitUntil(10_000) { document() == "before hel123 " }
            key("ESCAPE", "ESCAPE", "H", "E", "L")
            rule.waitUntil(10_000) { document() == "before hel123 hel" }
            assertTrue("Repeated Esc must not hide the input surface", has("hardware-keyboard-toolbar") || has("keyboard-underlay"))
            // Esc may dismiss the soft/IME focus while leaving the compact surface alive;
            // restore the editor so this scenario tests suggestions vs. DEL/arrows, not Esc focus.
            if (!focused()) {
                rule.runOnUiThread { editor.requestFocus() }
                rule.waitUntil(10_000) { focused() }
            }
            key("DEL", "DEL", "DEL", "DPAD_LEFT")
            rule.waitUntil(10_000) { document() == "before hel123 " && selection() == (13 to 13) }
        }
    }

    @Test fun shiftPunctuationIsLiteralAndDoesNotSwitchLanguage() = withIme("rime_ice") {
        for (english in listOf(false, true)) {
            if (english) {
                key("SHIFT_LEFT")
                rule.waitUntil(10_000) { engine.isAsciiMode() && languageIs("EN") }
            }
            resetEditor("")
            for (name in listOf("SLASH", "SEMICOLON", "APOSTROPHE", "BACKSLASH", "GRAVE")) {
                shell("input keycombination KEYCODE_SHIFT_LEFT KEYCODE_$name")
            }
            rule.waitUntil(10_000) { document() == "?:\"|~" }
            assertEquals(english, engine.isAsciiMode())
        }
    }

    @Test fun sideDockHiddenEnglishSuggestionsDoNotStealEditorArrows() = withIme("rime_ice") {
        key("SHIFT_LEFT")
        rule.waitUntil(10_000) { engine.isAsciiMode() && languageIs("EN") }
        resetEditor("before ")
        toolbarAction("靠左")
        rule.waitUntil(5_000) {
            val node = rule.onNodeWithTag("hardware-keyboard-toolbar", true).fetchSemanticsNode()
            node.size.height > node.size.width && !has("hardware-docked-candidates")
        }
        key("H", "E", "L")
        rule.waitUntil(10_000) { document() == "before hel" }
        assertFalse("Side docking intentionally has no suggestion row", has("hardware-docked-candidates"))
        assertFalse("English does not create an independent candidate popup", has("hardware-candidate-card"))
        key("DPAD_LEFT")
        rule.waitUntil(10_000) { selection() == (9 to 9) }
        key("DPAD_RIGHT")
        rule.waitUntil(10_000) { selection() == (10 to 10) }
        assertEquals("Invisible suggestions must not consume arrows or alter host text", "before hel", document())
        key("SPACE")
        rule.waitUntil(10_000) { document() == "before hel " }
    }

    private inner class Fixture(val context: Context, val engine: RimeEngine, val editor: EditText) {
        fun has(tag: String) = rule.onAllNodesWithTag(tag, true).fetchSemanticsNodes().isNotEmpty()
        fun languageIs(label: String) = rule.onAllNodesWithTag("hardware-toolbar-language", true)
            .fetchSemanticsNodes().any { node ->
                SemanticsProperties.ContentDescription in node.config &&
                    node.config[SemanticsProperties.ContentDescription].any { it.startsWith("当前语言：$label，") }
            }
        fun document(): String {
            var text = ""
            rule.runOnUiThread { text = editor.text.toString() }
            return text
        }
        fun selection(): Pair<Int, Int> {
            var result = -1 to -1
            rule.runOnUiThread { result = editor.selectionStart to editor.selectionEnd }
            return result
        }
        fun focused(): Boolean {
            var result = false
            rule.runOnUiThread { result = editor.hasFocus() }
            return result
        }
        fun key(vararg names: String) { shell("input keyboard keyevent " + names.joinToString(" ") { "KEYCODE_$it" }) }
        fun selected(index: Int) = rule.onAllNodesWithTag("bar-candidate:$index", true)
            .fetchSemanticsNodes().any { node ->
                SemanticsProperties.Selected in node.config && node.config[SemanticsProperties.Selected]
            }
        private val candidateNode = SemanticsMatcher("candidate row item") { node ->
            val tag = if (SemanticsProperties.TestTag in node.config) node.config[SemanticsProperties.TestTag] else ""
            tag.startsWith("bar-candidate:") || tag.startsWith("bar-association:")
        }
        fun selectedText(text: String) = rule.onAllNodes(candidateNode and hasText(text) and isSelected())
            .fetchSemanticsNodes().isNotEmpty()
        fun candidate(index: Int): String = rule.onNodeWithTag("bar-candidate:$index")
            .fetchSemanticsNode().config[SemanticsProperties.Text].first().text.removePrefix("${(index + 1) % 10} ")
        // Read an actual completion, excluding the raw prefix item.
        fun englishCompletion(): String? = rule.onAllNodes(
            hasTestTag("bar-candidate:1") or hasTestTag("bar-association:1"))
            .fetchSemanticsNodes().mapNotNull { node ->
                if (SemanticsProperties.Text in node.config) node.config[SemanticsProperties.Text].firstOrNull()?.text else null
            }.firstOrNull { it.startsWith("hel", true) && it.length > 3 }

        fun resetEditor(text: String, cursor: Int = text.length) {
            key("ESCAPE")
            rule.waitUntil(10_000) { engine.getInput().isEmpty() }
            rule.runOnUiThread {
                editor.setText(text)
                editor.setSelection(cursor)
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(editor)
            }
            rule.waitUntil(10_000) { document() == text && selection() == (cursor to cursor) && engine.getInput().isEmpty() }
            rule.waitForIdle()
        }
        fun toolbarAction(label: String) {
            val action = rule.onNodeWithTag("hardware-toolbar-drag", true).fetchSemanticsNode()
                .config[SemanticsActions.CustomActions].first { it.label == label }
            rule.runOnIdle { assertTrue(action.action()) }
        }
        fun surface(full: Boolean) {
            if (full) {
                if (has("hardware-toolbar-keyboard")) rule.onNodeWithTag("hardware-toolbar-keyboard", true).performClick()
                rule.waitUntil(10_000) { has("keyboard-underlay") && !has("hardware-keyboard-toolbar") }
            } else {
                if (!has("hardware-keyboard-toolbar")) key("BACK")
                rule.waitUntil(10_000) { has("hardware-keyboard-toolbar") }
                toolbarAction("停靠底部")
                rule.waitForIdle()
                val before = rule.onNodeWithTag("hardware-keyboard-toolbar", true).fetchSemanticsNode().positionOnScreen.y
                val dy = 150 * context.resources.displayMetrics.density
                rule.onNodeWithTag("hardware-toolbar-drag", true).performTouchInput {
                    swipe(center, center - Offset(0f, dy), durationMillis = 400)
                }
                rule.waitUntil(5_000) {
                    before - rule.onNodeWithTag("hardware-keyboard-toolbar", true).fetchSemanticsNode().positionOnScreen.y > dy / 2
                }
            }
        }

        fun verifyChineseNavigationInBothSurfaces() {
            val original = "第一行正文\n第二行正文\n第三行正文"
            val cursor = original.indexOf("第二") + 3
            for (full in listOf(false, true)) {
                resetEditor(original, cursor)
                surface(full)
                key("N", "I")
                rule.waitUntil(15_000) { engine.getInput().isNotEmpty() && has("bar-candidate:1") }
                val selectedWord = candidate(1)
                assertTrue(selectedWord.any { it.code in 0x4E00..0x9FFF })
                key("2")
                val expected = original.substring(0, cursor) + selectedWord + original.substring(cursor)
                rule.waitUntil(10_000) { engine.getInput().isEmpty() && document() == expected }
                key("DPAD_LEFT")
                val left = cursor + selectedWord.length - 1
                rule.waitUntil(10_000) { selection() == (left to left) }
                assertEquals("Cursor movement must preserve committed text", expected, document())

                resetEditor(original, cursor)
                surface(full)
                key("N", "I", "DPAD_LEFT")
                // While pinyin is composing, physical arrows navigate the composition
                // (Samsung/Gboard convention), never committing raw letters or moving
                // the host caret. The committed arrow case is covered above.
                rule.waitUntil(10_000) { engine.getInput() == "ni" && document() == original && selection() == (cursor to cursor) }
                key("DPAD_UP")
                rule.waitForIdle()
                assertEquals(original, document())
                assertEquals(cursor to cursor, selection())

                resetEditor(original, cursor)
                surface(full)
                key("N", "I", "ESCAPE", "ESCAPE", "N", "I")
                rule.waitUntil(15_000) { engine.getInput().isNotEmpty() && has("bar-candidate:0") }
                assertEquals("Esc cancels raw input and typing immediately restores visible candidates", original, document())
                key("ESCAPE", "1", "2", "3")
                val digits = original.substring(0, cursor) + "123" + original.substring(cursor)
                rule.waitUntil(10_000) { document() == digits && engine.getInput().isEmpty() }

                resetEditor(original, 0)
                surface(full)
                // Toolbar interactions may move window focus in the disposable activity;
                // this scenario is specifically about arrows, so focus the editor first.
                rule.runOnUiThread { editor.requestFocus() }
                rule.waitUntil(10_000) { focused() }
                key("DPAD_LEFT", "DPAD_LEFT", "DPAD_UP", "DPAD_UP")
                rule.waitForIdle()
                assertEquals(0 to 0, selection())
                assertTrue("Arrows at text edges must not transfer focus", focused())
                key("DPAD_DOWN")
                rule.waitUntil(10_000) { selection() == (6 to 6) }
                assertEquals(original, document())
            }
        }
    }

    private fun withIme(schema: String, block: Fixture.() -> Unit): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Connect a physical keyboard; a skipped test is not device validation",
            context.resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS)
        val prefs = SettingsPreferences.getPrefsPublic(context)
        fun fixtureKey(key: String) = key.startsWith(HardwareKeyboardPreferences.PREFIX) ||
            key.startsWith("current_schema") || key.startsWith("last_input_mode_") ||
            key.startsWith("selected_input_profile_") || key == LanguagePreferences.KEY ||
            key == "input_language_order" || key == "input_text_location" || key == "space_commit_association"
        val savedPrefs = prefs.all.filterKeys(::fixtureKey).mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
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
        var ready = false
        try {
            assertTrue("The requested real schema must already be compiled: $schema", SchemaManager.isSchemaCompiled(context, schema))
            SchemaManager.setEnabledSchemas(context, (SchemaManager.getEnabledSchemas(context) + schema).distinct())
            prefs.edit().putStringSet(LanguagePreferences.KEY, setOf("zh", "en"))
                .putString("input_language_order", "zh\nen")
                .putString("selected_input_profile_zh", schema)
                .putString("input_text_location", SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR)
                .putBoolean("space_commit_association", false)
                .putBoolean(HardwareKeyboardPreferences.PREFIX + "shift_tap", true)
                .putBoolean(HardwareKeyboardPreferences.PREFIX + "dock_edge", true).commit()
            assertTrue(engine.switchSchema(schema))
            engine.clearComposition()
            engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            SettingsPreferences.setCurrentSchema(context, schema)
            shell("ime set ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            rule.setContent {
                AndroidView(factory = { ctx ->
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(80, 140, 80, 0)
                        editor = EditText(ctx).apply {
                            hint = "Disposable hardware arrow test editor"
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            minLines = 3
                        }
                        ready = true
                        addView(editor, LinearLayout.LayoutParams(-1, 280))
                        addView(android.widget.Button(ctx).apply { text = "Focus must stay in the editor" })
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
            val fixture = Fixture(context, engine, editor)
            // A live service preserves an explicitly opened screen keyboard across editors
            // and test activities. Start each scenario from the real compact toolbar.
            rule.waitUntil(20_000) { fixture.has("hardware-keyboard-toolbar") || fixture.has("keyboard-underlay") }
            if (!fixture.has("hardware-keyboard-toolbar")) fixture.key("BACK")
            rule.waitUntil(20_000) { fixture.has("hardware-keyboard-toolbar") && fixture.languageIs("中") }
            fixture.block()
        } finally {
            if (ready) rule.runOnUiThread {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor.windowToken, 0)
                editor.clearFocus()
            }
            engine.clearComposition()
            if (savedSchema.isNotBlank()) engine.switchSchema(savedSchema)
            savedOptions.forEach { (key, value) -> engine.setOption(key, value) }
            engine.setUserConfigBool("var/option/ascii_mode", savedAscii)
            if (savedPrevious != null) engine.setUserConfigString("var/previously_selected_schema", savedPrevious)
            schemaFiles.forEach { (file, bytes) -> if (bytes == null) file.delete() else file.writeBytes(bytes) }
            val edit = prefs.edit()
            (prefs.all.keys.filter(::fixtureKey) + savedPrefs.keys).distinct().forEach { restore(edit, it, savedPrefs[it]) }
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
