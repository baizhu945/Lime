package com.kingzcheung.xime.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.SharedPreferencesTestRule
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardResizeOverlay
import com.kingzcheung.xime.ui.keyboard.FloatingKeyboardContainer
import com.kingzcheung.xime.ui.keyboard.FLOATING_DRAG_BAR_HEIGHT_DP
import com.kingzcheung.xime.ui.keyboard.KeyboardResizePreviewState
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardResizePreviewState
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardResizeOpacityEditing
import com.kingzcheung.xime.ui.keyboard.ResizeRect
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ResizeGlassControlsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val preferences = SharedPreferencesTestRule()

    @Before fun keepFixtureVisible() {
        rule.runOnUiThread { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test fun glassFloatingOpacityEditsDirectlyWithoutChangingMaterial() = verifyOpacityTransaction(true)

    @Test fun plainFixedOpacityEditsDirectlyWithoutChangingGeometry() = verifyOpacityTransaction(false)

    private fun verifyOpacityTransaction(glassEnabled: Boolean) {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = preferences.getSharedPreferences()
        }
        SettingsPreferences.setDarkMode(context, 1)
        // Match entering the real settings flow: complete first-read landscape defaults
        // before installing an existing saved value in this otherwise empty fixture.
        SettingsPreferences.getKeyboardOpacity(context)
        SettingsPreferences.setKeyboardOpacity(context, 0.82f)
        assertEquals("fixture must start with the saved opacity before any UI interaction",
            0.82f, SettingsPreferences.getKeyboardOpacity(context))
        FrostedGlassPreferences.save(context, FrostedGlassConfig(glassEnabled, 10f, 0.63f, 0.24f), true)
        val originalMaterial = FrostedGlassPreferences.readProfiles(context)
        val initialRect = ResizeRect(16f, 20f, 344f, 380f)
        val rectState = mutableStateOf(initialRect)
        var rect by rectState
        var showing by mutableStateOf(true)
        var opacity by mutableFloatStateOf(SettingsPreferences.getKeyboardOpacity(context))
        var geometryChanges = 0
        var confirmed = 0
        var canceled = 0
        var containerDrags = 0
        var dispatcher: OnBackPressedDispatcher? = null
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f)) {
                MaterialTheme {
                    dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                    val opacityEditing = remember(showing) { mutableStateOf(false) }
                    Box(Modifier.size(360.dp, 400.dp).testTag("resize-fixture-root")) {
                        if (showing) CompositionLocalProvider(
                            LocalKeyboardResizeOpacityEditing provides opacityEditing,
                            LocalKeyboardResizePreviewState provides KeyboardResizePreviewState(
                                rect = rect, initialRect = initialRect,
                                onRectChange = { rect = it }, bounds = ResizeRect(0f, 0f, 360f, 400f)),
                        ) {
                            FloatingKeyboardContainer(
                                isFloatingMode = glassEnabled, scaleFactor = 1f,
                                offsetX = 0, offsetY = 0,
                                contentHeightDp = 360 - FLOATING_DRAG_BAR_HEIGHT_DP,
                                onDrag = { _, _ -> containerDrags++ }, onDragEnd = {},
                                previewRectState = rectState,
                            ) {
                                Box(Modifier.fillMaxSize().testTag("resize-keyboard-body"))
                            }
                            KeyboardResizeOverlay(
                                initialHeightDp = 360, defaultHeightDp = 300, currentBottomPaddingDp = 0,
                                isFloatingMode = glassEnabled, initialWidthDp = 328, initialOpacity = opacity,
                                onHeightChange = {}, onBottomPaddingChange = {}, onReset = {},
                                onOpacityChange = { opacity = it },
                                onGeometryChange = { geometryChanges++ },
                                onConfirm = { _, _, _, value ->
                                    SettingsPreferences.setKeyboardOpacity(context, value)
                                    confirmed++; showing = false
                                },
                                onCancel = {
                                    opacity = SettingsPreferences.getKeyboardOpacity(context)
                                    canceled++; showing = false
                                },
                            )
                        } else TextButton(onClick = { showing = true }) { Text("重新调节") }
                    }
                }
            }
        }
        val cardTag = if (glassEnabled) "floating-keyboard-card" else "fixed-keyboard-resize-preview"
        val originalCardBounds = rule.onNodeWithTag(cardTag).fetchSemanticsNode().boundsInRoot
        val originalBodyBounds = rule.onNodeWithTag("resize-keyboard-body").fetchSemanticsNode().boundsInRoot
        val originalSliderBounds = rule.onNodeWithTag("keyboard-opacity-slider").fetchSemanticsNode().boundsInRoot
        if (glassEnabled) rule.onNodeWithTag("floating-drag-bar").assertIsDisplayed()
        fun assertInlineControls() {
            rule.onNodeWithTag("keyboard-opacity-slider").assertIsDisplayed().assertIsEnabled()
            rule.onNodeWithTag("keyboard-opacity-actions").assertDoesNotExist()
            // The full 「透明度 N%」 label stays centered over the inline track.
            rule.onNodeWithTag("keyboard-opacity-overlay-label", useUnmergedTree = true).assertIsDisplayed()
            rule.onNodeWithTag("keyboard-resize-frame").assertIsDisplayed()
            rule.onNodeWithTag("keyboard-resize-actions").assertIsDisplayed()
            listOf("重置", "悬浮键盘", "分体键盘", "确认").forEach {
                // Mode buttons remain visible even when unsupported by this fixture.
                rule.onNodeWithContentDescription(it).assertIsDisplayed()
            }
            if (glassEnabled) {
                rule.onNodeWithTag("keyboard-resize-move-bar").assertIsDisplayed()
                rule.onNodeWithTag("floating-drag-bar").assertIsDisplayed()
            } else {
                rule.onNodeWithTag("keyboard-resize-fixed-move-bar").assertIsDisplayed()
                rule.onNodeWithTag("keyboard-resize-padding-bar").assertIsDisplayed()
            }
            assertEquals("inline opacity adjustment must not move the track",
                originalSliderBounds, rule.onNodeWithTag("keyboard-opacity-slider").fetchSemanticsNode().boundsInRoot)
            assertEquals("inline opacity adjustment must retain the whole card's bounds",
                originalCardBounds, rule.onNodeWithTag(cardTag).fetchSemanticsNode().boundsInRoot)
            assertEquals("inline opacity adjustment must retain the keyboard content's height and position",
                originalBodyBounds, rule.onNodeWithTag("resize-keyboard-body").fetchSemanticsNode().boundsInRoot)
            rule.onNodeWithContentDescription("模糊强度").assertDoesNotExist()
            rule.onNodeWithContentDescription("背景不透明度").assertDoesNotExist()
            rule.onNodeWithContentDescription("按键不透明度").assertDoesNotExist()
        }
        fun dragOpacity(from: Float, to: Float) {
            rule.onNodeWithTag("keyboard-opacity-slider").performTouchInput {
                swipe(Offset(width * from, centerY), Offset(width * to, centerY), durationMillis = 250)
            }
            rule.waitForIdle()
        }
        rule.onNodeWithTag("keyboard-opacity-overlay-label").performTouchInput { click() }
        rule.runOnIdle {
            assertNotEquals("touching the label must really edit opacity", .82f, opacity)
            assertEquals(.82f, SettingsPreferences.getKeyboardOpacity(context))
        }
        assertInlineControls()
        assertEquals("touching the inline label must not move the track",
            originalSliderBounds, rule.onNodeWithTag("keyboard-opacity-slider").fetchSemanticsNode().boundsInRoot)
        dragOpacity(.25f, .67f)
        val changed = opacity
        rule.runOnIdle {
            assertNotEquals(.82f, changed)
            assertEquals(.82f, SettingsPreferences.getKeyboardOpacity(context))
            assertEquals(originalMaterial, FrostedGlassPreferences.readProfiles(context))
            assertEquals(initialRect, rect)
            assertEquals(0, geometryChanges)
        }
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "resize-opacity").apply { mkdirs() }
        File(directory, "direct-editor-glass-$glassEnabled.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // Hold the actual thumb so pressingOpacity is active before a second finger
        // drags the blank frame area. Idle resize-frame drags are allowed to move it.
        val fixture = rule.onNodeWithTag("resize-fixture-root")
        val rootOrigin = fixture.fetchSemanticsNode().boundsInRoot.topLeft
        val thumbCenter = rule.onNodeWithTag("keyboard-opacity-thumb", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center - rootOrigin
        fixture.performTouchInput { down(0, thumbCenter) }
        rule.waitForIdle()
        fixture.performTouchInput {
            down(1, Offset(40f, 30f))
            moveTo(1, Offset(90f, 50f), delayMillis = 250)
            up(1)
            // Cancel the stationary primary pointer instead of submitting a new track tap.
            cancel()
        }
        rule.runOnIdle {
            assertEquals(initialRect, rect)
            assertEquals(0, containerDrags)
            assertEquals(changed, opacity)
            assertEquals(.82f, SettingsPreferences.getKeyboardOpacity(context))
            assertEquals(originalMaterial, FrostedGlassPreferences.readProfiles(context))
            assertEquals(0, geometryChanges)
            assertEquals(0, canceled)
            assertEquals(0, confirmed)
        }
        // There is no return page: the controls and the uncommitted draft stay inline.
        assertInlineControls()
        rule.runOnIdle { assertEquals(changed, opacity); assertEquals(0, canceled) }
        // A real track tap edits opacity without entering a separate editor.
        rule.onNodeWithTag("keyboard-opacity-slider").performTouchInput { click(Offset(width * .1f, centerY)) }
        assertInlineControls()
        // Match the actual IME: system Back cancels the whole transaction, even while editing opacity.
        rule.runOnIdle { dispatcher!!.onBackPressed() }
        rule.runOnIdle {
            assertEquals(1, canceled)
            assertEquals(0, confirmed)
            assertEquals(.82f, opacity)
            assertEquals(.82f, SettingsPreferences.getKeyboardOpacity(context))
            assertEquals(originalMaterial, FrostedGlassPreferences.readProfiles(context))
        }
        rule.onNodeWithText("重新调节").performClick()
        rule.runOnIdle { assertEquals(.82f, opacity) }
        // Reopen a fresh transaction and really drag the same always-visible track.
        dragOpacity(.08f, .31f)
        assertInlineControls()
        val chosen = opacity
        rule.runOnIdle {
            assertNotEquals(.82f, chosen)
            assertEquals(.82f, SettingsPreferences.getKeyboardOpacity(context))
            assertEquals(1, canceled)
            assertEquals(0, confirmed)
            assertEquals(0, geometryChanges)
        }
        rule.onNodeWithContentDescription("确认").performClick()
        rule.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, canceled)
            assertEquals(0, containerDrags)
            assertEquals(chosen, SettingsPreferences.getKeyboardOpacity(context))
            assertEquals(originalMaterial, FrostedGlassPreferences.readProfiles(context))
            assertEquals(initialRect, rect)
        }
    }
}
