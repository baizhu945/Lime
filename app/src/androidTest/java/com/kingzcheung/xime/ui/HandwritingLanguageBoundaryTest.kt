package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.ui.keyboard.HandwritingKeyboardLayout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HandwritingLanguageBoundaryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun unsupportedLanguageHasNoChineseCanvasAndReturnActionStaysReachable() {
        var language by mutableStateOf(InputLanguage.UNSPECIFIED)
        var exits = 0
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme {
                    HandwritingKeyboardLayout(language = language, modifier = Modifier.size(280.dp, 140.dp),
                        onUnsupportedExit = { exits++ }, unsupportedExitLabel = "选择输入方案")
                }
            }
        }
        // Chinese, English and Japanese all have handwriting; only an unspecified
        // language falls back to the scheme chooser.
        rule.onNodeWithTag("handwriting-canvas").assertDoesNotExist()
        rule.onNodeWithTag("handwriting-unavailable").assertExists()
        rule.onNodeWithText("选择输入方案").performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun englishTypingShowsChineseHandwritingAndLanguageChangesKeepTheBoundary() {
        var language by mutableStateOf(InputLanguage.ENGLISH)
        rule.setContent {
            MaterialTheme {
                HandwritingKeyboardLayout(language = language, modifier = Modifier.size(320.dp, 260.dp))
            }
        }
        rule.onNodeWithTag("handwriting-canvas").assertExists()
        // The hint follows the active input language; with the optional template model
        // it gains a "· 边写边学" suffix, so only the language prefix is fixed.
        rule.onNodeWithTag("handwriting-language").assertTextContains("英文手写", substring = true)
        rule.onNodeWithTag("handwriting-unavailable").assertDoesNotExist()

        rule.runOnIdle { language = InputLanguage.JAPANESE }
        rule.onNodeWithTag("handwriting-canvas").assertExists()
        rule.onNodeWithTag("handwriting-language").assertTextContains("日语手写", substring = true)
        rule.onNodeWithTag("handwriting-unavailable").assertDoesNotExist()

        rule.runOnIdle { language = InputLanguage.CHINESE }
        rule.onNodeWithTag("handwriting-canvas").assertExists()
        rule.onNodeWithTag("handwriting-language").assertTextContains("中文手写", substring = true)
        rule.onNodeWithTag("handwriting-unavailable").assertDoesNotExist()
    }
}
