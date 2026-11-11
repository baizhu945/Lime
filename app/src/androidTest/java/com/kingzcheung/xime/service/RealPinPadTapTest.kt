package com.kingzcheung.xime.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.XimeApplication
import com.kingzcheung.xime.rime.RimeConfigHelper
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Taps the real on-screen number pad (not the keyboard callbacks) in PIN-style fields.
 * Fictional disposable editors only; digits are typed by the test itself.
 */
@SdkSuppress(minSdkVersion = 29)
class RealPinPadTapTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepFixtureVisible() {
        rule.runOnUiThread { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    private data class Field(val name: String, val inputType: Int, val imeOptions: Int = 0,
        val secureWindow: Boolean = false, val keyEventsOnly: Boolean = false)

    /** Like PIN views in secure apps: committed text is ignored, only key events are handled. */
    private class KeyOnlyEditor(context: Context) : EditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): android.view.inputmethod.InputConnection? {
            val connection = super.onCreateInputConnection(outAttrs) ?: return null
            return object : android.view.inputmethod.InputConnectionWrapper(connection, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int) = false
                override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = false
            }
        }
    }

    @Test fun pinFieldsAcceptTappedDigits() = kotlinx.coroutines.runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realIme") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.applicationContext is XimeApplication)
        check(RimeConfigHelper.prepareEngine(context))
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val imeId = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        lateinit var host: FrameLayout
        lateinit var editor: EditText
        var fieldId = 500
        val numberPassword = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val fields = listOf(
            Field("number(first)", InputType.TYPE_CLASS_NUMBER),
            Field("numberPassword", numberPassword),
            Field("numberPassword+noLearning", numberPassword, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
            Field("numberPassword+secureWindow", numberPassword, secureWindow = true),
            Field("textPassword+noLearning", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
            Field("numberPassword(after text)", numberPassword),
            Field("numberPassword(key events only)", numberPassword, keyEventsOnly = true),
            Field("number", InputType.TYPE_CLASS_NUMBER),
        )
        val results = linkedMapOf<String, String>()
        try {
            shell("ime enable $imeId"); shell("ime set $imeId")
            rule.setContent { AndroidView(factory = { FrameLayout(it).also { frame -> host = frame } }) }
            rule.waitForIdle()
            for (field in fields) {
                rule.runOnUiThread {
                    if (field.secureWindow) rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else rule.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    editor = (if (field.keyEventsOnly) KeyOnlyEditor(rule.activity) else EditText(rule.activity)).apply {
                        id = ++fieldId; inputType = field.inputType; imeOptions = field.imeOptions
                    }
                    host.removeAllViews()
                    host.addView(editor, FrameLayout.LayoutParams(-1, 180))
                    editor.requestFocus()
                    imm.showSoftInput(editor, 0)
                }
                var service: XimeInputMethodService? = null
                rule.waitUntil(30_000) {
                    rule.runOnUiThread { service = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findService) }
                    service?.currentInputEditorInfo?.fieldId == fieldId && service?.inputReadiness?.ticket() != null
                }
                // The Compose test rule controls the frame clock, including the IME's
                // ComposeView in this process. Sleeping does not advance a pending frame:
                // the model can be on the new page while old keys are still rendered.
                rule.waitForIdle()
                // The emulator's host keyboard counts as a physical keyboard; show the on-screen
                // pad as the toolbar's "展开屏幕键盘" button would.
                rule.runOnUiThread {
                    XimeInputMethodService::class.java.getDeclaredMethod("showScreenKeyboardFromHardware",
                        com.kingzcheung.xime.keyboard.OverlayRoute::class.java)
                        .apply { isAccessible = true }.invoke(service, null)
                }
                rule.waitForIdle()
                rule.runOnUiThread {
                    android.util.Log.i("RealPinPadTapTest", "${field.name} fieldId=$fieldId page=${service?.keyboardViewModel?.page?.value} lifecycle=${service?.lifecycle?.currentState} compact=${service?.uiState?.value?.isCompact}")
                }
                // Manual inspection mode: keep the field open so the pad can be driven over adb.
                InstrumentationRegistry.getArguments().getString("holdSeconds")?.toLongOrNull()?.let { hold ->
                    android.util.Log.i("RealPinPadTapTest", "holding ${field.name}")
                    var waited = 0L
                    while (waited < hold * 1000) {
                        Thread.sleep(1000); waited += 1000
                        var text = ""
                        rule.runOnUiThread { text = editor.text.toString() }
                        android.util.Log.i("RealPinPadTapTest", "${field.name} length=${text.length}")
                    }
                }
                shell("screencap -p /sdcard/Download/pin-${fieldId}.png")
                // The text-password editor must really show and accept letters before the
                // next field switches to the number pad; otherwise a stale pad can pass.
                val expected = if (field.name.startsWith("textPassword")) "abc" else "123"
                val tapped = expected.map { key -> tapImeKey(key.toString()).also { rule.waitForIdle() } }
                var actual = ""
                rule.runOnUiThread { actual = editor.text.toString() }
                results[field.name] = "found=${tapped.count { it }}/${tapped.size} typedLength=${actual.length} matches=${actual == expected} " +
                    "policy=${service?.editorInputPolicy?.field?.kind}"
                android.util.Log.i("RealPinPadTapTest", "${field.name}: ${results[field.name]}")
            }
        } finally {
            rule.runOnUiThread { rule.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
        }
        // Every numeric field receives 123; the intervening text field receives abc.
        for ((name, summary) in results) {
            assertEquals("$name -> $summary", true, summary.startsWith("found=3/3 typedLength=3 matches=true"))
        }
    }

    /** Finds a key labelled [label] in the IME window via accessibility and taps it with a real touch. */
    private fun tapImeKey(label: String): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val window = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return false
        val root = window.root ?: return false
        val node = findExact(root, label) ?: return false
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        android.util.Log.i("RealPinPadTapTest", "tap label=$label nodeBounds=$bounds x=${bounds.centerX()} y=${bounds.centerY()} clickable=${node.isClickable}")
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        Thread.sleep(300)
        return true
    }

    private fun findExact(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            return target ?: node
        }
        for (index in 0 until node.childCount) node.getChild(index)?.let { child -> findExact(child, label)?.let { return it } }
        return null
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun findService(view: View): XimeInputMethodService? {
        var context: Context? = view.context
        while (context != null) {
            if (context is XimeInputMethodService) return context
            context = (context as? ContextWrapper)?.baseContext?.takeUnless { it === context }
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) findService(view.getChildAt(index))?.let { return it }
        return null
    }
}
