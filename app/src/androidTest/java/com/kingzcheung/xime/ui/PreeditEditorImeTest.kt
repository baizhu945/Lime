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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.InputModes
import com.kingzcheung.xime.settings.SettingsPreferences
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real IME: live code edits retain the original keyboard and committed host text. */
class PreeditEditorImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val engine = RimeEngine.getInstance()
    private lateinit var editor: EditText
    private val swipeDistance get() = 80f * context.resources.displayMetrics.density
    private val imeComponent get() = ComponentName(context, "com.kingzcheung.xime.service.XimeInputMethodService")
    private val imeId get() = imeComponent.flattenToShortString()
    private val inputMethodManager get() = context.getSystemService(InputMethodManager::class.java)
    private var previousIme: String? = null
    private var previouslyEnabled = false
    private var previousSubtype = -1
    private var savedSystemImeState = false
    private var oldInputLocation = ""
    private var expandedCompactToolbar = false
    private var imeService: com.kingzcheung.xime.service.XimeInputMethodService? = null

    private fun findService(view: android.view.View): com.kingzcheung.xime.service.XimeInputMethodService? {
        var candidate: Context? = view.context
        while (candidate != null) {
            if (candidate is com.kingzcheung.xime.service.XimeInputMethodService) return candidate
            candidate = (candidate as? android.content.ContextWrapper)?.baseContext?.takeUnless { it === candidate }
        }
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findService(view.getChildAt(i))?.let { return it }
        return null
    }

    @Before fun startKeyboard(): Unit = runBlocking {
        oldInputLocation = SettingsPreferences.getInputTextLocation(context)
        SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR)
        previousIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        previousSubtype = Settings.Secure.getInt(context.contentResolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, -1)
        previouslyEnabled = inputMethodManager.enabledInputMethodList.any { it.id == imeId }
        savedSystemImeState = true
        // A service-owned ComposeView outlives a test's ComposeTestRule. Reusing it in the
        // next test would keep the previous rule's disposed recomposer and invisible test root.
        stopKeyboardService()
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        engine.initialize(user, shared)
        assertTrue(RimeConfigHelper.ensureDeployment(context))
        assertTrue(engine.ensureSession())
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
            imeService = android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findService)
            imeService != null && rule.onAllNodesWithTag("language-key-control", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val service = checkNotNull(imeService)
        if (service.uiState.value.isCompact) {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            expandedCompactToolbar = true
            rule.onNodeWithTag("hardware-toolbar-keyboard", useUnmergedTree = true).performClick()
            rule.waitUntil(10_000) { !service.uiState.value.isCompact }
        }
    }

    @After fun stopKeyboardAndRestoreSystemIme() {
        if (!savedSystemImeState) return
        try {
            try {
                // The preedit panel installs a BackHandler; close it via its real control
                // before Back is used to restore the hardware-keyboard compact toolbar.
                if (rule.onAllNodesWithTag("preedit-editor", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
                    rule.onNodeWithTag("preedit-editor-close", useUnmergedTree = true).performClick()
                    rule.waitUntil(10_000) { rule.onAllNodesWithTag("preedit-editor", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
                }
                if (expandedCompactToolbar && imeService?.uiState?.value?.isCompact == false) {
                    shell("input keyevent KEYCODE_BACK")
                    rule.waitUntil(10_000) { imeService?.uiState?.value?.isCompact == true }
                }
            } finally {
                if (::editor.isInitialized) {
                    rule.runOnUiThread {
                        inputMethodManager.hideSoftInputFromWindow(editor.windowToken, 0)
                        editor.clearFocus()
                    }
                }
                stopKeyboardService()
            }
        } finally {
            SettingsPreferences.setInputTextLocation(context, oldInputLocation)
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
        rule.waitUntil(5000) { text() == expected && engine.getInput().isEmpty() }
        android.os.SystemClock.sleep(350)
        rule.waitForIdle()
        assertEquals(expected, text())
        assertEquals("字面上屏后不能留旧编码", "", engine.getInput())
    }


    private fun openEditor() {
        rule.waitUntil(5000) { rule.onAllNodesWithTag("candidate-preedit").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("candidate-preedit").performTouchInput { click() }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("preedit-editor-code").fetchSemanticsNodes().isNotEmpty() }
        rule.runOnUiThread { assertTrue("点击预编辑浮层不能抢走宿主焦点", editor.hasFocus()) }
    }
    private fun draft(value: String, caret: Int = value.length) {
        rule.onNodeWithTag("preedit-editor-code").performSemanticsAction(SemanticsActions.SetText) { assertTrue(it(AnnotatedString(value))) }
        rule.onNodeWithTag("preedit-editor-code").performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(caret, caret, false)) }
    }
    private fun assertInputWhileEngineBusy(expected: String) {
        assertEquals("native editor input before the busy read", expected,
            engine.readPinyinEditSnapshot().firstOrNull())
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val holder = Thread {
            try {
                // Hold the real engine lock without applying an edit or replacing native state.
                engine.editPinyinAndReadState {
                    acquired.countDown()
                    check(release.await(5_000, TimeUnit.MILLISECONDS))
                    false
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        holder.start()
        try {
            assertTrue("real engine reader must own the lock", acquired.await(5_000, TimeUnit.MILLISECONDS))
            val observed = engine.getInput()
            println("PREEDIT_BUSY_INPUT expected=$expected observed=$observed")
            assertEquals("a busy read must retain the latest native edit", expected, observed)
        } finally {
            release.countDown()
            holder.join(5_000)
            assertFalse("engine lock reader must finish", holder.isAlive)
        }
        failure.get()?.let { throw it }
    }
    private fun applyDraft() {
        rule.onNodeWithTag("preedit-editor-close").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("preedit-editor").fetchSemanticsNodes().isEmpty() }
    }
    private fun selectExpandedCandidate(value: String) {
        val candidates = engine.getAllCandidates().toList()
        val index = candidates.indexOfFirst { it.text == value }
        assertNotNull("Rime 全量候选缺少 $value，input=${engine.getInput()} candidates=${candidates.take(30)}",
            index.takeIf { it >= 0 })
        // The candidate bar renders the first page, and the expanded page only carries the rest
        // (remainingCandidates), so a word on the bar is selected through its slot tag.
        val barSlot = (0 until 16).firstOrNull { slot ->
            rule.onAllNodes(hasTestTag("bar-candidate:$slot") and hasText(value, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        if (barSlot != null) {
            rule.onNodeWithTag("bar-candidate:$barSlot").performTouchInput { click() }
            return
        }
        // Expanded rows are lazy and carry the engine-global index; walk the row indexes so a
        // panel that opens scrolled does not hide the row (annotations are width-dependent).
        val tag = "expanded-candidate:$index"
        rule.onNodeWithTag("candidate-expansion").performTouchInput { click() }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("expanded-candidates").fetchSemanticsNodes().isNotEmpty() }
        val panel = rule.onNodeWithTag("expanded-candidates")
        try {
            val rows = panel.fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.CollectionInfo].rowCount
            // The panel dispatches its own scroll actions asynchronously, so settle after each step.
            for (row in 0 until maxOf(rows, 1)) {
                panel.performScrollToIndex(row)
                rule.waitForIdle()
                if (rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) break
            }
            rule.waitUntil(5000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(tag).performTouchInput { click() }
        } catch (failure: Throwable) {
            screenshot("candidate-$value-failure")
            val dir = File(context.getExternalFilesDir(null), "preedit-editor").apply { mkdirs() }
            File(dir, "candidate-$value-failure.txt").writeText(
                "expected=$value index=$index input=${engine.getInput()} candidates=$candidates\n" +
                    rule.onNodeWithTag("expanded-candidates").printToString() + "\n--- unmerged ---\n" +
                    rule.onNodeWithTag("expanded-candidates", useUnmergedTree = true).printToString())
            throw failure
        }
    }
    private fun setPrefix() = rule.runOnUiThread { editor.setText("前文"); editor.setSelection(editor.length()) }
    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "preedit-editor").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().also { image ->
            File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
    }

    @Test fun liveEditorStaysAboveCandidatesAndUsesOriginalKeysWithoutChangingHostText() {
        chooseMode("rime_ice"); setPrefix()
        "nihao".forEach { tap(it.toString()) }
        rule.waitUntil(5000) { engine.getInput() == "nihao" }
        // Editor restarts reapply this setting. Idempotent updates must retain composition.
        repeat(12) { engine.setPageSize("rime_ice", SettingsPreferences.getPageSize(context)) }
        assertEquals("nihao", engine.getInput())
        val qBefore = rule.onNodeWithText("q", ignoreCase = true).fetchSemanticsNode()
        val qPosition = qBefore.positionOnScreen
        val qSize = qBefore.size
        val preview = rule.onNodeWithTag("candidate-preedit").fetchSemanticsNode()
        val previewHeight = preview.size.height
        val snapshot = engine.readPinyinEditSnapshot()
        assertEquals("nihao", snapshot[0]); assertEquals("5", snapshot[1])
        assertEquals("nihao", snapshot[4].filter { it.isLetter() })
        val previewText = rule.onNodeWithTag("candidate-preedit-text", useUnmergedTree = true)
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        // The preview inserts U+200B after a separator as a line-break opportunity.
        assertEquals("nihao", previewText.filter { it.isLetter() })
        assertEquals("前文", text())
        rule.runOnUiThread { assertEquals(2, editor.selectionStart); assertEquals(2, editor.selectionEnd) }
        val previewLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithTag("candidate-preedit-text", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(previewLayouts) }
        screenshot("pinyin-preview-segmented")
        openEditor()
        val editorNode = rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode()
        val editorCode = editorNode.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        assertEquals("nihao", editorCode.filter { it.isLetter() })
        assertEquals(editorCode.length, editorNode.config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange].start)
        val editLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithTag("preedit-editor-code").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(editLayouts) }
        assertEquals(previewLayouts.single().layoutInput.style.fontSize, editLayouts.single().layoutInput.style.fontSize)
        // The editor intentionally dims the same text colour slightly; its RGB must still match the preview.
        val previewColor = previewLayouts.single().layoutInput.style.color
        val editorColor = editLayouts.single().layoutInput.style.color
        assertEquals(previewColor.red, editorColor.red)
        assertEquals(previewColor.green, editorColor.green)
        assertEquals(previewColor.blue, editorColor.blue)
        assertEquals(0.9f, editorColor.alpha, 0.01f)
        screenshot("pinyin-editor-segmented")
        val strip = rule.onNodeWithTag("preedit-editor").captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "preedit-editor/pinyin-editor-surface.png").outputStream().use {
            strip.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        rule.onNodeWithText("ni'hao", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("candidate-preedit").assertDoesNotExist()
        val bar = rule.onNodeWithTag("preedit-editor").fetchSemanticsNode()
        val candidate = rule.onNodeWithTag("candidate-expansion").fetchSemanticsNode()
        assertTrue("editor must be above the candidate row", bar.positionOnScreen.y + bar.size.height <= candidate.positionOnScreen.y + 2)
        val qAfter = rule.onNodeWithText("q", ignoreCase = true).fetchSemanticsNode()
        assertEquals(qPosition, qAfter.positionOnScreen); assertEquals(qSize, qAfter.size)
        rule.onNodeWithTag("preedit-apply").assertDoesNotExist()
        // The popup row and the candidate strip round their text boxes at slightly different dp;
        // keep the same compact height without requiring pixel-identical subpixel rounding.
        assertEquals(previewHeight.toFloat(),
            rule.onNodeWithTag("preedit-editor-surface").fetchSemanticsNode().size.height.toFloat(), 2f)
        // Both sides of a separator remain distinct cursor positions, and the normal
        // delete key removes the separator itself without deleting the preceding letter.
        for (caret in listOf(2, 3, 2, 3)) {
            rule.onNodeWithTag("preedit-editor-code").performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(caret, caret, false)) }
            rule.onNodeWithText("ni'hao", useUnmergedTree = true).assertExists()
        }
        rule.waitUntil(5000) {
            rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange].start == 3
        }
        // getInput() deliberately returns "" when its non-blocking engine lock is busy.
        // Read the locked editor snapshot so pending caret jobs cannot look like lost code.
        assertEquals("opening and moving must not rewrite code", "nihao", engine.readPinyinEditSnapshot().firstOrNull())
        rule.onNodeWithContentDescription("删除", true).performTouchInput { click() }
        rule.waitUntil(5000) { rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "nihao" }
        rule.onNodeWithTag("preedit-editor-code").assertTextEquals("nihao")
        assertEquals("nihao", engine.getInput())
        draft("nihao", 2)
        tap("m")
        rule.waitUntil(5000) { engine.getInput() == "nimhao" }
        assertEquals("前文", text())
        screenshot("pinyin-editor-above-original-keyboard")
        applyDraft()
        assertEquals("nimhao", engine.getInput())
        openEditor(); draft("womf")
        rule.waitUntil(5000) { engine.getInput() == "womf" }
        assertInputWhileEngineBusy("womf")
        shell("input keyevent 4")
        rule.waitUntil(5000) { rule.onAllNodesWithTag("preedit-editor").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("language-key-control", useUnmergedTree = true).assertExists()
        assertEquals("womf", engine.getInput()); assertEquals("前文", text())
        rule.runOnUiThread { assertTrue(editor.hasFocus()) }
        engine.clearQueuedComposition()
        assertInputWhileEngineBusy("")
    }

    @Test fun spaceDragEditsPinyinAndDeletingAllClosesEditorWithoutMovingHostCaret() {
        chooseMode("rime_ice"); setPrefix()
        "nihao".forEach { tap(it.toString()) }
        rule.waitUntil(5000) { engine.getInput() == "nihao" }
        // Start in ordinary composition: requiring a prior tap on preedit hid the bug.
        rule.onNodeWithTag("preedit-editor").assertDoesNotExist()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space-key", true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("space-key", true).performTouchInput { moveTo(center - Offset(50f, 0f)); up() }
        rule.mainClock.autoAdvance = true
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot()[1].toInt() < 5 }
        rule.onNodeWithTag("preedit-editor").assertHeightIsEqualTo(44.dp)
        assertEquals("nihao", engine.getInput())
        assertEquals("前文", text())
        rule.runOnUiThread { assertEquals(2, editor.selectionStart) }
        draft("a")
        rule.waitUntil(5000) { engine.getInput() == "a" }
        rule.onNodeWithContentDescription("删除", true).performTouchInput { click() }
        rule.waitUntil(5000) { engine.getInput().isEmpty() && rule.onAllNodesWithTag("preedit-editor").fetchSemanticsNodes().isEmpty() }
        assertEquals("前文", text())
    }
    @Test fun spaceDragFromInputBoxCompositionKeepsRawInputAndNeverFinalizesTheHost() {
        SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
        chooseMode("rime_ice"); setPrefix()
        "nihao".forEach { tap(it.toString()) }
        rule.waitUntil(5000) { engine.getInput() == "nihao" && text().startsWith("前文") && text().length > 2 }
        val before = text()
        var beforeSelection = -1
        rule.runOnUiThread { beforeSelection = editor.selectionStart }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("space-key", true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("space-key", true).performTouchInput { moveTo(center - Offset(50f, 0f)); up() }
        rule.mainClock.autoAdvance = true
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot()[1].toInt() < 5 }
        assertEquals("nihao", engine.getInput())
        assertEquals(before, text())
        rule.runOnUiThread { assertEquals(beforeSelection, editor.selectionStart) }
        assertEquals("Caret movement never commits", "", engine.commit())
    }
    @Test fun punctuationCommitsImmediatelyAndNeverLeavesCandidates() {
        chooseMode("rime_ice"); setPrefix()
        "nihao".forEach { tap(it.toString()) }
        rule.waitUntil(5000) { engine.getInput() == "nihao" && engine.getCandidates().contains("你好") }
        // Literal punctuation commits the typed code, not the first Chinese candidate.
        tap("，")
        assertSettled("前文nihao，")
        assertTrue("标点上屏后 Rime 候选必须清空", engine.getCandidates().isEmpty())
        rule.onNodeWithTag("candidate-expansion").assertDoesNotExist()
        rule.onNodeWithTag("candidate-preedit").assertDoesNotExist()
        // A fresh composition still offers Chinese candidates; explicitly select one.
        "nihao".forEach { tap(it.toString()) }
        rule.waitUntil(5000) { engine.getInput() == "nihao" && engine.getCandidates().contains("你好") }
        // The candidate text can include a user-configured number prefix (e.g. "1 你好").
        val firstCandidate = hasTestTag("bar-candidate:0") and hasText("你好", substring = true)
        rule.waitUntil(5000) { rule.onAllNodes(firstCandidate).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(firstCandidate).assertIsDisplayed().performClick()
        assertSettled("前文nihao，你好")
    }

    @Test fun mergedAndDoublePinyinEditsReturnToTheirOwnLayout() {
        chooseMode("pinyin_14jian"); setPrefix()
        listOf("bn", "ui", "gh", "as", "op").forEach(::tap)
        // Merged-key taps deliver uppercase representative codes; lowercase means exact letters.
        rule.waitUntil(5000) { engine.getInput() == "BUGAO" }
        openEditor(); draft("nihao"); applyDraft()
        // The editor buffer holds plain letters, so the rewritten code is lowercase.
        rule.waitUntil(5000) { engine.getInput() == "nihao" }
        assertEquals("nihao", engine.getInput()); assertEquals("前文", text())
        rule.onNodeWithText("bn", ignoreCase = true).assertExists()
        selectExpandedCandidate("你好")
        rule.waitUntil(5000) { text() == "前文你好" }
        // The committed word leaves association candidates in the bar, which replaces the
        // idle toolbar; dismiss them through the real control so the layout stays reachable.
        if (rule.onAllNodesWithContentDescription("取消输入").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithContentDescription("取消输入").performClick()
            rule.waitUntil(5000) { rule.onAllNodesWithTag("bar-association:0").fetchSemanticsNodes().isEmpty() }
        }
        chooseMode("double_pinyin_flypy")
        "nihk".forEach { tap(it.toString()) }
        rule.waitUntil(5000) { engine.getInput() == "nihk" }
        openEditor(); draft("womf"); applyDraft()
        assertEquals("double_pinyin_flypy", engine.getCurrentSchema())
        rule.waitUntil(5000) { engine.getInput() == "womf" }
        assertEquals("womf", engine.getInput()); assertEquals("前文你好", text())
        engine.clearQueuedComposition()
    }

    @Test fun mergedReadingAndMultiTapEditorKeepLettersAndCaretStable() {
        chooseMode("pinyin_14jian"); setPrefix()
        listOf("qw", "er", "bn").forEach(::tap)
        // Merged-key taps use uppercase representative codes; lowercase denotes exact letters.
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot().firstOrNull() == "QEB" }
        assertEquals("wen", engine.readPinyinEditSnapshot()[4])
        assertTrue(engine.getCandidates().any { it in listOf("问", "文", "温") })
        rule.onNode(hasText("wen") and hasAnyAncestor(hasTestTag("candidate-preedit")), useUnmergedTree = true).assertExists()
        screenshot("merged-reading-wen")
        openEditor()
        rule.onNodeWithTag("preedit-editor-code").assertTextEquals("wen")
        draft("we")
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot().firstOrNull() == "we" }
        tap("bn"); tap("bn")
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot().firstOrNull() == "wen" }
        rule.onNodeWithTag("preedit-editor-code").assertTextEquals("wen")
        draft("ni'hao", 3)
        for (caret in listOf(2, 3, 2, 3)) {
            rule.onNodeWithTag("preedit-editor-code").performSemanticsAction(SemanticsActions.SetSelection) { it(caret, caret, false) }
            rule.onNodeWithTag("preedit-editor-code").assertTextEquals("ni'hao")
        }
        rule.onNodeWithContentDescription("删除", true).performTouchInput { click() }
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot().firstOrNull() == "nihao" }
        rule.onNodeWithTag("preedit-editor-code").assertTextEquals("nihao")
        rule.onNodeWithContentDescription("删除", true).performTouchInput { click() }
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot().firstOrNull() == "nhao" }
        assertEquals("前文", text())
        engine.clearQueuedComposition()
    }

    @Test fun t9EditsRebuildTheBufferAndKeepPreviouslySelectedText() {
        chooseMode("t9_pinyin"); setPrefix()
        listOf("MNO", "GHI", "GHI", "ABC", "MNO").forEach(::tap)
        rule.waitUntil(5000) { engine.getInput().isNotEmpty() && engine.getCandidates().contains("你好") }
        val previewText = rule.onNodeWithTag("candidate-preedit-text", useUnmergedTree = true)
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        assertEquals("ni'hao", previewText.replace("\u200B", ""))
        openEditor(); draft("ni'hao", 3)
        rule.onNodeWithTag("t9-delete-key").performTouchInput { click() }
        rule.waitUntil(5000) { rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "nihao" }
        assertEquals("64426", engine.readPinyinEditSnapshot()[0])
        rule.onNodeWithTag("t9-delete-key").performTouchInput { click() }
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot()[0] == "6426" }
        rule.waitUntil(5000) {
            val code = rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode().config
            val reading = engine.readPinyinEditSnapshot()[4].replace(' ', '\'')
            code[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == reading &&
                code[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange].start == 1
        }
        assertEquals("6426", engine.t9GetRemainingDigits())
        assertEquals("前文", text())
        draft("ni'hao", 3)
        // 九键只把原始数字码交给预编辑：多击字母分支只服务 14 键，T9 的 MNO 直接送出数字 6，
        // 在 caret 3（码位 2）处插入后，原码 64426 变成 646426。
        tap("MNO")
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot().getOrNull(0) == "646426" }
        rule.waitUntil(5000) {
            val code = rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode().config
            code[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text ==
                engine.readPinyinEditSnapshot()[4].replace(' ', '\'') &&
                code[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange].start == 3
        }
        rule.waitForIdle()
        val shown = rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        assertFalse("T9 编辑器只显示引擎读音，不显示原始数字码: $shown", shown.any { it.isDigit() })
        assertEquals("插入原始数字码不能改动宿主文本", "前文", text())
        // 原九键删除键撤掉刚插入的码，回到原来的 64426、读音与 caret 3。
        rule.onNodeWithTag("t9-delete-key").performTouchInput { down(center); up() }
        rule.waitUntil(5000) { engine.readPinyinEditSnapshot().getOrNull(0) == "64426" }
        try {
            rule.waitUntil(5000) {
                val code = rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode().config
                code[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text ==
                    engine.readPinyinEditSnapshot()[4].replace(' ', '\'') &&
                    code[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange].start == 3
            }
        } catch (failure: Throwable) {
            val code = rule.onNodeWithTag("preedit-editor-code").fetchSemanticsNode().config
            throw AssertionError("恢复失败 snapshot=${engine.readPinyinEditSnapshot().joinToString()} " +
                "editor=${code[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text} " +
                "caret=${code[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]} " +
                "remaining=${engine.t9GetRemainingDigits()}", failure)
        }
        rule.onNodeWithTag("preedit-editor-code").assertTextEquals("ni'hao")
        assertEquals("前文", text())
        rule.waitForIdle()
        screenshot("t9-live-editor-original-keys")
        applyDraft()
        // 关闭浮层不会提交：恢复后的歧义码仍留在组合里，宿主不被改动。
        rule.waitUntil(5000) { engine.getInput() == "64426" }
        assertEquals("前文", text())
        // Select only the first word, leaving a protected nine-key partial commit.
        selectExpandedCandidate("你")
        rule.waitUntil(5000) { engine.getInput().isNotEmpty() && engine.getCandidates().isNotEmpty() }
        openEditor()
        // Actually edit the suffix. Merely reopening and assigning the same code is
        // a caret-only operation and must preserve the original T9 undo history.
        draft("ha")
        rule.waitUntil(5000) { engine.getInput() == "ha" }
        draft("hao"); applyDraft()
        assertEquals("前文", text())
        screenshot("t9-edited-partial")
        tap("MNO")
        rule.waitUntil(5000) { engine.getInput().contains("hao") && engine.getInput().endsWith("6") }
        // Delete the new key through the original nine-key control.
        rule.onNodeWithTag("t9-delete-key").performTouchInput { down(center); up() }
        try {
            rule.waitUntil(5000) { engine.getInput().contains("hao") && !engine.getInput().endsWith("6") }
        } catch (failure: Throwable) {
            screenshot("t9-partial-delete-failed")
            throw AssertionError("input=${engine.getInput()} remaining=${engine.t9GetRemainingDigits()} panel=${engine.t9GetLeftPanelState()} candidates=${engine.getCandidates().take(8)}", failure)
        }
        selectExpandedCandidate("好")
        rule.waitUntil(5000) { text() == "前文你好" }
        assertEquals("", engine.getInput())
    }

    @Test fun japanesePreeditHasNoChineseEditingEntry() {
        chooseMode("japanese")
        "kana".forEach { tap(it.toString()) }
        rule.waitUntil(5000) { engine.getInput().isNotEmpty() }
        // Its display can remain visible, but the Chinese editor action must be absent.
        rule.onAllNodesWithTag("candidate-preedit").fetchSemanticsNodes().forEach { node ->
            assertFalse(node.config.contains(SemanticsActions.OnClick))
        }
        rule.onNodeWithTag("preedit-editor").assertDoesNotExist()
        engine.clearQueuedComposition()
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
