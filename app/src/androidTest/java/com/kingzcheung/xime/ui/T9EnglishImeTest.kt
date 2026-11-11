package com.kingzcheung.xime.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Dedicated test device: real keyboard -> candidate click -> InputConnection -> learning. */
class T9EnglishImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeCandidatesLearnAndIdleSentencesReachTheRealKeyboard(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val engine = RimeEngine.getInstance()
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val previous = engine.getCurrentSchema()
        fun shell(command: String): String =
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
        val previousIme = shell("settings get secure default_input_method").trim()
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val wasEnabled = shell("ime list -s").lineSequence().any { it.trim() == ime }
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val saved = listOf("current_schema", "current_schema_dual", "last_input_mode_zh",
            "floating_mode", "input_text_location").associateWith { prefs.all[it] }
        val previousAscii = engine.isAsciiMode()
        val previousSavedAscii = engine.getUserConfigBool("var/option/ascii_mode")
        val optionalCompletion = OptionalDigitSuffixes(context, engine)
        var primaryFailure: Throwable? = null
        try {
            optionalCompletion.enable()
            SettingsPreferences.setFloatingMode(context, false)
            SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR)
            shell("ime enable $ime"); shell("ime set $ime")
            lateinit var editor: EditText
            rule.setContent {
                AndroidView(factory = { EditText(it).also { editor = it; it.hint = "T9 English regression" } },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val deadline = SystemClock.uptimeMillis() + 30_000
            fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
            rule.awaitRealImeInputReady(editor, imm, engine, remaining())
            var expanded = false
            rule.waitUntil(remaining()) {
                val toggle = rule.onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true)
                if (!expanded && toggle.fetchSemanticsNodes().isNotEmpty()) {
                    expanded = true
                    toggle.onFirst().performClick()
                }
                rule.onAllNodesWithTag("language-key-control", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.chooseModeThroughLanguageAndPanel("t9_pinyin")
            val labels = mapOf('2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ")
            labels.values.forEach { rule.onNodeWithText(it).assertIsDisplayed() }
            val sentenceKeys = "48452474687445394942"
            sentenceKeys.forEach { key -> rule.onNodeWithText(labels.getValue(key)).performTouchInput { down(center); up() } }
            // Observe the production controller's idle update, without calling the
            // test-only await/refine helper or replacing the native candidate list.
            val sentence = "回来收拾了一下"
            val numbered = context.resources.configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
            val displayedSentence = if (numbered) "1 $sentence" else sentence
            rule.waitUntil(10_000) {
                rule.onAllNodes(hasTestTag("bar-candidate:0") and hasText(displayedSentence))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(sentence, engine.inspectCandidates(1).first()[0])
            assertEquals(sentenceKeys, engine.getInput())
            rule.runOnUiThread { assertEquals("", editor.text.toString()) }
            instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
                File(context.getExternalFilesDir(null), "t9-idle-refinement-ime.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            engine.clearQueuedT9Composition()
            for ((word, keys) in listOf("steam" to "78326", "Dota2" to "36822", "CS2" to "272")) {
                var firstRank = -1
                repeat(2) { attempt ->
                    keys.forEach { key -> rule.onNodeWithText(labels.getValue(key)).performTouchInput { down(center); up() } }
                    rule.waitUntil(10_000) { engine.getInput() == keys }
                    rule.waitForIdle()
                    val rows = engine.inspectCandidates(200)
                    val rank = rows.indexOfFirst { it[0] == word }
                    assertTrue("$word is recalled", rank >= 0)
                    if (attempt == 0) firstRank = rank
                    else {
                        assertTrue("$word improves after a real host commit", rank <= firstRank)
                        assertEquals("user_table", rows[rank][2])
                    }
                    val bar = rule.onNodeWithTag("bar-candidate:$rank")
                    if (!bar.isDisplayed()) {
                        rule.onNodeWithTag("candidate-expansion").performClick()
                        rule.waitForIdle()
                    }
                    // Expansion removes shortcut labels, so the exact rendered prefix may grow.
                    // Those same native indices stay in the bar and are not duplicated in the grid.
                    if (bar.isDisplayed()) bar.performClick()
                    else {
                        rule.onNodeWithTag("expanded-candidates").performScrollToNode(hasTestTag("expanded-candidate:$rank"))
                        rule.onNodeWithTag("expanded-candidate:$rank").performTouchInput { down(center); up() }
                    }
                    rule.waitUntil(10_000) {
                        var accepted = false
                        rule.runOnUiThread { accepted = editor.text.toString() == word }
                        accepted && engine.getInput().isEmpty()
                    }
                    rule.runOnUiThread { assertEquals(word, editor.text.toString()); editor.setText("") }
                }
            }
            instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
                File(context.getExternalFilesDir(null), "t9-english-ime.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = failure else first.addSuppressed(failure)
                }
            }
            cleanup {
                File(context.getExternalFilesDir(null), "t9-ime-state.txt").writeText(
                    "input=${engine.getInput()}\nscoring=${engine.t9ScoringStatus()}\nrefinement=${engine.t9RefinementState()}\n" +
                        engine.inspectCandidates(20).joinToString("\n") { it.joinToString(" | ") })
                instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
                    File(context.getExternalFilesDir(null), "t9-ime-final.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            // Restore the compiled option while the real IME still owns a live engine.
            // Evidence failures must not leave a temporary configuration or skip other cleanup.
            cleanup { optionalCompletion.close() }
            cleanup {
                engine.clearQueuedT9Composition()
                engine.switchSchema(previous)
                engine.setOption("ascii_mode", previousAscii)
                engine.setUserConfigBool("var/option/ascii_mode", previousSavedAscii)
            }
            cleanup {
                prefs.edit().also { edit -> saved.forEach { (key, value) -> when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    else -> edit.remove(key)
                } } }.commit()
            }
            cleanup { if (previousIme.isNotEmpty() && previousIme != "null") shell("ime set $previousIme") }
            cleanup { if (!wasEnabled) shell("ime disable $ime") }
            cleanupFailure?.let { failure -> primaryFailure?.addSuppressed(failure) ?: throw failure }
        }
    }

    /** Opt into the same real, optional translator path as T9EnglishRegressionTest. */
    private class OptionalDigitSuffixes(context: Context, private val engine: RimeEngine) : AutoCloseable {
        private val file = File(context.filesDir, "rime/t9_pinyin.custom.yaml")
        private val original = file.takeIf { it.isFile }?.readBytes()
        private val previousEffective = engine.getSchemaString("t9_pinyin", "t9_english/digit_suffixes")
        private var changed = false

        fun enable() {
            if (previousEffective == "true") return
            val text = original?.toString(Charsets.UTF_8).orEmpty()
            // Use the existing additive custom-patch writer, retaining all user/cell-dictionary entries.
            val manager = com.kingzcheung.xime.settings.PersonalDictManager
            val insert = manager::class.java.getDeclaredMethod("insertUnderPatch", String::class.java, String::class.java)
                .apply { isAccessible = true }
            val patched = insert.invoke(manager, text, "  \"t9_english/digit_suffixes\": true\n") as String
            com.kingzcheung.xime.settings.SchemaManager.yaml.parseToYamlNode(patched)
            changed = true
            file.writeText(patched)
            assertTrue("Deploy optional digit suffixes through the real engine", engine.deployIncremental())
            assertEquals("true", engine.getSchemaString("t9_pinyin", "t9_english/digit_suffixes"))
        }

        override fun close() {
            if (!changed) return
            if (original == null) assertTrue("Remove only this fixture's newly created custom patch", file.delete())
            else file.writeBytes(original)
            assertTrue("Redeploy the restored original configuration", engine.deployIncremental())
            if (original == null) assertFalse(file.exists()) else assertArrayEquals(original, file.readBytes())
            assertEquals("Restore the original compiled option", previousEffective,
                engine.getSchemaString("t9_pinyin", "t9_english/digit_suffixes"))
            changed = false
        }
    }
}
