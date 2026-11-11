package com.kingzcheung.xime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.InputModes

/** Follow the two-level navigation instead of relying on every mode being in the globe menu. */
internal fun ComposeTestRule.chooseModeThroughLanguageAndPanel(id: String) {
    mainClock.autoAdvance = true
    waitForIdle()
    fun expandScreenKeyboard() {
        val expand = onAllNodesWithTag("hardware-toolbar-keyboard", useUnmergedTree = true)
        if (expand.fetchSemanticsNodes().isNotEmpty()) {
            expand.onFirst().performClick()
            waitUntil(10_000) { expand.fetchSemanticsNodes().isEmpty() }
            waitForIdle()
        }
        // A prior cut/copy can replace the toolbar with a clipboard preview.
        val back = onAllNodesWithContentDescription("返回工具栏")
        if (back.fetchSemanticsNodes().isNotEmpty()) {
            back.onFirst().performClick()
            waitForIdle()
        }
    }
    expandScreenKeyboard()
    val engine = RimeEngine.getInstance()
    val language = InputModes.languageOf(id)
    val globe = onAllNodesWithTag("language-key-control", useUnmergedTree = true).onLast()
    globe.performTouchInput { down(center) }
    waitUntil(5000) { onAllNodesWithContentDescription("选择${language.displayName}").fetchSemanticsNodes().isNotEmpty() }
    val target = onNodeWithContentDescription("选择${language.displayName}").performScrollTo().fetchSemanticsNode()
    val key = globe.fetchSemanticsNode()
    globe.performTouchInput {
        moveTo(target.positionOnScreen + Offset(target.size.width / 2f, target.size.height / 2f) - key.positionOnScreen)
        up()
    }
    waitUntil(10_000) {
        if (id == InputModes.ENGLISH) engine.isAsciiMode()
        else !engine.isAsciiMode() && InputModes.languageOf(engine.getCurrentSchema()) == language
    }
    waitForIdle()
    // A language switch can re-dock the physical-keyboard toolbar. Expand before
    // opening the layout/scheme panel, not after navigation has already finished.
    expandScreenKeyboard()
    if (id != InputModes.ENGLISH && engine.getCurrentSchema() != id) {
        try {
            fun targetSelected() = engine.getCurrentSchema() == id && !engine.isAsciiMode()
            fun openPanelUnlessSelected(): Boolean {
                waitUntil(5000) { targetSelected() || onAllNodesWithContentDescription("输入方式").fetchSemanticsNodes().isNotEmpty() }
                if (targetSelected()) return false
                onNodeWithContentDescription("输入方式").performClick()
                fun panelVisible() = onAllNodesWithTag("input-profile-panel", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                waitUntil(5000) { panelVisible() || targetSelected() }
                return panelVisible()
            }
            if (openPanelUnlessSelected()) {
                val profile = com.kingzcheung.xime.settings.InputProfiles.current(id)
                // LazyRow only composes visible cards; scroll by its stable key first.
                onNodeWithTag("profile-layout-list:list").performScrollToKey(profile.layout.id)
                onNodeWithTag("panel-layout:${profile.layout.id}").assertIsDisplayed().assertIsEnabled().performClick()
                waitUntil(10_000) { onAllNodesWithTag("input-profile-panel", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
                waitForIdle()
                if (!targetSelected() && openPanelUnlessSelected()) {
                    onNodeWithTag("profile-scheme-list:list").performScrollToKey(id)
                    onNodeWithTag("panel-scheme:$id").assertIsDisplayed().assertIsEnabled().performClick()
                }
            }
            waitUntil(10_000) { engine.getCurrentSchema() == id && !engine.isAsciiMode() }
        } catch (failure: Throwable) {
            println("MODE_NAVIGATION_FAILURE target=$id schema=${engine.getCurrentSchema()} ascii=${engine.isAsciiMode()}")
            runCatching {
                val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                val dir = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "mode-navigation").apply { mkdirs() }
                val image = instrumentation.uiAutomation.takeScreenshot()
                try { java.io.File(dir, "failure-$id.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } } finally { image.recycle() }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
                println("MODE_NAVIGATION_ROOT $index " + onAllNodes(isRoot()).get(index).printToString())
            }
            throw failure
        }
    }
    waitForIdle()
}
