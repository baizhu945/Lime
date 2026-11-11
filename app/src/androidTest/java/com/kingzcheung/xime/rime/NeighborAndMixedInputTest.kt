package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.keyboard.LetterKeyRect
import com.kingzcheung.xime.keyboard.encodeLetterNeighbors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.concurrent.withLock

class NeighborAndMixedInputTest {
    private fun engine(): RimeEngine = runBlocking {
        assertTrue(RimeConfigHelper.prepareEngine(InstrumentationRegistry.getInstrumentation().targetContext))
        RimeEngine.getInstance()
    }
    private fun graph() = encodeLetterNeighbors(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").flatMapIndexed { row, text ->
        text.mapIndexed { col, key -> key to LetterKeyRect(col * 40f + row * 8f, row * 60f, 40f, 60f) }
    }.toMap())

    @Test fun adjacentTypoOffersNativeSelectableCorrectionWithoutRewritingInput() {
        val e = engine()
        // Keep input, candidate index, revision and selection atomic against global IME work.
        RimeEngine.rimeLock.withLock {
        try {
            assertTrue(e.ensureSession()); assertTrue(e.switchSchema("rime_ice"))
            e.setOption("ascii_mode", false); e.clearQueuedComposition()
            e.setNeighborMap(graph())
            "nihso".forEach { e.processQueuedKeyAndGetResult(it.code, 0) }
            assertEquals("nihso", e.getInput())
            val all = e.getAllCandidates(300)
            val candidate = all.firstOrNull { it.text == "你好" }
            assertNotNull("adjacent s→a must offer 你好: ${all.take(20).map { it.text }}", candidate)
            val revision = e.readQueuedComposition()!!.engineRevision
            assertEquals("你好", e.selectCandidateAtRevision(all.indexOf(candidate!!), revision, global = true)!!.committedText)
            e.clearQueuedComposition()
            e.setNeighborMap("")
            "nihso".forEach { e.processQueuedKeyAndGetResult(it.code, 0) }
            assertFalse("disabled geometry must not use hard-coded QWERTY", e.getAllCandidates(300).any { it.text == "你好" })
        } finally { e.setNeighborMap(""); e.clearQueuedComposition() }
        }
    }

    @Test fun englishAndChineseWordsCommitInTheSameChineseMode() {
        val e = engine()
        RimeEngine.rimeLock.withLock {
        try {
            assertTrue(e.ensureSession()); assertTrue(e.switchSchema("rime_ice"))
            e.setOption("ascii_mode", false); e.clearQueuedComposition()
            e.setNeighborMap(graph())
            val output = StringBuilder()
            for ((input, expected) in listOf("nihao" to "你好", "hello" to "hello", "shijie" to "世界", "dacall" to "打call")) {
                input.forEach { e.processQueuedKeyAndGetResult(it.code, 0) }
                val all = e.getAllCandidates(200)
                val candidate = all.firstOrNull { it.text == expected }
                assertNotNull("$input: ${all.take(15).map { it.text }}", candidate)
                val revision = e.readQueuedComposition()!!.engineRevision
                output.append(e.selectCandidateAtRevision(all.indexOf(candidate!!), revision, global = true)!!.committedText)
                assertFalse(e.isAsciiMode())
            }
            assertEquals("你好hello世界打call", output.toString())
        } finally { e.setNeighborMap(""); e.clearQueuedComposition() }
        }
    }
}
