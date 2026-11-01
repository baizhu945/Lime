package com.kingzcheung.xime.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** 逐键测量引擎耗时，定位“键盘慢”发生在哪一层。 */
class EngineLatencyProbeTest {
    @Test fun measurePerKeyAndCandidateLatency(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = RimeEngine.getInstance()
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        engine.initialize(user, shared)
        assertTrue(RimeConfigHelper.ensureDeployment(context))
        assertTrue(engine.ensureSession())
        val out = StringBuilder("LATENCY ")
        for (schema in listOf("rime_ice", "japanese")) {
            if (!engine.switchSchema(schema)) { out.append("$schema=unavailable "); continue }
            engine.clearComposition()
            val keys = "nihongo".map { it.code }
            val perKey = keys.map { code ->
                val t0 = System.nanoTime()
                val result = engine.processKeyAndGetResult(code, 0)
                result // keep
                (System.nanoTime() - t0) / 1_000_000
            }
            val t1 = System.nanoTime()
            engine.getProcessResult(true)
            val candidateMs = (System.nanoTime() - t1) / 1_000_000
            val t2 = System.nanoTime()
            engine.getAllCandidates(50)
            val allMs = (System.nanoTime() - t2) / 1_000_000
            out.append("$schema keys=$perKey candidates=${candidateMs}ms all=${allMs}ms ")
            engine.clearComposition()
        }
        println(out)
    }
}
