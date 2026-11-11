package com.kingzcheung.xime.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.text.InputType
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
import com.kingzcheung.xime.settings.InputModes
import com.kingzcheung.xime.settings.SettingsPreferences
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real service/window transaction: live split and geometry preview, cancel rollback, confirm persistence. */
class ResizeTransactionImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val engine = RimeEngine.getInstance()
    private lateinit var editor: EditText
    private val swipeDistance get() = 80f * context.resources.displayMetrics.density
    private val imeComponent get() = ComponentName(context, "com.kingzcheung.xime.service.XimeInputMethodService")
    private val imeId get() = imeComponent.flattenToShortString()
    private val inputMethodManager get() = context.getSystemService(InputMethodManager::class.java)
    private var previousIme: String? = null
    private var previouslyEnabled = false
    private var previousSubtype = -1
    private var savedSystemImeState = false

    private val savedKeys = listOf("toolbar_buttons", SettingsPreferences.KEY_SPLIT_KEYBOARD,
        "keyboard_height_dp", "keyboard_height_dp_landscape", "keyboard_bottom_padding_dp",
        "keyboard_opacity", "keyboard_opacity_landscape", "floating_mode", "floating_mode_landscape",
        "floating_offset_x", "floating_offset_y", "floating_offset_x_landscape", "floating_offset_y_landscape",
        "landscape_layout_v2", "fixed_width_dp", "fixed_width_dp_landscape",
        "fixed_offset_x", "fixed_offset_x_landscape")
    private var savedPrefs: Map<String, Any?> = emptyMap()
    private var initialHeight = 0
    private var initialOpacity = 1f
    private val landscape get() = context.resources.configuration.screenWidthDp > context.resources.configuration.screenHeightDp

    @Before fun startKeyboard(): Unit = runBlocking {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        savedPrefs = savedKeys.associateWith { prefs.all[it] }
        SettingsPreferences.setToolbarButtons(context, listOf("float", "schema"))
        SettingsPreferences.setSplitKeyboardEnabled(context, false)
        prefs.edit().putBoolean("landscape_layout_v2", true).apply()
        SettingsPreferences.setFloatingMode(context, false, landscape)
        initialHeight = SettingsPreferences.getKeyboardHeightDp(context, landscape)
        initialOpacity = SettingsPreferences.getKeyboardOpacity(context)
        previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        previousSubtype = Settings.Secure.getInt(context.contentResolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, -1)
        previouslyEnabled = inputMethodManager.enabledInputMethodList.any { it.id == imeId }
        savedSystemImeState = true
        // A service-owned ComposeView outlives a test's ComposeTestRule. Reusing it in the
        // next test would keep the previous rule's disposed recomposer and invisible test root.
        stopKeyboardService()
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        engine.initialize(user, shared)
        assertTrue(RimeConfigHelper.ensureDeployment(context))
        assertTrue(engine.ensureSession())
        engine.clearQueuedComposition()
        shell("ime enable $imeId")
        shell("ime set $imeId")
        rule.setContent {
            AndroidView(factory = { EditText(it).also { field ->
                editor = field
                field.hint = "字面输入回归"
                field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } },
                modifier = Modifier.fillMaxWidth().height(120.dp))
        }
        rule.waitUntil(10_000) {
            var ready = false
            rule.runOnUiThread { ready = editor.isAttachedToWindow && editor.hasWindowFocus() }
            ready
        }
        rule.runOnUiThread {
            assertTrue("测试输入框必须取得焦点", editor.requestFocus())
            inputMethodManager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("language-key-control", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After fun stopKeyboardAndRestoreSystemIme() {
        if (!savedSystemImeState) return
        try {
            if (::editor.isInitialized) {
                rule.runOnUiThread {
                    inputMethodManager.hideSoftInputFromWindow(editor.windowToken, 0)
                    editor.clearFocus()
                }
            }
            stopKeyboardService()
        } finally {
            val restore = SettingsPreferences.getPrefsPublic(context).edit()
            savedPrefs.forEach { (key, value) ->
                when (value) {
                    null -> restore.remove(key)
                    is String -> restore.putString(key, value)
                    is Boolean -> restore.putBoolean(key, value)
                    is Int -> restore.putInt(key, value)
                    is Float -> restore.putFloat(key, value)
                }
            }
            restore.commit()
            if (previouslyEnabled) shell("ime enable $imeId")
            previousIme?.takeIf { it.isNotBlank() }?.let { shell("ime set $it") }
            shell("settings put secure selected_input_method_subtype $previousSubtype")
        }
    }

    @Suppress("DEPRECATION") // Android still exposes this application's own running services.
    private fun stopKeyboardService() {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val waitForEngineDestroy = RimeEngine.isInitialized() &&
            activityManager.getRunningServices(Int.MAX_VALUE).any { it.service == imeComponent }
        shell("ime disable $imeId")
        rule.waitUntil(10_000) {
            activityManager.getRunningServices(Int.MAX_VALUE).none { it.service == imeComponent } &&
                (!waitForEngineDestroy || !RimeEngine.isInitialized())
        }
        // Service removal and delivery of onDestroy happen on different threads. Drain Main
        // before initializing Rime again or allowing this test's recomposer to be disposed.
        instrumentation.waitForIdleSync()
    }

    private fun chooseMode(id: String) {
        rule.chooseModeThroughLanguageAndPanel(id)
    }

    @Test fun dockingPreviewsZeroTransparencyAndCancelRestoresFloatingOpacity() {
        chooseMode(InputModes.ENGLISH)
        rule.onNodeWithContentDescription("键盘调节").performClick()
        rule.onNodeWithTag("keyboard-resize-floating-button", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("keyboard-opacity-slider", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }
        rule.onNodeWithContentDescription("确认").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("keyboard-resize-frame", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        assertTrue(SettingsPreferences.isFloatingMode(context, landscape))
        assertEquals(0.4f, SettingsPreferences.getKeyboardOpacity(context), 0.001f)

        rule.onNodeWithContentDescription("键盘调节").performClick()
        rule.onNodeWithTag("keyboard-resize-floating-button", useUnmergedTree = true).performClick()
        rule.onNodeWithText("透明度 0%").assertIsDisplayed()
        assertEquals("Preview must not persist before confirmation", 0.4f, SettingsPreferences.getKeyboardOpacity(context), 0.001f)
        shell("input keyevent KEYCODE_BACK")
        rule.waitUntil(5000) { rule.onAllNodesWithTag("keyboard-resize-frame", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        assertTrue(SettingsPreferences.isFloatingMode(context, landscape))
        assertEquals(0.4f, SettingsPreferences.getKeyboardOpacity(context), 0.001f)

        rule.onNodeWithContentDescription("键盘调节").performClick()
        rule.onNodeWithTag("keyboard-resize-floating-button", useUnmergedTree = true).performClick()
        rule.onNodeWithText("透明度 0%").assertIsDisplayed()
        rule.onNodeWithContentDescription("确认").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("keyboard-resize-frame", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        assertFalse(SettingsPreferences.isFloatingMode(context, landscape))
        assertEquals(1f, SettingsPreferences.getKeyboardOpacity(context), 0f)
    }

    @Test fun cancelRestoresSplitAndSavedGeometryAfterLivePreview() {
        chooseMode(InputModes.ENGLISH)
        rule.onNodeWithContentDescription("键盘调节").performClick()
        rule.onNodeWithTag("keyboard-resize-split-button", useUnmergedTree = true).performClick()
        rule.waitUntil(3000) { SettingsPreferences.isSplitKeyboardEnabled(context) }
        rule.onNodeWithTag("split-keyboard-gap", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("keyboard-resize-frame", useUnmergedTree = true).performTouchInput {
            down(Offset(width / 2f, 4f)); moveBy(Offset(0f, -35f), 80); up()
        }
        rule.onNodeWithTag("keyboard-opacity-slider", useUnmergedTree = true).performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }
        rule.onNodeWithTag("keyboard-resize-frame", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("keyboard-resize-actions", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("keyboard-opacity-actions", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("keyboard-resize-floating-button", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("floating-keyboard-card", useUnmergedTree = true).assertExists()
        shell("input keyevent KEYCODE_BACK")
        rule.waitUntil(5000) { rule.onAllNodesWithTag("keyboard-resize-split-button").fetchSemanticsNodes().isEmpty() }
        assertFalse(SettingsPreferences.isSplitKeyboardEnabled(context))
        assertFalse(SettingsPreferences.isFloatingMode(context, landscape))
        assertEquals(initialHeight, SettingsPreferences.getKeyboardHeightDp(context, landscape))
        assertEquals(initialOpacity, SettingsPreferences.getKeyboardOpacity(context), 0f)
        rule.onNodeWithTag("split-keyboard-gap", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("floating-keyboard-card", useUnmergedTree = true).assertDoesNotExist()
        rule.runOnUiThread { assertTrue("cancel must leave editor focused", editor.hasFocus()) }
    }

    @Test fun confirmKeepsSplitAcrossKeyboardHideAndShow() {
        chooseMode(InputModes.ENGLISH)
        rule.onNodeWithContentDescription("键盘调节").performClick()
        rule.onNodeWithTag("keyboard-resize-split-button", useUnmergedTree = true).performClick()
        rule.onNodeWithContentDescription("确认").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("keyboard-resize-split-button").fetchSemanticsNodes().isEmpty() }
        assertTrue(SettingsPreferences.isSplitKeyboardEnabled(context))
        rule.onNodeWithTag("split-keyboard-gap", useUnmergedTree = true).assertIsDisplayed()
        rule.runOnUiThread { inputMethodManager.hideSoftInputFromWindow(editor.windowToken, 0) }
        instrumentation.waitForIdleSync()
        rule.runOnUiThread { editor.requestFocus(); inputMethodManager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("split-keyboard-gap").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("键盘调节").performClick()
        rule.onNodeWithTag("keyboard-resize-split-button", useUnmergedTree = true).assertIsSelected()
        shell("input keyevent KEYCODE_BACK")
        rule.waitUntil(5000) { rule.onAllNodesWithTag("keyboard-resize-split-button").fetchSemanticsNodes().isEmpty() }
        assertTrue("cancel a later transaction must preserve previously confirmed split", SettingsPreferences.isSplitKeyboardEnabled(context))
    }

    @Test fun fixedWidthAndAlignmentSurviveHideAndCancel() {
        chooseMode(InputModes.ENGLISH)
        rule.onNodeWithContentDescription("键盘调节").performClick()
        val before = rule.onNodeWithTag("fixed-keyboard-resize-preview").fetchSemanticsNode().boundsInRoot
        val frame = rule.onNodeWithTag("keyboard-resize-frame", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("keyboard-resize-frame", useUnmergedTree = true).performTouchInput {
            val right = before.right - frame.left - 4f
            val middle = before.center.y - frame.top
            swipe(Offset(right, middle), Offset(right - before.width * .25f, middle), 400)
        }
        rule.onNodeWithTag("keyboard-resize-fixed-move-bar", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(before.width * .3f, 0f), 400)
        }
        val expected = rule.onNodeWithTag("fixed-keyboard-resize-preview").fetchSemanticsNode().boundsInRoot
        assertTrue(expected.width < before.width - 20f)
        rule.onNodeWithContentDescription("确认").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("keyboard-resize-frame", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        assertFalse(SettingsPreferences.isFloatingMode(context, landscape))
        val savedWidth = SettingsPreferences.getFixedWidthDp(context, landscape)
        val savedX = SettingsPreferences.getFixedOffsetX(context, landscape)
        assertTrue(savedWidth > 0)
        fun assertActual() {
            val actual = rule.onNodeWithTag("fixed-keyboard-card").fetchSemanticsNode().boundsInRoot
            assertEquals(expected.width, actual.width, 3f)
            assertEquals(expected.left, actual.left, 3f)
        }
        assertActual()
        rule.runOnUiThread { inputMethodManager.hideSoftInputFromWindow(editor.windowToken, 0) }
        instrumentation.waitForIdleSync()
        rule.runOnUiThread { editor.requestFocus(); inputMethodManager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("language-key-control", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertActual()
        rule.onNodeWithContentDescription("键盘调节").performClick()
        rule.onNodeWithTag("keyboard-resize-fixed-move-bar", useUnmergedTree = true).performTouchInput {
            swipe(center, center - Offset(before.width * .3f, 0f), 400)
        }
        shell("input keyevent KEYCODE_BACK")
        rule.waitUntil(5000) { rule.onAllNodesWithTag("keyboard-resize-frame", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        assertActual()
        assertEquals(savedWidth, SettingsPreferences.getFixedWidthDp(context, landscape))
        assertEquals(savedX, SettingsPreferences.getFixedOffsetX(context, landscape))
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
