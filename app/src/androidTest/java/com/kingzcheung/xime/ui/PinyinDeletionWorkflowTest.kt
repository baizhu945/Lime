package com.kingzcheung.xime.ui

import android.content.Context
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
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real IME/editor regression. Run only on the isolated emulator. */
class PinyinDeletionWorkflowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun deletingAndClearingCompositionNeverLeavesStaleLettersOrDeletesDocument(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val saved = listOf("floating_mode", "keyboard_height_dp", "keyboard_bottom_padding_dp",
            "current_schema", "current_schema_dual", "neighbor_correction", "reverse_symbol_swipe").associateWith { prefs.all[it] }
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val wasEnabled = shell("ime list -s").lineSequence().any { it == ime }
        val engine = RimeEngine.getInstance()
        var schema = ""
        var ascii = false
        var savedAscii = false
        lateinit var editor: EditText
        try {
            assertTrue(RimeConfigHelper.prepareEngine(context))
            assertTrue(engine.ensureSession())
            schema = engine.getCurrentSchema()
            ascii = engine.isAsciiMode()
            savedAscii = engine.getUserConfigBool("var/option/ascii_mode")
            SettingsPreferences.setKeyboardHeightDp(context, 300)
            SettingsPreferences.setKeyboardBottomPaddingDp(context, 0)
            SettingsPreferences.setFloatingMode(context, false)
            SettingsPreferences.setCurrentSchema(context, "rime_ice")
            // This workflow explicitly exercises physical up = the delete key's top action.
            prefs.edit().putBoolean("neighbor_correction", true)
                .putBoolean("reverse_symbol_swipe", true).commit()
            assertTrue(engine.switchSchema("rime_ice"))
            engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            shell("ime enable $ime")
            shell("ime set $ime")
            rule.setContent {
                AndroidView(factory = { EditText(it).also { view ->
                    view.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    view.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
                    editor = view; view.setText("正文"); view.setSelection(view.text.length)
                } }, modifier = Modifier.fillMaxWidth().height(80.dp))
            }
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            rule.runOnUiThread { editor.requestFocus(); imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            var expandedHardwareToolbar = false
            rule.waitUntil(30000) {
                val ready = rule.onAllNodesWithTag("qwerty-key:g").fetchSemanticsNodes().isNotEmpty()
                val expand = rule.onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true)
                if (!ready && !expandedHardwareToolbar && expand.fetchSemanticsNodes().isNotEmpty()) {
                    expandedHardwareToolbar = true
                    expand.onFirst().performClick()
                }
                ready
            }
            fun type() {
                for (letter in "ggd") rule.onNodeWithTag("qwerty-key:$letter").performTouchInput { click() }
                rule.waitUntil(5000) { engine.getInput() == "ggd" }
                rule.waitUntil(5000) { rule.onAllNodesWithTag("candidate-preedit").fetchSemanticsNodes().isNotEmpty() }
            }
            fun document(): String { var text = ""; rule.runOnUiThread { text = editor.text.toString() }; return text }
            // Raw pinyin confirmation consumes one Enter. Only a second Enter adds a newline.
            for (letter in "hello") rule.onNodeWithTag("qwerty-key:$letter").performTouchInput { click() }
            rule.waitUntil(5000) { engine.getInput() == "hello" }
            rule.onNodeWithTag("qwerty-enter-key").performTouchInput { click() }
            rule.waitUntil(5000) { engine.getInput().isEmpty() }
            assertEquals("正文hello", document())
            rule.onNodeWithTag("qwerty-enter-key").performTouchInput { click() }
            rule.waitUntil(5000) { document() == "正文hello\n" }
            // Engine input can precede candidate UI publication. It is still input,
            // never permission to send a newline to the editor.
            assertTrue(engine.setInput("world"))
            rule.onNodeWithTag("qwerty-enter-key").performTouchInput { click() }
            rule.waitUntil(5000) { document() == "正文hello\nworld" }
            rule.waitUntil(5000) { engine.getInput().isEmpty() }
            rule.runOnUiThread { editor.setText("正文"); editor.setSelection(editor.text.length) }
            type()
            for (remaining in listOf("gg", "g", "")) {
                rule.onNodeWithTag("qwerty-delete-key").performTouchInput { click() }
                rule.waitUntil(5000) { engine.getInput() == remaining }
            }
            rule.waitUntil(5000) { rule.onAllNodesWithTag("candidate-preedit").fetchSemanticsNodes().isEmpty() }
            assertEquals("正文", document())
            // Measured keycaps must reach the native corrector through production callbacks.
            for (letter in "nihso") {
                // The bottom letter row shares space with Shift/Delete and has no row-key tags.
                val key = if (letter == 'n') rule.onNodeWithText("n") else rule.onNodeWithTag("qwerty-key:$letter")
                key.performTouchInput { click() }
            }
            rule.waitUntil(5000) { engine.getInput() == "nihso" }
            assertTrue("measured software keyboard must enable adjacent s→a", engine.getAllCandidates(300).any { it.text == "你好" })
            for (letter in "nihso") rule.onNodeWithTag("qwerty-delete-key").performTouchInput { click() }
            rule.waitUntil(5000) { engine.getInput().isEmpty() }
            type()
            rule.onNodeWithTag("candidate-expansion").performTouchInput { click() }
            rule.waitUntil(5000) { rule.onAllNodesWithTag("expanded-delete-key").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("expanded-enter-key").performTouchInput { click() }
            rule.waitUntil(5000) { engine.getInput().isEmpty() }
            assertEquals("正文ggd", document())
            rule.runOnUiThread { editor.setText("正文"); editor.setSelection(editor.text.length) }
            type()
            rule.onNodeWithTag("candidate-expansion").performTouchInput { click() }
            rule.waitUntil(5000) { rule.onAllNodesWithTag("expanded-delete-key").fetchSemanticsNodes().isNotEmpty() }
            for (remaining in listOf("gg", "g", "")) {
                rule.onNodeWithTag("expanded-delete-key").performTouchInput { click() }
                rule.waitUntil(5000) { engine.getInput() == remaining }
                if (remaining.isNotEmpty()) {
                    rule.onNodeWithTag("expanded-delete-key").assertExists("Deleting to $remaining must keep the candidate page open")
                }
            }
            rule.waitUntil(5000) { rule.onAllNodesWithTag("qwerty-delete-key").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("正文", document())
            type()
            val density = context.resources.displayMetrics.density
            rule.onNodeWithTag("qwerty-delete-key").performTouchInput {
                down(center); moveTo(center - Offset(0f, 90f * density), 150); up()
            }
            rule.waitUntil(5000) { engine.getInput().isEmpty() }
            rule.waitUntil(5000) { rule.onAllNodesWithTag("candidate-preedit").fetchSemanticsNodes().isEmpty() }
            assertEquals("正文", document())
        } finally {
            engine.setNeighborMap("")
            engine.clearQueuedComposition()
            if (schema.isNotEmpty()) {
                engine.switchSchema(schema); engine.setOption("ascii_mode", ascii)
                engine.setUserConfigBool("var/option/ascii_mode", savedAscii)
            }
            prefs.edit().also { edit -> saved.forEach { (key, value) -> when (value) {
                null -> edit.remove(key)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is String -> edit.putString(key, value)
            } } }.commit()
            if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
            if (!wasEnabled) shell("ime disable $ime")
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
