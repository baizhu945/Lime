package com.kingzcheung.xime.ui

import android.content.Context
import android.graphics.Bitmap
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
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 在真实 IME 窗口中覆盖服务层高度/底部留白；只用于隔离模拟器。 */
class RealFloatingImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun floatingLayoutsUseOnlyTheDragRowAndDockWithoutLeavingBlankSpace() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val keys = listOf("floating_mode", "floating_offset_x", "floating_offset_y",
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
        try {
            assertTrue(RimeConfigHelper.prepareEngine(context))
            assertTrue(engine.ensureSession())
            previousSchema = engine.getCurrentSchema()
            previousAscii = engine.isAsciiMode()
            previousSavedAscii = engine.getUserConfigBool("var/option/ascii_mode")
            SettingsPreferences.setKeyboardHeightDp(context, 300)
            SettingsPreferences.setKeyboardBottomPaddingDp(context, 80)
            SettingsPreferences.setKeyboardOpacity(context, 1f)
            SettingsPreferences.setFloatingMode(context, true)
            SettingsPreferences.setFloatingOffsetY(context, 120)
            shell("ime enable $ime")
            shell("ime set $ime")
            rule.setContent {
                AndroidView(factory = { EditText(it).also { view -> editor = view; view.hint = "真实输入法回归输入框" } },
                    modifier = Modifier.fillMaxWidth().height(80.dp))
            }
            val density = context.resources.displayMetrics.density
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            fun showSchema(schema: String) {
                val deadline = SystemClock.uptimeMillis() + 30_000
                fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
                val service = rule.awaitRealImeInputReady(editor, imm, engine, remaining())
                assertTrue("切换 $schema", engine.switchSchema(schema))
                engine.setOption("ascii_mode", false)
                engine.setUserConfigBool("var/option/ascii_mode", false)
                SettingsPreferences.setCurrentSchema(context, schema)
                rule.runOnUiThread {
                    editor.requestFocus()
                    imm.restartInput(editor)
                    imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
                var expandedHardwareToolbar = false
                rule.waitUntil(remaining()) {
                    val expand = rule.onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true)
                    if (!expandedHardwareToolbar && expand.fetchSemanticsNodes().isNotEmpty()) {
                        expandedHardwareToolbar = true
                        expand.onFirst().performClick()
                    }
                    service.inputReadiness.ticket() != null && service.uiState.value.currentSchemaId == schema &&
                        !service.uiState.value.isAsciiMode && !engine.isAsciiMode() &&
                        rule.onAllNodesWithTag("floating-drag-bar").fetchSemanticsNodes().isNotEmpty()
                }
            }
            showSchema("t9_pinyin")
            rule.waitUntil(30_000) { rule.onAllNodesWithText("!@#").fetchSemanticsNodes().isNotEmpty() }
            val symbol = rule.onNodeWithText("!@#").fetchSemanticsNode()
            val bar = rule.onNodeWithTag("floating-drag-bar").fetchSemanticsNode()
            assertTrue("九键末行与拖条之间不能残留80dp留白",
                bar.positionOnScreen.y - (symbol.positionOnScreen.y + symbol.size.height) < 55f * density)
            screenshot("floating-t9")

            showSchema("rime_ice")
            rule.waitUntil(30_000) { rule.onAllNodesWithText("Q").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("q").fetchSemanticsNodes().isNotEmpty() }
            val space = rule.onNodeWithContentDescription("空格").fetchSemanticsNode()
            val qwertyBar = rule.onNodeWithTag("floating-drag-bar").fetchSemanticsNode()
            assertTrue("26键末行与拖条之间不能残留底部留白",
                qwertyBar.positionOnScreen.y - (space.positionOnScreen.y + space.size.height) < 55f * density)
            screenshot("floating-qwerty")

            SettingsPreferences.setKeyboardBottomPaddingDp(context, 0)
            SettingsPreferences.setFloatingMode(context, false)
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("floating-drag-bar").fetchSemanticsNodes().isEmpty() }
            val dockedSpace = rule.onNodeWithContentDescription("空格").fetchSemanticsNode()
            val screenBottom = instrumentation.uiAutomation.takeScreenshot().height
            assertTrue("恢复普通键盘后只保留末行半高与系统导航栏",
                screenBottom - dockedSpace.positionOnScreen.y - dockedSpace.size.height < 85f * density)
            screenshot("restored-qwerty")
            rule.onNodeWithContentDescription("编辑").performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("段首").fetchSemanticsNodes().isNotEmpty() }
            screenshot("editor-nine-grid")
        } catch (failure: Throwable) {
            screenshot("failure")
            throw failure
        } finally {
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
