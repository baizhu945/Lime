package com.kingzcheung.xime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.settings.KeyVisualEffectSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KeyVisualEffectSettingsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun separateSwitchesRemainReachableAtNarrowWidthsAndLargeFonts() {
        var glow by mutableStateOf(false)
        var motion by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        KeyVisualEffectSettings(glow, motion, { glow = it }, { motion = it })
                    }
                }
            }
        }
        for (scale in listOf(1f, 1.5f, 2f)) {
            rule.runOnIdle { glow = false; motion = false; fontScale = scale }
            val animation = rule.onNodeWithText("按键动效")
            val light = rule.onNodeWithText("键内炫光")
            animation.performScrollTo().assertIsOff().assertHeightIsAtLeast(48.dp).performClick()
            rule.runOnIdle { assertTrue(motion); assertFalse(glow) }
            light.performScrollTo().assertIsOff().assertHeightIsAtLeast(48.dp).performClick()
            rule.runOnIdle { assertTrue(motion); assertTrue(glow) }
            animation.performScrollTo().performClick()
            rule.runOnIdle { assertFalse(motion); assertTrue(glow) }
            for (label in listOf("按键动效", "键内炫光", "点按时键帽缩放回弹，可单独开启。", "点按时显示半秒渐变光效，可单独开启。")) {
                val layouts = mutableListOf<TextLayoutResult>()
                rule.onNodeWithText(label, useUnmergedTree = true).performScrollTo()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertFalse("$label at font=$scale size=${layout.size} constraints=${layout.layoutInput.constraints} " +
                    "lines=${layout.lineCount} overflow=${layout.hasVisualOverflow} " +
                    "widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight}",
                    layout.hasVisualOverflow)
            }
        }
    }
}
