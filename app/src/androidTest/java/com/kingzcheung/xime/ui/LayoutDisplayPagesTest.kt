package com.kingzcheung.xime.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.ui.settings.LayoutDisplayPages
import com.kingzcheung.xime.ui.settings.LayoutDisplaySettingsContent
import com.kingzcheung.xime.ui.settings.LayoutInputCategory
import com.kingzcheung.xime.ui.settings.LayoutInputSettingsContent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 「键盘布局」页：各布局入口、自定义布局、按键符号、分体键盘、数字框。候选词、按键与手势从首页直接进入，返回回首页。
 *  14 键、九键、假名提示和拂动圆盘合在「九键与 14 键」一页。 */
class LayoutDisplayPagesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun layoutIndexHoldsLayoutsSymbolsSplitAndNumberKeyboard() {
        rule.setContent { MaterialTheme { LayoutDisplaySettingsContent(onBack = {}) } }
        rule.onNodeWithText("键盘布局").assertExists()
        rule.onNodeWithText("九键与 14 键").assertExists()
        rule.onNodeWithText("自定义布局").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("按键符号").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("分体键盘").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("layout-display-list")
            .performScrollToNode(hasText("数字框自动数字键盘"))
        rule.onNodeWithText("数字框自动数字键盘").assertIsDisplayed()
        save("index")
    }

    @Test fun pagesOpenedFromHomeGoBackHome() {
        val route = androidx.compose.runtime.mutableStateOf(LayoutDisplayPages.first().first)
        var backs = 0
        rule.setContent {
            MaterialTheme {
                androidx.compose.runtime.key(route.value) {
                    LayoutDisplaySettingsContent(onBack = { backs++ }, initialPage = route.value)
                }
            }
        }
        for ((id, title, _) in LayoutDisplayPages) {
            rule.runOnIdle { route.value = id; backs = 0 }
            rule.waitForIdle()
            assertTrue("page $title did not open",
                rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
            rule.waitForIdle()
            save("page-$title")
            rule.onNodeWithContentDescription("返回").performClick()
            rule.runOnIdle { assertEquals("$title 返回首页", 1, backs) }
        }
    }

    @Test fun nineKeyPageHoldsFourteenKeyFlickAndKanaSettings() {
        rule.setContent { MaterialTheme { LayoutInputSettingsContent(LayoutInputCategory.Nine, onBack = {}) } }
        rule.onNodeWithText("拂动选字母").assertExists()
        rule.onNodeWithText("左右滑动选字母").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("fourteen-letter-distance").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("flick-direction-disc").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("kana-direction-hints").performScrollTo().assertIsDisplayed()
        save("page-nine-fourteen")
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "layout-display-pages").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
}
