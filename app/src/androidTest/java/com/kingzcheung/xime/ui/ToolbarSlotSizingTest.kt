package com.kingzcheung.xime.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 菜单、收起与中间工具图标同为等宽一格；拖动外框与按钮胶囊同尺寸。图片存到应用外部目录供人工查看。 */
class ToolbarSlotSizingTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun show(width: Int) {
        KeysConfigHelper.loadConfig(app)
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
        rule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = width; screenHeightDp = 1400; orientation = Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalConfiguration provides configuration) {
                MaterialTheme {
                    Box(Modifier.requiredSize((width + 20).dp, 420.dp).testTag("toolbar-host"), contentAlignment = Alignment.Center) {
                        val vm = remember { KeyboardViewModel(app) }
                        KeyboardView(vm, KeyboardUiState(currentSchemaId = "rime_ice", keyboardHeightDp = 380,
                            keyboardWidthDp = width, fixedWidthDp = width, isDarkTheme = true),
                            KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {},
                                onHideKeyboard = {}, onUpdateToolbarButtons = {}),
                            modifier = Modifier.size(width.dp, 380.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun save(name: String) {
        val bitmap = rule.onNodeWithTag("toolbar-host").captureToImage().asAndroidBitmap()
        val dir = File(app.getExternalFilesDir(null), "toolbar-visual").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun check(width: Int, name: String) {
        show(width)
        val leading = bounds("toolbar-leading")
        val hide = bounds("toolbar-hide")
        val slots = rule.onAllNodesWithTag("toolbar-slot", useUnmergedTree = true).fetchSemanticsNodes()
        // 菜单、收起与中间每一格同宽。
        assertEquals("menu and hide share the slot width", leading.width, hide.width, 1f)
        // 取布局宽度：放不下时工具行横向滚动，可见范围会裁掉最后一格。
        slots.forEach { assertEquals("middle icons use the same slot width", leading.width, it.size.width.toFloat(), 1f) }
        // 键盘在宿主里居中，左右各留 10dp：收起按钮的格子不能伸出键盘面板。
        assertTrue("toolbar stays inside the panel: ${hide.right} > ${width + 10}", hide.right <= width + 10.5f)
        assertTrue("menu stays inside the panel", leading.left >= 9.5f)
        save("$name-idle")
        // 长按两秒进入拖动：外框与按钮胶囊同尺寸。
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("toolbar-order-row").performTouchInput { down(centerLeft.copy(x = leading.width / 2f)) }
        rule.mainClock.advanceTimeBy(2100L)
        rule.waitForIdle()
        val outline = bounds("toolbar-drag-outline")
        val draggedSlot = hasTestTag("toolbar-slot") and hasAnyDescendant(hasTestTag("toolbar-drag-outline"))
        val pill = rule.onNode(hasTestTag("toolbar-pill") and hasAnyAncestor(draggedSlot), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals("Outline must match the actual pill in its own slot", pill.width, outline.width, 1f)
        assertEquals("Outline must match the actual pill in its own slot", pill.height, outline.height, 1f)
        save("$name-dragging")
        rule.onNodeWithTag("toolbar-order-row").performTouchInput { up() }
        rule.mainClock.autoAdvance = true
    }

    @Test fun tabletWidthSlotsAndOutlineMatch() = check(940, "tablet")

    @Test fun phoneWidthSlotsAndOutlineMatch() = check(400, "phone")

    /** 悬浮小窗（约屏宽四分之一）：格子收窄，按钮底板不再溢出压住面板边框。 */
    @Test fun floatingSmallWidthStaysInsidePanel() = check(260, "floating-small")
}
