package com.kingzcheung.xime.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.HardwareKeyboardPreferences
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.LanguageSwitchPreferences
import com.kingzcheung.xime.settings.LanguageSwitchOptions
import com.kingzcheung.xime.settings.LanguageSwitchMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises actual hardware-key events and the real IME in a disposable editor, never a user document. */
class HardwareEnglishCandidateImeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun languageStatusIdleWidthEnglishAcceptanceAndChinesePreeditUseRealInput() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("A physical keyboard is required", context.resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS)
        val prefs = SettingsPreferences.getPrefsPublic(context)
        fun fixtureKey(key: String) = key.startsWith(HardwareKeyboardPreferences.PREFIX) ||
            key.startsWith("current_schema") || key.startsWith("last_input_mode_") ||
            key.startsWith("selected_input_profile_") || key == LanguagePreferences.KEY || key == LanguageSwitchPreferences.MODE_KEY || key == LanguageSwitchPreferences.LANGUAGE_KEY || key == "input_language_order"
        val savedPreferences = prefs.all.filterKeys(::fixtureKey).mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val engine = RimeEngine.getInstance()
        assertTrue("The real input engine must be ready", RimeConfigHelper.prepareEngine(context))
        val savedSchema = engine.getCurrentSchema()
        val savedOptions = listOf("ascii_mode", "full_shape", "ascii_punct").associateWith(engine::getOption)
        val savedPersistentAscii = engine.getUserConfigBool("var/option/ascii_mode")
        val savedPreviousSchema = engine.getUserConfigString("var/previously_selected_schema")
        lateinit var editor: EditText
        var editorReady = false
        fun has(tag: String) = rule.onAllNodesWithTag(tag, true).fetchSemanticsNodes().isNotEmpty()
        fun document(): String {
            var result = ""
            rule.runOnUiThread { result = editor.text.toString() }
            return result
        }
        fun languageIs(label: String): Boolean = rule.onAllNodesWithTag("hardware-toolbar-language", true)
            .fetchSemanticsNodes().any { node ->
                val descriptions = if (SemanticsProperties.ContentDescription in node.config)
                    node.config[SemanticsProperties.ContentDescription] else emptyList()
                descriptions.any { it.startsWith("当前语言：$label，") }
            }
        fun screenshot(name: String) {
            instrumentation.uiAutomation.takeScreenshot()?.also { bitmap ->
                File(context.getExternalFilesDir(null), name).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        try {
            // Default switching is preserved; Japanese is exercised through the real menu later.
            prefs.edit().putStringSet(LanguagePreferences.KEY, setOf(InputLanguage.CHINESE.id, InputLanguage.ENGLISH.id, InputLanguage.JAPANESE.id))
                .putString("selected_input_profile_${InputLanguage.CHINESE.id}", "rime_ice")
                .remove(LanguageSwitchPreferences.MODE_KEY)
                .remove(LanguageSwitchPreferences.LANGUAGE_KEY)
                .putBoolean(HardwareKeyboardPreferences.PREFIX + "ctrl_space", true)
                .putBoolean(HardwareKeyboardPreferences.PREFIX + "dock_edge", true).commit()
            assertTrue("Pinyin schema must be available", engine.switchSchema("rime_ice"))
            engine.clearComposition()
            engine.setOption("ascii_mode", false)
            engine.setUserConfigBool("var/option/ascii_mode", false)
            SettingsPreferences.setCurrentSchema(context, "rime_ice")
            val ime = "${context.packageName}/com.kingzcheung.xime.service.XimeInputMethodService"
            shell("ime set $ime")
            rule.setContent {
                AndroidView(factory = { ctx ->
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(80, 160, 80, 0)
                        editor = EditText(ctx).apply {
                            hint = "Hardware English regression — temporary editor"
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            minLines = 2
                        }
                        editorReady = true
                        addView(editor, LinearLayout.LayoutParams(-1, 220))
                    }
                }, modifier = Modifier.fillMaxSize())
            }
            rule.runOnUiThread {
                editor.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                editor.post {
                    imm.restartInput(editor)
                    imm.showSoftInput(editor, InputMethodManager.SHOW_FORCED)
                }
            }
            rule.waitUntil(30_000) { has("hardware-keyboard-toolbar") && languageIs("中") }
            // Always test the same docking configuration; inspect the actual dock candidate container.
            val dockAction = rule.onNodeWithTag("hardware-toolbar-drag", true).fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first { it.label == "停靠底部" }
            rule.runOnIdle { assertTrue(dockAction.action()) }
            rule.waitForIdle()
            val density = context.resources.displayMetrics.density
            fun toolbarWidth() = rule.onNodeWithTag("hardware-keyboard-toolbar", true).fetchSemanticsNode().boundsInRoot.width
            // Docked keyboards reserve the candidate strip even while idle, so the
            // capsule keeps one stable width and never jumps when words appear.
            rule.waitUntil(10_000) { has("hardware-docked-candidates") && !has("bar-candidate:0") }
            assertTrue("Idle docked strip must remain wide", toolbarWidth() > 300f * density)

            // Tap must use the same immediate language toggle as the screen keyboard.
            rule.onNodeWithTag("hardware-toolbar-language", true).performTouchInput { click() }
            rule.waitUntil(10_000) { engine.isAsciiMode() && languageIs("EN") }
            assertFalse("A tap must switch language without opening the list", has("language-menu"))
            rule.onNodeWithText("EN", useUnmergedTree = true).assertIsDisplayed()
            rule.waitUntil(10_000) { has("hardware-docked-candidates") && !has("bar-candidate:0") }
            assertTrue("Idle English strip must reserve its width", toolbarWidth() > 300f * density)
            assertFalse("An empty English toolbar must not show a candidate-next action", has("hardware-candidate-next"))
            screenshot("hardware-english-idle-ime.png")

            // Hold opens the real menu; releasing over the original key cancels selection.
            val languageKey = rule.onNodeWithTag("hardware-toolbar-language", true)
            languageKey.performTouchInput { down(center) }
            try {
                rule.waitUntil(5_000) { has("language-menu") }
                rule.onNodeWithContentDescription("选择中文").assertIsDisplayed()
                rule.onNodeWithContentDescription("选择英文").assertIsDisplayed()
                assertTrue("Opening the list must not switch the current language", languageIs("EN"))
                screenshot("hardware-language-menu-ime.png")
            } finally {
                languageKey.performTouchInput { up() }
            }
            // Releasing without moving keeps the menu open for tap selection by design.
            if (has("language-menu")) {
                rule.onNodeWithContentDescription("选择英文").performClick()
            }
            rule.waitUntil(5_000) { !has("language-menu") }
            assertTrue(languageIs("EN"))

            shell("input keyboard keyevent KEYCODE_H KEYCODE_E KEYCODE_L")
            rule.waitUntil(10_000) { document() == "hel" }
            rule.waitUntil(15_000) {
                rule.onAllNodes(hasTestTag("bar-candidate:0") and hasText("hel")).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("bar-candidate:0", true).assertIsDisplayed()
            assertTrue("English input must retain its language label", languageIs("EN"))
            assertTrue("Actual English words must appear in the dock", has("hardware-docked-candidates"))
            assertFalse("English direct input has no pinyin preview", has("hardware-preedit-card"))
            fun visibleCompletion(): String? {
                val node = rule.onAllNodesWithTag("bar-candidate:1").fetchSemanticsNodes().firstOrNull()
                    ?: return null
                if (SemanticsProperties.Text !in node.config) return null
                return node.config[SemanticsProperties.Text].joinToString("") { it.text }
                    .takeIf { it.startsWith("hel", ignoreCase = true) && it.length > 3 }
            }
            // Require a real completion in addition to the already typed word. Do not initialize
            // the dictionary in this test: production must load it correctly on its own.
            rule.waitUntil(15_000) { visibleCompletion() != null }
            val completion = requireNotNull(visibleCompletion())
            val inBundledDictionary = context.assets.open("english.txt").bufferedReader().useLines { words ->
                words.any { it.trim().equals(completion, ignoreCase = true) }
            }
            assertTrue("Completion must come from the bundled English dictionary: $completion", inBundledDictionary)
            screenshot("hardware-english-candidates-ime.png")
            rule.onNodeWithTag("bar-candidate:0", true).performClick()
            rule.waitUntil(10_000) { !has("bar-candidate:0") && !has("hardware-candidate-next") }
            assertEquals("Confirming the already typed word must not insert it twice", "hel", document())

            // Replacing the second word must preserve the previously accepted word.
            shell("input keyboard keyevent KEYCODE_SPACE KEYCODE_H KEYCODE_E KEYCODE_L")
            rule.waitUntil(10_000) { document() == "hel hel" }
            rule.waitUntil(15_000) { visibleCompletion() != null }
            val selectedCompletion = requireNotNull(visibleCompletion())
            rule.onNodeWithTag("bar-candidate:1", true).performClick()
            rule.waitUntil(10_000) { document() == "hel $selectedCompletion" && !has("bar-candidate:0") }
            assertEquals("Selecting a completion replaces only the pending prefix", "hel $selectedCompletion", document())
            screenshot("hardware-english-completion-accepted-ime.png")

            // With Chinese/English/Japanese enabled the tap cycle is EN -> 日 -> 中.
            var switchTaps = 0
            while (switchTaps < 3 && !(!engine.isAsciiMode() && languageIs("中"))) {
                rule.onNodeWithTag("hardware-toolbar-language", true).performTouchInput { click() }
                rule.waitForIdle()
                android.os.SystemClock.sleep(250)
                switchTaps++
            }
            rule.waitUntil(10_000) { !engine.isAsciiMode() && languageIs("中") }
            assertFalse("Switching back by tap must not open the language list", has("language-menu"))
            rule.runOnUiThread {
                editor.setText("")
                editor.setSelection(0)
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(editor)
            }
            rule.waitUntil(10_000) { has("hardware-keyboard-toolbar") && languageIs("中") }
            shell("input keyboard keyevent KEYCODE_J KEYCODE_I KEYCODE_N KEYCODE_G KEYCODE_J KEYCODE_I KEYCODE_U")
            rule.waitUntil(15_000) { engine.getInput() == "jingjiu" && has("hardware-preedit-card") && has("hardware-docked-candidates") }
            rule.onNodeWithTag("candidate-preedit-text", true).assertIsDisplayed()
            val previews = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onNodeWithTag("candidate-preedit-text", true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(previews) }
            val preedit = previews.single()
            assertTrue(preedit.layoutInput.text.text.contains("jiu"))
            assertEquals(preedit.layoutInput.text.length, preedit.getLineEnd(0))
            assertFalse(preedit.isLineEllipsized(0))
            val range = rule.onNodeWithTag("candidate-preedit-text", true).fetchSemanticsNode()
                .config[SemanticsProperties.HorizontalScrollAxisRange]
            assertEquals("Short pinyin must fit without hidden text", 0f, range.maxValue(), 0f)
            rule.runOnUiThread {
                assertTrue("Chinese text must remain uncommitted, including input-box preview mode",
                    editor.text.isEmpty() || android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
            }
            screenshot("hardware-chinese-preedit-ime.png")

            // Long-press selection must not change default native/English behavior. Missing
            // Japanese resources fail visibly; they must not be reported as passing validation.
            shell("input keyboard keyevent KEYCODE_ENTER")
            rule.waitUntil(10_000) { engine.getInput().isEmpty() && !has("hardware-preedit-card") }
            // This segment validates the current-language/English pair, not the cycle.
            LanguageSwitchPreferences.save(context, LanguageSwitchOptions(LanguageSwitchMode.CURRENT_ENGLISH))
            val pairKey = rule.onNodeWithTag("hardware-toolbar-language", true)
            pairKey.performTouchInput { down(center) }
            var released = false
            try {
                rule.waitUntil(5_000) { has("language-menu") }
                val japaneseChoice = rule.onAllNodesWithContentDescription("选择日语").fetchSemanticsNodes()
                assertTrue("Japanese backend/menu entry is required to validate the Chinese/Japanese pair", japaneseChoice.isNotEmpty())
                val target = rule.onNodeWithContentDescription("选择日语").performScrollTo().fetchSemanticsNode()
                val key = pairKey.fetchSemanticsNode()
                pairKey.performTouchInput {
                    moveTo(target.positionOnScreen + Offset(target.size.width / 2f, target.size.height / 2f) - key.positionOnScreen)
                    up()
                }
                released = true
            } finally {
                if (!released) pairKey.performTouchInput { up() }
            }
            rule.waitUntil(15_000) { !engine.isAsciiMode() && languageIs("あ") }
            val japaneseSchema = engine.getCurrentSchema()
            assertEquals(InputLanguage.JAPANESE, com.kingzcheung.xime.settings.InputModes.languageOf(japaneseSchema))
            rule.onNodeWithTag("hardware-toolbar-language", true).performTouchInput { click() }
            rule.waitUntil(10_000) { engine.isAsciiMode() && languageIs("EN") }
            rule.onNodeWithTag("hardware-toolbar-language", true).performTouchInput { click() }
            rule.waitUntil(10_000) { !engine.isAsciiMode() && languageIs("あ") && engine.getCurrentSchema() == japaneseSchema }
            assertEquals(LanguageSwitchMode.CURRENT_ENGLISH, LanguageSwitchPreferences.read(context).mode)

            // Opting in to a cycle honors the user's configured language order.
            prefs.edit().putString("input_language_order", "ja\nzh\nen").commit()
            LanguageSwitchPreferences.save(context, LanguageSwitchOptions(LanguageSwitchMode.CYCLE))
            rule.onNodeWithTag("hardware-toolbar-language", true).performTouchInput { click() }
            rule.waitUntil(10_000) { !engine.isAsciiMode() && languageIs("中") && engine.getCurrentSchema() == "rime_ice" }
            shell("input keyboard keycombination KEYCODE_CTRL_LEFT KEYCODE_SPACE")
            rule.waitUntil(10_000) { engine.isAsciiMode() && languageIs("EN") }
            rule.onNodeWithTag("hardware-toolbar-language", true).performTouchInput { click() }
            rule.waitUntil(10_000) { !engine.isAsciiMode() && languageIs("あ") && engine.getCurrentSchema() == japaneseSchema }
            screenshot("hardware-language-cycle-ime.png")

            // An explicit Chinese/English pair first leaves Japanese for the chosen language.
            LanguageSwitchPreferences.save(context, LanguageSwitchOptions(LanguageSwitchMode.SPECIFIC_ENGLISH, InputLanguage.CHINESE))
            rule.onNodeWithTag("hardware-toolbar-language", true).performTouchInput { click() }
            rule.waitUntil(10_000) { !engine.isAsciiMode() && languageIs("中") && engine.getCurrentSchema() == "rime_ice" }
            rule.onNodeWithTag("hardware-toolbar-language", true).performTouchInput { click() }
            rule.waitUntil(10_000) { engine.isAsciiMode() && languageIs("EN") }
            rule.onNodeWithTag("hardware-toolbar-language", true).performTouchInput { click() }
            rule.waitUntil(10_000) { !engine.isAsciiMode() && languageIs("中") && engine.getCurrentSchema() == "rime_ice" }
        } finally {
            if (editorReady) {
                rule.runOnUiThread {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(editor.windowToken, 0)
                    editor.clearFocus()
                }
            }
            engine.clearComposition()
            if (savedSchema.isNotBlank()) engine.switchSchema(savedSchema)
            savedOptions.forEach { (key, value) -> engine.setOption(key, value) }
            engine.setUserConfigBool("var/option/ascii_mode", savedPersistentAscii)
            if (savedPreviousSchema != null) engine.setUserConfigString("var/previously_selected_schema", savedPreviousSchema)
            val edit = prefs.edit()
            (prefs.all.keys.filter(::fixtureKey) + savedPreferences.keys).distinct().forEach { key ->
                restore(edit, key, savedPreferences[key])
            }
            edit.commit()
            if (!oldIme.isNullOrBlank() && oldIme != "null") shell("ime set $oldIme")
        }
    }

    private fun restore(edit: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> edit.remove(key)
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
