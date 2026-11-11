package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.concurrent.withLock

class RimeRecreationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun assertChineseWorks(engine: RimeEngine) {
        assertTrue(engine.ensureSession(10_000L))
        // UI probes are deliberately nonblocking: a contended tryLock returns an empty
        // list. Take one atomic snapshot/transaction, not a retry of an empty assertion.
        RimeEngine.rimeLock.withLock {
            assertTrue("Engine destroyed before Chinese verification", RimeEngine.isInitialized())
            val schemas = engine.getAvailableSchemas().toSet()
            assertTrue("Chinese schemas disappeared: $schemas", schemas.containsAll(listOf("rime_ice", "t9_pinyin")))
            for ((schema, code) in listOf("rime_ice" to "zenme", "t9_pinyin" to "93663")) {
                assertTrue("Cannot select $schema", engine.switchSchema(schema))
                engine.setOption("ascii_mode", false)
                engine.clearQueuedComposition()
                engine.setInput(code)
                val candidates = engine.getCandidates().toList()
                assertTrue("No Chinese candidates for $schema/$code: $candidates", candidates.any { it == "怎么" })
            }
            engine.clearQueuedComposition()
        }
    }

    @Test fun preparationRecreatesDestroyedEngineInsteadOfTrustingProcessCache(): Unit = runBlocking {
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val engine = RimeEngine.getInstance()
        assertChineseWorks(engine)
        repeat(3) {
            engine.destroy() // Same native lifecycle as switching away and destroying the IME service.
            assertFalse(RimeEngine.isInitialized())
            assertTrue(RimeConfigHelper.prepareEngine(context))
            assertTrue("Preparation reported success with a destroyed engine (round $it)", RimeEngine.isInitialized())
            assertChineseWorks(engine)
        }
    }

    @Test fun buildFilesAloneCannotReportReadyAfterEngineDestruction(): Unit = runBlocking {
        assertTrue(RimeConfigHelper.prepareEngine(context))
        RimeEngine.getInstance().destroy()
        try {
            assertFalse("A build hash cannot prove a live input engine", RimeConfigHelper.ensureDeployment(context))
            assertFalse(SettingsPreferences.isDeploymentDone(context))
        } finally {
            assertTrue(RimeConfigHelper.prepareEngine(context))
        }
    }

    @Test fun settingsDeploymentRepairsDestroyedEngineAndPublishesRefresh(): Unit = runBlocking {
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val previous = prefs.getLong(RimeConfigHelper.DEPLOYMENT_REVISION, 0L)
        RimeEngine.getInstance().destroy()
        assertTrue(RimeConfigHelper.redeploy(context))
        assertTrue(SettingsPreferences.isDeploymentDone(context))
        assertTrue(prefs.getLong(RimeConfigHelper.DEPLOYMENT_REVISION, 0L) > previous)
        assertChineseWorks(RimeEngine.getInstance())
    }
}
