package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.CoroutineTestRule
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.PanelType
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.AsciiKeyboardContext
import com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.ui.keyboard.KeyboardViewState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JVM regressions for engine language changes while temporary handwriting is visible. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TemporaryHandwritingReturnTest {
    @get:Rule val mainDispatcher = CoroutineTestRule()

    private val store = ViewModelStore()
    private lateinit var vm: KeyboardViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeysConfigHelper.setActiveKeyboardSchema(T9_SCHEMA)
        vm = KeyboardViewModel(app)
        store.put("keyboard", vm)
    }

    @After
    fun tearDown() {
        store.clear()
        KeysConfigHelper.setActiveKeyboardSchema("")
    }

    @Test
    fun t9ToTemporaryHandwriting_asciiTrue_returnsEnglishFull() {
        startTyping(ascii = false)
        assertTyping(KeyboardLayoutState.T9Pinyin, KeyboardViewState.T9PinyinFull)

        enterHandwritingAndChangeAscii(ascii = true)

        assertTrue(vm.exitTemporaryHandwriting())
        assertTyping(KeyboardLayoutState.English, KeyboardViewState.EnglishFull)
        assertFalse(vm.hasTemporaryHandwriting)
        assertFalse(vm.exitTemporaryHandwriting())
        assertTyping(KeyboardLayoutState.English, KeyboardViewState.EnglishFull)
    }

    @Test
    fun englishToTemporaryHandwriting_asciiFalseWithT9Schema_returnsT9Full() {
        startTyping(ascii = true)
        assertTyping(KeyboardLayoutState.English, KeyboardViewState.EnglishFull)

        enterHandwritingAndChangeAscii(ascii = false)

        assertTrue(vm.exitTemporaryHandwriting())
        assertTyping(KeyboardLayoutState.T9Pinyin, KeyboardViewState.T9PinyinFull)
        assertFalse(vm.hasTemporaryHandwriting)
    }

    @Test
    fun backgroundAsciiRefresh_preservesEveryTemporaryTypingReturn() {
        for (layout in listOf(KeyboardLayoutState.Chinese, KeyboardLayoutState.English,
            KeyboardLayoutState.T9Pinyin, KeyboardLayoutState.Stroke)) {
            vm.setKeyboardState(layout)
            vm.enterTemporaryHandwriting()
            vm.dispatch(KeyboardDispatchAction.AsciiModeChanged(false, "pinyin_simp"))
            assertTrue(vm.hasTemporaryHandwriting)
            assertEquals(KeyboardPage.Main(MainType.HANDWRITING), vm.page.value)
            assertEquals(layout, vm.keyboardState.value)
            assertTrue(vm.exitTemporaryHandwriting())
            assertEquals(KeyboardPage.Main(MainType.FULL), vm.page.value)
            assertEquals(layout, vm.keyboardState.value)
            assertFalse(vm.exitTemporaryHandwriting())
        }
    }

    @Test
    fun backgroundAsciiRefresh_preservesTemporaryPanelReturnAndItsTypingLayout() {
        for (type in PanelType.entries) {
            startTyping(ascii = false)
            vm.enterPanel(type)
            val panelPage = vm.page.value
            val panelLayout = vm.keyboardState.value
            vm.enterTemporaryHandwriting()
            vm.dispatch(KeyboardDispatchAction.AsciiModeChanged(true, T9_SCHEMA))
            assertEquals(KeyboardPage.Main(MainType.HANDWRITING), vm.page.value)
            assertTrue(vm.exitTemporaryHandwriting())
            assertEquals(panelPage, vm.page.value)
            assertEquals(panelLayout, vm.keyboardState.value)
            vm.exitPanel()
            assertTyping(KeyboardLayoutState.T9Pinyin, KeyboardViewState.T9PinyinFull)
        }
    }

    @Test
    fun numberPanelToTemporaryHandwriting_asciiTrue_preservesPanelThenReturnsEnglish() {
        assertPanelReturnTracksAscii(PanelType.NUMBER)
    }

    @Test
    fun commonSymbolPanelToTemporaryHandwriting_asciiTrue_preservesPanelThenReturnsEnglish() {
        assertPanelReturnTracksAscii(PanelType.COMMON_SYMBOL)
    }

    @Test
    fun numberPanel_explicitLanguageChoice_preservesPanelThenReturnsEnglish() {
        assertPanelReturnTracksAscii(PanelType.NUMBER, temporaryHandwriting = false)
    }

    @Test
    fun commonSymbolPanel_explicitLanguageChoice_preservesPanelThenReturnsEnglish() {
        assertPanelReturnTracksAscii(PanelType.COMMON_SYMBOL, temporaryHandwriting = false)
    }

    @Test
    fun panelLocalAsciiChange_preservesMainLanguageMemory() {
        for (type in PanelType.entries) {
            startTyping(ascii = false)
            vm.asciiStateMachine.saveMemory(AsciiKeyboardContext.MAIN, false)
            vm.enterPanel(type)
            val page = vm.page.value
            val layout = vm.keyboardState.value
            vm.dispatch(KeyboardDispatchAction.AsciiModeChanged(true, T9_SCHEMA))
            assertEquals(page, vm.page.value)
            assertEquals(layout, vm.keyboardState.value)
            assertEquals(false, vm.asciiStateMachine.targetFor(AsciiKeyboardContext.MAIN, true))
            vm.exitPanel()
            assertTyping(KeyboardLayoutState.T9Pinyin, KeyboardViewState.T9PinyinFull)
        }
    }

    @Test
    fun schemaOpenedFromMenu_returnsToMenuBeforeTypingKeyboard() {
        startTyping(ascii = false)
        vm.toggleOverlay(OverlayRoute.Menu)
        val menuPage = vm.page.value
        vm.toggleOverlay(OverlayRoute.SchemaList)
        assertEquals(KeyboardPage.Overlay(OverlayRoute.SchemaList, listOf(OverlayRoute.Menu),
            KeyboardPage.Main(MainType.FULL)), vm.page.value)
        vm.popOverlay()
        assertEquals(menuPage, vm.page.value)
        vm.closeOverlay()
        assertTyping(KeyboardLayoutState.T9Pinyin, KeyboardViewState.T9PinyinFull)
    }

    @Test
    fun ordinaryOverlay_asciiChangeDoesNotReplaceVisiblePageOrBackingLayout() {
        startTyping(ascii = false)
        vm.showOverlay(OverlayRoute.Menu)
        val page = vm.page.value
        val layout = vm.keyboardState.value
        val viewState = vm.viewState.value
        assertEquals(
            KeyboardPage.Overlay(OverlayRoute.Menu, emptyList(), KeyboardPage.Main(MainType.FULL)),
            page,
        )
        assertEquals(
            KeyboardViewState.Overlay(OverlayRoute.Menu, emptyList(), KeyboardViewState.T9PinyinFull),
            viewState,
        )

        vm.dispatch(KeyboardDispatchAction.AsciiModeChanged(true, T9_SCHEMA))

        assertEquals(page, vm.page.value)
        assertEquals(layout, vm.keyboardState.value)
        assertEquals(viewState, vm.viewState.value)
        assertFalse(vm.hasTemporaryHandwriting)
    }

    @Test
    fun voiceToTemporaryHandwriting_asciiChangeDoesNotTurnReturnPageIntoFullKeyboard() {
        startTyping(ascii = false)
        vm.enterVoice()
        assertState(
            KeyboardPage.Main(MainType.VOICE),
            KeyboardLayoutState.T9Pinyin,
            KeyboardViewState.Voice,
        )

        enterHandwritingAndChangeAscii(ascii = true)

        assertTrue(vm.exitTemporaryHandwriting())
        assertFalse(vm.hasTemporaryHandwriting)
        assertState(
            KeyboardPage.Main(MainType.VOICE),
            KeyboardLayoutState.T9Pinyin,
            KeyboardViewState.Voice,
        )
    }

    private fun assertPanelReturnTracksAscii(type: PanelType, temporaryHandwriting: Boolean = true) {
        startTyping(ascii = false)
        assertTyping(KeyboardLayoutState.T9Pinyin, KeyboardViewState.T9PinyinFull)
        vm.enterPanel(type)
        val panelPage = KeyboardPage.Panel(type, MainType.FULL)
        val panelLayout = when (type) {
            PanelType.NUMBER -> KeyboardLayoutState.Number
            PanelType.COMMON_SYMBOL -> KeyboardLayoutState.CommonSymbol
        }
        val panelViewState = when (type) {
            PanelType.NUMBER -> KeyboardViewState.NumberPanel(MainType.FULL)
            PanelType.COMMON_SYMBOL -> KeyboardViewState.CommonSymbolPanel(MainType.FULL)
        }
        assertState(panelPage, panelLayout, panelViewState)

        if (temporaryHandwriting) {
            enterHandwritingAndChangeAscii(ascii = true)
            assertTrue(vm.exitTemporaryHandwriting())
        } else {
            vm.asciiStateMachine.saveMemory(AsciiKeyboardContext.MAIN, false)
            vm.dispatch(KeyboardDispatchAction.AsciiModeChanged(true, T9_SCHEMA, explicitLanguageChoice = true))
            assertNull("explicit language selection must not be undone by stale main memory",
                vm.asciiStateMachine.targetFor(AsciiKeyboardContext.MAIN, true))
        }
        assertFalse(vm.hasTemporaryHandwriting)
        // Updating the saved typing layout must not replace the saved panel itself.
        assertState(panelPage, panelLayout, panelViewState)
        vm.exitPanel()
        assertTyping(KeyboardLayoutState.English, KeyboardViewState.EnglishFull)
    }

    private fun startTyping(ascii: Boolean) {
        vm.dispatch(KeyboardDispatchAction.InputSessionStarted(ascii, T9_SCHEMA))
    }

    private fun enterHandwritingAndChangeAscii(ascii: Boolean) {
        val layoutBeforeHandwriting = vm.keyboardState.value
        vm.enterTemporaryHandwriting()
        assertTrue(vm.hasTemporaryHandwriting)
        assertState(
            KeyboardPage.Main(MainType.HANDWRITING),
            layoutBeforeHandwriting,
            KeyboardViewState.Handwriting,
        )

        vm.dispatch(KeyboardDispatchAction.AsciiModeChanged(ascii, T9_SCHEMA, explicitLanguageChoice = true))

        // An accepted language choice updates only the return snapshot, never the writing page.
        assertTrue(vm.hasTemporaryHandwriting)
        assertState(
            KeyboardPage.Main(MainType.HANDWRITING),
            layoutBeforeHandwriting,
            KeyboardViewState.Handwriting,
        )
    }

    private fun assertTyping(layout: KeyboardLayoutState, viewState: KeyboardViewState) {
        assertState(KeyboardPage.Main(MainType.FULL), layout, viewState)
        assertEquals("last main layout must follow the restored typing layout", layout, vm.lastMainLayout.value)
    }

    private fun assertState(
        page: KeyboardPage,
        layout: KeyboardLayoutState,
        viewState: KeyboardViewState,
    ) {
        assertEquals("page", page, vm.page.value)
        assertEquals("layout", layout, vm.keyboardState.value)
        assertEquals("viewState", viewState, vm.viewState.value)
    }

    private companion object {
        const val T9_SCHEMA = "t9_pinyin"
    }
}
