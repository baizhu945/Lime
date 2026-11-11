package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.JUnitCore

/** Exercise the whole other class in this process, after real engine initialization. */
class RimeEngineCrossClassIsolationTest {
    @Test
    fun initializedSessionSurvivesUninitializedEngineTests() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val engine = RimeEngine.getInstance()
        assertTrue(engine.ensureSession(10_000L))
        val schema = engine.getCurrentSchema()
        val input = engine.getInput()
        val ascii = engine.isAsciiMode()
        val candidates = engine.getCandidates().toList()

        val result = JUnitCore.runClasses(RimeEngineTest::class.java)

        assertSame(engine, RimeEngine.getInstance())
        assertTrue(RimeEngine.isInitialized())
        assertTrue(engine.ensureSession(10_000L))
        assertEquals(schema, engine.getCurrentSchema())
        assertEquals(input, engine.getInput())
        assertEquals(ascii, engine.isAsciiMode())
        assertEquals(candidates, engine.getCandidates().toList())
        assertTrue("No RimeEngineTest methods ran", result.runCount > 0)
        assertTrue(result.failures.joinToString("\n") { it.toString() }, result.wasSuccessful())
    }
}
