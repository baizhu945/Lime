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

/** 逐键测量“点键到 UI 稳定”的可见延迟，用于优化前后的对比。 */
class UiKeyLatencyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun measureVisibleLatencyPerKey(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val saved = listOf("current_schema", "current_schema_dual", "last_input_mode_zh", "floating_mode")
            .associateWith { prefs.all[it] }
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val wasEnabled = shell("ime list -s").lineSequence().any { it.trim() == ime }
        val engine = RimeEngine.getInstance()
        var previousSchema: String? = null
        var previousAscii = false
        var previousSavedAscii = false
        lateinit var editor: EditText
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
            shell("ime enable ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            shell("ime set ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            rule.setContent {
                AndroidView(factory = { EditText(it).also { edit -> editor = edit } },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val deadline = SystemClock.uptimeMillis() + 60_000
            fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
            rule.awaitRealImeInputReady(editor, imm, engine, remaining())
            var expanded = false
            rule.waitUntil(remaining()) {
                val toggle = rule.onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true)
                if (!expanded && toggle.fetchSemanticsNodes().isNotEmpty()) {
                    expanded = true
                    toggle.onFirst().performClick()
                }
                rule.onAllNodesWithTag("qwerty-key:n").fetchSemanticsNodes().isNotEmpty()
            }
            fun tapAndTime(tag: String): Long {
                val t0 = System.nanoTime()
                rule.onNodeWithTag(tag).performTouchInput { down(center); up() }
                rule.waitForIdle()
                return (System.nanoTime() - t0) / 1_000_000
            }
            // 预热两轮：首轮含会话建立、字体与布局首次测量。
            repeat(2) { "niha".forEach { tapAndTime("qwerty-key:$it") } }
            engine.clearComposition()
            rule.waitForIdle()
            val samples = mutableListOf<Long>()
            repeat(4) { "nihao".forEach { samples += tapAndTime("qwerty-key:$it") } }
            val sorted = samples.sorted()
            println("UIKEY samples=$samples median=${sorted[sorted.size / 2]}ms p90=${sorted[(sorted.size * 9) / 10]}ms max=${sorted.last()}ms")
        } catch (failure: Throwable) {
            runCatching {
                val image = instrumentation.uiAutomation.takeScreenshot()
                try {
                    java.io.File(context.getExternalFilesDir(null), "ui-key-latency-failure.png").outputStream().use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { image.recycle() }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            runCatching {
                engine.clearComposition()
                previousSchema?.let {
                    engine.switchSchema(it)
                    engine.setOption("ascii_mode", previousAscii)
                    engine.setUserConfigBool("var/option/ascii_mode", previousSavedAscii)
                }
            }
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
