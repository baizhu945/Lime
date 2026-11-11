package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.ln

/** Compile a real dictionary pack and exercise native lookup/selection, not mocked weights. */
class CorpusWeightIntegrationTest {
    @Test fun dictionaryCountsReachNativeWeightsAndSurviveRestart(): Unit = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        assertTrue(RimeConfigHelper.prepareEngine(context))
        val engine = RimeEngine.getInstance()
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        val previous = engine.getCurrentSchema()
        engine.destroy()
        val fixture = File(context.filesDir, "corpus-weight-fixture-${System.nanoTime()}").apply { mkdirs() }
        val report = JSONObject()
        try {
            // Immutable shipped sources only; never fall back to the app's learned DB.
            context.assets.open("rime-bundled-manifest.tsv").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val fields = line.split('\t')
                    File(fixture, fields[3]).also { target ->
                        target.parentFile!!.mkdirs()
                        context.assets.open(fields[2]).use { input -> target.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
            for (name in listOf("default.yaml", "symbols.yaml", "punctuation.yaml")) {
                File(user, name).takeIf { it.isFile }?.copyTo(File(fixture, name), overwrite = true)
            }
            val build = File(fixture, "build").apply { mkdirs() }
            for (dictionary in listOf("rime_ice", "melt_eng", "radical_pinyin", "cyime_t9_english")) {
                for (suffix in listOf("table.bin", "reverse.bin")) {
                    File(user, "build/$dictionary.$suffix").takeIf { it.isFile }?.copyTo(File(build, "$dictionary.$suffix"))
                }
            }
            File(fixture, "default.custom.yaml").writeText("patch:\n  schema_list:\n    - schema: t9_pinyin\n    - schema: rime_ice\n")
            val sourcePath = InstrumentationRegistry.getArguments().getString("corpusLexicon")
            val pack = File(fixture, "cyime_corpus_words.dict.yaml")
            if (sourcePath != null) {
                val source = File(sourcePath).canonicalFile
                require(source.path.startsWith(context.filesDir.canonicalPath + File.separator))
                source.copyTo(pack)
            } else {
                pack.writeText("---\nname: cyime_corpus_words\nversion: '1'\nsort: by_weight\nuse_preset_vocabulary: false\n...\n")
            }
            val privateRows = pack.readLines().count { it.contains('\t') && !it.startsWith("#") }
            // Public synthetic entry exists only in the test fixture, never in the export/app.
            val word = "甲乙甲乙"
            pack.appendText("\n$word\tjia yi jia yi\t12345\n")
            for (schema in listOf("t9_pinyin", "rime_ice")) {
                File(fixture, "$schema.custom.yaml").writeText("patch:\n  translator/packs:\n    - cyime_corpus_words\n  translator/enable_user_dict: false\n")
            }
            engine.initialize(fixture.path, fixture.path)
            assertTrue(engine.deploy()); assertTrue(engine.ensureSession())
            assertTrue(File(build, "cyime_corpus_words.table.bin").length() > 0)
            fun select(schema: String, code: String) {
                assertTrue(engine.switchSchema(schema)); engine.setOption("ascii_mode", false)
                engine.clearQueuedComposition()
                if (schema == "t9_pinyin") engine.clearQueuedT9Composition()
                code.forEach { if (schema == "t9_pinyin") engine.processQueuedT9Key(it.code)
                    else engine.processQueuedKeyAndGetResult(it.code, 0) }
                val rows = engine.inspectCandidates(100)
                assertEquals(listOf("cyime_corpus_words"), engine.getSchemaList(schema, "translator/packs"))
                val at = rows.indexOfFirst { it[0] == word }
                assertTrue("Imported dictionary entry must be reachable in $schema", at >= 0)
                val row = rows[at]
                // A T9 sentence's overall weight includes language-model scoring. Its
                // single dictionary component retains the count-derived entry weight.
                val entryWeight = if (row[2] == "sentence") {
                    val components = row[9].split(';').filter { it.isNotEmpty() }
                    assertEquals("Expected one dictionary component in $schema: ${row.toList()}", 1, components.size)
                    val component = components.single().split(':')
                    assertEquals(word, component[0])
                    component[1].toDouble()
                } else row[7].toDouble()
                assertEquals("Raw count must use Rime's normal log-weight scale in $schema: ${row.toList()}", ln(12345.0) - ln(1e8), entryWeight, 0.00001)
                val revision = engine.readQueuedComposition()!!.engineRevision
                assertEquals(word, engine.selectCandidateAtRevision(at, revision, global = true)!!.committedText)
                assertEquals("", engine.getInput())
            }
            select("rime_ice", "jiayijiayi"); select("t9_pinyin", "5429454294")
            engine.destroy(); engine.initialize(fixture.path, fixture.path); assertTrue(engine.ensureSession())
            select("rime_ice", "jiayijiayi")
            report.put("compiledPrivateRows", privateRows).put("nativeWeightAndSelection", true).put("restart", true)
        } finally {
            File(context.filesDir, "corpus-weight-integration.json").writeText(report.toString(2))
            engine.destroy(); engine.initialize(user, shared); engine.ensureSession()
            if (previous.isNotEmpty()) engine.switchSchema(previous)
            check(fixture.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            fixture.deleteRecursively()
        }
    }
}
