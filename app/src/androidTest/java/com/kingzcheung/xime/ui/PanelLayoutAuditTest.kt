package com.kingzcheung.xime.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.keyboard.*
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.viewmodel.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PanelLayoutAuditTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun portraitPanelsAtLowHeight() = checkPanels(360, 220, false, 1f)
    @Test fun narrowPanelsWithTransparencyAndLargeText() = checkPanels(280, 220, false, 1.3f)
    @Test fun landscapePanelsAtLowHeight() = checkPanels(640, 180, true, 1f)

    private fun checkPanels(width: Int, height: Int, landscape: Boolean, font: Float) {
        val vm = KeyboardViewModel(app)
        val schemas = listOf("rime_ice" to "中文26键", "t9_pinyin" to "中文九键",
            "double_pinyin_flypy" to "小鹤双拼", "japanese" to "日语26键", "japanese_kana" to "日语九宫格")
            .map { (id, name) -> SchemaInfo(id, name, "", "", "") }
        rule.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = if (landscape) 640 else 360
                screenHeightDp = if (landscape) 360 else 800
            }
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(1f, font)) {
                MaterialTheme {
                    Box(Modifier.background(Color.White)) {
                        KeyboardView(vm, KeyboardUiState(currentSchemaId = "rime_ice", isDarkTheme = true, schemas = schemas,
                            clipboardItems = (1..8).map { ClipboardItem(it.toLong(), "复制内容 $it") },
                            quickSendItems = listOf(ClipboardItem(9, "常用短语")), toolPanelTitle = "插件面板"),
                            KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                            modifier = Modifier.size(width.dp, height.dp).graphicsLayer { alpha = 0.5f }.testTag("audit-keyboard"))
                    }
                }
            }
        }
        rule.waitForIdle()
        val issues = mutableListOf<String>()
        val routes = listOf(OverlayRoute.Menu, OverlayRoute.SchemaList, OverlayRoute.Edit, OverlayRoute.Emoji,
            OverlayRoute.Clipboard(0), OverlayRoute.Clipboard(1), OverlayRoute.ToolbarCustomize,
            OverlayRoute.SplitWords("这是一个拆词测试，包含标点与多个可以选择的文字。"), OverlayRoute.Symbol, OverlayRoute.ToolPanel)
        routes.forEachIndexed { index, route ->
            rule.runOnIdle { vm.closeOverlay(); vm.showOverlay(route) }
            val keyboard = rule.onNodeWithTag("audit-keyboard").fetchSemanticsNode().boundsInRoot
            val toolbar = rule.onNodeWithTag("toolbar-order-row").fetchSemanticsNode().boundsInRoot
            val panel = rule.onNodeWithTag("keyboard-overlay").fetchSemanticsNode().boundsInRoot
            if (panel.top < toolbar.bottom || panel.bottom > keyboard.bottom) issues += "$route 面板越过工具栏或底边"
            val exitLabel = when (route) {
                OverlayRoute.ToolbarCustomize -> "完成"
                OverlayRoute.ToolPanel -> "关闭"
                OverlayRoute.Symbol -> "返回键盘"
                else -> "返回"
            }
            val backs = rule.onAllNodesWithContentDescription(exitLabel).fetchSemanticsNodes()
            if (backs.size != 1) issues += "$route $exitLabel 入口数量 ${backs.size}"
            if (route == OverlayRoute.Edit) {
                val delete = rule.onNodeWithContentDescription("删除").fetchSemanticsNode().boundsInRoot
                if (delete.top - panel.top > 3f || panel.right - delete.right > 5f) issues += "编辑回格未贴右上角"
            }
            if (route == OverlayRoute.SchemaList) {
                rule.onAllNodesWithTag("apply-input-profile").assertCountEquals(0)
                for (tag in listOf("profile-layout-list", "profile-scheme-list")) {
                    val choice = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    if (choice.top < panel.top || choice.bottom > panel.bottom ||
                        choice.left < panel.left || choice.right > panel.right) issues += "$tag 选择行超出面板"
                }
            }
            save("panel-$width-$index")
            issues += rule.geometryIssues("audit-keyboard").map { "$route $width x $height font=$font: $it" }
        }
        assertTrue(issues.joinToString("\n"), issues.isEmpty())
    }

    private data class ResizeScenario(
        val width: Int,
        val height: Int,
        val floating: Boolean,
        val landscape: Boolean,
        val fontScale: Float,
    )

    @Test fun resizeOpacityControlFitsInsideFrameAndStaysClearOfEdgeHandles() {
        val scenarios = listOf(
            ResizeScenario(280, 180, false, false, 1.3f),
            ResizeScenario(280, 180, false, false, 2f),
            ResizeScenario(360, 220, false, false, 1f),
            ResizeScenario(640, 140, false, true, 1f),
            ResizeScenario(640, 180, false, true, 1.3f),
            ResizeScenario(360, 220, true, false, 1.3f),
            ResizeScenario(640, 228, true, true, 2f),
            ResizeScenario(1200, 300, false, true, 1f),
            ResizeScenario(600, 220, false, true, 1.5f),
            ResizeScenario(1200, 220, false, true, 2f),
        )
        val current = mutableStateOf(scenarios.first())
        rule.setContent {
            val scenario = current.value
            val config = Configuration(LocalConfiguration.current).apply {
                orientation = if (scenario.landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = if (scenario.landscape) 800 else 360
                screenHeightDp = if (scenario.landscape) 360 else 800
            }
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(1f, scenario.fontScale)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(scenario.width.dp, scenario.height.dp).testTag("resize-layout-host")) {
                        key(scenario) {
                            KeyboardResizeOverlay(scenario.height, scenario.height, 0, scenario.floating,
                                onHeightChange = {}, onBottomPaddingChange = {}, onOpacityChange = {}, onReset = {},
                                onConfirm = { _, _, _, _ -> }, onCancel = {}, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        for (scenario in scenarios) {
            rule.runOnIdle { current.value = scenario }
            val frame = rule.onNodeWithTag("keyboard-resize-frame", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val controls = rule.onNodeWithTag("keyboard-resize-controls", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val panel = rule.onNodeWithTag("keyboard-resize-opacity-panel", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val sliderNode = rule.onNodeWithTag("keyboard-opacity-slider", useUnmergedTree = true)
            sliderNode.performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }
            val slider = sliderNode.fetchSemanticsNode().boundsInRoot
            val labelNode = rule.onNodeWithTag("keyboard-opacity-overlay-label", useUnmergedTree = true)
            labelNode.assertIsDisplayed()
            val label = labelNode.fetchSemanticsNode().boundsInRoot
            val buttons = listOf("重置", "悬浮键盘", "分体键盘", "确认")
                .map { rule.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot }
            val actions = rule.onNodeWithTag("keyboard-resize-actions", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val case = "$scenario frame=$frame controls=$controls panel=$panel slider=$slider label=$label"

            assertTrue("$case 透明度面板应在控件区域内", panel.left >= controls.left - 1f &&
                panel.right <= controls.right + 1f && panel.top >= controls.top - 1f && panel.bottom <= controls.bottom + 1f)
            assertTrue("$case 滑条和按钮应避开左右 26dp 调节热区",
                panel.left >= frame.left + 30f && panel.right <= frame.right - 30f &&
                    actions.left >= frame.left + 30f && actions.right <= frame.right - 30f)
            assertEquals("$case 滑条和按钮组应等宽", actions.width, panel.width, 1f)
            assertEquals("$case 滑条和按钮组应居中对齐", actions.center.x, panel.center.x, 1f)
            assertTrue("$case 宽屏按钮过长", buttons.all { it.width <= 140f })
            assertTrue("$case 按钮超出控件区域", buttons.all {
                it.top >= controls.top && it.bottom <= controls.bottom + 1f
            })
            if (scenario.width >= 600) {
                assertTrue("$case 平板滑条不应仍停留在手机宽度", slider.width >= 480f)
            }
            assertTrue("$case 进度条应在透明度面板内", slider.left >= panel.left - 1f &&
                slider.right <= panel.right + 1f && slider.top >= panel.top - 1f && slider.bottom <= panel.bottom + 1f)
            assertTrue("$case 透明度文字应完整叠在进度条中", label.left >= slider.left - 1f &&
                label.right <= slider.right + 1f && label.top >= slider.top - 1f && label.bottom <= slider.bottom + 1f)
            assertEquals("$case 透明度文字应水平居中", slider.center.x, label.center.x, 2f)
            assertEquals("$case 透明度文字应垂直居中", slider.center.y, label.center.y, 2f)
            val layouts = mutableListOf<TextLayoutResult>()
            labelNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val text = layouts.single()
            assertEquals("$case 透明度和百分比应显示完整", "透明度 70%", text.layoutInput.text.text)
            assertEquals("$case 透明度文字应仅占一行", 1, text.lineCount)
            assertFalse("$case 透明度文字被裁切: size=${text.size}, width=${text.didOverflowWidth}, height=${text.didOverflowHeight}, paragraphWidth=${text.multiParagraph.width}, maxWidth=${text.layoutInput.constraints.maxWidth}, lineRight=${text.getLineRight(0)}, lineEnd=${text.getLineEnd(0, true)}/${text.layoutInput.text.length}, lineBottom=${text.getLineBottom(0)}", text.hasVisualOverflow)
            assertTrue("$case 文字真实行高超过分配高度", text.getLineBottom(0) <= text.size.height + 1f)
            assertTrue("$case 透明度不得与底部按钮相叠", panel.bottom <= buttons.minOf { it.top })
            buttons.zipWithNext().forEach { (left, right) ->
                assertTrue("$case 底部操作区域重叠：$left / $right", left.right <= right.left)
            }
        }
    }

    private fun save(name: String) {
        val dir = File(app.getExternalFilesDir(null), "panel-audit").apply { mkdirs() }
        val bitmap = rule.onNodeWithTag("audit-keyboard").captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
