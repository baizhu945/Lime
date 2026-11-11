package com.kingzcheung.xime.handwriting

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.ui.keyboard.HandwritingKeyboardLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HandwritingPauseTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun waitsHalfSecondAndRestartsOnTouchDownBeforeTheNextStroke(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(withContext(Dispatchers.IO) { HandwritingEngine.initialize(context) })
        var recognized = 0
        var newCharacters = 0
        val windowSizes = mutableListOf<Int>()
        rule.setContent {
            MaterialTheme {
                HandwritingKeyboardLayout(
                    onRecognition = { recognized++; windowSizes += it.sumOf { segment -> segment.strokeCount } },
                    onNewCharacter = { newCharacters++ }, bottomPaddingDp = 0, keyTextColor = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(300.dp).testTag("writing").background(Color.White),
                )
            }
        }
        rule.mainClock.autoAdvance = false
        // The drawing area is inset by the side keys, so inject in canvas-local
        // coordinates instead of the surrounding keyboard root.
        val writing = rule.onNodeWithTag("handwriting-canvas", useUnmergedTree = true)
        fun oldInkPixels(): Int {
            rule.mainClock.advanceTimeByFrame()
            val pixels = writing.captureToImage().toPixelMap()
            // Only the old stroke's area: the new touch-down below is a legitimate
            // dot and must not be mistaken for restored old ink.
            return (0 until minOf(200, pixels.width)).sumOf { x ->
                (0 until minOf(190, pixels.height)).count { y -> pixels[x, y].red < 0.8f }
            }
        }
        writing.performTouchInput {
            down(Offset(50f, 100f)); moveTo(Offset(80f, 100f)); moveTo(Offset(180f, 100f)); up()
        }
        rule.mainClock.advanceTimeBy(350)
        rule.runOnIdle { assertEquals("未到0.5秒不能上屏", 0, recognized) }
        assertTrue("识别前实际画布应包含笔迹", oldInkPixels() > 0)
        writing.performTouchInput { down(Offset(110f, 60f)) }
        rule.mainClock.advanceTimeBy(1200)
        rule.runOnIdle { assertEquals("落笔后即使尚未移动也必须停止上一轮识别", 0, recognized) }
        writing.performTouchInput { moveTo(Offset(110f, 90f)); moveTo(Offset(110f, 160f)); up() }
        rule.mainClock.advanceTimeBy(350)
        rule.runOnIdle { assertEquals("下一笔重新计时", 0, recognized) }
        rule.mainClock.advanceTimeBy(250)
        rule.waitUntil(20_000) { recognized == 1 }
        assertEquals("识别完成立即清空旧笔迹", 0, oldInkPixels())
        writing.performTouchInput { down(Offset(260f, 60f)) }
        assertEquals("重新落笔也不能恢复上一字笔迹", 0, oldInkPixels())
        writing.performTouchInput { moveTo(Offset(260f, 90f)); moveTo(Offset(260f, 180f)); up() }
        rule.mainClock.advanceTimeBy(1100)
        rule.waitUntil(20_000) { recognized == 2 }
        rule.runOnIdle {
            assertEquals("新字只识别自己的笔画", listOf(2, 1), windowSizes)
            assertEquals("每轮新字开始时结束旧候选替换期", 2, newCharacters)
        }
        rule.mainClock.advanceTimeBy(4000)
        writing.performTouchInput { down(Offset(260f, 60f)); moveTo(Offset(280f, 60f)); moveTo(Offset(350f, 60f)); up() }
        rule.mainClock.advanceTimeBy(1100)
        rule.waitUntil(20_000) { recognized == 3 }
        rule.runOnIdle { assertEquals(listOf(2, 1, 1), windowSizes); assertEquals(3, newCharacters) }
        assertEquals("长暂停后旧字同样保持清空", 0, oldInkPixels())
    }
}
