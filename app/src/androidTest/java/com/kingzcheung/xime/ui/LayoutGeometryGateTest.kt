package com.kingzcheung.xime.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.ui.menubar.ClearClipboardConfirmOverlay
import com.kingzcheung.xime.ui.menubar.ClipboardView
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.Cyletix10Layout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LayoutGeometryGateTest {
    @get:Rule val rule = createComposeRule()

    @Test fun validatorRejectsClippedButtonTextEvenWhenContainerFits() {
        rule.setContent { Box(Modifier.size(280.dp, 120.dp).testTag("fixture")) {
            Text("清空", Modifier.height(6.dp), fontSize = 16.sp)
        } }
        assertTrue("尺寸校验器必须能够拦截文字被压扁", rule.geometryIssues("fixture").isNotEmpty())
    }

    @Test fun validatorRejectsOverlappingActions() {
        val visibility = mutableStateOf(0)
        rule.setContent { Box(Modifier.size(160.dp, 120.dp).testTag("overlap-fixture")) {
            Box(Modifier.size(80.dp, 48.dp).testTag("first").clickable {})
            val semantics = when (visibility.value) {
                1 -> Modifier.clearAndSetSemantics {}
                2 -> Modifier.clearAndSetSemantics {
                    contentDescription = "combined second"
                    onClick { true }
                }
                else -> Modifier
            }
            Box(semantics) {
                Box(Modifier.offset(40.dp, 0.dp).size(80.dp, 48.dp).testTag("second").clickable {})
            }
        } }
        assertTrue(rule.geometryIssues("overlap-fixture").any { it.contains("相互遮挡") })
        rule.runOnIdle { visibility.value = 1 }
        assertFalse("隐藏子树不再是活动操作", rule.geometryIssues("overlap-fixture").any { it.contains("相互遮挡") })
        rule.runOnIdle { visibility.value = 2 }
        assertTrue("清除语义节点自身的活动按钮仍必须校验", rule.geometryIssues("overlap-fixture").any { it.contains("相互遮挡") })
    }

    @Test fun clearConfirmationMatrixKeepsActionsReadableAndClickable() {
        data class Viewport(val width: Int, val height: Int, val font: Float)
        val viewport = mutableStateOf(Viewport(280, 120, 1f))
        var cancelled = 0
        var confirmed = 0
        rule.setContent {
            val v = viewport.value
            CompositionLocalProvider(LocalDensity provides Density(1f, v.font)) {
                MaterialTheme { Box(Modifier.requiredSize(v.width.dp, v.height.dp).testTag("confirmation-host")) {
                    key(v) { ClearClipboardConfirmOverlay(12345, Color.LightGray, Color.White,
                        Color.Black, Color.DarkGray, { cancelled++ }, { confirmed++ }) }
                } }
            }
        }
        for (width in listOf(240, 360, 800)) for (height in listOf(104, 140, 220, 360)) for (font in listOf(1f, 1.3f, 2f)) {
            val v = Viewport(width, height, font)
            rule.runOnIdle { viewport.value = v }
            val issues = rule.geometryIssues("confirmation-host", allowViewportClipping = false)
            assertTrue("$v\n${issues.joinToString("\n")}", issues.isEmpty())
            for (tag in listOf("clipboard-clear-cancel", "clipboard-clear-confirm")) {
                val action = rule.onNodeWithTag(tag)
                val rect = action.fetchSemanticsNode().boundsInRoot
                assertTrue("$v $tag 应至少 48dp 高: $rect", rect.height >= 47f)
                action.assertIsDisplayed().performClick()
            }
        }
        assertEquals(36, cancelled)
        assertEquals(36, confirmed)
    }

    @Test fun actualClipboardRouteUsesWholePanelForConfirmationAndCancelPreservesItems() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = KeyboardViewModel(app)
        val size = mutableStateOf(280 to 140)
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
            MaterialTheme { Box(Modifier.requiredSize(size.value.first.dp, size.value.second.dp).testTag("clipboard-host")) {
                ClipboardView(listOf(ClipboardItem(1, "保留的内容")), emptyList(), 0,
                    Color.LightGray, Color.Black, Color.White, vm, {}, { _, _ -> })
            } }
        } }
        for (v in listOf(280 to 140, 360 to 200, 800 to 180)) {
            rule.runOnIdle { size.value = v }
            rule.onNodeWithTag("clipboard-more").performClick()
            rule.onNodeWithTag("clipboard-clear").performScrollTo().performClick()
            rule.waitForIdle()
            val issues = rule.geometryIssues("clipboard-clear-overlay", allowViewportClipping = false)
            assertTrue("实际剪贴板 $v\n${issues.joinToString("\n")}", issues.isEmpty())
            val host = rule.onNodeWithTag("clipboard-host").fetchSemanticsNode().boundsInRoot
            val overlay = rule.onNodeWithTag("clipboard-clear-overlay").fetchSemanticsNode().boundsInRoot
            assertEquals(host, overlay)
            val folder = java.io.File(app.getExternalFilesDir(null), "layout-gate").apply { mkdirs() }
            java.io.File(folder, "clipboard-${v.first}-${v.second}.png").outputStream().use {
                rule.onNodeWithTag("clipboard-host").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            rule.onNodeWithTag("clipboard-clear-cancel").performClick()
            rule.onNodeWithText("保留的内容").assertExists()
            rule.onNodeWithTag("clipboard-clear-overlay").assertDoesNotExist()
        }
    }

    @Test fun clipboardPreviewPillShrinksForShortTextAndScrollsLongText() {
        data class Scenario(val width: Int, val font: Float, val dark: Boolean, val text: String)
        val shortText = "你好"
        val longText = "剪贴板预览必须保留完整内容，并能横向滚动到最后。".repeat(8) + "\n终点"
        val current = mutableStateOf(Scenario(280, 1f, false, shortText))
        val events = mutableListOf<String>()
        val lightPill = Color(0xFFE4EDF2)
        val darkPill = Color(0xFF323D51)
        rule.setContent {
            val scenario = current.value
            CompositionLocalProvider(LocalDensity provides Density(1f, scenario.font)) {
                MaterialTheme {
                    Box(Modifier.requiredWidth(scenario.width.dp).testTag("clipboard-preview-host")) {
                        key(scenario) {
                            CandidateBar(
                                state = CandidateBarState.ClipboardDisplay(listOf(scenario.text)),
                                visuals = CandidateBarVisuals(
                                    backgroundColor = if (scenario.dark) Color(0xFF171C27) else Color.White,
                                    textColor = if (scenario.dark) Color.White else Color.Black,
                                    dividerColor = Color.Gray,
                                    accentColor = if (scenario.dark) Color(0xFF9CCAFF) else Color(0xFF3269B5),
                                    isDarkTheme = scenario.dark,
                                    preeditBackgroundColor = if (scenario.dark) darkPill else lightPill,
                                ),
                                callbacks = CandidateBarCallbacks(
                                    onCandidateSelect = { events += "paste:$it" },
                                    onDismissClipboardPreview = { events += "dismiss" },
                                    onOpenClipboard = { events += "open" },
                                    onHideKeyboard = { events += "hide" },
                                ),
                            )
                        }
                    }
                }
            }
        }

        val shortWidths = mutableMapOf<Triple<Int, Float, Boolean>, Float>()
        val longScenarios = listOf(
            Scenario(280, 1f, false, longText), Scenario(280, 2f, true, longText),
            Scenario(360, 1.3f, true, longText), Scenario(800, 2f, false, longText),
        )
        // Build the 280/360/800dp by 1/1.3/2x font matrix for both color schemes.
        for (dark in listOf(false, true)) for (font in listOf(1f, 1.3f, 2f)) {
            val widthsAtThisFont = mutableListOf<Float>()
            for (width in listOf(280, 360, 800)) {
                val scenario = Scenario(width, font, dark, shortText)
                rule.runOnIdle { current.value = scenario }
                val issues = rule.geometryIssues("clipboard-preview-host", allowViewportClipping = false)
                assertTrue("短复制预览 $scenario\n${issues.joinToString("\n")}", issues.isEmpty())
                val host = rule.onNodeWithTag("clipboard-preview-host").fetchSemanticsNode().boundsInRoot
                val pill = rule.onNodeWithTag("clipboard-preview-pill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val back = rule.onNodeWithContentDescription("返回工具栏").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val hide = rule.onNodeWithContentDescription("收起键盘").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val open = rule.onNodeWithContentDescription("打开剪贴板").fetchSemanticsNode().boundsInRoot
                val scroll = rule.onNodeWithTag("clipboard-preview-scroll", useUnmergedTree = true)
                val scrollBounds = scroll.fetchSemanticsNode().boundsInRoot
                val text = rule.onNodeWithTag("clipboard-preview-text:0", useUnmergedTree = true)
                val textBounds = text.fetchSemanticsNode().boundsInRoot
                assertTrue("$scenario 胶囊应明显窄于两侧按钮间的空间: $pill / $host",
                    pill.width < (host.width - back.width - hide.width) * 0.75f)
                assertEquals("$scenario 胶囊应居于整个键盘中央", host.center.x, pill.center.x, 2f)
                assertTrue("$scenario 胶囊不得盖住左右按钮或越界",
                    back.left >= host.left && back.right <= pill.left && pill.right <= hide.left && hide.right <= host.right)
                assertTrue("$scenario 文字和入口应在胶囊内", textBounds.top >= pill.top - 1f &&
                    textBounds.bottom <= pill.bottom + 1f && open.right <= pill.right + 1f)
                assertTrue("$scenario 点击区不得相叠", scrollBounds.right <= open.left + 1f)
                assertEquals("$scenario 短文字应在预览区居中", scrollBounds.center.x, textBounds.center.x, 4f)
                val layouts = mutableListOf<TextLayoutResult>()
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(shortText, layouts.single().layoutInput.text.text)
                assertTrue("$scenario 真实文字高度被裁切", layouts.single().getLineBottom(0) <= layouts.single().size.height + 1f)
                if (width == 360 && font == 1f) {
                    val pixels = rule.onNodeWithTag("clipboard-preview-pill", useUnmergedTree = true).captureToImage().toPixelMap()
                    val actual = pixels[pixels.width / 2, pixels.height - 5]
                    val expected = if (dark) darkPill else lightPill
                    assertEquals("$scenario 胶囊背景应取当前主题", expected.red, actual.red, 0.04f)
                    assertEquals(expected.green, actual.green, 0.04f)
                    assertEquals(expected.blue, actual.blue, 0.04f)
                }
                scroll.performTouchInput { down(center); up() }
                rule.onNodeWithContentDescription("打开剪贴板").performClick()
                rule.onNodeWithContentDescription("返回工具栏").performClick()
                rule.onNodeWithContentDescription("收起键盘").performClick()
                rule.runOnIdle {
                    assertEquals("$scenario 点击行为", listOf("paste:0", "open", "dismiss", "hide"), events.toList())
                    events.clear()
                }
                shortWidths[Triple(width, font, dark)] = pill.width
                widthsAtThisFont += pill.width
            }
            assertTrue("$font 倍字体的短文胶囊不应随屏幕变宽", widthsAtThisFont.maxOrNull()!! - widthsAtThisFont.minOrNull()!! <= 12f)
        }

        for (scenario in longScenarios) {
            rule.runOnIdle { current.value = scenario }
            val issues = rule.geometryIssues("clipboard-preview-host")
            assertTrue("长复制预览 $scenario\n${issues.joinToString("\n")}", issues.isEmpty())
            val host = rule.onNodeWithTag("clipboard-preview-host").fetchSemanticsNode().boundsInRoot
            val pill = rule.onNodeWithTag("clipboard-preview-pill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val back = rule.onNodeWithContentDescription("返回工具栏").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val hide = rule.onNodeWithContentDescription("收起键盘").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val open = rule.onNodeWithContentDescription("打开剪贴板").fetchSemanticsNode().boundsInRoot
            val scroll = rule.onNodeWithTag("clipboard-preview-scroll", useUnmergedTree = true)
            val text = rule.onNodeWithTag("clipboard-preview-text:0", useUnmergedTree = true)
            val textBounds = text.fetchSemanticsNode().boundsInRoot
            assertEquals("$scenario 长文胶囊应居于整个键盘中央", host.center.x, pill.center.x, 2f)
            assertTrue("$scenario 左右按钮与胶囊不得重叠",
                back.left >= host.left && back.right <= pill.left && pill.right <= hide.left && hide.right <= host.right)
            assertTrue("$scenario 长文应展开可用宽度", pill.width > (hide.left - back.right) * 0.7f &&
                pill.width > shortWidths.getValue(Triple(scenario.width, scenario.font, scenario.dark)))
            assertTrue("$scenario 预览与打开入口不得重叠", scroll.fetchSemanticsNode().boundsInRoot.right <= open.left + 1f)
            assertTrue("$scenario 长文文字不得上下裁切", textBounds.top >= pill.top - 1f && textBounds.bottom <= pill.bottom + 1f)
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(longText.replace("\n", " "), layouts.single().layoutInput.text.text)
            assertFalse("$scenario 长文不得用省略号截断", layouts.single().isLineEllipsized(0))
            assertTrue("$scenario 真实文字高度被裁切", layouts.single().getLineBottom(0) <= layouts.single().size.height + 1f)
            val range = scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue("$scenario 长文应可横向滚动", range.maxValue() > 0f)
            scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(100_000f, 0f) }
            assertEquals("$scenario 末尾应能滚入视口", scroll.fetchSemanticsNode().boundsInRoot.right,
                text.fetchSemanticsNode().boundsInRoot.right, 3f)
            scroll.performTouchInput { down(center); up() }
            rule.onNodeWithContentDescription("打开剪贴板").performClick()
            rule.onNodeWithContentDescription("返回工具栏").performClick()
            rule.onNodeWithContentDescription("收起键盘").performClick()
            rule.runOnIdle {
                assertEquals("$scenario 点击行为", listOf("paste:0", "open", "dismiss", "hide"), events.toList())
                events.clear()
            }
        }
    }

    @Test fun mainKeyboardFamiliesFitPhoneTabletAndCompactPanels() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        val vm = KeyboardViewModel(app)
        val schema = mutableStateOf("rime_ice")
        val size = mutableStateOf(360 to 220)
        val font = mutableStateOf(1f)
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, font.value)) {
            MaterialTheme {
                KeyboardView(vm, KeyboardUiState(currentSchemaId = schema.value),
                    KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                    modifier = Modifier.requiredSize(size.value.first.dp, size.value.second.dp).testTag("keyboard-family"))
            }
        } }
        val modes = listOf(KeyboardLayoutState.Chinese to "rime_ice", KeyboardLayoutState.English to "rime_ice",
            KeyboardLayoutState.T9Pinyin to "t9_pinyin", KeyboardLayoutState.Number to "rime_ice",
            KeyboardLayoutState.CommonSymbol to "rime_ice", KeyboardLayoutState.Stroke to "stroke",
            KeyboardLayoutState.Chinese to "handwriting", KeyboardLayoutState.Chinese to "pinyin_14jian",
            KeyboardLayoutState.Chinese to "japanese", KeyboardLayoutState.Chinese to "japanese_kana")
        val failures = mutableListOf<String>()
        for (viewport in listOf(280 to 220, 360 to 220, 800 to 180)) for (scale in listOf(1f, 1.3f)) for ((mode, id) in modes) {
            rule.runOnIdle { size.value = viewport; font.value = scale; schema.value = id }
            // Schema synchronization is asynchronous. Select the requested page only
            // after it settles, so the audit cannot accidentally inspect the old page.
            rule.waitForIdle()
            val mainType = if (id == "handwriting") com.kingzcheung.xime.keyboard.MainType.HANDWRITING
                else com.kingzcheung.xime.keyboard.MainType.FULL
            rule.runOnIdle { vm.switchMain(mainType); vm.setKeyboardState(mode) }
            rule.waitForIdle()
            rule.runOnIdle {
                assertEquals(mode, vm.keyboardState.value)
                assertEquals(com.kingzcheung.xime.keyboard.KeyboardPage.Main(mainType), vm.page.value)
            }
            failures += rule.geometryIssues("keyboard-family").map { "$mode $viewport font=$scale: $it" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun cyletix10MobileRowsFitExtremeSizesAndKeepPunctuationClickable() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        val vm = KeyboardViewModel(app)
        data class Viewport(val width: Int, val height: Int, val font: Float)
        val viewport = mutableStateOf(Viewport(360, 220, 1f))
        val pressed = mutableListOf<String>()
        rule.setContent {
            val v = viewport.value
            CompositionLocalProvider(LocalDensity provides Density(1f, v.font)) {
                MaterialTheme {
                    KeyboardLayout(
                        onKeyPress = { pressed += it }, viewModel = vm,
                        callbacks = KeyboardCallbacks(onKeyPress = { key, _ -> pressed += key }, onCandidateSelect = {}),
                        uiState = KeyboardUiState(currentSchemaId = Cyletix10Layout.ID), isAsciiMode = false,
                        modifier = Modifier.requiredSize(v.width.dp, v.height.dp).testTag("cyletix10-keyboard"),
                    )
                }
            }
        }
        val failures = mutableListOf<String>()
        for (v in listOf(Viewport(280, 220, 1f), Viewport(280, 220, 2f),
            Viewport(360, 220, 1.3f), Viewport(800, 180, 2f))) {
            rule.runOnIdle { viewport.value = v }
            failures += rule.geometryIssues("cyletix10-keyboard", allowViewportClipping = false)
                .map { "$v: $it" }
            val keyboard = rule.onNodeWithTag("cyletix10-keyboard", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            for (row in Cyletix10Layout.rows) {
                val bounds = row.map { key ->
                    val rect = rule.onNodeWithTag("qwerty-key:$key", useUnmergedTree = true)
                        .fetchSemanticsNode().boundsInRoot
                    assertTrue("$v qwerty-key:$key 应在键盘内: $rect / $keyboard",
                        rect.width > 0f && rect.height > 0f && rect.left >= keyboard.left - 1f &&
                            rect.right <= keyboard.right + 1f && rect.top >= keyboard.top - 1f &&
                            rect.bottom <= keyboard.bottom + 1f)
                    rect
                }
                assertTrue("$v 原始字母和分号顺序", bounds.zipWithNext().all { (a, b) -> a.right <= b.left + 1f })
            }
            // The 800dp synthetic keyboard extends beyond the emulator window; touch only
            // the 280dp cases, while checking all keys against their own viewport above.
            if (v.width == 280) {
                rule.onNodeWithTag("qwerty-key:;", useUnmergedTree = true)
                    .assertIsDisplayed().performClick()
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertEquals(List(2) { ";" }, pressed)
    }
}
