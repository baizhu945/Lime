package com.kingzcheung.xime.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.*
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.settings.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsAlignmentTest {
    @get:Rule val rule = createComposeRule()

    @Test fun englishUsesTheSameCardAndSwitchGeometryAsOtherModes() {
        var toggleCalls = 0
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            Surface {
                Column(Modifier.width(360.dp).padding(16.dp).testTag("settings-preview"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SchemaToggleItem(SchemaMeta(InputModes.ENGLISH, "英文"), true, true, false,
                        { toggleCalls++ }, {}, isBuiltIn = true)
                    SchemaToggleItem(SchemaMeta("t9_pinyin", "中文九键", "3.0.0", "Dvel"), true, true, false,
                        { toggleCalls++ }, {})
                }
            }
        } }
        val englishCard = rule.onNodeWithTag("schema-card:${InputModes.ENGLISH}").fetchSemanticsNode().boundsInRoot
        val chineseCard = rule.onNodeWithTag("schema-card:t9_pinyin").fetchSemanticsNode().boundsInRoot
        assertEquals(englishCard.left, chineseCard.left, 0f)
        assertEquals(englishCard.right, chineseCard.right, 0f)
        val description = rule.onNodeWithText(requireNotNull(CyimeInputDefaults.description("t9_pinyin")), useUnmergedTree = true)
        val descriptionBounds = description.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Only the actual extra description row may add height", englishCard.height + descriptionBounds.height,
            chineseCard.height, 1f)
        val descriptionLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        description.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(descriptionLayouts) }
        assertTrue("Description must be measured and remain unclipped: ${descriptionLayouts.map {
            "size=${it.size},width=${it.didOverflowWidth},height=${it.didOverflowHeight},lines=${it.lineCount}," +
                "bottom=${if (it.lineCount > 0) it.getLineBottom(it.lineCount - 1) else 0f}," +
                "paragraphWidth=${it.multiParagraph.width},lineRight=${if (it.lineCount > 0) it.getLineRight(0) else 0f}," +
                "constraints=${it.layoutInput.constraints},style=${it.layoutInput.style}"
        }}", descriptionLayouts.isNotEmpty() && descriptionLayouts.all { layout ->
            !layout.didOverflowHeight && layout.lineCount > 0 &&
                layout.getLineEnd(layout.lineCount - 1) == layout.layoutInput.text.length &&
                (0 until layout.lineCount).all { line ->
                    !layout.isLineEllipsized(line) && layout.getLineLeft(line) >= 0f &&
                        layout.getLineRight(line) <= layout.size.width && layout.getLineBottom(line) <= layout.size.height
                }
        })
        val englishSwitch = rule.onNodeWithTag("schema-toggle:${InputModes.ENGLISH}")
        val chineseSwitch = rule.onNodeWithTag("schema-toggle:t9_pinyin")
        val first = englishSwitch.fetchSemanticsNode().boundsInRoot
        val second = chineseSwitch.fetchSemanticsNode().boundsInRoot
        assertEquals(first.left, second.left, 0f)
        assertEquals(first.right, second.right, 0f)
        assertEquals(first.height, second.height, 0f)
        assertEquals(englishCard.center.y, first.center.y, 1f)
        assertEquals(chineseCard.center.y, second.center.y, 1f)
        assertEquals(rule.onNodeWithText("英文", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left,
            rule.onNodeWithText("中文九键", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left, 0f)
        englishSwitch.assertIsOn().assertIsNotEnabled().performTouchInput { down(center); up() }
        rule.runOnIdle { assertEquals(0, toggleCalls) }
        englishSwitch.assertIsOn()
        chineseSwitch.performClick()
        rule.runOnIdle { assertEquals(1, toggleCalls) }
        screenshot("english-card-alignment")
    }

    @Test fun spaceHoldOptionsShareOneFullWidthRowAndStillPersistSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val keys = listOf("space_hold_action", "cursor_gesture_mode", "cursor_step_dp", "key_text_scale", "fixed_symbols", "handwriting_pause_seconds", "handwriting_pause_seconds_v2")
        val saved = keys.associateWith { prefs.all[it] }
        try {
            rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    Column(Modifier.width(360.dp).height(600.dp).padding(16.dp).verticalScroll(rememberScrollState()).testTag("settings-preview")) {
                        InputExperienceSettings()
                    }
                }
            } }
            rule.onNodeWithTag("space-hold-options", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val left = rule.onNodeWithTag("space-hold:REPEAT").assertIsDisplayed()
            val right = rule.onNodeWithTag("space-hold:VOICE_TOGGLE").assertIsDisplayed()
            val a = left.fetchSemanticsNode().boundsInRoot
            val b = right.fetchSemanticsNode().boundsInRoot
            val row = rule.onNodeWithTag("space-hold-options", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(a.top, b.top, 0f)
            assertEquals(a.bottom, b.bottom, 0f)
            assertEquals(a.width, b.width, 1f)
            assertEquals(row.left, a.left, 1f)
            assertEquals(row.right, b.right, 1f)
            assertTrue(a.right < b.left)
            right.performClick().assertIsSelected()
            rule.runOnIdle { assertEquals(SpaceHoldAction.VOICE_TOGGLE, KeyboardInputPreferences.read(context).effectiveSpaceHold) }
            left.performClick().assertIsSelected()
            rule.runOnIdle { assertEquals(SpaceHoldAction.REPEAT, KeyboardInputPreferences.read(context).effectiveSpaceHold) }
            screenshot("space-hold-alignment")
        } finally {
            prefs.edit().also { edit -> saved.forEach { (key, value) -> when (value) {
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                else -> edit.remove(key)
            } } }.commit()
        }
    }

    private fun screenshot(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "settings-regression").apply { mkdirs() }
        val bitmap = rule.onNodeWithTag("settings-preview").captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
