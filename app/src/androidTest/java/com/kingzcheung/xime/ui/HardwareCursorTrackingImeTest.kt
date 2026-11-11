package com.kingzcheung.xime.ui

import android.content.Context
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.service.XimeInputMethodService
import com.kingzcheung.xime.settings.HardwareKeyboardPreferences
import com.kingzcheung.xime.settings.SettingsPreferences
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real editor-to-IME cursor reporting, with a connected physical keyboard. No user document is edited. */
class HardwareCursorTrackingImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeEditorCaretAndScrolledEditorMoveTheRealCandidateWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS)
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val density = context.resources.displayMetrics.density
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val optionKeys = listOf("dock_edge", "follow_cursor", "avoid_editor")
            .map { HardwareKeyboardPreferences.PREFIX + it }
        val savedOptions = optionKeys.associateWith { prefs.all[it] }
        val engine = RimeEngine.getInstance()
        var previousSchema = ""
        var previousAscii = false
        var previousSavedAscii = false
        lateinit var editor: EditText
        try {
            assertTrue("The real engine must be prepared", kotlinx.coroutines.runBlocking { RimeConfigHelper.prepareEngine(context) })
            previousSchema = engine.getCurrentSchema()
            previousAscii = engine.isAsciiMode()
            previousSavedAscii = engine.getUserConfigBool("var/option/ascii_mode")
            engine.clearComposition()
            assertTrue(engine.switchSchema("rime_ice"))
            engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            SettingsPreferences.setCurrentSchema(context, "rime_ice")
            // This scenario tracks the floating caret window; the default bottom dock
            // renders candidates inside the toolbar instead.
            prefs.edit()
                .putBoolean(HardwareKeyboardPreferences.PREFIX + "dock_edge", false)
                .putBoolean(HardwareKeyboardPreferences.PREFIX + "follow_cursor", true)
                .putBoolean(HardwareKeyboardPreferences.PREFIX + "avoid_editor", true)
                .commit()
            shell("ime enable $ime")
            shell("ime set $ime")
            rule.setContent {
                AndroidView(factory = { ctx ->
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(80, 140, 80, 0)
                        editor = EditText(ctx).apply {
                            setText("Hardware cursor sample")
                            setSingleLine(true)
                        }
                        addView(editor, LinearLayout.LayoutParams(-1, 120))
                    }
                }, modifier = Modifier.fillMaxSize())
            }
            rule.runOnUiThread {
                editor.requestFocus()
                editor.setSelection(2)
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                editor.post {
                    imm.restartInput(editor)
                    imm.showSoftInput(editor, InputMethodManager.SHOW_FORCED)
                }
            }
            rule.waitUntil(30_000) { rule.onAllNodesWithTag("hardware-keyboard-toolbar").fetchSemanticsNodes().isNotEmpty() }
            var service: XimeInputMethodService? = null
            rule.waitUntil(30_000) {
                rule.runOnUiThread {
                    service = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findService)
                }
                service?.keyboardCallbacks != null && service?.inputReadiness?.ticket() != null &&
                    service?.uiState?.value?.isDeploying == false
            }
            fun editorFocused(): Boolean {
                var focused = false
                rule.runOnUiThread { focused = editor.hasFocus() }
                return focused
            }
            rule.waitUntil(30_000) {
                service?.uiState?.value?.currentSchemaId == "rime_ice" &&
                    service?.uiState?.value?.isDeploying == false
            }
            rule.waitUntil(10_000) { engine.getInput().isEmpty() }
            rule.runOnUiThread { editor.requestFocus() }
            rule.waitUntil(20_000) { editorFocused() }
            rule.runOnUiThread {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.restartInput(editor)
                imm.showSoftInput(editor, InputMethodManager.SHOW_FORCED)
            }
            rule.waitUntil(30_000) { RimeEngine.isInitialized() && engine.getCurrentSchema().isNotEmpty() }
            shell("input keyboard keyevent KEYCODE_N KEYCODE_I")
            rule.waitUntil(30_000) { rule.onAllNodesWithTag("hardware-candidate-card", true).fetchSemanticsNodes().isNotEmpty() }
            rule.waitForIdle()
            fun card() = rule.onNodeWithTag("hardware-candidate-card", true).fetchSemanticsNode().positionOnScreen
            val first = card()
            val location = IntArray(2)
            rule.runOnUiThread { editor.getLocationOnScreen(location) }
            assertTrue("Candidate must be near the editor, not bottom-center: $first", first.y < location[1] + editor.height + 64 * density)
            rule.runOnUiThread { editor.translationY += 160f }
            rule.waitUntil(10_000) { kotlin.math.abs(card().y - first.y - 160f) < 4f }
            val moved = card()
            rule.runOnUiThread { editor.setSelection(18) }
            rule.waitForIdle()
            rule.waitUntil(10_000) { card().x > moved.x + 40f }
            fun document(): String {
                var text = ""
                rule.runOnUiThread { text = editor.text.toString() }
                return text
            }
            fun candidates() = engine.getCandidates().toList()
            val composing = engine.getInput()
            val originalText = document()
            assertTrue("Paging fixture needs an active composition", composing.isNotEmpty())
            val firstPage = candidates()
            assertTrue("Paging fixture needs multiple pages", engine.hasNextPage())
            fun compactWindowStart(): Int = service?.let {
                it.keyRouter.hardwareWindowStart(it.candidateState.value)
            } ?: -1
            val compact = service?.uiState?.value?.isCompact == true
            for ((next, previous) in listOf(
                "keyevent KEYCODE_EQUALS" to "keyevent KEYCODE_MINUS",
                "keyevent KEYCODE_RIGHT_BRACKET" to "keyevent KEYCODE_LEFT_BRACKET",
                "keyevent KEYCODE_PERIOD" to "keyevent KEYCODE_COMMA",
                "keycombination KEYCODE_SHIFT_LEFT KEYCODE_PERIOD" to "keycombination KEYCODE_SHIFT_LEFT KEYCODE_COMMA",
            )) {
                val windowBefore = compactWindowStart()
                shell("input keyboard $next")
                android.os.SystemClock.sleep(150)
                if (compact) {
                    // Compact hardware mode pages the visible ten-word window before the engine page.
                    rule.waitUntil(10_000) { compactWindowStart() != windowBefore && engine.getInput() == composing }
                    assertEquals(composing, engine.getInput())
                    shell("input keyboard $previous")
                    android.os.SystemClock.sleep(150)
                    rule.waitUntil(10_000) { compactWindowStart() == windowBefore && engine.getInput() == composing }
                } else {
                    rule.waitUntil(10_000) { candidates() != firstPage && engine.hasPrevPage() }
                    assertEquals(composing, engine.getInput())
                    shell("input keyboard $previous")
                    android.os.SystemClock.sleep(150)
                    rule.waitUntil(10_000) { candidates() == firstPage && !engine.hasPrevPage() }
                }
                assertEquals(originalText, document())
            }
            // First-page boundaries still consume every previous-page key.
            shell("input keyboard keyevent KEYCODE_MINUS KEYCODE_LEFT_BRACKET KEYCODE_COMMA")
            android.os.SystemClock.sleep(200)
            assertEquals(composing, engine.getInput())
            assertEquals(firstPage, candidates())
            assertEquals(originalText, document())
            repeat(composing.length) { shell("input keyboard keyevent KEYCODE_DEL") }
            rule.waitUntil(10_000) { engine.compositionActiveForDeletion() == false && engine.getInput().isEmpty() }
            rule.runOnUiThread { editor.setText(""); editor.setSelection(0); editor.requestFocus() }
            rule.waitUntil(10_000) { editorFocused() && engine.getInput().isEmpty() }
            android.os.SystemClock.sleep(250)
            shell("input keyboard keyevent KEYCODE_N KEYCODE_I")
            rule.waitUntil(10_000) { engine.getInput() == "ni" }
            shell("input keyboard keyevent KEYCODE_ENTER")
            try {
                rule.waitUntil(10_000) { engine.compositionActiveForDeletion() == false && document() == "ni" }
            } catch (failure: Throwable) {
                throw AssertionError(
                    "ni+Enter must commit raw text: input=${engine.getInput()} text=${document()} " +
                        "schema=${engine.getCurrentSchema()} ascii=${engine.isAsciiMode()} " +
                        "stored=${SettingsPreferences.getCurrentSchema(context)}", failure)
            }
            assertEquals("ni", document())
            // Only an empty composition permits literal punctuation, including shifted < and >.
            for (key in listOf("KEYCODE_MINUS", "KEYCODE_EQUALS", "KEYCODE_LEFT_BRACKET", "KEYCODE_RIGHT_BRACKET", "KEYCODE_COMMA", "KEYCODE_PERIOD")) {
                val before = document()
                shell("input keyboard keyevent $key")
                rule.waitUntil(10_000) { document() != before }
            }
            for (key in listOf("KEYCODE_COMMA", "KEYCODE_PERIOD")) {
                val before = document()
                shell("input keyboard keycombination KEYCODE_SHIFT_LEFT $key")
                rule.waitUntil(10_000) { document() != before }
            }
            rule.onNodeWithTag("hardware-toolbar-keyboard").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("hardware-keyboard-toolbar").fetchSemanticsNodes().isEmpty() }
        } finally {
            engine.clearComposition()
            if (previousSchema.isNotBlank()) engine.switchSchema(previousSchema)
            engine.setOption("ascii_mode", previousAscii)
            engine.setUserConfigBool("var/option/ascii_mode", previousSavedAscii)
            shell("input keyevent KEYCODE_ESCAPE")
            prefs.edit().also { edit ->
                savedOptions.forEach { (key, value) ->
                    when (value) {
                        null -> edit.remove(key)
                        is Boolean -> edit.putBoolean(key, value)
                        is Int -> edit.putInt(key, value)
                        is Float -> edit.putFloat(key, value)
                        is Long -> edit.putLong(key, value)
                        is String -> edit.putString(key, value)
                        is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }.commit()
            if (!oldIme.isNullOrBlank()) shell("ime set $oldIme")
        }
    }

    private fun findService(view: View): XimeInputMethodService? {
        var current: Context? = view.context
        while (current != null) {
            if (current is XimeInputMethodService) return current
            current = (current as? android.content.ContextWrapper)?.baseContext?.takeUnless { it === current }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) findService(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }
}
