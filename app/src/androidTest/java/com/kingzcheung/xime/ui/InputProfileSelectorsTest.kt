package com.kingzcheung.xime.ui

import android.content.ContextWrapper
import java.io.File
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.InputLayout
import com.kingzcheung.xime.settings.InputScheme
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.settings.Cyletix10Layout
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.settings.InputProfileSelectors
import com.kingzcheung.xime.ui.menubar.SchemaListView
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class InputProfileSelectorsTest {
    @get:Rule val rule = createComposeRule()

    private val target by lazy { InstrumentationRegistry.getInstrumentation().targetContext }
    private val isolated by lazy {
        object : ContextWrapper(target) {
            override fun getFilesDir(): File =
                File(target.cacheDir, "input-profile-selectors-config").apply { mkdirs() }
        }
    }

    @Before fun loadDefaultKeyboardConfig() {
        KeysConfigHelper.loadConfig(isolated)
    }

    @After fun restoreKeyboardConfig() {
        KeysConfigHelper.loadConfig(target)
    }
    private val entries = listOf(
        SchemaInfo("rime_ice", "雾凇拼音", "", "", ""),
        SchemaInfo("t9_pinyin", "九键拼音", "", "", ""),
        SchemaInfo("pinyin_14jian", "14键拼音", "", "", ""),
        SchemaInfo("double_pinyin_flypy", "小鹤双拼", "", "", ""),
    )

    private fun selectChoice(cardTag: String, choiceId: String, scrollParent: Boolean = false) {
        if (scrollParent) rule.onNodeWithTag(cardTag).performScrollTo()
        rule.onNodeWithTag("$cardTag:list").performScrollToKey(choiceId)
        rule.onNodeWithTag("$cardTag:option:$choiceId").assertIsDisplayed().performClick()
    }

    /** 点一下布局就切换：保持当前方案（拼音），该布局没有时取它的第一个方案。 */
    @Test fun layoutTapSwitchesAtOnceKeepingTheCurrentScheme() {
        var current by mutableStateOf("double_pinyin_flypy")
        val confirmed = mutableListOf<String>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme {
                    SchemaListView(entries, current, Color.DarkGray, Color.Cyan, Color.White, Color.Gray,
                        { confirmed += it; current = it }, Modifier.size(640.dp, 300.dp))
                }
            }
        }
        rule.onNodeWithTag("profile-layout-list:list").performScrollToKey("t9")
        rule.onNodeWithTag("panel-layout:t9").performClick()
        rule.runOnIdle { assertEquals(listOf("t9_pinyin"), confirmed) }
        rule.onNodeWithTag("profile-layout-list:list").performScrollToKey("qwerty")
        rule.onNodeWithTag("panel-layout:qwerty").performClick()
        rule.runOnIdle { assertEquals(listOf("t9_pinyin", "rime_ice"), confirmed) }
        rule.onNodeWithTag("panel-scheme:double_pinyin_flypy").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(listOf("t9_pinyin", "rime_ice", "double_pinyin_flypy"), confirmed) }
        rule.onNodeWithTag("profile-layout-list:list").performScrollToKey("qwerty_14")
        rule.onNodeWithTag("panel-layout:qwerty_14").performClick()
        rule.runOnIdle { assertEquals("pinyin_14jian", confirmed.last()) }
    }

    @Test fun wideLightSettingsShowEqualPreviewCardsAndSelectRealCombinations() {
        var current by mutableStateOf(entries.first())
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    InputProfileSelectors(entries, current, Modifier.size(640.dp, 360.dp)) { current = it }
                }
            }
        }
        val layout = rule.onNodeWithTag("layout-choice:zh").fetchSemanticsNode().boundsInRoot
        val scheme = rule.onNodeWithTag("scheme-choice:zh").fetchSemanticsNode().boundsInRoot
        assertTrue(layout.bottom < scheme.top)
        assertTrue(abs(layout.width - scheme.width) < 1f)
        val selected = rule.onNodeWithTag("layout-choice:zh:option:qwerty").assertIsSelected().fetchSemanticsNode().boundsInRoot
        val viewport = rule.onNodeWithTag("layout-choice:zh:list").fetchSemanticsNode().boundsInRoot
        val other = rule.onNodeWithTag("layout-choice:zh:option:t9").fetchSemanticsNode().boundsInRoot
        assertEquals(selected.height, other.height, 1f)
        assertEquals(selected.width, other.width, 1f)
        assertTrue("Compact layout keeps a miniature and one tight label line", viewport.height in 80f..110f)
        rule.onNodeWithTag("layout-preview:qwerty", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("layout-preview:qwerty_14", useUnmergedTree = true)
            .assertContentDescriptionContains("按键排列：qw er ty ui op / as df gh jk l / zx cv bn m")
        saveUiEvidence("input-layout-wide-light", rule.onNodeWithTag("profile-settings-selectors"))
        assertTrue(selected.right < other.left)
        assertTrue(abs(selected.center.y - viewport.center.y) < 2f)
        selectChoice("layout-choice:zh", "t9")
        rule.runOnIdle { assertEquals("t9_pinyin", current.schemaId) }
        selectChoice("scheme-choice:zh", "double_pinyin_flypy")
        rule.runOnIdle {
            assertEquals("double_pinyin_flypy", current.schemaId)
            assertEquals(InputLayout.QWERTY.id, current.profile.layout.id)
            assertEquals(InputScheme.DOUBLE_PINYIN, current.profile.scheme)
        }
        // Layouts the selected scheme does not offer stay visible but unavailable.
        rule.onNodeWithTag("layout-choice:zh:option:t9")
            .assertExists().assertIsDisplayed().assertIsNotSelected()
    }

    @Test fun narrowDarkLargeTextStacksCardsAndKeepsBothListsReachable() {
        var current by mutableStateOf(entries.first())
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                    Column(Modifier.size(280.dp, 440.dp).verticalScroll(rememberScrollState())) {
                        InputProfileSelectors(entries, current) { current = it }
                    }
                    }
                }
            }
        }
        val layout = rule.onNodeWithTag("layout-choice:zh").getUnclippedBoundsInRoot()
        val scheme = rule.onNodeWithTag("scheme-choice:zh").getUnclippedBoundsInRoot()
        assertTrue(layout.bottom < scheme.top)
        saveUiEvidence("input-layout-narrow-large-text", rule.onNodeWithTag("profile-settings-selectors"))
        selectChoice("layout-choice:zh", "t9")
        rule.runOnIdle { assertEquals("t9_pinyin", current.schemaId) }
        selectChoice("scheme-choice:zh", "double_pinyin_flypy", scrollParent = true)
        rule.runOnIdle { assertEquals("double_pinyin_flypy", current.schemaId) }
        rule.onNodeWithTag("scheme-choice:zh").performScrollTo()
        saveUiEvidence("input-scheme-narrow-large-text", rule.onNodeWithTag("scheme-choice:zh"))
        rule.onAllNodes(isPopup()).assertCountEquals(0)
    }

    @Test fun keyboardPanelPreviewsAndSchemesStayReachableWithinShortWindow() {
        var current by mutableStateOf("rime_ice")
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    SchemaListView(entries + SchemaInfo(Cyletix10Layout.ID, "Cyletix10（实验）", "", "", ""), current,
                        Color(0xff20212b), Color(0xffc6b0f4),
                        Color(0xffece6f2), Color(0xff33343f), { current = it },
                        Modifier.size(640.dp, 340.dp))
                }
            }
        }
        saveUiEvidence("input-layout-ime-wide", rule.onNodeWithTag("input-profile-panel"))
        rule.onNodeWithTag("panel-scheme:rime_ice").assertTextContains("拼音")
        rule.onNodeWithText("中文26键").assertDoesNotExist()
        rule.onNodeWithText("输入方式").assertDoesNotExist()
        rule.onAllNodesWithText("方案管理").assertCountEquals(1)
        val layoutCard = rule.onNodeWithTag("panel-layout:qwerty").fetchSemanticsNode().boundsInRoot
        val schemeCard = rule.onNodeWithTag("panel-scheme:rime_ice").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("profile-layout-list:list").performScrollToKey("layout-management")
        rule.onAllNodesWithText("布局管理").assertCountEquals(1)
        val layoutAction = rule.onNodeWithTag("profile-layout-management").fetchSemanticsNode().boundsInRoot
        val schemeAction = rule.onNodeWithTag("profile-scheme-management").fetchSemanticsNode().boundsInRoot
        assertEquals(layoutCard.left, schemeCard.left, 1f)
        assertEquals("Layout and scheme use 60:40", layoutCard.height / 1.5f, schemeCard.height, 1f)
        assertTrue("Unused bottom space stays below one text line", 340f - schemeCard.bottom <= 8f)
        assertEquals(layoutCard.top, layoutAction.top, 1f)
        assertEquals(layoutCard.height, layoutAction.height, 1f)
        assertEquals(schemeCard.top, schemeAction.top, 1f)
        assertEquals(schemeCard.height, schemeAction.height, 1f)
        assertTrue(layoutAction.width < layoutCard.width)
        assertTrue(schemeAction.width < schemeCard.width)
        assertTrue(schemeCard.bottom <= rule.onNodeWithTag("input-profile-panel").fetchSemanticsNode().boundsInRoot.bottom)
        rule.onNodeWithTag("panel-scheme:rime_ice").assertIsDisplayed()
        rule.onNodeWithTag("profile-layout-management").assertIsDisplayed()
        rule.onNodeWithTag("profile-layout-list:list").performScrollToKey("qwerty_14")
        rule.onNodeWithTag("panel-layout:qwerty_14").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("pinyin_14jian", current) }
        rule.onNodeWithTag("profile-layout-list:list").performScrollToKey("layout-management")
        rule.onNodeWithTag("profile-layout-management").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("panel-scheme:pinyin_14jian").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("profile-layout-list:list").performScrollToKey("t9")
        rule.onNodeWithTag("panel-layout:t9").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("t9_pinyin", current) }
    }

    @Test fun concreteDoublePinyinNamesRemainVisibleAndSelectDifferentEntries() {
        val choices = entries + SchemaInfo("double_pinyin_mspy", "微软双拼", "", "", "")
        var current by mutableStateOf(entries.first())
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme { InputProfileSelectors(choices, current, Modifier.width(640.dp)) { current = it } }
            }
        }
        rule.onNodeWithText("小鹤双拼").assertIsDisplayed()
        rule.onNodeWithText("微软双拼").assertIsDisplayed()
        rule.onNodeWithText("双拼", substring = false).assertDoesNotExist()
        selectChoice("scheme-choice:zh", "double_pinyin_mspy")
        rule.runOnIdle { assertEquals("double_pinyin_mspy", current.schemaId) }
        selectChoice("scheme-choice:zh", "double_pinyin_flypy")
        rule.runOnIdle { assertEquals("double_pinyin_flypy", current.schemaId) }
    }

    @Test fun minimumImeKeepsBothRowsVisibleAndManagementAtTheirEnds() {
        var width by mutableStateOf(380.dp)
        var height by mutableStateOf(164.dp)
        var selected by mutableStateOf("rime_ice")
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.size(width, height)) {
                        SchemaListView(entries + SchemaInfo(Cyletix10Layout.ID, "Cyletix10（实验）", "", "", ""), selected,
                            Color(0xff20212b), Color(0xffc6b0f4), Color(0xffece6f2), Color(0xff33343f),
                            { selected = it }, Modifier.fillMaxSize().padding(bottom = 20.dp))
                    }
                }
            }
        }
        for ((w, h) in listOf(380.dp to 164.dp, 280.dp to 164.dp, 760.dp to 204.dp, 760.dp to 340.dp)) {
            rule.runOnIdle { width = w; height = h }
            rule.onNodeWithTag("profile-layout-list:list").performScrollToKey("qwerty")
            rule.onNodeWithTag("profile-scheme-list:list").performScrollToKey("rime_ice")
            val panel = rule.onNodeWithTag("input-profile-panel").fetchSemanticsNode().boundsInRoot
            val layout = rule.onNodeWithTag("profile-layout-list").getUnclippedBoundsInRoot()
            val scheme = rule.onNodeWithTag("profile-scheme-list").getUnclippedBoundsInRoot()
            assertTrue("The first row must not start above the panel", layout.top.value >= panel.top)
            assertTrue("The scheme row fits without vertical scrolling at $w x $h", scheme.bottom.value <= panel.bottom + 1f)
            assertEquals((layout.bottom - layout.top).value / 1.5f, (scheme.bottom - scheme.top).value, 2f)
            assertTrue("No dead space below the choices", panel.bottom - scheme.bottom.value <= 8f)
            rule.onNodeWithTag("panel-scheme:rime_ice").assertIsDisplayed()
            saveUiEvidence("input-layout-initial-${w.value.toInt()}", rule.onNodeWithTag("input-profile-panel"))
            rule.onNodeWithTag("profile-layout-list:list").performScrollToKey("layout-management")
            rule.onNodeWithTag("profile-layout-management").assertIsDisplayed()
            rule.onNodeWithTag("profile-scheme-list:list").performScrollToKey("scheme-management")
            rule.onNodeWithTag("profile-scheme-management").assertIsDisplayed()
            saveUiEvidence("input-layout-minimum-${w.value.toInt()}", rule.onNodeWithTag("input-profile-panel"))
        }
        rule.onNodeWithTag("profile-layout-list:list").performScrollToKey("qwerty_14")
        rule.onNodeWithTag("panel-layout:qwerty_14").performClick()
        rule.runOnIdle { assertEquals("pinyin_14jian", selected) }
        rule.onNodeWithTag("panel-scheme:pinyin_14jian").assertIsSelected().assertIsDisplayed()
    }

    @Test fun reopeningAfterMinimumThenLargerHeightUsesTheNewSize() {
        var height by mutableStateOf(164.dp)
        var visible by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme {
                    Box(Modifier.size(380.dp, height)) {
                        if (visible) SchemaListView(entries, "rime_ice", Color(0xff20212b), Color.Cyan,
                            Color.White, Color.DarkGray, {}, Modifier.fillMaxSize())
                    }
                }
            }
        }
        for (target in listOf(164.dp, 340.dp, 164.dp, 300.dp)) {
            rule.runOnIdle { visible = false }
            rule.runOnIdle { height = target; visible = true }
            val panel = rule.onNodeWithTag("input-profile-panel").fetchSemanticsNode().boundsInRoot
            val layout = rule.onNodeWithTag("profile-layout-list").fetchSemanticsNode().boundsInRoot
            val scheme = rule.onNodeWithTag("profile-scheme-list").fetchSemanticsNode().boundsInRoot
            assertEquals(target.value, panel.height, 1f)
            assertEquals(layout.height / 1.5f, scheme.height, 2f)
            assertTrue(panel.bottom - scheme.bottom <= 8f)
            rule.onNodeWithTag("panel-scheme:rime_ice").assertIsDisplayed()
        }
        saveUiEvidence("input-layout-reopened-taller", rule.onNodeWithTag("input-profile-panel"))
    }

    @Test fun miniatureAspectAndUnselectedDividersSurviveTallAndShortPanels() {
        var height by mutableStateOf(164.dp)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    SchemaListView(entries, "rime_ice", Color(0xff20212b), Color(0xffc6b0f4),
                        Color.White, Color.DarkGray, {}, Modifier.size(380.dp, height))
                }
            }
        }
        for (target in listOf(164.dp, 340.dp)) {
            rule.runOnIdle { height = target }
            for ((id, aspect) in listOf("qwerty" to 356f / 152f, "t9" to 224f / 152f, "qwerty_14" to 356f / 152f)) {
                rule.onNodeWithTag("profile-layout-list:list").performScrollToKey(id)
                val preview = rule.onNodeWithTag("layout-preview:$id", useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertEquals("$id preserves keyboard proportions at $target", aspect,
                    (preview.right - preview.left).value / (preview.bottom - preview.top).value, .025f)
                rule.onNodeWithTag("panel-layout:$id:divider", useUnmergedTree = true).assertIsDisplayed()
                saveUiEvidence("input-preview-$id-${target.value.toInt()}", rule.onNodeWithTag("input-profile-panel"))
            }
            rule.onNodeWithTag("profile-scheme-list:list").performScrollToKey("double_pinyin_flypy")
            rule.onNodeWithTag("panel-scheme:double_pinyin_flypy").assertTextContains("xn'he'ul'pb")
        }
    }

    @Test fun unboundedLanguageListParentKeepsOptionScrollAreasFinite() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme {
                    LazyColumn(Modifier.size(360.dp, 480.dp)) {
                        item {
                            InputProfileSelectors(entries, entries.first(), Modifier.fillMaxWidth()) {}
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("layout-choice:zh:list").assertIsDisplayed()
        rule.onNodeWithTag("scheme-choice:zh:list").assertIsDisplayed()
        val selector = rule.onNodeWithTag("profile-settings-selectors").getUnclippedBoundsInRoot()
        assertTrue("Selector must not expand to an unbounded height", selector.bottom - selector.top < 480.dp)
    }
}
