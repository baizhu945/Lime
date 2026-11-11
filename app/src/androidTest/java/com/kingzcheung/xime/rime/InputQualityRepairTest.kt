package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Runs the shipped native decoder, not a second implementation of its ranking. */
class InputQualityRepairTest {
    @Test fun reportedInputsSelectionAndPersistentNegativeFeedback(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val engine = RimeEngine.getInstance()
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        val previous = engine.getCurrentSchema()
        val model = File(user, "t9_ngram.lm")
        assertTrue("Missing non-empty $model: install a real T9 ngram model before running InputQualityRepairTest", model.isFile && model.length() > 0)
        val fixture = File(context.filesDir, "input-quality-repair-fixture").apply { mkdirs() }
        // Recreate only this test's private directory; never touch real user data.
        fixture.listFiles()?.forEach { it.deleteRecursively() }
        File(user).listFiles()?.filter { it.isFile && it.extension in listOf("yaml", "json") }
            ?.forEach { it.copyTo(File(fixture, it.name)) }
        File(user, "build").walkTopDown().filter { it.isFile }.forEach { source ->
            File(fixture, "build/" + source.relativeTo(File(user, "build")).path).also {
                it.parentFile!!.mkdirs(); source.copyTo(it)
            }
        }
        // A shared directory containing the app's userdb defeats isolation: Rime
        // resolves a missing fixture DB through shared-data fallback. Both roots
        // must therefore point to the fixture, which contains compiled assets only.
        assertFalse(fixture.walkTopDown().any { it.name.endsWith(".userdb") })
        // Lua filters and OpenCC data are runtime dependencies, not learned data.
        for (directory in listOf("lua", "opencc")) {
            File(user, directory).takeIf { it.isDirectory }?.copyRecursively(File(fixture, directory))
        }
        File(user).listFiles()?.filter { it.isFile && it.extension == "lua" }
            ?.forEach { it.copyTo(File(fixture, it.name)) }
        model.copyTo(File(fixture, "t9_ngram.lm"))
        val readings = File(fixture, "cyime_readings.txt")
        try {
            context.assets.open("rime_ice/cyime_readings.txt").use { input ->
                readings.outputStream().use { input.copyTo(it) }
            }
        } catch (e: java.io.IOException) {
            throw AssertionError("Missing required bundled runtime resource rime_ice/cyime_readings.txt", e)
        }
        assertTrue("Bundled cyime_readings.txt is empty", readings.length() > 0)
        val args = InstrumentationRegistry.getArguments()
        args.getString("maxHomophones")?.toInt()?.let { count ->
            require(count in 1..16)
            val schema = File(fixture, "build/t9_pinyin.schema.yaml")
            schema.writeText(schema.readText().replace(Regex("(?m)^translator:\n"), "translator:\n  max_homophones: $count\n"))
        }
        val report = JSONObject()
        val samples = JSONArray()
        val failures = mutableListOf<String>()
        fun start(schema: String) {
            engine.initialize(fixture.path, fixture.path)
            assertTrue(engine.ensureSession()); assertTrue(engine.switchSchema(schema))
            engine.setOption("ascii_mode", false)
        }
        fun query(schema: String, input: String, datetimeOff: Boolean? = null): Array<Array<String>> {
            assertTrue(engine.switchSchema(schema))
            datetimeOff?.let { engine.setOption("_cyime_datetime_off", it) }
            engine.clearQueuedComposition()
            if (schema == "t9_pinyin") engine.clearQueuedT9Composition()
            assertEquals("", engine.getInput())
            input.forEach {
                if (schema == "t9_pinyin") engine.processQueuedT9Key(it.code)
                else engine.processQueuedKeyAndGetResult(it.code, 0)
            }
            return engine.inspectCandidates(100)
        }
        fun record(schema: String, code: String, target: String, top: Int) {
            val begin = System.nanoTime()
            val rows = query(schema, code)
            val rank = rows.indexOfFirst { it[0] == target }
            samples.put(JSONObject().put("schema", schema).put("code", code).put("target", target)
                .put("rank", if (rank < 0) -1 else rank + 1)
                .put("elapsedMs", (System.nanoTime() - begin) / 1e6)
                .put("candidates", JSONArray(rows.map { JSONArray(it.toList()) })))
            if (rank !in 0 until top) failures += "$schema $code -> $target: rank ${rank + 1}, expected top $top"
        }
        engine.destroy()
        try {
            start("rime_ice")
            for ((code, target) in linkedMapOf("ddzm" to "到底怎么", "d'd'z'm" to "到底怎么",
                "daodizenme" to "到底怎么", "wzd" to "我知道", "wbzd" to "我不知道",
                "zmb" to "怎么办", "zmhs" to "怎么回事", "wsm" to "为什么", "njsm" to "你叫什么",
                "wjs" to "我就是", "ygwt" to "一个问题", "zgy" to "这个月", "jtws" to "今天晚上")) {
                record("rime_ice", code, target, 10)
            }
            // A complete spelling may outrank the abbreviation; require a usable
            // candidate rather than prescribing the model's single-key winner.
            val singleBegin = System.nanoTime()
            val singleRows = query("t9_pinyin", "3")
            val singleIndex = singleRows.indexOfFirst { it[0] == "的" }
            samples.put(JSONObject().put("schema", "t9_pinyin").put("code", "3").put("target", "的")
                .put("rank", if (singleIndex < 0) -1 else singleIndex + 1)
                .put("first", singleRows.firstOrNull()?.get(0) ?: JSONObject.NULL)
                .put("elapsedMs", (System.nanoTime() - singleBegin) / 1e6)
                .put("candidates", JSONArray(singleRows.map { JSONArray(it.toList()) })))
            if (singleIndex !in 0 until 20) failures += "t9_pinyin 3 -> 的: missing from top 20 (rank ${singleIndex + 1})"
            if (singleIndex >= 0) {
                val comment = singleRows[singleIndex][1].trim().removeSurrounding("［", "］").trim()
                if (comment != "de") failures += "t9_pinyin 3 -> 的: expected pinyin de, got ${singleRows[singleIndex][1]}"
                val revision = engine.readQueuedComposition()!!.engineRevision
                val selected = engine.selectCandidateAtRevision(singleIndex, revision, global = true)
                if (selected?.committedText != "的") failures += "t9_pinyin 3 -> 的: committed ${selected?.committedText}"
                if (engine.getInput().isNotEmpty()) failures += "t9_pinyin 3 -> 的: left residual input"
            }
            for ((code, target) in linkedMapOf("5833" to "觉得", "3242458" to "大概率",
                "9692674264" to "我晚上", "645831316" to "你觉得呢", "94294744" to "下意识",
                "32942426" to "打一遍", "936848" to "问题太", "743663484" to "什么鬼")) {
                record("t9_pinyin", code, target, if (code in listOf("5833", "3242458", "9692674264", "32942426")) 3 else 20)
            }
            for ((code, accepted) in listOf("65" to setOf("OK", "ok"), "77" to setOf("QQ", "qq"))) {
                val rows = query("t9_pinyin", code)
                if (rows.take(20).none { it[0] in accepted }) failures += "$code: missing English $accepted"
                if (code == "77" && rows.any { it[2] == "date" }) failures += "77 must not trigger date"
            }
            // Uniquifier can merge equal-text candidates; inspectCandidates reports the
            // genuine type of the first candidate, not all merged sources. Check what
            // the user sees by text; verify native provenance separately with the filter off.
            fun hasVisibleDate(rows: Array<Array<String>>) = rows.any {
                it[0].matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
            }
            if (hasVisibleDate(query("t9_pinyin", "77", datetimeOff = false))) failures += "77 must not trigger a visible date"
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
            val beforeDate = dateFormat.format(java.util.Date())
            val dateRows = query("t9_pinyin", "7474", datetimeOff = false)
            val afterDate = dateFormat.format(java.util.Date())
            if (dateRows.none { it[0] == beforeDate || it[0] == afterDate }) failures += "7474 missing visible current date"
            try {
                // query switches schema each time, so apply the option after each switch.
                if (query("t9_pinyin", "77", datetimeOff = true).any { it[2] == "date" }) failures += "77 must not trigger native date"
                if (query("t9_pinyin", "7474", datetimeOff = true).none { it[2] == "date" }) failures += "7474 missing native date"
            } finally {
                engine.setOption("_cyime_datetime_off", false)
            }
            // These inputs encode four initials. Longer Han phrases are legitimate
            // predictions, but must be marked as completions, not exact matches.
            for (code in listOf("ddzm", "d'd'z'm", "wbzd", "njsm")) {
                val longRows = query("rime_ice", code).take(20).filter {
                    it[0].length > 4 && it[0].all { ch -> ch in '\u3400'..'\u9fff' }
                }
                for (row in longRows) {
                    if (row[2] != "completion") {
                        failures += "$code: longer candidate ${row[0]} has non-completion type ${row[2]}"
                        continue
                    }
                    // Select the actual full prediction, never a silently truncated
                    // prefix or a source word with additional invisible characters.
                    val current = query("rime_ice", code)
                    val index = current.indexOfFirst { it[0] == row[0] && it[2] == "completion" }
                    if (index < 0) {
                        failures += "$code: completion ${row[0]} disappeared on re-query"
                        continue
                    }
                    val candidate = current[index]
                    if (candidate[5].toInt() != 0 || candidate[6].toInt() != engine.getInput().length)
                        failures += "$code: completion ${row[0]} does not cover the input: ${candidate[5]}..${candidate[6]}"
                    val revision = engine.readQueuedComposition()!!.engineRevision
                    val selected = engine.selectCandidateAtRevision(index, revision, global = true)
                    if (selected?.committedText != row[0]) failures += "$code: completion ${row[0]} committed ${selected?.committedText}"
                    if (engine.getInput().isNotEmpty()) failures += "$code: completion ${row[0]} left residual input"
                }
            }
            val t9 = query("t9_pinyin", "743663484")
            if (t9.take(20).any { it[0] in setOf("神媒体", "审美提") }) failures += "unsupported sentences in top20"

            // Selection must commit only encoded syllables, not its source word's suffix.
            for (code in listOf("ddzm", "d'd'z'm", "daodizenme")) {
                val rows = query("rime_ice", code)
                val index = rows.indexOfFirst { it[0] == "到底怎么" }
                if (index < 0) continue
                assertEquals("Encoded target must not be a completion: $code", false, rows[index][2] == "completion")
                assertEquals("Encoded target must start at the beginning: $code", 0, rows[index][5].toInt())
                assertEquals("Encoded target must cover the full input: $code", engine.getInput().length, rows[index][6].toInt())
                assertEquals("Prefix spelling must preserve paired comment formatting",
                    rows[index][1].startsWith("［"), rows[index][1].endsWith("］"))
                val revision = engine.readQueuedComposition()!!.engineRevision
                val selected = engine.selectCandidateAtRevision(index, revision, global = true)
                if (selected?.committedText != "到底怎么") failures += "$code committed ${selected?.committedText}"
                if (engine.getInput().isNotEmpty()) failures += "$code left residual input"
            }
            // Text deletion is a durable user decision, across all Chinese layouts.
            val before = query("t9_pinyin", "743663484")
            val index = before.indexOfFirst { it[0] == "什么鬼" }
            assertTrue(index >= 0)
            val revision = engine.readQueuedComposition()!!.engineRevision
            assertTrue(engine.deleteCandidateAtRevision(index, revision, global = true))
            assertFalse(query("t9_pinyin", "743663484").any { it[0] == "什么鬼" })
            engine.destroy(); start("t9_pinyin")
            assertFalse(query("t9_pinyin", "743663484").any { it[0] == "什么鬼" })
            assertFalse(query("rime_ice", "shenmegui").any { it[0] == "什么鬼" })
            report.put("persistentHide", true)
        } finally {
            report.put("samples", samples).put("failures", JSONArray(failures))
            File(context.filesDir, "input-quality-repair.json").writeText(report.toString(2))
            engine.destroy(); engine.initialize(user, shared); engine.ensureSession()
            if (previous.isNotEmpty()) engine.switchSchema(previous)
            fixture.deleteRecursively()
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
