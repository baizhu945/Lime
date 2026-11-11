package com.kingzcheung.xime.handwriting

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.keyboard.HandwritingKeyboardLayout
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 输入法手写板：按住侧键一次拖过去，路过的每一笔都擦掉（与训练画板共用 PenEraser）。 */
class HandwritingPadEraserTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun holdSideButtonDragErasesEveryStrokeOnTheWay() {
        rule.setContent {
            MaterialTheme {
                HandwritingKeyboardLayout(onKeyPress = {}, onRecognition = {}, bottomPaddingDp = 0,
                    keyTextColor = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(320.dp).background(Color.White))
            }
        }
        rule.waitForIdle()
        // 冻结时钟：停顿识别不会在测试中途把笔迹提交掉
        rule.mainClock.autoAdvance = false
        val canvas = rule.onNodeWithTag("handwriting-canvas")
        val b = canvas.fetchSemanticsNode().boundsInWindow
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })

        fun send(down: Long, action: Int, x: Float, y: Float, buttons: Int) {
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f })
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords, 0, buttons,
                1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            rule.runOnUiThread { rule.activity.window.decorView.dispatchTouchEvent(e) }
            e.recycle()
            rule.mainClock.advanceTimeByFrame()
        }
        fun image() = canvas.captureToImage().toPixelMap()
        // 画布上叠有语言提示胶囊，并非纯白。以落笔前的画面为基准，只检查每条线所在的区域。
        val baseline = image()
        fun hasInkNear(px: androidx.compose.ui.graphics.PixelMap, x: Float): Boolean {
            val cx = (x - b.left).toInt()
            val y0 = (b.height * 0.55f).toInt()
            val y1 = (b.height * 0.65f).toInt()
            return (cx - 8..cx + 8).any { col -> (y0..y1).any { row ->
                val a = baseline[col, row]
                val c = px[col, row]
                abs(a.red - c.red) > 0.15f || abs(a.green - c.green) > 0.15f || abs(a.blue - c.blue) > 0.15f
            } }
        }

        val top = b.top + b.height * 0.2f
        val bottom = b.top + b.height * 0.7f
        val xs = (1..3).map { b.left + b.width * it / 4 }
        for (x in xs) {
            val d = SystemClock.uptimeMillis()
            send(d, MotionEvent.ACTION_DOWN, x, top, 0)
            for (i in 1..6) send(d, MotionEvent.ACTION_MOVE, x, top + (bottom - top) * i / 6, 0)
            send(d, MotionEvent.ACTION_UP, x, bottom, 0)
        }
        val drawn = image()
        xs.forEachIndexed { i, x -> assertTrue("第 ${i + 1} 笔竖线应已画出", hasInkNear(drawn, x)) }

        val y = (top + bottom) / 2
        val d = SystemClock.uptimeMillis()
        send(d, MotionEvent.ACTION_DOWN, b.left + 10, y, MotionEvent.BUTTON_STYLUS_PRIMARY)
        for (i in 1..12) send(d, MotionEvent.ACTION_MOVE, b.left + 10 + (b.width - 20) * i / 12, y, MotionEvent.BUTTON_STYLUS_PRIMARY)
        send(d, MotionEvent.ACTION_UP, b.right - 10, y, MotionEvent.BUTTON_STYLUS_PRIMARY)
        val erased = image()
        xs.forEachIndexed { i, x -> assertTrue("一次拖动应擦掉第 ${i + 1} 笔", !hasInkNear(erased, x)) }
    }
}
