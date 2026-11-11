package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.settings.JapaneseSchemas
import com.kingzcheung.xime.service.JapaneseKanaComposer
import com.kingzcheung.xime.service.processJapaneseEnterIfComposing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 使用发布的完整词库和真实 librime，验证两种布局背后的日文汉字转换。 */
class JapaneseInputTest {
    companion object {
        @org.junit.ClassRule @JvmField val schemas = JapaneseSchemaFixture()
    }
    @Test fun bothJapaneseSchemasConvertRomanizedReadingsIntoKanji() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (userDir, sharedDir) = RimeConfigHelper.initializeRimeDataAsync(context)
        val engine = RimeEngine.getInstance()
        engine.initialize(userDir, sharedDir)
        assertTrue("日语词库部署成功", RimeConfigHelper.ensureDeployment(context))
        assertTrue("部署完成后创建 Rime 会话", engine.ensureSession())
        // The engine/session is process-global. Keep setup, input, candidate lookup and
        // selection in one transaction: UI-style tryLock probes otherwise return defaults.
        RimeEngine.rimeLock.lock()
        val previous = engine.getCurrentSchema()
        try {
            JapaneseSchemas.ids.forEach { schema ->
                assertTrue("切换 $schema", engine.switchSchema(schema))
                engine.setOption("ascii_mode", false)
                engine.clearComposition()
                "nihonngo".forEach { assertTrue("$schema key $it", engine.processKey(it.code, 0)) }
                val candidates = engine.getAllCandidates(50).map { it.text }
                assertTrue("$schema: $candidates", candidates.contains("日本語"))
                val index = candidates.indexOf("日本語")
                assertTrue(engine.selectCandidateByGlobalIndex(index))
                assertEquals("日本語", engine.commit())
                "nihonngo".forEach { assertTrue("$schema reading key $it", engine.processKey(it.code, 0)) }
                assertReadingReturn(engine, "にほんご")
                if (schema == "japanese_kana") verifyKanaModifiers(engine)
                if (schema == "japanese") {
                    // This helper deliberately tests another thread waiting for the lock.
                    RimeEngine.rimeLock.unlock()
                    try { verifyQueuedKeyWaitsForEngine(engine) }
                    finally { RimeEngine.rimeLock.lock() }
                    "katakana".forEach {
                        val code = com.kingzcheung.xime.service.JapaneseTyping.keyCode(it.toString(), true)
                        assertTrue(engine.processKey(code, 0))
                    }
                    assertReadingReturn(engine, "カタカナ")
                    "katakana".forEach { assertTrue(engine.processKey(com.kingzcheung.xime.service.JapaneseTyping.keyCode(it.toString(), false), 0)) }
                    assertReadingReturn(engine, "かたかな")
                }
            }
        } finally {
            try {
                engine.clearComposition()
                if (previous.isNotBlank()) engine.switchSchema(previous)
            } finally { RimeEngine.rimeLock.unlock() }
        }
    }

    @Test fun unfinishedRomajiCompletesWordsWithoutInventingSokuon() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (userDir, sharedDir) = RimeConfigHelper.initializeRimeDataAsync(context)
        val engine = RimeEngine.getInstance()
        engine.initialize(userDir, sharedDir)
        assertTrue(RimeConfigHelper.ensureDeployment(context))
        assertTrue(engine.ensureSession())
        RimeEngine.rimeLock.lock()
        val previous = engine.getCurrentSchema()
        try {
            assertTrue(engine.switchSchema("japanese"))
            engine.setOption("ascii_mode", false)
            fun type(input: String): List<String> {
                engine.clearComposition()
                input.forEach { assertTrue("key $it in $input", engine.processKey(it.code, 0)) }
                val words = engine.getAllCandidates(40).map { it.text }
                android.util.Log.i("JapaneseCompletionTest", "$input: ${engine.getComposition().preedit} / $words")
                return words
            }
            val words = type("yoy")
            assertEquals("よy", engine.getComposition().preedit.replace(" ", ""))
            assertFalse("must not invent っ: $words", "よっ" in words)
            assertTrue("予約 must be reachable from yoy: $words", "予約" in words)
            assertTrue("余裕 must be reachable from yoy: $words", "余裕" in words)
            assertTrue("common completions must be near the front: $words",
                listOf("予約", "余裕", "よい").all { it in words.take(8) })
            val index = words.indexOf("予約")
            assertTrue(engine.selectCandidateByGlobalIndex(index))
            assertEquals("予約", engine.commit())
            assertEquals("", engine.getInput())
            listOf("y", "k", "sh", "ky", "n").forEach { input ->
                val candidates = type(input)
                assertFalse("$input must not become a standalone っ", candidates.firstOrNull() == "っ")
                assertEquals(input, engine.getComposition().preedit.replace(" ", ""))
            }
            mapOf("kitte" to "きって", "gakkou" to "がっこう", "nka" to "んか",
                "nnka" to "んか", "kanya" to "かにゃ", "KATTA" to "カッタ").forEach { (input, reading) ->
                type(input)
                assertEquals(input, reading, engine.getComposition().preedit.replace(" ", ""))
                assertReadingReturn(engine, reading)
            }
            type("yoy")
            engine.setInput(deleteJapaneseRomaji(engine.getInput()))
            assertEquals("よ", engine.getComposition().preedit.replace(" ", ""))
        } finally {
            try {
                engine.clearComposition()
                if (previous.isNotBlank()) engine.switchSchema(previous)
            } finally { RimeEngine.rimeLock.unlock() }
        }
    }

    private fun verifyQueuedKeyWaitsForEngine(engine: RimeEngine) {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        var result: java.util.concurrent.Future<RimeProcessResult>? = null
        try {
            RimeEngine.rimeLock.lock()
            try {
                result = executor.submit<RimeProcessResult> { engine.processQueuedKeyAndGetResult('K'.code, 0) }
                try {
                    result!!.get(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                    fail("后台输入遇到引擎锁应等待，不能立即返回未处理")
                } catch (_: java.util.concurrent.TimeoutException) { }
            } finally { RimeEngine.rimeLock.unlock() }
            val processed = result!!.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("解锁后按键正常进入方案", processed.processed)
            assertEquals("K", processed.inputText)
            val cleared = RimeEngine.rimeLock.run {
                lock()
                try {
                    executor.submit { engine.clearQueuedComposition() }.also { pending ->
                        try {
                            pending.get(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                            fail("取消输入必须等待引擎锁，不能丢失清空操作")
                        } catch (_: java.util.concurrent.TimeoutException) { }
                    }
                } finally { unlock() }
            }
            cleared.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("解锁后取消所有编码", "", engine.getInput())
            assertEquals("取消不应提交原始编码", "", engine.commit())
        } finally { executor.shutdownNow() }
    }

    private fun verifyKanaModifiers(engine: RimeEngine) {
        val composer = JapaneseKanaComposer()
        fun input(romaji: String) {
            val before = engine.getInput()
            val results = romaji.map { engine.processKeyAndGetResult(it.code, 0) }
            assertTrue("假名编码按序进入引擎: $romaji", results.all { it.processed })
            composer.recordInput(before, romaji, engine.getInput(), results.any { it.committedText.isNotEmpty() })
        }
        fun modify(expectedInput: String, expectedKana: String, expectedCandidate: String = expectedKana) {
            val replacement = composer.replacement(engine.getInput())
            assertNotNull("存在仍未提交的假名尾部", replacement)
            assertEquals(expectedInput, replacement!!.input)
            assertTrue("setInput 重建假名组合", engine.setInput(replacement.input))
            composer.recordReplacement(replacement, engine.getInput())
            val composition = engine.getComposition()
            assertEquals(expectedInput, composition.input)
            assertEquals(expectedKana, composition.preedit.replace(" ", ""))
            val candidates = engine.getAllCandidates(100).map { it.text }
            assertTrue("变音候选含 $expectedCandidate: $candidates", expectedCandidate in candidates)
        }

        engine.clearComposition()
        input("ha")
        modify("ba", "ば")
        modify("pa", "ぱ")
        val candidates = engine.getAllCandidates(100)
        assertTrue(engine.selectCandidateByGlobalIndex(candidates.indexOfFirst { it.text == "ぱ" }))
        assertEquals("ぱ", engine.commit())
        assertNull("已提交的假名不能再被变音键替换", composer.replacement(engine.getInput()))
        assertEquals("", engine.getInput())

        input("ki")
        input("ya")
        modify("kixya", "きゃ")
        // 上游词典 ki ya 收录的是 キヤ/木屋，不保证整段平假名出现在转换候选中。
        // 恢复大字后，回车仍必须通过方案提交完整平假名读音，而非原始编码 kiya。
        modify("kiya", "きや", expectedCandidate = "キヤ")
        assertReadingReturn(engine, "きや")
        assertNull("读音回车提交后不能再替换假名", composer.replacement(engine.getInput()))
        input("tu")
        modify("xtu", "っ")
        modify("du", "づ")
        modify("tu", "つ")
    }

    private fun assertReadingReturn(engine: RimeEngine, expectedReading: String) {
        val result = engine.processJapaneseEnterIfComposing()
        assertNotNull("日语组合态走生产回车路径", result)
        assertTrue("日语方案处理原生 Return", result!!.processed)
        assertEquals("回车提交假名读音", expectedReading, result.committedText)
        assertEquals("回车后无剩余编码", "", result.inputText)
        assertEquals("引擎组合同步清空", "", engine.getInput())
        assertEquals("commit 已由结果取走，不得重复上屏", "", engine.commit())
        assertNull("空闲回车留给宿主执行编辑动作", engine.processJapaneseEnterIfComposing())
    }
}
