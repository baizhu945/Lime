package com.kingzcheung.xime.rime

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.BuildConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Explicit offline regression: isolated user dictionaries, no host editor or personal text. */
class InputQualityProbeTest {
    @Test fun sampleReportedCasesAndVerifyNativeSelection() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val engine = RimeEngine.getInstance()
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        engine.initialize(user, shared)
        assertTrue(RimeConfigHelper.ensureDeployment(context))
        val previousSchema = engine.getCurrentSchema()
        engine.destroy()
        val samples = File(context.filesDir, "ime-lab/samples-${System.currentTimeMillis()}.jsonl")
        val fixtureParent = File(context.filesDir, "ime-lab").apply { mkdirs() }
        val fixture = java.nio.file.Files.createTempDirectory(fixtureParent.toPath(), "run-").toFile()
        var failure: Throwable? = null
        try {
        val manifest = context.assets.open("rime-bundled-manifest.tsv").bufferedReader().use { it.readText() }
        // Entire shipped language assets, with fresh user databases, not copied user preferences.
        manifest.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val fields = line.split('\t')
            File(fixture, fields[3]).apply {
                parentFile?.mkdirs()
                context.assets.open(fields[2]).use { input -> outputStream().use { input.copyTo(it) } }
            }
        }
        for (name in listOf("default.yaml", "symbols.yaml", "punctuation.yaml")) {
            try { context.assets.open("rime/$name").use { input -> File(fixture, name).outputStream().use { input.copyTo(it) } } }
            catch (_: java.io.FileNotFoundException) { }
        }
        File(fixture, "default.custom.yaml").writeText("patch:\n  schema_list:\n    - schema: t9_pinyin\n    - schema: rime_ice\n")
        // Reuse immutable system tables only; no userdb/custom dictionary is copied.
        // Librime validates dictionary checksums during deploy. Prisms/configs are rebuilt
        // for this fixture, so device-specific custom algebra cannot leak into the baseline.
        val compiled = File(fixture, "build").apply { mkdirs() }
        for (dictionary in listOf("rime_ice", "melt_eng", "radical_pinyin", "cyime_t9_english")) {
            for (suffix in listOf("table.bin", "reverse.bin")) {
                val source = File(user, "build/$dictionary.$suffix")
                if (source.isFile) source.copyTo(File(compiled, source.name))
            }
        }
        val cases = JSONArray(instrumentation.context.assets.open("ime_lab/regression_cases.json").bufferedReader().use { it.readText() })
        val violations = mutableListOf<String>()
        val englishSelections = mutableListOf<Pair<String, String>>()
            engine.initialize(fixture.path, fixture.path)
            assertTrue(engine.deploy())
            assertTrue(engine.ensureSession())
            assertTrue(engine.switchSchema("t9_pinyin"))
            engine.setOption("ascii_mode", false)
            engine.setVerboseLogging(false)
            for (iteration in 0 until 5) for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                if (case.isNull("input")) continue
                val input = case.getString("input")
                engine.clearQueuedT9Composition()
                val started = SystemClock.elapsedRealtimeNanos()
                input.forEach { engine.processQueuedT9Key(it.code) }
                val composed = SystemClock.elapsedRealtimeNanos()
                val rows = engine.inspectCandidates(100)
                val finished = SystemClock.elapsedRealtimeNanos()
                val candidates = JSONArray()
                rows.forEach { row -> candidates.put(JSONObject().put("text", row[0]).put("comment", row[1])
                    .put("type", row[2]).put("quality", row[3].toDouble()).put("engine_index", row[4].toInt())
                    .put("start", row[5].toInt()).put("end", row[6].toInt())) }
                val sample = JSONObject().put("case_id", case.getString("id")).put("input", input)
                    .put("engine_input", engine.getInput()).put("candidates", candidates).put("latency_ms", (finished - started) / 1e6)
                    .put("compose_ms", (composed - started) / 1e6).put("snapshot_ms", (finished - composed) / 1e6)
                    .put("metadata", JSONObject().put("device", Build.MODEL).put("abi", Build.SUPPORTED_ABIS[0])
                        .put("version", BuildConfig.VERSION_NAME).put("schema", "t9_pinyin")
                        .put("dictionary", "bundled-clean-userdict").put("iteration", iteration)
                        .put("scenario", if (iteration == 0) "first-query" else "warm-query")
                        .put("latency_scope", "whole-input-engine-plus-top100; excludes UI/plugins"))
                samples.appendText(sample.toString() + "\n")
                if (iteration == 0) {
                    android.util.Log.i("InputQualityProbe", case.getString("id") + " " + input + " " + rows.take(20).joinToString { it[0] + ":" + it[2] })
                    val hasDate = rows.any { it[2] in listOf("date", "cyime_datetime") && it[0].matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                    if (case.getString("id") == "T9-005" && hasDate) violations += "77 still triggers date"
                    if (case.getString("id") == "T9-006" && !hasDate) violations += "7474 missing date"
                    if (case.getString("id") in listOf("T9-007", "T9-008")) {
                        val expected = case.getJSONArray("expected")
                        val index = rows.indexOfFirst { row -> (0 until expected.length()).any { expected.getString(it) == row[0] } }
                        if (index !in 0..19) violations += input + " missing English in top20 (rank=" + (index + 1) + ")"
                        else englishSelections += input to rows[index][0]
                    }
                }
            }
            for ((input, text) in englishSelections) {
                engine.clearQueuedT9Composition(); input.forEach { engine.processQueuedT9Key(it.code) }
                val rows = engine.inspectCandidates(100)
                val index = rows.indexOfFirst { it[0] == text }
                assertTrue(index >= 0)
                assertEquals(rows.map { it[0] }, engine.getAllCandidates(100).map { it.text })
                assertTrue(engine.selectCandidateByGlobalIndex(index)); assertEquals(text, engine.commit())
                engine.clearQueuedT9Composition(); input.forEach { engine.processQueuedT9Key(it.code) }
                val selected = engine.inspectCandidates(100).first { it[0] == text }
                assertTrue("English right-selection consumes all digits", engine.t9SelectCandidate(selected[1], text, text.length))
                assertEquals("", engine.t9GetRemainingDigits())
                engine.clearComposition(); assertEquals("", engine.getInput())
            }
            // Confirmed separated initials: deleting and retyping the last key is equivalent.
            fun separatedSnapshot(): List<String> = engine.inspectCandidates(100).map { it[0] }
            engine.clearQueuedT9Composition()
            "645831316".forEach { engine.processQueuedT9Key(it.code) }
            val separated = separatedSnapshot()
            assertTrue("Explicit boundaries must retain ni jue d n", "你觉得呢" in separated)
            engine.processQueuedT9Key(0xff08); engine.processQueuedT9Key('6'.code)
            assertEquals(separated, separatedSnapshot())
            // Learning happens only in this test's isolated directory; verify 1/5 selections + restart.
            engine.clearQueuedT9Composition(); "5833".forEach { engine.processQueuedT9Key(it.code) }
            val rankBefore = separatedSnapshot().indexOf("觉得")
            assertTrue(rankBefore >= 0)
            repeat(5) { count ->
                assertTrue(engine.t9Memorize("觉得", "jue de"))
                engine.clearQueuedT9Composition(); "5833".forEach { engine.processQueuedT9Key(it.code) }
                if (count == 0 || count == 4) {
                    val rank = separatedSnapshot().indexOf("觉得")
                    android.util.Log.i("InputQualityProbe", "LEARNING selection=" + (count + 1) + " rank=" + (rank + 1))
                    assertTrue(rank in 0..rankBefore)
                }
            }
            engine.destroy(); engine.initialize(fixture.path, fixture.path); assertTrue(engine.ensureSession())
            assertTrue(engine.switchSchema("t9_pinyin")); engine.setOption("ascii_mode", false)
            engine.clearQueuedT9Composition(); "5833".forEach { engine.processQueuedT9Key(it.code) }
            assertTrue("Learning survives restart", separatedSnapshot().indexOf("觉得") in 0..rankBefore)
            // The datetime filter intentionally replaces native date candidates with hints.
            // Disable it to verify the underlying native trigger independently of ranking.
            engine.setOption("_cyime_datetime_off", true)
            for ((digits, expectedDate) in listOf("77" to false, "7474" to true)) {
                engine.clearQueuedT9Composition(); digits.forEach { engine.processQueuedT9Key(it.code) }
                assertEquals("default date input=" + digits, expectedDate, engine.inspectCandidates(100).any { it[2] == "date" })
            }
            // Legacy date: rq custom settings must not bring back the numeric 77 trigger.
            engine.clearQueuedT9Composition()
            engine.destroy()
            File(fixture, "t9_pinyin.custom.yaml").writeText("patch:\n  t9/date_translator/date: rq\n")
            engine.initialize(fixture.path, fixture.path); assertTrue(engine.deploy()); assertTrue(engine.ensureSession())
            assertTrue(engine.switchSchema("t9_pinyin")); engine.setOption("ascii_mode", false)
            engine.setOption("_cyime_datetime_off", true)
            for ((digits, expectedDate) in listOf("77" to false, "7474" to true)) {
                engine.clearQueuedT9Composition(); digits.forEach { engine.processQueuedT9Key(it.code) }
                assertEquals("legacy date: rq input=" + digits, expectedDate, engine.inspectCandidates(100).any { it[2] == "date" })
            }
            engine.clearQueuedT9Composition(); assertTrue(engine.switchSchema("rime_ice"))
            engine.setOption("ascii_mode", false); engine.setInput("rq")
            assertTrue("26-key rq remains date", engine.inspectCandidates(30).any { it[0] == java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date()) })
            if (InstrumentationRegistry.getArguments().getString("sentence_sweep") == "true") {
                val sweep = File(context.filesDir, "ime-lab/sweep-${System.currentTimeMillis()}.jsonl")
                for ((count, cutoff, homophones) in listOf(Triple(3, 0.1, 1), Triple(8, 0.1, 8), Triple(8, 1.0, 8), Triple(8, 1.0, 16))) {
                    engine.destroy()
                    File(fixture, "t9_pinyin.custom.yaml").writeText("patch:\n  translator/max_sentences: $count\n  translator/sentence_cutoff_threshold: $cutoff\n  translator/max_homophones: $homophones\n")
                    engine.initialize(fixture.path, fixture.path); assertTrue(engine.deploy()); assertTrue(engine.ensureSession())
                    assertTrue(engine.switchSchema("t9_pinyin")); engine.setOption("ascii_mode", false)
                    repeat(5) { iteration ->
                        for (i in 0 until cases.length()) {
                            val case = cases.getJSONObject(i)
                            if (case.isNull("input")) continue
                            val input = case.getString("input")
                            engine.clearQueuedT9Composition()
                            val started = SystemClock.elapsedRealtimeNanos()
                            input.forEach { engine.processQueuedT9Key(it.code) }
                            val rows = engine.inspectCandidates(100)
                            val ms = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                            sweep.appendText(JSONObject().put("case_id", case.getString("id")).put("input", input)
                                .put("candidates", JSONArray(rows.map { JSONObject().put("text",it[0]).put("type",it[2]) }))
                                .put("latency_ms", ms).put("metadata", JSONObject().put("max_sentences", count)
                                    .put("cutoff", cutoff).put("max_homophones", homophones).put("iteration", iteration).put("device", Build.MODEL)
                                    .put("scenario",if(iteration==0) "first-query" else "warm-query")).toString()+"\n")
                        }
                    }
                }
                android.util.Log.i("InputQualityProbe", "SWEEP_FILE=" + sweep.path)
            }
            assertTrue(violations.joinToString("; "), violations.isEmpty())
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            fun attempt(action: () -> Unit) {
                try { action() } catch (t: Throwable) {
                    if (failure == null) failure = t else if (failure !== t) failure!!.addSuppressed(t)
                }
            }
            attempt { android.util.Log.i("InputQualityProbe", "RESULT_FILE=" + samples.path) }
            attempt { engine.destroy() }
            attempt { check(fixture.deleteRecursively()) { "Failed to remove isolated fixture: $fixture" } }
            attempt {
                engine.initialize(user, shared); engine.ensureSession()
                if (previousSchema.isNotEmpty()) engine.switchSchema(previousSchema)
            }
            failure?.let { throw it }
        }
    }
}
