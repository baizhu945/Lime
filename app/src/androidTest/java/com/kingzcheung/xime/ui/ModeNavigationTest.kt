package com.kingzcheung.xime.ui

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.keyboard.*
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.viewmodel.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Production KeyboardView + real touch coordinates, not a recreated navigation mock. */
class ModeNavigationTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val context = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("mode_navigation_test_$name", mode)
    }
    private lateinit var vm: KeyboardViewModel
    private val keys = mutableListOf<String>()
    private val commits = mutableListOf<String>()
    private val rowLayoutFields = listOf("_zhRowsLayout", "_enRowsLayout").map { name ->
        KeysConfigHelper::class.java.getDeclaredField(name).apply { isAccessible = true }
    }
    private var previousRowLayouts = emptyList<Any?>()

    @Before fun prepare() {
        SettingsPreferences.getPrefsPublic(context).edit().clear()
            .putBoolean("reverse_symbol_swipe", true)
            .putBoolean("qwerty_period_key_v2", false)
            .putString("symbol_input_mode", "SWIPE_UP").commit()
        KeysConfigHelper.loadConfig(app)
        previousRowLayouts = rowLayoutFields.map { it.get(KeysConfigHelper) }
        KeyboardThemes.reload(app)
    }
    @After fun cleanup() {
        rowLayoutFields.zip(previousRowLayouts).forEach { (field, previous) -> field.set(KeysConfigHelper, previous) }
        SettingsPreferences.getPrefsPublic(context).edit().clear().commit()
    }

    private fun mount(schema: String, english: Boolean = false, width: Int = 400, split: Boolean = false, floating: Boolean = false) {
        SettingsPreferences.setSplitKeyboardEnabled(context, split)
        KeysConfigHelper.setActiveKeyboardSchema(schema)
        // Explicit legacy two-entry configuration. The current configured ?123 single
        // key is covered independently by ConfiguredModeKeyComposeTest.
        rowLayoutFields.forEachIndexed { index, field ->
            val rows = KeysConfigHelper.getKeyRows(schema, index == 1).take(3)
            field.set(KeysConfigHelper, com.kingzcheung.xime.settings.KeyboardRowsLayout(
                rows.map { com.kingzcheung.xime.settings.KeyboardRow(it) }))
        }
        vm = KeyboardViewModel(app)
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = width; screenHeightDp = if (width > 600) 700 else 850
                orientation = if (width > 600) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            var ui by remember { mutableStateOf(KeyboardUiState(currentSchemaId = schema, isAsciiMode = english,
                isDarkTheme = true, themeId = "soft_blue", keyboardHeightDp = 330,
                keyboardBottomPaddingDp = 12, isFloatingMode = floating, floatingScreenHeightDp = 700)) }
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context, LocalDensity provides Density(1f)) {
                XimeTheme(darkTheme = true, themeId = "soft_blue") {
                    Box(Modifier.size(width.dp, 360.dp).testTag("navigation-keyboard").background(KeyboardThemes.getKeyboardBackgroundColor("soft_blue", true))) {
                        KeyboardView(vm, ui, KeyboardCallbacks(onKeyPress = { key, _ ->
                            keys.add(key)
                            if (key == "ime_switch") ui = ui.copy(isAsciiMode = !ui.isAsciiMode)
                        }, onCandidateSelect = {}, onCommitText = { commits.add(it) }), modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun chineseCommaTapAndPeriodSwipe() = verifyComma(false)
    @Test fun englishCommaTapAndPeriodSwipe() = verifyComma(true)
    @Test fun splitEnglishCommaTapAndPeriodSwipe() = verifyComma(true, split = true)

    private fun verifyComma(english: Boolean, split: Boolean = false) {
        mount("rime_ice", english = english, split = split, width = if (split) 1000 else 400)
        val comma = rule.onNodeWithText(if (english) "," else "，", useUnmergedTree = true)
        comma.performTouchInput { click() }
        comma.performTouchInput { swipe(center, center - androidx.compose.ui.geometry.Offset(0f, 80f), 180) }
        rule.runOnIdle {
            // Current callbacks carry the exact printed glyph; retain the legacy key-identity
            // check and additionally verify width, rather than normalizing a wrong glyph away.
            val identities = keys.map { when (it) { "，" -> ","; "。" -> "."; else -> it } }
            assertEquals(listOf(",", "."), identities)
            assertEquals(if (english) listOf(",", ".") else listOf("，", "。"), keys)
        }
    }

    @Test fun numericOperatorColumnIsBalancedFrom14Key() = verifyNumericColumns(400)
    @Test fun numericOperatorColumnIsBalancedOnTablet() = verifyNumericColumns(1000)
    private fun verifyNumericColumns(width: Int) {
        mount("pinyin_14jian", width = width)
        val first = bounds(1); val second = bounds(2)
        tapAt(second)
        val operators = rule.onNodeWithTag("number-operators").fetchSemanticsNode().boundsInRoot
        val actions = rule.onNodeWithTag("number-actions").fetchSemanticsNode().boundsInRoot
        assertEquals("operator/action widths", actions.width, operators.width, 1.5f)
        assertNumericNavigationColumns()
        assertEquals("bottom row stays at the same height", first.top, bounds(1).top, 1.5f)
        rule.onNodeWithText("+").performTouchInput { click() }
        rule.onNodeWithText("7").performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf("+", "7"), keys) }
        save("balanced-number-$width")
        tapAt(bounds(2))
        rule.onNodeWithText("qw").assertExists()
    }

    @Test fun heldShiftSupportsTwoFingersAndRepeatedLetters() {
        mount("rime_ice", english = true)
        val root = rule.onNodeWithTag("navigation-keyboard", true)
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val shift = rule.onNodeWithTag("shift-key").fetchSemanticsNode().boundsInRoot.center - origin
        val a = rule.onNodeWithText("a").fetchSemanticsNode().boundsInRoot.center - origin
        val b = rule.onNodeWithText("b").fetchSemanticsNode().boundsInRoot.center - origin
        root.performTouchInput {
            down(0, shift)
            down(1, a); up(1)
            advanceEventTime(50)
            down(1, a); up(1)
            down(1, b); up(1)
            up(0)
        }
        rule.onNodeWithText("c").performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf("A", "A", "B", "c"), keys); assertEquals(ShiftMode.OFF, vm.shiftMode.value) }
    }
    @Test fun shiftSlideTypesOneUppercaseAndReleases() = verifyShiftSlide(false)
    @Test fun splitShiftSlideCrossesTheGapAndReleases() = verifyShiftSlide(true)
    @Test fun shiftDragWaitsForReleaseDespiteLongHoldAndVerticalMovement() {
        mount("rime_ice", english = true)
        val shift = rule.onNodeWithTag("shift-key")
        val origin = shift.fetchSemanticsNode().boundsInRoot.topLeft
        val p = rule.onNodeWithText("p").fetchSemanticsNode().boundsInRoot.center - origin
        val a = rule.onNodeWithText("a").fetchSemanticsNode().boundsInRoot.center - origin
        shift.performTouchInput { down(center); moveTo(p) }
        rule.mainClock.advanceTimeBy(1000)
        rule.runOnIdle { assertTrue(keys.isEmpty()); assertTrue(commits.isEmpty()) }
        shift.performTouchInput { moveTo(a); moveTo(a + androidx.compose.ui.geometry.Offset(0f, -70f)) }
        rule.mainClock.advanceTimeBy(1000)
        rule.runOnIdle { assertTrue(keys.isEmpty()); assertTrue(commits.isEmpty()) }
        shift.performTouchInput { moveTo(p); up() }
        rule.runOnIdle { assertEquals(listOf("P"), keys); assertTrue(commits.isEmpty()); assertEquals(ShiftMode.OFF, vm.shiftMode.value) }
    }

    @Test fun shiftDragReturningToShiftOrReleasedOutsideTypesNothingAndDoesNotToggle() {
        mount("rime_ice", english = true)
        val shift = rule.onNodeWithTag("shift-key")
        val origin = shift.fetchSemanticsNode().boundsInRoot.topLeft
        val p = rule.onNodeWithText("p").fetchSemanticsNode().boundsInRoot.center - origin
        shift.performTouchInput { down(center); moveTo(p); moveTo(center); up() }
        rule.runOnIdle { assertTrue(keys.isEmpty()); assertEquals(ShiftMode.OFF, vm.shiftMode.value) }
        shift.performTouchInput { down(center); moveTo(p); moveTo(androidx.compose.ui.geometry.Offset(-80f, -80f)); up() }
        rule.runOnIdle { assertTrue(keys.isEmpty()); assertEquals(ShiftMode.OFF, vm.shiftMode.value) }
        shift.performTouchInput { down(center); moveTo(p); cancel() }
        rule.onNodeWithText("a").performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf("a"), keys); assertEquals(ShiftMode.OFF, vm.shiftMode.value) }
    }

    @Test fun mergedShiftDragSelectsOnlyTheLetterUnderTheFinger() {
        mount("pinyin_14jian")
        val shift = rule.onNodeWithTag("shift-key")
        val origin = shift.fetchSemanticsNode().boundsInRoot.topLeft
        val qw = rule.onNodeWithText("qw").fetchSemanticsNode().boundsInRoot
        val q = androidx.compose.ui.geometry.Offset(qw.left + qw.width * .25f, qw.center.y) - origin
        val w = androidx.compose.ui.geometry.Offset(qw.left + qw.width * .75f, qw.center.y) - origin
        shift.performTouchInput { down(center); moveTo(q); up() }
        shift.performTouchInput { down(center); moveTo(w); up() }
        rule.runOnIdle { assertEquals(listOf("Q", "W"), keys); assertEquals(ShiftMode.OFF, vm.shiftMode.value) }
    }
    private fun verifyShiftSlide(split: Boolean) {
        mount("rime_ice", english = true, split = split, width = if (split) 1000 else 400)
        val key = rule.onNodeWithTag("shift-key")
        val node = key.fetchSemanticsNode()
        val target = rule.onNodeWithText("p").fetchSemanticsNode().positionOnScreen
        key.performTouchInput { down(center); moveTo(target - node.positionOnScreen + androidx.compose.ui.geometry.Offset(5f, 5f)); up() }
        rule.onNodeWithText("q").performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf("P", "q"), keys); assertEquals(ShiftMode.OFF, vm.shiftMode.value) }
    }
    @Test fun shiftTapDoubleTapAndCancelledSlideRetainTheirMeanings() {
        mount("rime_ice", english = true)
        val shift = rule.onNodeWithTag("shift-key")
        shift.performTouchInput { click() }
        rule.onNodeWithText("A").performTouchInput { click() }
        rule.onNodeWithText("b").performTouchInput { click() }
        shift.performTouchInput { doubleClick() }
        rule.onNodeWithText("C").performTouchInput { click() }
        rule.onNodeWithText("D").performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf("A", "b", "C", "D"), keys); vm.resetShift() }
        shift.performTouchInput { down(center); moveTo(center - androidx.compose.ui.geometry.Offset(200f, 200f)); cancel() }
        rule.onNodeWithText("e").performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf("A", "b", "C", "D", "e"), keys); assertEquals(ShiftMode.OFF, vm.shiftMode.value) }
    }

    private fun assertNumericNavigationColumns() {
        val operators = rule.onNodeWithTag("number-operators").fetchSemanticsNode().boundsInRoot
        val first = bounds(1); val second = bounds(2)
        assertEquals("first mode key aligns with operators", operators.left, first.left, 1.5f)
        assertEquals("first mode key spans operator width", operators.width, first.width, 1.5f)
        assertEquals("both mode keys have equal widths", first.width, second.width, 1.5f)
        assertEquals("second mode key sits immediately to the right", first.right, second.left, 1.5f)
        val zero = rule.onNodeWithTag("number-zero", true).fetchSemanticsNode().boundsInRoot
        assertEquals("zero starts after both mode keys", second.right, zero.left, 1.5f)
        assertTrue("zero must no longer take most of the bottom row", zero.width < first.width * 2.5f)
    }

    private fun bounds(slot: Int): Rect = rule.onNodeWithTag("mode-slot-$slot").fetchSemanticsNode().boundsInRoot
    private fun same(expected: Rect, actual: Rect) {
        assertEquals("slot left", expected.left, actual.left, 1.5f)
        assertEquals("slot top", expected.top, actual.top, 1.5f)
        assertEquals("slot right", expected.right, actual.right, 1.5f)
        assertEquals("slot bottom", expected.bottom, actual.bottom, 1.5f)
    }
    private fun tapAt(rect: Rect) {
        val root = rule.onNodeWithTag("navigation-keyboard", true).fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("navigation-keyboard", true).performTouchInput { click(rect.center - root.topLeft) }
        rule.waitForIdle()
    }
    private fun label(slot: Int, expected: String) {
        try {
            rule.onNode(hasTestTag("mode-slot-$slot") and
                (hasText(expected) or hasAnyDescendant(hasText(expected)))).assertExists()
        } catch (failure: AssertionError) {
            println("MODE_LABEL_FAILURE slot=$slot expected=$expected " +
                rule.onAllNodesWithTag("mode-slot-$slot", true).fetchSemanticsNodes().indices.joinToString("\n") {
                    rule.onAllNodesWithTag("mode-slot-$slot", true)[it].printToString()
                })
            throw failure
        }
    }
    private fun verifyTriangle(label: String, screenshot: String) {
        val original = vm.keyboardState.value
        val first = bounds(1); val second = bounds(2)
        label(1, "!@#"); label(2, "123")
        tapAt(first) // text -> symbols
        label(1, label); label(2, "123")
        same(first, bounds(1)); same(second, bounds(2))
        rule.onNodeWithText("暂无最近使用").assertDoesNotExist()
        save("$screenshot-symbols")
        tapAt(first) // same physical coordinate -> text
        rule.runOnIdle { assertEquals(original, vm.keyboardState.value); assertTrue(vm.page.value is KeyboardPage.Main) }
        tapAt(second) // text -> numbers
        label(1, "!@#"); label(2, label)
        assertNumericNavigationColumns()
        rule.onNodeWithTag("number-digit:1").performTouchInput { click() }
        save("$screenshot-numbers")
        tapAt(bounds(2)) // numeric return shares the operator-column width
        rule.runOnIdle { assertEquals(original, vm.keyboardState.value); assertTrue(vm.page.value is KeyboardPage.Main) }
        tapAt(first); tapAt(second) // text -> symbols -> numbers
        label(1, "!@#"); label(2, label)
        tapAt(first) // numbers -> symbols
        label(1, label); label(2, "123")
        tapAt(first) // symbols -> text, never back to numbers
        rule.runOnIdle {
            assertEquals(original, vm.keyboardState.value)
            assertTrue(vm.page.value is KeyboardPage.Main)
            assertEquals(listOf("1"), keys) // mode keys cannot type commands or change language
        }
        save("$screenshot-text")
    }
    private fun save(name: String) {
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        val dir = File(app.getExternalFilesDir(null), "mode-navigation").apply { mkdirs() }
        rule.onNodeWithTag("navigation-keyboard", true).captureToImage().asAndroidBitmap().let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun chinese26UsesDirectPeerNavigation() { mount("rime_ice"); verifyTriangle("中文", "chinese26") }
    @Test fun englishReturnNeverUsesGlobeOrChangesLanguage() { mount("rime_ice", true); verifyTriangle("ABC", "english") }
    @Test fun chinese14KeepsTheSameTwoSlots() { mount("pinyin_14jian"); verifyTriangle("中文", "chinese14") }
    @Test fun doublePinyinKeepsTheSameTwoSlots() { mount("double_pinyin_flypy"); verifyTriangle("中文", "double-pinyin") }
    @Test fun nineKeyOnTabletKeepsExactNavigationBounds() { mount("t9_pinyin", width = 1000); verifyTriangle("中文", "tablet-t9") }
    @Test fun strokeKeepsExactNavigationBounds() { mount("stroke"); verifyTriangle("中文", "stroke") }
    @Test fun splitKeyboardKeepsTheTwoSlotsAtTheLeftEdge() { mount("rime_ice", true, 1000, true); verifyTriangle("ABC", "split") }
    @Test fun floatingKeyboardKeepsItsSlotCoordinates() { mount("rime_ice", false, 1000, floating = true); verifyTriangle("中文", "floating") }
    @Test fun japanese26ReturnsToJapanese() { mount("jaroomaji"); verifyTriangle("あいう", "japanese26") }

    @Test fun japaneseFlickKeepsItsKeysAndOpensCommonSymbolsInOneTap() {
        mount("japanese_kana")
        val symbolBounds = rule.onNodeWithText("!@#").fetchSemanticsNode().boundsInRoot
        val numbersBounds = rule.onNodeWithText("123").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("!@#").performTouchInput { click() }
        rule.onNodeWithText("常用").assertIsDisplayed()
        rule.onNodeWithText("、").assertIsDisplayed()
        rule.onNodeWithText("ー").assertIsDisplayed()
        rule.onNodeWithText("符号").assertDoesNotExist()
        label(1, "あいう")
        rule.onNodeWithTag("mode-slot-1", true).performTouchInput { click() }
        same(symbolBounds, rule.onNodeWithText("!@#").fetchSemanticsNode().boundsInRoot)
        same(numbersBounds, rule.onNodeWithText("123").fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { assertTrue(keys.isEmpty()) }
        save("japanese-flick")
    }

    @Test fun handwritingReturnsToTheCanvasFromEitherSecondaryPage() {
        mount("rime_ice")
        rule.runOnIdle { vm.switchMain(MainType.HANDWRITING) }
        val first = rule.onNodeWithTag("handwriting-key:symbol", true).fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithTag("handwriting-key:number", true).fetchSemanticsNode().boundsInRoot
        tapAt(first)
        same(first, bounds(1)); same(second, bounds(2))
        tapAt(first)
        rule.onNodeWithTag("handwriting-canvas", true).assertIsDisplayed()
        tapAt(second)
        assertNumericNavigationColumns()
        tapAt(bounds(2))
        rule.onNodeWithTag("handwriting-canvas", true).assertIsDisplayed()
    }

    @Test fun viewModelReturnsToOriginalLayoutAndMainThroughToolOverlays() {
        vm = KeyboardViewModel(app)
        for (layout in listOf(KeyboardLayoutState.Chinese, KeyboardLayoutState.English, KeyboardLayoutState.T9Pinyin, KeyboardLayoutState.Stroke)) {
            vm.switchMain(MainType.FULL); vm.setKeyboardState(layout)
            vm.enterPanel(PanelType.NUMBER); vm.showOverlay(OverlayRoute.Symbol)
            vm.enterPanel(PanelType.NUMBER); vm.showOverlay(OverlayRoute.Menu)
            vm.enterPanel(PanelType.NUMBER); vm.returnToTextKeyboard()
            assertEquals(layout, vm.keyboardState.value)
            assertEquals(KeyboardPage.Main(MainType.FULL), vm.page.value)
        }
        vm.switchMain(MainType.HANDWRITING)
        vm.enterPanel(PanelType.NUMBER); vm.showOverlay(OverlayRoute.Symbol)
        vm.enterPanel(PanelType.NUMBER); vm.returnToTextKeyboard()
        assertEquals(KeyboardPage.Main(MainType.HANDWRITING), vm.page.value)
    }
}
