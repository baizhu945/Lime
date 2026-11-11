package com.kingzcheung.xime.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.CustomLayoutManager
import com.kingzcheung.xime.settings.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QwertySymbolEditorTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fixedLayoutEditsSymbolsAcrossSchemesWithoutCreatingLayout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previous = QwertySwipeSymbols.load(context, false)
        val english = QwertySwipeSymbols.load(context, true)
        val layouts = CustomKeyboardLayouts.load(context)
        val schema = SettingsPreferences.getCurrentSchema(context)
        try {
            rule.setContent { MaterialTheme { CustomLayoutManager("default", true) {} } }
            rule.onNodeWithText("编辑符号").performClick()
            rule.waitUntil(10_000) {
                rule.onAllNodes(hasTestTag("symbol-editor-up") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            saveUiEvidence("qwerty-symbol-editor", rule.onRoot())
            rule.onNodeWithTag("symbol-editor-up").performScrollTo().performTextReplacement("★")
            rule.onNodeWithTag("symbol-editor-up").assertTextContains("★")
            // Text replacement focuses the field. Finish the real IME dismissal before
            // scrolling/clicking, so its asynchronous insets cannot move the save target.
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.onNodeWithText("保存符号").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("已保存，键盘上立即生效").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(QwertySwipeSymbols.load(context, false).toString(), "★",
                QwertySwipeSymbols.load(context, false)["up.q"])
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithText("编辑符号").assertExists()
            rule.onNodeWithText("编辑符号").performClick()
            rule.waitUntil(10_000) {
                rule.onAllNodes(hasTestTag("symbol-editor-up") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("symbol-editor-up").assertTextContains("★")
            saveUiEvidence("qwerty-symbol-editor-saved", rule.onRoot())
            assertEquals(layouts, CustomKeyboardLayouts.load(context))
            assertEquals(schema, SettingsPreferences.getCurrentSchema(context))
            assertEquals(english, QwertySwipeSymbols.load(context, true))
            for (id in listOf("rime_ice", "double_pinyin_flypy")) {
                KeysConfigHelper.setActiveKeyboardSchema(id)
                assertEquals("★", KeysConfigHelper.getSwipeUpCommitValue("q", false))
                assertEquals("q", KeysConfigHelper.getKeyCommitValue("q", false))
            }
        } catch (failure: Throwable) {
            saveUiEvidence("qwerty-symbol-editor-failure", rule.onRoot())
            android.util.Log.e("SymbolEditorTest", "symbol editor failed; test value persisted=" +
                (QwertySwipeSymbols.load(context, false)["up.q"] == "★"))
            throw failure
        } finally {
            QwertySwipeSymbols.save(context, false, previous)
            KeysConfigHelper.setActiveKeyboardSchema(schema)
        }
    }
}
