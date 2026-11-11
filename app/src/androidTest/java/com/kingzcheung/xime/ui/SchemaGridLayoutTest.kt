package com.kingzcheung.xime.ui

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.*
import com.kingzcheung.xime.ui.menubar.SchemaListView
import com.kingzcheung.xime.ui.settings.InputProfileSelectors
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class SchemaGridLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val schemas = listOf("rime_ice", "t9_pinyin", "pinyin_14jian", "double_pinyin_flypy", "japanese")
        .map { SchemaInfo(it, it, "", "", "") }

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val launches = mutableListOf<Intent>()
    private val fixtureContext by lazy {
        object : ContextWrapper(app) {
            // Any future selector preference writes remain in the test APK, not user prefs.
            override fun getSharedPreferences(name: String, mode: Int) =
                InstrumentationRegistry.getInstrumentation().context
                    .getSharedPreferences("schema-grid-$name", mode)
            override fun startActivity(intent: Intent) { launches += Intent(intent) }
        }
    }
    private val bindingsField = KeysConfigHelper::class.java.getDeclaredField("_schemaSectionBindings").apply { isAccessible = true }
    private val loadedField = KeysConfigHelper::class.java.getDeclaredField("hasLoadedLayoutBindings").apply { isAccessible = true }
    private var previousBindings: Any? = null
    private var previousLoaded = false
    private val chosenField = LetterLayouts::class.java.getDeclaredField("chosen\$delegate").apply { isAccessible = true }
    private var previousChineseLayout: String? = null
    private var previousRows: List<List<String>> = emptyList()

    @Before fun useShippedBindingsWithoutLoadingUserConfig() {
        // Snapshot only the caches this fixture changes; never load/clear real user prefs.
        previousBindings = bindingsField.get(KeysConfigHelper)
        previousLoaded = loadedField.getBoolean(KeysConfigHelper)
        // Preserve even a stale/deleted layout ID, which chosen() deliberately hides.
        @Suppress("UNCHECKED_CAST")
        val chosen = chosenField.get(LetterLayouts) as State<Map<InputLanguage, String>>
        previousChineseLayout = chosen.value[InputLanguage.CHINESE]
        previousRows = KeysConfigHelper.getKeyRows(false)
        val yaml = app.assets.open("xime.yaml").bufferedReader().use { it.readText() }
        bindingsField.set(KeysConfigHelper, KeysConfigHelper.parseSchemaBindingsYamlText(yaml))
        loadedField.setBoolean(KeysConfigHelper, true)
        LetterLayouts.setForTest(InputLanguage.CHINESE, null)
    }

    @After fun restoreOnlyFixtureState() {
        bindingsField.set(KeysConfigHelper, previousBindings)
        loadedField.setBoolean(KeysConfigHelper, previousLoaded)
        LetterLayouts.setForTest(InputLanguage.CHINESE, previousChineseLayout)
        assertEquals("Preview/selection must not replace the active keyboard rows", previousRows,
            KeysConfigHelper.getKeyRows(false))
    }

    private fun themedFixture(content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalContext provides fixtureContext) {
                MaterialTheme { content() }
            }
        }
    }

    private fun choice(row: String, key: String, tag: String, scrollParent: Boolean = true): SemanticsNodeInteraction {
        if (scrollParent) rule.onNodeWithTag(row).performScrollTo()
        rule.onNodeWithTag("$row:list").performScrollToKey(key)
        val node = rule.onNodeWithTag(tag)
        if (scrollParent) node.performScrollTo()
        return node.assertIsDisplayed()
    }

    @Test fun shortPanelSwitchesWithoutAnApplyStep() = checkChoices(360, 176, 1f)
    @Test fun narrowLargeTextChoicesRemainReachable() = checkChoices(280, 184, 2f)
    @Test fun landscapePanelUsesTheSameChoices() = checkChoices(640, 180, 1.3f)

    private fun checkChoices(width: Int, height: Int, fontScale: Float) {
        val selected = mutableListOf<String>()
        val current = mutableStateOf("rime_ice")
        rule.setContent {
            CompositionLocalProvider(LocalContext provides fixtureContext, LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme {
                    SchemaListView(schemas, current.value, Color.White, Color.Blue, Color.Black, Color.LightGray,
                        onSelectSchema = { selected += it; current.value = it }, modifier = Modifier.size(width.dp, height.dp).testTag("schema-panel"))
                }
            }
        }
        rule.onAllNodesWithTag("profile-language").assertCountEquals(0)
        rule.onAllNodesWithTag("apply-input-profile").assertCountEquals(0)
        val entries = InputModes.available(schemas).filter { it.profile.language == InputLanguage.CHINESE }
        val flypy = entries.single { it.schemaId == "double_pinyin_flypy" }
        val fallback = requireNotNull(InputProfileSelection.switchLayout(entries, flypy, "t9", schemas))
        assertFalse("A missing combination must take the explicit hint path", fallback.keepsScheme)
        assertEquals("t9_pinyin", fallback.entry.schemaId)
        assertEquals("九键没有double_pinyin_flypy，已换成拼音",
            InputProfileSelection.switchHint(InputLayout.T9, flypy, fallback.entry))
        assertTrue(requireNotNull(InputProfileSelection.switchLayout(entries, fallback.entry, "qwerty", schemas)).keepsScheme)
        choice("profile-scheme-list", "double_pinyin_flypy", "panel-scheme:double_pinyin_flypy").performClick()
        rule.runOnIdle { assertEquals(listOf("double_pinyin_flypy"), selected) }
        val unavailable = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "没有当前方案")
        choice("profile-layout-list", "t9", "panel-layout:t9").assertIsEnabled()
            .assert(unavailable or hasAnyAncestor(unavailable))
            .performClick()
        rule.runOnIdle { assertEquals(listOf("double_pinyin_flypy", "t9_pinyin"), selected) }
        rule.onNodeWithTag("panel-layout:t9").assertIsSelected()
        choice("profile-layout-list", "qwerty", "panel-layout:qwerty").assertIsNotSelected()
        val target = choice("profile-scheme-list", "t9_pinyin", "panel-scheme:t9_pinyin").assertIsSelected()
        val targetBounds = target.getUnclippedBoundsInRoot()
        val panel = rule.onNodeWithTag("schema-panel").fetchSemanticsNode().boundsInRoot
        // These three cases explicitly use density 1, so dp and root pixels coincide.
        assertTrue(targetBounds.top.value >= panel.top && targetBounds.bottom.value <= panel.bottom)
        assertTrue(targetBounds.left.value >= panel.left && targetBounds.right.value <= panel.right)
        target.performClick()
        // ProfileChoiceRow's selectable invokes onSelect even for the selected item.
        rule.runOnIdle { assertEquals(listOf("double_pinyin_flypy", "t9_pinyin", "t9_pinyin"), selected) }
        rule.onNodeWithTag("panel-scheme:t9_pinyin").assertIsSelected()
        choice("profile-layout-list", "qwerty", "panel-layout:qwerty").performClick()
        rule.runOnIdle { assertEquals(listOf("double_pinyin_flypy", "t9_pinyin", "t9_pinyin", "rime_ice"), selected) }
        rule.onNodeWithTag("panel-layout:qwerty").assertIsSelected()
        choice("profile-scheme-list", "rime_ice", "panel-scheme:rime_ice").assertIsSelected()
        rule.onAllNodesWithTag("schema-tile:t9_pinyin").assertCountEquals(0)
    }

    @Test fun roomyPanelCentersActualChoicesWithoutAnApplyButton() {
        val current = mutableStateOf("t9_pinyin")
        themedFixture {
            SchemaListView(schemas, current.value, Color.White, Color.Blue, Color.Black, Color.LightGray,
                { current.value = it }, modifier = Modifier.size(360.dp, 360.dp))
        }
        fun assertCentered(item: String, viewport: String) {
            val bounds = rule.onNodeWithTag(item).fetchSemanticsNode().boundsInRoot
            val parent = rule.onNodeWithTag(viewport).fetchSemanticsNode().boundsInRoot
            assertTrue("The active choice should sit in the centre of the selector",
                abs(bounds.center.y - parent.center.y) <= 2f)
        }
        rule.onNodeWithTag("panel-layout:t9").assertIsSelected()
        assertCentered("panel-layout:t9", "profile-layout-list:list")
        assertCentered("panel-scheme:t9_pinyin", "profile-scheme-list:list")
        rule.runOnIdle { current.value = "double_pinyin_flypy" }
        rule.onNodeWithTag("panel-scheme:double_pinyin_flypy").assertIsSelected()
        assertCentered("panel-scheme:double_pinyin_flypy", "profile-scheme-list:list")
        rule.onAllNodesWithTag("apply-input-profile").assertCountEquals(0)
    }

    @Test fun managementKeepsCustomLayoutsWithoutTheObsoleteCombinationOrder() =
        checkManagement(360, 260, 1f, repetitions = 4)

    @Test fun shortLargeTextManagementActionsStayScrollableAndCanReturn() =
        checkManagement(280, 184, 2f, repetitions = 1)

    private fun checkManagement(width: Int, height: Int, fontScale: Float, repetitions: Int) {
        val selections = mutableListOf<String>()
        rule.setContent {
            CompositionLocalProvider(LocalContext provides fixtureContext,
                LocalDensity provides Density(1f, fontScale)) { MaterialTheme {
                SchemaListView(schemas, "t9_pinyin", Color.White, Color.Blue, Color.Black, Color.LightGray,
                    { selections += it }, modifier = Modifier.size(width.dp, height.dp).testTag("schema-panel"))
            } }
        }
        fun assertNoObsoleteManagement() {
            rule.onAllNodes(isPopup()).assertCountEquals(0)
            rule.onNodeWithTag("profile-edit-order").assertDoesNotExist()
            rule.onNodeWithText("组合顺序").assertDoesNotExist()
            rule.onNodeWithTag("profile-more").assertDoesNotExist()
            rule.onNodeWithTag("profile-management").assertDoesNotExist()
            rule.onNodeWithTag("add-layout-tile").assertDoesNotExist()
            rule.onNodeWithTag("profile-management-back").assertDoesNotExist()
        }
        repeat(repetitions) { iteration ->
            assertNoObsoleteManagement()
            for ((row, key, tag) in listOf(
                Triple("profile-layout-list", "layout-management", "profile-layout-management"),
                Triple("profile-scheme-list", "scheme-management", "profile-scheme-management"))) {
                val action = choice(row, key, tag).assertHasClickAction()
                val bounds = action.getUnclippedBoundsInRoot()
                val panel = rule.onNodeWithTag("schema-panel").fetchSemanticsNode().boundsInRoot
                assertTrue("Management remains inside the short panel",
                    bounds.top.value >= panel.top && bounds.bottom.value <= panel.bottom)
                assertTrue(bounds.left.value >= panel.left && bounds.right.value <= panel.right)
                action.performClick()
                assertNoObsoleteManagement()
                // startActivity is intercepted: the fixture stays here, with no back subpage.
                choice("profile-layout-list", "t9", "panel-layout:t9").assertIsSelected()
                choice("profile-scheme-list", "t9_pinyin", "panel-scheme:t9_pinyin").assertIsSelected()
            }
            rule.runOnIdle {
                assertEquals((iteration + 1) * 2, launches.size)
                val layoutIntent = launches[iteration * 2]
                assertEquals("com.kingzcheung.xime.CustomLayoutActivity", layoutIntent.component?.className)
                assertFalse(layoutIntent.hasExtra("create_layout"))
                assertTrue(layoutIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                val schemeIntent = launches[iteration * 2 + 1]
                assertEquals("com.kingzcheung.xime.MainActivity", schemeIntent.component?.className)
                assertEquals("schema", schemeIntent.getStringExtra("open_fragment"))
                assertTrue(schemeIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                assertTrue("Management must not select another combination", selections.isEmpty())
            }
        }
    }

    @Test fun currentCombinationAndExternalChangesNeverDefaultToFirst() {
        val current = mutableStateOf("t9_pinyin")
        themedFixture {
            SchemaListView(schemas, current.value, Color.White, Color.Blue, Color.Black, Color.LightGray,
                {}, modifier = Modifier.size(360.dp, 240.dp))
        }
        rule.onNodeWithTag("panel-layout:t9").assertIsSelected()
        rule.onNodeWithTag("panel-scheme:t9_pinyin").assertIsSelected()
        rule.runOnIdle { current.value = "double_pinyin_flypy" }
        rule.onNodeWithTag("panel-scheme:double_pinyin_flypy").assertIsSelected()
        rule.runOnIdle { current.value = "not_available" }
        rule.onAllNodesWithTag("apply-input-profile").assertCountEquals(0)
    }

    @Test fun backendVariantsDoNotDuplicateTheLayoutChoice() {
        val variants = listOf("double_pinyin_flypy", "double_pinyin_abc", "rime_ice")
            .map { SchemaInfo(it, it, "", "", "") }
        var selected by mutableStateOf(variants.first())
        themedFixture {
            InputProfileSelectors(variants, selected, Modifier.size(360.dp, 300.dp)) { selected = it }
        }
        choice("layout-choice:zh", "qwerty", "layout-choice:zh:option:qwerty", scrollParent = false)
            .assertIsEnabled().assertIsSelected()
        rule.onAllNodesWithTag("layout-choice:zh:option:qwerty").assertCountEquals(1)
        rule.onAllNodesWithTag("variant-choice:zh").assertCountEquals(0)
        choice("scheme-choice:zh", "double_pinyin_abc", "scheme-choice:zh:option:double_pinyin_abc", scrollParent = false).performClick()
        rule.runOnIdle {
            assertEquals("double_pinyin_abc", selected.schemaId)
            assertEquals(InputScheme.DOUBLE_PINYIN, selected.profile.scheme)
            assertEquals(InputLayout.QWERTY.id, selected.profile.layout.id)
        }
        rule.onNodeWithTag("scheme-choice:zh:option:double_pinyin_abc").assertIsSelected()
        choice("layout-choice:zh", "qwerty", "layout-choice:zh:option:qwerty", scrollParent = false).assertIsSelected()
        rule.onAllNodesWithTag("layout-choice:zh:option:qwerty").assertCountEquals(1)
    }
}
