package com.kingzcheung.xime.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import android.view.inspector.WindowInspector
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
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.XimeApplication
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.service.XimeInputMethodService
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real IME + disposable editor. Records timing only, never clipboard text or screenshots. */
@SdkSuppress(minSdkVersion = 30)
class ClipboardDragImePerformanceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun repeatedPanelDragsKeepInputAndRecordFrameCosts() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("realIme") == "true")
        val context = instrumentation.targetContext
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        InstrumentationRegistry.getArguments().getString("expectedVersion")?.let {
            assertEquals("measure the intended installed APK", it, installed.versionName)
        }
        val initialActivity = rule.activity
        check(context.applicationContext is XimeApplication) {
            "Use AndroidJUnitRunner with -PuiRenderingOnly=false; the isolated rendering runner cannot start a real IME."
        }
        check(PluginManager.isInitialized) { "The real application must initialize plugins before showing the IME." }
        assertTrue(RimeConfigHelper.prepareEngine(context))
        // Measure the user's current geometry; do not write or restore preferences.
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val enabled = shell("ime list -s").lineSequence().any { it.trim() == ime }
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        lateinit var editor: EditText
        var editorReady = false
        var service: XimeInputMethodService? = null
        var metricsWindow: Window? = null
        var originalExpanded: Boolean? = null
        val frameThread = HandlerThread("clipboard-frame-metrics").apply { start() }
        val frameHandler = Handler(frameThread.looper)
        val samples = Collections.synchronizedList(mutableListOf<List<Double>>())
        val droppedReports = AtomicInteger()
        val gestureDurations = mutableListOf<Long>()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            droppedReports.addAndGet(dropped)
            samples.add(listOf(FrameMetrics.TOTAL_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION,
                FrameMetrics.DRAW_DURATION, FrameMetrics.SYNC_DURATION).map { metrics.getMetric(it) / 1_000_000.0 })
        }
        var testFailure: Throwable? = null
        try {
            shell("ime enable $ime"); shell("ime set $ime")
            rule.runOnUiThread {
                rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            rule.setContent {
                AndroidView(factory = { EditText(it).also { view ->
                    editor = view; editorReady = true; view.hint = "剪贴板拖动性能检查"
                } }, modifier = Modifier.fillMaxWidth().height(80.dp))
            }
            rule.waitUntil(10_000) { editor.hasWindowFocus() }
            rule.runOnUiThread { editor.requestFocus(); imm.restartInput(editor); imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            rule.waitUntil(40_000) {
                rule.runOnUiThread { service = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findService) }
                service?.inputReadiness?.ticket() != null && service?.keyboardCallbacks != null
            }
            val actualIme = checkNotNull(service)
            originalExpanded = actualIme.uiState.value.clipboardImagesExpanded
            val refreshRate = actualIme.window.window?.decorView?.display?.refreshRate ?: 60f
            val displayMetrics = android.util.DisplayMetrics().also { metrics ->
                actualIme.window.window?.decorView?.display?.getRealMetrics(metrics)
            }
            val windowMetrics = (actualIme.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics
            val displayBounds = windowMetrics.bounds
            val bars = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val displayEvidence = buildString {
                appendLine("reported_display_px=${displayMetrics.widthPixels}x${displayMetrics.heightPixels}; density=${displayMetrics.density}; densityDpi=${displayMetrics.densityDpi}; xdpi=${displayMetrics.xdpi}; ydpi=${displayMetrics.ydpi}")
                appendLine("window_px=${displayBounds.width()}x${displayBounds.height()}; available_content_px=${displayBounds.width() - bars.left - bars.right}x${displayBounds.height() - bars.top - bars.bottom}; system_bars_px=$bars")
                appendLine("configuration_dp=${actualIme.resources.configuration.screenWidthDp}x${actualIme.resources.configuration.screenHeightDp}; fontScale=${actualIme.resources.configuration.fontScale}")
                appendLine("keyboard_container_px=${actualIme.keyboardContainer.width}x${actualIme.keyboardContainer.height}; normal_keyboard_dp=${actualIme.currentResolvedKeyboardSize}")
            }
            fun recordImeState(stage: String) {
                rule.runOnUiThread {
                    val state = actualIme.uiState.value
                    val decor = actualIme.window.window?.decorView
                    println("CLIPBOARD_DIAG $stage page=${actualIme.keyboardViewModel.page.value} compact=${state.isCompact} " +
                        "clipboardExpanded=${state.clipboardImagesExpanded} imeShowing=${actualIme.isInputViewShown} " +
                        "imeWindow=${decor?.isShown} imeFocus=${decor?.hasWindowFocus()} " +
                        "container=${actualIme.keyboardContainer.width}x${actualIme.keyboardContainer.height} " +
                        "editorAttached=${editor.isAttachedToWindow} editorFocus=${editor.hasFocus()} " +
                        "editorWindowFocus=${editor.hasWindowFocus()} immActive=${imm.isActive(editor)}")
                }
                val roots = rule.onAllNodes(isRoot(), useUnmergedTree = true)
                roots.fetchSemanticsNodes().indices.forEach { index ->
                    println("CLIPBOARD_DIAG $stage root=$index\n${roots[index].printToString(maxDepth = Int.MAX_VALUE)}")
                }
            }
            rule.waitForIdle()
            recordImeState("before-overlay")
            // An emulator may expose a physical keyboard. The compact toolbar does not
            // render the panel even if the view model already points at Clipboard.
            if (actualIme.uiState.value.isCompact) {
                rule.waitUntil(10_000) {
                    rule.onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).performClick()
                rule.waitUntil(10_000) { !actualIme.uiState.value.isCompact }
            }
            rule.runOnUiThread { actualIme.keyboardViewModel.showOverlay(OverlayRoute.Clipboard()) }
            rule.waitForIdle()
            recordImeState("after-overlay")
            try {
                rule.waitUntil(10_000) { rule.onAllNodesWithTag("clipboard-controls").fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: Throwable) {
                runCatching { recordImeState("overlay-wait-failed") }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            rule.waitForIdle(); SystemClock.sleep(400)
            val normalHeight = actualIme.currentEffectiveKeyboardHeight
            fun drag(expand: Boolean) {
                assertFalse("activity recreation invalidates the comparison", initialActivity.isDestroyed)
                val control = rule.onNodeWithTag("clipboard-controls").fetchSemanticsNode()
                val x = control.positionOnScreen.x + control.size.width * .45f
                val y = control.positionOnScreen.y + control.size.height * .5f
                val distance = 360f * context.resources.displayMetrics.density * if (expand) -1f else 1f
                val started = SystemClock.uptimeMillis()
                fun inject(action: Int, currentY: Float) {
                    val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x, currentY, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try {
                        assertTrue("touch injection rejected: expand=$expand action=$action start=($x,$y) point=($x,$currentY) " +
                            "elapsed=${SystemClock.uptimeMillis() - started}ms activityDestroyed=${initialActivity.isDestroyed} " +
                            "expanded=${actualIme.uiState.value.clipboardImagesExpanded}",
                            instrumentation.uiAutomation.injectInputEvent(event, true))
                    } finally { event.recycle() }
                }
                inject(MotionEvent.ACTION_DOWN, y)
                repeat(40) { step -> SystemClock.sleep(8); inject(MotionEvent.ACTION_MOVE, y + distance * (step + 1) / 40f) }
                inject(MotionEvent.ACTION_UP, y + distance)
                gestureDurations += SystemClock.uptimeMillis() - started
                SystemClock.sleep(600); rule.waitForIdle()
                rule.runOnIdle {
                    assertEquals("gesture must reach its intended endpoint", expand, actualIme.uiState.value.clipboardImagesExpanded)
                    assertTrue(editor.hasFocus()); assertEquals("", editor.text.toString())
                    if (!expand) assertEquals(normalHeight, actualIme.currentEffectiveKeyboardHeight)
                }
            }
            drag(true); drag(false) // Warm up image/layout caches without including startup.
            gestureDurations.clear()
            rule.runOnUiThread {
                metricsWindow = actualIme.window.window
                checkNotNull(metricsWindow).addOnFrameMetricsAvailableListener(listener, frameHandler)
            }
            // The current panel settles with only a few rendered frames per drag;
            // exercise more real drags rather than relaxing the sample-size check.
            repeat(10) { drag(true); drag(false) }
            rule.runOnUiThread { metricsWindow?.removeOnFrameMetricsAvailableListener(listener); metricsWindow = null }
            val drained = CountDownLatch(1)
            frameHandler.post { drained.countDown() }
            assertTrue("drain already queued frame reports", drained.await(5, TimeUnit.SECONDS))
            val snapshot = synchronized(samples) { samples.toList() }
            val decor = actualIme.window.window?.decorView
            assertTrue("must measure actual rendered frames: count=${snapshot.size}, dropped=${droppedReports.get()}, " +
                "hardwareAccelerated=${decor?.isHardwareAccelerated}, shown=${decor?.isShown}, " +
                "durations=$gestureDurations", snapshot.size >= 30)
            val label = InstrumentationRegistry.getArguments().getString("evidenceLabel")?.takeIf { it.matches(Regex("[a-z0-9-]+")) } ?: "sample"
            val dir = File(context.getExternalFilesDir(null), "clipboard-drag-performance").apply { mkdirs() }
            File(dir, "$label.csv").writeText("total_ms,layout_measure_ms,draw_ms,sync_ms\n" + snapshot.joinToString("\n") { it.joinToString(",") })
            File(dir, "$label-summary.txt").writeText(buildString {
                appendLine("installed_version=${installed.versionName}; versionCode=${installed.longVersionCode}")
                appendLine("frames=${snapshot.size}; normalHeightDp=$normalHeight; theme=${actualIme.uiState.value.themeId}; refreshRate=$refreshRate")
                appendLine("dropped_frame_reports=${droppedReports.get()}; move_events_per_drag=40; actual_drag_durations_ms=${gestureDurations.joinToString(",")}")
                append(displayEvidence)
                for ((index, name) in listOf("total", "layout_measure", "draw", "sync").withIndex()) {
                    val values = snapshot.map { it[index] }.sorted()
                    appendLine("$name p50=${values[values.size / 2]} p95=${values[((values.size - 1) * .95).toInt()]} max=${values.last()}")
                }
                appendLine("frames_over_16_7ms=${snapshot.count { it[0] > 16.7 }}; frames_over_33_3ms=${snapshot.count { it[0] > 33.3 }}")
                appendLine("frames_over_display_budget=${snapshot.count { it[0] > 1000.0 / refreshRate }}")
            })
        } catch (failure: Throwable) {
            testFailure = failure
            val partial = synchronized(samples) { samples.toList() }
            if (partial.isNotEmpty()) {
                val label = InstrumentationRegistry.getArguments().getString("evidenceLabel")
                    ?.takeIf { it.matches(Regex("[a-z0-9-]+")) } ?: "sample"
                val dir = File(context.getExternalFilesDir(null), "clipboard-drag-performance").apply { mkdirs() }
                File(dir, "$label-failed.csv").writeText("total_ms,layout_measure_ms,draw_ms,sync_ms\n" +
                    partial.joinToString("\n") { it.joinToString(",") })
                File(dir, "$label-failed.txt").writeText("Incomplete run; not a passing benchmark.\n" +
                    "version=${installed.versionName}/${installed.longVersionCode}; droppedReports=${droppedReports.get()}\n" +
                    "completed_drag_durations_ms=${gestureDurations.joinToString(",")}\n${failure.message}\n")
            }
            throw failure
        } finally {
            // Composition failures must not prevent the remaining cleanup or IME restoration.
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(cleanupFailures::add)
            }
            cleanup { instrumentation.runOnMainSync {
                metricsWindow?.removeOnFrameMetricsAvailableListener(listener)
                originalExpanded?.let { expanded -> service?.let { imeService ->
                    imeService.uiState.value = imeService.uiState.value.copy(clipboardImagesExpanded = expanded)
                } }
                service?.keyboardViewModel?.closeOverlay()
                if (editorReady) { imm.hideSoftInputFromWindow(editor.windowToken, 0); editor.clearFocus() }
            } }
            cleanup { frameThread.quitSafely() }
            cleanup { if (!previousIme.isNullOrBlank()) shell("ime set $previousIme") }
            cleanup { if (!enabled) shell("ime disable $ime") }
            if (cleanupFailures.isNotEmpty()) {
                val failure = testFailure ?: cleanupFailures.first()
                cleanupFailures.filter { it !== failure }.forEach(failure::addSuppressed)
                if (testFailure == null) throw failure
            }
        }
    }

    private fun findService(view: View): XimeInputMethodService? {
        var context: Context? = view.context
        while (context != null) {
            if (context is XimeInputMethodService) return context
            context = (context as? ContextWrapper)?.baseContext?.takeUnless { it === context }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) findService(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
