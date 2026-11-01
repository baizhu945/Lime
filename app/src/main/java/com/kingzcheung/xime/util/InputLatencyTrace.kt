package com.kingzcheung.xime.util

import android.os.Build
import android.os.SystemClock
import android.os.Trace
import com.kingzcheung.xime.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in Perfetto trace. No input/candidate text; no work in normal Debug/Release typing.
 * Ends at Compose draw submission, not physical display presentation.
 */
internal object InputLatencyTrace {
    private val sequence = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, Long>()
    // Debug 用的可见延迟统计：按键开始 → 绘制提交，32 次一组给中位/p95。
    private val drawn = LongArray(8)
    private var drawnCount = 0
    fun begin(): Int {
        // 即使没开 Perfetto 也计时：可见延迟统计只依赖这里的起止时间点。
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < 29) return 0
        // An off-screen keyboard may never draw. Keep diagnostics bounded and identify drops.
        if (pending.size >= 128) pending.keys.minOrNull()?.let { finish(it, "dropped") }
        val id = sequence.incrementAndGet()
        pending[id] = SystemClock.elapsedRealtimeNanos()
        if (Trace.isEnabled()) Trace.beginAsyncSection("CyIME.input", id)
        return id
    }
    inline fun <T> phase(id: Int, name: String, block: () -> T): T {
        if (id == 0) return block()
        Trace.beginSection("CyIME.$id.$name")
        return try { block() } finally { Trace.endSection() }
    }
    fun finish(id: Int, outcome: String) {
        if (id == 0 || Build.VERSION.SDK_INT < 29) return
        val start = pending.remove(id) ?: return
        if (outcome == "draw-submitted") recordVisibleLatency(SystemClock.elapsedRealtimeNanos() - start)
        if (Trace.isEnabled()) {
            phase(id, outcome) { Trace.setCounter("CyIME.input_us", (SystemClock.elapsedRealtimeNanos() - start) / 1000) }
            Trace.endAsyncSection("CyIME.input", id)
        }
    }

    /** 普通字母按键没有逐命令 id：用一个“最近一次按键”起点，到下一次绘制提交即结束。 */
    private val simpleStart = java.util.concurrent.atomic.AtomicLong(0)

    fun beginSimple() {
        if (BuildConfig.DEBUG) simpleStart.set(SystemClock.elapsedRealtimeNanos())
    }

    fun finishSimple() {
        val start = simpleStart.getAndSet(0)
        if (start != 0L) recordVisibleLatency(SystemClock.elapsedRealtimeNanos() - start)
    }

    /** 只需 Debug 可读：按键到绘制提交的中位/p95，不记录任何输入内容。 */
    private fun recordVisibleLatency(nanos: Long) {
        synchronized(drawn) {
            drawn[drawnCount++] = (nanos / 1_000_000).coerceAtLeast(0)
            if (drawnCount < drawn.size) return
            val sorted = drawn.copyOf().apply { sort() }
            val p95 = (drawnCount * 95 + 99) / 100 - 1
            com.kingzcheung.xime.util.FileLogger.w("VisibleLatency",
                "n=$drawnCount median=${sorted[(drawnCount - 1) / 2]}ms p95=${sorted[p95]}ms max=${sorted.last()}ms")
            drawnCount = 0
        }
    }
}
