package com.kingzcheung.xime.service

import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 编码显示在输入框时，宿主在拼音组合中途重启输入（setText）或收起键盘：
 * 不能把未完成的拼音留成普通文字；宿主改过的文字一字不碰。只用临时输入框和虚构文字。
 */
@SdkSuppress(minSdkVersion = 29)
class InlineCompositionRestartTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepFixtureVisible() {
        rule.runOnUiThread { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test fun hostRestartsAndHidingNeverLeaveRawPinyin() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realIme") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val imeId = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
        val wasEnabled = shell("ime list -s").lineSequence().any { it.trim() == imeId }
        val previousLocation = SettingsPreferences.getInputTextLocation(context)
        lateinit var host: FrameLayout
        lateinit var editor: EditText
        var service: XimeInputMethodService? = null
        var fieldId = 300
        fun text(): String { var value = ""; rule.runOnUiThread { value = editor.text.toString() }; return value }
        fun openField() {
            rule.runOnUiThread {
                editor = EditText(rule.activity).apply { id = ++fieldId; inputType = InputType.TYPE_CLASS_TEXT }
                host.removeAllViews()
                host.addView(editor, FrameLayout.LayoutParams(-1, 180))
                editor.requestFocus()
                imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            rule.waitUntil(30_000) {
                rule.runOnUiThread { service = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findService) }
                service?.currentInputEditorInfo?.fieldId == fieldId && service?.inputReadiness?.ticket() != null &&
                    service?.keyboardCallbacks != null
            }
        }
        fun drain() {
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            rule.runOnUiThread { checkNotNull(service).keyRouter.postRimeJob { withContext(Dispatchers.Main) { done.set(true) } } }
            rule.waitUntil(10_000) { done.get() }
        }
        fun type(keys: String) = keys.forEach { key ->
            rule.runOnUiThread { checkNotNull(service?.keyboardCallbacks).onKeyPress(key.toString(), false) }
        }
        fun restartWith(newText: String, caret: Int) {
            val previous = checkNotNull(service).uiState.value.inputSessionId
            rule.runOnUiThread { editor.setText(newText); editor.setSelection(caret) }
            // 等新会话就绪：未就绪时排队的检查请求会被拒收。
            rule.waitUntil(10_000) {
                checkNotNull(service).uiState.value.inputSessionId != previous && checkNotNull(service).inputReadiness.ticket() != null
            }
            drain()
        }
        fun composePinyin(prefix: String): String {
            // setText restarts the input connection asynchronously; don't submit the
            // barrier or keystrokes under the session that it is about to replace.
            restartWith(prefix, prefix.length)
            type("nihao")
            drain()
            rule.waitUntil(10_000) { checkNotNull(service).candidateState.value.isComposing && text().length > prefix.length }
            return text().removePrefix(prefix)
        }
        try {
            SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
            shell("ime enable $imeId"); shell("ime set $imeId")
            rule.setContent { AndroidView(factory = { FrameLayout(it).also { frame -> host = frame } }) }
            rule.waitForIdle()
            openField()
            drain()
            val actual = checkNotNull(service)
            rule.runOnUiThread { actual.schemaController.switchSchema("rime_ice") }
            drain()
            rule.waitUntil(10_000) { !actual.uiState.value.isAsciiMode }

            // 宿主 setText 重启，光标在编码末尾：拼音被撤掉，前文保留。
            val prefix = "前文，"
            val shown = composePinyin(prefix)
            restartWith(prefix + shown, prefix.length + shown.length)
            rule.waitUntil(10_000) { text() == prefix }
            var caret = -1
            rule.runOnUiThread { caret = editor.selectionStart }
            assertEquals(prefix.length, caret)

            // 宿主把光标放回编码倒数第一个字符前（截图二）：同样撤掉。
            val shown2 = composePinyin(prefix)
            restartWith(prefix + shown2, prefix.length + shown2.length - 1)
            rule.waitUntil(10_000) { text() == prefix }

            // 宿主改过文字：拼不回原编码，一字不碰。
            val shown3 = composePinyin(prefix)
            val edited = prefix + shown3.dropLast(1)
            restartWith(edited, edited.length)
            drain()
            assertEquals(edited, text())

            // 重启后照常输入。
            restartWith("", 0)
            type("nihao")
            drain()
            rule.waitUntil(10_000) { actual.candidateState.value.isComposing && text().isNotEmpty() }

            // 收起键盘：未完成的拼音不留在框里。
            rule.runOnUiThread { imm.hideSoftInputFromWindow(editor.windowToken, 0) }
            rule.waitUntil(10_000) { text().isEmpty() }
        } finally {
            SettingsPreferences.setInputTextLocation(context, previousLocation)
            rule.runOnUiThread { runCatching { editor.text.clear(); imm.hideSoftInputFromWindow(editor.windowToken, 0) } }
            if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
            if (!wasEnabled && previousIme != imeId) shell("ime disable $imeId")
        }
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
