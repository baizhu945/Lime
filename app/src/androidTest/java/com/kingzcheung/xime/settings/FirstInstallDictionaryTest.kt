package com.kingzcheung.xime.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FirstInstallDictionaryTest {
    @Test fun modelsPrepareWithoutOpeningMainActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deadline = android.os.SystemClock.elapsedRealtime() + 180_000L
        val manager = com.kingzcheung.xime.model.ModelManager
        // Catalog metadata is needed to inspect models already installed by an earlier run.
        runBlocking { manager.loadFromRemote(context) }
        while (android.os.SystemClock.elapsedRealtime() < deadline &&
            listOf("ochwpro", "paraformer-zh-en-int8", "sensevoice-multilingual-int8").any { !manager.isModelDownloaded(context, it) }) {
            Thread.sleep(500)
        }
        for (id in listOf("ochwpro", "paraformer-zh-en-int8", "sensevoice-multilingual-int8")) assertTrue("default model $id", manager.isModelDownloaded(context, id))
    }

    @Test fun bundledWordsWorkWithoutUserDictionaryOrPrediction() = runBlocking {
        // Application startup uses the same process-global native engine. Hold its entire
        // preparation lock, including across fixture teardown, so it cannot replace the
        // fixture engine between asset installation, initialization and deployment.
        val lockField = RimeConfigHelper::class.java.getDeclaredField("preparationMutex")
        lockField.isAccessible = true
        val preparationLock = lockField.get(RimeConfigHelper) as Mutex
        preparationLock.withLock {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "first-install-${java.util.UUID.randomUUID()}"
        // Android may evict cache files during this large deployment; keep the
        // isolated fixture in filesDir until this test explicitly removes it.
        val fixture = File(app.filesDir, id).apply { check(mkdirs()) }
        val preferenceNames = mutableSetOf<String>()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = fixture
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences("$id-$name".also { preferenceNames.add(it) }, mode)
        }
        val engine = RimeEngine.getInstance()
        val wasInitialized = RimeEngine.isInitialized()
        val previousSchema = if (wasInitialized) engine.getCurrentSchema() else ""
        var engineTakenOver = false
        try {
        // An empty preference store represents first install; prediction is on by default.
        assertTrue(SettingsPreferences.shouldShowCandidateCancelButton(context))
        assertTrue(SettingsPreferences.isSmartPredictionEnabled(context))
        SettingsPreferences.setSmartPredictionEnabled(context, false)
        assertFalse(SettingsPreferences.isSmartPredictionEnabled(context))
        // The fresh files directory contains no learned user dictionary.
        engineTakenOver = true
        engine.destroy()
        val (user, shared) = RimeConfigHelper.initializeRimeDataAsync(context)
        // These layouts ship with the app but are opt-in, so include them in this
        // isolated deployment before exercising their bundled dictionary entries.
        val optional = listOf("pinyin_14jian", "double_pinyin_flypy_14jian")
        SchemaManager.setEnabledSchemas(context, (SchemaManager.getEnabledSchemas(context) + optional).distinct())
        assertTrue(SchemaManager.getEnabledSchemas(context).containsAll(optional))
        engine.initialize(user, shared)
        assertTrue(RimeConfigHelper.ensureDeployment(context))
        assertTrue(engine.ensureSession())
        val samples = mapOf(
            "rime_ice" to listOf("zenme" to "怎么", "zenm" to "怎么", "dazi" to "打字"),
            "t9_pinyin" to listOf("93663" to "怎么", "3294" to "打字"),
            // zenme: zx/er/bn, single m, er.
            "pinyin_14jian" to listOf("ZEBmE" to "怎么"),
            "double_pinyin_flypy" to listOf("zfme" to "怎么"),
            // zfme -> zx/df/m/er; nihk -> bn/ui/gh/cv; ceui -> cv/er/ui/ui.
            // zfmE also covers exact-letter swipes followed by a merged-key tap.
            "double_pinyin_flypy_14jian" to listOf("ZDmE" to "怎么", "BUGC" to "你好", "CEUU" to "测试", "zfmE" to "怎么"))
        for ((schema, cases) in samples) {
            assertTrue(engine.switchSchema(schema))
            engine.setOption("ascii_mode", false)
            for ((code, word) in cases) {
                engine.clearQueuedComposition()
                engine.setInput(code)
                val candidates = engine.getCandidates().toList()
                assertTrue("$schema $code -> $candidates; missing $word", candidates.any { it == word })
            }
        }
        // Do selection only after all fresh-dictionary candidate assertions: selecting
        // can teach librime, but any learned data stays inside this disposable fixture.
        for (schema in listOf("pinyin_14jian", "double_pinyin_flypy_14jian")) {
            assertTrue(engine.switchSchema(schema))
            engine.setOption("ascii_mode", false)
            for ((code, word) in samples.getValue(schema)) {
                engine.clearQueuedComposition()
                assertTrue("$schema $code input", engine.setInput(code))
                val candidates = engine.getCandidates().toList()
                val index = candidates.indexOf(word)
                assertTrue("$schema $code -> $candidates; missing $word before selection", index >= 0)
                val revision = engine.readQueuedComposition()!!.engineRevision
                val result = engine.selectCandidateAtRevision(index, revision)
                assertNotNull("$schema $code selection", result)
                assertEquals("$schema $code committed", word, result!!.committedText)
            }
        }
        engine.clearQueuedComposition()
        val table = java.io.File(context.filesDir, "rime/build/rime_ice.table.bin")
        val backup = java.io.File(table.parentFile, "rime_ice.table.bin.test-backup")
        assertTrue(table.renameTo(backup))
        try { assertFalse("schema YAML alone must not mark deployment complete", RimeConfigHelper.isDeploymentComplete(context)) }
        finally { assertTrue(backup.renameTo(table)) }
        } finally {
            try {
                if (engineTakenOver) engine.destroy()
                if (engineTakenOver && wasInitialized) {
                    val original = File(app.filesDir, "rime").absolutePath
                    engine.initialize(original, original)
                    assertTrue(engine.ensureSession())
                    if (previousSchema.isNotEmpty()) assertTrue(engine.switchSchema(previousSchema))
                }
            } finally {
                preferenceNames.forEach { app.deleteSharedPreferences(it) }
                check(fixture.deleteRecursively())
            }
        }
        }
    }
}
