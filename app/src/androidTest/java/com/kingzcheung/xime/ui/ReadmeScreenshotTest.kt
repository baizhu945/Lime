package com.kingzcheung.xime.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Captures the real IME for the README keyboard gallery. */
class ReadmeScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureKeyboardAppearance(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val ownedKeys = listOf("current_schema", "current_schema_dual", "floating_mode", "input_text_location",
            "deployment_done", "deployment_hash", "rime_deployment_revision", "rime_deployment_problem",
            "builtin_schemas_merged", "japanese_schemas_added_v1", "cyime_chinese_defaults_v1") +
            listOf("zh", "en", "ja").flatMap { listOf("selected_input_profile_$it", "last_input_mode_$it", "letter_layout_$it") }
        val saved = ownedKeys.associateWith { prefs.all[it] }
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val enabledBefore = shell("ime list -s").lineSequence().any { it == ime }
        val engine = RimeEngine.getInstance()
        data class NativeState(val schema: String, val ascii: Boolean, val savedAscii: Boolean,
            val fullShape: Boolean, val savedFullShape: Boolean)
        fun nativeState(): NativeState? = if (!RimeEngine.isInitialized()) null else
            engine.getCurrentSchema().takeIf { it.isNotBlank() }?.let {
                NativeState(it, engine.isAsciiMode(), engine.getUserConfigBool("var/option/ascii_mode"),
                    engine.getOption("full_shape"), engine.getUserConfigBool("var/option/full_shape"))
            }
        var previousNative = nativeState()
        lateinit var editor: EditText
        var imeService: com.kingzcheung.xime.service.XimeInputMethodService? = null
        var expandedCompactToolbar = false
        fun findService(view: View): com.kingzcheung.xime.service.XimeInputMethodService? {
            var candidate: Context? = view.context
            while (candidate != null) {
                if (candidate is com.kingzcheung.xime.service.XimeInputMethodService) return candidate
                candidate = (candidate as? android.content.ContextWrapper)?.baseContext?.takeUnless { it === candidate }
            }
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findService(view.getChildAt(i))?.let { return it }
            return null
        }
        fun capture(name: String) {
            rule.waitForIdle()
            android.os.SystemClock.sleep(450)
            rule.waitForIdle()
            val dir = File(context.getExternalFilesDir(null), "readme").apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot().also { image ->
                File(dir, "$name.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
        }
        fun tap(tag: String) {
            rule.onNodeWithTag("qwerty-key:$tag", useUnmergedTree = true).performTouchInput { down(center); up() }
            rule.waitForIdle()
        }
        fun motion(action: Int, x: Float, y: Float) {
            val now = android.os.SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        try {
            val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
            engine.initialize(user, shared)
            assertTrue(RimeConfigHelper.ensureDeployment(context))
            assertTrue(engine.ensureSession())
            if (previousNative == null) previousNative = nativeState()
            SettingsPreferences.setCurrentSchema(context, "rime_ice")
            SettingsPreferences.setFloatingMode(context, false)
            SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
            shell("ime enable $ime")
            shell("ime set $ime")
            rule.setContent {
                AndroidView(factory = { EditText(it).also { edit -> editor = edit; edit.hint = "README" } },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            rule.runOnUiThread {
                editor.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            rule.waitUntil(60_000) {
                imeService = android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findService)
                imeService?.let { service ->
                    service.inputReadiness.ticket() != null && !engine.isMaintaining() && (
                        !service.uiState.value.isCompact &&
                            rule.onAllNodesWithTag("qwerty-key:g", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                            service.uiState.value.isCompact &&
                            rule.onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
                } == true
            }
            val service = checkNotNull(imeService)
            if (service.uiState.value.isCompact) {
                expandedCompactToolbar = true
                rule.onNodeWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).performClick()
                rule.waitUntil(15_000) { !service.uiState.value.isCompact }
            }
            rule.runOnUiThread { service.schemaController.switchSchema("rime_ice") }
            rule.waitUntil(15_000) {
                engine.getCurrentSchema() == "rime_ice" && !engine.isAsciiMode() &&
                    service.uiState.value.currentSchemaId == "rime_ice" && !service.uiState.value.isAsciiMode &&
                    rule.onAllNodesWithTag("qwerty-key:g", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            // Gallery-only: let the real scheme-switch toast disappear, as for the Japanese shot.
            // This is not a latency benchmark warm-up and changes none of its rounds or budget.
            android.os.SystemClock.sleep(3_000)
            capture("readme-probability-keyboard")
            val g = rule.onNodeWithTag("qwerty-key:g", useUnmergedTree = true).fetchSemanticsNode()
            val pressX = g.positionOnScreen.x + g.size.width * 0.62f
            val pressY = g.positionOnScreen.y + g.size.height * 0.5f
            motion(MotionEvent.ACTION_DOWN, pressX, pressY)
            android.os.SystemClock.sleep(700)
            capture("readme-probability-press")
            motion(MotionEvent.ACTION_CANCEL, pressX, pressY)
            android.os.SystemClock.sleep(250)
            engine.clearComposition()
            rule.waitUntil(5_000) { engine.getInput().isEmpty() }
            rule.runOnUiThread { editor.setText("") }

            listOf("x", "i", "a", "n").forEach(::tap)
            // The h touch-cell's left edge is in the visible cap gap, not inside its cap.
            // g/h have equal Gaussian density there; stable order chooses g and types xiang.
            rule.onNodeWithTag("qwerty-key:h", useUnmergedTree = true)
                .performTouchInput { click(Offset(0f, centerY)) }
            rule.waitUntil(10_000) { engine.getInput() == "xiang" && engine.getCandidates().contains("想") }
            capture("readme-xiang")
            engine.clearComposition()
            rule.waitUntil(5_000) { engine.getInput().isEmpty() }
            rule.runOnUiThread { editor.setText("") }

            listOf("w", "o", "b", "u", "z", "h", "i", "d", "a", "o").forEach(::tap)
            rule.waitUntil(10_000) { engine.getInput() == "wobuzhidao" && engine.getCandidates().contains("我不知道") }
            capture("readme-wobuzhidao")
            engine.clearComposition()
            rule.waitUntil(5_000) { engine.getInput().isEmpty() }
            rule.runOnUiThread { editor.setText("") }

            rule.chooseModeThroughLanguageAndPanel("japanese")
            rule.waitUntil(15_000) { engine.getCurrentSchema() == "japanese" && !engine.isAsciiMode() }
            rule.waitUntil(10_000) { imeService?.uiState?.value?.currentSchemaId == "japanese" }
            rule.runOnUiThread { editor.setText("") }
            // Let the switch toast and the keyboard recomposition settle before the idle shot.
            android.os.SystemClock.sleep(3_000)
            capture("readme-japanese")
        } finally {
            runCatching { engine.clearComposition() }
            if (expandedCompactToolbar && imeService?.uiState?.value?.isCompact == false) {
                runCatching { shell("input keyevent KEYCODE_BACK") }
            }
            runCatching { rule.runOnUiThread { editor.clearFocus() } }
            try {
                previousNative?.let { previous ->
                    assertTrue(engine.switchSchema(previous.schema))
                    assertTrue(engine.setOption("ascii_mode", previous.ascii))
                    engine.setUserConfigBool("var/option/ascii_mode", previous.savedAscii)
                    assertTrue(engine.setOption("full_shape", previous.fullShape))
                    engine.setUserConfigBool("var/option/full_shape", previous.savedFullShape)
                }
            } finally {
                check(prefs.edit().also { edit ->
                    saved.forEach { (k, v) ->
                        if (prefs.all[k] == v) return@forEach
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
                }.commit())
                com.kingzcheung.xime.settings.LetterLayouts.load(context)
                com.kingzcheung.xime.settings.KeysConfigHelper.applyLetterLayouts()
                com.kingzcheung.xime.settings.KeysConfigHelper.setActiveKeyboardSchema(
                    previousNative?.schema ?: SettingsPreferences.getCurrentSchema(context))
                if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
                if (!enabledBefore) shell("ime disable $ime")
            }
        }
    }

    private fun shell(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
    ).bufferedReader().use { it.readText() }
}
