package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.espresso.Espresso
import com.kingzcheung.xime.ui.keyboard.FloatingKeyboardContainer
import com.kingzcheung.xime.ui.keyboard.KeyboardDockTarget
import com.kingzcheung.xime.ui.keyboard.KeyButton
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardKeyMetrics
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardResizeOverlay
import com.kingzcheung.xime.ui.keyboard.SwipeableKeyButton
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FloatingKeyboardUiTest {
    @get:Rule val rule = createComposeRule()
    private var density = 1f
    private var docks = 0
    private var releasedDragY = 0f

    private fun showFloating(hostHeightDp: Int = 328) {
        rule.setContent {
            density = LocalDensity.current.density
            MaterialTheme {
                var floating by remember { mutableStateOf(true) }
                Box(Modifier.size(340.dp, hostHeightDp.dp).testTag("floating-window")) {
                    FloatingKeyboardContainer(
                        isFloatingMode = floating, scaleFactor = 0.85f, fontScaleFactor = 1f,
                        contentHeightDp = 300, offsetX = 0, offsetY = 0,
                        onDrag = { dx, dy -> releasedDragY = dy; android.util.Log.i("FloatingDockTest", "released dx=$dx dy=$dy host=$hostHeightDp") }, onDragEnd = {},
                        onDock = { docks++; floating = false },
                        dockTarget = KeyboardDockTarget(340, 328, 0, 0, 0),
                    ) { Box(Modifier.fillMaxSize().testTag("keyboard-content")) }
                }
            }
        }
    }

    @Test fun dragBarUsesOnlyOneShortBottomRow() {
        showFloating()
        val content = rule.onNodeWithTag("keyboard-content").fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithTag("floating-drag-bar").fetchSemanticsNode().boundsInRoot
        val card = rule.onNodeWithTag("floating-keyboard-card").fetchSemanticsNode().boundsInRoot
        assertEquals(content.bottom, bar.top, 1f)
        assertEquals(card.bottom, bar.bottom, 1f)
        assertEquals(28f * density, bar.height, 1f)
        assertEquals(300f * density, content.height, 1f)
    }

    @Test fun edgePreviewExpandsThenReleaseRestoresWithoutLeavingTheDragRow() {
        showFloating()
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            down(center); moveBy(Offset(24f * density, 0f))
        }
        rule.waitUntil(2_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "ready")).fetchSemanticsNodes().isNotEmpty()
        }
        val preview = rule.onNodeWithTag("floating-dock-preview").fetchSemanticsNode().boundsInRoot
        val window = rule.onNodeWithTag("floating-window").fetchSemanticsNode().boundsInRoot
        assertEquals(window.left, preview.left, 1f)
        assertEquals(window.right, preview.right, 1f)
        assertEquals(window.top, preview.top, 1f)
        assertEquals(window.bottom, preview.bottom, 1f)
        assertEquals(340f * density, preview.width, 1f)
        assertEquals(328f * density, preview.height, 1f)
        rule.onNodeWithTag("floating-drag-bar").performTouchInput { up() }
        rule.onNodeWithTag("floating-drag-bar").assertDoesNotExist()
        rule.onNodeWithTag("floating-dock-preview").assertDoesNotExist()
        assertEquals(328f * density, rule.onNodeWithTag("keyboard-content").fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(1, docks)
    }

    @Test fun cancellingAnArmedDockKeepsTheFloatingKeyboard() {
        showFloating()
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            down(center); moveBy(Offset(24f * density, 0f))
        }
        rule.waitUntil(2_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "ready")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("floating-drag-bar").performTouchInput { cancel() }
        rule.onNodeWithTag("floating-dock-preview").assertDoesNotExist()
        rule.onNodeWithTag("floating-drag-bar").assertIsDisplayed()
        assertEquals(0, docks)
    }

    @Test fun leavingTheEdgeAndReleasingBeforeRecompositionDoesNotDock() {
        // 328dp card in a 400dp host has 72dp of upward travel, beyond the 12dp bottom edge zone.
        showFloating(hostHeightDp = 400)
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            down(center); moveBy(Offset(24f * density, 0f))
        }
        rule.waitUntil(2_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "ready")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            moveBy(Offset(0f, -80f * density)); up()
        }
        rule.runOnIdle {
            assertTrue("release must have moved the card beyond the bottom edge: $releasedDragY", releasedDragY > 12f)
            assertEquals(0, docks)
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("floating-drag-bar").assertIsDisplayed()
    }

    @Test fun leavingAndReturningInOneFrameRestartsTheHold() {
        showFloating(hostHeightDp = 400)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            down(center); moveBy(Offset(24f * density, 0f))
        }
        rule.mainClock.advanceTimeBy(650L)
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            moveBy(Offset(0f, -80f * density))
            moveBy(Offset(0f, 80f * density))
        }
        rule.mainClock.advanceTimeBy(200L)
        rule.onNodeWithTag("floating-drag-bar").performTouchInput { up() }
        rule.runOnIdle { assertEquals(0, docks) }
    }

    @Test fun unspecifiedControlFontUsesReadableDefault() {
        rule.setContent {
            MaterialTheme {
                Column {
                    @androidx.compose.runtime.Composable
                    fun Sample(label: String, fontScale: Float, prefs: KeyboardInputPreferences, font: TextUnit) {
                        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale),
                            LocalKeyboardInputPreferences provides prefs) {
                            KeyButton(label, {}, Color.Gray, Color.White,
                                modifier = Modifier.size(64.dp, 48.dp), fontSize = font)
                        }
                    }
                    Sample("符号", 1f, KeyboardInputPreferences(keyTextScale = 1f), TextUnit.Unspecified)
                    Sample("字母", 1f, KeyboardInputPreferences(keyTextScale = 1f), 14.sp)
                    Sample("数字", 1f, KeyboardInputPreferences(keyTextScale = 1.2f), TextUnit.Unspecified)
                    Sample("设置", 1f, KeyboardInputPreferences(), TextUnit.Unspecified)
                    Sample("大字", 1.3f, KeyboardInputPreferences(keyTextScale = 1f), TextUnit.Unspecified)
                }
            }
        }
        fun layout(label: String): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().also {
                assertFalse("$label: size=${it.size}, paragraph=${it.multiParagraph.width}x${it.multiParagraph.height}, constraints=${it.layoutInput.constraints}, style=${it.layoutInput.style}", it.hasVisualOverflow)
            }
        }
        val baseline = layout("符号").layoutInput.style.fontSize.value
        assertEquals(18f, KeyboardKeyMetrics.LabelSize.value, 0f)
        assertEquals(KeyboardKeyMetrics.LabelSize.value, baseline, 0.01f)
        assertEquals(14f, layout("字母").layoutInput.style.fontSize.value, 0.01f)
        assertEquals(baseline * 1.2f, layout("数字").layoutInput.style.fontSize.value, 0.01f)
        assertEquals(baseline * 1.15f, layout("设置").layoutInput.style.fontSize.value, 0.01f)
        val accessible = layout("大字").layoutInput.style.fontSize.value
        assertTrue("larger system font should increase rendered size", accessible * 1.3f > baseline)
    }

    @Test fun compressedKeyKeepsTheNumberHintInsideItsBounds() {
        rule.setContent {
            MaterialTheme {
                SwipeableKeyButton(text = "Q", onClick = {}, backgroundColor = Color.Gray,
                    textColor = Color.White, swipeText = "1", onSwipe = {},
                    modifier = Modifier.size(40.dp, 32.dp).testTag("short-key"))
            }
        }
        val key = rule.onNodeWithTag("short-key").fetchSemanticsNode().boundsInRoot
        val hint = rule.onNodeWithText("1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(hint.top >= key.top)
        assertTrue(hint.bottom <= key.bottom)
    }

    @Test fun resizeHasBottomCornerActionsAndAppliesOpacityOnlyOnConfirm() {
        var previewOpacity = 1f
        var savedOpacity = 1f
        var confirms = 0
        showResize(onOpacity = { previewOpacity = it }, onConfirm = { savedOpacity = it; confirms++ })
        rule.onNodeWithContentDescription("取消").assertDoesNotExist()
        val reset = rule.onNodeWithContentDescription("重置").fetchSemanticsNode().boundsInRoot
        val confirm = rule.onNodeWithContentDescription("确认").fetchSemanticsNode().boundsInRoot
        val slider = rule.onNodeWithTag("keyboard-opacity-slider").fetchSemanticsNode().boundsInRoot
        assertTrue(reset.center.x < confirm.center.x)
        assertEquals(reset.top, confirm.top, 1f)
        assertTrue(slider.bottom < confirm.top)
        val toggle = rule.onNodeWithContentDescription("悬浮键盘").fetchSemanticsNode().boundsInRoot
        assertEquals(reset.center.y, toggle.center.y, 1f)
        assertTrue(toggle.left > reset.right && toggle.right < confirm.left)
        rule.onNodeWithTag("keyboard-opacity-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }
        rule.runOnIdle { assertEquals(0.6f, previewOpacity, 0.01f); assertEquals(1f, savedOpacity, 0f); assertEquals(0, confirms) }
        rule.onNodeWithContentDescription("确认").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(0.6f, savedOpacity, 0.01f); assertEquals(1, confirms) }
    }

    @Test fun systemBackCancelsResizeWithoutConfirming() {
        var cancelled = false
        var confirmed = false
        showResize(onCancel = { cancelled = true }, onConfirm = { confirmed = true })
        Espresso.pressBack()
        rule.runOnIdle { assertTrue(cancelled); assertFalse(confirmed) }
    }

    @Test fun resetRestoresOpacityBeforeConfirmation() {
        var opacity = 0.5f
        var savedOpacity = 0.5f
        var confirms = 0
        showResize(initialOpacity = opacity, onOpacity = { opacity = it },
            onConfirm = { savedOpacity = it; confirms++ })
        val label = rule.onNodeWithTag("keyboard-opacity-overlay-label", useUnmergedTree = true)
        label.assertIsDisplayed().assertTextEquals("透明度 50%")
        rule.runOnIdle { assertEquals(0.5f, opacity, 0f); assertEquals(0.5f, savedOpacity, 0f); assertEquals(0, confirms) }
        rule.onNodeWithContentDescription("重置").performClick()
        rule.runOnIdle { assertEquals(1f, opacity, 0f); assertEquals(0.5f, savedOpacity, 0f); assertEquals(0, confirms) }
        label.assertIsDisplayed().assertTextEquals("透明度 0%")
        rule.onNodeWithContentDescription("确认").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1f, savedOpacity, 0f); assertEquals(1, confirms) }
    }

    private fun showResize(initialOpacity: Float = 1f, onOpacity: (Float) -> Unit = {},
        onConfirm: (Float) -> Unit = {}, onCancel: () -> Unit = {}) {
        rule.setContent {
            MaterialTheme {
                KeyboardResizeOverlay(initialHeightDp = 320, defaultHeightDp = 300,
                    currentBottomPaddingDp = 0, isFloatingMode = true, initialOpacity = initialOpacity,
                    onHeightChange = {}, onBottomPaddingChange = {}, onOpacityChange = onOpacity,
                    onReset = {}, onConfirm = { _, _, _, opacity -> onConfirm(opacity) }, onCancel = onCancel,
                    modifier = Modifier.size(340.dp, 400.dp))
            }
        }
    }

    @Test fun restoredUnsafeOffsetCannotPutDragHandleBelowNavigationInset() {
        rule.setContent {
            density = LocalDensity.current.density
            Box(Modifier.size(340.dp, 400.dp).testTag("floating-window")) {
                FloatingKeyboardContainer(isFloatingMode = true, scaleFactor = 0.85f,
                    offsetX = 0, offsetY = -60, minOffsetY = 48, availableHeightDp = 800, contentHeightDp = 300,
                    onDrag = { _, _ -> }, onDragEnd = {}) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        val window = rule.onNodeWithTag("floating-window").fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithTag("floating-drag-bar").fetchSemanticsNode().boundsInRoot
        assertEquals(48f * density, window.bottom - bar.bottom, 1f)
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            down(center); moveBy(Offset(0f, 100f * density)); up()
        }
        val after = rule.onNodeWithTag("floating-drag-bar").fetchSemanticsNode().boundsInRoot
        assertEquals(bar.bottom, after.bottom, 1f)
    }
    @Test fun dockPreviewEndsAboveTheNavigationInset() {
        rule.setContent {
            density = LocalDensity.current.density
            Box(Modifier.size(340.dp, 328.dp).testTag("floating-window")) {
                FloatingKeyboardContainer(true, 0.85f, offsetX = 0, offsetY = 48, minOffsetY = 48,
                    availableHeightDp = 800, contentHeightDp = 300, onDrag = { _, _ -> }, onDragEnd = {},
                    dockTarget = KeyboardDockTarget(340, 240, 0, 0, 48)) { Box(Modifier.fillMaxSize()) }
            }
        }
        rule.onNodeWithTag("floating-drag-bar").performTouchInput { down(center); moveBy(Offset(24f * density, 0f)) }
        rule.waitUntil(2000) { rule.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "ready")).fetchSemanticsNodes().isNotEmpty() }
        val window = rule.onNodeWithTag("floating-window").fetchSemanticsNode().boundsInRoot
        val preview = rule.onNodeWithTag("floating-dock-preview").fetchSemanticsNode().boundsInRoot
        assertEquals(window.left, preview.left, 1f)
        assertEquals(window.right, preview.right, 1f)
        assertEquals(window.top + 40f * density, preview.top, 1f)
        assertEquals(window.bottom - 48f * density, preview.bottom, 1f)
        rule.onNodeWithTag("floating-drag-bar").performTouchInput { cancel() }
    }

    @Test fun draggingAgainstEitherSideNeverShowsTheRestorePreview() {
        rule.setContent {
            density = LocalDensity.current.density
            Box(Modifier.size(340.dp, 500.dp)) {
                FloatingKeyboardContainer(true, 0.85f, offsetX = 0, offsetY = 80,
                    availableHeightDp = 800, contentHeightDp = 300, onDrag = { _, _ -> }, onDragEnd = {}, onDock = { docks++ },
                    dockTarget = KeyboardDockTarget(340, 328, 0, 0, 0)) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        rule.mainClock.autoAdvance = false
        for (direction in listOf(-1, 1)) {
            rule.onNodeWithTag("floating-drag-bar").performTouchInput { down(center); moveBy(Offset(direction * 60f * density, 0f)) }
            rule.mainClock.advanceTimeBy(1600)
            rule.onNodeWithTag("floating-dock-preview").assertDoesNotExist()
            rule.onNodeWithText("松手恢复普通键盘").assertDoesNotExist()
            rule.onNodeWithTag("floating-drag-bar").performTouchInput { up() }
        }
        rule.runOnIdle { assertEquals(0, docks) }
    }


    @Test fun absentRestoreTargetNeverDrawsAPreviewOrConfirmsDocking() {
        var finishes = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(340.dp, 500.dp)) {
                        FloatingKeyboardContainer(true, .85f, offsetX = 0, offsetY = 0,
                            contentHeightDp = 300, onDrag = { _, _ -> }, onDragEnd = { finishes++ },
                            onDock = { docks++ }, dockTarget = null) { Box(Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            down(center); moveBy(Offset(24f, 0f))
        }
        rule.onNodeWithTag("floating-dock-preview").assertDoesNotExist()
        // The production dwell coroutine uses elapsed time; do not merely advance pointer timestamps.
        android.os.SystemClock.sleep(650L)
        rule.waitForIdle()
        rule.onNodeWithTag("floating-dock-preview").assertDoesNotExist()
        rule.onNodeWithTag("floating-drag-bar").performTouchInput { up() }
        rule.onNodeWithTag("floating-drag-bar").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, docks); assertEquals(1, finishes) }
    }

    @Test fun restorePreviewUsesTargetOffsetPaddingAndFixedInsetInsteadOfFloatingInset() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(600.dp, 700.dp).testTag("floating-window")) {
                        FloatingKeyboardContainer(true, .85f, offsetX = 50, offsetY = 61,
                            minOffsetY = 61, availableHeightDp = 700, contentHeightDp = 300,
                            onDrag = { _, _ -> }, onDragEnd = {}, onDock = { docks++ },
                            dockTarget = KeyboardDockTarget(widthDp = 380, contentHeightDp = 270,
                                offsetXDp = -70, bottomPaddingDp = 19, bottomInsetDp = 27)) {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        val window = rule.onNodeWithTag("floating-window").getUnclippedBoundsInRoot()
        val bar = rule.onNodeWithTag("floating-drag-bar").getUnclippedBoundsInRoot()
        assertEquals("floating placement keeps its own 61dp navigation clearance", 61f,
            window.bottom.value - bar.bottom.value, 1f)
        rule.onNodeWithTag("floating-drag-bar").performTouchInput {
            down(center); moveBy(Offset(24f, 0f))
        }
        rule.waitUntil(2_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "ready"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val preview = rule.onNodeWithTag("floating-dock-preview").getUnclippedBoundsInRoot()
        // Independent target: (600 - 380) / 2 - 70 = 40; 700 - 27 = 673;
        // the fixed frame includes 270dp content + 19dp user padding, not the floating drag row.
        assertEquals(40f, preview.left.value - window.left.value, 1f)
        assertEquals(420f, preview.right.value - window.left.value, 1f)
        assertEquals(384f, preview.top.value - window.top.value, 1f)
        assertEquals(673f, preview.bottom.value - window.top.value, 1f)
        assertEquals(289f, (preview.bottom - preview.top).value, 1f)
        rule.onNodeWithTag("floating-drag-bar").performTouchInput { cancel() }
        rule.onNodeWithTag("floating-dock-preview").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, docks) }
    }

}
