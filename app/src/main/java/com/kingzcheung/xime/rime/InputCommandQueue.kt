package com.kingzcheung.xime.rime

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.ContinuationInterceptor

/** One finished queued action: a coarse kind label, queue wait, total execution and engine-lock time. No key or text. */
data class InputCommandSample(val kind: String, val waitMillis: Long, val executionMillis: Long, val engineMillis: Long)

/** Engine-lock time of the queued action running on this thread; installed per action as a coroutine context element. */
object EngineTimeMeter {
    internal val current = ThreadLocal<LongArray?>()
    fun add(nanos: Long) { current.get()?.let { it[0] += nanos } }
}

/** One FIFO for a complete input action, including its suspended editor delivery and cleanup. */
class InputCommandQueue(
    parent: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val reportLatency: (waitMillis: Long, executionMillis: Long) -> Unit = { _, _ -> },
    private val nanoTime: () -> Long = System::nanoTime,
    private val reportFailure: ((Throwable) -> Unit)? = null,
    private val reportSample: (InputCommandSample) -> Unit = {},
    private val onPersistentFailure: (() -> Unit)? = null,
) : AutoCloseable {
    private val consecutiveFailures = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile var passthroughMode = false; private set
    val consecutiveFailureCount: Int get() = consecutiveFailures.get()
    private fun recordSuccess() { if (consecutiveFailures.getAndSet(0) > 0 && passthroughMode) passthroughMode = false }
    private fun recordFailure(failure: Throwable) {
        val n = consecutiveFailures.incrementAndGet()
        val report = reportFailure ?: throw failure
        report(failure)
        if (n >= 3 && !passthroughMode) {
            passthroughMode = true
            runCatching { onPersistentFailure?.invoke() }
        }
    }
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime + dispatcher)
    private data class Entry(val turn: CompletableDeferred<Unit>, val job: Job)
    private val jobs = Channel<Entry>(Channel.UNLIMITED)
    private val pending = java.util.concurrent.atomic.AtomicInteger()
    val hasPendingCommands: Boolean get() = pending.get() != 0

    private suspend fun <T> measured(enqueued: Long, kind: String, meter: LongArray, block: suspend () -> T): T {
        val started = nanoTime()
        try { return block() }
        finally {
            // Diagnostics must never stop input or contain the key/text being processed.
            val wait = (started - enqueued) / 1_000_000
            val execution = (nanoTime() - started) / 1_000_000
            runCatching { reportLatency(wait, execution) }
            runCatching { reportSample(InputCommandSample(kind, wait, execution, meter[0] / 1_000_000)) }
        }
    }

    init {
        scope.launch {
            for (entry in jobs) {
                entry.turn.complete(Unit)
                entry.job.join()
            }
        }
    }

    fun submit(
        context: CoroutineContext = EmptyCoroutineContext,
        kind: String = "other",
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val turn = CompletableDeferred<Unit>()
        val enqueued = nanoTime()
        val meter = LongArray(1)
        val job = scope.launch(context + current.asContextElement(this) + EngineTimeMeter.current.asContextElement(meter)) {
            turn.await()
            try { measured(enqueued, kind, meter) { block() }; recordSuccess() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { recordFailure(failure) }
        }
        enqueue(turn, job)
        return job
    }

    /** A nested engine step belongs to the current action; an external call gets its own FIFO slot. */
    suspend fun <T> execute(kind: String = "other", block: suspend () -> T): T {
        if (current.get() === this) return block()
        val caller = currentCoroutineContext().minusKey(Job).minusKey(ContinuationInterceptor)
        val turn = CompletableDeferred<Unit>()
        val enqueued = nanoTime()
        val meter = LongArray(1)
        val result = scope.async(caller + current.asContextElement(this) + EngineTimeMeter.current.asContextElement(meter)) {
            turn.await(); measured(enqueued, kind, meter, block)
        }
        enqueue(turn, result)
        try { return result.await() }
        finally { result.cancel() }
    }

    private fun enqueue(turn: CompletableDeferred<Unit>, job: Job) {
        pending.incrementAndGet()
        job.invokeOnCompletion { pending.decrementAndGet() }
        if (!jobs.trySend(Entry(turn, job)).isSuccess) job.cancel()
    }

    override fun close() {
        jobs.close()
        lifetime.cancel()
    }

    private companion object {
        val current = ThreadLocal<InputCommandQueue?>()
    }
}
