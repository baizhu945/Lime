package com.kingzcheung.xime.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.*
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.menubar.SchemaListView
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReportedLayoutsTest {
    @get:Rule val rule = createComposeRule()
    private fun save(tag: String, name: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
        file.outputStream().use { rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun handwritingKeysShareCompactHeightAndLeaveMoreCanvasAtBothSizes() {
        val height = mutableStateOf(300.dp)
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
            HandwritingKeyboardLayout(bottomPaddingDp = 0, modifier = Modifier.size(800.dp, height.value).background(Color(0xFF191D25)),
                keyTextColor = Color.White, keyBackgroundColor = Color(0xFF303540), specialKeyBackgroundColor = Color(0xFF475E83))
        } } }
        for (h in listOf(300.dp, 180.dp)) {
            rule.runOnIdle { height.value = h }
            val keys = listOf("delete", "，", "。", "enter", "space", "number", "symbol", "ime_switch")
                .map { rule.onNodeWithTag("handwriting-key:$it").fetchSemanticsNode().boundsInRoot }
            val canvas = rule.onNodeWithTag("handwriting-canvas").fetchSemanticsNode().boundsInRoot
            keys.forEach { assertEquals((h.value - 8f) / 5f, it.height, 1f); assertFalse(it.overlaps(canvas)) }
            save("handwriting-panel", "handwriting-compact-${h.value.toInt()}.png")
            for (label in listOf("!@#", "123", "，", "。")) {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                rule.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("$label at $h must fit: ${layouts.map { "size=${it.size},paragraph=${it.multiParagraph.width}x${it.multiParagraph.height},font=${it.layoutInput.style.fontSize},line=${it.layoutInput.style.lineHeight}" }}", layouts.all { !it.didOverflowHeight && (0 until it.lineCount).all { line -> it.getLineRight(line) - it.getLineLeft(line) <= it.size.width + 1f } })
            }
            assertEquals((h.value - 8f) * 4f / 5f, canvas.height, 1f)
            assertEquals(keys[3].bottom, keys[4].bottom, 1f)
            save("handwriting-panel", "handwriting-compact-${h.value.toInt()}.png")
        }
    }
    @Test fun zeroIsAnIndependentLiteralKeyAlignedWithTheOtherRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        KeysConfigHelper.loadConfig(context)
        val committed = mutableListOf<String>()
        val composition = mutableListOf<String>()
        rule.setContent { MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
            T9KeyboardLayout({ composition += it },
                KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}, onCommitText = { committed += it }),
                com.kingzcheung.xime.viewmodel.KeyboardUiState(currentSchemaId = "t9_pinyin"),
                remember { com.kingzcheung.xime.rime.T9InputController() },
                Color(0xFF303540), Color.White, Color(0xFF475E83),
                modifier = Modifier.size(360.dp, 260.dp).background(Color(0xFF191D25)).testTag("t9-preview"))
        } }
        val zero = rule.onNodeWithTag("t9-zero-key")
        val delete = rule.onNodeWithTag("t9-delete-key").fetchSemanticsNode().boundsInRoot
        val bounds = zero.fetchSemanticsNode().boundsInRoot
        assertEquals(delete.left, bounds.left, 1f)
        assertEquals(delete.width, bounds.width, 1f)
        assertEquals(delete.height, bounds.height, 1f)
        zero.performTouchInput { down(center); up() }
        rule.runOnIdle { assertEquals(listOf("0"), committed); assertTrue(composition.isEmpty()) }
        save("t9-preview", "t9-zero.png")
    }
    @Test fun schemePanelDoesNotExposeOrChangeLanguageOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val saved = prefs.getString("input_language_order", null)
        val savedLanguages = prefs.getStringSet(com.kingzcheung.xime.settings.LanguagePreferences.KEY, null)?.toSet()
        try {
            prefs.edit().putStringSet(com.kingzcheung.xime.settings.LanguagePreferences.KEY, setOf("zh", "ja", "en")).commit()
            InputModes.saveLanguageOrder(context, listOf("zh", "ja", "en"))
            var currentSchema by mutableStateOf("rime_ice")
            rule.setContent { MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                SchemaListView(listOf(SchemaInfo("rime_ice", "中文26键", "", "", ""), SchemaInfo("t9_pinyin", "中文九键", "", "", "")),
                    currentSchema, Color(0xFF191D25), Color(0xFFB1C9F5), Color.White, Color(0xFF303540),
                    { currentSchema = it },
                    modifier = Modifier.size(360.dp, 260.dp).testTag("modes-preview"))
            } }
            save("modes-preview", "chinese-mode-order.png")
            rule.onAllNodesWithTag("profile-language").assertCountEquals(0)
            rule.onNodeWithTag("profile-layout-list").assertIsDisplayed()
            rule.onNodeWithTag("panel-scheme:rime_ice").assertIsSelected()
            rule.onNodeWithTag("panel-layout:t9").performScrollTo().performClick()
            rule.onNodeWithTag("panel-scheme:t9_pinyin").assertIsSelected()
            assertEquals(listOf("zh", "ja", "en"), InputModes.languageOrder(context).map { it.id })
        } finally { prefs.edit().also {
            if (saved == null) it.remove("input_language_order") else it.putString("input_language_order", saved)
            if (savedLanguages == null) it.remove(com.kingzcheung.xime.settings.LanguagePreferences.KEY)
            else it.putStringSet(com.kingzcheung.xime.settings.LanguagePreferences.KEY, savedLanguages)
        }.commit() }
    }
    @Test fun editorSectorGlowPreservesCentreAndOtherDirections() {
        rule.setContent { MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) { CompositionLocalProvider(
            LocalKeyboardInputPreferences provides KeyboardInputPreferences(keyGlowEnabled = true, keyAnimationEnabled = true)) {
            EditKeyboardLayout({}, {}, Color(0xFF191D25), Color.White, Color(0xFFB1C9F5), Color(0xFF303540),
                modifier = Modifier.size(360.dp, 260.dp).testTag("editor-preview"))
        } } }
        rule.mainClock.autoAdvance = false
        // Controls overlay the disc; capture the complete panel to verify non-active areas.
        val panelBefore = rule.onNodeWithTag("editor-preview").captureToImage().asAndroidBitmap()
        rule.onNodeWithContentDescription("向上").performTouchInput { down(androidx.compose.ui.geometry.Offset(center.x, height / 6f)); up() }
        rule.mainClock.advanceTimeBy(80)
        val active = rule.onNodeWithTag("editor-preview").captureToImage().asAndroidBitmap()
        assertFalse(panelBefore.sameAs(active))
        val panel = rule.onNodeWithTag("editor-preview").fetchSemanticsNode().boundsInRoot
        for (label in listOf("选择", "向左", "向右", "向下")) {
            val bounds = rule.onNodeWithTag("editor-label-$label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            for (x in (bounds.left-panel.left).toInt() until (bounds.right-panel.left).toInt())
                for (y in (bounds.top-panel.top).toInt() until (bounds.bottom-panel.top).toInt())
                    assertEquals("Only the upper sector may glow", panelBefore.getPixel(x,y), active.getPixel(x,y))
        }
        save("editor-preview", "editor-sector-glow.png")
        rule.mainClock.advanceTimeBy(500)
        assertTrue(panelBefore.sameAs(rule.onNodeWithTag("editor-preview").captureToImage().asAndroidBitmap()))
    }
}
