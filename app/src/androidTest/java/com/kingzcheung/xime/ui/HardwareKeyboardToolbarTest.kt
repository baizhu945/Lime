package com.kingzcheung.xime.ui

import android.graphics.Rect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.ui.keyboard.HardwareKeyboardToolbar
import com.kingzcheung.xime.ui.keyboard.HardwareToolbarPosition
import com.kingzcheung.xime.ui.keyboard.HardwareCandidateRow
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.keyboard.KeyboardInputActions
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HardwareKeyboardToolbarTest {
    @get:Rule val rule = createComposeRule()
    private val visible = mutableStateOf(true)
    private val position = mutableStateOf(HardwareToolbarPosition())
    private var bounds: Rect? = null
    private var keyboard = 0
    private var emoji = 0
    private var clipboard = 0
    private var language = 0
    private var background = 0
    private var selectedSuggestion = -1
    private var selectedSuggestionWord: String? = null
    private val candidateSelections = mutableListOf<String>()
    private var selectedLanguage: String? = null
    private val candidateWords = mutableStateOf(listOf("hello", "world", "again"))
    private val activeLanguage = mutableStateOf("中")
    private val floatingSuggestions = mutableStateOf(false)
    private val candidatesPending = mutableStateOf(false)

    @Test fun idleEnglishShowsLanguageAndReservesCandidateSpaceWithoutEmptyActions() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(800.dp, 600.dp)) {
                    HardwareKeyboardToolbar({}, {}, {}, { language++ }, Color.DarkGray, Color.White,
                        { bounds = it }, HardwareToolbarPosition(), {}, bottomDocked = true,
                        bottomCandidateContent = null, languageLabel = "EN")
                }
            }
        }
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(720.dp)
        rule.onNodeWithText("EN", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("hardware-docked-candidates").assertExists()
        rule.onNodeWithTag("bar-candidate:0").assertDoesNotExist()
        rule.onNodeWithTag("hardware-candidate-next").assertDoesNotExist()
        assertHorizontalActionsEndWithLanguage()
        rule.onNodeWithTag("hardware-toolbar-language").performClick()
        rule.runOnIdle { assertEquals(1, language) }
    }

    private fun show(width: Int = 360, height: Int = 400, docking: Boolean = false, languageLabel: String = "中",
        suggestions: Boolean = false, layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        languageMenu: Boolean = false, fontScale: Float = 1f) {
        activeLanguage.value = languageLabel
        floatingSuggestions.value = suggestions
        candidateWords.value = if (suggestions) listOf("hello", "world", "again") else listOf("测试", "侧视")
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalLayoutDirection provides layoutDirection) {
                Box(Modifier.requiredSize(width.dp, height.dp).testTag("toolbar-host")) {
                    Box(Modifier.fillMaxSize().clickable { background++ }.testTag("toolbar-underlay"))
                    val words = candidateWords.value
                    if (visible.value) HardwareKeyboardToolbar(
                        onShowKeyboard = { keyboard++ }, onEmoji = { emoji++ }, onClipboard = { clipboard++ },
                        onSwitchLanguage = { language++ }, backgroundColor = Color.DarkGray, contentColor = Color.White,
                        onBoundsChanged = { bounds = it }, position = position.value, onPositionChange = { position.value = it },
                        bottomDocked = docking && position.value.yFraction == 1f,
                        bottomCandidateContent = if (words.isEmpty()) null else {
                          {
                            HardwareCandidateRow(words, emptyList(), 0,
                                false, false, CandidateBarVisuals(Color.DarkGray, Color.White, Color.Transparent,
                                    Color.Blue, Color.White), {
                                    selectedSuggestion = it
                                    selectedSuggestionWord = words[it]
                                    candidateSelections += words[it]
                                }, null, null,
                                showNumberLabels = false)
                          }
                        },
                        showCandidatesWhenFloating = floatingSuggestions.value,
                        candidatesPending = candidatesPending.value,
                        languageLabel = activeLanguage.value,
                        languageActions = if (languageMenu) KeyboardInputActions(
                            schemas = listOf(SchemaInfo("first", "中文", "", "", "")),
                            currentInputModeId = "first", onSwitchSchema = { selectedLanguage = it },
                        ) else KeyboardInputActions(),
                    )
                }
            }
        }
    }

    private fun assertHorizontalActionsEndWithLanguage() {
        val toolbar = rule.onNodeWithTag("hardware-keyboard-toolbar").fetchSemanticsNode().boundsInRoot
        val controls = listOf("keyboard", "emoji", "clipboard", "language").map { name ->
            rule.onNodeWithTag("hardware-toolbar-$name").assertIsDisplayed()
                .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
                .fetchSemanticsNode().boundsInRoot
        }
        controls.zipWithNext().forEach { (left, right) ->
            assertTrue("Controls retain their physical left-to-right order", left.right <= right.left + .1f)
            assertEquals("Controls share one row", left.center.y, right.center.y, .1f)
        }
        assertEquals("Language stays at the right padding", toolbar.right - 4f, controls.last().right, .1f)
        rule.onNodeWithTag("hardware-toolbar-more").assertDoesNotExist()
    }

    @Test fun actionsRemainSeparateFromDragAndEmptyHostDoesNotConsumeTouches() {
        show()
        rule.onNodeWithTag("hardware-toolbar-keyboard").performClick()
        rule.onNodeWithTag("hardware-toolbar-emoji").performClick()
        rule.onNodeWithTag("hardware-toolbar-clipboard").performClick()
        rule.onNodeWithTag("hardware-toolbar-language").performClick()
        rule.onNodeWithTag("toolbar-host").performTouchInput { click(Offset(8f, 8f)) }
        rule.runOnIdle {
            assertEquals(listOf(1, 1, 1, 1), listOf(keyboard, emoji, clipboard, language))
            assertEquals(1, background)
            assertEquals(232, bounds!!.width())
            assertEquals(56, bounds!!.height())
            visible.value = false
        }
        rule.runOnIdle { assertNull(bounds) }
    }

    @Test fun narrowAuxiliaryActionsStayVisibleAndDoNotHideRecovery() {
        show(width = 208, languageLabel = "EN")
        rule.onNodeWithText("EN", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("hardware-toolbar-language").assertIsDisplayed().performClick()
        rule.onNodeWithTag("hardware-toolbar-more").assertDoesNotExist()
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(184.dp).assertHeightIsEqualTo(104.dp)
        val toolbar = rule.onNodeWithTag("hardware-keyboard-toolbar").fetchSemanticsNode().boundsInRoot
        val host = rule.onNodeWithTag("toolbar-host").fetchSemanticsNode().boundsInRoot
        assertTrue("toolbar must remain inside its host: $toolbar vs $host", toolbar.bottom <= host.bottom)
        val languageButton = rule.onNodeWithTag("hardware-toolbar-language").fetchSemanticsNode().boundsInRoot
        val keyboardButton = rule.onNodeWithTag("hardware-toolbar-keyboard").fetchSemanticsNode().boundsInRoot
        assertEquals(toolbar.right - 4f, languageButton.right, .1f)
        assertEquals(keyboardButton.center.y, languageButton.center.y, .1f)
        for (tag in listOf("hardware-toolbar-emoji", "hardware-toolbar-clipboard")) {
            val button = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertEquals("auxiliary button must keep its whole touch height: $tag", 48f, button.height, 0.1f)
            assertEquals("auxiliary button must keep its whole touch width: $tag", 48f, button.width, 0.1f)
            assertTrue("auxiliary button uses the second row", button.top >= languageButton.bottom)
            assertTrue("auxiliary button must fit its capsule: $tag $button vs $toolbar",
                button.left >= toolbar.left && button.right <= toolbar.right && button.bottom <= toolbar.bottom)
        }
        rule.onNodeWithTag("hardware-toolbar-keyboard").assertIsDisplayed()
        rule.onNodeWithTag("hardware-toolbar-language").assertIsDisplayed()
        rule.onNodeWithTag("hardware-toolbar-emoji").assertIsDisplayed().performTouchInput { click() }
        rule.onNodeWithTag("hardware-toolbar-clipboard").assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, clipboard); assertEquals(1, emoji); assertEquals(1, language) }
        rule.onNodeWithTag("hardware-toolbar-clipboard").assertIsDisplayed()
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertHeightIsEqualTo(104.dp)
        rule.onNodeWithTag("hardware-toolbar-keyboard").performClick()
        rule.runOnIdle { assertEquals(1, keyboard) }
    }

    @Test fun handleDragsVerticallyAndSnapsRightWithoutFiringAction() {
        show(width = 800, height = 600)
        val hostBounds = rule.onNodeWithTag("toolbar-host").fetchSemanticsNode().boundsInRoot
        val handleBounds = rule.onNodeWithTag("hardware-toolbar-drag").fetchSemanticsNode().boundsInRoot
        val start = handleBounds.center - hostBounds.topLeft
        rule.onNodeWithTag("toolbar-host").performTouchInput {
            down(start)
            moveTo(start + Offset(500f, -100f))
            up()
        }
        rule.runOnIdle {
            assertEquals(1f, position.value.xFraction, 0.001f)
            assertTrue(position.value.yFraction in 0.1f..0.9f)
            assertEquals(0, keyboard + emoji + clipboard + language)
        }
    }

    @Test fun minimumWindowKeeps48DpRecoveryButton() {
        show(width = 48, languageLabel = "あ")
        rule.onNodeWithText("あ", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("hardware-toolbar-language").assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(48.dp).assertIsDisplayed().performClick()
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertHeightIsEqualTo(96.dp)
        rule.onNodeWithTag("hardware-toolbar-keyboard").assertWidthIsEqualTo(48.dp).assertIsDisplayed().performClick()
        rule.onNodeWithTag("hardware-toolbar-drag").assertWidthIsEqualTo(48.dp)
        rule.runOnIdle {
            assertEquals(48, bounds!!.width())
            assertEquals(1, keyboard)
            assertEquals(1, language)
        }
    }

    @Test fun narrowWindowKeepsBothPrimaryActionsWithoutAHandle() {
        show(width = 120, languageLabel = "EN")
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(104.dp).assertHeightIsEqualTo(56.dp)
        rule.onNodeWithText("EN", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("hardware-toolbar-language").assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(48.dp).assertIsDisplayed().performClick()
        rule.onNodeWithTag("hardware-toolbar-keyboard").assertWidthIsEqualTo(48.dp)
            .assertHeightIsEqualTo(48.dp).assertIsDisplayed().performClick()
        rule.onNodeWithTag("hardware-toolbar-more").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, language); assertEquals(1, keyboard) }
    }

    @Test fun exactTwoKeyWidthKeepsLabelsAndTouchesWithoutPadding() {
        show(width = 96, languageLabel = "中")
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(96.dp).assertHeightIsEqualTo(48.dp)
        rule.onNodeWithText("中", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("hardware-toolbar-language").assertWidthIsEqualTo(48.dp).assertIsDisplayed().performClick()
        rule.onNodeWithTag("hardware-toolbar-keyboard").assertWidthIsEqualTo(48.dp).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, language); assertEquals(1, keyboard) }
    }

    @Test fun continuousDragMatchesFingerAcrossRecomposedFrames() {
        show(width = 800, height = 600)
        val host = rule.onNodeWithTag("toolbar-host")
        val hostBounds = host.fetchSemanticsNode().boundsInRoot
        val start = rule.onNodeWithTag("hardware-toolbar-drag").fetchSemanticsNode().boundsInRoot.center - hostBounds.topLeft
        host.performTouchInput { down(start); moveTo(start + Offset(12f, -40f)) }
        rule.waitForIdle()
        val first = rule.onNodeWithTag("hardware-keyboard-toolbar").fetchSemanticsNode().boundsInRoot.topLeft
        val movements = mutableListOf<Pair<Offset, Offset>>()
        repeat(5) { step ->
            host.performTouchInput { moveTo(start + Offset(12f + (step + 1) * 3f, -40f - (step + 1) * 20f)) }
            rule.waitForIdle()
            val now = rule.onNodeWithTag("hardware-keyboard-toolbar").fetchSemanticsNode().boundsInRoot.topLeft
            println("DRAG_AUDIT step=$step first=$first now=$now reported=$bounds")
            movements += (first + Offset((step + 1) * 3f, -(step + 1) * 20f)) to now
        }
        host.performTouchInput { up() }
        movements.forEach { (expected, actual) ->
            assertEquals(expected.x, actual.x, 2f)
            assertEquals(expected.y, actual.y, 2f)
        }
    }

    @Test fun dockingReorientsWithoutLosingActionsAndCanReturnToHorizontal() {
        show(width = 800, height = 600)
        rule.runOnIdle { position.value = HardwareToolbarPosition(0f, 0.5f) }
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(232.dp)
        rule.onNodeWithTag("hardware-toolbar-keyboard").assertIsDisplayed().performClick()
        rule.onNodeWithTag("hardware-toolbar-clipboard").assertIsDisplayed().performClick()
        val host = rule.onNodeWithTag("toolbar-host")
        val origin = host.fetchSemanticsNode().boundsInRoot.topLeft
        val start = rule.onNodeWithTag("hardware-toolbar-drag").fetchSemanticsNode().boundsInRoot.center - origin
        host.performTouchInput { down(start); moveTo(start + Offset(320f, 0f)); up() }
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(232.dp).assertHeightIsEqualTo(56.dp)
        assertHorizontalActionsEndWithLanguage()
        rule.runOnIdle { assertEquals(1, keyboard); assertEquals(1, clipboard) }
    }
    @Test fun draggingFromActionDoesNotClickAndDockPreviewAppears() {
        show(width = 800, height = 600)
        val host = rule.onNodeWithTag("toolbar-host")
        val origin = host.fetchSemanticsNode().boundsInRoot.topLeft
        val start = rule.onNodeWithTag("hardware-toolbar-clipboard").fetchSemanticsNode().boundsInRoot.center - origin
        host.performTouchInput { down(start); moveTo(start + Offset(-500f, -80f)) }
        rule.onNodeWithTag("hardware-toolbar-dock-preview").assertIsDisplayed()
        host.performTouchInput { up() }
        rule.runOnIdle { assertEquals(0, clipboard); assertEquals(0f, position.value.xFraction) }
        rule.onNodeWithTag("hardware-toolbar-clipboard").performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, clipboard) }
    }

    @Test fun bottomStripRetainsRecoveryAndReleasesIntoFreeToolbar() {
        show(width = 800, height = 600, docking = true)
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(720.dp)
        rule.onNodeWithTag("hardware-docked-candidates").assertIsDisplayed()
        rule.onNodeWithTag("hardware-toolbar-language").assertIsDisplayed()
        assertHorizontalActionsEndWithLanguage()
        rule.onNodeWithTag("hardware-toolbar-clipboard").assertIsDisplayed().performClick()
        val host = rule.onNodeWithTag("toolbar-host")
        val origin = host.fetchSemanticsNode().boundsInRoot.topLeft
        val start = rule.onNodeWithTag("hardware-toolbar-drag").fetchSemanticsNode().boundsInRoot.center - origin
        host.performTouchInput { down(start); moveTo(start + Offset(0f, -150f)); up() }
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(232.dp)
        rule.onNodeWithTag("hardware-docked-candidates").assertDoesNotExist()
        rule.onNodeWithTag("bar-candidate:0").assertDoesNotExist()
        assertHorizontalActionsEndWithLanguage()
        rule.onNodeWithTag("hardware-toolbar-keyboard").performClick()
        rule.runOnIdle { assertEquals(1, keyboard); assertEquals(1, clipboard) }
    }

    @Test fun suggestionsStayInOneRowWhenDraggedUpAndHideOnlyInTheSideToolbar() {
        show(width = 800, height = 600, docking = true, languageLabel = "EN", suggestions = true)
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(720.dp)
        rule.onNodeWithTag("bar-candidate:1").assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, selectedSuggestion) }
        val host = rule.onNodeWithTag("toolbar-host")
        val origin = host.fetchSemanticsNode().boundsInRoot.topLeft
        val start = rule.onNodeWithTag("hardware-toolbar-drag").fetchSemanticsNode().boundsInRoot.center - origin
        host.performTouchInput { down(start); moveTo(start + Offset(0f, -150f)); up() }
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(720.dp)
        rule.onNodeWithTag("hardware-docked-candidates").assertIsDisplayed()
        rule.onNodeWithTag("hardware-candidate-next").assertDoesNotExist()
        val first = rule.onNodeWithTag("bar-candidate:0").fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("bar-candidate:2").fetchSemanticsNode().boundsInRoot
        assertEquals("Suggestions stay in one row after dragging", first.center.y, last.center.y, 1f)
        rule.onNodeWithTag("bar-candidate:2").performTouchInput { click() }
        assertHorizontalActionsEndWithLanguage()
        rule.onNodeWithTag("hardware-toolbar-clipboard").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(2, selectedSuggestion)
            assertEquals(1, clipboard)
            position.value = HardwareToolbarPosition(0f, .5f)
        }
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(56.dp)
        rule.onNodeWithTag("hardware-docked-candidates").assertDoesNotExist()
        rule.onNodeWithTag("bar-candidate:0").assertDoesNotExist()
        rule.onNodeWithTag("hardware-toolbar-keyboard").assertIsDisplayed().performClick()
        rule.runOnIdle { position.value = HardwareToolbarPosition(.5f, .5f) }
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(720.dp)
        assertHorizontalActionsEndWithLanguage()
        rule.onNodeWithTag("bar-candidate:0").assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle { assertEquals(0, selectedSuggestion); assertEquals(1, keyboard) }
    }

    @Test fun suggestionsKeepTheExistingNarrowWindowRecoveryFallback() {
        show(width = 208, languageLabel = "EN", suggestions = true)
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(184.dp).assertHeightIsEqualTo(104.dp)
        rule.onNodeWithTag("hardware-docked-candidates").assertDoesNotExist()
        rule.onNodeWithTag("hardware-toolbar-language").assertIsDisplayed().performClick()
        rule.onNodeWithTag("hardware-toolbar-keyboard").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, language); assertEquals(1, keyboard) }
    }

    @Test fun candidateAndLanguageChangesKeepTheWideToolbarStationaryAndDiscardOldTargets() {
        show(width = 800, height = 600, docking = true, languageLabel = "EN", suggestions = true)
        val toolbar = rule.onNodeWithTag("hardware-keyboard-toolbar").fetchSemanticsNode().boundsInRoot
        val languageBounds = rule.onNodeWithTag("hardware-toolbar-language").fetchSemanticsNode().boundsInRoot
        val host = rule.onNodeWithTag("toolbar-host")
        val oldCandidatePoint = rule.onNodeWithTag("bar-candidate:1").fetchSemanticsNode().boundsInRoot.center -
            host.fetchSemanticsNode().boundsInRoot.topLeft
        assertHorizontalActionsEndWithLanguage()

        fun assertStableAcrossFrames() {
            repeat(8) {
                rule.mainClock.advanceTimeBy(32L)
                assertEquals("Candidate changes must not move or resize the capsule", toolbar,
                    rule.onNodeWithTag("hardware-keyboard-toolbar").fetchSemanticsNode().boundsInRoot)
                assertEquals("Language keeps its exact touch target", languageBounds,
                    rule.onNodeWithTag("hardware-toolbar-language").fetchSemanticsNode().boundsInRoot)
            }
        }

        rule.mainClock.autoAdvance = false
        try {
            rule.runOnIdle { candidateWords.value = emptyList() }
            assertStableAcrossFrames()
            rule.onNodeWithTag("bar-candidate:1").assertDoesNotExist()
            rule.onNodeWithText("world", useUnmergedTree = true).assertDoesNotExist()
            host.performTouchInput { click(oldCandidatePoint) }
            rule.runOnIdle { assertTrue("Empty space must not retain an old candidate callback", candidateSelections.isEmpty()) }

            rule.runOnIdle { activeLanguage.value = "中" }
            assertStableAcrossFrames()
            rule.onNodeWithText("中", useUnmergedTree = true).assertIsDisplayed()
            rule.runOnIdle {
                candidateWords.value = listOf("测试", "输入")
                floatingSuggestions.value = false
            }
            assertStableAcrossFrames()
            rule.onNodeWithTag("bar-candidate:0").assertIsDisplayed()
            rule.onNodeWithText("world", useUnmergedTree = true).assertDoesNotExist()

            rule.runOnIdle {
                activeLanguage.value = "EN"
                candidateWords.value = listOf("fresh", "new")
                floatingSuggestions.value = true
            }
            assertStableAcrossFrames()
            rule.onNodeWithTag("bar-candidate:1").performTouchInput { click() }
            rule.runOnIdle {
                assertEquals("new", selectedSuggestionWord)
                assertEquals(listOf("new"), candidateSelections)
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
        assertHorizontalActionsEndWithLanguage()
        rule.onNodeWithTag("hardware-toolbar-language").performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, language) }
    }

    @Test fun freeToolbarRetainsWidthWhilePendingThenContractsOnEmptyResult() {
        show(width = 800, height = 600, suggestions = true)
        rule.runOnIdle { position.value = HardwareToolbarPosition(.5f, .5f) }
        val toolbar = rule.onNodeWithTag("hardware-keyboard-toolbar")
        toolbar.assertWidthIsEqualTo(720.dp)
        val expanded = toolbar.fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        try {
            rule.runOnIdle {
                candidatesPending.value = true
                candidateWords.value = emptyList()
            }
            repeat(8) {
                rule.mainClock.advanceTimeBy(32L)
                assertEquals(expanded, toolbar.fetchSemanticsNode().boundsInRoot)
            }
            rule.onNodeWithTag("bar-candidate:0").assertDoesNotExist()
            rule.runOnIdle {
                candidateWords.value = listOf("new")
                candidatesPending.value = false
            }
            rule.mainClock.advanceTimeBy(32L)
            assertEquals(expanded, toolbar.fetchSemanticsNode().boundsInRoot)
            rule.onNodeWithTag("bar-candidate:0").performClick()
            rule.runOnIdle { assertEquals(listOf("new"), candidateSelections) }
            rule.runOnIdle { candidateWords.value = emptyList() }
            rule.mainClock.advanceTimeBy(300L)
        } finally {
            rule.mainClock.autoAdvance = true
        }
        toolbar.assertWidthIsEqualTo(232.dp)
        rule.onNodeWithTag("hardware-docked-candidates").assertDoesNotExist()
        assertHorizontalActionsEndWithLanguage()
    }

    @Test fun freeEmptyToolbarDoesNotExpandJustBecausePredictionStarts() {
        show(width = 800, height = 600, suggestions = true)
        rule.runOnIdle {
            position.value = HardwareToolbarPosition(.5f, .5f)
            candidateWords.value = emptyList()
        }
        val toolbar = rule.onNodeWithTag("hardware-keyboard-toolbar")
        toolbar.assertWidthIsEqualTo(232.dp)
        rule.runOnIdle { candidatesPending.value = true }
        toolbar.assertWidthIsEqualTo(232.dp)
        rule.runOnIdle { candidatesPending.value = false }
        toolbar.assertWidthIsEqualTo(232.dp)
        rule.runOnIdle { candidateWords.value = listOf("测试") }
        toolbar.assertWidthIsEqualTo(720.dp)
        rule.onNodeWithTag("bar-candidate:0").assertIsDisplayed()
        // Once composing, these words belong to the caret window, not the lower toolbar.
        rule.runOnIdle { floatingSuggestions.value = false }
        toolbar.assertWidthIsEqualTo(232.dp)
        rule.onNodeWithTag("bar-candidate:0").assertDoesNotExist()
        assertHorizontalActionsEndWithLanguage()
    }

    @Test fun freeToolbarExpansionNearTheRightEdgeStaysInsideTheHostEveryFrame() {
        show(width = 800, height = 600, suggestions = true)
        rule.runOnIdle {
            candidateWords.value = emptyList()
            position.value = HardwareToolbarPosition(.99f, .5f)
        }
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(232.dp)
        val host = rule.onNodeWithTag("toolbar-host").fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        try {
            rule.runOnIdle { candidateWords.value = listOf("测试") }
            repeat(20) {
                rule.mainClock.advanceTimeByFrame()
                val actual = rule.onNodeWithTag("hardware-keyboard-toolbar").fetchSemanticsNode().boundsInRoot
                assertTrue("Expanding frame must remain inside the host: $actual", actual.left >= host.left && actual.right <= host.right)
                assertTrue(actual.top >= host.top && actual.bottom <= host.bottom)
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(720.dp)
        assertHorizontalActionsEndWithLanguage()
    }

    @Test fun rightHandLanguageAndDirectActionsRemainPhysicalRightInAnRtlHost() {
        show(width = 800, height = 600, docking = true, languageLabel = "EN", suggestions = true,
            layoutDirection = LayoutDirection.Rtl)
        assertHorizontalActionsEndWithLanguage()
        val host = rule.onNodeWithTag("toolbar-host").fetchSemanticsNode().boundsInRoot
        val toolbar = rule.onNodeWithTag("hardware-keyboard-toolbar").fetchSemanticsNode().boundsInRoot
        assertTrue("RTL must not shift the capsule outside the host", toolbar.left >= host.left && toolbar.right <= host.right)
        assertEquals(host.center.x, toolbar.center.x, 1f)
        rule.onNodeWithTag("hardware-toolbar-keyboard").performTouchInput { click() }
        rule.onNodeWithTag("hardware-toolbar-emoji").performTouchInput { click() }
        rule.onNodeWithTag("hardware-toolbar-clipboard").performTouchInput { click() }
        rule.onNodeWithTag("hardware-toolbar-language").performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(listOf(1, 1, 1, 1), listOf(keyboard, emoji, clipboard, language))
            candidateWords.value = emptyList()
        }
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(720.dp)
        assertHorizontalActionsEndWithLanguage()
    }

    @Test fun largeFontReservesTheSameCandidateHeightBeforeAndAfterClearing() {
        show(width = 800, height = 600, docking = true, languageLabel = "EN", suggestions = true, fontScale = 2f)
        rule.onNodeWithTag("bar-candidate:0").assertIsDisplayed()
        val originalBounds = rule.onNodeWithTag("hardware-keyboard-toolbar").fetchSemanticsNode().boundsInRoot
        val originalLanguage = rule.onNodeWithTag("hardware-toolbar-language").fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { candidateWords.value = emptyList() }
        rule.onNodeWithTag("bar-candidate:0").assertDoesNotExist()
        assertEquals("Clearing candidates keeps the large-font row's height", originalBounds,
            rule.onNodeWithTag("hardware-keyboard-toolbar").fetchSemanticsNode().boundsInRoot)
        assertEquals(originalLanguage,
            rule.onNodeWithTag("hardware-toolbar-language").fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { candidateWords.value = listOf("新词", "new") }
        rule.onNodeWithTag("bar-candidate:0").assertIsDisplayed().performTouchInput { click() }
        assertEquals("New candidates reuse the same reserved row", originalBounds,
            rule.onNodeWithTag("hardware-keyboard-toolbar").fetchSemanticsNode().boundsInRoot)
        assertEquals(originalLanguage,
            rule.onNodeWithTag("hardware-toolbar-language").fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { assertEquals(listOf("新词"), candidateSelections) }
    }

    @Test fun fullNarrowToolbarRetainsItsGeometryAndLanguageTapVersusHold() {
        show(width = 300, height = 400, languageMenu = true)
        rule.onNodeWithTag("hardware-keyboard-toolbar").assertWidthIsEqualTo(232.dp).assertHeightIsEqualTo(56.dp)
        assertHorizontalActionsEndWithLanguage()
        val originalPosition = position.value
        val originalBounds = rule.onNodeWithTag("hardware-keyboard-toolbar").fetchSemanticsNode().boundsInRoot
        val languageKey = rule.onNodeWithTag("hardware-toolbar-language")
        rule.mainClock.autoAdvance = false
        try {
            languageKey.performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(350L)
            rule.onNodeWithTag("language-menu").assertIsDisplayed()
            rule.runOnIdle {
                assertEquals("Holding language must not invoke tap", 0, language)
                assertNull(selectedLanguage)
                assertEquals("Holding language must not drag the toolbar", originalPosition, position.value)
            }
            assertEquals(originalBounds,
                rule.onNodeWithTag("hardware-keyboard-toolbar").fetchSemanticsNode().boundsInRoot)
            languageKey.performTouchInput { up() }
            rule.mainClock.advanceTimeByFrame()
        } finally {
            rule.mainClock.autoAdvance = true
        }
        // Releasing without moving keeps the menu open for tap selection by design;
        // choose the shown language to close it, then reset the fixture counters so
        // the following tap still validates the language key itself.
        rule.onNodeWithTag("language-menu").assertIsDisplayed()
        rule.onAllNodes(hasAnyAncestor(hasTestTag("language-menu")) and hasClickAction(),
            useUnmergedTree = true).onFirst().performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("language-menu").fetchSemanticsNodes().isEmpty()
        }
        rule.runOnIdle { language = 0; selectedLanguage = null }
        languageKey.performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, language); assertNull(selectedLanguage) }
    }
}
