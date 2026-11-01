package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 候选/预编辑更新只该重组候选栏，不该带动它旁边的字母键盘与工具栏。
 *
 * 这里把「包着字母键盘/工具栏的作用域」换成计数：候选状态一变，只有 [CandidateBarHost] 自己的
 * 作用域该失效，外层计数必须一动不动；同时候选栏自己要拿到新候选（否则等于把功能关掉了）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h500dp-xhdpi")
class CandidateBarRecompositionScopeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
    }

    @Test
    fun candidateUpdateRecomposesTheBarOnly() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = KeyboardViewModel(app)
        val candidate = mutableStateOf(
            CandidateState(
                candidates = listOf("zhe", "zheg", "zhegshi"),
                inputText = "zhe",
                preeditText = "zhe",
                isComposing = true,
            )
        )
        // 外层组合次数 / 候选栏上报次数（候选栏每画一次报一次，只有 SideEffect 里做）。
        val outerCompositions = intArrayOf(0)
        val barReports = intArrayOf(0)
        val reported = mutableListOf<List<String>>()

        rule.setContent {
            // 这一层代表 KeyboardView 里包着字母键盘、工具栏的作用域。
            // 只要它读了 candidateState.value，每次按键都会走到这里。
            outerCompositions[0]++
            Box(Modifier.size(400.dp, 200.dp)) {
                CandidateBarHost(
                    candidateState = candidate,
                    page = KeyboardPage.Main(MainType.FULL),
                    uiState = KeyboardUiState(currentSchemaId = "pinyin"),
                    viewModel = viewModel,
                    callbacks = KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                    candidatePageExpanded = false,
                    candidateStripExtended = false,
                    singleCharFilter = false,
                    showPreeditPreview = false,
                    hardwareHighlightIndex = null,
                    hardwareKeyboardConnected = false,
                    toolbarActions = emptyList(),
                    onToolbarFeedback = {},
                    barScale = remember { mutableFloatStateOf(1f) },
                    voiceAmplitude = remember { mutableFloatStateOf(0f) },
                    voiceSpectrum = remember { mutableStateOf(FloatArray(16)) },
                    handwritingCandidates = emptyList(),
                    handwritingComments = emptyList(),
                    showHandwritingCandidates = false,
                    isHandwritingPage = false,
                    handwritingContext = app,
                    textColor = Color.White,
                    dividerColor = Color.Gray,
                    accentColor = Color.Blue,
                    selectedTextColor = Color.Blue,
                    keyBackgroundColor = Color.Black,
                    onDeletePending = { _, _, _ -> },
                    onVisibleCandidatesChanged = { barReports[0]++; reported += it },
                    onVisibleAssociationsChanged = {},
                    onClosePreeditEditor = {},
                    onOpenPreeditEditor = {},
                    onSelectHandwriting = {},
                    onClearHandwriting = {},
                    inlineSuggestions = emptyList<Any>(),
                )
            }
        }
        rule.waitForIdle()
        val outerBefore = outerCompositions[0]
        val reportsBefore = barReports[0]
        assertTrue("候选栏应该先按第一组候选画一遍", reportsBefore >= 1)

        rule.runOnIdle {
            candidate.value = candidate.value.copy(
                candidates = listOf("zhi"),
                inputText = "zhi",
                preeditText = "zhi",
                engineRevision = 1L,
            )
        }
        rule.waitForIdle()

        assertEquals("候选更新不该重组外层（字母键盘/工具栏）", outerBefore, outerCompositions[0])
        assertTrue("候选栏自己要跟着新候权重画", barReports[0] > reportsBefore)
        assertTrue("候选栏应拿到新候选", reported.last().contains("zhi"))
    }
}
