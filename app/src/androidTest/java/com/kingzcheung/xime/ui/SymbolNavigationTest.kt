package com.kingzcheung.xime.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import android.graphics.Bitmap
import com.kingzcheung.xime.ui.keyboard.LocalTextModeLabel
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardPunctuation
import com.kingzcheung.xime.ui.keyboard.KeyboardPunctuation
import com.kingzcheung.xime.ui.keyboard.KeyVisualMetrics
import com.kingzcheung.xime.ui.keyboard.KeyVisualPolicy
import kotlin.math.roundToInt
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardMetricsProbe
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.data.SymbolData
import com.kingzcheung.xime.ui.keyboard.SymbolKeyboardLayout
import com.kingzcheung.xime.ui.keyboard.CommonSymbolKeyboardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SymbolNavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun symbolDeleteRepeatsWhileHeldAndStopsOnReleaseOrCancellation() {
        val actions = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                SymbolKeyboardLayout(onSelect = { actions += it }, onBack = {}, onNumber = {},
                    backgroundColor = Color.Black, textColor = Color.White,
                    accentColor = Color.Blue, keyBgColor = Color.Gray, modifier = Modifier.size(360.dp, 260.dp))
            }
        }
        rule.mainClock.autoAdvance = false
        val key = rule.onNodeWithTag("symbol-delete", true)
        for (cancelled in listOf(false, true)) {
            val start = actions.size
            key.performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(750)
            assertTrue(actions.size > start + 1)
            key.performTouchInput { if (cancelled) cancel() else up() }
            val stopped = actions.size
            rule.mainClock.advanceTimeBy(200)
            assertEquals(stopped, actions.size)
            assertTrue(actions.all { it == "delete" })
        }
    }

    @Test fun largeCategoryComposesVisibleRowsAndStillReachesItsLastSymbol() {
        rule.setContent {
            MaterialTheme {
                SymbolKeyboardLayout(onSelect = {}, onBack = {}, onNumber = {},
                    backgroundColor = Color.Black, textColor = Color.White,
                    accentColor = Color.Blue, keyBgColor = Color.Gray, modifier = Modifier.size(360.dp, 260.dp))
            }
        }
        val category = SymbolData.categories.first { it.id == "graphicalSymbols" }
        rule.onNodeWithTag("symbol-category:${category.id}", true).performScrollTo()
            .performTouchInput { down(center); up() }
        val inGrid = hasAnyAncestor(hasTestTag("symbol-grid:${category.id}"))
        val keys = rule.onAllNodes(inGrid and SemanticsMatcher("symbol grid key") {
            (it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) ?: "")
                .startsWith("symbol-key:")
        }, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Only visible rows may be composed, got ${keys.size} of ${category.symbols.size}",
            keys.isNotEmpty() && keys.size < category.symbols.size / 4)
        val last = hasTestTag("symbol-key:${category.symbols.last()}")
        rule.onNodeWithTag("symbol-grid:${category.id}").performScrollToNode(last)
        rule.onNode(inGrid and last, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun returnStaysAtBottomLeftBeforeTheRecentCategoryStrip() {
        var returns = 0
        var numbers = 0
        rule.setContent {
            MaterialTheme {
                SymbolKeyboardLayout(onSelect = {}, onBack = { returns++ }, onNumber = { numbers++ },
                    backgroundColor = Color.White, textColor = Color.Black,
                    accentColor = Color.Blue, keyBgColor = Color.LightGray,
                    modifier = Modifier.size(360.dp, 240.dp).testTag("symbols"))
            }
        }
        val panel = rule.onNodeWithTag("symbols").fetchSemanticsNode().boundsInRoot
        val backNode = rule.onNodeWithText("中文")
        val back = backNode.fetchSemanticsNode().boundsInRoot
        val recent = rule.onNodeWithText("最近").fetchSemanticsNode().boundsInRoot
        assertTrue(back.center.x < panel.left + panel.width * 0.2f)
        assertTrue(back.center.y > panel.top + panel.height * 0.8f)
        assertTrue(recent.left > back.right)
        backNode.performTouchInput { down(center); up() }
        rule.runOnIdle { assertEquals(1, returns) }
        rule.onNodeWithTag("mode-slot-2", useUnmergedTree = true).performTouchInput { down(center); up() }
        rule.runOnIdle { assertEquals(1, numbers) }
    }

    @Test fun categoryTabsFitFooterOnPhoneAndTabletAndStillScroll() {
        val viewport = mutableStateOf(360.dp to 240.dp)
        var metrics: KeyVisualMetrics? = null
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalTextModeLabel provides "あいう",
                LocalKeyboardMetricsProbe provides { metrics = it },
                LocalKeyboardPunctuation provides KeyboardPunctuation(full = true, japanese = true)) {
                MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                    SymbolKeyboardLayout(onSelect = {}, onBack = {}, onNumber = {},
                        backgroundColor = Color(0xFF191D25), textColor = Color.White,
                        accentColor = Color(0xFFB1C9F5), keyBgColor = Color(0xFF303540),
                        specialKeyBackgroundColor = Color(0xFF475E83),
                        modifier = Modifier.size(viewport.value.first, viewport.value.second).testTag("symbols"))
                }
            }
        }
        for (size in listOf(260.dp to 180.dp, 360.dp to 240.dp, 800.dp to 560.dp)) {
            rule.runOnIdle { viewport.value = size }
            val tags = listOf("mode-slot-1", "mode-slot-2", "symbol-category:common", "symbol-category:recentSymbols", "symbol-delete")
            val bounds = tags.map { rule.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
            listOf(bounds[1], bounds[4]).forEach {
                assertEquals(bounds[0].top, it.top, 1f)
                assertEquals(bounds[0].bottom, it.bottom, 1f)
            }
            val observed = requireNotNull(metrics)
            val declaredGap = com.kingzcheung.xime.settings.KeysConfigHelper.getKeyboardKeyConfig()
                .spacingFor("qwerty").second ?: KeyVisualPolicy.DeclaredDefaultGap
            val sharedInset = (if (declaredGap == KeyVisualPolicy.DeclaredDefaultGap)
                requireNotNull(observed.insetY) else declaredGap / 2f) + observed.extraInsetY
            // This fixture uses density 1: Compose rounds each side separately, not the total gap.
            val expectedCategoryHeight = bounds[0].height - sharedInset.roundToInt() * 2
            listOf(bounds[2], bounds[3]).forEach {
                assertTrue("Categories must fit within the footer", it.height <= bounds[0].height + 1f)
                assertEquals("Category surface must inherit the real shared inset at $size: $observed",
                    expectedCategoryHeight, it.height, 1f)
                assertEquals(bounds[0].center.y, it.center.y, 1f)
            }
            val panel = rule.onNodeWithTag("symbols").fetchSemanticsNode().boundsInRoot
            val keys = rule.onAllNodes(SemanticsMatcher("symbol grid key") {
                (it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) ?: "")
                    .startsWith("symbol-key:")
            }, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
                .filter { it.left >= panel.left && it.right <= panel.right && it.top < bounds[0].top }
            assertTrue("Symbols must be rendered", keys.isNotEmpty())
            assertTrue("Grid uses the panel height instead of tiny squares", keys.first().height >= panel.height * .15f)
            val strip = rule.onNodeWithTag("symbol-category-strip").fetchSemanticsNode().boundsInRoot
            assertEquals(bounds[0].top, strip.top, 1f)
            assertEquals(bounds[0].bottom, strip.bottom, 1f)
            assertTrue("Scrollable content must not cover the number key", strip.left >= bounds[1].right - 1f)
            assertTrue("Scrollable content must not cover delete", strip.right <= bounds[4].left + 1f)
            rule.onNodeWithText("最近使用").assertDoesNotExist()
            val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "symbol-footer-${size.first.value.toInt()}.png")
            file.outputStream().use { rule.onNodeWithTag("symbols").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        rule.onNodeWithTag("symbol-category:punctuationSymbols")
            .performScrollTo().assertTextEquals("全")
        rule.onNodeWithTag("symbol-category:englishSymbols", useUnmergedTree = true).performScrollTo().performTouchInput { down(center); up() }
        rule.onNodeWithTag("symbol-category:englishSymbols", useUnmergedTree = true).assertIsSelected()
    }

    @Test fun commonSymbolPortraitKeepsReturnLeftAndEnterRight() = assertCommonSymbolNavigation(false)

    @Test fun symbolModeKeysKeepStandardQwertyBoundsAfterSwitching() = withLegacyTwoModeKeys { app ->
        val vm = com.kingzcheung.xime.viewmodel.KeyboardViewModel(app)
        vm.setKeyboardState(com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.Chinese)
        var viewport by mutableStateOf(360.dp to 240.dp)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    com.kingzcheung.xime.ui.keyboard.KeyboardView(vm,
                        com.kingzcheung.xime.viewmodel.KeyboardUiState(currentSchemaId = "luna_pinyin"),
                        com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks(
                            onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                        modifier = Modifier.size(viewport.first, viewport.second))
                }
            }
        }
        for (size in listOf(260.dp to 180.dp, 360.dp to 240.dp, 800.dp to 360.dp)) {
            rule.runOnIdle { vm.returnToTextKeyboard(); viewport = size }
            val expected = (1..2).map {
                rule.onNodeWithTag("mode-slot-$it", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            }
            rule.runOnIdle { vm.pushOverlay(com.kingzcheung.xime.keyboard.OverlayRoute.Symbol) }
            expected.forEachIndexed { index, before ->
                val after = rule.onNodeWithTag("mode-slot-${index + 1}", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                assertEquals(before.left, after.left, 1f)
                assertEquals(before.right, after.right, 1f)
                assertEquals(before.top, after.top, 1f)
                assertEquals(before.bottom, after.bottom, 1f)
            }
        }
    }

    @Test fun commonSymbolLandscapeKeepsReturnLeftAndEnterRight() = assertCommonSymbolNavigation(true)

    private fun withLegacyTwoModeKeys(block: (android.app.Application) -> Unit) {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val keys = com.kingzcheung.xime.settings.KeysConfigHelper
        keys.loadConfig(app)
        val fields = listOf("_zhRowsLayout", "_zhRowsBase", "_zhRows").map { name ->
            keys::class.java.getDeclaredField(name).apply { isAccessible = true }
        }
        val previousRows = fields.map { it.get(keys) }
        val activeSchema = keys::class.java.getDeclaredField("_activeSchemaId").apply { isAccessible = true }
        val previousSchema = activeSchema.get(keys) as String
        try {
            keys.setActiveKeyboardSchema("luna_pinyin")
            // Audit the two supported legacy slots, not the default combined ?123 span.
            val rows = keys.getKeyRows(false).take(3)
            fields.first().set(keys, com.kingzcheung.xime.settings.KeyboardRowsLayout(
                rows.map { com.kingzcheung.xime.settings.KeyboardRow(it) }))
            fields.drop(1).forEach { it.set(keys, rows) }
            block(app)
        } finally {
            keys.setActiveKeyboardSchema(previousSchema)
            fields.zip(previousRows).forEach { (field, value) -> field.set(keys, value) }
        }
    }

    private fun assertCommonSymbolNavigation(landscape: Boolean) {
        val actions = mutableListOf<String>()
        rule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = if (landscape) 800 else 360
                screenHeightDp = if (landscape) 360 else 800
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MaterialTheme {
                    CommonSymbolKeyboardLayout(onKeyPress = { actions += it }, isAsciiMode = false,
                        keyBackgroundColor = Color.White, keyTextColor = Color.Black,
                        specialKeyBackgroundColor = Color.LightGray, specialKeyTextColor = Color.Black,
                        shadowEnabled = false, modifier = Modifier.size(360.dp, 240.dp).testTag("common-symbols"))
                }
            }
        }
        val panel = rule.onNodeWithTag("common-symbols").fetchSemanticsNode().boundsInRoot
        val backNode = rule.onNodeWithText("中文")
        val enterNode = rule.onNodeWithContentDescription("回车")
        val back = backNode.fetchSemanticsNode().boundsInRoot
        val enter = enterNode.fetchSemanticsNode().boundsInRoot
        assertTrue(back.center.x < panel.left + panel.width * 0.3f)
        assertTrue(enter.center.x > panel.left + panel.width * 0.7f)
        assertTrue(back.center.y > panel.top + panel.height * 0.8f)
        assertEquals(back.center.y, enter.center.y, 1f)
        backNode.performTouchInput { down(center); up() }
        enterNode.performTouchInput { down(center); up() }
        rule.runOnIdle { assertEquals(listOf("abc", "enter"), actions) }
    }
}
