package com.kingzcheung.xime.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
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
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 集成版最小中文输入验证：点字母必须进编码、空格必须上屏。 */
class SandboxTypingSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun lettersEnterCompositionAndSpaceCommits(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val saved = listOf("current_schema", "current_schema_dual", "last_input_mode_zh",
            "floating_mode", "input_text_location").associateWith { prefs.all[it] }
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val engine = RimeEngine.getInstance()
        var previousSchema = ""
        var previousAscii = false
        var previousSavedAscii = false
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
        try {
            assertTrue(RimeConfigHelper.prepareEngine(context))
            assertTrue(engine.ensureSession())
            previousSchema = engine.getCurrentSchema()
            previousAscii = engine.isAsciiMode()
            previousSavedAscii = engine.getUserConfigBool("var/option/ascii_mode")
            assertTrue(engine.switchSchema("rime_ice"))
            engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            SettingsPreferences.setCurrentSchema(context, "rime_ice")
            SettingsPreferences.setFloatingMode(context, false)
            SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
            shell("ime enable ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            shell("ime set ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            rule.setContent {
                AndroidView(factory = { EditText(it).also { edit -> editor = edit; edit.hint = "输入冒烟" } },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            rule.runOnUiThread {
                editor.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            rule.waitUntil(60_000) {
                imeService = android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findService)
                imeService != null && rule.onAllNodesWithTag("qwerty-key:a", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                    imeService?.uiState?.value?.isCompact == true &&
                    rule.onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val service = checkNotNull(imeService)
            if (service.uiState.value.isCompact) {
                expandedCompactToolbar = true
                rule.onNodeWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).performClick()
                rule.waitUntil(15_000) { !service.uiState.value.isCompact }
                rule.waitUntil(15_000) { rule.onAllNodesWithTag("qwerty-key:a", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            }
            fun tap(tag: String) {
                rule.onNodeWithTag(tag).performTouchInput { down(center); up() }
                rule.waitForIdle()
            }
            listOf("n", "i", "h", "a", "o").forEach {
                if (it == "n") {
                    rule.onNodeWithText("n", ignoreCase = true).performTouchInput { down(center); up() }
                    rule.waitForIdle()
                } else tap("qwerty-key:$it")
            }
            rule.waitUntil(15_000) { engine.getInput().isNotEmpty() }
            screenshot("typed")
            assertEquals("nihao", engine.getInput())
            tap("space-key")
            rule.waitUntil(15_000) { val t = textOf(rule) { editor }; t.isNotEmpty() }
            val committed = textOf(rule) { editor }
            assertTrue("空格后上屏：$committed", committed.isNotEmpty())
            assertEquals("", engine.getInput())
            screenshot("committed")
        } catch (t: Throwable) {
            screenshot("failure")
            throw t
        } finally {
            runCatching { engine.clearComposition() }
            if (expandedCompactToolbar && imeService?.uiState?.value?.isCompact == false) {
                runCatching { shell("input keyevent KEYCODE_BACK") }
            }
            if (previousSchema.isNotEmpty()) {
                engine.switchSchema(previousSchema)
                engine.setOption("ascii_mode", previousAscii)
                engine.setUserConfigBool("var/option/ascii_mode", previousSavedAscii)
            }
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
        }
    }

    private fun textOf(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>, block: () -> EditText): String {
        var result = ""
        rule.runOnUiThread { result = block().text.toString() }
        return result
    }

    private fun shell(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
    ).bufferedReader().use { it.readText() }

    private fun screenshot(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val dir = File(i.targetContext.getExternalFilesDir(null), "typing").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().also { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
