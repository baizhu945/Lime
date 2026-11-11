package com.kingzcheung.xime.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.XimeApplication
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.service.XimeInputMethodService
import com.kingzcheung.xime.service.getActiveBottomInsetPx
import com.kingzcheung.xime.service.tryGetVisibleNavBarHeightDp
import com.kingzcheung.xime.settings.KeyboardHeightProfiles
import com.kingzcheung.xime.settings.SettingsPreferences
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Actual IME window, saved fixed geometry and real pointer input; no substitute keyboard. */
@SdkSuppress(minSdkVersion = 30)
class RealFloatingDockGeometryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun armedDockFrameMatchesRestoredKeyboardOnEveryScreenEdge() = runBlocking {
        assumeTrue("Opt in to real IME interaction", InstrumentationRegistry.getArguments().getString("realIme") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.javaClass.name == "androidx.test.runner.AndroidJUnitRunner") {
            "Use AndroidJUnitRunner with -PuiRenderingOnly=false."
        }
        val context = instrumentation.targetContext
        check(context.applicationContext is XimeApplication && PluginManager.isInitialized)
        InstrumentationRegistry.getArguments().getString("expectedVersion")?.let { expected ->
            assertEquals("verify the installed package under test", expected,
                context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        }
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val ledger = PreferenceLedger(prefs)
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val imeId = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val wasEnabled = shell("ime list -s").lineSequence().any { it.trim() == imeId }
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val initialActivity = rule.activity
        lateinit var editor: EditText
        var editorReady = false
        var service: XimeInputMethodService? = null
        var suffix = ""
        var touchDown = false
        var downTime = 0L
        var pointerX = 0f
        var pointerY = 0f
        var dragStartRect: Rect? = null
        var dragStartOffsetX = 0
        var dragStartOffsetY = 0
        var failure: Throwable? = null
        val evidence = mutableListOf<String>()

        fun expectDragPersistence(docking: Boolean) {
            val actual = checkNotNull(service)
            val start = checkNotNull(dragStartRect)
            val shown = screenRect("floating-keyboard-card")
            val density = actual.resources.displayMetrics.density
            // The production card previews locally and commits the total delta only on UP/CANCEL.
            val expectedX = dragStartOffsetX + ((shown.left - start.left) / density).roundToInt()
            val expectedY = dragStartOffsetY - ((shown.top - start.top) / density).roundToInt()
            evidence += "drag-end expectation from rendered delta: x=$expectedX, y=$expectedY, " +
                "storedY=${prefs.all["floating_offset_y$suffix"]}, docking=$docking"
            ledger.expect("floating_offset_x$suffix", expectedX)
            ledger.expect("floating_offset_y$suffix", expectedY)
            if (docking) {
                ledger.expect("floating_mode$suffix", false)
                ledger.expect("keyboard_opacity$suffix", 1f)
            }
        }
        fun inject(action: Int, x: Float = pointerX, y: Float = pointerY) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            pointerX = x; pointerY = y
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("touch rejected: action=$action screen=($x,$y) elapsed=${SystemClock.uptimeMillis() - downTime} " +
                    "activityDestroyed=${initialActivity.isDestroyed} floating=${service?.uiState?.value?.isFloatingMode}",
                    instrumentation.uiAutomation.injectInputEvent(event, true))
                touchDown = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
            } finally { event.recycle() }
        }
        try {
            shell("ime enable $imeId"); shell("ime set $imeId")
            rule.runOnUiThread {
                rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            rule.setContent {
                AndroidView(factory = { EditText(it).also { view ->
                    editor = view; editorReady = true; view.hint = "悬浮恢复边界测试"
                } }, modifier = Modifier.fillMaxWidth().height(80.dp))
            }
            rule.waitUntil(10_000) { editorReady && editor.hasWindowFocus() }
            rule.runOnUiThread { editor.requestFocus(); imm.restartInput(editor); imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            rule.waitUntil(40_000) {
                rule.runOnUiThread { service = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findService) }
                service?.inputReadiness?.ticket() != null && service?.keyboardCallbacks != null
            }
            val actual = checkNotNull(service)
            val landscape = actual.resources.configuration.screenWidthDp > actual.resources.configuration.screenHeightDp
            suffix = if (landscape) "_landscape" else ""
            val density = actual.resources.displayMetrics.density
            val screenWidthDp = actual.resources.configuration.screenWidthDp
            val width = (screenWidthDp * .72f).roundToInt().coerceAtLeast(240).coerceAtMost(screenWidthDp - 32)
            val offset = minOf(36, (screenWidthDp - width) / 2).coerceAtLeast(1)
            val height = 300.coerceIn(KeyboardHeightProfiles.fixedBounds(actual, landscape))
            val floatWidth = (width - 40).coerceIn(200, 400)
            ledger.capture("keyboard_opacity$suffix")
            // Only these keys are temporarily changed. Language, schema and clipboard remain untouched.
            for (signedOffset in listOf(-offset, offset)) {
                assertFalse("activity recreation would invalidate absolute coordinates", initialActivity.isDestroyed)
                rule.runOnUiThread {
                    ledger.expect("keyboard_opacity$suffix", 1f)
                    ledger.write(mapOf("fixed_width_dp$suffix" to width, "fixed_offset_x$suffix" to signedOffset,
                        "keyboard_height_dp$suffix" to height, "keyboard_bottom_padding_dp" to 48,
                        "floating_width_dp$suffix" to floatWidth, "floating_height_dp$suffix" to 260,
                        "floating_offset_x$suffix" to 0, "floating_offset_y$suffix" to 112,
                        "floating_mode$suffix" to false))
                    checkNotNull(actual.keyboardCallbacks?.onFloatingModeChange).invoke(false)
                }
                var expandedHardwareToolbar = false
                rule.waitUntil(10_000) {
                    if (!expandedHardwareToolbar && exists("hardware-toolbar-keyboard")) {
                        expandedHardwareToolbar = true
                        rule.onNodeWithTag("hardware-toolbar-keyboard").performClick()
                    }
                    !actual.uiState.value.isFloatingMode && actual.uiState.value.fixedOffsetX == signedOffset &&
                        actual.uiState.value.keyboardBottomPaddingDp == 48 && exists("fixed-keyboard-card")
                }
                rule.waitForIdle()
                val before = screenRect("fixed-keyboard-card")
                capture("offset-$signedOffset-before", before)
                assertTrue("fixture retains nonzero saved horizontal offset", abs(signedOffset) > 0)
                assertTrue("real keycaps must exist before floating", exists("space-key"))
                rule.runOnUiThread {
                    ledger.expect("floating_mode$suffix", true)
                    checkNotNull(actual.keyboardCallbacks?.onFloatingModeChange).invoke(true)
                }
                rule.waitUntil(10_000) { actual.uiState.value.isFloatingMode && exists("floating-drag-bar") }
                rule.waitForIdle()
                val bar = screenRect("floating-drag-bar")
                val card = screenRect("floating-keyboard-card")
                val host = IntArray(2)
                rule.runOnUiThread { actual.keyboardContainer.getLocationOnScreen(host) }
                val minBottomDp = maxOf(tryGetVisibleNavBarHeightDp(actual, actual.window.window),
                    ceil(getActiveBottomInsetPx(actual.window.window) / density).toInt())
                val bottom = host[1] + actual.keyboardContainer.height - minBottomDp * density
                val travel = (bottom - card.bottom).coerceAtLeast(0f)
                assertTrue("floating fixture starts above the docking region", travel > 16f * density)
                dragStartRect = card
                dragStartOffsetX = actual.uiState.value.floatingOffsetX
                dragStartOffsetY = actual.uiState.value.floatingOffsetY
                inject(MotionEvent.ACTION_DOWN, bar.center.x, bar.center.y)
                repeat(24) { step ->
                    SystemClock.sleep(10)
                    inject(MotionEvent.ACTION_MOVE, bar.center.x, bar.center.y + travel * (step + 1) / 24f)
                }
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithTag("floating-dock-preview").fetchSemanticsNodes().singleOrNull()
                        ?.config?.getOrNull(SemanticsProperties.StateDescription) == "ready"
                }
                // Read two completed frames after the hold; don't equate ready alone with final animation geometry.
                SystemClock.sleep(80)
                val preview = screenRect("floating-dock-preview")
                SystemClock.sleep(50)
                assertEdges("ready preview must stop moving", preview, screenRect("floating-dock-preview"), 1f)
                val floating = screenRect("floating-keyboard-card")
                capture("offset-$signedOffset-armed", Rect(minOf(preview.left, floating.left), minOf(preview.top, floating.top),
                    maxOf(preview.right, floating.right), maxOf(preview.bottom, floating.bottom)))
                assertEdges("preview must target saved fixed rectangle", before, preview, density)
                expectDragPersistence(docking = true)
                inject(MotionEvent.ACTION_UP)
                rule.waitUntil(10_000) { !actual.uiState.value.isFloatingMode && exists("fixed-keyboard-card") && !exists("floating-drag-bar") }
                rule.waitForIdle()
                val restored = screenRect("fixed-keyboard-card")
                evidence += "docked state: x=${actual.uiState.value.floatingOffsetX}, " +
                    "y=${actual.uiState.value.floatingOffsetY}, storedY=${prefs.all["floating_offset_y$suffix"]}"
                capture("offset-$signedOffset-restored", restored)
                assertEdges("all four preview edges must match real fixed keyboard", preview, restored, density)
                assertEdges("restored keyboard must keep initial saved geometry", before, restored, density)
                val space = screenRect("space-key")
                assertTrue("real keycap is inside restored surface", restored.contains(space.center))
                // A Chinese q may remain composing; accept that real editor/candidate path without changing language.
                val letter = listOf("qwerty-key:q", "qwerty-key:Q").firstOrNull(::exists)
                val oldText = editor.text.toString()
                val oldPreedit = actual.candidateState.value.inputText
                val oldEnglish = actual.candidateState.value.pendingEnglishText
                rule.onNodeWithTag(letter ?: "space-key").performTouchInput { click() }
                rule.waitUntil(10_000) {
                    editor.text.toString() != oldText || actual.candidateState.value.inputText != oldPreedit ||
                        actual.candidateState.value.pendingEnglishText != oldEnglish
                }
                assertTrue("host editor keeps focus after docking and key input", editor.hasFocus())
                evidence += "offsetDp=$signedOffset; density=$density; before=$before; armed=$preview; restored=$restored; realKey=${letter ?: "space-key"}"
                rule.runOnUiThread { editor.text.clear(); imm.restartInput(editor) }
                rule.waitForIdle()
            }
        } catch (error: Throwable) {
            failure = error
            evidence += "FAILED: ${error.javaClass.simpleName}: ${error.message}"
            throw error
        } finally {
            val cleanupErrors = mutableListOf<Throwable>()
            fun cleanup(block: () -> Unit) { runCatching(block).exceptionOrNull()?.let(cleanupErrors::add) }
            cleanup { if (touchDown) {
                expectDragPersistence(docking = false)
                inject(MotionEvent.ACTION_CANCEL)
            } }
            cleanup { instrumentation.runOnMainSync {
                evidence += "geometry preference ledger: ${ledger.describe()}"
                evidence += "preserved_external_preference_changes=${ledger.restore().joinToString(",")}"
                service?.takeIf { ledger.hasMutations }?.let { actual ->
                    val currentLandscape = actual.resources.configuration.screenWidthDp > actual.resources.configuration.screenHeightDp
                    actual.restoreKeyboardDisplayMode(SettingsPreferences.isFloatingMode(actual, currentLandscape), persist = false)
                }
            } }
            cleanup { instrumentation.runOnMainSync {
                if (editorReady) { imm.hideSoftInputFromWindow(editor.windowToken, 0); editor.clearFocus() }
            } }
            cleanup {
                val currentIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                if (currentIme == imeId && !previousIme.isNullOrBlank()) shell("ime set $previousIme")
                if (!wasEnabled && previousIme != imeId &&
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) != imeId) shell("ime disable $imeId")
            }
            cleanup {
                val dir = File(context.getExternalFilesDir(null), "floating-dock-geometry").apply { mkdirs() }
                File(dir, "latest.txt").writeText(evidence.joinToString("\n"))
            }
            if (cleanupErrors.isNotEmpty()) {
                val primary = failure ?: cleanupErrors.first()
                cleanupErrors.filter { it !== primary }.forEach(primary::addSuppressed)
                if (failure == null) throw primary
            }
        }
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun screenRect(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().let {
        Rect(it.positionOnScreen.x, it.positionOnScreen.y,
            it.positionOnScreen.x + it.size.width, it.positionOnScreen.y + it.size.height)
    }
    private fun assertEdges(label: String, expected: Rect, actual: Rect, tolerancePx: Float) {
        for ((edge, values) in listOf("left" to (expected.left to actual.left), "top" to (expected.top to actual.top),
            "right" to (expected.right to actual.right), "bottom" to (expected.bottom to actual.bottom))) {
            assertEquals("$label: $edge expected=$expected actual=$actual", values.first, values.second, tolerancePx)
        }
    }
    private fun capture(name: String, rect: Rect) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val left = floor(rect.left).toInt().coerceIn(0, screen.width - 1)
            val top = floor(rect.top).toInt().coerceIn(0, screen.height - 1)
            val right = ceil(rect.right).toInt().coerceIn(left + 1, screen.width)
            val bottom = ceil(rect.bottom).toInt().coerceIn(top + 1, screen.height)
            val crop = Bitmap.createBitmap(screen, left, top, right - left, bottom - top)
            try {
                val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "floating-dock-geometry").apply { mkdirs() }
                File(dir, "$name.png").outputStream().use { assertTrue(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { if (crop !== screen) crop.recycle() }
        } finally { screen.recycle() }
    }
    private fun findService(view: View): XimeInputMethodService? {
        var context: Context? = view.context
        while (context != null) {
            if (context is XimeInputMethodService) return context
            context = (context as? ContextWrapper)?.baseContext?.takeUnless { it === context }
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) findService(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    /** Compare each key with the last fixture/service write; never restore an entire preferences snapshot. */
    private class PreferenceLedger(private val prefs: SharedPreferences) {
        private val original = linkedMapOf<String, Any?>()
        private val written = linkedMapOf<String, Any?>()
        private val pending = linkedMapOf<String, Any?>()
        val hasMutations get() = written.isNotEmpty() || pending.isNotEmpty()
        fun describe() = "original=$original, written=$written, pending=$pending"
        fun capture(key: String) {
            if (!original.containsKey(key)) original[key] = prefs.all[key]
        }
        fun expect(key: String, value: Any?) {
            acknowledgeServiceWrites()
            capture(key)
            pending[key] = value
        }
        private fun acknowledgeServiceWrites() {
            val current = prefs.all
            val completed = pending.filter { (key, value) -> current[key] == value }
            written.putAll(completed)
            completed.keys.forEach(pending::remove)
        }
        fun write(values: Map<String, Any>) {
            acknowledgeServiceWrites()
            values.keys.forEach { key ->
                check(!written.containsKey(key) || prefs.all[key] == written[key]) {
                    "Preference $key was changed externally; expected=${written[key]}, actual=${prefs.all[key]}, " +
                        "pending=${pending[key]}; stop instead of overwriting it"
                }
            }
            val edit = prefs.edit()
            values.forEach { (key, value) -> expect(key, value); put(edit, key, value) }
            check(edit.commit()) { "Cannot save temporary geometry" }
            acknowledgeServiceWrites()
        }
        fun restore(): List<String> {
            // Failed/rejected UP must not replace the last actual write with a value never saved.
            acknowledgeServiceWrites()
            val preserved = mutableListOf<String>()
            val edit = prefs.edit()
            written.forEach { (key, value) ->
                if (prefs.all[key] == value) put(edit, key, original[key]) else preserved += key
            }
            check(edit.commit()) { "Cannot restore temporary geometry" }
            return preserved
        }
        private fun put(edit: SharedPreferences.Editor, key: String, value: Any?) {
            when (value) {
                null -> edit.remove(key)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                else -> error("Unsupported geometry preference $key")
            }
        }
    }
}
