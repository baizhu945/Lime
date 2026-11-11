package com.kingzcheung.xime.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.settings.SpeechToTextSettingsContent
import org.junit.Rule
import org.junit.Test

class SpeechSettingsLayoutGateTest {
    @get:Rule val rule = createComposeRule()

    private data class Viewport(val width: Int, val height: Int, val font: Float)
    private val viewports = listOf(
        Viewport(280, 360, 2f),
        Viewport(360, 640, 1.3f),
        Viewport(800, 500, 1f),
    )

    @Test fun onlineSpeechPageFitsShortAndLargeTextViewports() = withUseLocal(false) { context ->
        val viewport = mutableStateOf(viewports.first())
        rule.setContent {
            val v = viewport.value
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f, v.font)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(v.width.dp, v.height.dp).testTag("speech-settings-host")) {
                        key(v) { SpeechToTextSettingsContent(onBack = {}) }
                    }
                }
            }
        }
        for (v in viewports) {
            rule.runOnIdle { viewport.value = v }
            rule.assertGeometry("speech-settings-host", "在线语音设置 $v")
            val title = rule.onNodeWithTag("speech-settings-title").assertTextEquals("语音转文本")
            if (fitsDevice(v)) title.assertIsDisplayed()
            else assertInsideHost("speech-settings-host", title, "标题 $v")
            rule.onAllNodesWithText("使用本地模型").assertCountEquals(0)
            val engine = rule.onNodeWithText("本地识别").performScrollTo()
            if (fitsDevice(v)) engine.assertIsDisplayed()
            else assertInsideHost("speech-settings-host", engine, "常驻本地识别 $v")
            listOf("speech-api-url", "speech-api-key", "speech-api-model", "speech-api-status", "speech-api-test").forEach { tag ->
                val control = rule.onNodeWithTag(tag).performScrollTo()
                if (fitsDevice(v)) control.assertIsDisplayed()
                else assertInsideHost("speech-settings-host", control, "在线配置 $tag $v")
                rule.assertGeometry("speech-settings-host", "在线配置 $tag $v")
            }
            val footerNode = rule.onNodeWithTag("speech-api-description")
                .assertTextContains("不填或测试没通过时一律用本地模型。", substring = true)
                .assertTextContains("密钥只存在本机，不进备份。", substring = true)
                .performScrollTo()
            assertFooterEdgesReachable(footerNode, v)
            if (fitsDevice(v)) footerNode.assertIsDisplayed()
            else assertInsideHost("speech-settings-host", footerNode, "在线说明 $v")
            val footer = footerNode.fetchSemanticsNode().boundsInRoot
            val host = rule.onNodeWithTag("speech-settings-host").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("在线语音页末项超出 $v: $footer / $host", footer.bottom <= host.bottom + 1f)
            rule.assertGeometry("speech-settings-host", "在线语音说明末项 $v")
        }
    }

    @Test fun localSpeechPageCanReachLastModelOptionAtEverySize() = withUseLocal(true) { context ->
        val viewport = mutableStateOf(viewports.first())
        rule.setContent {
            val v = viewport.value
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f, v.font)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(v.width.dp, v.height.dp).testTag("speech-settings-host")) {
                        key(v) { SpeechToTextSettingsContent(onBack = {}) }
                    }
                }
            }
        }
        for (v in viewports) {
            rule.runOnIdle { viewport.value = v }
            rule.assertGeometry("speech-settings-host", "本地语音设置 $v")
            rule.onNodeWithText("本地识别").performScrollTo().assertIsDisplayed()
            val lastModel = rule.onNodeWithTag("speech-correction").performScrollTo()
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val host = rule.onNodeWithTag("speech-settings-host").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("本地语音页末项超出 $v: $lastModel / $host", lastModel.bottom <= host.bottom + 1f)
            rule.assertGeometry("speech-settings-host", "本地语音模型末项 $v")
        }
    }

    private fun assertFooterEdgesReachable(node: SemanticsNodeInteraction, viewport: Viewport) {
        val scroller = rule.onNodeWithTag("speech-settings-list")
        // The full privacy explanation is taller than the short, large-font viewport.
        // Check both actual, unclipped text edges through real scrolling, not clipped semantic bounds.
        for (trailing in listOf(false, true)) {
            val visible = scroller.fetchSemanticsNode().boundsInRoot
            val text = node.fetchSemanticsNode()
            val edge = text.positionInRoot.y + if (trailing) text.size.height else 0
            val target = if (trailing) visible.bottom else visible.top
            scroller.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, edge - target) }
            rule.waitForIdle()
            val updated = node.fetchSemanticsNode()
            val actualEdge = updated.positionInRoot.y + if (trailing) updated.size.height else 0
            val bounds = scroller.fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("在线说明${if (trailing) "末尾" else "开头"}不可达 $viewport: $actualEdge / $bounds",
                actualEdge >= bounds.top - 1f && actualEdge <= bounds.bottom + 1f)
        }
    }

    private fun fitsDevice(viewport: Viewport): Boolean {
        val size = ApplicationProvider.getApplicationContext<Context>().resources.configuration
        return viewport.width <= size.screenWidthDp && viewport.height <= size.screenHeightDp
    }

    private fun assertInsideHost(hostTag: String, node: SemanticsNodeInteraction, scenario: String) {
        val host = rule.onNodeWithTag(hostTag).fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("$scenario 不在合成视口内: $bounds / $host",
            bounds.left >= host.left - 1f && bounds.right <= host.right + 1f &&
                bounds.top >= host.top - 1f && bounds.bottom <= host.bottom + 1f)
    }

    private fun withUseLocal(value: Boolean, block: (Context) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val apiNamespace = "speech-layout-api-${System.nanoTime()}"
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences(if (name == "online_speech_api") apiNamespace else name, mode)
        }
        val prefs = SettingsPreferences.getPrefsPublic(app)
        val key = SettingsPreferences.KEY_STT_USE_LOCAL
        val existed = prefs.contains(key)
        val previous = prefs.getBoolean(key, false)
        prefs.edit().putBoolean(key, value).commit()
        try {
            block(context)
        } finally {
            prefs.edit().apply {
                if (existed) putBoolean(key, previous) else remove(key)
            }.commit()
        }
    }
}
