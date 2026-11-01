package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.KeyGestureConfig
import com.kingzcheung.xime.settings.KeyboardRow
import com.kingzcheung.xime.settings.KeyboardRowsLayout
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConfiguredControlRowComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun reorderedControlsUseExistingKeyRoutes() {
        val layoutField = KeysConfigHelper::class.java.getDeclaredField("_zhRowsLayout").apply { isAccessible = true }
        val gesturesField = KeysConfigHelper::class.java.getDeclaredField("_keyGestureConfig").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val gestures = gesturesField.get(KeysConfigHelper) as MutableState<Map<String, KeyGestureConfig>>
        val previousLayout = layoutField.get(KeysConfigHelper)
        val previousGestures = gestures.value
        val rows = listOf(
            "qwertyuiop".map(Char::toString), "asdfghjkl".map(Char::toString),
            listOf("shift") + "zxcvbnm".map(Char::toString) + "delete",
            listOf("earth", "space", "comma", "mode_change", "enter"),
        )
        val events = mutableListOf<String>()
        try {
            rule.runOnIdle {
                layoutField.set(KeysConfigHelper, KeyboardRowsLayout(rows.map { KeyboardRow(it) }))
                gestures.value = emptyMap()
            }
            val app = ApplicationProvider.getApplicationContext<Application>()
            val vm = KeyboardViewModel(app)
            rule.setContent {
                CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(
                    qwertyCommaKey = true, qwertyPeriodKey = false, splitKeyboardEnabled = false,
                )) {
                    MaterialTheme {
                        KeyboardLayout(
                            onKeyPress = { events.add(it) }, viewModel = vm,
                            callbacks = KeyboardCallbacks(onKeyPress = { key, _ -> events.add(key) }, onCandidateSelect = {}),
                            uiState = KeyboardUiState(currentSchemaId = "rime_ice"), isAsciiMode = false,
                            modifier = Modifier.requiredSize(360.dp, 240.dp),
                        )
                    }
                }
            }
            rule.onNodeWithTag("configured-control-row", useUnmergedTree = true).assertExists()
            val earth = rule.onNodeWithTag("configured-earth-key", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val space = rule.onNodeWithTag("space-key", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val enter = rule.onNodeWithTag("qwerty-enter-key", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(earth.right <= space.left && space.right < enter.left)
            rule.onNodeWithTag("space-key", useUnmergedTree = true).performTouchInput { click() }
            rule.onNodeWithTag("qwerty-enter-key", useUnmergedTree = true).performTouchInput { click() }
            rule.onNodeWithTag("configured-earth-key", useUnmergedTree = true).performTouchInput { click() }
            rule.runOnIdle {
                assertTrue(events.contains("space"))
                assertTrue(events.contains("enter"))
                assertTrue(events.contains("ime_switch"))
            }
        } finally {
            rule.runOnIdle {
                layoutField.set(KeysConfigHelper, previousLayout)
                gestures.value = previousGestures
            }
        }
    }
}
