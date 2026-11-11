package com.kingzcheung.xime.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.rime.PinyinSyllableChoices
import com.kingzcheung.xime.rime.PinyinSyllableOption
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.*
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI routing only; real prism/decoder correctness is tested by the local host probe. */
class PinyinSyllableRailTest {
    @get:Rule val rule = createComposeRule()

    @Test fun emptyPinyinRailDoesNotFallBackToPunctuation() {
        val usePinyinRail = mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                CandidatePage(
                    CandidatePageState(backgroundColor = Color.White, textColor = Color.Black,
                        usePinyinRail = usePinyinRail.value),
                    CandidatePageCallbacks(onCandidateSelect = {}),
                    Modifier.requiredSize(360.dp, 276.dp),
                )
            }
        }
        rule.onNodeWithText("？").assertDoesNotExist()
        rule.onNodeWithText("！").assertDoesNotExist()
        rule.runOnIdle { usePinyinRail.value = false }
        rule.onNodeWithText("？").assertIsDisplayed()
    }

    @Test fun mergedPinyinAndDoublePinyinUseRealChoiceCallbackWithoutCommittingText() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        val vm = KeyboardViewModel(app)
        val schema = mutableStateOf("pinyin_14jian")
        val candidate = mutableStateOf(CandidateState(candidates = listOf("你", "里"),
            inputText = "NI", preeditText = "ni", isComposing = true, engineRevision = 7))
        var requests = 0
        var commits = 0
        var selected = -1
        var holdResponse = false
        var delayed: (() -> Unit)? = null
        fun snapshot(revision: Long) = PinyinSyllableChoices(42, schema.value, revision,
            "NI", 0, "", listOf(PinyinSyllableOption(1, "ni", 0, 2, selected == 0),
                PinyinSyllableOption(2, "li", 0, 2, selected == 1)))
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    KeyboardView(vm, KeyboardUiState(currentSchemaId = schema.value,
                        inputSessionId = 42, keyboardHeightDp = 276, keyboardWidthDp = 360),
                        KeyboardCallbacks(onKeyPress = { _, _ -> commits++ }, onCandidateSelect = {},
                            onCommitText = { commits++ },
                            onRequestPinyinSyllables = { revision, reply ->
                                requests++
                                val result = snapshot(revision)
                                if (holdResponse) delayed = { reply(result) } else reply(result)
                            }, onSelectPinyinSyllable = { choices, index, reply ->
                                assertEquals(schema.value, choices.schemaId)
                                assertEquals(candidate.value.engineRevision, choices.engineRevision)
                                selected = index
                                candidate.value = candidate.value.copy(engineRevision = choices.engineRevision + 1)
                                reply(snapshot(candidate.value.engineRevision))
                            }), Modifier.requiredSize(360.dp, 276.dp), candidateState = candidate)
                }
            }
        }
        rule.waitForIdle()
        assertEquals("do not enumerate prism on ordinary key renders", 0, requests)
        for (current in listOf("pinyin_14jian", "double_pinyin_flypy_14jian", "double_pinyin_flypy")) {
            rule.runOnIdle { vm.setCandidatePageExpanded(false); schema.value = current; selected = -1 }
            rule.waitForIdle()
            rule.runOnIdle { vm.setCandidatePageExpanded(true) }
            val choice = rule.onNode(hasText("li") and hasAnyAncestor(hasTestTag("expanded-pinyin-options")), true)
            choice.assertIsDisplayed().performClick()
            rule.runOnIdle {
                assertEquals(1, selected)
                assertEquals(0, commits)
                assertTrue("choosing a reading keeps the page open", vm.candidatePageExpanded.value)
            }
        }
        rule.runOnIdle {
            vm.setCandidatePageExpanded(false)
            holdResponse = true
        }
        rule.waitForIdle()
        rule.runOnIdle { vm.setCandidatePageExpanded(true) }
        rule.waitForIdle()
        rule.onNodeWithText("？").assertDoesNotExist()
        rule.onNodeWithText("！").assertDoesNotExist()
        rule.runOnIdle { vm.setCandidatePageExpanded(false) }
        rule.waitForIdle()
        rule.runOnIdle { checkNotNull(delayed).invoke() }
        rule.onNodeWithTag("expanded-pinyin-options").assertDoesNotExist()
        KeysConfigHelper.setActiveKeyboardSchema("rime_ice")
    }
}
