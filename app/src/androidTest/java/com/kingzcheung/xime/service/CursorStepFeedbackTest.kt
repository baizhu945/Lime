package com.kingzcheung.xime.service

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kingzcheung.xime.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CursorStepFeedbackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun spaceDragNotifiesEachRealCharacterAndRetainsFocusAtEdges() {
        lateinit var editor: EditText
        val cursor = EditorCursor()
        var ticks = 0
        var density = 1f
        var dragPx = 70f
        rule.setContent {
            density = LocalDensity.current.density
            dragPx = 5f * 10f * cursorUnitPx(
                keyboardPhysicalMetrics(
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
                    LocalView.current.display, density), density)
            Column {
                AndroidView(factory = { EditText(it).also { v -> editor = v; v.setText("a😀bc"); v.showSoftInputOnFocus = false } }, modifier = Modifier.size(250.dp, 80.dp))
                CompositionLocalProvider(
                    LocalKeyboardInputPreferences provides KeyboardInputPreferences(cursorStepDp = 10f),
                    LocalKeyboardInputActions provides KeyboardInputActions(onCursorMove = { steps ->
                        cursor.moveWithFeedback(editor.onCreateInputConnection(EditorInfo())!!, steps) { ticks++ }
                    })
                ) { SpaceKeyButton({}, Color.DarkGray, Color.White, modifier = Modifier.size(240.dp, 60.dp).testTag("space")) }
            }
        }
        rule.runOnUiThread { editor.requestFocus(); editor.setSelection(0) }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(320)
        rule.onNodeWithTag("space").performTouchInput { moveTo(center + Offset(dragPx, 0f)) }
        rule.runOnIdle { assertEquals(4, ticks); assertEquals(5, editor.selectionEnd); assertTrue(editor.hasFocus()) }
        rule.onNodeWithTag("space").performTouchInput { moveTo(center + Offset(dragPx + 20f * density, 0f)); up() }
        rule.runOnIdle { assertEquals(4, ticks); assertEquals("a😀bc", editor.text.toString()) }
    }
}
