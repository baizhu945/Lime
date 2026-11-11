package com.kingzcheung.xime.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Log
import com.kingzcheung.xime.settings.InputLanguage
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections

/** Opt-in acoustic integration: install test samples/models locally; never opens a microphone. */
@RunWith(AndroidJUnit4::class)
class LocalSpeechModelsTest {
    @Test fun incompatibleModesRejectJapanese() {
        val manager = AsrModelManager(InstrumentationRegistry.getInstrumentation().targetContext)
        for (mode in listOf("zipformer-zh-int8", SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.ZIPFORMER_TWO_PASS, SpeechModelCatalog.TWO_PASS)) {
            assertFalse(SpeechLanguages.supports(mode, InputLanguage.JAPANESE))
            try {
                manager.selection(mode, InputLanguage.JAPANESE)
                fail("$mode accepted Japanese")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(SpeechLanguages.supports(SpeechModelCatalog.SENSEVOICE, InputLanguage.JAPANESE))
    }

    @Test fun recognizeRecordedSpeechWithEachAvailableEngine() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.getExternalFilesDir(null), "speech-eval")
        assumeTrue("Opt-in: push public test models and WAVs into $root", File(root, "zh.wav").isFile)
        val args = InstrumentationRegistry.getArguments()
        val manager = AsrModelManager(context)
        val results = JSONArray()
        val clips = listOf("zh.wav" to InputLanguage.CHINESE, "en.wav" to InputLanguage.ENGLISH,
            "ja.wav" to InputLanguage.JAPANESE, "long.wav" to InputLanguage.CHINESE)
        for (mode in listOf("zipformer-zh-int8", SpeechModelCatalog.PARAFORMER, SpeechModelCatalog.ZIPFORMER_TWO_PASS, SpeechModelCatalog.TWO_PASS, SpeechModelCatalog.SENSEVOICE)) {
            if (args.getString("mode") != null && args.getString("mode") != mode) continue
            for ((name, language) in clips) {
                if (!SpeechLanguages.supports(mode, language)) continue
                if (args.getString("clip") != null && args.getString("clip") != name) continue
                val wav = File(root, name)
                if (!wav.exists()) continue
            val base = manager.selection(mode, language)
            // Prefer installed private models; use a separate fixture only when it exists
            // and the corresponding installed model is incomplete.
            val firstReady = base.first == null || base.firstDir != null &&
                base.first.files.isNotEmpty() && base.first.files.all { File(base.firstDir, it).let { f -> f.isFile && f.length() > 0 } }
            val secondReady = base.secondDir == null || listOf("model.int8.onnx", "tokens.txt").all {
                File(base.secondDir, it).let { f -> f.isFile && f.length() > 0 }
            }
            val selection = base.copy(
                firstDir = if (firstReady) base.firstDir else base.first?.let { File(root, it.id).takeIf(File::isDirectory) } ?: base.firstDir,
                secondDir = if (secondReady) base.secondDir else File(root, SpeechModelCatalog.SENSEVOICE).takeIf(File::isDirectory) ?: base.secondDir)
            if (!selection.ready) continue
            val loadedAt = System.nanoTime()
            val engine = SherpaSpeechEngine(context, selection)
            assertEquals(selection.first != null && selection.secondDir != null, engine.hasRefinement)
            val loadMs = (System.nanoTime() - loadedAt) / 1e6
            try {
                    val pcm = pcmData(wav.readBytes())
                    val errors = Collections.synchronizedList(mutableListOf<String>())
                    val snapshots = Collections.synchronizedList(mutableListOf<String>())
                    val whole = if (mode == SpeechModelCatalog.SENSEVOICE && pcm.size < 320000) {
                        engine.refine(FloatArray(pcm.size / 2) { i ->
                            (((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 255)).toShort()).toFloat() / 32768f
                        })
                    } else ""
                    val boundaries = JSONArray()
                    val startedAt = System.nanoTime()
                    val session = LocalSpeechSession(engine, { snapshots.add(it) }, { errors.add(it) }, { id, count, reason ->
                        boundaries.put(JSONObject().put("id", id).put("duration_ms", count / 16.0).put("reason", reason.name))
                    })
                    // Simulate capture to measure queue behavior and avoid an artificial batch overload.
                    val chunk = 3200
                    for (start in pcm.indices step chunk) {
                        session.acceptPcm(pcm.copyOfRange(start, (start + chunk).coerceAtMost(pcm.size)))
                        Thread.sleep(100)
                    }
                    val stoppedAt = System.nanoTime()
                    val text = session.finish()
                    val stopMs = (System.nanoTime() - stoppedAt) / 1e6
                    session.awaitIdle()
                    val result = JSONObject().put("mode", mode).put("clip", name).put("text", text)
                        .put("load_ms", loadMs).put("stop_ms", stopMs).put("audio_ms", pcm.size / 32.0)
                        .put("elapsed_ms", (System.nanoTime() - startedAt) / 1e6).put("updates", snapshots.size)
                        .put("errors", JSONArray(errors.toList())).put("regions", boundaries).put("whole_text", whole)
                    results.put(result)
                    Log.i("LocalSpeechModelsTest", result.toString())
                    assertTrue("$mode $name: $errors", errors.isEmpty())
                    assertTrue("$mode $name produced no speech", text.isNotBlank())
                    assertFalse("punctuation was emitted: $text", Regex("[，。！？]").containsMatchIn(text))
                    assertFalse("rich tags were emitted", text.contains("<|"))
                    if (name == "ja.wav") assertTrue("Japanese was not recognized: $text", text.any { it in '\u3040'..'\u30ff' })
                    if (name == "long.wav") assertTrue("No acoustic separation in long speech: $text", text.contains(' '))
            } finally { engine.close() }
            }
        }
        assertTrue("No models installed for evaluation", results.length() > 0)
    }
    private fun pcmData(wav: ByteArray): ByteArray {
        fun int32(i: Int) = (0..3).fold(0) { v, n -> v or ((wav[i + n].toInt() and 255) shl (8 * n)) }
        require(String(wav, 0, 4) == "RIFF" && String(wav, 8, 4) == "WAVE")
        var offset = 12
        while (offset + 8 <= wav.size) {
            val size = int32(offset + 4)
            if (String(wav, offset, 4) == "fmt ") {
                require(wav[offset + 8] == 1.toByte() && wav[offset + 10] == 1.toByte())
                require(int32(offset + 12) == 16000 && wav[offset + 22] == 16.toByte())
            }
            if (String(wav, offset, 4) == "data") return wav.copyOfRange(offset + 8, offset + 8 + size)
            offset += 8 + size + size % 2
        }
        error("No PCM data")
    }
}
