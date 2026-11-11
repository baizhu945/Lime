package com.kingzcheung.xime.ui

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.viewmodel.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class TabletSplitResizeTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val context = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("split_resize_test_$name", mode)
    }
    @Before fun before() {
        SettingsPreferences.getPrefsPublic(context).edit().clear().commit()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.reload(app)
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
    }
    @After fun after() { SettingsPreferences.getPrefsPublic(context).edit().clear().commit() }

    @Test fun realFloatingKeyboardKeepsSystemFontScaleAndReadableKeycaps() {
        val vm = KeyboardViewModel(app)
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 1200; screenHeightDp = 800; orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context,
                LocalDensity provides Density(1f, 1.15f)) {
                XimeTheme(darkTheme = true, themeId = "lavender_purple") {
                    // Keep the existing font-scale stress condition inside the production theme.
                    CompositionLocalProvider(LocalDensity provides Density(1f, 1.15f)) {
                    // FloatingKeyboardContainer draws its own themed card, over a dark host surface.
                    Box(Modifier.size(1000.dp, 360.dp).testTag("tablet-real-keyboard")
                        .background(Color(0xFF101010))) {
                        KeyboardView(vm, KeyboardUiState(currentSchemaId = "rime_ice", isAsciiMode = true,
                            isDarkTheme = true, themeId = "lavender_purple", isFloatingMode = true,
                            keyboardHeightDp = 330, floatingOffsetY = 0, floatingScreenHeightDp = 800),
                            KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                            modifier = Modifier.fillMaxSize())
                    }
                    }
                }
            }
        }
        rule.waitForIdle()
        save("tablet-real-floating-complete", "tablet-real-keyboard")
        val layout = textLayout("q", true)
        // Exercises the production KeyboardView caller plus FloatingKeyboardContainer and KeyButton.
        assertEquals("floating screen ratio must not be applied to font scale", 1.15f,
            layout.layoutInput.density.fontScale, 0.01f)
        assertTrue("floating letters are too small: ${layout.layoutInput.style.fontSize}",
            layout.layoutInput.style.fontSize.value >= 16f)
        assertFalse("q overflow: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
            "width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, constraints=${layout.layoutInput.constraints}, style=${layout.layoutInput.style}", layout.hasVisualOverflow)
        val card = rule.onNodeWithTag("floating-keyboard-card", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(card.width < 900f)
        rule.onNodeWithTag("split-keyboard", useUnmergedTree = true).assertDoesNotExist()
        save("tablet-real-floating-complete", "tablet-real-keyboard")
    }

    @Test fun landscapeUsesCompleteKeyboardUntilUserExplicitlyEnablesSplit() {
        val vm = KeyboardViewModel(app)
        val keys = mutableListOf<String>()
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 1000; screenHeightDp = 700; orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context,
                LocalDensity provides Density(1f)) {
                XimeTheme(darkTheme = true, themeId = "soft_blue") {
                    val theme = KeyboardThemes.getThemeById("soft_blue")
                    // Fixed IME background is drawn once by the service behind KeyboardView.
                    Box(Modifier.size(1000.dp, 360.dp).testTag("tablet-real-keyboard")
                        .keyboardBackground(theme.keyboardBackground, true,
                            KeyboardThemes.getKeyboardBackgroundColor(theme.id, true))) {
                        KeyboardView(vm, KeyboardUiState(currentSchemaId = "rime_ice", isAsciiMode = true,
                            isDarkTheme = true, themeId = "soft_blue", keyboardHeightDp = 300),
                            KeyboardCallbacks(onKeyPress = { key, _ -> keys.add(key) }, onCandidateSelect = {}),
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        rule.onNodeWithTag("split-keyboard", useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle { SettingsPreferences.setSplitKeyboardEnabled(context, true) }
        rule.onNodeWithTag("split-keyboard-gap", useUnmergedTree = true).assertIsDisplayed()
        val layout = textLayout("q", true)
        assertTrue(layout.layoutInput.style.fontSize.value >= 18f)
        assertFalse("q overflow: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
            "width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, constraints=${layout.layoutInput.constraints}, style=${layout.layoutInput.style}", layout.hasVisualOverflow)
        rule.onNodeWithText("q", ignoreCase = true).performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf("q"), keys) }
        save("tablet-real-manual-split", "tablet-real-keyboard")
        rule.runOnIdle { SettingsPreferences.setSplitKeyboardEnabled(context, false) }
        rule.onNodeWithTag("split-keyboard", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun narrowResizeControlsToggleSplitImmediatelyWithoutClosingOrOverlap() {
        var closes = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                MaterialTheme {
                    KeyboardResizeOverlay(180, 240, 0, false, onHeightChange = {}, onBottomPaddingChange = {},
                        onOpacityChange = {}, onReset = {}, onConfirm = { _, _, _, _ -> closes++ }, onCancel = {},
                        modifier = Modifier.size(280.dp, 180.dp).testTag("resize-test-root"),
                        onSplitKeyboardChange = { SettingsPreferences.setSplitKeyboardEnabled(context, it) })
                }
            }
        }
        rule.onNodeWithTag("keyboard-resize-split-button", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertTrue(SettingsPreferences.isSplitKeyboardEnabled(context)); assertEquals(0, closes) }
        rule.onNodeWithTag("keyboard-resize-split-button", useUnmergedTree = true).assertIsSelected()
        save("resize-narrow-split-controls", "resize-test-root")
        val root = rule.onNodeWithTag("resize-test-root", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val floating = rule.onNodeWithTag("keyboard-resize-floating-button", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val split = rule.onNodeWithTag("keyboard-resize-split-button", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val panel = rule.onNodeWithTag("keyboard-resize-opacity-panel", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("keyboard-resize-height-handle").assertDoesNotExist()
        rule.onNodeWithTag("keyboard-resize-position-handle").assertDoesNotExist()
        assertTrue(panel.top >= root.top + 24f)
        assertTrue(panel.bottom <= floating.top)
        assertEquals(floating.top, split.top, 1f)
        assertEquals(floating.bottom, split.bottom, 1f)
        assertTrue(floating.right < split.left)
        assertTrue(split.bottom <= root.bottom)
        listOf(floating, split).forEach { button ->
            assertTrue("Compact action retains a real 30px target: $button", button.width >= 30f && button.height >= 30f)
            assertTrue(button.left >= root.left && button.right <= root.right)
        }
        rule.onNodeWithTag("keyboard-resize-floating-button", useUnmergedTree = true).assertContentDescriptionEquals("悬浮键盘")
        rule.onNodeWithTag("keyboard-resize-split-button", useUnmergedTree = true).assertContentDescriptionEquals("分体键盘")
        rule.onAllNodesWithText("分体", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText("悬浮", useUnmergedTree = true).assertCountEquals(0)
        for (label in listOf("透明度 0%")) {
            val layout = textLayout(label)
            val details = "$label: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                "width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, lines=${layout.lineCount}, " +
                "exceededLines=${layout.multiParagraph.didExceedMaxLines}, lineEnd=${layout.getLineEnd(0)}, " +
                "fontScale=${layout.layoutInput.density.fontScale}, constraints=${layout.layoutInput.constraints}, style=${layout.layoutInput.style}"
            assertFalse(details, layout.hasVisualOverflow)
            assertEquals(details, label.length, layout.getLineEnd(0))
            assertTrue("Complete opacity label retains its readable minimum; $details", layout.layoutInput.style.fontSize.value >= 16f)
            assertTrue("Every glyph must fit the allocated label width; $details",
                layout.getLineLeft(0) >= 0f && layout.getLineRight(0) <= layout.layoutInput.constraints.maxWidth)
        }
        save("resize-narrow-split-controls", "resize-test-root")
        rule.onNodeWithTag("keyboard-resize-split-button", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertFalse(SettingsPreferences.isSplitKeyboardEnabled(context)) }
    }

    @Test fun fixedResizeUsesExplicitHandlesAndUpdatesBeforeRelease() {
        val heights = mutableListOf<Int>()
        val gaps = mutableListOf<Int>()
        val rect = setResize(heights = heights, gaps = gaps)
        val root = rule.onNodeWithTag("resize-test-root", useUnmergedTree = true)
        root.performTouchInput {
            down(Offset(rect.value.centerX, rect.value.bottom - 2f)); moveBy(Offset(0f, -40f), 80)
        }
        rule.runOnIdle { assertTrue("bottom gap must preview before pointer up", gaps.last() > 0) }
        root.performTouchInput { up() }
        root.performTouchInput {
            down(Offset(rect.value.centerX, rect.value.top + 2f)); moveBy(Offset(0f, -40f), 80)
        }
        rule.runOnIdle { assertTrue("height must preview before pointer up", heights.last() > 240) }
        root.performTouchInput { up() }
    }

    @Test fun floatingPositionHandleMovesContinuouslyWithoutChangingHeight() {
        val heights = mutableListOf<Int>()
        val gaps = mutableListOf<Int>()
        val positions = mutableListOf<Offset>()
        var ends = 0
        val rect = setResize(true, heights, gaps, { x, y -> positions.add(Offset(x, y)) }, { ends++ })
        val original = rect.value
        val handle = rule.onNodeWithTag("keyboard-resize-move-bar", useUnmergedTree = true)
        handle.performTouchInput { down(center); moveBy(Offset(40f, -30f), 80) }
        rule.runOnIdle {
            assertTrue("A real draft position must be emitted", positions.isNotEmpty())
            assertTrue(positions.last().x > 0); assertTrue(positions.last().y > 0)
            assertEquals(original.width, rect.value.width, 0f)
            assertEquals(original.height, rect.value.height, 0f)
            assertEquals(0, ends); assertTrue(gaps.isEmpty()); assertTrue(heights.isEmpty())
        }
        handle.performTouchInput { up() }
        rule.runOnIdle { assertEquals(1, ends) }
    }

    @Test fun repeatedMovesFollowRealOverlayGeometryBeforePointerUp() {
        val rect = setResize(heights = mutableListOf(), gaps = mutableListOf())
        val initial = rect.value
        val card = rule.onNodeWithTag("resize-preview-card", useUnmergedTree = true)
        val initialBounds = card.fetchSemanticsNode().boundsInRoot
        val root = rule.onNodeWithTag("resize-test-root", useUnmergedTree = true)
        root.performTouchInput { down(Offset(initial.centerX, initial.bottom - 2f)); moveBy(Offset(0f, -35f), 80) }
        rule.waitForIdle()
        val first = card.fetchSemanticsNode().boundsInRoot
        assertTrue("position must change during the drag", first.top < initialBounds.top)
        root.performTouchInput { moveBy(Offset(0f, -20f), 80) }
        rule.waitForIdle()
        val second = card.fetchSemanticsNode().boundsInRoot
        assertEquals("moving coordinate space must not double or discard drag deltas", 20f, first.top - second.top, 2f)
        root.performTouchInput { moveBy(Offset(0f, -10f), 80) }
        rule.waitForIdle()
        val third = card.fetchSemanticsNode().boundsInRoot
        assertEquals(10f, second.top - third.top, 2f)
        root.performTouchInput { up() }
        val beforeResize = rect.value
        val beforeResizeBounds = card.fetchSemanticsNode().boundsInRoot
        root.performTouchInput { down(Offset(beforeResize.centerX, beforeResize.top + 2f)); moveBy(Offset(0f, -35f), 80) }
        rule.waitForIdle()
        val resized = card.fetchSemanticsNode().boundsInRoot
        assertTrue(resized.top < beforeResizeBounds.top)
        root.performTouchInput { moveBy(Offset(0f, -20f), 80) }
        rule.waitForIdle()
        assertEquals(20f, resized.top - card.fetchSemanticsNode().boundsInRoot.top, 2f)
        root.performTouchInput { up() }
    }

    private fun setResize(floating: Boolean = false, heights: MutableList<Int>, gaps: MutableList<Int>,
        onMove: ((Float, Float) -> Unit)? = null, onEnd: (() -> Unit)? = null): MutableState<ResizeRect> {
        val bounds = ResizeRect(0f, 0f, 360f, 600f)
        val initial = if (floating) ResizeRect(40f, 260f, 320f, 500f + FLOATING_DRAG_BAR_HEIGHT_DP)
            else fixedKeyboardRect(360f, 600f, 240f, 0f, 0f)
        val rect = mutableStateOf(initial)
        val padding = mutableIntStateOf(0)
        var contentHeight = 240
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalContext provides context,
                LocalKeyboardResizePreviewState provides KeyboardResizePreviewState(
                    rect = rect.value, initialRect = initial, bounds = bounds,
                    bottomPaddingDp = padding.intValue,
                    onRectChange = { next ->
                        rect.value = next
                        val height = (next.height - if (floating) FLOATING_DRAG_BAR_HEIGHT_DP else padding.intValue).roundToInt()
                        if (height != contentHeight) { contentHeight = height; heights.add(height) }
                        if (floating) onMove?.invoke(next.left - initial.left, initial.top - next.top)
                    },
                    onBottomPaddingChange = { padding.intValue = it; gaps.add(it) },
                    onDragEnd = { onEnd?.invoke() },
                )) {
                MaterialTheme {
                    Box(Modifier.size(360.dp, 600.dp).testTag("resize-test-root")) {
                        Box(Modifier.offset(x = rect.value.left.dp, y = rect.value.top.dp)
                            .size(rect.value.width.dp, rect.value.height.dp)
                            .background(MaterialTheme.colorScheme.surface).testTag("resize-preview-card"))
                        KeyboardResizeOverlay(240, 240, 0, floating,
                            onHeightChange = { heights.add(it) }, onBottomPaddingChange = { gaps.add(it) },
                            onOpacityChange = {}, onReset = {}, onConfirm = { _, _, _, _ -> }, onCancel = {},
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        return rect
    }
    private fun textLayout(text: String, ignoreCase: Boolean = false): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, ignoreCase = ignoreCase, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
    private fun save(name: String, tag: String) {
        val folder = File(app.getExternalFilesDir(null), "cyime").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            rule.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
