package com.kingzcheung.xime.handwriting

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.ui.keyboard.HandwritingKeyboardLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 真实触摸（含抖动和跨区域移动），不使用 performClick 绕过手势分发。 */
class HandwritingTouchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun functionKeysOwnTheirTouchesAtBothHeightsAndNeverWriteInk(): Unit = runBlocking {
        assertTrue(withContext(Dispatchers.IO) {
            HandwritingEngine.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        })
        val events = mutableListOf<String>()
        val panelHeightState = mutableStateOf(300.dp)
        rule.setContent {
            MaterialTheme {
                HandwritingKeyboardLayout(onKeyPress = { events += it },
                    onRecognition = { events += "result" }, bottomPaddingDp = 0,
                    keyTextColor = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(panelHeightState.value).background(Color.White))
            }
        }
        rule.mainClock.autoAdvance = false
        val canvas = rule.onNodeWithTag("handwriting-canvas")
        fun jitter(key: String) {
            rule.onNodeWithTag("handwriting-key:$key").performTouchInput {
                down(center); moveTo(center + Offset(13f, 0f)); moveTo(center + Offset(13f, 5f)); up()
            }
            rule.mainClock.advanceTimeByFrame()
        }
        // 语言提示叠在画布之上；用各高度落笔前的截图比较，而不是假定整个画布纯白。
        lateinit var baseline: PixelMap
        fun snapshot(): PixelMap {
            rule.mainClock.advanceTimeByFrame()
            return canvas.captureToImage().toPixelMap()
        }
        fun blank(): Boolean {
            val pixels = snapshot()
            if (pixels.width != baseline.width || pixels.height != baseline.height) return false
            return (0 until pixels.width).all { x ->
                (0 until pixels.height).all { y ->
                    val before = baseline[x, y]
                    val after = pixels[x, y]
                    abs(before.red - after.red) < 0.08f && abs(before.green - after.green) < 0.08f &&
                        abs(before.blue - after.blue) < 0.08f
                }
            }
        }
        fun stroke() {
            // 避开左上角不透明语言胶囊，确保截图能看到实际笔迹。
            canvas.performTouchInput {
                val y = height * 0.65f
                down(Offset(width * 0.2f, y)); moveTo(Offset(width * 0.4f, y)); moveTo(Offset(width * 0.7f, y)); up()
            }
            rule.mainClock.advanceTimeByFrame()
        }
        val keys = listOf("delete", "space", "number", "symbol", "ime_switch", "，", "。", "enter")
        lateinit var normalBaseline: PixelMap
        for (panelHeight in listOf(300.dp, 180.dp)) {
            rule.runOnIdle { panelHeightState.value = panelHeight }
            rule.mainClock.advanceTimeByFrame()
            rule.runOnIdle { events.clear() }
            baseline = snapshot()
            if (panelHeight == 300.dp) normalBaseline = baseline
            keys.forEach(::jitter)
            rule.mainClock.advanceTimeBy(1200)
            rule.runOnIdle { assertEquals("空白时每次轻微移动的点击只能执行对应按键", keys, events) }
            assertTrue("按功能键不能留下任何笔迹", blank())
        }
        rule.runOnIdle { panelHeightState.value = 300.dp; events.clear() }
        rule.mainClock.advanceTimeByFrame()
        baseline = normalBaseline
        stroke(); assertFalse("实际落笔应渲染", blank())
        jitter("delete")
        rule.mainClock.advanceTimeBy(1500)
        assertTrue("删除清除整轮未识别笔迹", blank())
        rule.runOnIdle { assertTrue("不能删宿主文字或继续识别", events.isEmpty()) }

        // 起点在按钮：拖进画布也不能变成书写。
        val key = rule.onNodeWithTag("handwriting-key:delete")
        val delta = canvas.fetchSemanticsNode().positionInRoot - key.fetchSemanticsNode().positionInRoot
        key.performTouchInput { down(center); moveTo(delta + Offset(80f, 80f)); up() }
        rule.mainClock.advanceTimeBy(1500)
        assertTrue(blank())
        rule.runOnIdle { assertTrue(events.isEmpty()) }

        // 起点在画布：拖到删除键也不能点击删除，笔迹限制在画布内。
        canvas.performTouchInput { down(Offset(80f, 80f)); moveTo(Offset(width + 60f, 80f)); up() }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        jitter("delete")
        assertTrue(blank())

        for (mode in listOf("number", "symbol", "ime_switch")) {
            rule.runOnIdle { events.clear() }
            stroke(); jitter(mode)
            rule.mainClock.advanceTimeBy(1500)
            rule.runOnIdle { assertEquals(listOf(mode), events) }
            assertTrue(blank())
        }
        rule.runOnIdle { events.clear() }
        stroke(); jitter("space")
        rule.waitUntil(20_000) { events.contains("space") }
        rule.runOnIdle { assertEquals("空格须在识别结果后执行一次", listOf("result", "space"), events) }
        assertTrue(blank())
    }
}
