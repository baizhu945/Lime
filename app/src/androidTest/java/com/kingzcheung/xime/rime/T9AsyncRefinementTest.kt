package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class T9AsyncRefinementTest {
    @Test fun oldWorkerResultsCannotReplaceNewInputOrConsumeAnOldCandidate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = RimeEngine.getInstance()
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        val previous = engine.getCurrentSchema()
        val ascii = engine.isAsciiMode()
        val fixture = File(context.cacheDir, "t9-refinement-fixture")
        fixture.deleteRecursively()
        fixture.mkdirs()
        try {
            File(user).listFiles()?.filter { it.isFile && it.extension in listOf("yaml", "json", "lua") }
                ?.forEach { it.copyTo(File(fixture, it.name)) }
            File(user, "build").copyRecursively(File(fixture, "build"))
            for (directory in listOf("lua", "opencc")) {
                File(user, directory).takeIf { it.isDirectory }?.copyRecursively(File(fixture, directory))
            }
            File(user, "t9_ngram.lm").takeIf { it.isFile }?.copyTo(File(fixture, "t9_ngram.lm"))
            val schema = File(fixture, "build/t9_pinyin.schema.yaml")
            val compiled = schema.readText()
            assertTrue("Missing compiled T9 configuration", compiled.contains("\nt9:\n"))
            assertFalse("Fixture must not inherit a sentence model", compiled.contains("  sentence_model:"))
            schema.writeText(compiled.replace("\nt9:\n", "\nt9:\n  sentence_model: t9_sentence\n"))
            InstrumentationRegistry.getInstrumentation().context.assets.open("refinement_fixture.onnx").use { input ->
                File(fixture, "t9_sentence.onnx").outputStream().use { input.copyTo(it) }
            }
            context.assets.open("rime_chinese/t9_sentence.vocab").use { input ->
                File(fixture, "t9_sentence.vocab").outputStream().use { input.copyTo(it) }
            }
            engine.destroy()
            engine.initialize(fixture.path, fixture.path)
            assertTrue(engine.ensureSession())
            assertTrue(engine.switchSchema("t9_pinyin"))
            engine.setOption("ascii_mode", false)
            engine.clearQueuedT9Composition()
            val keys = "4845247468745394942"
            keys.forEach { engine.processQueuedT9KeyAndGetResult(it.code) }
            val old = engine.readQueuedComposition()!!.engineRevision
            assertTrue(engine.t9RefinementState() != 0)
            engine.processQueuedT9KeyAndGetResult('2'.code)
            assertFalse("new input must invalidate the old snapshot", engine.isCandidateRevisionCurrent(old))
            assertNull(engine.refineQueuedT9Result(old))
            assertNull(engine.selectCandidateAtRevision(0, old, global = true))
            assertNull(engine.readCandidateBatch(old, 0, 20))
            assertEquals(keys + "2", engine.getInput())
            val current = engine.readQueuedComposition()!!
            engine.awaitT9Refinement()
            assertTrue("Real sentence model did not load: ${engine.t9ScoringStatus()}",
                engine.t9ScoringStatus().contains("sentence=ready"))
            assertEquals(keys + "2", engine.getInput())
            assertNull(engine.selectCandidateAtRevision(0, old, global = true))
            assertNull(engine.readCandidateBatch(old, 0, 20))
            val after = engine.readQueuedComposition()!!
            if (engine.isCandidateRevisionCurrent(current.engineRevision)) {
                // The worker may already have finished, or refinement may be a no-op.
                // In either case retaining the revision is valid only for the same snapshot.
                assertEquals(current.preedit, after.preedit)
                assertArrayEquals(current.candidates, after.candidates)
                assertEquals(current.engineRevision, after.engineRevision)
            } else {
                assertNull(engine.selectCandidateAtRevision(0, current.engineRevision, global = true))
                assertNull(engine.readCandidateBatch(current.engineRevision, 0, 20))
            }
            val refined = after.engineRevision
            assertNotNull(engine.readCandidateBatch(refined, 0, 20))
            engine.clearQueuedT9Composition()
            assertNull(engine.refineQueuedT9Result(refined))
            assertEquals(0, engine.t9RefinementState())
            assertEquals("", engine.getInput())
        } finally {
            engine.clearQueuedT9Composition()
            engine.destroy()
            engine.initialize(user, shared)
            assertTrue(engine.ensureSession())
            if (previous.isNotEmpty()) engine.switchSchema(previous)
            engine.setOption("ascii_mode", ascii)
            fixture.deleteRecursively()
        }
    }
}
