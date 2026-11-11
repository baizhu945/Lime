package com.kingzcheung.xime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SchemaMeta
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.ui.menubar.ClipboardBoardView
import com.kingzcheung.xime.ui.settings.SchemaToggleItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ResourceAndCategoryConsistencyTest {
    @get:Rule val rule = createComposeRule()

    @Test fun clipboardAndEmojiCategoryBarsMatchAtNormalAndLargeFonts() {
        val scale = mutableFloatStateOf(1f)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.floatValue)) {
                MaterialTheme(darkColorScheme()) {
                    Column(Modifier.requiredSize(360.dp, 260.dp).background(MaterialTheme.colorScheme.surface).testTag("category-comparison")) {
                        ExpressionPanelBar(Color.White, {}, leadingTabs = {
                            ExpressionTab("Emoji", true, Color.White, Color.Cyan, onClick = {})
                        }, categories = { Spacer(Modifier.weight(1f)) })
                        ClipboardBoardView(textItems = emptyList(), images = emptyList(), initialImages = false,
                            expanded = false, pins = emptySet(), photoAccess = false, failure = null,
                            onBack = {}, onQuickSend = {}, onSelectText = {}, onSelectImage = {}, onSplit = { _, _ -> },
                            onAddQuick = {}, onPinsChange = {}, onRemove = {}, onPhotoAccess = {}, onPick = {},
                            onSystemPaste = {}, onShare = { _, _ -> }, onPullRemote = null)
                    }
                }
            }
        }
        for (font in listOf(1f, 1.3f, 2f)) {
            rule.runOnIdle { scale.floatValue = font }
            val expression = rule.onNodeWithTag("expression-controls", true).fetchSemanticsNode().boundsInRoot
            val clipboard = rule.onNodeWithTag("clipboard-controls", true).fetchSemanticsNode().boundsInRoot
            assertEquals("Both menus at font scale $font", expression.height, clipboard.height, 1f)
            if (font == 1f) saveUiEvidence("clipboard-expression-same-height", rule.onNodeWithTag("category-comparison"))
        }
    }

    @Test fun sameNamedResourcesShowTheirLayoutOrOrigin() {
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            MaterialTheme(darkColorScheme()) {
                Column(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface).testTag("resource-comparison")) {
                    listOf("double_pinyin_flypy" to "小鹤双拼", "double_pinyin_flypy_14jian" to "小鹤双拼",
                        "custom_pinyin_fixture" to "Cyletix10", "pinyin_cyletix10" to "Cyletix10（实验）")
                        .forEachIndexed { index, (id, name) ->
                            SchemaToggleItem(SchemaMeta(id, name), enabled = index % 2 == 0,
                                isCompiled = true, isCurrent = false, onToggle = {}, onSelect = {})
                        }
                }
            }
        } }
        for (name in listOf("小鹤双拼 · 26键", "小鹤双拼 · 14键", "Cyletix10 · 自定义", "Cyletix10（实验） · 内置"))
            rule.onNodeWithText(name).assertIsDisplayed()
        saveUiEvidence("resources-distinct-layout-source", rule.onNodeWithTag("resource-comparison"))
    }

    @Test fun cornerKeysFollowCommonDirectionWithoutCallerOptIn() {
        val reversed = mutableStateOf(false)
        val commits = mutableListOf<String>()
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f),
            LocalKeyboardInputPreferences provides KeyboardInputPreferences(reverseSymbolSwipe = reversed.value)) {
            Box(Modifier.requiredSize(240.dp)) {
                SwipeableKeyButton("ABC", { commits += "tap" }, Color.DarkGray, Color.White,
                    Modifier.fillMaxSize().testTag("common-symbol-key"), swipeText = "2", swipeDownText = "!",
                    onSwipe = { commits += it }, onSwipeDown = { commits += it })
            }
        } }
        for (reverse in listOf(false, true)) {
            rule.runOnIdle { reversed.value = reverse }
            rule.onNodeWithTag("common-symbol-key").performTouchInput {
                down(center); moveTo(center.copy(y = center.y - 80f)); up()
            }
            rule.onNodeWithTag("common-symbol-key").performTouchInput {
                down(center); moveTo(center.copy(y = center.y + 80f)); up()
            }
        }
        rule.runOnIdle { assertEquals(listOf("!", "2", "2", "!"), commits) }
    }
    @Test fun quickActionEditorShowsOneClipboardAndDragsAcrossTheMenuDivider() {
        val layout = mutableStateOf(com.kingzcheung.xime.keyboard.QuickActionLayout(
            listOf("clipboard", "edit", "voice"), listOf("settings", "sound")))
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            MaterialTheme(darkColorScheme()) {
                Box(Modifier.requiredSize(360.dp, 520.dp).testTag("toolbar-customize")) {
                    com.kingzcheung.xime.ui.menubar.ToolbarCustomizeView(layout.value.toolbar, layout.value.menu,
                        keyTextColor = Color.White, backgroundColor = Color(0xFF202028),
                        accentColor = Color(0xFFCEC0FF), keyBgColor = Color(0xFF303038),
                        onUpdateQuickActions = { layout.value = it }, onDismiss = {})
                }
            }
        } }
        rule.onNodeWithText("固定内容").assertDoesNotExist()
        rule.onNodeWithText("剪贴板").assertIsDisplayed()
        // Sections read top to bottom: toolbar entries, the menu divider, menu entries, then unused entries.
        val y = { tag: String -> rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top }
        val menuHeader = rule.onNodeWithText("菜单").fetchSemanticsNode().boundsInRoot.top
        assertTrue(y("quick-action:voice") < menuHeader && menuHeader < y("quick-action:settings"))
        saveUiEvidence("quick-action-editor", rule.onNodeWithTag("toolbar-customize"))
        // Long-press the last toolbar entry and drag it past the menu divider.
        rule.onNodeWithTag("quick-action:voice").performTouchInput {
            down(center); advanceEventTime(800)
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(0f, 8f)); advanceEventTime(16) }
            up()
        }
        rule.runOnIdle {
            assertEquals(listOf("clipboard", "edit"), layout.value.toolbar)
            assertEquals("voice", layout.value.menu.first())
        }
    }

    @Test fun fourteenPreviewShowsSingleLettersAsFullKeys() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        com.kingzcheung.xime.settings.KeysConfigHelper.loadConfig(context)
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            MaterialTheme(darkColorScheme()) {
                Box(Modifier.requiredSize(360.dp, 180.dp).background(Color(0xFF202028)).testTag("fourteen-preview")) {
                    com.kingzcheung.xime.ui.settings.LayoutPreviewChoices(
                        listOf(com.kingzcheung.xime.settings.InputLayout.MERGED14),
                        com.kingzcheung.xime.settings.InputLanguage.CHINESE, "qwerty_14", "fourteen-list",
                        Color(0xFF303038), Color.White, Color(0xFFCEC0FF), rowHeight = 160.dp, onSelect = {})
                }
            }
        } }
        rule.onNodeWithTag("layout-preview:qwerty_14", true).assertContentDescriptionContains(
            "按键排列：qw er ty ui op / as df gh jk l / zx cv bn m")
        saveUiEvidence("fourteen-preview-balanced", rule.onNodeWithTag("fourteen-preview"))
    }

}
