package com.kingzcheung.xime.clipboard

import android.content.Context
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.MainActivity
import com.kingzcheung.xime.clipboard.db.ClipboardEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ClipboardManagerTest {

    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var storageFixture: ClipboardManagerOwnedStorageFixture
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var systemClipboard: android.content.ClipboardManager
    private var previousClip: android.content.ClipData? = null

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        scenario = ActivityScenario.launch(intent)
        awaitCondition({
            var focused = false
            scenario.onActivity { focused = it.hasWindowFocus() }
            focused
        })
        systemClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        previousClip = systemClipboard.primaryClip
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) systemClipboard.clearPrimaryClip()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        storageFixture = ClipboardManagerOwnedStorageFixture(context)
        context = storageFixture.context
        clipboardManager = storageFixture.manager
        clipboardManager.resetHistoryWindow()
        // Await production startup migration before a test can seed legacy preferences.
        runBlocking { clipboardManager.exportItems() }
        awaitCondition({ clipboardManager.clipboardItems.value.isEmpty() && clipboardManager.quickSendItems.value.isEmpty() })
    }

    @After
    fun tearDown() {
        try {
            if (::storageFixture.isInitialized) runBlocking { storageFixture.close() }
        } finally {
            try {
                if (::systemClipboard.isInitialized) {
                    if (previousClip != null) systemClipboard.setPrimaryClip(previousClip!!)
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) systemClipboard.clearPrimaryClip()
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                }
            } finally {
                if (::scenario.isInitialized) scenario.close()
            }
        }
    }

    private fun awaitCondition(condition: () -> Boolean, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for condition")
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun getInstanceReturnsSingleton() {
        // The same production Provider supplies the application singleton and scoped stores.
        val instance1 = storageFixture.provider.getInstance()
        val instance2 = storageFixture.provider.getInstance()

        assertEquals(instance1, instance2)
    }

    @Test
    fun clipboardItemsEmptyInitially() {
        assertTrue("Initial clipboard should be empty", clipboardManager.clipboardItems.value.isEmpty())
    }

    @Test
    fun quickSendItemsEmptyInitially() {
        assertTrue("Initial quick send should be empty", clipboardManager.quickSendItems.value.isEmpty())
    }

    @Test
    fun addItemAddsItemToList() {
        clipboardManager.addItem("Test text")

        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        val items = clipboardManager.clipboardItems.value
        assertEquals(1, items.size)
        assertEquals("Test text", items[0].text)
        assertFalse("New item should not be pinned", items[0].isPinned)
    }

    @Test
    fun addItemNotAddBlankText() {
        clipboardManager.addItem("")
        clipboardManager.addItem("   ")

        Thread.sleep(200)
        assertTrue("Blank text should not be added", clipboardManager.clipboardItems.value.isEmpty())
    }

    @Test
    fun addItemUpdatesTimestampForExistingText() {
        clipboardManager.addItem("Test text")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        val firstTimestamp = clipboardManager.clipboardItems.value[0].timestamp

        Thread.sleep(10)
        clipboardManager.addItem("Test text")

        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        val items = clipboardManager.clipboardItems.value
        assertEquals("Should still have one item", 1, items.size)
        assertTrue("Timestamp should be updated", items[0].timestamp >= firstTimestamp)
        assertEquals("Item should be at top", 0, items.indexOfFirst { it.text == "Test text" })
    }

    @Test
    fun addItemMovesExistingItemToTop() {
        clipboardManager.addItem("First")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        clipboardManager.addItem("Second")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })

        assertEquals(listOf("Second", "First"), clipboardManager.clipboardItems.value.map { it.text })

        clipboardManager.addItem("First")

        awaitCondition({ clipboardManager.clipboardItems.value.first().text == "First" })
        assertEquals("First should now be at top", listOf("First", "Second"), clipboardManager.clipboardItems.value.map { it.text })
    }

    @Test
    fun removeItemRemovesItemById() {
        clipboardManager.addItem("Test text")
        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        val itemId = clipboardManager.clipboardItems.value[0].id

        clipboardManager.removeItem(itemId)

        awaitCondition({ clipboardManager.clipboardItems.value.isEmpty() })
    }

    @Test
    fun removeItemNotAffectOtherItems() {
        clipboardManager.addItem("First")
        clipboardManager.addItem("Second")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })
        val firstId = clipboardManager.clipboardItems.value.find { it.text == "First" }!!.id

        clipboardManager.removeItem(firstId)

        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        assertEquals("Second", clipboardManager.clipboardItems.value[0].text)
    }

    @Test
    fun splitItemSplitsTextIntoCharacters() {
        clipboardManager.addItem("你好")
        awaitCondition({ clipboardManager.clipboardItems.value.any { it.text == "你好" } })
        val itemId = clipboardManager.clipboardItems.value.single { it.text == "你好" }.id

        clipboardManager.splitItem(itemId)

        // Wait for this operation, not merely a size that unrelated rows can satisfy.
        awaitCondition({
            val texts = clipboardManager.clipboardItems.value.map { it.text }
            "你好" !in texts && "好" in texts && "你" in texts
        })
        val items = clipboardManager.clipboardItems.value
        // Split characters receive increasing timestamps; history is shown newest first.
        assertEquals(listOf("好", "你"), items.map { it.text })
    }

    @Test
    fun splitItemHandlesSingleCharacter() {
        clipboardManager.addItem("A")
        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        val itemId = clipboardManager.clipboardItems.value[0].id

        clipboardManager.splitItem(itemId)

        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        assertEquals("A", clipboardManager.clipboardItems.value[0].text)
    }

    @Test
    fun clearAllClearsAllItems() {
        clipboardManager.addItem("Item1")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        clipboardManager.addItem("Item2")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })

        clipboardManager.clearAll()

        awaitCondition({ clipboardManager.clipboardItems.value.isEmpty() })
    }

    @Test
    fun clearAllRemovesUnpinnedItems() {
        clipboardManager.addItem("First")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        clipboardManager.addItem("Second")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })
        clipboardManager.addItem("Third")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 3 })

        clipboardManager.clearAll()

        awaitCondition({ clipboardManager.clipboardItems.value.isEmpty() })
    }

    @Test
    fun legacyAddToQuickSendPinsTheExistingRowWithoutCopyingIt() {
        clipboardManager.addItem("Quick text")
        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        val itemId = clipboardManager.clipboardItems.value[0].id

        clipboardManager.addToQuickSend(itemId)

        awaitCondition({ clipboardManager.quickSendItems.value.isNotEmpty() })
        val quickSendItems = clipboardManager.quickSendItems.value
        assertEquals(1, quickSendItems.size)
        assertEquals("Quick text", quickSendItems[0].text)
        assertFalse("No separate quick-send storage is created", quickSendItems[0].isQuickSend)
        assertTrue("The existing record should be fixed", quickSendItems[0].isPinned)
        assertEquals(itemId, quickSendItems[0].id)
        assertEquals(1, clipboardManager.clipboardItems.value.size)
    }

    @Test
    fun removeFromQuickSendRemovesItem() {
        clipboardManager.addItem("Quick text")
        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        val itemId = clipboardManager.clipboardItems.value[0].id
        clipboardManager.addToQuickSend(itemId)
        awaitCondition({ clipboardManager.quickSendItems.value.isNotEmpty() })

        clipboardManager.removeFromQuickSend(itemId)

        awaitCondition({ clipboardManager.quickSendItems.value.isEmpty() })
    }

    @Test
    fun removeItemsRemovesMultipleClipboardItems() {
        clipboardManager.addItem("First")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        clipboardManager.addItem("Second")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })
        clipboardManager.addItem("Third")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 3 })
        val ids = clipboardManager.clipboardItems.value.map { it.id }

        clipboardManager.removeItems(ids)

        awaitCondition({ clipboardManager.clipboardItems.value.isEmpty() })
    }

    @Test
    fun removeItemsKeepsUnselectedFixedRecordsIntact() {
        clipboardManager.addItem("Shared text")
        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        val itemId = clipboardManager.clipboardItems.value[0].id
        clipboardManager.addToQuickSend(itemId)
        awaitCondition({ clipboardManager.quickSendItems.value.isNotEmpty() })
        clipboardManager.addItem("Clipboard only")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })
        val clipboardIds = clipboardManager.clipboardItems.value.filterNot { it.isPinned }.map { it.id }

        clipboardManager.removeItems(clipboardIds)

        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        assertEquals(
            "An unselected fixed record must survive batch deletion",
            1,
            clipboardManager.quickSendItems.value.size
        )
        assertEquals("Shared text", clipboardManager.quickSendItems.value[0].text)
        assertEquals(itemId, clipboardManager.clipboardItems.value.single().id)

        clipboardManager.removeItems(listOf(itemId))
        awaitCondition({ clipboardManager.clipboardItems.value.isEmpty() && clipboardManager.quickSendItems.value.isEmpty() })
    }

    @Test
    fun clearClipboardClearsOnlyUnfixedHistory() {
        clipboardManager.addItem("Clipboard A")
        awaitCondition({ clipboardManager.clipboardItems.value.isNotEmpty() })
        clipboardManager.addItem("Clipboard B")
        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })
        clipboardManager.addQuickSendItem("Quick A")
        awaitCondition({ clipboardManager.quickSendItems.value.isNotEmpty() })

        clipboardManager.clearClipboard()

        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        assertEquals(
            "Fixed records must survive history clearing",
            1,
            clipboardManager.quickSendItems.value.size
        )
        assertEquals("Quick A", clipboardManager.quickSendItems.value[0].text)
        assertEquals("Quick A", clipboardManager.clipboardItems.value.single().text)
    }

    @Test
    fun addQuickSendItemAddsTextDirectly() {
        clipboardManager.addQuickSendItem("Direct quick")

        awaitCondition({ clipboardManager.quickSendItems.value.isNotEmpty() })
        val quickSendItems = clipboardManager.quickSendItems.value
        assertEquals(1, quickSendItems.size)
        assertEquals("Direct quick", quickSendItems[0].text)
        assertFalse("The compatibility method must not create legacy records", quickSendItems[0].isQuickSend)
        assertTrue(quickSendItems[0].isPinned)
        awaitCondition({ clipboardManager.clipboardItems.value.size == 1 })
        assertEquals(quickSendItems[0].id, clipboardManager.clipboardItems.value.single().id)
    }

    @Test
    fun addQuickSendItemNotAddBlankText() {
        clipboardManager.addQuickSendItem("")
        clipboardManager.addQuickSendItem("   ")

        Thread.sleep(200)
        assertTrue("Blank text should not be added to quick send", clipboardManager.quickSendItems.value.isEmpty())
    }

    @Test
    fun getCurrentClipboardTextReturnsNullWhenEmpty() {
        val text = clipboardManager.getCurrentClipboardText()
        assertNull("Should return null when clipboard is empty", text)
    }

    @Test
    fun copyToSystemClipboardSetsClipboardText() {
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            // Android 10+ only allows clipboard reads while the app has focus (or is the default IME).
            awaitCondition({
                var focused = false
                scenario.onActivity { focused = it.hasWindowFocus() }
                focused
            })
            val systemClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val previousClip = systemClipboard.primaryClip
            try {
                clipboardManager.copyToSystemClipboard("Copied text")
                assertEquals("Copied text", clipboardManager.getCurrentClipboardText())
            } finally {
                if (previousClip != null) {
                    systemClipboard.setPrimaryClip(previousClip)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    systemClipboard.clearPrimaryClip()
                }
            }
        }
    }

    @Test
    fun clipboardItemHasCorrectDefaultValues() {
        val item = ClipboardItem(text = "Test")

        assertNotNull(item.id)
        assertEquals("Test", item.text)
        assertFalse(item.isPinned)
        assertFalse(item.isQuickSend)
        assertTrue(item.timestamp > 0)
    }

    @Test
    fun clipboardItemCopyPreservesValues() {
        val original = ClipboardItem(
            id = 123L,
            text = "Original",
            timestamp = 1000L,
            isPinned = true,
            isQuickSend = true
        )

        val copied = original.copy(isPinned = false)

        assertEquals(123L, copied.id)
        assertEquals("Original", copied.text)
        assertEquals(1000L, copied.timestamp)
        assertFalse(copied.isPinned)
        assertTrue(copied.isQuickSend)
    }

    @Test
    fun migrateLegacyPrefsPreservesData() {
        val items = listOf(
            ClipboardItem(id = 1L, text = "Test:::with|||special", timestamp = 1000L, isPinned = true, isQuickSend = false),
            ClipboardItem(id = 2L, text = "Normal text", timestamp = 2000L, isPinned = false, isQuickSend = true)
        )

        val prefs = context.getSharedPreferences("clipboard_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("clipboard_items",
            items.joinToString("|||") { item ->
                "${item.id}:::${item.text.replace("|||", "〈PIPE〉").replace(":::", "〈COLON〉")}:::${item.timestamp}:::${item.isPinned}:::${item.isQuickSend}"
            }
        ).commit()

        clipboardManager.migrateLegacyData()

        awaitCondition({ clipboardManager.clipboardItems.value.size == 2 })
        val loaded = clipboardManager.clipboardItems.value
        assertEquals(setOf("Test:::with|||special", "Normal text"), loaded.map { it.text }.toSet())
        assertTrue(loaded.all { it.isPinned && !it.isQuickSend })
        assertEquals(1000L, loaded.single { it.text == "Test:::with|||special" }.timestamp)
        assertEquals(2000L, loaded.single { it.text == "Normal text" }.timestamp)
    }

    @Test
    fun maxItemsLimitEnforced() {
        // The old 1000-item cap was replaced by a byte-based capacity and paged history.
        clipboardManager.resetHistoryWindow()
        runBlocking {
            storageFixture.database.clipboardDao().insertAll(
                (1..1000).map { ClipboardEntry(text = "Item $it", searchKey = "item$it") }
            )
        }
        awaitCondition({ clipboardManager.unpinnedCount.value == 1000 })
        clipboardManager.addItem("Beyond the old limit")
        awaitCondition({ clipboardManager.unpinnedCount.value == 1001 }, timeoutMs = 15000)
        awaitCondition({ clipboardManager.clipboardItems.value.any { it.text == "Beyond the old limit" } })
        assertEquals(ClipboardManager.HISTORY_PAGE, clipboardManager.clipboardItems.value.size)
        assertEquals(1001, runBlocking { clipboardManager.exportItems().size })
        clipboardManager.loadMoreHistory()
        awaitCondition({ clipboardManager.clipboardItems.value.size == ClipboardManager.HISTORY_PAGE * 2 })
    }
}
