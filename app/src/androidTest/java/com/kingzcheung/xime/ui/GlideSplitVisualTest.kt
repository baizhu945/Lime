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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 14 键分体（跨中线的双字母键拆开）与英文滑行轨迹的实际渲染；图片存到应用外部目录供人工查看。 */
class GlideSplitVisualTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = SettingsPreferences.getPrefsPublic(app)
    private val keys = listOf(SettingsPreferences.KEY_SPLIT_KEYBOARD, "glide_typing", "glide_typing_v2", "cursor_gesture_mode")
    private var original: Map<String, Any?> = emptyMap()

    @Before fun before() {
        original = keys.associateWith { prefs.all[it] }
        prefs.edit().putBoolean("glide_typing", true).putBoolean("glide_typing_v2", true)
            .putString("cursor_gesture_mode", "SPACE").commit()
        KeysConfigHelper.loadConfig(app)
    }

    @After fun after() {
        prefs.edit().also { edit -> original.forEach { (name, value) ->
            when (value) { is String -> edit.putString(name, value); is Boolean -> edit.putBoolean(name, value); else -> edit.remove(name) }
        } }.commit()
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
    }

    private fun show(schema: String, ascii: Boolean) {
        KeysConfigHelper.setActiveKeyboardSchema(schema)
        rule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 1000; screenHeightDp = 1400; orientation = Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalConfiguration provides configuration) {
                MaterialTheme {
                    Box(Modifier.requiredSize(960.dp, 420.dp).testTag("visual-host"), contentAlignment = Alignment.Center) {
                        val vm = remember { KeyboardViewModel(app) }
                        KeyboardView(vm, KeyboardUiState(currentSchemaId = schema, isAsciiMode = ascii,
                            keyboardHeightDp = 380, keyboardWidthDp = 940, fixedWidthDp = 940, isDarkTheme = true),
                            KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}, onGlideWord = { _, _ -> }),
                            modifier = Modifier.size(940.dp, 380.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun save(name: String) {
        val bitmap = rule.onNodeWithTag("visual-host").captureToImage().asAndroidBitmap()
        val dir = File(app.getExternalFilesDir(null), "glide-visual").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun fourteenKeySplitBreaksTheMidlinePairs() {
        prefs.edit().putBoolean(SettingsPreferences.KEY_SPLIT_KEYBOARD, true).commit()
        show("pinyin_14jian", false)
        save("fourteen-split")
        val t = bounds("qwerty-key:t"); val y = bounds("qwerty-key:y")
        val gap = bounds("split-keyboard-gap")
        assertTrue("t must sit left of the gap", t.right <= gap.left + 1f)
        assertTrue("y must sit right of the gap", y.left >= gap.right - 1f)
    }

    @Test fun englishGlideDrawsItsTrail() {
        prefs.edit().putBoolean(SettingsPreferences.KEY_SPLIT_KEYBOARD, false).commit()
        show("rime_ice", true)
        val host = bounds("visual-host")
        val path = "keyboard".map { bounds("qwerty-key:$it").center - host.topLeft }
        rule.onNodeWithTag("visual-host").performTouchInput {
            down(path.first())
            path.zipWithNext().forEach { (a, b) -> for (step in 1..6) moveTo(a + (b - a) * (step / 6f)) }
        }
        rule.waitForIdle()
        save("english-glide-trail")
        rule.onNodeWithTag("visual-host").performTouchInput { up() }
        rule.waitForIdle()
        save("english-after-glide")
    }
}
