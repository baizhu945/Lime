package com.kingzcheung.xime.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.InputModes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real input connection + complete gestures across the shared literal-input route. */
class LiteralInputModesImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val engine = RimeEngine.getInstance()
    private lateinit var editor: EditText
    private val swipeDistance get() = 80f * context.resources.displayMetrics.density
    private val imeComponent get() = ComponentName(context, "com.kingzcheung.xime.service.XimeInputMethodService")
    private val imeId get() = imeComponent.flattenToShortString()
    private val inputMethodManager get() = context.getSystemService(InputMethodManager::class.java)
    private var previousEnabledSchemas: List<String>? = null
    private var previousPunctuationWidth: Boolean? = null
    private var previousReverseSymbolSwipe: Boolean? = null
    private var previousSymbolInputMode: String? = null
    private var previousModeChangeTarget: String? = null
    private var previousAsciiPunct = false
    private var previousFullShape = false
    private var previousIme: String? = null
    private var previouslyEnabled = false
    private var previousSubtype = -1
    private var savedSystemImeState = false

    @Before fun startKeyboard(): Unit = runBlocking {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        previousPunctuationWidth = if (prefs.contains(SettingsPreferences.KEY_PUNCTUATION_FULL_WIDTH))
            prefs.getBoolean(SettingsPreferences.KEY_PUNCTUATION_FULL_WIDTH, false) else null
        // Physical upward swipes enter upper-corner symbols only in the reversed setting.
        // Own both gesture preferences, independently of the emulator's saved settings.
        previousReverseSymbolSwipe = if (prefs.contains("reverse_symbol_swipe"))
            prefs.getBoolean("reverse_symbol_swipe", false) else null
        previousSymbolInputMode = prefs.getString("symbol_input_mode", null)
        previousModeChangeTarget = prefs.getString("mode_change_target", null)
        assertTrue(prefs.edit().putBoolean("reverse_symbol_swipe", true)
            .putString("symbol_input_mode", "SWIPE_UP")
            .putBoolean(SettingsPreferences.KEY_PUNCTUATION_FULL_WIDTH, false).commit())
        previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        previousSubtype = Settings.Secure.getInt(context.contentResolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, -1)
        previouslyEnabled = inputMethodManager.enabledInputMethodList.any { it.id == imeId }
        savedSystemImeState = true
        // A service-owned ComposeView outlives a test's ComposeTestRule. Reusing it in the
        // next test would keep the previous rule's disposed recomposer and invisible test root.
        stopKeyboardService()
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        engine.initialize(user, shared)
        // This isolated IME test exercises optional Japanese schemes as well as the defaults.
        previousEnabledSchemas = com.kingzcheung.xime.settings.SchemaManager.getEnabledSchemas(context)
        com.kingzcheung.xime.settings.SchemaManager.setEnabledSchemas(context,
            previousEnabledSchemas.orEmpty() + listOf("rime_ice", "t9_pinyin", "pinyin_14jian", "japanese", "japanese_kana"))
        assertTrue(RimeConfigHelper.ensureDeployment(context))
        assertTrue(engine.ensureSession())
        previousAsciiPunct = engine.getUserConfigBool("var/option/ascii_punct")
        previousFullShape = engine.getUserConfigBool("var/option/full_shape")
        engine.clearQueuedComposition()
        shell("ime enable $imeId")
        shell("ime set $imeId")
        rule.setContent {
            AndroidView(factory = { EditText(it).also { field ->
                editor = field
                field.hint = "字面输入回归"
                field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } },
                modifier = Modifier.fillMaxWidth().height(120.dp))
        }
        rule.waitUntil(10_000) {
            var ready = false
            rule.runOnUiThread { ready = editor.isAttachedToWindow && editor.hasWindowFocus() }
            ready
        }
        rule.runOnUiThread {
            assertTrue("测试输入框必须取得焦点", editor.requestFocus())
            inputMethodManager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("language-key-control", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After fun stopKeyboardAndRestoreSystemIme() {
        if (!savedSystemImeState) return
        try {
            previousEnabledSchemas?.let { com.kingzcheung.xime.settings.SchemaManager.setEnabledSchemas(context, it) }
            SettingsPreferences.getPrefsPublic(context).edit().apply {
                val previous = previousPunctuationWidth
                if (previous == null) remove(SettingsPreferences.KEY_PUNCTUATION_FULL_WIDTH)
                else putBoolean(SettingsPreferences.KEY_PUNCTUATION_FULL_WIDTH, previous)
                val reverse = previousReverseSymbolSwipe
                if (reverse == null) remove("reverse_symbol_swipe")
                else putBoolean("reverse_symbol_swipe", reverse)
                val symbolMode = previousSymbolInputMode
                if (symbolMode == null) remove("symbol_input_mode")
                else putString("symbol_input_mode", symbolMode)
                val modeTarget = previousModeChangeTarget
                if (modeTarget == null) remove("mode_change_target")
                else putString("mode_change_target", modeTarget)
            }.commit()
            engine.setUserConfigBool("var/option/ascii_punct", previousAsciiPunct)
            engine.setUserConfigBool("var/option/full_shape", previousFullShape)
            if (::editor.isInitialized) {
                rule.runOnUiThread {
                    inputMethodManager.hideSoftInputFromWindow(editor.windowToken, 0)
                    editor.clearFocus()
                }
            }
            stopKeyboardService()
        } finally {
            if (previouslyEnabled) shell("ime enable $imeId")
            previousIme?.takeIf { it.isNotBlank() }?.let { shell("ime set $it") }
            shell("settings put secure selected_input_method_subtype $previousSubtype")
        }
    }

    @Suppress("DEPRECATION") // Android still exposes this application's own running services.
    private fun stopKeyboardService() {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val waitForEngineDestroy = RimeEngine.isInitialized() &&
            activityManager.getRunningServices(Int.MAX_VALUE).any { it.service == imeComponent }
        shell("ime disable $imeId")
        rule.waitUntil(10_000) {
            activityManager.getRunningServices(Int.MAX_VALUE).none { it.service == imeComponent } &&
                (!waitForEngineDestroy || !RimeEngine.isInitialized())
        }
        // Service removal and delivery of onDestroy happen on different threads. Drain Main
        // before initializing Rime again or allowing this test's recomposer to be disposed.
        instrumentation.waitForIdleSync()
    }

    private fun chooseMode(id: String) {
        rule.chooseModeThroughLanguageAndPanel(id)
    }

    /** The configured text row now has one ?123 key; its hold menu still opens either page directly. */
    private fun openTextPanel(symbols: Boolean) {
        val configured = rule.onAllNodesWithTag("configured-mode-key", useUnmergedTree = true)
        if (configured.fetchSemanticsNodes().isEmpty()) {
            rule.onNodeWithTag(if (symbols) "mode-slot-1" else "mode-slot-2", true)
                .performTouchInput { click() }
            return
        }
        val key = configured.onFirst()
        val hold = com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.read(context).symbolKeyHoldMs
        rule.mainClock.autoAdvance = false
        try {
            key.performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(maxOf(600L, hold + 100L))
            key.performTouchInput {
                if (symbols) moveBy(Offset(width * 0.5f, 0f))
                up()
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()
        if (symbols) {
            // The hold menu opens common symbols; its 符号 key opens the full category page
            // exercised by these tests, equivalent to the former dedicated !@# key.
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("common-symbol-panel", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("符号").performTouchInput { click() }
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("symbol-category-strip", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.waitForIdle()
        }
    }

    private fun tap(label: String) {
        rule.onNodeWithText(label, ignoreCase = true).performTouchInput { down(center); up() }
    }

    private fun swipeUp(label: String) {
        val distance = swipeDistance
        rule.onNodeWithText(label, ignoreCase = true).performTouchInput {
            down(center)
            moveTo(center - Offset(0f, distance), delayMillis = 120)
            up()
        }
    }

    private fun text(): String {
        var value = ""
        rule.runOnUiThread { value = editor.text.toString() }
        return value
    }

    private fun assertSettled(expected: String) {
        try {
            rule.waitUntil(5000) { text() == expected && engine.getInput().isEmpty() }
        } catch (failure: Throwable) {
            println("LiteralInputModesImeTest: expected=[$expected] text=[${text()}] input=[${engine.getInput()}] " +
                "preedit=[${engine.getComposition().preedit}] schema=${engine.getCurrentSchema()}")
            throw failure
        }
        android.os.SystemClock.sleep(350)
        rule.waitForIdle()
        assertEquals(expected, text())
        assertEquals("字面上屏后不能留旧编码", "", engine.getInput())
    }

    @Test fun editingPanelCutUndoRedoAndSelectionUseTheRealHost() {
        chooseMode(InputModes.ENGLISH)
        rule.runOnUiThread { editor.setText("abc"); editor.setSelection(3) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("编辑").performClick()
        rule.onNodeWithContentDescription("全选").performClick()
        rule.onNodeWithContentDescription("剪切").performClick()
        rule.waitUntil(5000) { text().isEmpty() }
        rule.onNodeWithContentDescription("撤销").performClick()
        rule.waitUntil(5000) { text().equals("abc", ignoreCase = true) }
        rule.onNodeWithContentDescription("重做").performClick()
        rule.waitUntil(5000) { text().isEmpty() }
    }

    @Test fun punctuationWidthWorksFromMenuIn26Keys() = verifyPunctuationWidth("rime_ice", "，")
    @Test fun punctuationWidthWorksFromMenuIn14Keys() = verifyPunctuationWidth("pinyin_14jian", "，")
    @Test fun punctuationWidthWorksFromMenuInNineKeys() = verifyPunctuationWidth("t9_pinyin", "，")
    @Test fun englishSymbolCategoryKeepsLiteralGlyphsInFullWidthChineseT9() {
        chooseMode("t9_pinyin")
        setWidthFromMenu(true)
        openTextPanel(symbols = true)
        rule.onNodeWithTag("symbol-category:englishSymbols", true).performScrollTo().performTouchInput { click() }
        rule.waitForIdle()
        rule.onNodeWithTag("symbol-key:.", true).performTouchInput { click() }
        rule.onNodeWithTag("symbol-key:,", true).performTouchInput { click() }
        rule.onNodeWithTag("symbol-key:?", true).performTouchInput { click() }
        assertSettled(".,?")
        // Selecting English symbols must not change the Chinese punctuation preference.
        rule.onNodeWithTag("symbol-category:common", true).performScrollTo().performTouchInput { click() }
        rule.waitForIdle()
        rule.onNodeWithTag("symbol-key:，", true).performTouchInput { click() }
        assertSettled(".,?，")
        rule.onNodeWithContentDescription("返回键盘", useUnmergedTree = true).performTouchInput { click() }
        rule.onAllNodesWithText("，").onLast().performTouchInput { click() }
        assertSettled(".,?，，")
    }

    @Test fun englishPunctuationStaysHalfWidthAndOffersNoWidthEntry() {
        chooseMode(InputModes.ENGLISH)
        // 英文（ASCII）模式标点固定半角：菜单里不出现全角／半角入口，
        // 因此也不会出现"点了看不到状态变化、实际却已经改了状态"的情况。
        rule.onNodeWithTag("toolbar-leading").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("menu-item:float").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("menu-item:full_shape").assertDoesNotExist()
        rule.onNodeWithTag("toolbar-leading").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText(",").onLast().performTouchInput { click() }
        assertSettled(",")
        tap("a")
        rule.waitUntil(5000) { text().endsWith("a", ignoreCase = true) }
        assertFalse(text().contains('ａ'))
    }
    @Test fun punctuationWidthWorksInJapaneseFlick() = verifyPunctuationWidth("japanese_kana", "、")
    @Test fun punctuationWidthWorksInJapanese26() = verifyPunctuationWidth("japanese", "、")

    @Test fun japaneseSpaceAndDigitsFollowFullWidthIn26Keys() = verifyJapaneseSpaceAndDigits("japanese")
    @Test fun japaneseSpaceAndDigitsFollowFullWidthInKanaKeys() = verifyJapaneseSpaceAndDigits("japanese_kana")

    private fun verifyJapaneseSpaceAndDigits(schema: String) {
        chooseMode(schema)
        setWidthFromMenu(true)
        rule.onNodeWithTag(if (schema == "japanese_kana") "kana-space" else "space-key").performTouchInput { click() }
        assertSettled("　")
        if (schema == "japanese_kana") rule.onNodeWithTag("kana-number").performTouchInput { click() }
        else openTextPanel(symbols = false)
        tap("１")
        tap("．")
        tap("２")
        assertSettled("　１．２")
        setWidthFromMenu(false)
        tap("3")
        rule.onNodeWithTag(if (schema == "japanese_kana") "kana-space" else "space-key")
            .performTouchInput { click() }
        assertSettled("　１．２3 ")
    }

    private fun setWidthFromMenu(full: Boolean) {
        rule.onNodeWithTag("toolbar-leading").performClick()
        val tag = "menu-item:full_shape"
        rule.waitUntil(5000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        val desired = if (full) "全角" else "半角"
        fun shown() = rule.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
        if (shown() != desired) rule.onNodeWithTag(tag).performClick()
        rule.waitUntil(5000) { shown() == desired }
        rule.onNodeWithTag("toolbar-leading").performClick()
        rule.waitForIdle()
    }

    private fun verifyPunctuationWidth(schema: String, commaLabel: String) {
        chooseMode(schema)
        setWidthFromMenu(false)
        rule.onAllNodesWithText(",").onLast().performTouchInput { click() }
        assertSettled(",")
        setWidthFromMenu(true)
        val fullComma = if (schema.startsWith("japanese")) "、" else "，"
        rule.onAllNodesWithText(fullComma).onLast().performTouchInput { click() }
        assertSettled("," + fullComma)
        setWidthFromMenu(false)
        rule.onAllNodesWithText(",").onLast().performTouchInput { click() }
        assertSettled("," + fullComma + ",")
        rule.onNodeWithTag("candidate-expansion").assertDoesNotExist()
        // A separate symbol-page path uses onCommitText and must obey the same switch.
        if (schema != "japanese_kana") {
            openTextPanel(symbols = true)
            rule.onNode(hasText("?") and hasAnyAncestor(hasTestTag("symbol-grid:common")),
                useUnmergedTree = true).performTouchInput { click() }
            assertSettled("," + fullComma + ",?")
            rule.onNodeWithContentDescription("返回键盘", useUnmergedTree = true).performTouchInput { click() }
        }
    }

    @Test fun mergedKeySwipeDigitsAndSymbolsNeverStartPredictionsOrTypeLetters() {
        val previousSymbols = com.kingzcheung.xime.settings.FourteenKeySymbols.load(context)
        try {
            // Exercise explicit digit/symbol mappings, independently of evolving presets.
            com.kingzcheung.xime.settings.FourteenKeySymbols.save(context, previousSymbols + mapOf(
                "up.qw" to "1", "up.zx" to "*", "up.er" to "2", "up.ty" to "3"))
            SettingsPreferences.setPunctuationFullWidth(context, false)
            chooseMode("pinyin_14jian")
            listOf("qw" to "1", "zx" to "1*", "er" to "1*2", "ty" to "1*23").forEach { (key, output) ->
                swipeUp(key)
                assertSettled(output)
                rule.onNodeWithTag("candidate-expansion").assertDoesNotExist()
                rule.onNodeWithTag("candidate-preedit").assertDoesNotExist()
            }
            tap("qw")
            // Snapshot[0] is the internal ambiguous-key token Q; the displayed reading is q.
            rule.waitUntil(5000) { engine.getComposition().preedit == "q" }
            assertEquals("合并键保留内部歧义编码", "Q", engine.readPinyinEditSnapshot().firstOrNull())
            swipeUp("qw")
            rule.waitUntil(5000) { text().endsWith("1") && engine.readPinyinEditSnapshot().firstOrNull().isNullOrEmpty() }
            android.os.SystemClock.sleep(600)
            rule.onNodeWithTag("candidate-expansion").assertDoesNotExist()
            rule.onNodeWithTag("candidate-preedit").assertDoesNotExist()
        } finally {
            com.kingzcheung.xime.settings.FourteenKeySymbols.save(context, previousSymbols)
        }
    }

    @Test fun shiftHoldAndSlideCommitUppercaseThenRestoreLowercaseInEnglish() {
        chooseMode(InputModes.ENGLISH)
        holdShiftAndType("a", "a", "b")
        assertSettled("AAB")
        tap("c")
        rule.waitUntil(5000) { text() == "AABc" }
        slideShiftTo("p")
        rule.waitUntil(5000) { text() == "AABcP" }
        tap("q")
        assertSettled("AABcPq")
    }

    @Test fun shiftHoldAndSlideInChineseReturnToPinyinAfterRelease() {
        chooseMode("rime_ice")
        holdShiftAndType("a", "b")
        assertSettled("AB")
        slideShiftTo("p")
        assertSettled("ABP")
        tap("n"); tap("i")
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot().firstOrNull() == "ni" }
        assertEquals("松开 Shift 后小写必须继续中文组词", "ABP", text())
        assertTrue(engine.getCandidates().contains("你"))
        engine.clearQueuedComposition()
    }

    private fun holdShiftAndType(vararg letters: String) {
        val shift = rule.onNodeWithTag("shift-key")
        val origin = shift.fetchSemanticsNode().positionOnScreen
        val positions = letters.map { letter ->
            val key = rule.onNodeWithText(letter, ignoreCase = true).fetchSemanticsNode()
            key.positionOnScreen + Offset(key.size.width / 2f, key.size.height / 2f) - origin
        }
        shift.performTouchInput {
            down(0, center)
            positions.forEach { position -> down(1, position); up(1); advanceEventTime(50) }
            up(0)
        }
    }

    private fun slideShiftTo(letter: String) {
        val shift = rule.onNodeWithTag("shift-key")
        val origin = shift.fetchSemanticsNode().positionOnScreen
        val key = rule.onNodeWithText(letter, ignoreCase = true).fetchSemanticsNode()
        val target = key.positionOnScreen + Offset(key.size.width / 2f, key.size.height / 2f) - origin
        shift.performTouchInput { down(center); moveTo(target, delayMillis = 150); up() }
    }

    @Test fun englishTypedWordThenSwipeDigitDoesNotRepeatTheWord() {
        chooseMode(InputModes.ENGLISH)
        listOf("h", "i").forEach(::tap)
        rule.waitUntil(5000) { text().equals("hi", ignoreCase = true) }
        val word = text()
        swipeUp("q")
        assertSettled(word + "1")
        tap("w")
        rule.waitUntil(5000) { text().equals(word + "1w", ignoreCase = true) }
        assertEquals("", engine.getInput())
    }

    @Test fun japaneseUppercaseKatakanaSurvivesFollowingSwipeDigit() {
        chooseMode("japanese")
        rule.onNodeWithText("q", ignoreCase = false).assertExists()
        rule.onNodeWithTag("shift-key").performTouchInput { down(center); up() }
        "KATA".forEach { tap(it.toString()) }
        rule.waitUntil(10_000) { engine.getComposition().preedit.replace(" ", "") == "カタ" }
        swipeUp("Q")
        assertSettled("カタ1")
        tap("K"); tap("A")
        rule.waitUntil(5000) { engine.getComposition().preedit.replace(" ", "") == "カ" }
        assertFalse("上滑不能混入 q 编码", engine.getInput().contains("q", ignoreCase = true))
        engine.clearQueuedComposition()
    }

    @Test fun t9CandidateThenDigitThenImmediateNextKeyKeepsOnlyNewCode() {
        chooseMode("t9_pinyin")
        tap("ABC")
        rule.waitUntil(5000) { engine.getInput().isNotEmpty() && engine.getCandidates().isNotEmpty() }
        // Unified key contract: digits finish the displayed reading, not the first candidate.
        val previousReading = engine.getComposition().preedit.filterNot {
            it.isWhitespace() || it == '\'' || it == '|'
        }
        assertTrue("九键必须有已解码读音", previousReading.isNotEmpty())
        swipeUp("DEF")
        assertSettled(previousReading + "3")
        tap("GHI")
        rule.waitUntil(5000) { engine.getInput() == "4" }
        val secondReading = engine.getComposition().preedit.filterNot {
            it.isWhitespace() || it == '\'' || it == '|'
        }
        assertTrue("第二段九键必须有已解码读音", secondReading.isNotEmpty())
        // Send the next touch immediately, before waiting for Compose/service work to settle.
        val digitNode = rule.onNodeWithText("ABC")
        val digitBounds = digitNode.fetchSemanticsNode().boundsInRoot
        val digit = digitBounds.center - digitBounds.topLeft
        val next = rule.onNodeWithText("JKL").fetchSemanticsNode().boundsInRoot.center - digitBounds.topLeft
        val distance = swipeDistance
        digitNode.performTouchInput {
            down(digit)
            moveTo(digit - Offset(0f, distance), delayMillis = 120)
            up()
            advanceEventTime(1)
            down(next)
            up()
        }
        rule.waitUntil(5000) { engine.getInput() == "5" }
        android.os.SystemClock.sleep(350)
        rule.waitForIdle()
        assertEquals("延迟的清空不能删除新九键输入", "5", engine.getInput())
        assertEquals(previousReading + "3" + secondReading + "2", text())
        engine.clearQueuedComposition()
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
