package com.kingzcheung.xime.ui

import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.keyboard.EdgeButtonStyle
import com.kingzcheung.xime.ui.keyboard.LocalEdgeButtonStyle
import com.kingzcheung.xime.ui.settings.LayoutDisplaySettingsContent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CandidateBarOptionsTest {
    @get:Rule val rule = createComposeRule()
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("candidate_options_$name", mode)
    }
    private val visuals = CandidateBarVisuals(Color(0xFF191C22), Color.White, Color.Gray)
    private val composing = CandidateBarState.ChineseCandidates(listOf("你好", "你"), hasMore = true)

    @Before fun setup() { SettingsPreferences.getPrefsPublic(context).edit().clear().commit() }
    @After fun teardown() { SettingsPreferences.getPrefsPublic(context).edit().clear().commit() }

    @Test fun defaultCandidatesShowCancelButtonAndPreferenceUpdatesImmediately() {
        var cancels = 0
        var selects = 0
        // 用户 10-31：默认打开。
        assertTrue(SettingsPreferences.shouldShowCandidateCancelButton(context))
        rule.setContent {
            // 44dp is the circular button's 40dp slot plus 4dp spacing; the default
            // capsule style uses a wider, measured slot. Keep this geometry deterministic.
            CompositionLocalProvider(LocalContext provides context, LocalEdgeButtonStyle provides EdgeButtonStyle.CIRCLE) {
                MaterialTheme {
                    CandidateBar(composing, visuals = visuals,
                        callbacks = CandidateBarCallbacks(onCandidateSelect = { selects++ }, onCancelInput = { cancels++ }),
                        modifier = Modifier.width(320.dp).testTag("candidate-bar"))
                }
            }
        }
        rule.onNodeWithContentDescription("取消输入").assertIsDisplayed()
        val barLeft = rule.onNodeWithTag("candidate-bar").fetchSemanticsNode().boundsInRoot.left
        val enabledLeft = rule.onNodeWithText("你好").fetchSemanticsNode().boundsInRoot.left
        val density = context.resources.displayMetrics.density
        rule.runOnIdle { SettingsPreferences.setShowCandidateCancelButton(context, false) }
        rule.onNodeWithContentDescription("取消输入").assertDoesNotExist()
        val originalLeft = rule.onNodeWithText("你好").fetchSemanticsNode().boundsInRoot.left
        assertTrue("隐藏按钮不保留40dp占位", originalLeft - barLeft < 20f * density)
        assertEquals(44f * density, enabledLeft - originalLeft, 2f)
        rule.runOnIdle { SettingsPreferences.setShowCandidateCancelButton(context, true) }
        rule.onNodeWithContentDescription("取消输入").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, cancels); assertEquals(0, selects) }
        assertEquals(enabledLeft, rule.onNodeWithText("你好").fetchSemanticsNode().boundsInRoot.left, 1f)
        rule.onNodeWithText("你好").performClick()
        rule.runOnIdle { assertEquals(1, selects) }
    }

    @Test fun defaultCapsuleCancelButtonUsesItsMeasuredSlot() {
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    CandidateBar(composing, visuals = visuals,
                        callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onCancelInput = {}),
                        modifier = Modifier.width(320.dp))
                }
            }
        }
        rule.onNodeWithContentDescription("取消输入").assertIsDisplayed()
        val enabledLeft = rule.onNodeWithText("你好").fetchSemanticsNode().boundsInRoot.left
        rule.runOnIdle { SettingsPreferences.setShowCandidateCancelButton(context, false) }
        rule.onNodeWithContentDescription("取消输入").assertDoesNotExist()
        val disabledLeft = rule.onNodeWithText("你好").fetchSemanticsNode().boundsInRoot.left
        assertEquals(48f * context.resources.displayMetrics.density, enabledLeft - disabledLeft, 2f)
    }

    @Test fun clipboardRetainsItsOwnReturnButtonWithOptionOff() {
        var dismissed = 0
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    CandidateBar(CandidateBarState.ClipboardDisplay(listOf("复制的完整文字")), visuals = visuals,
                        callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onCancelInput = {},
                            onDismissClipboardPreview = { dismissed++ }), modifier = Modifier.width(320.dp))
                }
            }
        }
        rule.onNodeWithContentDescription("取消输入").assertDoesNotExist()
        rule.onNodeWithContentDescription("返回工具栏").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun expansionArrowRotatesDownAndBackUpWithoutMovingTheButton() {
        val expanded = mutableStateOf(false)
        var opens = 0
        var closes = 0
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    CandidateBar(composing, candidatePageExpanded = expanded.value, visuals = visuals,
                        callbacks = CandidateBarCallbacks(onCandidateSelect = {},
                            onShowMoreCandidates = { opens++; expanded.value = true },
                            onBack = { closes++; expanded.value = false }), modifier = Modifier.width(320.dp))
                }
            }
        }
        rule.onNodeWithText("更多").assertDoesNotExist()
        val before = rule.onNodeWithTag("candidate-expansion").captureToImage()
        val originalBounds = rule.onNodeWithTag("candidate-expansion").fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("展开候选词").performClick()
        rule.mainClock.advanceTimeBy(96)
        val opening = rule.onNodeWithTag("candidate-expansion").captureToImage()
        rule.mainClock.advanceTimeBy(250)
        val after = rule.onNodeWithTag("candidate-expansion").captureToImage()
        assertEquals(originalBounds, rule.onNodeWithTag("candidate-expansion").fetchSemanticsNode().boundsInRoot)
        fun differs(a: androidx.compose.ui.graphics.ImageBitmap, b: androidx.compose.ui.graphics.ImageBitmap): Boolean {
            val first = a.toPixelMap(); val second = b.toPixelMap()
            return (0 until a.height).any { y -> (0 until a.width).any { x -> first[x,y] != second[x,y] } }
        }
        assertTrue("展开后的箭头须朝下", differs(before, after))
        assertTrue("展开过程要有中间帧", differs(before, opening) && differs(after, opening))
        rule.onNodeWithContentDescription("返回键盘").performClick()
        rule.mainClock.advanceTimeBy(96)
        val closing = rule.onNodeWithTag("candidate-expansion").captureToImage()
        assertTrue("收起过程要有中间帧", differs(before, closing) && differs(after, closing))
        rule.mainClock.advanceTimeBy(250)
        val restored = rule.onNodeWithTag("candidate-expansion").captureToImage()
        assertFalse("收起后恢复朝上", differs(before, restored))
        rule.mainClock.autoAdvance = true
        rule.onNodeWithContentDescription("展开候选词").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, opens); assertEquals(1, closes) }
    }

    @Test fun settingIsInitiallyOnAndSavesExplicitChoice() {
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                // 候选词从首页直接进入。
                MaterialTheme { LayoutDisplaySettingsContent(onBack = {}, initialPage = "candidates") }
            }
        }
        // 外层设置搜索定位修饰的 testTag 在前，同一节点上前面的生效，两个名字都认。
        val tagged = hasTestTag("candidate-cancel-setting") or hasTestTag("settings-search-anchor:候选栏取消按钮")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(tagged)
        val setting = rule.onNode(tagged)
        setting.assertIsOn().performClick().assertIsOff()
        rule.runOnIdle { assertFalse(SettingsPreferences.shouldShowCandidateCancelButton(context)) }
        setting.performClick().assertIsOn()
        rule.runOnIdle { assertTrue(SettingsPreferences.shouldShowCandidateCancelButton(context)) }
    }

    @Test fun preeditShowsSyllableSeparatorsAtEditorFontSizeAndRemainsClickable() {
        SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
        val showPreview = mutableStateOf(true)
        var edits = 0
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().padding(top = 100.dp)) {
                        CandidateBar(composing.copy(inputText = "nihao", preeditText = "ni hao"), visuals = visuals,
                            callbacks = CandidateBarCallbacks(onCandidateSelect = {}), modifier = Modifier.width(300.dp),
                            onEditPreedit = { edits++ }, showPreeditPreview = showPreview.value)
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        // Multiline preedit inserts a zero-width break after each visible separator.
        rule.onNodeWithText("ni'\u200Bhao", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("ni'\u200Bhao", layouts.single().layoutInput.text.text)
        assertEquals(18.sp, layouts.single().layoutInput.style.fontSize)
        rule.onNodeWithTag("candidate-preedit").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, edits); showPreview.value = false }
        rule.onNodeWithTag("candidate-preedit").assertDoesNotExist()
    }

    @Test fun longPreeditScrollsWithinScreenAndRefreshesWithoutDuplicating() {
        val text = mutableStateOf("xiu'gai'shu'ru'".repeat(20))
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().padding(start = 36.dp, top = 100.dp)) {
                        CandidateBar(composing.copy(inputText = text.value), visuals = visuals,
                            callbacks = CandidateBarCallbacks(onCandidateSelect = {}), modifier = Modifier.width(280.dp),
                            onEditPreedit = {})
                    }
                }
            }
        }
        val preview = rule.onNodeWithTag("candidate-preedit").fetchSemanticsNode()
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertTrue(preview.positionOnScreen.x >= 0f)
        assertTrue(preview.positionOnScreen.x + preview.size.width <= screen.width + 1f)
        assertTrue(preview.positionOnScreen.y >= 0f)
        assertTrue(preview.positionOnScreen.y + preview.size.height <= screen.height + 1f)
        screen.recycle()
        // The multiline preview inserts a zero-width break after every apostrophe.
        val displayedLongText = "xiu'\u200Bgai'\u200Bshu'\u200Bru'\u200B".repeat(20)
        rule.onNodeWithText(displayedLongText, useUnmergedTree = true).performTouchInput { swipeLeft() }
        rule.runOnIdle { text.value = "ni'hao" }
        rule.onNodeWithText("ni'\u200Bhao", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithTag("candidate-preedit").assertCountEquals(1)
    }
}
