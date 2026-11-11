package com.kingzcheung.xime.ui

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import com.kingzcheung.xime.service.XimeInputMethodService
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.keyboard.OverlayRoute
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.handwriting.HandwritingEngine
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the actual service's mode, IME insets and Back dispatch in a disposable editor. */
@SdkSuppress(minSdkVersion = 30)
class HandwritingOverlayImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun fullscreenMotionKeepsHostInsetsButClipboardExitsHandwriting() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Install the handwriting model before this real-IME check", HandwritingEngine.hasModel(context))
        assertTrue("The actual engine must be ready", RimeConfigHelper.prepareEngine(context))
        val engine = RimeEngine.getInstance()
        val previousSchema = engine.getCurrentSchema()
        val previousAscii = engine.isAsciiMode()
        val previousSavedAscii = engine.getUserConfigBool("var/option/ascii_mode")
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val fixtureKeys = setOf("toolbar_buttons", LanguagePreferences.KEY,
            "floating_mode", "floating_mode_landscape", "fixed_width_dp", "fixed_width_dp_landscape",
            "fixed_offset_x", "fixed_offset_x_landscape", "keyboard_height_dp", "keyboard_height_dp_landscape",
            "keyboard_bottom_padding_dp", "keyboard_bottom_padding_dp_landscape")
        fun fixtureKey(key: String) = key in fixtureKeys || key.startsWith("current_schema") ||
            key.startsWith("last_input_mode_") || key.startsWith("selected_input_profile_")
        val saved = prefs.all.filterKeys(::fixtureKey).mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val enabledBefore = shell("ime list -s").lineSequence().any { it.trim() == ime }
        lateinit var editor: EditText
        var editorReady = false
        var expandedCompactToolbar = false
        var service: XimeInputMethodService? = null
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        fun has(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        fun hostInset(): Int {
            var inset = -1
            rule.runOnUiThread { inset = editor.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: -1 }
            return inset
        }
        try {
            prefs.edit().apply {
                putStringSet(LanguagePreferences.KEY, setOf(InputLanguage.CHINESE.id, InputLanguage.ENGLISH.id))
                putString("toolbar_buttons", "handwriting_lookup,clipboard,emoji,schema")
                for (suffix in listOf("", "_landscape")) {
                    putBoolean("floating_mode$suffix", false)
                    putInt("fixed_width_dp$suffix", 300)
                    putInt("fixed_offset_x$suffix", -50)
                    putInt("keyboard_height_dp$suffix", 260)
                    putInt("keyboard_bottom_padding_dp$suffix", 0)
                }
            }.commit()
            SettingsPreferences.setCurrentSchema(context, "rime_ice")
            assertTrue(engine.switchSchema("rime_ice"))
            engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            shell("ime enable $ime")
            shell("ime set $ime")
            rule.runOnUiThread {
                rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            rule.setContent {
                AndroidView(factory = { EditText(it).also { view ->
                    editor = view
                    editorReady = true
                    view.hint = "全屏手写菜单测试"
                } }, modifier = Modifier.fillMaxWidth().height(80.dp))
            }
            rule.waitUntil(10_000) { editor.hasWindowFocus() }
            rule.runOnUiThread {
                editor.requestFocus()
                imm.restartInput(editor)
                imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            rule.waitUntil(30_000) {
                rule.runOnUiThread { service = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findService) }
                service?.keyboardCallbacks != null && (service?.currentEffectiveKeyboardHeight ?: 0) > 0
            }
            val actualIme = checkNotNull(service)
            rule.waitUntil(30_000) { actualIme.inputReadiness.ticket() != null && !actualIme.uiState.value.isDeploying }
            android.os.SystemClock.sleep(300)
            // A hardware keyboard opens the compact toolbar; fullscreen handwriting can only
            // grow from the expanded soft keyboard, so use the real expand control first.
            if (actualIme.uiState.value.isCompact) {
                rule.waitUntil(10_000) { has("hardware-toolbar-keyboard") }
                rule.onNodeWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).performClick()
                rule.waitUntil(10_000) { !actualIme.uiState.value.isCompact }
                expandedCompactToolbar = true
                android.os.SystemClock.sleep(300)
            }
            val callbacks = checkNotNull(actualIme.keyboardCallbacks)
            rule.runOnUiThread { callbacks.onHandwritingToggle!!.invoke() }
            rule.waitUntil(10_000) { actualIme.keyboardViewModel.page.value == KeyboardPage.Main(MainType.HANDWRITING) }
            rule.waitForIdle()
            android.os.SystemClock.sleep(200)
            saveScreen(context, "handwriting-normal-live")
            val normalHeight = actualIme.currentEffectiveKeyboardHeight
            val normalInset = hostInset()
            fun sampleHeight(): List<Int> {
                val heights = mutableListOf<Int>()
                repeat(45) {
                    rule.mainClock.advanceTimeByFrame()
                    rule.runOnUiThread { heights += actualIme.currentEffectiveKeyboardHeight }
                    android.os.SystemClock.sleep(10)
                }
                return heights
            }
            rule.runOnUiThread { callbacks.onHandwritingExpand!!.invoke(true) }
            val opening = sampleHeight()
            val expandedHeight = actualIme.currentEffectiveKeyboardHeight
            assertTrue("Fullscreen grows: normal=$normalHeight full=$expandedHeight heights=$opening page=${actualIme.keyboardViewModel.page.value} requested=${actualIme.uiState.value.handwritingExpanded} compact=${actualIme.uiState.value.isCompact} resize=${actualIme.uiState.value.showKeyboardResize}", expandedHeight > normalHeight * 1.5)
            assertTrue("Actual service must render intermediate heights: $opening", opening.any { it > normalHeight && it < expandedHeight })
            assertTrue("Host inset must remain normal: $normalInset -> ${hostInset()}", kotlin.math.abs(normalInset - hostInset()) <= 3)
            saveScreen(context, "handwriting-fullscreen-live")
            for (tool in listOf(OverlayRoute.Emoji, OverlayRoute.SchemaList)) {
                rule.runOnUiThread { actualIme.keyboardViewModel.showOverlay(tool) }
                android.os.SystemClock.sleep(300)
                assertEquals(expandedHeight, actualIme.currentEffectiveKeyboardHeight)
                rule.runOnUiThread { actualIme.keyboardViewModel.closeOverlay() }
            }
            rule.runOnUiThread { callbacks.onHandwritingExpand!!.invoke(false) }
            val closing = sampleHeight()
            assertTrue("Closing also passes through intermediate heights: $closing", closing.any { it > normalHeight && it < expandedHeight })
            assertEquals(normalHeight, actualIme.currentEffectiveKeyboardHeight)
            saveScreen(context, "handwriting-normal-live")
            rule.runOnUiThread { callbacks.onHandwritingExpand!!.invoke(true) }
            sampleHeight()
            // Voice is an independent overlay flag. Changing these two pages must never clear it.
            val previousVoice = actualIme.uiState.value.isVoiceMode
            try {
                rule.runOnUiThread {
                    actualIme.uiState.value = actualIme.uiState.value.copy(isVoiceMode = true)
                    actualIme.keyboardViewModel.showOverlay(OverlayRoute.Clipboard())
                }
                sampleHeight()
                assertEquals(normalHeight, actualIme.currentEffectiveKeyboardHeight)
                val clipboard = actualIme.keyboardViewModel.page.value as KeyboardPage.Overlay
                assertEquals(KeyboardPage.Main(MainType.FULL), clipboard.behind)
                assertTrue(actualIme.uiState.value.isVoiceMode)
                assertTrue(!actualIme.uiState.value.handwritingExpanded)
            } finally {
                rule.runOnUiThread { actualIme.uiState.value = actualIme.uiState.value.copy(isVoiceMode = previousVoice) }
            }
            android.os.SystemClock.sleep(250)
            saveScreen(context, "handwriting-to-clipboard-live")
            rule.runOnUiThread {
                actualIme.keyboardViewModel.closeOverlay()
                assertEquals(KeyboardPage.Main(MainType.FULL), actualIme.keyboardViewModel.page.value)
                assertEquals("", editor.text.toString())
            }

        } finally {
            rule.runOnUiThread {
                val actual: XimeInputMethodService? = service
                if (actual != null) {
                    actual.keyboardCallbacks?.onHandwritingExpand?.invoke(false)
                    actual.keyboardViewModel.closeOverlay()
                    val page = actual.keyboardViewModel.page.value
                    if (page is KeyboardPage.Main && page.type == MainType.HANDWRITING) {
                        actual.keyboardCallbacks?.onHandwritingToggle?.invoke()
                    }
                }
            }
            if (editorReady) rule.runOnUiThread {
                imm.hideSoftInputFromWindow(editor.windowToken, 0)
                editor.clearFocus()
            }
            if (expandedCompactToolbar) runCatching { shell("input keyevent KEYCODE_BACK") }
            if (previousSchema.isNotBlank()) engine.switchSchema(previousSchema)
            engine.setOption("ascii_mode", previousAscii)
            engine.setUserConfigBool("var/option/ascii_mode", previousSavedAscii)
            prefs.edit().apply {
                prefs.all.keys.filter(::fixtureKey).forEach(::remove)
                saved.forEach { (key, value) -> when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
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

    private fun saveScreen(context: Context, name: String) {
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = java.io.File(context.cacheDir, "ui-verification").apply { mkdirs() }
        java.io.File(dir, "$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
