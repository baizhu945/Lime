package com.kingzcheung.xime.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalFourteenKeyLayout
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.SwipeableKeyButton
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Held flick keys show the kana direction disc; the gesture result is unchanged. */
class FlickDirectionDiscTest {
    @get:Rule val rule = createComposeRule()

    @Test fun nineKeyFlickAndFourteenKeyPairShowTheDirectionDisc() {
        val committed = mutableListOf<String>()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(t9FlickLetters = true, flickDirectionDisc = true)) {
                    // Keep the centre and every injected flick endpoint on screen, including DOWN.
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Row(Modifier.background(Color(0xFF121212))) {
                            SwipeableKeyButton("ABC", onClick = { committed += "tap" }, backgroundColor = Color(0xFF3C3C3C),
                                textColor = Color(0xFFE8EAED), modifier = Modifier.size(110.dp, 64.dp).testTag("t9-abc"),
                                flickLetters = listOf("A", "B", "C"), onFlickLetter = { committed += it },
                                onFlickLongPress = { committed += "2" }, badgeText = "2")
                            CompositionLocalProvider(LocalFourteenKeyLayout provides true) {
                                SwipeableKeyButton("QW", onClick = { committed += "tap" }, backgroundColor = Color(0xFF3C3C3C),
                                    textColor = Color(0xFFE8EAED), modifier = Modifier.size(70.dp, 64.dp).testTag("fourteen-qw"),
                                    letterGroup = "qw", onLetterSelection = { committed += it }, swipeText = "1", onSwipe = { committed += it })
                            }
                        }
                    }
                }
            }
        }
        // Held without moving: the disc shows every direction, none selected.
        rule.onNodeWithTag("t9-abc").performTouchInput { down(center) }
        rule.onNodeWithTag("letter-flick-preview").assertIsDisplayed()
        rule.onNodeWithTag("letter-preview-choice:LEFT", useUnmergedTree = true).assertIsNotSelected()
        save("t9-held")
        rule.onNodeWithTag("t9-abc").performTouchInput { moveBy(Offset(-90f, 0f)) }
        rule.onNodeWithTag("letter-preview-choice:LEFT", useUnmergedTree = true).assertIsSelected()
        save("t9-left")
        rule.onNodeWithTag("t9-abc").performTouchInput { moveBy(Offset(90f, -110f)) }
        rule.onNodeWithTag("letter-preview-choice:UP", useUnmergedTree = true).assertIsSelected()
        rule.onNodeWithTag("t9-abc").performTouchInput { moveBy(Offset(-90f, 110f)) }
        rule.onNodeWithTag("t9-abc").performTouchInput { up() }
        rule.onNodeWithTag("letter-flick-preview").assertDoesNotExist()

        rule.onNodeWithTag("fourteen-qw").performTouchInput { down(center); moveBy(Offset(90f, 0f)) }
        rule.onNodeWithTag("letter-preview-choice:RIGHT", useUnmergedTree = true).assertIsSelected()
        save("fourteen-right")
        // Straight across to the neighbouring direction, without pausing at the centre.
        // The corner "1" sits on the pull-down side under the default symbol direction.
        // Vertical activation is 50 dp; touch input offsets are physical pixels.
        val verticalFlick = with(rule.density) { 80.dp.toPx() }
        rule.onNodeWithTag("fourteen-qw").performTouchInput { moveBy(Offset(-90f, verticalFlick)) }
        rule.onNodeWithTag("letter-preview-choice:DOWN", useUnmergedTree = true).assertIsSelected()
        rule.onNodeWithTag("fourteen-qw").performTouchInput { moveBy(Offset(90f, -verticalFlick)) }
        rule.onNodeWithTag("letter-preview-choice:RIGHT", useUnmergedTree = true).assertIsSelected()
        rule.onNodeWithTag("fourteen-qw").performTouchInput { up() }
        rule.runOnIdle { assertEquals("same results as before the disc", listOf("A", "w"), committed) }

        // Long press: the digit is offered, not entered, until the finger lifts; the disc gives way.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("t9-abc").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("letter-flick-preview").assertDoesNotExist()
        rule.runOnIdle { assertEquals("long press must not commit while held", listOf("A", "w"), committed) }
        rule.onNodeWithTag("t9-abc").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { assertEquals(listOf("A", "w", "2"), committed) }
        rule.onNodeWithTag("letter-flick-preview").assertDoesNotExist()
    }

    private fun save(name: String) {
        rule.waitForIdle()
        Thread.sleep(400)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "flick-disc").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
}
