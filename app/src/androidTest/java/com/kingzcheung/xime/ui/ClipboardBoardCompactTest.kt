package com.kingzcheung.xime.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.clipboard.ClipboardImage
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.clipboard.ClipboardKeyboardNavigation
import com.kingzcheung.xime.clipboard.ClipboardNavigation
import com.kingzcheung.xime.clipboard.LocalClipboardKeyboardNavigation
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.menubar.ClipboardBoardView
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The board receives the remaining height after the keyboard's main toolbar. */
class ClipboardBoardCompactTest {
    @get:Rule val rule = createComposeRule()
    private val height = mutableStateOf(180)
    private val dark = mutableStateOf(true)
    private val fontScale = mutableStateOf(1f)
    private val manyRecords = mutableStateOf(false)
    private val pinnedText = ClipboardItem(12, "固定文字", timestamp = 200)
    private val newestText = ClipboardItem(10, "最新普通文字", timestamp = 500)
    private val oldestText = ClipboardItem(4, "较早普通文字", timestamp = 1)
    private lateinit var imageFile: File
    private lateinit var image: ClipboardImage
    private val pins = mutableStateOf(emptySet<String>())
    private val pastedText = mutableListOf<String>()
    private val pastedImages = mutableListOf<ClipboardImage>()
    private var closed = 0
    private val keyboard = ClipboardKeyboardNavigation { closed++ }

    @Before fun createImageWithoutUsingClipboardStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        imageFile = File.createTempFile("clipboard-compact-fixture-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { color = IMAGE_COLOR }
            canvas.drawRect(0f, 0f, 160f, 120f, paint)
            imageFile.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        image = ClipboardImage(Uri.fromFile(imageFile).toString(), "image/png", 100, "紧凑布局测试图片")
        // Deliberately opposite to timestamp order: Set insertion order must not drive placement.
        pins.value = linkedSetOf(imageKey(), "text:${pinnedText.id}")
    }

    @After fun removeOnlyFixtureImage() {
        keyboard.reset()
        if (::imageFile.isInitialized && imageFile.exists()) assertTrue(imageFile.delete())
    }

    @Test fun compactControlsLeaveAtLeastEightyPercentForContentAtBothKeyboardHeights() {
        showBoard()
        // 220/260dp keyboard surfaces leave 180/220dp after the main keyboard toolbar.
        for (boardHeight in listOf(180, 220)) for (isDark in listOf(true, false)) {
            rule.runOnIdle { height.value = boardHeight; dark.value = isDark }
            val board = bounds("clipboard-board")
            val controls = bounds("clipboard-controls")
            val records = bounds("clipboard-records-region")
            assertEquals(360f, board.width, 1f)
            assertEquals(boardHeight.toFloat(), board.height, 1f)
            assertEquals("classification/actions use the expression menu height", 36f, controls.height, 1f)
            assertTrue("content occupies >=80%: height=$boardHeight dark=$isDark",
                records.width * records.height >= board.width * board.height * .8f)
            assertTrue("records cannot be covered by the controls", records.top >= controls.bottom - 1f)
            assertTrue("records must remain inside the keyboard surface", records.bottom <= board.bottom + 1f)
            rule.onNodeWithTag("clipboard-expand").assertDoesNotExist()
            for (tag in listOf("clipboard-filter:ALL", "clipboard-more")) {
                val item = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals("$tag belongs to the same row", controls.center.y, item.center.y, 1f)
                assertTrue("$tag must not create another row", item.top >= controls.top - 1f && item.bottom <= controls.bottom + 1f)
            }
            for (title in listOf("剪贴板", "固定", "最近记录")) rule.onNodeWithText(title).assertDoesNotExist()
            rule.onNodeWithContentDescription("返回键盘").assertDoesNotExist()
            val first = bounds("clipboard-card:text:${pinnedText.id}")
            assertTrue("no hidden section heading before the first record", first.top - records.top in 0f..8f)
            rule.onNodeWithTag("clipboard-card:${imageKey()}").assertIsDisplayed()
            awaitLoadedImage()
            val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "clipboard-compact-$boardHeight-${if (isDark) "dark" else "light"}.png")
            screenshot.outputStream().use { stream ->
                assertTrue(rule.onNodeWithTag("clipboard-board").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        }
    }

    @Test fun largeFontsKeepFilterTextInsideItsControlWithoutRestoringSectionRows() {
        showBoard()
        for (scale in listOf(1f, 1.3f, 2f)) {
            rule.runOnIdle { fontScale.value = scale }
            val label = rule.onNodeWithText("全部", useUnmergedTree = true).assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val button = bounds("clipboard-filter:ALL")
            val controls = bounds("clipboard-controls")
            assertTrue(label.top >= button.top && label.bottom <= button.bottom)
            assertTrue(label.height >= 18f * scale - 1f)
            assertTrue(button.top >= controls.top && button.bottom <= controls.bottom)
            assertTrue(bounds("clipboard-records-region").top >= controls.bottom)
            for (title in listOf("固定", "最近记录")) rule.onNodeWithText(title).assertDoesNotExist()
        }
    }

    @Test fun horizontalRecordSwipesChangeOneCategoryWithoutPastingOrWrapping() {
        showBoard()
        for (filter in listOf("TEXT", "LINK", "ADDRESS")) {
            rule.onNodeWithTag("clipboard-records-region").performTouchInput {
                swipe(Offset(300f, 80f), Offset(80f, 100f), 300)
            }
            rule.onNodeWithTag("clipboard-filter:$filter").assertIsSelected()
        }
        // Empty categories must still receive the paging gesture.
        rule.onNodeWithTag("clipboard-records-region").performTouchInput {
            swipe(Offset(70f, 90f), Offset(290f, 80f), 300)
        }
        rule.onNodeWithTag("clipboard-filter:LINK").assertIsSelected()
        rule.onNodeWithTag("clipboard-filter:ALL").performScrollTo().performClick()
        rule.onNodeWithTag("clipboard-records-region").performTouchInput {
            swipe(Offset(70f, 90f), Offset(290f, 90f), 300)
        }
        rule.onNodeWithTag("clipboard-filter:ALL").assertIsSelected()
        rule.runOnIdle { assertTrue(pastedText.isEmpty()); assertTrue(pastedImages.isEmpty()) }
    }

    @Test fun verticalAndDiagonalRecordSwipesScrollInsteadOfSwitchingCategories() {
        manyRecords.value = true
        showBoard()
        rule.onNodeWithTag("clipboard-card:text:160").assertIsDisplayed()
        rule.onNodeWithTag("clipboard-records-region").performTouchInput {
            swipe(Offset(280f, 145f), Offset(250f, 10f), 300)
        }
        rule.onNodeWithTag("clipboard-filter:ALL").assertIsSelected()
        rule.onNodeWithTag("clipboard-card:text:160").assertIsNotDisplayed()
        rule.onNodeWithTag("clipboard-records-region").performTouchInput {
            swipe(Offset(250f, 130f), Offset(170f, 50f), 300)
        }
        rule.onNodeWithTag("clipboard-filter:ALL").assertIsSelected()
        rule.runOnIdle { assertTrue(pastedText.isEmpty()); assertTrue(pastedImages.isEmpty()) }
    }

    @Test fun pinsLeadMixedRecordsByTimestampAndCornerLockStaysInsideBoundedPreview() {
        showBoard()
        val textTag = "clipboard-card:text:${pinnedText.id}"
        val imageTag = "clipboard-card:${imageKey()}"
        val pinned = bounds(textTag)
        val pinnedImage = bounds(imageTag)
        val recent = bounds("clipboard-card:text:${newestText.id}")
        assertEquals("all pinned records share the first row", pinned.top, pinnedImage.top, 1f)
        assertTrue("newer pinned text precedes the older pinned image", pinned.left < pinnedImage.left)
        assertTrue("even newer unpinned content follows pinned content", recent.top >= pinned.bottom)
        awaitLoadedImage()
        rule.onNodeWithTag("clipboard-card:${imageKey()}").performClick()
        rule.runOnIdle { assertEquals(listOf(image), pastedImages) }

        // Unpinning restores the normal history card. Re-pinning restores its bounded
        // first-row preview; the lock remains an overlay rather than a separate footer.
        rule.runOnIdle { pins.value = emptySet() }
        rule.onNodeWithTag("clipboard-records").performScrollToNode(hasTestTag(textTag))
        for (isDark in listOf(true, false)) {
            rule.runOnIdle { dark.value = isDark }
            val card = rule.onNodeWithTag(textTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val text = rule.onNodeWithText(pinnedText.text, useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            // A single-line history card intentionally has no 48dp floor; verify the
            // line itself fits and its rendered glyphs contrast with the card instead.
            assertTrue("ordinary text fits inside card: card=$card text=$text", text.height >= 18f &&
                text.top >= card.top && text.bottom <= card.bottom && text.left >= card.left && text.right <= card.right)
            val bitmap = rule.onNodeWithTag(textTag).captureToImage().asAndroidBitmap()
            val background = bitmap.getPixel(bitmap.width - 10, bitmap.height / 2)
            var contrastingPixels = 0
            for (y in 4 until bitmap.height - 4) for (x in 8 until bitmap.width - 30) {
                if (androidx.core.graphics.ColorUtils.calculateContrast(bitmap.getPixel(x, y), background) >= 4.5) contrastingPixels++
            }
            assertTrue("ordinary history glyphs remain readable dark=$isDark: $contrastingPixels pixels", contrastingPixels >= 10)
        }
        rule.runOnIdle { pins.value = setOf("text:${pinnedText.id}") }
        rule.onNodeWithTag("clipboard-records").performScrollToNode(hasTestTag(textTag))
        val repinned = bounds(textTag)
        assertTrue("pinned preview leaves room for recent history",
            repinned.height <= bounds("clipboard-records-region").height * .35f + 1f)
        assertTrue("large panels cannot inflate the pinned preview", repinned.height <= 113f)
        val lock = rule.onNode(hasContentDescription("已固定") and
            hasAnyAncestor(hasTestTag("clipboard-pin:text:${pinnedText.id}")), useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("small lock belongs inside the card", lock.width <= 18f && lock.height <= 18f)
        assertTrue("lock belongs inside the trailing edge of the single-line preview", lock.top >= repinned.top &&
            lock.bottom <= repinned.bottom && abs(lock.center.y - repinned.center.y) <= 1f &&
            repinned.right - lock.right in 0f..12f)
        rule.onNodeWithTag(textTag).performClick()
        rule.runOnIdle { assertEquals(listOf(pinnedText.text), pastedText) }
    }

    @Test fun filteringAndKeyboardConfirmationKeepMixedRecordIdentityAndCloseOnce() {
        keyboard.open()
        showBoard()
        rule.onNodeWithTag("clipboard-card:text:${pinnedText.id}").assertIsFocused()
        rule.runOnIdle {
            keyboard.dispatch(ClipboardNavigation.RIGHT)
            keyboard.dispatch(ClipboardNavigation.CONFIRM)
            keyboard.dispatch(ClipboardNavigation.CONFIRM)
        }
        rule.runOnIdle {
            assertEquals(listOf(image), pastedImages)
            assertTrue(pastedText.isEmpty())
            assertEquals(1, closed)
            assertFalse(keyboard.active)
        }

        rule.runOnIdle { keyboard.open() }
        rule.onNodeWithTag("clipboard-filter:TEXT").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithTag("clipboard-card:${imageKey()}").assertDoesNotExist()
        rule.onNodeWithTag("clipboard-card:text:${pinnedText.id}").assertIsFocused()
        // The newest unpinned card is below the full-span pin separator, not to its right.
        rule.runOnIdle { keyboard.dispatch(ClipboardNavigation.DOWN) }
        rule.onNodeWithTag("clipboard-card:text:${newestText.id}").assertIsFocused().assertIsDisplayed()
        rule.runOnIdle { keyboard.dispatch(ClipboardNavigation.CONFIRM) }
        rule.runOnIdle {
            assertEquals(listOf(newestText.text), pastedText)
            assertEquals(2, closed)
        }

        rule.onNodeWithTag("clipboard-filter:IMAGE").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithTag("clipboard-card:text:${newestText.id}").assertDoesNotExist()
        rule.onNodeWithTag("clipboard-card:${imageKey()}").assertIsDisplayed()
        rule.onNodeWithTag("clipboard-filter:ALL").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithTag("clipboard-card:text:${pinnedText.id}").assertIsDisplayed()
        rule.onNodeWithTag("clipboard-card:${imageKey()}").assertIsDisplayed()
    }

    @Test fun imagesHaveSparseTextureWhilePastingKeepsTheOriginalImage() {
        showBoard()
        awaitLoadedImage()
        rule.onNodeWithTag("clipboard-image-hatch:${imageKey()}", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("clipboard-image-hatch:text:${pinnedText.id}", useUnmergedTree = true).assertDoesNotExist()
        val bitmap = rule.onNodeWithTag("clipboard-card:${imageKey()}").captureToImage().asAndroidBitmap()
        var changed = 0
        var sampled = 0
        // Ignore rounded borders and the lock badge, measuring the image itself.
        for (y in 4 until bitmap.height - 4) for (x in 4 until bitmap.width - 30) {
            val pixel = bitmap.getPixel(x, y)
            val difference = maxOf(abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(IMAGE_COLOR)),
                abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(IMAGE_COLOR)),
                abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(IMAGE_COLOR)))
            if (difference > 3) changed++
            sampled++
        }
        assertTrue("Lines must be visible but leave most image pixels unchanged: $changed/$sampled",
            changed > sampled * .01f && changed < sampled * .15f)
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "clipboard-image-texture.png")
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.onNodeWithTag("clipboard-card:${imageKey()}").performClick()
        rule.runOnIdle { assertEquals(listOf(image), pastedImages) }
    }

    private fun showBoard() {
        rule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale.value),
                LocalClipboardKeyboardNavigation provides keyboard,
                LocalKeyboardInputPreferences provides KeyboardInputPreferences(
                    frostedGlass = FrostedGlassConfig.defaults(dark.value, enabled = true)),
            ) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.requiredSize(360.dp, height.value.dp)) {
                        ClipboardBoardView(
                            textItems = if (manyRecords.value) (1L..60L).map {
                                ClipboardItem(100L + it, "历史记录 $it", timestamp = it)
                            } else listOf(oldestText, newestText, pinnedText), images = listOf(image),
                            initialImages = false, expanded = false, pins = pins.value,
                            photoAccess = false, failure = null, onBack = { fail("board must use the main toolbar's navigation") },
                            onQuickSend = {}, onSelectText = { pastedText += it }, onSelectImage = { pastedImages += it },
                            onSplit = { _, _ -> }, onAddQuick = {}, onPinsChange = { pins.value = it }, onRemove = {},
                            onPhotoAccess = {}, onPick = {}, onSystemPaste = {}, onShare = { _, _ -> }, onPullRemote = null,
                        )
                    }
                }
            }
        }
    }

    private fun imageKey() = "image:${image.uri}"
    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot

    private fun awaitLoadedImage() {
        rule.waitUntil(10_000) {
            val bitmap = rule.onNodeWithTag("clipboard-card:${imageKey()}").captureToImage().asAndroidBitmap()
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(IMAGE_COLOR)) < 12 &&
                abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(IMAGE_COLOR)) < 12 &&
                abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(IMAGE_COLOR)) < 12
        }
    }

    private companion object { val IMAGE_COLOR: Int = android.graphics.Color.rgb(20, 160, 190) }
}
