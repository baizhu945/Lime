package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.service.InputCommandOwner
import com.kingzcheung.xime.service.PendingCandidateCommit
import com.kingzcheung.xime.service.TextCommitResult
import com.kingzcheung.xime.service.deliver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Packaged dictionaries with optional digit suffixes enabled, native selection and persistent learning. */
class T9EnglishRegressionTest {
    @Test fun recallLearningAndCommitIsolation() = runBlocking {
      // Use the same coroutine mutex as app startup/redeploy. Do not hold the native
      // thread-owned rimeLock across accept()'s dispatcher switch or fixture setup.
      val preparation = RimeConfigHelper::class.java.getDeclaredField("preparationMutex").apply {
          isAccessible = true
      }.get(null) as Mutex
      preparation.withLock {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val engine = RimeEngine.getInstance()
        val baseline = InstrumentationRegistry.getArguments().getString("t9_baseline") == "true"
        val labDir = File(context.filesDir, "ime-lab").apply { check(mkdirs() || isDirectory) }
        val fixture = java.nio.file.Files.createTempDirectory(labDir.toPath(), "t9-english-").toFile()
        var user: String? = null
        var shared: String? = null
        var previous = ""
        var replacedEngine = false
        try {
        val paths = RimeConfigHelper.initializeRimeDataAsync(context)
        user = paths.first
        shared = paths.second
        engine.initialize(user, shared)
        assertTrue("production deployment must be usable", RimeConfigHelper.ensureDeployment(context))
        previous = engine.getCurrentSchema()
        replacedEngine = true
        engine.destroy()
        val evidence = File.createTempFile("t9-english-${if (baseline) "before" else "after"}-", ".jsonl", labDir)
        android.util.Log.i("T9EnglishRegression", "RESULT_FILE=${evidence.path}")
        val manifest = context.assets.open("rime-bundled-manifest.tsv").bufferedReader().use { it.readText() }
        manifest.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val fields = line.split('\t')
            File(fixture, fields[3]).apply {
                parentFile?.mkdirs()
                if (fields[3].endsWith(".gram")) {
                    File(user!!, fields[3]).inputStream().use { i -> outputStream().use { i.copyTo(it) } }
                } else context.assets.open(fields[2]).use { i -> outputStream().use { i.copyTo(it) } }
            }
        }
        for (name in listOf("default.yaml", "symbols.yaml", "punctuation.yaml")) {
            try { context.assets.open("rime/$name").use { i -> File(fixture, name).outputStream().use { i.copyTo(it) } } }
            catch (_: java.io.FileNotFoundException) { }
        }
        File(fixture, "default.custom.yaml").writeText("patch:\n  schema_list:\n    - schema: t9_pinyin\n    - schema: rime_ice\n")
        // Dota2 and steam42 are inferred from English prefixes, not dictionary entries.
        // Production disables these optional completions to avoid crowding Chinese results.
        File(fixture, "t9_pinyin.custom.yaml").writeText("patch:\n  t9_english/digit_suffixes: true\n")
        val compiled = File(fixture, "build").apply { mkdirs() }
        for (dict in listOf("rime_ice", "melt_eng", "radical_pinyin", "cyime_t9_english")) {
            for (suffix in listOf("table.bin", "reverse.bin")) {
                val source = File(user!!, "build/$dict.$suffix")
                if (source.isFile) source.copyTo(File(compiled, source.name))
            }
        }
        fun start(deploy: Boolean = false) {
            engine.initialize(fixture.path, fixture.path)
            if (deploy) assertTrue(engine.deploy())
            assertTrue(engine.ensureSession())
            assertTrue(engine.switchSchema("t9_pinyin"))
            engine.setOption("ascii_mode", false)
        }
        fun input(keys: String) {
            engine.clearQueuedT9Composition()
            keys.forEach { engine.processQueuedT9Key(it.code) }
        }
        fun rows() = engine.inspectCandidates(200).map { it.toList() }
        fun record(id: String, keys: String, target: String): List<List<String>> {
            val snapshot = rows()
            evidence.appendText(JSONObject().put("id", id).put("keys", keys).put("target", target)
                .put("rank", snapshot.indexOfFirst { it[0] == target } + 1)
                .put("candidates", JSONArray(snapshot.map { JSONArray(it) })).toString() + "\n")
            return snapshot
        }
        fun select(text: String): Boolean {
            val row = rows().first { it[0] == text }
            val revision = engine.readQueuedComposition()!!.engineRevision
            return engine.selectQueuedT9Candidate(row[1], text, text.codePointCount(0, text.length), revision)!!.first
        }
        val owner = InputCommandOwner(1, 1) {}
        suspend fun accept(text: String, result: TextCommitResult = TextCommitResult.ACCEPTED_HOST): Boolean {
            var learned = false
            withContext(owner.context()) {
                PendingCandidateCommit(owner, text, true).deliver({ result }, {}, {
                    learned = engine.t9MemorizeSelection(it.text)
                    engine.clearComposition()
                })
            }
            return learned
        }
            start(deploy = true)
            // Independent ordinary words, proper names, upstream punctuation and literal numbers.
            val cases = linkedMapOf("steam" to "78326", "hello" to "43556", "world" to "96753",
                "computer" to "26678837", "Android" to "2637643", "GitHub" to "448482",
                "ChatGPT" to "2428478", "Minecraft" to "646327238", "QQ" to "77", "OK" to "65",
                "Dota2" to "36822", "Dota3" to "36823", "steam42" to "7832642",
                "hello88" to "4355688", "CS2" to "272", "Counter-Strike 2" to "272",
                "MP3" to "673", "3D" to "33", "GPT-4" to "4784", "Steam Deck" to "783263325",
                "BitTorrent" to "2488677368", "PlistEdit" to "754783348")
            for ((word, keys) in cases) {
                input(keys)
                val snapshot = record("recall", keys, word)
                if (!baseline) {
                    assertTrue("missing $word for $keys: ${snapshot.take(15).map { it[0] }}", snapshot.any { it[0] == word })
                    assertEquals("UI/native identity", snapshot.map { it[0] }, engine.getAllCandidates(200).map { it.text })
                    engine.processQueuedT9Key(0xff08)
                    engine.processQueuedT9Key(keys.last().code)
                    assertEquals("delete/retype $word", snapshot, rows())
                    assertTrue("the native span consumes all keys: $word", select(word))
                }
            }
            input("78326")
            val before = record("before_learning", "78326", "steam")
            val beforeRank = before.indexOfFirst { it[0] == "steam" }
            val learnedCounts = mutableListOf<Boolean>()
            repeat(5) {
                input("78326")
                assertTrue(select("steam"))
                learnedCounts += accept("steam")
                input("78326")
                record("after_${it + 1}_accepted", "78326", "steam")
            }
            val after = rows()
            if (baseline) return@withLock
            assertTrue("every accepted English selection must learn: $learnedCounts", learnedCounts.all { it })
            assertTrue("steam must move forward from ${beforeRank + 1}", after.indexOfFirst { it[0] == "steam" } < beforeRank)
            assertEquals("user_table", after.first { it[0] == "steam" }[2])

            // Persisted learned entries have custom_code and may have no native syllable IDs.
            engine.destroy(); start()
            input("78326")
            assertEquals("ranking/quality survives a fresh native engine", after, record("restart", "78326", "steam"))
            assertTrue(select("steam")); assertTrue(accept("steam"))

            input("36822")
            val dotaBefore = record("before_learning", "36822", "Dota2")
            repeat(3) { input("36822"); assertTrue(select("Dota2")); assertTrue(accept("Dota2")) }
            input("36822")
            val dotaAfter = record("after_learning", "36822", "Dota2")
            assertTrue(dotaAfter.indexOfFirst { it[0] == "Dota2" } < dotaBefore.indexOfFirst { it[0] == "Dota2" })
            assertEquals(1, dotaAfter.count { it[0] == "Dota2" })

            input("43556")
            val helloBefore = rows()
            assertTrue(select("hello"))
            assertFalse("host rejection must not learn", accept("hello", TextCommitResult.REJECTED))
            input("43556")
            assertEquals("rejected choice has no ranking side effect", helloBefore, rows())
            val stale = engine.readQueuedComposition()!!.engineRevision
            engine.processQueuedT9Key('8'.code)
            assertNull(engine.selectQueuedT9Candidate("", "hello", 5, stale))
            engine.clearQueuedT9Composition()
            assertFalse("cleared/stale capture must not learn", engine.t9MemorizeSelection("hello"))
            input("43556"); assertEquals(helloBefore, rows())
            assertTrue(select("hello"))
            assertFalse("mismatched host text must not learn", engine.t9MemorizeSelection("world"))
            input("43556"); assertEquals(helloBefore, rows())

            // An English suffix after a Chinese partial choice learns into its own DB.
            input("6478326")
            assertFalse(select("你"))
            engine.setInput(engine.t9GetRemainingDigits())
            assertEquals("78326", engine.getInput())
            assertTrue(select("steam"))
            assertTrue(accept("你steam"))
            input("64"); assertTrue(rows().any { it[0] == "你" })
            input("78326"); assertEquals("user_table", rows().first { it[0] == "steam" }[2])
            engine.destroy(); start()
            input("36822")
            assertEquals("Dota2 survives restart", "user_table", record("restart", "36822", "Dota2").first { it[0] == "Dota2" }[2])
        } finally {
            var fixtureDetached = !replacedEngine
            try {
                if (replacedEngine) {
                    engine.destroy()
                    fixtureDetached = true
                    engine.initialize(checkNotNull(user), checkNotNull(shared))
                    check(engine.ensureSession()) { "failed to restore production Rime session" }
                    if (previous.isNotEmpty()) check(engine.switchSchema(previous)) { "failed to restore schema $previous" }
                }
            } finally {
                if (fixtureDetached) check(fixture.deleteRecursively()) { "failed to clean fixture ${fixture.path}" }
                else android.util.Log.e("T9EnglishRegression", "Fixture retained: ${fixture.path}")
            }
        }
      }
    }
}
