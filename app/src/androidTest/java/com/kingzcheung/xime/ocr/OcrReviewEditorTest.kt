package com.kingzcheung.xime.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.service.QuickSendFormCodeEditTextHolder
import com.kingzcheung.xime.service.QuickSendFormEditTextHolder
import com.kingzcheung.xime.ui.keyboard.QuickSendFormArea
import com.kingzcheung.xime.ui.saveUiEvidence
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OcrReviewEditorTest {
    @get:Rule val rule = createComposeRule()

    private fun saveScreen(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(instrumentation.targetContext.cacheDir, "ui-verification").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun popupScreenBounds(): Rect = rule.runOnIdle {
        val popup = QuickSendFormEditTextHolder.editText!!.rootView
        val xy = IntArray(2)
        popup.getLocationOnScreen(xy)
        Rect(xy[0], xy[1], xy[0] + popup.width, xy[1] + popup.height)
    }

    @Test fun ocrUsesClipboardPopupGeometryAndOnlySaveDeliversEditedDraft() {
        var ocr by mutableStateOf(false)
        var visible by mutableStateOf(true)
        var copied: String? = null
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xff101018))) {
                    Column(Modifier.align(Alignment.BottomStart).width(420.dp).height(220.dp).testTag("host")) {
                        if (visible) {
                            if (ocr) OcrReviewEditor("中文 Hello", Color.White, Color(0xff252630), Color(0xffc6b0f4),
                                {}, { copied = it; visible = false }, { visible = false }, { visible = false })
                            else QuickSendFormArea(Color.Transparent, Color.White, Color(0xffc6b0f4), true,
                                initialText = "剪贴板测试内容", cardBgColor = Color(0xff252630), editingItemId = 7L,
                                onClose = { _, _ -> }, onCancel = {}, onFocusChange = {}, onCodeFocusChange = {})
                        }
                        Box(Modifier.fillMaxSize().background(Color(0xff33343f)), contentAlignment = Alignment.Center) {
                            Text("测试键盘区域 · 高度保持不变", color = Color.White)
                        }
                    }
                }
            }
        }
        val keyboard = rule.onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
        val clipboardBounds = popupScreenBounds()
        saveUiEvidence("clipboard-editor-reference", rule.onNodeWithTag("clipboard-text-editor"))
        rule.runOnIdle { ocr = true }
        rule.onNodeWithText("编辑识别文字").assertDoesNotExist()
        rule.onNode(isPopup()).assertExists()
        val ocrBounds = popupScreenBounds()
        assertEquals(clipboardBounds.left, ocrBounds.left)
        assertEquals(clipboardBounds.right, ocrBounds.right)
        assertEquals(clipboardBounds.bottom, ocrBounds.bottom)
        assertTrue("OCR omits the optional code row", ocrBounds.height() < clipboardBounds.height())
        assertEquals(keyboard, rule.onNodeWithTag("host").fetchSemanticsNode().boundsInRoot)
        assertEquals(0f, rule.onNodeWithTag("clipboard-editor-anchor").fetchSemanticsNode().boundsInRoot.height, 0f)
        saveUiEvidence("ocr-clipboard-editor", rule.onNodeWithTag("clipboard-text-editor"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "ui-verification/ocr-editor-with-keyboard.png")
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        rule.runOnIdle {
            assertNull(copied)
            assertNull(QuickSendFormCodeEditTextHolder.editText)
            QuickSendFormEditTextHolder.editText!!.append(" 123")
        }
        rule.onNodeWithText("保存").performClick()
        rule.runOnIdle {
            assertEquals("中文 Hello 123", copied)
            assertNull(QuickSendFormEditTextHolder.editText)
        }
    }

    @Test fun editorGrowsWithTextThenScrollsWithinSpaceAboveKeyboardAndShrinksAgain() {
        var keyboardHeight by mutableStateOf(220.dp)
        rule.setContent {
            // Use a phone density: at the test default 1.0 the 2400px window is
            // 2400dp tall, so a 340dp keyboard never constrains a 40-line draft.
            CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                    Box(Modifier.width(360.dp).height(keyboardHeight).testTag("adaptive-keyboard")) {
                        OcrReviewEditor("短文本", Color.White, Color(0xff252630), Color.Cyan, {}, {}, {}, {})
                    }
                }
            }
            }
        }
        val short = popupScreenBounds()
        rule.runOnIdle { QuickSendFormEditTextHolder.editText!!.setText("") }
        rule.waitForIdle()
        assertTrue("Empty OCR draft should fit one line plus actions", popupScreenBounds().height() <= short.height())
        rule.onNodeWithText("编辑识别文字").assertDoesNotExist()
        saveUiEvidence("ocr-editor-empty-compact", rule.onNodeWithTag("clipboard-text-editor"))
        rule.runOnIdle { QuickSendFormEditTextHolder.editText!!.setText("短文本") }
        saveUiEvidence("ocr-editor-short", rule.onNodeWithTag("clipboard-text-editor"))
        saveScreen("ocr-editor-short-screen")
        rule.runOnIdle { QuickSendFormEditTextHolder.editText!!.setText((1..40).joinToString("\n") { "第 $it 行识别文本" }) }
        rule.waitUntil(5_000) { popupScreenBounds().height() > short.height() }
        val long = popupScreenBounds()
        assertEquals(short.bottom, long.bottom)
        assertTrue(long.top >= 0)
        rule.onNodeWithText("保存").assertIsDisplayed()
        saveUiEvidence("ocr-editor-long", rule.onNodeWithTag("clipboard-text-editor"))
        saveScreen("ocr-editor-long-screen")
        rule.runOnIdle { keyboardHeight = 340.dp }
        // The popup is a separate window; Compose idle alone does not guarantee its
        // layout has been measured after the anchor moves.
        rule.waitUntil(5_000) { popupScreenBounds().height() < long.height() }
        assertTrue("Resizing the keyboard reduces available editor height",
            popupScreenBounds().height() < long.height())
        rule.onNodeWithText("保存").assertIsDisplayed()
        rule.runOnIdle { QuickSendFormEditTextHolder.editText!!.setText("短文本") }
        rule.waitUntil(5_000) { popupScreenBounds().height() == short.height() }
    }

    @Test fun cancelAndRetakeCloseWithoutSavingAndReleaseDraft() {
        var visible by mutableStateOf(true)
        var retakes = 0
        var saves = 0
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                    Box(Modifier.width(360.dp).height(220.dp)) {
                        if (visible) OcrReviewEditor("不保存的识别草稿", Color.White, Color.DarkGray, Color.Cyan,
                            {}, { saves++ }, { visible = false }, { retakes++; visible = false })
                    }
                }
            }
        }
        rule.onNodeWithText("重拍").performClick()
        rule.runOnIdle {
            assertEquals(1, retakes)
            assertEquals(0, saves)
            assertNull(QuickSendFormEditTextHolder.editText)
            visible = true
        }
        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle {
            assertEquals(0, saves)
            assertNull(QuickSendFormEditTextHolder.editText)
        }
    }
}
