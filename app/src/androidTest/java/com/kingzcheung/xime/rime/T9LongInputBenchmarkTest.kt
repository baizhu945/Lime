package com.kingzcheung.xime.rime

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper
import com.kingzcheung.xime.keyboard.RepeatInput

/** Public, deterministic stress strings. No selection/commit or user-dictionary learning. */
class T9LongInputBenchmarkTest {
    private val stressLength get() = InstrumentationRegistry.getArguments()
        .getString("stressLength", "63")!!.toInt()
    private fun repeatedToLength(code: String) = code.repeat((stressLength + code.length - 1) / code.length).take(stressLength)

    @Test fun queuedTypingAndHeldDeleteRemainResponsive(): Unit = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val engine = RimeEngine.getInstance()
        val schema = engine.getCurrentSchema()
        val ascii = engine.isAsciiMode()
        val code = repeatedToLength("746928374625938472635927483629")
        // Match XimeInputMethodService: a dedicated serialized key dispatcher,
        // not the standalone controller's shared Dispatchers.Default fallback.
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "key-process-test").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val queue = InputCommandQueue(scope, worker)
        val controller = T9InputController(engine, inputCommands = queue)
        // Instrumentation changes timing; wall clock is comparable across threads, CPU is per event thread.
        // Never capture digit values, input, candidates, or editor text.
        data class Timing(val id: Int, val event: String, val wall: Long, val cpu: Long, val thread: Long)
        val timing = java.util.concurrent.ConcurrentLinkedQueue<Timing>()
        controller.timingObserver = { id, event ->
            timing.add(Timing(id, event, SystemClock.elapsedRealtimeNanos(), android.os.Debug.threadCpuTimeNanos(), Thread.currentThread().id))
        }
        val samples = JSONArray()
        val deleteSamples = mutableListOf<Double>()
        val main = Handler(Looper.getMainLooper())
        var beatTime = SystemClock.elapsedRealtime()
        var maxMainGap = 0L
        val beat = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                maxMainGap = maxOf(maxMainGap, now - beatTime)
                beatTime = now
                main.postDelayed(this, 16)
            }
        }
        fun drain() {
            val done = CountDownLatch(1)
            controller.enqueueLiteralInput { done.countDown() }
            assertTrue("input queue stalled", done.await(10, TimeUnit.SECONDS))
        }
        try {
            assertTrue(engine.switchSchema("t9_pinyin"))
            engine.setOption("ascii_mode", false)
            engine.clearQueuedT9Composition()
            main.post(beat)
            code.forEachIndexed { index, digit ->
                val pressed = SystemClock.elapsedRealtimeNanos()
                ins.runOnMainSync { controller.onDigitPressed(digit.toString()) }
                controller.enqueueLiteralInput {
                    assertEquals(code.take(index + 1), engine.getInput())
                    samples.put((SystemClock.elapsedRealtimeNanos() - pressed) / 1e6)
                }
                SystemClock.sleep(35) // Faster than typical two-thumb typing, without waiting for decoding.
            }
            drain()
            assertEquals("Every queued key must be checked", code.length, samples.length())
            assertEquals(code, engine.getInput())
            val hold = RepeatInput()
            val empty = CountDownLatch(1)
            var previousLength = code.length
            val deleteStarted = SystemClock.elapsedRealtime()
            val repeatDelete = object : Runnable {
                override fun run() {
                    hold.dispatch {
                        val pressed = SystemClock.elapsedRealtimeNanos()
                        controller.onDeleted { result ->
                            deleteSamples += (SystemClock.elapsedRealtimeNanos() - pressed) / 1e6
                            assertEquals(T9InputController.DeleteResult.DELETED, result)
                            val length = controller.inputBuffer.length
                            assertEquals(previousLength - 1, length)
                            previousLength = length
                            if (length == 0) { hold.stop(); empty.countDown() }
                        }
                    }
                    if (hold.isActive) main.postDelayed(this, 70)
                }
            }
            main.post(repeatDelete)
            try {
                assertTrue("held backspace stalled", empty.await(10, TimeUnit.SECONDS))
            } finally {
                hold.stop()
                main.removeCallbacks(repeatDelete)
            }
            drain()
            assertEquals("", engine.getInput())
            val report = JSONObject().put("keyToEngineCompleteMs", samples)
                .put("heldDeleteTotalMs", SystemClock.elapsedRealtime() - deleteStarted)
                .put("deleteToEngineCompleteMs", JSONArray(deleteSamples))
                .put("maxMainHeartbeatGapMs", maxMainGap)
                .put("scope", "production controller FIFO and Main heartbeat; not actual display frames")
            File(context.getExternalFilesDir(null), "t9-long-queue.json").writeText(report.toString())
            val typing = (0 until samples.length()).map { samples.getDouble(it) }.sorted()
            assertTrue("typing P95 must not accumulate a queue: $typing", typing[(typing.size * .95).toInt()] < 100)
            assertTrue("a queued key must complete within 200 ms: $typing", typing.last() < 200)
            assertEquals(code.length, deleteSamples.size)
            val deleteP95 = deleteSamples.sorted()[(deleteSamples.size * .95).toInt()]
            assertTrue("held delete P95 must fit 70 ms repeat interval: $deleteP95 ms", deleteP95 < 35)
            assertTrue("held delete must not accumulate a queue: $report",
                report.getLong("heldDeleteTotalMs") < (code.length - 1) * 70 + 500)
        } finally {
            main.removeCallbacks(beat)
            drain()
            ins.runOnMainSync { } // collect pending publication events before writing
            val events = JSONArray()
            timing.forEach { sample ->
                events.put(JSONObject().put("id", sample.id).put("event", sample.event)
                    .put("elapsedNanos", sample.wall).put("threadCpuNanos", sample.cpu).put("threadId", sample.thread))
            }
            File(context.getExternalFilesDir(null), "t9-long-timing.json").writeText(
                JSONObject().put("diagnosticPerturbsTiming", true).put("events", events).toString())
            controller.close(); queue.close(); worker.close(); scope.cancel()
            engine.clearQueuedT9Composition()
            engine.switchSchema(schema); engine.setOption("ascii_mode", ascii)
        }
    }

    @Test fun longAmbiguousInputAndBackspace(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val engine = RimeEngine.getInstance()
        val schema = engine.getCurrentSchema()
        val ascii = engine.isAsciiMode()
        val samples = JSONArray()
        val deleteSamples = mutableListOf<Double>()
        val candidates = JSONArray()
        val prefixCandidates = mutableMapOf<Pair<String, Int>, List<List<String>>>()
        val label = InstrumentationRegistry.getArguments().getString("sampleLabel", "sample")!!
            .replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val output = File(context.getExternalFilesDir(null), "t9-long-$label.json")
        fun record(case: String, action: String, length: Int, block: () -> Unit) {
            val start = SystemClock.elapsedRealtimeNanos()
            val cpuStart = android.os.Debug.threadCpuTimeNanos()
            // Diagnostic outer acquisition uses the same fair, reentrant lock as the engine.
            // It slightly perturbs timing; wall time still includes acquisition and the call.
            RimeEngine.rimeLock.lock()
            val acquired = SystemClock.elapsedRealtimeNanos()
            val queued = RimeEngine.rimeLock.queueLength
            try { block() } finally { RimeEngine.rimeLock.unlock() }
            val end = SystemClock.elapsedRealtimeNanos()
            val cpuMs = (android.os.Debug.threadCpuTimeNanos() - cpuStart) / 1e6
            val ms = (end - start) / 1e6
            if (action == "delete") deleteSamples += ms
            samples.put(JSONObject().put("case", case).put("action", action)
                .put("length", length).put("ms", ms).put("threadCpuMs", cpuMs)
                .put("lockWaitMs", (acquired - start) / 1e6).put("lockQueueLength", queued))
            Log.i("T9LongBenchmark", "$case $action $length: $ms ms")
            val visible = engine.inspectCandidates(20).map { it.toList() }
            if (action == "key") prefixCandidates[case to length] = visible
            else if (length > 0) assertEquals("$case prefix $length must not depend on typing vs backspace",
                prefixCandidates[case to length], visible)
            candidates.put(JSONObject().put("case", case).put("action", action).put("length", length)
                .put("candidates", JSONArray(visible)))
        }
        try {
            assertTrue(engine.switchSchema("t9_pinyin"))
            engine.setOption("ascii_mode", false)
            for ((name, code) in listOf(
                "repeated7" to "7".repeat(stressLength),
                "random" to repeatedToLength("746928374625938472635927483629"),
                "sentence" to repeatedToLength("9642633464942649869464748723294"),
                "separators" to repeatedToLength("964'263'346'494'264'986'"),
            )) {
                val onlyCase = InstrumentationRegistry.getArguments().getString("case")
                if (onlyCase != null && onlyCase != name) continue
                deleteSamples.clear()
                engine.clearQueuedT9Composition()
                code.forEachIndexed { index, digit ->
                    record(name, "key", index + 1) {
                        assertNotNull(engine.processQueuedT9KeyAndGetResult(digit.code))
                    }
                    assertEquals(code.take(index + 1), engine.getInput())
                }
                for (length in code.length - 1 downTo 0) {
                    record(name, "delete", length) {
                        assertNotNull(engine.processQueuedT9KeyAndGetResult(0xff08))
                    }
                    assertEquals(code.take(length), engine.getInput())
                }
                val keyTimes = (0 until samples.length()).map { samples.getJSONObject(it) }
                    .filter { it.getString("case") == name && it.getString("action") == "key" }.map { it.getDouble("ms") }.sorted()
                assertTrue("$name typing P95 must stay below 70 ms: $keyTimes", keyTimes[(keyTimes.size * .95).toInt()] < 70)
                assertTrue("$name typing must not freeze a key for 120 ms: $keyTimes", keyTimes.last() < 120)
                val deleteP95 = deleteSamples.sorted()[(deleteSamples.size * .95).toInt()]
                assertTrue("$name backspace P95 must stay below 25 ms: $deleteP95 ms", deleteP95 < 25)
                assertTrue("$name backspace must fit repeat interval: ${deleteSamples.maxOrNull()} ms",
                    deleteSamples.all { it < 70 })
            }
        } finally {
            output.writeText(samples.toString())
            File(context.getExternalFilesDir(null), "t9-long-$label-candidates.json").writeText(candidates.toString())
            engine.clearQueuedT9Composition()
            engine.switchSchema(schema)
            engine.setOption("ascii_mode", ascii)
        }
    }
}
