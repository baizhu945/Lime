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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * 同一输入框分别用已安装的原 Xime 和整合沙箱显示键盘，各存一张截图用于外观对照。
 * 只读取两个输入法自己的界面，不读取输入框之外的文本。
 */
class KeyboardAppearanceComparisonTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureOriginalXimeAndMigrationKeyboards() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        lateinit var editor: EditText
        try {
            rule.setContent {
                AndroidView(factory = { EditText(it).also { edit -> editor = edit; edit.hint = "键盘外观对照" } },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
            }
            for ((name, ime) in listOf(
                "original-xime" to "com.kingzcheung.xime/.service.XimeInputMethodService",
                "migration" to "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService",
            )) {
                shell("ime set $ime")
                SystemClock.sleep(2_000)
                rule.runOnUiThread {
                    editor.requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
                // 首次部署中日词典在真机上可能要几分钟，等它结束再截图。
                SystemClock.sleep(20_000)
                screenshot("keyboard-$name")
            }
        } finally {
            if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
        }
    }

    private fun shell(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
    ).bufferedReader().use { it.readText() }

    private fun screenshot(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val dir = File(i.targetContext.getExternalFilesDir(null), "appearance").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().also { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
