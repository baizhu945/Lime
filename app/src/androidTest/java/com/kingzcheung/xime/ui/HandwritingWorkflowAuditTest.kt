package com.kingzcheung.xime.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
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
import com.kingzcheung.xime.service.XimeInputMethodService
import com.kingzcheung.xime.settings.SettingsPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 在真实 IME 窗口中覆盖手写文本归属及会话重启；只用于隔离模拟器。 */
class HandwritingWorkflowAuditTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun separatorsSelectionAndInputRestartKeepHandwritingConsistent(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val keys = listOf(SettingsPreferences.KEY_SHOW_CANDIDATE_CANCEL_BUTTON, "floating_mode", "floating_offset_x", "floating_offset_y",
            "keyboard_height_dp", "keyboard_bottom_padding_dp", "keyboard_opacity",
            "current_schema", "current_schema_dual")
        val saved = keys.associateWith { prefs.all[it] }
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val enabledBefore = shell("ime list -s").lineSequence().any { it == ime }
        val engine = RimeEngine.getInstance()
        var previousSchema = ""
        var previousAscii = false
        var previousSavedAscii = false
        lateinit var editor: EditText
        var expandedCompactToolbar = false
        fun hasContent(description: String) =
            rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        fun hasTag(tag: String) =
            rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        try {
            SettingsPreferences.setShowCandidateCancelButton(context, true)
            assertTrue(RimeConfigHelper.prepareEngine(context))
            assertTrue(engine.ensureSession())
            previousSchema = engine.getCurrentSchema()
            previousAscii = engine.isAsciiMode()
            previousSavedAscii = engine.getUserConfigBool("var/option/ascii_mode")
            SettingsPreferences.setKeyboardHeightDp(context, 300)
            SettingsPreferences.setKeyboardBottomPaddingDp(context, 0)
            SettingsPreferences.setFloatingMode(context, false)
            SettingsPreferences.setCurrentSchema(context, "pinyin_simp")
            assertTrue(engine.switchSchema("pinyin_simp"))
            engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            shell("ime enable $ime")
            shell("ime set $ime")
            rule.setContent {
                AndroidView(factory = { EditText(it).also { view -> editor = view; view.hint = "真实输入法回归输入框" } },
                    modifier = Modifier.fillMaxWidth().height(80.dp))
            }
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            rule.runOnUiThread { editor.requestFocus(); imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            rule.waitUntil(30_000) {
                hasTag("hardware-toolbar-keyboard") || hasContent("手写") || hasTag("handwriting-canvas")
            }
            var imeService: XimeInputMethodService? = null
            rule.waitUntil(30_000) {
                imeService = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findService)
                imeService?.keyboardCallbacks != null
            }
            val service = checkNotNull(imeService)
            if (service.uiState.value.isCompact) {
                expandedCompactToolbar = true
                rule.onNodeWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).performClick()
                rule.waitUntil(15_000) { !service.uiState.value.isCompact }
            }
            if (!hasTag("handwriting-canvas")) {
                rule.runOnUiThread { service.keyboardCallbacks?.onHandwritingToggle?.invoke() }
                rule.waitUntil(10_000) { hasTag("handwriting-canvas") }
            }
            fun text(): String { var value = ""; rule.runOnUiThread { value = editor.text.toString() }; return value }
            fun key(action: String) { rule.onNodeWithTag("handwriting-key:$action").performTouchInput { down(center); up() } }
            fun stroke() {
                rule.onNodeWithTag("handwriting-canvas").performTouchInput {
                    down(androidx.compose.ui.geometry.Offset(60f, 100f))
                    moveTo(androidx.compose.ui.geometry.Offset(120f, 100f))
                    moveTo(androidx.compose.ui.geometry.Offset(240f, 100f)); up()
                }
            }
            fun recognize() {
                stroke()
                rule.waitUntil(20_000) { rule.onAllNodesWithContentDescription("取消输入").fetchSemanticsNodes().isNotEmpty() }
            }
            // Merely recognizing or starting the next character never writes to the editor.
            recognize()
            assertEquals("", text())
            stroke()
            rule.waitUntil(20_000) { rule.onAllNodesWithText("一一").fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithText("第1/2字", useUnmergedTree = true).assertCountEquals(0)
            assertEquals("两字未点选都不能自动上屏", "", text())
            rule.onNodeWithText("一一").performTouchInput { click() }
            rule.waitUntil(5_000) { text() == "一一" }
            rule.onAllNodesWithContentDescription("取消输入").assertCountEquals(0)
            // Separators explicitly confirm pending characters, but the next one stays pending.
            for (separator in listOf("space", "，", "。", "enter")) {
                val before = text()
                recognize()
                assertEquals(before, text())
                key(separator)
                rule.waitForIdle()
                rule.waitUntil(5_000) { text().length > before.length }
                val prefix = text()
                assertTrue("确认时应提交待选字", prefix.length > before.length)
                recognize()
                assertEquals(prefix, text())
                rule.onNodeWithContentDescription("取消输入").performClick()
                assertEquals("取消未确认手写不应修改宿主", prefix, text())
            }
            recognize()
            val written = text()
            rule.runOnUiThread { editor.setSelection(0, editor.text.length) }
            rule.onNodeWithContentDescription("取消输入").performClick()
            assertEquals(written, text())
            rule.runOnUiThread { editor.setSelection(editor.text.length) }
            recognize()
            key("delete")
            assertEquals("删除先移除未确认字", written, text())
            rule.onAllNodesWithContentDescription("取消输入").assertCountEquals(0)
            // A new input session must cancel its pending recognition even while the panel stays handwriting.
            rule.runOnUiThread { editor.setSelection(editor.text.length) }
            stroke()
            rule.runOnUiThread { editor.setText("新输入框"); editor.setSelection(editor.text.length); imm.restartInput(editor) }
            Thread.sleep(1800)
            rule.waitForIdle()
            assertEquals("旧会话识别不能写入重启后的输入框", "新输入框", text())
            rule.onAllNodesWithContentDescription("取消输入").assertCountEquals(0)
            screenshot("handwriting-audit")
        } catch (failure: Throwable) {
            screenshot("failure")
            throw failure
        } finally {
            if (hasTag("handwriting-canvas")) runCatching {
                rule.onNodeWithContentDescription("手写").performClick()
            }
            if (expandedCompactToolbar) runCatching { shell("input keyevent KEYCODE_BACK") }
            if (previousSchema.isNotEmpty()) {
                engine.switchSchema(previousSchema)
                engine.setOption("ascii_mode", previousAscii)
                engine.setUserConfigBool("var/option/ascii_mode", previousSavedAscii)
            }
            prefs.edit().also { edit ->
                saved.forEach { (key, value) ->
                    when (value) {
                        null -> edit.remove(key)
                        is Boolean -> edit.putBoolean(key, value)
                        is Int -> edit.putInt(key, value)
                        is Float -> edit.putFloat(key, value)
                        is String -> edit.putString(key, value)
                    }
                }
            }.commit()
            if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
            if (!enabledBefore) shell("ime disable $ime")
        }
    }

    private fun findService(view: View): XimeInputMethodService? {
        var current: Context? = view.context
        while (current != null) {
            if (current is XimeInputMethodService) return current
            current = (current as? ContextWrapper)?.baseContext?.takeUnless { it === current }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) findService(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ime-regression").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
