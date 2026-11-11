package com.kingzcheung.xime.ui

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
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
import com.kingzcheung.xime.SharedPreferencesTestRule
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.viewmodel.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ResizeControlsContrastTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val preferences = SharedPreferencesTestRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun phoneControlsRemainOpaqueWhileKeyboardOpacityChanges() = checkControls(360, 300, false, 1f)
    @Test fun floatingControlsRemainReadableAndCanSwitchWithoutClosing() = checkControls(640, 360, true, 1f)
    @Test fun tabletControlsHaveLargerTextAndClearButtonBorders() = checkControls(760, 420, false, 1f)
    @Test fun narrowLowControlsDoNotOverlapWithLargeFont() = checkControls(280, 180, false, 1.3f)

    @Test fun opacityCompositesToolbarKeysAndBackgroundTogether() {
        val vm = KeyboardViewModel(app)
        var opacity by mutableFloatStateOf(1f)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 300.dp).background(Color.White).testTag("opacity-root")) {
                    KeyboardView(vm, KeyboardUiState(isDarkTheme = true, keyboardOpacity = opacity,
                        keyboardHeightDp = 300),
                        KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                        modifier = Modifier.fillMaxSize(), resizeOverlay = {})
                }
            }
        }
        val before = rule.onNodeWithTag("opacity-root").captureToImage().asAndroidBitmap()
        rule.runOnIdle { opacity = 0.25f }
        val after = rule.onNodeWithTag("opacity-root").captureToImage().asAndroidBitmap()
        var darkPixels = 0
        var incorrect = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            val old = before.getPixel(x, y)
            val now = after.getPixel(x, y)
            if (android.graphics.Color.red(old) < 180) darkPixels++
            for (shift in listOf(0, 8, 16)) {
                val expected = 255 * 0.75f + ((old shr shift) and 255) * 0.25f
                if (kotlin.math.abs(((now shr shift) and 255) - expected) > 3) incorrect++
            }
        }
        assertTrue("test must include actual dark keyboard pixels", darkPixels > 10000)
        assertEquals("keys and toolbar must receive one shared opacity", 0, incorrect)
    }

    @Test fun floatingPreviewAndConfirmedCardShareOneOpacityIncludingBottomBackground() {
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = preferences.getSharedPreferences()
        }
        FrostedGlassPreferences.save(context, FrostedGlassConfig(enabled = false), true)
        val vm = KeyboardViewModel(app)
        var opacity by mutableFloatStateOf(1f)
        var resizing by mutableStateOf(true)
        lateinit var frameStyle: ResizeFrameStyle
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply { screenWidthDp = 400; screenHeightDp = 700 }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.size(400.dp, 400.dp).background(Color.White).testTag("floating-opacity-root")) {
                        val controls: (@Composable () -> Unit)? = if (resizing) {
                            {
                                frameStyle = resizeFrameStyle(LocalConfiguration.current.smallestScreenWidthDp)
                                KeyboardResizeOverlay(260, 260, 0, true, initialWidthDp = 360,
                                    initialOpacity = opacity, onHeightChange = {}, onBottomPaddingChange = {},
                                    onOpacityChange = { opacity = it }, onReset = {},
                                    onConfirm = { _, _, _, value -> opacity = value; resizing = false }, onCancel = {})
                            }
                        } else null
                        KeyboardView(vm, KeyboardUiState(isDarkTheme = true, isFloatingMode = true,
                            keyboardOpacity = opacity, keyboardHeightDp = 260, keyboardWidthDp = 360,
                            floatingWidthDp = 360, floatingOffsetY = 24, floatingMinOffsetY = 12,
                            floatingScreenHeightDp = 700),
                            KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                            modifier = Modifier.fillMaxSize(), resizeOverlay = controls)
                    }
                }
            }
        }
        rule.onNodeWithTag("keyboard-resize-frame").assertIsDisplayed()
        rule.onNodeWithTag("floating-drag-indicator").assertIsDisplayed()
        val root = rule.onNodeWithTag("floating-opacity-root")
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        fun localBounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.translate(-origin.x, -origin.y)
        val card = localBounds("floating-keyboard-card")
        val body = localBounds("keyboard-underlay")
        // Only actual opaque resize UI is outside the keyboard-card alpha layer.
        // Density is explicitly 1 here. Edge ink uses the renderer's real style,
        // not the whole 26dp touch band; all original content regions remain unchanged.
        val topInk = maxOf(frameStyle.strokeDp, frameStyle.bracketThicknessDp, frameStyle.handleThicknessDp)
        val bottomInk = maxOf(frameStyle.strokeDp, frameStyle.bracketThicknessDp)
        val excludedControls = listOf(localBounds("keyboard-resize-opacity-panel"),
            localBounds("keyboard-resize-actions"), localBounds("keyboard-resize-floating-move-pill"),
            Rect(card.left, card.top, card.right, card.top + topInk),
            Rect(card.left, card.bottom - bottomInk, card.right, card.bottom))
        val opacityButtons = listOf("重置", "悬浮键盘", "分体键盘", "确认").map { label ->
            label to rule.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot.translate(-origin.x, -origin.y)
        }
        val regions = listOf(
            "toolbar" to Rect(card.left + 24f, card.top + 3f, card.right - 24f, body.top - 2f),
            "body" to Rect(card.left + 24f, body.top + 2f, card.right - 24f,
                card.bottom - FLOATING_DRAG_BAR_HEIGHT_DP - 2f),
            "bottom background" to Rect(card.left + 24f, card.bottom - FLOATING_DRAG_BAR_HEIGHT_DP + 2f,
                card.right - 24f, card.bottom - 2f),
        )
        val opaque = root.captureToImage().asAndroidBitmap()
        rule.onNodeWithTag("keyboard-opacity-overlay-label").performTouchInput { click() }
        rule.onNodeWithTag("keyboard-resize-frame").assertIsDisplayed()
        rule.onNodeWithTag("keyboard-resize-actions").assertIsDisplayed()
        rule.onNodeWithTag("keyboard-opacity-actions").assertDoesNotExist()
        rule.onNodeWithTag("keyboard-opacity-slider").performTouchInput {
            swipe(Offset(width * .25f, centerY), Offset(width * .72f, centerY), durationMillis = 250)
        }
        rule.waitForIdle()
        val chosenOpacity = opacity
        assertTrue("real drag must change the card opacity", chosenOpacity in .3f..0.8f)
        assertEquals(card, localBounds("floating-keyboard-card"))
        val preview = root.captureToImage().asAndroidBitmap()
        saveBitmap("floating-opacity-opaque", opaque)
        saveBitmap("floating-opacity-preview", preview)
        for ((label, bounds) in opacityButtons) {
            // Compare the filled interior, excluding only the rounded transparent corners.
            // Bare TextButtons reveal the changing key letters here and fail this check.
            val inset = minOf(24f, bounds.width / 3f).toInt()
            var checkedPixels = 0
            var changedChannels = 0
            for (y in bounds.top.toInt() + 3 until bounds.bottom.toInt() - 3) {
                for (x in bounds.left.toInt() + inset until bounds.right.toInt() - inset) {
                    checkedPixels++
                    for (shift in listOf(0, 8, 16)) {
                        val before = (opaque.getPixel(x, y) shr shift) and 255
                        val after = (preview.getPixel(x, y) shr shift) and 255
                        if (kotlin.math.abs(before - after) > 1) changedChannels++
                    }
                }
            }
            assertTrue("$label must expose a real button interior", checkedPixels > 100)
            assertEquals("$label must cover the underlying key letters with an opaque theme surface", 0, changedChannels)
        }
        fun covered(x: Int, y: Int, bounds: List<Rect>) = bounds.any {
            x >= it.left - 2f && x < it.right + 2f && y >= it.top - 2f && y < it.bottom + 2f
        }
        for ((name, area) in regions) {
            var changedPixels = 0
            var incorrectChannels = 0
            for (y in area.top.toInt() until area.bottom.toInt()) for (x in area.left.toInt() until area.right.toInt()) {
                if (covered(x, y, excludedControls)) continue
                val before = opaque.getPixel(x, y)
                val after = preview.getPixel(x, y)
                if ((0..2).any { ((before shr (it * 8)) and 255) < 180 }) changedPixels++
                for (shift in listOf(0, 8, 16)) {
                    val expected = 255 * (1f - chosenOpacity) + ((before shr shift) and 255) * chosenOpacity
                    if (kotlin.math.abs(((after shr shift) and 255) - expected) > 3f) incorrectChannels++
                }
            }
            assertTrue("$name must have actual dark pixels to validate alpha", changedPixels > 100)
            assertEquals("$name must receive exactly one shared opacity in preview", 0, incorrectChannels)
        }
        rule.onNodeWithContentDescription("确认").performClick()
        rule.onNodeWithTag("floating-drag-indicator").assertIsDisplayed()
        assertEquals("confirmation must retain the card rectangle", card, localBounds("floating-keyboard-card"))
        assertEquals("confirmation must retain the content rectangle", body, localBounds("keyboard-underlay"))
        val confirmed = root.captureToImage().asAndroidBitmap()
        saveBitmap("floating-opacity-confirmed", confirmed)
        val excludedAfter = excludedControls
        for ((name, area) in regions) {
            var incorrectChannels = 0
            for (y in area.top.toInt() until area.bottom.toInt()) for (x in area.left.toInt() until area.right.toInt()) {
                if (covered(x, y, excludedAfter)) continue
                for (shift in listOf(0, 8, 16)) {
                    val previewChannel = (preview.getPixel(x, y) shr shift) and 255
                    val finalChannel = (confirmed.getPixel(x, y) shr shift) and 255
                    if (kotlin.math.abs(previewChannel - finalChannel) > 3) incorrectChannels++
                }
            }
            assertEquals("$name opacity must not jump when leaving preview", 0, incorrectChannels)
        }
    }

    private fun checkControls(width: Int, height: Int, floating: Boolean, font: Float) {
        val vm = KeyboardViewModel(app)
        val opacity = mutableFloatStateOf(1f)
        val floatingState = mutableStateOf(floating)
        var modeChanges = 0
        var confirmed = false
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply { screenWidthDp = width; screenHeightDp = 900 }
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(1f, font)) {
                MaterialTheme {
                    Box(Modifier.size(width.dp, height.dp).background(Color.White).testTag("resize-contrast-root")) {
                        KeyboardView(vm, KeyboardUiState(isDarkTheme = true, isFloatingMode = floatingState.value,
                            keyboardOpacity = opacity.floatValue, keyboardHeightDp = height,
                            floatingOffsetY = if (floatingState.value) 24 else 0, floatingMinOffsetY = 24,
                            floatingScreenHeightDp = 900),
                            KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                            modifier = Modifier.fillMaxSize(),
                            resizeOverlay = {
                                KeyboardResizeOverlay(height, height, 0, floatingState.value,
                                    initialOpacity = opacity.floatValue, onHeightChange = {}, onBottomPaddingChange = {},
                                    onOpacityChange = { opacity.floatValue = it }, onReset = {},
                                    onConfirm = { _, _, _, _ -> confirmed = true }, onCancel = {},
                                    onFloatingModeChange = { floatingState.value = it; modeChanges++ },
                                    modifier = Modifier.fillMaxSize())
                            })
                    }
                }
            }
        }
        rule.waitForIdle()
        save("resize-controls-before-$width-$height-$floating")
        val button = rule.onNodeWithTag("keyboard-resize-floating-button")
        val before = button.captureToImage().asAndroidBitmap()
        rule.onNodeWithTag("keyboard-opacity-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }
        save("resize-opacity-editing-$width-$height-$floating")
        rule.onNodeWithTag("keyboard-resize-frame").assertIsDisplayed()
        rule.onNodeWithTag("keyboard-resize-actions").assertIsDisplayed()
        rule.onNodeWithTag("keyboard-opacity-actions").assertDoesNotExist()
        for (label in listOf("重置", "悬浮键盘", "分体键盘", "确认"))
            rule.onNodeWithContentDescription(label).assertIsDisplayed()
        rule.waitForIdle()
        val after = button.captureToImage().asAndroidBitmap()
        // Save the complete layout before assertions so a failure always has visual evidence.
        save("resize-controls-$width-$height-$floating")
        saveBitmap("resize-mode-before-$width-$height-$floating", before)
        saveBitmap("resize-mode-after-$width-$height-$floating", after)
        rule.runOnIdle { assertEquals(0.3f, opacity.floatValue, 0.01f) }
        // The rectangular capture includes transparent pixels outside the rounded button.
        // Those should reveal the changing keyboard; compare its opaque center, including text.
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        val inset = minOf(26, before.width / 3)
        var changedInteriorPixels = 0
        for (y in 3 until before.height - 3) for (x in inset until before.width - inset) {
            if (before.getPixel(x, y) != after.getPixel(x, y)) changedInteriorPixels++
        }
        assertEquals("opaque control interior inherited keyboard transparency", 0, changedInteriorPixels)
        val image = button.captureToImage().toPixelMap()
        val panelPixel = image[image.width / 2, 5]
        // 按钮现在是主题实心色、无描边：顶边中点和内部必须是同一种颜色。
        val border = image[image.width / 2, 0]
        assertTrue("mode button must be a solid fill without an outline",
            kotlin.math.abs(border.red - panelPixel.red) < 0.06f && kotlin.math.abs(border.green - panelPixel.green) < 0.06f &&
                kotlin.math.abs(border.blue - panelPixel.blue) < 0.06f)
        val reset = rule.onNodeWithContentDescription("重置").fetchSemanticsNode().boundsInRoot
        val mode = button.fetchSemanticsNode().boundsInRoot
        val confirm = rule.onNodeWithContentDescription("确认").fetchSemanticsNode().boundsInRoot
        val controls = rule.onNodeWithTag("keyboard-resize-controls", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val opacityPanel = rule.onNodeWithTag("keyboard-resize-opacity-panel", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val slider = rule.onNodeWithTag("keyboard-opacity-slider", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val opacityLabel = rule.onNodeWithTag("keyboard-opacity-overlay-label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val actions = rule.onNodeWithTag("keyboard-resize-actions", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("opacity bar must leave space for side resize handles", opacityPanel.left >= controls.left + 11f &&
            opacityPanel.right <= controls.right - 11f)
        assertEquals("slider and actions must share the same width", actions.width, opacityPanel.width, 1f)
        assertEquals(actions.left, opacityPanel.left, 1f)
        assertTrue("tablet action buttons must not stretch across the screen", reset.width <= 140f)
        assertEquals(controls.center.x, opacityPanel.center.x, 1f)
        // The current control keeps the complete label centered over the slider.
        assertTrue("opacity label must stay inside the slider", opacityLabel.left >= slider.left - 1f &&
            opacityLabel.right <= slider.right + 1f && opacityLabel.right <= opacityPanel.right)
        assertEquals(slider.center.x, opacityLabel.center.x, 1f)
        assertEquals(slider.center.y, opacityLabel.center.y, 1f)
        assertTrue(slider.bottom <= reset.top)
        assertTrue(reset.right < mode.left && mode.right < confirm.left)
        assertEquals(reset.center.y, mode.center.y, 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("透明度 70%", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("opacity label overflow at $width x $height, floating=$floating, font=$font: " +
            "size=${layout.size}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, " +
            "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
            "constraints=${layout.layoutInput.constraints}, style=${layout.layoutInput.style}", layout.hasVisualOverflow)
        assertTrue(layout.layoutInput.style.fontSize.value >= 16f)
        if (floating) {
            rule.onNodeWithTag("floating-drag-bar").assertIsDisplayed()
            assertTrue(confirm.bottom <= height - 24)
        }
        rule.onNodeWithContentDescription("悬浮键盘").performClick()
        rule.runOnIdle { assertEquals(1, modeChanges); assertEquals(!floating, floatingState.value); assertFalse(confirmed) }
        rule.onNodeWithContentDescription("确认").assertIsDisplayed()
        rule.onNodeWithTag("keyboard-opacity-slider").assertIsDisplayed()
    }

    private fun save(name: String) = saveBitmap(name,
        rule.onNodeWithTag("resize-contrast-root").captureToImage().asAndroidBitmap())

    private fun saveBitmap(name: String, bitmap: Bitmap) {
        val directory = File(app.getExternalFilesDir(null), "cyime").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
