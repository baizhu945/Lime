package com.kingzcheung.xime.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.*
import com.kingzcheung.xime.ui.menubar.SchemaListView
import com.kingzcheung.xime.ui.menubar.dragOrderItem
import androidx.compose.ui.platform.testTag
import com.kingzcheung.xime.ui.settings.reorderEnabledModes
import com.kingzcheung.xime.ui.settings.orderedInstalledModeIds
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InputModeOrderTest {
    @get:Rule val rule = createComposeRule()

    @Test fun draggedCardMovesBeforeDropAndEdgeScrollsWithoutSavingUntilRelease() {
        lateinit var state: com.kingzcheung.xime.ui.menubar.DragOrderState
        var saved: List<String>? = null
        val ids = (0..15).map { "mode$it" }
        rule.setContent {
            state = com.kingzcheung.xime.ui.menubar.rememberDragOrder(ids) { saved = it }
            androidx.compose.foundation.lazy.LazyColumn(state = state.list, modifier = Modifier.size(320.dp, 240.dp).testTag("drag-list")) {
                items(state.order.size, key = { state.order[it] }) { index ->
                    val id = state.order[index]
                    Box(Modifier.then(dragOrderItem(state, id)).fillMaxWidth().height(48.dp).testTag(id))
                }
            }
        }
        val before = rule.onNodeWithTag("mode0").fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag("mode0").performTouchInput { down(Offset(20f, center.y)) }
            rule.mainClock.advanceTimeBy(700)
            rule.runOnUiThread { assertEquals("mode0", state.dragging); assertEquals(0, state.list.firstVisibleItemScrollOffset) }
            rule.onRoot().performTouchInput { moveBy(Offset(0f, 12f)) }
            rule.mainClock.advanceTimeBy(32)
            val moved = rule.onNodeWithTag("mode0").fetchSemanticsNode().boundsInRoot
            assertTrue("卡片在松手之前应跟手移动", moved.top > before.top)
            assertNull(saved)
            val viewport = rule.onNodeWithTag("drag-list").fetchSemanticsNode().boundsInRoot
            rule.onRoot().performTouchInput { moveTo(Offset(before.center.x, viewport.bottom - 8f)) }
            repeat(80) { rule.mainClock.advanceTimeByFrame(); Thread.sleep(3) }
            rule.runOnUiThread {
                assertTrue("边缘拖动应自动滚动", state.list.firstVisibleItemIndex > 0)
                assertTrue("拖动应连续穿过中间卡片", state.order.indexOf("mode0") >= 4)
                assertNull(saved)
            }
            rule.onRoot().performTouchInput { up() }
            rule.runOnUiThread {
                assertEquals(state.order, saved)
                state.start(state.list.layoutInfo.visibleItemsInfo.first { it.key in state.order }.key as String)
                state.drag(80f)
                state.cancel()
                assertEquals(saved, state.order)
            }
        } finally { rule.mainClock.autoAdvance = true }
        rule.waitForIdle()
    }

    @Test fun settingsOrderUsesKeyboardPreferenceAndKeepsInactiveSlots() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "scheme-order-${System.nanoTime()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences("$namespace-$name", mode)
        }
        val installed = listOf(
            SchemaMeta("rime_ice", "中文26键"),
            SchemaMeta("japanese", "日语"),
            SchemaMeta("t9_pinyin", "中文九键"),
        )
        InputModes.saveOrder(context, listOf("rime_ice", "japanese", "t9_pinyin", InputModes.ENGLISH))

        assertTrue(reorderEnabledModes(context, installed, setOf("rime_ice", "t9_pinyin"),
            englishEnabled = true, reordered = listOf("t9_pinyin", "rime_ice", InputModes.ENGLISH)))
        assertEquals(listOf("t9_pinyin", "japanese", "rime_ice", InputModes.ENGLISH),
            orderedInstalledModeIds(context, installed))

        assertTrue(reorderEnabledModes(context, installed, setOf("rime_ice", "t9_pinyin"),
            englishEnabled = true, reordered = listOf("t9_pinyin", InputModes.ENGLISH, "rime_ice")))
        assertEquals(listOf("t9_pinyin", "japanese", InputModes.ENGLISH, "rime_ice"),
            InputModes.ordered(context, installed.map {
                SchemaInfo(it.schemaId, it.name, it.version, it.author, it.description)
            }).map { it.schemaId })
        assertFalse(reorderEnabledModes(context, installed, setOf("rime_ice", "t9_pinyin"),
            englishEnabled = true, reordered = listOf("t9_pinyin", InputModes.ENGLISH, InputModes.ENGLISH)))
    }

    @Test fun schemeManagementDoesNotExposeOrRewriteCombinationOrder() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "scheme-management-${System.nanoTime()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences("$namespace-$name", mode)
        }
        val initial = listOf(SchemaInfo("t9_pinyin", "拼音", "", "", ""), SchemaInfo("japanese", "日语", "", "", ""))
        InputModes.saveOrder(context, listOf("t9_pinyin", "rime_ice", "japanese", InputModes.ENGLISH))
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val saved = prefs.getString(InputModes.ORDER_KEY, null)
        rule.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides context) { MaterialTheme {
                SchemaListView(InputModes.ordered(context, initial), "t9_pinyin", Color.White,
                    Color.Blue, Color.Black, Color.LightGray, {}, modifier = Modifier.size(360.dp, 250.dp))
            } }
        }
        // Scheme management now opens the dedicated layout manager from this tile;
        // the entry itself must never expose or rewrite the keyboard combination order.
        rule.onNodeWithTag("profile-layout-management").assertIsDisplayed()
        rule.onNodeWithText("组合顺序").assertDoesNotExist()
        rule.onNodeWithTag("profile-edit-order").assertDoesNotExist()
        rule.onNodeWithTag("panel-scheme:t9_pinyin").assertIsSelected()
        assertEquals(saved, prefs.getString(InputModes.ORDER_KEY, null))
    }
}
