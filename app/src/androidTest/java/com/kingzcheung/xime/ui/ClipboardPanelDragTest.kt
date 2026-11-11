package com.kingzcheung.xime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ClipboardPanelDragTest {
    @get:Rule val rule = createComposeRule()
    private val open = mutableStateOf(true)
    private val expanded = mutableStateOf(false)
    private var panel: ClipboardPanelExpansion? = null
    private var clicks = 0

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val expansion = rememberClipboardPanelExpansion(open.value, 0L,
                    ClipboardPanelBounds(140, 300), expanded.value) { expanded.value = it }
                panel = expansion
                CompositionLocalProvider(LocalClipboardPanelExpansion provides expansion) {
                    Box(Modifier.requiredSize(280.dp, 440.dp).testTag("drag-host")) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .height((expansion?.height ?: 140f).dp).testTag("drag-panel")) {
                            Box(Modifier.fillMaxWidth().height(48.dp).clipboardPanelExpandGesture()
                                .testTag("drag-toolbar")) {
                                Text("剪贴板", Modifier.fillMaxSize().clickable { clicks++ })
                            }
                            Spacer(Modifier.weight(1f).fillMaxWidth().testTag("list-body"))
                        }
                    }
                }
            }
        }
    }

    private var listScroll: ScrollState? = null
    private var longClicks = 0

    @OptIn(ExperimentalFoundationApi::class)
    private fun showScrollable(expand: Boolean = true) {
        expanded.value = expand
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val expansion = rememberClipboardPanelExpansion(true, 0L,
                    ClipboardPanelBounds(140, 300), expanded.value) { expanded.value = it }
                panel = expansion
                val scroll = rememberScrollState()
                listScroll = scroll
                CompositionLocalProvider(LocalClipboardPanelExpansion provides expansion) {
                    Box(Modifier.requiredSize(280.dp, 440.dp).testTag("drag-host")) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .height((expansion?.height ?: 140f).dp).testTag("drag-panel")) {
                            Box(Modifier.fillMaxWidth().height(48.dp).clipboardPanelExpandGesture().testTag("drag-toolbar"))
                            Column(Modifier.weight(1f).fillMaxWidth().testTag("scroll-records")
                                .clipboardPanelCollapseGesture(true) { scroll.value == 0 }
                                .verticalScroll(scroll)) {
                                repeat(20) { index ->
                                    Box(Modifier.fillMaxWidth().height(60.dp).testTag("record:$index")
                                        .combinedClickable(onClick = { clicks++ }, onLongClick = { longClicks++ })) {
                                        Text("Record $index")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun topOfExpandedListCollapsesAfterASmallDownwardDragWithoutClicking() {
        showScrollable()
        rule.onNodeWithTag("scroll-records").performTouchInput {
            swipe(Offset(100f, 30f), Offset(100f, 70f), 300)
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertFalse(expanded.value)
            assertEquals(140f, panel!!.height, 1f)
            assertEquals(0, clicks)
            assertEquals(0, listScroll!!.value)
        }
    }

    @Test fun listAwayFromTopScrollsWithoutCollapsingThePanel() {
        showScrollable()
        rule.onNodeWithTag("record:8").performScrollTo()
        rule.runOnIdle { assertTrue(listScroll!!.value > 100) }
        rule.onNodeWithTag("scroll-records").performTouchInput {
            swipe(Offset(100f, 30f), Offset(100f, 70f), 300)
        }
        rule.runOnIdle { assertTrue(expanded.value); assertEquals(300f, panel!!.height, 1f) }
    }

    @Test fun clicksLongPressesAndUpwardScrollingDoNotBecomePanelDrags() {
        showScrollable()
        rule.onNodeWithTag("record:0").performTouchInput { click() }
        rule.onNodeWithTag("record:0").performTouchInput { longClick() }
        rule.runOnIdle {
            assertEquals(1, clicks); assertEquals(1, longClicks)
            assertTrue(expanded.value); assertEquals(300f, panel!!.height, 1f)
        }
        rule.onNodeWithTag("scroll-records").performTouchInput {
            swipe(Offset(100f, 170f), Offset(100f, 40f), 300)
        }
        rule.runOnIdle {
            assertTrue(listScroll!!.value > 0)
            assertTrue(expanded.value); assertEquals(300f, panel!!.height, 1f)
        }
    }

    @Test fun collapsedListAndCancelledTopDragKeepTheirOriginalPanelHeight() {
        showScrollable(false)
        rule.onNodeWithTag("scroll-records").performTouchInput {
            swipe(Offset(100f, 10f), Offset(100f, 65f), 300)
        }
        rule.runOnIdle { assertFalse(expanded.value); assertEquals(140f, panel!!.height, 1f); expanded.value = true }
        rule.waitForIdle()
        rule.onNodeWithTag("scroll-records").performTouchInput { down(Offset(100f, 30f)); moveBy(Offset(0f, 70f)); cancel() }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(expanded.value); assertEquals(300f, panel!!.height, 1f) }
    }

    @Test fun normalToolbarAndListBodyDoNotExpand() {
        open.value = false
        show()
        rule.onNodeWithTag("drag-toolbar").performTouchInput { swipeUp() }
        rule.runOnIdle { assertNull(panel); assertFalse(expanded.value); open.value = true }
        rule.onNodeWithTag("list-body").performTouchInput { swipeUp() }
        rule.runOnIdle { assertEquals(140f, panel!!.height, .1f) }
    }

    @Test fun quickShortHeaderFlickExpandsAndSlowSubThresholdDragReturns() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val distance = android.view.ViewConfiguration.get(context).scaledTouchSlop + 5f
        // Density is fixed to 1 below: the gesture must cross touch slop but stay
        // below the panel's 32dp distance threshold without a velocity projection.
        assertTrue("touch slop too large for a sub-threshold drag: $distance", distance < 32f)
        show()
        rule.onNodeWithTag("drag-host").performTouchInput {
            swipe(Offset(100f, 324f), Offset(100f, 324f - distance), 40)
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(expanded.value); assertEquals(300f, panel!!.height, 1f); expanded.value = false }
        rule.waitForIdle()
        rule.onNodeWithTag("drag-host").performTouchInput {
            swipe(Offset(100f, 324f), Offset(100f, 324f - distance), 800)
        }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(expanded.value); assertEquals(140f, panel!!.height, 1f); assertEquals(0, clicks) }
    }

    @Test fun movingToolbarTracksScreenDistanceAndSettlesWithoutClicking() {
        show()
        rule.onNodeWithTag("drag-host").performTouchInput {
            down(Offset(100f, 324f)); moveTo(Offset(100f, 274f))
        }
        rule.runOnIdle { assertEquals(190f, panel!!.height, 1f); assertFalse(expanded.value) }
        rule.onNodeWithTag("drag-host").performTouchInput { moveTo(Offset(100f, 214f)) }
        rule.runOnIdle { assertEquals(250f, panel!!.height, 1f) }
        rule.onNodeWithTag("drag-host").performTouchInput { up() }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(expanded.value); assertEquals(300f, panel!!.height, 1f); assertEquals(0, clicks) }
        rule.onNodeWithTag("drag-host").performTouchInput {
            down(Offset(100f, 164f)); moveTo(Offset(100f, 274f)); up()
        }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(expanded.value); assertEquals(140f, panel!!.height, 1f) }
    }

    @Test fun cancelledDragAndReopeningReturnToNormalHeight() {
        show()
        rule.onNodeWithTag("drag-host").performTouchInput {
            down(Offset(100f, 324f)); moveTo(Offset(100f, 214f)); cancel()
        }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(expanded.value); assertEquals(140f, panel!!.height, 1f); expanded.value = true }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(300f, panel!!.height, 1f); open.value = false }
        rule.runOnIdle { assertFalse(expanded.value); open.value = true }
        rule.runOnIdle { assertEquals(140f, panel!!.height, 1f) }
        rule.onNodeWithTag("drag-toolbar").performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, clicks) }
    }
}
