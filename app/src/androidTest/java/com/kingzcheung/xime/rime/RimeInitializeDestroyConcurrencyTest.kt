package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Run alone in a fresh instrumentation process: librime is process-global. */
class RimeInitializeDestroyConcurrencyTest {
    @Test
    fun destroyDuringInitializationAndSubsequentSessionLifecycle() {
        org.junit.Assume.assumeTrue(
            "Requires an explicitly isolated instrumentation process",
            InstrumentationRegistry.getArguments().getString("limeLifecycleIsolated") == "true"
        )
        assumeFalse("Run this destructive lifecycle test alone in a fresh process", RimeEngine.isInitialized())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File.createTempFile("rime-lifecycle-", "", context.cacheDir).apply {
            check(delete() && mkdir())
        }
        val shared = File(root, "shared").apply { check(mkdir()) }
        val user = File(root, "user").apply { check(mkdir()) }
        File(shared, "default.yaml").writeText("schema_list:\n  - schema: lifecycle_test\n")
        File(shared, "lifecycle_test.schema.yaml").writeText(
            "schema:\n  schema_id: lifecycle_test\n  name: Lifecycle Test\n  version: '1'\nengine:\n  processors: []\n  segmentors: []\n  translators: []\n"
        )
        val engine = RimeEngine.getInstance()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "rime-lifecycle-test").apply { isDaemon = true }
        }
        try {
            // initialize calls this callback while holding the native lock, before publishing
            // isInitialized. This forces destroy to overlap that exact lifecycle window.
            RimeEngine.setDeploymentCallback { deploying, _ ->
                if (deploying) {
                    entered.countDown()
                    check(proceed.await(10, TimeUnit.SECONDS)) { "initialization gate timed out" }
                }
            }
            val initializing = executor.submit { engine.initialize(user.path, shared.path) }
            assertTrue("initialize did not reach the native critical section", entered.await(10, TimeUnit.SECONDS))
            val destroying = executor.submit { engine.destroy() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!RimeEngine.rimeLock.hasQueuedThreads() && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue("destroy did not wait for initialization", RimeEngine.rimeLock.hasQueuedThreads())
            proceed.countDown()
            initializing.get(20, TimeUnit.SECONDS)
            destroying.get(20, TimeUnit.SECONDS)
            assertFalse("destroy must not be lost during initialization", RimeEngine.isInitialized())

            RimeEngine.setDeploymentCallback { _, _ -> }
            engine.initialize(user.path, shared.path)
            assertTrue("fixture deployment failed", engine.deploy())
            assertTrue("session could not be created", engine.ensureSession(10_000))
            assertTrue(engine.getAvailableSchemas().contains("lifecycle_test"))
            // Restore holds the native lock while stopped. An initialize arriving here
            // must queue without taking another lock needed by restore's restart.
            val restoring = CountDownLatch(1)
            val resumeRestore = CountDownLatch(1)
            try {
                val restored = executor.submit<Result<Unit>> {
                    engine.restoreUserData {
                        restoring.countDown()
                        check(resumeRestore.await(10, TimeUnit.SECONDS))
                        Result.success(Unit)
                    }
                }
                assertTrue("restore did not reach its gate", restoring.await(10, TimeUnit.SECONDS))
                val concurrentInit = executor.submit { engine.initialize(user.path, shared.path) }
                val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!RimeEngine.rimeLock.hasQueuedThreads() && System.nanoTime() < restoreDeadline) {
                    Thread.sleep(10)
                }
                assertTrue("initialize did not wait for restore", RimeEngine.rimeLock.hasQueuedThreads())
                resumeRestore.countDown()
                assertTrue(restored.get(20, TimeUnit.SECONDS).isSuccess)
                concurrentInit.get(20, TimeUnit.SECONDS)
            } finally {
                resumeRestore.countDown()
            }
            assertTrue(RimeEngine.isInitialized())
            assertTrue("session could not be recreated", engine.ensureSession(10_000))
            assertTrue(engine.getAvailableSchemas().contains("lifecycle_test"))
        } finally {
            proceed.countDown()
            executor.shutdownNow()
            // Do not race cleanup against a stuck native call or delete its open database.
            if (executor.awaitTermination(20, TimeUnit.SECONDS)) {
                engine.destroy()
                root.deleteRecursively()
            }
            RimeEngine.setDeploymentCallback { _, _ -> }
        }
    }
}
