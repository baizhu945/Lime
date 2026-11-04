package com.kingzcheung.xime.ui.render

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.ui.keyboard.CandidateBar
import com.kingzcheung.xime.ui.keyboard.CandidateBarCallbacks
import com.kingzcheung.xime.ui.keyboard.CandidateBarState
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalCorrectedCandidates
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.ui.theme.resolveKeyboardPalette
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 自动纠错的候选不再在词后画改正拼音。用真实 CandidateBar 在电脑上渲染各主题深浅色和玻璃。
 * 只在设了环境变量 CYIME_RENDER_OUT 时出图。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w800dp-h600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CorrectionMarkRenderTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun renderCorrectionMarks() {
        val out = System.getenv("CYIME_RENDER_OUT")
        assumeTrue(!out.isNullOrEmpty())
        val context = ApplicationProvider.getApplicationContext<Context>()
        KeyboardThemes.initFromConfig(context)
        val themes = listOf(
            Triple("soft_blue", false, false), Triple("soft_blue", true, false),
            Triple("soft_blue", false, true), Triple("soft_blue", true, true),
            Triple("pure_black", true, false), Triple("pure_black", true, true),
        )
        val words = listOf("陈宫", "成功", "中国", "中文", "论")
        val comments = listOf("chen gong", "cheng gong", "zhong guo", "zhong wen", "lun")
        val corrections = mapOf(
            "成功" to "chen=cheng gong", // 少打
            "中国" to "zhonng=zhong guo", // 多打
            "中文" to "zhing=zhong wen", // 打错
            "论" to "lnu=lun", // 打反
        )
        rule.setContent {
            Column(Modifier.background(Color(0xFF808080)).padding(4.dp)) {
                for ((id, dark, glass) in themes) {
                    val palette = resolveKeyboardPalette(id, dark)
                    val config = FrostedGlassConfig.defaults(dark, enabled = glass)
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("$id ${if (dark) "深" else "浅"} ${if (glass) "玻璃" else "不透明"}", fontSize = 10.sp,
                            color = Color.White, modifier = Modifier.width(110.dp))
                        Box(Modifier.width(660.dp).height(44.dp)
                            .then(if (glass) Modifier.background(Brush.linearGradient(
                                listOf(Color(0xFF2E7D32), Color(0xFFF9A825), Color(0xFFC62828), Color(0xFF1565C0)))) else Modifier)
                            .keyboardBackground(KeyboardThemes.getRenderingScheme(id).keyboardBackground, dark, palette.background, config)) {
                            CompositionLocalProvider(LocalKeyboardPalette provides palette,
                                LocalKeyboardInputPreferences provides KeyboardInputPreferences(frostedGlass = config),
                                LocalCorrectedCandidates provides corrections) {
                                CandidateBar(
                                    state = CandidateBarState.ChineseCandidates(words, comments, inputText = "chengong", hasMore = true),
                                    visuals = CandidateBarVisuals(Color.Transparent, palette.candidateText,
                                        if (dark) Color(0xFF3C4043) else Color(0xFFDADCE0), palette.accent,
                                        palette.selectedText, dark, palette.key),
                                    callbacks = CandidateBarCallbacks(onCandidateSelect = {}, onShowMoreCandidates = {}),
                                    showPreeditPreview = false,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        File(out!!).mkdirs()
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle { root.draw(android.graphics.Canvas(bitmap)) }
        File(out, "correction-marks.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
