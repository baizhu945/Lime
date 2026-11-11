package com.kingzcheung.xime.ui

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Strict end-to-end key-trigger budget: inject a real touchscreen event at the visible key,
 * then require the Rime composition to reflect it within 50 ms. The timer covers Android
 * input dispatch, Compose pointer handling, the key callback, and the Rime update.
 */
class KeyLatencyBudgetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val budgetMs = 50L

    @Test fun everyVisibleKeyReachesTheEngineWithinFiftyMilliseconds(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val saved = listOf("current_schema", "current_schema_dual", "last_input_mode_zh",
            "floating_mode", "sound_enabled", "haptic_mode").associateWith { prefs.all[it] }
        val previousIme = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val wasEnabled = shell("ime list -s").lineSequence().any { it.trim() == ime }
        val engine = RimeEngine.getInstance()
        var previousSchema: String? = null
        var previousAscii = false
        var previousSavedAscii = false
        lateinit var editor: EditText
        var expandedCompactToolbar = false
        try {
            val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
            engine.initialize(user, shared)
            assertTrue(RimeConfigHelper.ensureDeployment(context))
            assertTrue(engine.ensureSession())
            previousSchema = engine.getCurrentSchema()
            previousAscii = engine.isAsciiMode()
            previousSavedAscii = engine.getUserConfigBool("var/option/ascii_mode")
            assertTrue(engine.switchSchema("rime_ice"))
            engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            SettingsPreferences.setCurrentSchema(context, "rime_ice")
            SettingsPreferences.setFloatingMode(context, false)
            // Isolate key dispatch/Rime from optional audio and haptic feedback.
            prefs.edit().putBoolean("sound_enabled", false)
                .putString("haptic_mode", "disabled").commit()
            shell("ime enable ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            shell("ime set ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            rule.setContent {
                AndroidView(factory = { EditText(it).also { edit -> editor = edit } },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            rule.runOnUiThread {
                editor.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            rule.waitUntil(60_000) {
                rule.onAllNodesWithTag("qwerty-key:n").fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
            }
            if (rule.onAllNodesWithTag("qwerty-key:n").fetchSemanticsNodes().isEmpty()) {
                expandedCompactToolbar = true
                rule.onNodeWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).performClick()
            }
            rule.waitUntil(30_000) {
                rule.onAllNodesWithTag("qwerty-key:n").fetchSemanticsNodes().isNotEmpty()
            }
            rule.waitUntil(30_000) { engine.getInput().isEmpty() }
            fun keyCenter(key: Char): Pair<Float, Float> {
                val node = rule.onNodeWithTag("qwerty-key:$key").fetchSemanticsNode()
                return (node.positionOnScreen.x + node.size.width / 2f) to
                    (node.positionOnScreen.y + node.size.height / 2f)
            }

            fun measure(key: Char, expected: String): Double {
                // Resolve the bounds before starting the clock. The timing window must
                // cover Android input dispatch -> Compose pointer handling -> key callback
                // -> Rime, not Compose Test's idle synchronization.
                val node = rule.onNodeWithTag("qwerty-key:$key")
                node.fetchSemanticsNode()
                var start = 0L
                node.performTouchInput {
                    // Start after Compose Test resolved the node and created the
                    // injection scope; only the actual gesture and its dispatch remain.
                    start = SystemClock.elapsedRealtimeNanos()
                    down(center)
                    up()
                }
                check(start != 0L) { "touch gesture was not injected" }
                val deadline = SystemClock.elapsedRealtimeNanos() + 2_000_000_000L
                while (engine.getInput() != expected) {
                    if (SystemClock.elapsedRealtimeNanos() > deadline) {
                        throw AssertionError(
                            "key '$key' never reached '$expected': input=${engine.getInput()}")
                    }
                    SystemClock.sleep(1)
                }
                // Preserve sub-millisecond precision: 50.x ms must not truncate to a passing 50.
                return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            }

            // clearComposition is deliberately non-blocking for the UI; retry until the
            // serial key/candidate work has released the engine and the clear took effect.
            fun clearAndWait() {
                engine.clearComposition()
                rule.waitUntil(10_000) {
                    engine.clearComposition()
                    engine.getInput().isEmpty()
                }
            }

            // Warm up layout, font and the first Rime candidate pass.
            val warmup = "niha"
            var prefix = ""
            repeat(3) {
                clearAndWait()
                prefix = ""
                for (key in warmup) {
                    prefix += key
                    measure(key, prefix)
                }
            }
            clearAndWait()

            val text = "nihaozhongguorenmin"
            prefix = ""
            val samples = mutableListOf<Double>()
            for (key in text) {
                prefix += key
                val elapsed = measure(key, prefix)
                samples += elapsed
                println("KEYLAT key=$key input=$prefix elapsed=${elapsed}ms")
            }
            val sorted = samples.sorted()
            println("KEYLAT samples=$samples median=${sorted[sorted.size / 2]}ms " +
                "p90=${sorted[(sorted.size * 9) / 10]}ms max=${sorted.last()}ms budget=${budgetMs}ms")
            assertTrue(
                "every key must reach the engine within ${budgetMs}ms; " +
                    "worst=${sorted.last()}ms samples=$samples",
                sorted.last() <= budgetMs)
        } finally {
            runCatching {
                engine.clearComposition()
                previousSchema?.let {
                    engine.switchSchema(it)
                    engine.setOption("ascii_mode", previousAscii)
                    engine.setUserConfigBool("var/option/ascii_mode", previousSavedAscii)
                }
            }
            if (expandedCompactToolbar) runCatching { shell("input keyevent KEYCODE_BACK") }
            prefs.edit().also { edit ->
                saved.forEach { (k, v) ->
                    when (v) {
                        is String -> edit.putString(k, v)
                        is Int -> edit.putInt(k, v)
                        is Float -> edit.putFloat(k, v)
                        is Long -> edit.putLong(k, v)
                        is Boolean -> edit.putBoolean(k, v)
                        is Set<*> -> edit.putStringSet(k, v.filterIsInstance<String>().toSet())
                        else -> edit.remove(k)
                    }
                }
            }.commit()
            if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
            if (!wasEnabled) shell("ime disable $ime")
        }
    }

    private fun shell(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
    ).bufferedReader().use { it.readText() }
}
