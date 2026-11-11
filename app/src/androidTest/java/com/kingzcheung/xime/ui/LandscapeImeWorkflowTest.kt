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
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.*
import com.kingzcheung.xime.service.XimeInputMethodService
import com.kingzcheung.xime.settings.SettingsPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LandscapeImeWorkflowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun landscapeDefaultsAndResizeStayAttachedToTheActualFloatingCard(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val screen = context.resources.configuration.smallestScreenWidthDp
        val saved = listOf("current_schema", "current_schema_dual", "last_input_mode_zh", "last_input_mode_ja",
            "landscape_layout_v2", "keyboard_opacity_landscape", "floating_mode_landscape",
            "keyboard_height_dp_landscape", "floating_height_dp_landscape", "floating_width_dp_landscape",
            "floating_offset_x_landscape", "floating_offset_y_landscape", "floating_rotation_landscape",
            "fixed_width_dp_landscape", "fixed_offset_x_landscape", "fixed_geometry_screen_landscape",
            "fixed_width_dp_landscape_sw$screen", "fixed_offset_x_landscape_sw$screen",
            "keyboard_height_dp", "fixed_height_normalized_to_1_14_25_v1", "height_profiles_v1_landscape",
            "keyboard_height_dp_landscape_before_height_profiles", "deployment_done", "deployment_hash")
            .associateWith { prefs.all[it] }
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val wasEnabled = shell("ime list -s").lineSequence().any { it.trim() == ime }
        val engine = RimeEngine.getInstance()
        var previousSchema: String? = null
        var previousSelectedSchema: String? = null
        var previousAscii: Boolean? = null
        var previousSavedAscii: Boolean? = null
        fun captureNativeState() {
            if (previousSchema != null || !RimeEngine.isInitialized()) return
            engine.getCurrentSchema().takeIf { it.isNotBlank() }?.let {
                previousSchema = it
                previousSelectedSchema = engine.getUserConfigString("var/previously_selected_schema")
                previousAscii = engine.isAsciiMode()
                previousSavedAscii = engine.getUserConfigBool("var/option/ascii_mode")
            }
        }
        lateinit var editor: EditText
        var expandedCompactToolbar = false
        try {
            captureNativeState()
            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
            rule.waitUntil(5000) { context.resources.configuration.screenWidthDp > context.resources.configuration.screenHeightDp }
            val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
            engine.initialize(user, shared); assertTrue(RimeConfigHelper.ensureDeployment(context)); assertTrue(engine.ensureSession())
            captureNativeState()
            assertTrue(context.resources.configuration.screenWidthDp > context.resources.configuration.screenHeightDp)
            assertTrue(engine.switchSchema("t9_pinyin")); engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            SettingsPreferences.setCurrentSchema(context, "t9_pinyin")
            // 横屏新装默认固定、不透明（用户 10-31）；本用例测横屏悬浮流程，自己开悬浮、设半透明。
            prefs.edit().remove("landscape_layout_v2").remove("keyboard_opacity_landscape")
                .remove("floating_mode_landscape").putInt("keyboard_height_dp_landscape", 60).commit()
            // A previous test may have saved a fixed floating width; both layouts would then
            // have the same card width. Zero means "derive the width from the height".
            SettingsPreferences.setFloatingWidthDp(context, 0, true)
            SettingsPreferences.setFixedHorizontalGeometry(context, 0, 0, true)
            // The IME service is process-wide and may still hold a width cached from an
            // earlier test. Tear it down so the zero above is resolved by a fresh service.
            shell("ime disable ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            assertFalse(SettingsPreferences.isFloatingMode(context, true))
            assertEquals(1f, SettingsPreferences.getKeyboardOpacity(context), 0.001f)
            prefs.edit().putBoolean("floating_mode_landscape", true).putFloat("keyboard_opacity_landscape", 0.5f).commit()
            assertTrue(SettingsPreferences.isFloatingMode(context, true))
            assertEquals(0.5f, SettingsPreferences.getKeyboardOpacity(context), 0.001f)
            assertTrue(SettingsPreferences.getKeyboardHeightDp(context, true) >= 228)
            shell("ime enable ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            shell("ime set ${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService")
            rule.setContent { AndroidView(factory = { EditText(it).also { editor = it; it.hint = "横屏悬浮输入回归" } }, modifier = Modifier.fillMaxWidth().height(60.dp)) }
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            rule.runOnUiThread { editor.requestFocus(); imm.restartInput(editor); imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            rule.waitUntil(30_000) {
                rule.onAllNodesWithTag("floating-drag-bar").fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            if (rule.onAllNodesWithTag("floating-drag-bar").fetchSemanticsNodes().isEmpty()) {
                expandedCompactToolbar = true
                rule.onNodeWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).performClick()
            }
            rule.waitUntil(30_000) { rule.onAllNodesWithTag("floating-drag-bar").fetchSemanticsNodes().isNotEmpty() }
            var imeService: XimeInputMethodService? = null
            rule.waitUntil(30_000) {
                imeService = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findService)
                imeService != null
            }
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("t9-delete-key", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val density = context.resources.displayMetrics.density
            fun cardWidth(): Float = rule.onNodeWithTag("floating-keyboard-card").fetchSemanticsNode().size.width.toFloat()
            val nineWidth = cardWidth()
            val symbol = rule.onNodeWithTag("t9-delete-key").fetchSemanticsNode().boundsInRoot
            assertTrue("横屏最低行高保持可用: $symbol density=$density", symbol.height >= 40 * density)
            screenshot("landscape-t9")
            rule.onNodeWithTag("toolbar-leading").performClick()
            rule.waitForIdle()
            if (rule.onAllNodesWithTag("menu-item:float").fetchSemanticsNodes().isEmpty()) {
                rule.onNodeWithTag("menu-pages", useUnmergedTree = true).performTouchInput { swipeLeft() }
            }
            rule.onNodeWithTag("menu-item:float").performClick()
            rule.onNodeWithTag("keyboard-opacity-slider").assertIsDisplayed()
            val before = rule.onNodeWithContentDescription("确认").fetchSemanticsNode().positionOnScreen
            rule.onNodeWithTag("floating-drag-bar").performTouchInput { down(center); moveBy(Offset(-65f, -30f) * density); up() }
            val after = rule.onNodeWithContentDescription("确认").fetchSemanticsNode().positionOnScreen
            assertTrue("调节面板必须随键盘左右移动", kotlin.math.abs(after.x - before.x) > 10 * density)
            val confirm = rule.onNodeWithContentDescription("确认").fetchSemanticsNode()
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            val nav = editor.rootWindowInsets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            assertTrue("确认不被导航栏挡住", confirm.positionOnScreen.y + confirm.size.height < bitmap.height - nav)
            bitmap.recycle()
            screenshot("landscape-resize-moved")
            rule.onNodeWithContentDescription("悬浮键盘").performClick()
            rule.onNodeWithTag("floating-drag-bar").assertDoesNotExist()
            rule.onNodeWithTag("keyboard-opacity-slider").assertIsDisplayed()
            rule.onNodeWithContentDescription("悬浮键盘").performClick()
            rule.onNodeWithTag("floating-drag-bar").assertIsDisplayed()
            rule.onNodeWithContentDescription("确认").performClick()
            rule.runOnUiThread {
                checkNotNull(imeService).schemaController.switchSchema("japanese")
            }
            rule.waitUntil(15_000) { engine.getCurrentSchema() == "japanese" }
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("qwerty-key:q", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.waitUntil(15_000) { cardWidth() / nineWidth > 1.1f }
            assertEquals("26键横屏宽约为九键1.2倍", 1.2f, cardWidth() / nineWidth, 0.06f)
            screenshot("landscape-qwerty")
        } catch (t: Throwable) { screenshot("landscape-failure"); throw t }
        finally {
            try {
                if (expandedCompactToolbar) runCatching { shell("input keyevent KEYCODE_BACK") }
                runCatching {
                    engine.clearComposition()
                    previousSchema?.let { engine.switchSchema(it) }
                    previousSelectedSchema?.let { engine.setUserConfigString("var/previously_selected_schema", it) }
                    previousAscii?.let { engine.setOption("ascii_mode", it) }
                    previousSavedAscii?.let { engine.setUserConfigBool("var/option/ascii_mode", it) }
                }
                prefs.edit().also { edit -> saved.forEach { (key, value) ->
                    if (prefs.all[key] != value) when (value) {
                        is String -> edit.putString(key, value)
                        is Int -> edit.putInt(key, value)
                        is Float -> edit.putFloat(key, value)
                        is Long -> edit.putLong(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                        is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                        else -> edit.remove(key)
                    }
                } }.commit()
                if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
                if (!wasEnabled) shell("ime disable $ime")
            } finally {
                instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
            }
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

    private fun shell(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }
    private fun screenshot(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val dir = File(i.targetContext.getExternalFilesDir(null), "round4").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().also { bitmap -> File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
    }
}
