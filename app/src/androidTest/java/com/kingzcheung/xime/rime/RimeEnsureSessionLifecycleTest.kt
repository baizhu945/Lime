package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Destructive process-global lifecycle test: run only in a fresh isolated process. */
class RimeEnsureSessionLifecycleTest {
    @Test fun queuedRequestsCannotCrossDestroyOrRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("limeLifecycleIsolated") == "true")
        assumeFalse(RimeEngine.isInitialized())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File.createTempFile("rime-session-lifecycle-", "", context.cacheDir).apply { check(delete() && mkdir()) }
        val shared = File(root, "shared").apply { check(mkdir()) }
        val user = File(root, "user").apply { check(mkdir()) }
        File(shared, "default.yaml").writeText("schema_list:\n  - schema: lifecycle_test\n")
        File(shared, "lifecycle_test.schema.yaml").writeText("schema:\n  schema_id: lifecycle_test\n  name: Lifecycle Test\n  version: '1'\nengine:\n  processors: []\n  segmentors: []\n  translators: []\n")
        val engine = RimeEngine.getInstance()
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "session-lifecycle-test").apply { isDaemon = true } }
        val worker = AtomicReference<Thread>()
        try {
            engine.initialize(user.path, shared.path)
            assertTrue(engine.deploy())
            for (restart in listOf(false, true)) {
                if (!RimeEngine.isInitialized()) engine.initialize(user.path, shared.path)
                RimeEngine.rimeLock.lock()
                try {
                    val pending = executor.submit<Boolean> {
                        worker.set(Thread.currentThread())
                        engine.ensureSession(10_000)
                    }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while ((worker.get() == null || !RimeEngine.rimeLock.hasQueuedThread(worker.get())) && System.nanoTime() < deadline) {
                        Thread.yield()
                    }
                    assertTrue("ensureSession did not queue", worker.get() != null && RimeEngine.rimeLock.hasQueuedThread(worker.get()))
                    engine.destroy()
                    if (restart) engine.initialize(user.path, shared.path)
                    RimeEngine.rimeLock.unlock()
                    try {
                        assertFalse("old request crossed lifecycle boundary", pending.get(20, TimeUnit.SECONDS))
                    } finally { RimeEngine.rimeLock.lock() }
                    if (restart) {
                        val hasSession = RimeEngine::class.java.getDeclaredMethod("nativeHasSession").apply { isAccessible = true }
                        assertFalse("old request created a new-generation session", hasSession.invoke(engine) as Boolean)
                    }
                } finally { RimeEngine.rimeLock.unlock() }
            }
            assertTrue("new generation should still be usable", engine.ensureSession(10_000))
        } finally {
            executor.shutdownNow()
            if (executor.awaitTermination(20, TimeUnit.SECONDS)) {
                engine.destroy()
                root.deleteRecursively()
            }
        }
    }
}
