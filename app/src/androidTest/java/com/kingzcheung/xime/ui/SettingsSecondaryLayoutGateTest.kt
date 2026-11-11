package com.kingzcheung.xime.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.InputModes
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.settings.SchemaMeta
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.settings.AboutContent
import com.kingzcheung.xime.ui.settings.IconSettingsContent
import com.kingzcheung.xime.ui.settings.LanguageSettingsContent
import com.kingzcheung.xime.ui.settings.StorageSpaceScreen
import com.kingzcheung.xime.ui.settings.SchemaToggleItem
import com.kingzcheung.xime.ui.menubar.reorderOnLongPress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsSecondaryLayoutGateTest {
    @get:Rule val rule = createComposeRule()

    @Test fun themeStartsWithAppearanceThenPaletteAndMaterialLivesBehindCompactEntry() {
        val size = mutableStateOf(Triple(360, 640, 1f))
        rule.setContent {
            val (width, height, font) = size.value
            CompositionLocalProvider(LocalDensity provides Density(1f, font)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width.dp, height.dp).testTag("theme-host")) {
                        com.kingzcheung.xime.ui.settings.ThemeSettingsContent(onBack = {})
                    }
                }
            }
        }
        rule.onNodeWithText("显示模式").assertIsDisplayed()
        listOf("跟随系统", "浅色", "深色").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("配色方案").assertIsDisplayed()
        assertTrue(rule.onNodeWithText("显示模式").fetchSemanticsNode().boundsInRoot.top <
            rule.onNodeWithText("配色方案").fetchSemanticsNode().boundsInRoot.top)
        rule.assertGeometry("theme-host", "明暗预览优先于配色")
        val context = ApplicationProvider.getApplicationContext<Context>()
        java.io.File(context.getExternalFilesDir(null), "settings-palette.png").outputStream().use {
            rule.onNodeWithTag("theme-host").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        // 视觉样式直接在主题与外观页里，不再单独一页。
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("visual-style-original"))
        rule.onNodeWithTag("visual-style-original").assertIsDisplayed()
        rule.assertGeometry("theme-host", "视觉样式")
        rule.runOnIdle { size.value = Triple(280, 360, 2f) }
        rule.assertGeometry("theme-host", "视觉样式窄屏大字体")
        rule.onNodeWithText("主题与外观").assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("显示模式"))
        rule.assertGeometry("theme-host", "明暗预览窄屏大字体")
    }

    @Test fun practicalSettingsPrecedeDecorations() {
        val page = mutableStateOf(0)
        val route = mutableStateOf<String?>(null)
        val candidateEntry = ApplicationProvider.getApplicationContext<Context>()
            .getString(com.kingzcheung.xime.R.string.item_candidates_title)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(360.dp, 640.dp).testTag("priority-host")) {
                        if (page.value == 0) com.kingzcheung.xime.ui.settings.KeyEffectSettingsContent(onBack = {})
                        else if (route.value == null) com.kingzcheung.xime.ui.settings.SettingsMainContent(
                            onNavigateToSchema = {}, onNavigateToTheme = {}, onNavigateToKeyEffect = {},
                            onNavigateToLayoutDisplay = {}, onNavigateToDictionary = {}, onNavigateToPlugins = {},
                            onNavigateToSmartPrediction = {}, onNavigateToSpeechToText = {}, onNavigateToAbout = {},
                            onNavigateToRoute = { route.value = it }
                        )
                        else com.kingzcheung.xime.ui.settings.LayoutDisplaySettingsContent(
                            onBack = { route.value = null },
                            initialPage = android.net.Uri.parse(route.value).getQueryParameter("page")
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("按键音效").assertIsDisplayed()
        rule.onAllNodesWithText("按键视觉效果").assertCountEquals(0)
        rule.runOnIdle { page.value = 1 }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(candidateEntry))
        rule.onNodeWithText(candidateEntry).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals("${com.kingzcheung.xime.ui.settings.SettingsRoutes.LayoutDisplay}?page=candidates", route.value) }
        rule.onNodeWithText("候选字大小").assertIsDisplayed()
        rule.onAllNodesWithText("键盘底部样式").assertCountEquals(0)
    }

    private data class Viewport(val width: Int, val height: Int, val font: Float)
    private val viewports = listOf(Viewport(280, 360, 2f), Viewport(360, 640, 1f))

    @Test fun aboutPageKeepsLastCardAndStorageEntryReachable() {
        val viewport = mutableStateOf(viewports.first())
        var storageOpens = 0
        rule.setContent {
            val v = viewport.value
            CompositionLocalProvider(LocalDensity provides Density(1f, v.font)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(v.width.dp, v.height.dp).testTag("about-host")) {
                        AboutContent(onBack = {}, onNavigateToPrivacy = {}, onNavigateToLicenses = {},
                            onNavigateToStorageSpace = { storageOpens++ })
                    }
                }
            }
        }
        for (v in viewports) {
            rule.runOnIdle { viewport.value = v }
            rule.assertGeometry("about-host", "关于页顶部 $v")
            assertInside("about-host", rule.onNodeWithText("设备型号").performScrollTo().assertIsDisplayed(),
                "关于页末项 $v")
            rule.assertGeometry("about-host", "关于页末项 $v")
            rule.onNodeWithText("存储空间").performScrollTo().assertIsDisplayed()
                .performTouchInput { click() }
        }
        rule.runOnIdle { assertEquals(viewports.size, storageOpens) }
    }

    @Test fun iconPageKeepsStyleChoiceClickableAfterScrolling() {
        val viewport = mutableStateOf(viewports.first())
        rule.setContent {
            val v = viewport.value
            CompositionLocalProvider(LocalDensity provides Density(1f, v.font)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(v.width.dp, v.height.dp).testTag("icon-host")) {
                        IconSettingsContent(onBack = {}, onChanged = {})
                    }
                }
            }
        }
        for (v in viewports) {
            rule.runOnIdle { viewport.value = v }
            rule.assertGeometry("icon-host", "图标设置顶部 $v")
            val lastChoice = rule.onNodeWithTag("icon-style-original").performScrollTo()
                .assertIsDisplayed().assertHasClickAction()
            assertInside("icon-host", lastChoice, "图标设置末项 $v")
            assertTrue("图标样式点击区高度不足 $v", lastChoice.fetchSemanticsNode().size.height >= 47)
            assertInside("icon-host", rule.onNodeWithText("绑定开启时，恢复原有图标也会恢复原有外观。关闭绑定后可单独恢复图标。")
                .performScrollTo().assertIsDisplayed(), "图标设置末尾说明 $v")
            rule.assertGeometry("icon-host", "图标设置末项 $v")
        }
    }

    @Test fun languagePageKeepsLastDragCardReachable() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "language-layout-gate-${System.nanoTime()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences("$namespace-$name", mode)
        }
        SettingsPreferences.getPrefsPublic(context).edit()
            .putStringSet(LanguagePreferences.KEY, setOf("zh", "ja", "en")).commit()
        InputModes.saveLanguageOrder(context, listOf("zh", "ja", "en"))
        val viewport = mutableStateOf(viewports.first())
        rule.setContent {
            val v = viewport.value
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f, v.font)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(v.width.dp, v.height.dp).testTag("language-host")) {
                        LanguageSettingsContent(onBack = {})
                    }
                }
            }
        }
        for (v in viewports) {
            rule.runOnIdle { viewport.value = v }
            rule.assertGeometry("language-host", "语言管理顶部 $v")
            rule.onNodeWithTag("language-settings-list").performScrollToNode(hasTestTag("language-drag:en"))
            val card = rule.onNodeWithTag("language-drag:en").assertIsDisplayed()
            assertInside("language-host", card, "语言管理末项卡片 $v")
            assertTrue("语言卡片拖动区域高度不足 $v", card.fetchSemanticsNode().size.height >= 71)
            assertTrue("语言卡片拖动区域宽度不足 $v", card.fetchSemanticsNode().size.width >= v.width - 33)
            rule.assertGeometry("language-host", "语言管理末项 $v")
        }
    }

    @Test fun schemeCardsRemainDraggableAndControlsFitNarrowLargeText() {
        val viewport = mutableStateOf(viewports.first())
        var starts = 0
        var drops = 0
        var toggles = 0
        var selects = 0
        rule.setContent {
            val v = viewport.value
            CompositionLocalProvider(LocalDensity provides Density(1f, v.font)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(v.width.dp, v.height.dp).testTag("scheme-order-host")) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            item {
                                SchemaToggleItem(SchemaMeta("rime_ice", "中文26键"), true, true, false,
                                    onToggle = { toggles++ }, onSelect = { selects++ },
                                    reorderModifier = Modifier.reorderOnLongPress(
                                        onStart = { starts++ }, onDrag = {}, onEnd = { drops++ }, onCancel = {}))
                            }
                            item {
                                SchemaToggleItem(SchemaMeta(InputModes.ENGLISH, "英文"), true, true, false,
                                    onToggle = {}, onSelect = {}, isBuiltIn = true,
                                    reorderModifier = Modifier.reorderOnLongPress(
                                        onStart = { starts++ }, onDrag = {}, onEnd = { drops++ }, onCancel = {}))
                            }
                        }
                    }
                }
            }
        }
        for ((index, v) in viewports.withIndex()) {
            rule.runOnIdle { viewport.value = v }
            for (id in listOf("rime_ice", InputModes.ENGLISH)) {
                val card = rule.onNodeWithTag("schema-card:$id").performScrollTo().assertIsDisplayed()
                assertInside("scheme-order-host", card, "输入方案卡片 $id $v")
                assertTrue("输入方案卡片触摸区不足 $id $v", card.fetchSemanticsNode().size.height >= 47)
                card.performTouchInput {
                    down(Offset(24f, center.y))
                    advanceEventTime(600)
                    moveBy(Offset(0f, 20f))
                    up()
                }
            }
            rule.runOnIdle {
                assertEquals("长按拖动不应切换方案 $v", index, selects)
                assertEquals("长按拖动不应触发开关 $v", index, toggles)
            }
            rule.onNodeWithTag("schema-toggle:rime_ice").performScrollTo().performClick()
            rule.onNodeWithTag("schema-card:rime_ice").performScrollTo().performClick()
            rule.onAllNodesWithTag("schema-order-up:rime_ice").assertCountEquals(0)
            rule.onAllNodesWithText("顺序 1").assertCountEquals(0)
            rule.assertGeometry("scheme-order-host", "输入方案排序卡片 $v")
        }
        rule.runOnIdle {
            assertEquals(viewports.size * 2, starts)
            assertEquals(viewports.size * 2, drops)
            assertEquals(viewports.size, toggles)
            assertEquals(viewports.size, selects)
        }
    }

    @Test fun storagePageKeepsLastCategoryAndPluginNavigationReachable() {
        val viewport = mutableStateOf(viewports.first())
        var pluginOpens = 0
        rule.setContent {
            val v = viewport.value
            CompositionLocalProvider(LocalDensity provides Density(1f, v.font)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(v.width.dp, v.height.dp).testTag("storage-host")) {
                        StorageSpaceScreen(onBack = {}, onNavigateToPlugins = { pluginOpens++ })
                    }
                }
            }
        }
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("仅统计应用数据目录，不含应用本体").fetchSemanticsNodes().isNotEmpty()
        }
        for (v in viewports) {
            rule.runOnIdle { viewport.value = v }
            rule.assertGeometry("storage-host", "存储空间顶部 $v")
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
            val lastCategory = rule.onNodeWithTag("storage-category:${com.kingzcheung.xime.settings.StorageStats.ID_OTHER}:description")
                .performScrollTo().assertIsDisplayed()
            assertInside("storage-host", lastCategory, "存储空间末项 $v")
            rule.assertGeometry("storage-host", "存储空间末项 $v")
            rule.onNodeWithText("插件").performScrollTo().assertIsDisplayed().performTouchInput { click() }
        }
        rule.runOnIdle { assertEquals(viewports.size, pluginOpens) }
    }

    private fun assertInside(hostTag: String, node: SemanticsNodeInteraction, scenario: String) {
        val host = rule.onNodeWithTag(hostTag).fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("$scenario 越出页面: $bounds / $host", bounds.left >= host.left - 1f &&
            bounds.right <= host.right + 1f && bounds.top >= host.top - 1f && bounds.bottom <= host.bottom + 1f)
    }
}
