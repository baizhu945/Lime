package com.kingzcheung.xime.ui

import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.SharedPreferencesTestRule
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.FrostedGlassProfiles
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.LocalFrostedGlassPreview
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette
import com.kingzcheung.xime.ui.theme.resolveKeyboardPalette
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.ui.keyboard.KeyboardInputActions
import com.kingzcheung.xime.ui.keyboard.LanguageKeyButton
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageKeyButtonTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule val preferences = SharedPreferencesTestRule()

    private val events = mutableListOf<String>()
    private var density = 1f

    @Test fun menuIsCenteredAnchoredAndBoundedAtExtremePanelSizes() {
        var panelSize by androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.DpSize(200.dp, 160.dp))
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(1f),
                LocalKeyboardInputActions provides KeyboardInputActions(
                    schemas = listOf(
                        SchemaInfo("first", "中文九键", "", "", "", language = com.kingzcheung.xime.settings.InputLanguage.CHINESE),
                        SchemaInfo("second", "日语26键", "", "", "", language = com.kingzcheung.xime.settings.InputLanguage.JAPANESE)),
                    currentInputModeId = "first", onSwitchSchema = {})) {
                androidx.compose.material3.MaterialTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(panelSize).testTag("menu-panel")) {
                            com.kingzcheung.xime.ui.keyboard.LanguageMenuPanel {
                                Box(Modifier.fillMaxSize().padding(end = 12.dp), contentAlignment = Alignment.BottomEnd) {
                                    LanguageKeyButton(onClick = {}, backgroundColor = Color.Gray,
                                        modifier = Modifier.size(44.dp).testTag("language-key"))
                                }
                            }
                        }
                    }
                }
            }
        }
        for (size in listOf(androidx.compose.ui.unit.DpSize(200.dp,160.dp),
            androidx.compose.ui.unit.DpSize(400.dp,228.dp), androidx.compose.ui.unit.DpSize(1000.dp,500.dp))) {
            rule.runOnIdle { panelSize = size }
            rule.mainClock.autoAdvance = false
            rule.onNodeWithTag("language-key").performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(350L)
            val panel = rule.onNodeWithTag("menu-panel").fetchSemanticsNode()
            val key = rule.onNodeWithTag("language-key").fetchSemanticsNode()
            val menu = rule.onNodeWithTag("language-menu").fetchSemanticsNode()
            assertEquals(key.positionOnScreen.x + key.size.width, menu.positionOnScreen.x + menu.size.width, 1f)
            assertTrue(menu.positionOnScreen.x >= panel.positionOnScreen.x)
            assertTrue(menu.positionOnScreen.y >= panel.positionOnScreen.y)
            assertTrue(menu.positionOnScreen.y + menu.size.height <= panel.positionOnScreen.y + panel.size.height + 1f)
            assertTrue(menu.size.width <= 280)
            val label = rule.onNodeWithText("中文", useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(menu.positionOnScreen.x + menu.size.width / 2f,
                label.positionOnScreen.x + label.size.width / 2f, 1f)
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            java.io.File(context.getExternalFilesDir(null), "language-menu-${size.width.value.toInt()}.png").outputStream().use {
                rule.onNodeWithTag("language-menu").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            rule.onNodeWithTag("language-key").performTouchInput { cancel() }
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun glassLanguageMenuOwnsOpaqueReadingSurfaceEvenWithZeroBackgroundOpacity() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = preferences.getSharedPreferences()
        }
        // The isolated store defaults to Chinese/English; explicitly enable the Japanese fixture.
        preferences.getSharedPreferences().edit().putStringSet(
            com.kingzcheung.xime.settings.LanguagePreferences.KEY,
            setOf(com.kingzcheung.xime.settings.InputLanguage.CHINESE.id,
                com.kingzcheung.xime.settings.InputLanguage.JAPANESE.id,
                com.kingzcheung.xime.settings.InputLanguage.ENGLISH.id)).commit()
        SettingsPreferences.setKeyboardTheme(context, "transparent_glass")
        val saved = FrostedGlassProfiles(FrostedGlassConfig(true, 7f, .31f, .68f),
            FrostedGlassConfig(true, 23f, .68f, .31f))
        FrostedGlassPreferences.saveProfiles(context, saved)
        var dark by mutableStateOf(false)
        var opacity by mutableStateOf(0f)
        var blurRadius by mutableStateOf(0f)
        var beneath by mutableStateOf(Color.Black)
        assertTrue("the 10dp branch must exercise real system blur, not a skipped fallback",
            base.getSystemService(android.view.WindowManager::class.java).isCrossWindowBlurEnabled)
        val lightSurface = Color(0xFFF2F2F2)
        val darkSurface = Color(0xFF191C22)
        rule.setContent {
            val glass = FrostedGlassConfig(true, blurRadius, opacity, .29f)
            CompositionLocalProvider(LocalContext provides context,
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(frostedGlass = glass),
                LocalDensity provides androidx.compose.ui.unit.Density(1f),
                LocalFrostedGlassPreview provides FrostedGlassProfiles(glass, glass),
                // A custom gradient's representative first color can be bright in dark appearance.
                LocalKeyboardPalette provides resolveKeyboardPalette("transparent_glass", dark)
                    .copy(background = Color(0xFF9CACCC)),
                LocalKeyboardInputActions provides KeyboardInputActions(
                    schemas = listOf(SchemaInfo("first", "中文九键", "", "", "",
                        language = com.kingzcheung.xime.settings.InputLanguage.CHINESE),
                        SchemaInfo("second", "日语模式", "", "", "", language = com.kingzcheung.xime.settings.InputLanguage.JAPANESE)),
                    currentInputModeId = "first", onSwitchSchema = {})) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme(surface = darkSurface, onSurface = Color.White)
                    else lightColorScheme(surface = lightSurface, onSurface = Color(0xFF202020))) {
                    Box(Modifier.fillMaxSize().background(beneath), contentAlignment = Alignment.Center) {
                        Text(("Q W E R T Y U I O P\n").repeat(20), color = if (beneath == Color.Black) Color.White else Color.Black)
                        Box(Modifier.size(360.dp, 280.dp)) {
                            com.kingzcheung.xime.ui.keyboard.LanguageMenuPanel {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                                    LanguageKeyButton(onClick = {}, backgroundColor = Color.Gray,
                                        modifier = Modifier.size(44.dp).testTag("language-key"))
                                }
                            }
                        }
                    }
                }
            }
        }
        for (blur in listOf(0f, 10f)) for (appearance in listOf(false, true)) for (alpha in listOf(0f, .2f, .8f)) {
            rule.runOnIdle { dark = appearance; opacity = alpha; blurRadius = blur }
            rule.mainClock.autoAdvance = false
            rule.onNodeWithTag("language-key").performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(350L)
            // Popup initially draws at alpha=0 until placement is calculated. Its follow-up
            // composition needs frames after the long-press timing check has completed.
            rule.mainClock.autoAdvance = true
            // With no live blur, the original opaque theme-role RGB oracle remains exact.
            // The 10dp branch captures the actual composed screen, including the blur window.
            val themed = resolveKeyboardPalette("transparent_glass", appearance)
            val expected = androidx.compose.ui.graphics.lerp(themed.function.copy(alpha = 1f),
                themed.key.copy(alpha = 1f), 0.5f).toArgb()
            try {
                val opened = if (blur == 0f) awaitPopupEvidence("language-$blur-$appearance-$alpha-opened", expected)
                    else awaitBlurredPopupEvidence("language-$blur-$appearance-$alpha-opened")
                opened.recycle()
                rule.onNodeWithTag("language-schema:second").assertIsDisplayed()
                rule.onNodeWithTag("language-schema:first").assertIsSelected()
                rule.onNodeWithText("中文").assertIsDisplayed()
                rule.onNodeWithText("日语").assertIsDisplayed()
                val foregrounds = if (blur > 0f) menuForegrounds() else emptyList()
                for (underlay in listOf(Color.Black, Color.White)) {
                    rule.runOnIdle { beneath = underlay }
                    rule.mainClock.advanceTimeByFrame()
                    val name = "language-$blur-$appearance-$alpha-${underlay.toArgb()}"
                    val image = if (blur == 0f) awaitPopupEvidence(name, expected) else awaitBlurredPopupEvidence(name)
                    try {
                        assertTrue("menu has a measurable reading surface", image.width > 12 && image.height > 40)
                        // Keep every original edge sample, chosen by geometry rather than its colour.
                        for (y in image.height / 2 - 4..image.height / 2 + 4) for (x in 4..6) {
                            val pixel = image.getPixel(x, y)
                            if (blur == 0f) {
                                assertEquals("menu must occlude keyboard labels at opacity=$alpha dark=$appearance x=$x y=$y",
                                    expected, pixel)
                            } else for (foreground in foregrounds) {
                                val light = maxOf(foreground.luminance(), Color(pixel).luminance())
                                val darkValue = minOf(foreground.luminance(), Color(pixel).luminance())
                                val contrast = (light + .05f) / (darkValue + .05f)
                                assertTrue("$name x=$x y=$y foreground=$foreground pixel=${pixel.toUInt().toString(16)} contrast=$contrast",
                                    contrast >= 4.5f)
                            }
                        }
                    } finally { image.recycle() }
                }
                assertEquals("transparent_glass", SettingsPreferences.getKeyboardTheme(context))
                assertEquals(saved, FrostedGlassPreferences.readProfiles(context))
            } finally {
                rule.onNodeWithTag("language-key").performTouchInput { cancel() }
                rule.mainClock.advanceTimeByFrame()
                rule.mainClock.autoAdvance = true
            }
        }
    }

    private fun menuForegrounds(): List<Color> {
        val nodes = rule.onAllNodes(hasAnyAncestor(hasTestTag("language-menu")) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        nodes.fetchSemanticsNodes().indices.forEach { index ->
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        }
        assertTrue("actual menu text layouts must be present", layouts.isNotEmpty())
        return layouts.map { it.layoutInput.style.color }.distinct().also { colors ->
            colors.forEach { assertTrue("menu glyph color must be explicit and opaque: $it", it != Color.Unspecified && it.alpha == 1f) }
        }
    }

    /** Wait only for geometry/animation readiness, never for a favourable contrast result. */
    private fun awaitBlurredPopupEvidence(name: String): android.graphics.Bitmap {
        try {
            rule.waitUntil(timeoutMillis = 5_000L) {
                rule.onAllNodesWithTag("language-menu").fetchSemanticsNodes().singleOrNull()?.let {
                    it.size.width > 12 && it.size.height > 40
                } == true
            }
            rule.waitForIdle()
            val node = rule.onNodeWithTag("language-menu").fetchSemanticsNode()
            val screen = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                val x = kotlin.math.round(node.positionOnScreen.x).toInt()
                val y = kotlin.math.round(node.positionOnScreen.y).toInt()
                assertTrue("popup crop must be wholly on the actual screen", x >= 0 && y >= 0 &&
                    x + node.size.width <= screen.width && y + node.size.height <= screen.height)
                val image = android.graphics.Bitmap.createBitmap(screen, x, y, node.size.width, node.size.height)
                val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                    "popup-reading-surfaces").apply { mkdirs() }
                java.io.File(directory, "$name-screen.png").outputStream().use {
                    screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                java.io.File(directory, "$name.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                return image
            } finally { screen.recycle() }
        } catch (failure: Throwable) {
            savePopupEvidence("$name-capture-failure", "language-menu")
            throw failure
        }
    }

    /** Semantics alone can exist while Popup's initial positioning layer is still invisible. */
    private fun awaitPopupEvidence(name: String, expected: Int): android.graphics.Bitmap {
        try {
            rule.waitUntil(timeoutMillis = 5_000L) {
                if (rule.onAllNodesWithTag("language-menu").fetchSemanticsNodes().isEmpty()) false else {
                    val image = rule.onNodeWithTag("language-menu").captureToImage().asAndroidBitmap()
                    try {
                        image.width > 12 && image.height > 40 &&
                            (image.height / 2 - 4..image.height / 2 + 4).all { y ->
                                (4..6).all { x -> image.getPixel(x, y) == expected }
                            }
                    } finally { image.recycle() }
                }
            }
        } catch (failure: Throwable) {
            savePopupEvidence("$name-timeout", "language-menu")
            throw failure
        }
        return checkNotNull(savePopupEvidence(name, "language-menu"))
    }

    /** Preserve diagnostics before any display/pixel assertion, including missing-menu failures. */
    private fun savePopupEvidence(name: String, tag: String): android.graphics.Bitmap? {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null),
            "popup-reading-surfaces").apply { mkdirs() }
        val roots = rule.onAllNodes(isRoot(), useUnmergedTree = true)
        val nodes = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        val report = buildString {
            appendLine("physicalDensity=${instrumentation.targetContext.resources.displayMetrics.density}")
            nodes.forEach { appendLine("$tag screen=${it.positionOnScreen} size=${it.size} root=${it.boundsInRoot}") }
            if (nodes.isEmpty()) appendLine("$tag is absent")
            roots.fetchSemanticsNodes().indices.forEach { index -> appendLine(roots[index].printToString()) }
        }
        java.io.File(directory, "$name.txt").writeText(report)
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            java.io.File(directory, "$name-screen.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
        if (nodes.isEmpty()) return null
        val image = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        java.io.File(directory, "$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        return image
    }

    private fun setKey(currentModeId: String = "first") {
        rule.setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalKeyboardInputActions provides KeyboardInputActions(
                    schemas = listOf(
                        SchemaInfo("first", "同名方案", "", "", "", language = com.kingzcheung.xime.settings.InputLanguage.CHINESE),
                        SchemaInfo("second", "日语模式", "", "", "", language = com.kingzcheung.xime.settings.InputLanguage.JAPANESE),
                    ),
                    currentInputModeId = currentModeId,
                    onSwitchSchema = { events += "schema:$it" },
                )
            ) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomStart) {
                    LanguageKeyButton(
                        text = "中/英",
                        modifier = Modifier.size(100.dp).testTag("language-key"),
                        backgroundColor = Color.White, textColor = Color.Black,
                        onClick = { events += "tap" },
                        swipeText = "中", onSwipe = { events += "up:$it" },
                        swipeDownText = "中", onSwipeDown = { events += "down:$it" },
                        longPressItems = listOf("中", "英"),
                        onLongPressSelect = { events += "legacy:$it" },
                        onSwipeStateChange = { _, _ -> events += "legacy-preview" },
                        shadowEnabled = false,
                    )
                }
            }
        }
    }

    @Test
    fun numberPanelReturnIsTextOnlyAndCannotOpenTheLanguageMenu() {
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputActions provides KeyboardInputActions(
                schemas = listOf(SchemaInfo("second", "中文九键", "", "", "")),
                onSwitchSchema = { events += "schema:$it" },
            )) {
                com.kingzcheung.xime.ui.keyboard.NumberKeyboardLayout(
                    onKeyPress = { events += it }, keyBackgroundColor = Color.Gray,
                    keyTextColor = Color.Black, specialKeyBackgroundColor = Color.Blue,
                    modifier = Modifier.size(360.dp, 280.dp),
                )
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("mode-slot-2", useUnmergedTree = true).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(350L)
        rule.onNodeWithTag("language-schema:second").assertDoesNotExist()
        rule.onNodeWithTag("language-schema:__xime_english").assertDoesNotExist()
        rule.onNodeWithTag("mode-slot-2", useUnmergedTree = true).performTouchInput { cancel() }
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("mode-slot-2", useUnmergedTree = true).performTouchInput { down(center); up() }
        rule.runOnIdle { assertEquals(listOf("abc"), events) }
    }

    @Test fun currentModeIsHighlightedBeforeDragging() {
        setKey(); holdKey()
        rule.onNodeWithTag("language-schema:first").assertIsSelected()
        rule.onNodeWithText("中文").assertIsDisplayed()
        rule.onNodeWithText("日语").assertIsDisplayed()
        rule.onNodeWithTag("language-key").performTouchInput { cancel() }
    }

    private fun holdKey() {
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("language-key").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(350L)
        rule.onNodeWithTag("language-schema:second").assertIsDisplayed()
    }

    private fun moveToSecondSchema() {
        val key = rule.onNodeWithTag("language-key").fetchSemanticsNode()
        val schema = rule.onNodeWithTag("language-schema:second").fetchSemanticsNode()
        val target = schema.positionOnScreen + Offset(schema.size.width / 2f, schema.size.height / 2f)
        rule.onNodeWithTag("language-key").performTouchInput {
            moveTo(target - key.positionOnScreen)
        }
        rule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun menuWaitsFor200msThenOpensWithoutAnInstructionHeading() {
        setKey()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("language-key").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(150L)
        rule.onNodeWithTag("language-schema:first").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(100L)
        rule.onNodeWithTag("language-schema:first").assertIsDisplayed()
        rule.onNodeWithText("滑动选择输入方案，松手确认").assertDoesNotExist()
        rule.onNodeWithTag("language-key").performTouchInput { cancel() }
    }

    @Test
    fun shortTapKeepsTheOriginalLanguageToggle() {
        setKey()
        rule.onNodeWithTag("language-key").performTouchInput {
            down(center)
            up()
        }
        rule.runOnIdle { assertEquals(listOf("tap"), events) }
        rule.onNodeWithTag("language-schema:first").assertDoesNotExist()
    }

    @Test
    fun upSwipeIsCancelledWithoutTypingOrOpeningMenu() {
        setKey()
        rule.onNodeWithTag("language-key").performTouchInput {
            down(Offset(center.x, height * 0.85f))
            moveTo(Offset(center.x, height * 0.85f - 90f * density))
            up()
        }
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.onNodeWithTag("language-schema:first").assertDoesNotExist()
    }

    @Test
    fun slideReleaseSelectsTheSchemaIdWithoutTriggeringTapOrSwipe() {
        setKey()
        holdKey()
        moveToSecondSchema()
        rule.onNodeWithTag("language-schema:second").assertIsSelected()
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.onNodeWithTag("language-key").performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf("schema:second"), events) }
        // 长按使用手动时钟，松手后的菜单移除也需要一帧重组。
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("language-schema:second").assertDoesNotExist()
    }

    @Test
    fun cancellingAfterSelectingDoesNotSwitch() {
        setKey()
        holdKey()
        moveToSecondSchema()
        rule.onNodeWithTag("language-key").performTouchInput { cancel() }
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("language-schema:second").assertDoesNotExist()
    }

    @Test
    fun releasingOnTheOriginalKeyKeepsTheMenuForATapSelection() {
        setKey()
        holdKey()
        rule.onNodeWithTag("language-key").performTouchInput { up() }
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("language-schema:second").assertIsDisplayed()
        rule.onNodeWithTag("language-schema:second").performClick()
        rule.runOnIdle { assertEquals(listOf("schema:second"), events) }
        rule.onNodeWithTag("language-schema:second").assertDoesNotExist()
    }

    @Test
    fun tappingOutsideTheStickyMenuClosesItWithoutTyping() {
        setKey()
        holdKey()
        rule.onNodeWithTag("language-key").performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("language-menu-dismiss").performTouchInput { click(Offset(2f, 2f)) }
        rule.onNodeWithTag("language-schema:second").assertDoesNotExist()
        rule.runOnIdle { assertTrue(events.isEmpty()) }
    }

    @Test fun englishIsAlwaysListedAndSlideSelectable() {
        setKey()
        holdKey()
        val englishId = com.kingzcheung.xime.settings.InputModes.ENGLISH
        val key = rule.onNodeWithTag("language-key").fetchSemanticsNode()
        val english = rule.onNodeWithTag("language-schema:$englishId").assertIsDisplayed().fetchSemanticsNode()
        val target = english.positionOnScreen + Offset(english.size.width / 2f, english.size.height / 2f)
        rule.onNodeWithTag("language-key").performTouchInput { moveTo(target - key.positionOnScreen); up() }
        rule.runOnIdle { assertEquals(listOf("schema:$englishId"), events) }
    }
    @Test fun draggingAboveMenuKeepsChineseInsteadOfReturningToEnglish() {
        setKey(com.kingzcheung.xime.settings.InputModes.ENGLISH)
        holdKey()
        moveToSecondSchema()
        val key = rule.onNodeWithTag("language-key").fetchSemanticsNode()
        val first = rule.onNodeWithTag("language-schema:first").fetchSemanticsNode()
        val target = first.positionOnScreen + Offset(first.size.width / 2f, -200f)
        rule.onNodeWithTag("language-key").performTouchInput { moveTo(target - key.positionOnScreen) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("language-schema:first").assertIsSelected()
        rule.onNodeWithTag("language-key").performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf("schema:first"), events) }
    }

    @Test fun draggingBelowMenuKeepsLastLanguageAndReleaseCommitsIt() {
        setKey()
        holdKey()
        moveToSecondSchema()
        val key = rule.onNodeWithTag("language-key").fetchSemanticsNode()
        // Default language order is Chinese, English, then newly enabled Japanese.
        val lastId = "second"
        val last = rule.onNodeWithTag("language-schema:$lastId").fetchSemanticsNode()
        val target = last.positionOnScreen + Offset(last.size.width / 2f, last.size.height + 20f)
        rule.onNodeWithTag("language-key").performTouchInput { moveTo(target - key.positionOnScreen) }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag("language-schema:$lastId").assertIsSelected()
        rule.onNodeWithTag("language-key").performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf("schema:$lastId"), events) }
    }}
