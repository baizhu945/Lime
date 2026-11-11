package com.kingzcheung.xime.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.SettingsPreferences
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.ToolbarAction
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.menubar.ToolbarCustomizeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolbarUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test fun toolbarEdgesStayAlignedAndDefaultActionsFitPhoneAndTablet() {
        var width by mutableStateOf(360.dp)
        val buttons = ToolbarButton.DEFAULT_VISIBLE.toList()
        rule.setContent {
            val config = android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current).apply {
                orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides config,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f)) {
                MaterialTheme {
                    CandidateBar(CandidateBarState.Idle,
                        toolbarActions = buttons.map { ToolbarAction(ToolbarButtonItem.Builtin(it)) {} },
                        visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray),
                        callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onHideKeyboard = {}),
                        modifier = Modifier.width(width).testTag("bar"))
                }
            }
        }
        for (size in listOf(360.dp, 1000.dp)) {
            rule.runOnIdle { width = size }
            val bar = rule.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
            val left = rule.onNodeWithTag("toolbar-leading").fetchSemanticsNode().boundsInRoot
            val right = rule.onNodeWithTag("toolbar-hide").fetchSemanticsNode().boundsInRoot
            assertEquals(8f, left.left - bar.left, 0.1f)
            assertEquals(8f, bar.right - right.right, 0.1f)
            buttons.forEach {
                val bounds = rule.onNodeWithContentDescription(it.label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("${it.label} must fit without scrolling at $size", bounds.width >= 22f && bounds.left >= left.right && bounds.right <= right.left)
            }
        }
    }

    @Test
    fun aFewToolbarButtonsSpreadAcrossTheBar() {
        val buttons = listOf(ToolbarButton.CLIPBOARD, ToolbarButton.EDIT, ToolbarButton.SCHEMA)
        rule.setContent {
            MaterialTheme {
                CandidateBar(
                    state = CandidateBarState.Idle,
                    toolbarActions = buttons.map { ToolbarAction(ToolbarButtonItem.Builtin(it)) {} },
                    visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray),
                    callbacks = CandidateBarCallbacks(onCandidateSelect = {}),
                    modifier = Modifier.width(320.dp).testTag("bar"),
                )
            }
        }
        val centers = buttons.map {
            rule.onNodeWithContentDescription(it.label).fetchSemanticsNode().boundsInRoot.center.x
        }
        val barWidth = rule.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot.width
        assertEquals(centers[1] - centers[0], centers[2] - centers[1], 2f)
        assertTrue("Buttons should use the available space", centers.last() - centers.first() > barWidth * 0.4f)
    }

    @Test
    fun overflowToolbarButtonsRemainReachable() {
        val clicked = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                CandidateBar(
                    state = CandidateBarState.Idle,
                    toolbarActions = ToolbarButton.entries.map { button ->
                        ToolbarAction(ToolbarButtonItem.Builtin(button)) { clicked += button.id }
                    },
                    visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray),
                    callbacks = CandidateBarCallbacks(onCandidateSelect = {}),
                    modifier = Modifier.width(260.dp),
                )
            }
        }
        rule.onNodeWithContentDescription(ToolbarButton.VOICE.label)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        rule.runOnIdle { assertEquals(listOf(ToolbarButton.VOICE.id), clicked) }
    }

    @Test
    fun toolbarPressCircleFadesFor300msAfterRelease() = withOriginalToolbarMaterial {
        rule.setContent {
            MaterialTheme {
                CandidateBar(
                    state = CandidateBarState.Idle,
                    toolbarActions = listOf(ToolbarAction(ToolbarButtonItem.Builtin(ToolbarButton.EDIT)) {}),
                    visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray),
                    callbacks = CandidateBarCallbacks(onCandidateSelect = {}),
                    modifier = Modifier.width(320.dp),
                )
            }
        }
        val button = rule.onNodeWithContentDescription(ToolbarButton.EDIT.label)
        fun edgeBrightness(): Float {
            val image = button.captureToImage().toPixelMap()
            return image[(image.width * 0.16f).toInt(), image.height / 2].red
        }
        rule.mainClock.autoAdvance = false
        button.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(200L)
        val held = edgeBrightness()
        button.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(120L)
        val fading = edgeBrightness()
        assertTrue("Circle should remain visible while fading", fading > held && fading < 0.995f)
        rule.mainClock.advanceTimeBy(240L)
        assertEquals(1f, edgeBrightness(), 0.01f)
    }

    private fun withOriginalToolbarMaterial(block: () -> Unit) {
        // The fade oracle below is a pure-white base, not an independently decorated toolbar.
        val previous = com.kingzcheung.xime.ui.theme.VisualStyles.current
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                com.kingzcheung.xime.ui.theme.VisualStyles.current = com.kingzcheung.xime.ui.theme.VisualStyle.ORIGINAL
            }
            block()
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                com.kingzcheung.xime.ui.theme.VisualStyles.current = previous
            }
        }
    }

    @Test
    fun clipboardPreviewKeepsPasteOpenAndDismissAsSeparateActions() {
        val events = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                CandidateBar(
                    state = CandidateBarState.ClipboardDisplay(listOf("复制的内容")),
                    visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray),
                    callbacks = CandidateBarCallbacks(
                        onCandidateSelect = { events += "paste:$it" },
                        onDismissClipboardPreview = { events += "dismiss" },
                        onOpenClipboard = { events += "open" },
                    ),
                    modifier = Modifier.width(320.dp),
                )
            }
        }
        val back = rule.onNodeWithContentDescription("返回工具栏")
        val text = rule.onNodeWithText("复制的内容")
        val open = rule.onNodeWithContentDescription("打开剪贴板")
        assertTrue(back.fetchSemanticsNode().boundsInRoot.right < text.fetchSemanticsNode().boundsInRoot.left)
        assertTrue(text.fetchSemanticsNode().boundsInRoot.right <= open.fetchSemanticsNode().boundsInRoot.left)
        text.performClick()
        open.performClick()
        back.performClick()
        rule.runOnIdle { assertEquals(listOf("paste:0", "open", "dismiss"), events) }
    }

    @Test fun longClipboardPreviewScrollsThroughTheWholeTextWithoutPastingOnDrag() {
        val original = "这段复制内容超过八个字且不应该截断。".repeat(8) + "\n末尾完整内容"
        val displayed = original.replace("\n", " ")
        val pasted = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                CandidateBar(
                    state = CandidateBarState.ClipboardDisplay(listOf(original)),
                    visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray),
                    callbacks = CandidateBarCallbacks(onCandidateSelect = { pasted += listOf(original)[it] }),
                    modifier = Modifier.width(320.dp),
                )
            }
        }
        val text = rule.onNodeWithTag("clipboard-preview-text:0", useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(displayed, layouts.single().layoutInput.text.text)
        assertEquals(displayed.length, layouts.single().getLineEnd(0))
        assertFalse(layouts.single().isLineEllipsized(0))
        val scroll = rule.onNodeWithTag("clipboard-preview-scroll", useUnmergedTree = true)
        scroll.performTouchInput { swipeLeft() }
        rule.runOnIdle { assertTrue(pasted.isEmpty()) }
        assertTrue(scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(100_000f, 0f) }
        val viewport = scroll.fetchSemanticsNode().boundsInRoot
        assertEquals("末尾应能滚入可见区域", viewport.right, text.fetchSemanticsNode().boundsInRoot.right, 2f)
        scroll.performTouchInput { down(center); up() }
        rule.runOnIdle { assertEquals(listOf(original), pasted) }
    }

    @Test
    fun shortCustomizePanelScrollsToCompleteLabelsAndKeepsTheExistingSelection() {
        val plugin = ToolbarButtonItem.Plugin(
            id = "test.long_label",
            label = "长名称插件功能入口",
            icon = PluginIcon(text = "测"),
            pluginId = "test",
            action = "open_panel",
        )
        var saved = listOf(ToolbarButton.CLIPBOARD.id)
        rule.setContent {
            MaterialTheme {
                var selected by remember { mutableStateOf(saved) }
                ToolbarCustomizeView(
                    toolbarButtons = selected,
                    pluginButtons = listOf(plugin),
                    keyTextColor = Color.Black,
                    backgroundColor = Color.White,
                    accentColor = Color.Blue,
                    keyBgColor = Color.LightGray,
                    onUpdateQuickActions = { selected = it.toolbar; saved = it.toolbar },
                    onDismiss = {},
                    modifier = Modifier.size(width = 320.dp, height = 160.dp),
                )
            }
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("quick-action:${plugin.id}"))
        val label = rule.onNodeWithText(plugin.label, useUnmergedTree = true)
        label.assertIsDisplayed()
        val textLayouts = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
        assertFalse(textLayouts.single().hasVisualOverflow)
        // Arranging never changes the saved toolbar until something is dragged.
        rule.runOnIdle { assertEquals(listOf(ToolbarButton.CLIPBOARD.id), saved) }
    }

    @Test fun voiceKeepsAllToolbarButtonsAndHighlightsOnlyMicrophone() {
        var clicks = 0
        rule.setContent {
            MaterialTheme {
                CandidateBar(
                    state = CandidateBarState.Idle,
                    isVoiceSticky = true,
                    voiceRecognitionState = com.kingzcheung.xime.speech.RecognitionState.LISTENING,
                    toolbarActions = ToolbarButton.DEFAULT_VISIBLE.map { button ->
                        ToolbarAction(ToolbarButtonItem.Builtin(button), active = button == ToolbarButton.VOICE) { clicks++ }
                    },
                    visuals = CandidateBarVisuals(Color.Black, Color.White, Color.Gray),
                    callbacks = CandidateBarCallbacks(onCandidateSelect = {}),
                    modifier = Modifier.width(360.dp),
                )
            }
        }
        ToolbarButton.DEFAULT_VISIBLE.forEach { rule.onNodeWithContentDescription(it.label).assertIsDisplayed() }
        rule.onNodeWithContentDescription("语音").assertIsSelected().performClick()
        rule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun handwritingToggleRemainsReachableAlongsideRecognitionCandidates() {
        var clicks = 0
        rule.setContent {
            MaterialTheme {
                CandidateBar(
                    state = CandidateBarState.AssociationOnly(listOf("你", "好")),
                    toolbarActions = listOf(ToolbarAction(ToolbarButtonItem.Builtin(ToolbarButton.HANDWRITING_LOOKUP), true) { clicks++ }),
                    visuals = CandidateBarVisuals(Color.Black, Color.White, Color.Gray),
                    callbacks = CandidateBarCallbacks(onCandidateSelect = {}),
                    modifier = Modifier.width(360.dp),
                )
            }
        }
        rule.onNodeWithText("你").assertIsDisplayed()
        rule.onNodeWithContentDescription("手写").assertIsSelected().performClick()
        rule.runOnIdle { assertEquals(1, clicks) }
    }
    @Test fun compositionCancelArrowPrecedesCandidatesAndDoesNotSelectOne() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("toolbar_test_$name", mode)
        }
        SettingsPreferences.setShowCandidateCancelButton(context, true)
        val events = mutableListOf<String>()
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    CandidateBar(state = CandidateBarState.ChineseCandidates(listOf("你好", "你"), inputText = "nihao"),
                        visuals = CandidateBarVisuals(Color.Black, Color.White, Color.Gray),
                        callbacks = CandidateBarCallbacks(onCandidateSelect = { events += "select" }, onCancelInput = { events += "cancel" }),
                        modifier = Modifier.width(320.dp))
                }
            }
        }
        val back = rule.onNodeWithContentDescription("取消输入")
        assertTrue(back.fetchSemanticsNode().boundsInRoot.right <= rule.onNodeWithText("你好").fetchSemanticsNode().boundsInRoot.left)
        back.performClick()
        rule.runOnIdle { assertEquals(listOf("cancel"), events) }
    }

    @Test fun twoSecondToolbarHoldReordersWithoutOpeningTools() {
        val buttons = listOf(ToolbarButton.CLIPBOARD, ToolbarButton.EDIT, ToolbarButton.SCHEMA)
        val clicks = mutableListOf<String>()
        var saved = buttons.map { it.id }
        rule.setContent {
            MaterialTheme {
                CandidateBar(state = CandidateBarState.Idle,
                    toolbarActions = buttons.map { button -> ToolbarAction(ToolbarButtonItem.Builtin(button)) { clicks += button.id } },
                    visuals = CandidateBarVisuals(Color.Black, Color.White, Color.Gray),
                    callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onReorderToolbar = { saved = it }),
                    modifier = Modifier.width(320.dp))
            }
        }
        val first = rule.onNodeWithContentDescription(buttons[0].label).fetchSemanticsNode().boundsInRoot.center
        val last = rule.onNodeWithContentDescription(buttons[2].label).fetchSemanticsNode().boundsInRoot.center
        val row = rule.onNodeWithTag("toolbar-order-row")
        val origin = row.fetchSemanticsNode().boundsInRoot.topLeft
        rule.mainClock.autoAdvance = false
        row.performTouchInput { down(first - origin) }
        rule.mainClock.advanceTimeBy(1500)
        rule.runOnIdle { assertEquals(buttons.map { it.id }, saved); assertTrue(clicks.isEmpty()) }
        rule.mainClock.advanceTimeBy(600)
        row.performTouchInput { moveTo(last - origin); up() }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { assertEquals(listOf(buttons[1].id, buttons[2].id, buttons[0].id), saved); assertTrue(clicks.isEmpty()) }
        rule.onNodeWithContentDescription(buttons[1].label).performTouchInput { down(center); up() }
        rule.runOnIdle { assertEquals(listOf(buttons[1].id), clicks) }
    }

    @Test fun activeModesRemainReachableWithCandidates() {
        val clicked = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                CandidateBar(
                    state = CandidateBarState.AssociationOnly(listOf("一", "二")),
                    toolbarActions = listOf(ToolbarButton.HANDWRITING_LOOKUP, ToolbarButton.VOICE).map {
                        ToolbarAction(ToolbarButtonItem.Builtin(it), active = true) { clicked += it.id }
                    },
                    visuals = CandidateBarVisuals(Color.White, Color.Black, Color.Gray),
                    callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onCancelInput = {}),
                    modifier = Modifier.width(280.dp),
                )
            }
        }
        for (button in listOf(ToolbarButton.HANDWRITING_LOOKUP, ToolbarButton.VOICE)) {
            rule.onNodeWithContentDescription(button.label).assertIsDisplayed().assertIsSelected().performClick()
        }
        rule.onNodeWithText("一").assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf(ToolbarButton.HANDWRITING_LOOKUP.id, ToolbarButton.VOICE.id), clicked) }
    }

}
