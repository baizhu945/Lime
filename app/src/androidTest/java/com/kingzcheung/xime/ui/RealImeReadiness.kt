package com.kingzcheung.xime.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.service.XimeInputMethodService

/** Await the real service's input epoch, not merely a process-wide native session. */
internal fun ComposeTestRule.awaitRealImeInputReady(
    editor: EditText,
    imm: InputMethodManager,
    engine: RimeEngine,
    timeoutMillis: Long,
): XimeInputMethodService {
    check(Build.VERSION.SDK_INT >= 29) { "Real IME window inspection requires API 29 or newer" }
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    var service: XimeInputMethodService? = null
    runOnUiThread {
        editor.requestFocus()
        imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }
    waitUntil((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)) {
        runOnUiThread {
            service = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::imeServiceOwner)
        }
        service?.inputReadiness?.ticket() != null && !engine.isMaintaining()
    }
    return checkNotNull(service)
}

private fun imeServiceOwner(view: View): XimeInputMethodService? {
    var context: Context? = view.context
    while (context != null) {
        if (context is XimeInputMethodService) return context
        context = (context as? ContextWrapper)?.baseContext?.takeUnless { it === context }
    }
    if (view is ViewGroup) for (index in 0 until view.childCount)
        imeServiceOwner(view.getChildAt(index))?.let { return it }
    return null
}
