package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercise a real T9 candidate selection in a separate, unlearned Rime directory. */
class T9ProblemPhraseSelectionTest {
    @Test fun selectsAndCommitsProblemPhrase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val engine = RimeEngine.getInstance()
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        val previous = engine.getCurrentSchema()
        val model = File(user, "t9_ngram.lm")
        assertTrue("Missing T9 model", model.isFile && model.length() > 0)
        val fixture = File(context.filesDir, "t9-problem-selection-fixture").apply { mkdirs() }
        fixture.listFiles()?.forEach { it.deleteRecursively() }
        File(user).listFiles()?.filter { it.isFile && it.extension in listOf("yaml", "json", "lua") }
            ?.forEach { it.copyTo(File(fixture, it.name)) }
        File(user, "build").copyRecursively(File(fixture, "build"))
        for (directory in listOf("lua", "opencc")) {
            File(user, directory).takeIf { it.isDirectory }?.copyRecursively(File(fixture, directory))
        }
        model.copyTo(File(fixture, "t9_ngram.lm"))
        context.assets.open("rime_ice/cyime_readings.txt").use { input ->
            File(fixture, "cyime_readings.txt").outputStream().use { input.copyTo(it) }
        }
        assertFalse(fixture.walkTopDown().any { it.name.endsWith(".userdb") })
        engine.destroy()
        try {
            engine.initialize(fixture.path, fixture.path)
            assertTrue(engine.ensureSession())
            assertTrue(engine.switchSchema("t9_pinyin"))
            engine.setOption("ascii_mode", false)
            engine.clearQueuedComposition()
            engine.clearQueuedT9Composition()
            "936848".forEach { engine.processQueuedT9Key(it.code) }
            assertEquals("936848", engine.getInput())
            val rows = engine.inspectCandidates(100)
            val index = rows.indexOfFirst { it[0] == "问题太" }
            assertTrue("问题太 missing from top 20; rank=${index + 1}", index in 0 until 20)
            val revision = engine.readQueuedComposition()!!.engineRevision
            val selected = engine.selectCandidateAtRevision(index, revision, global = true)
            assertEquals("问题太", selected?.committedText)
            assertTrue("Selection left residual input", engine.getInput().isEmpty())
        } finally {
            engine.destroy()
            engine.initialize(user, shared)
            engine.ensureSession()
            if (previous.isNotEmpty()) engine.switchSchema(previous)
            fixture.deleteRecursively()
        }
    }
}
