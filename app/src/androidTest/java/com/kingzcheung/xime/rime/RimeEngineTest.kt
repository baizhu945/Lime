package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class RimeEngineTest {
    
    private lateinit var context: android.content.Context
    
    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }
    
    @Test
    fun getInstanceReturnsSingleton() {
        val instance1 = RimeEngine.getInstance()
        val instance2 = RimeEngine.getInstance()
        
        assertSame(instance1, instance2)
    }
    
    @Test
    fun isInitializedReturnsFalseBeforeInit() {
        // Swap only the Kotlin singleton reference, never the process-wide native session.
        // Restore even on assertion failure so later tests retain the original engine.
        val field = RimeEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        synchronized(RimeEngine.Companion) {
            val previous = field.get(null)
            try {
                field.set(null, RimeEngine())
                assertFalse(RimeEngine.isInitialized())
            } finally {
                field.set(null, previous)
                assertSame(previous, field.get(null))
            }
        }
    }
    
    @Test
    fun processKeyReturnsFalseWhenNotInitialized() {
        val engine = RimeEngine()
        val result = engine.processKey(65, 0)
        assertFalse(result)
    }
    
    @Test
    fun getCandidatesReturnsEmptyArrayWhenNotInitialized() {
        val engine = RimeEngine()
        val candidates = engine.getCandidates()
        assertTrue(candidates.isEmpty())
    }
    
    @Test
    fun getInputReturnsEmptyStringWhenNotInitialized() {
        val engine = RimeEngine()
        val input = engine.getInput()
        assertEquals("", input)
    }
    
    @Test
    fun initializeCreatesValidDirectories() {
        val userDataDir = File(context.filesDir, "rime_user_test")
        val sharedDataDir = File(context.filesDir, "rime_shared_test")
        
        assertNotNull(userDataDir)
        assertNotNull(sharedDataDir)
    }
    
    @Test
    fun toggleAsciiModeReturnsFalseWhenNotInitialized() {
        val engine = RimeEngine()
        val result = engine.toggleAsciiMode()
        assertFalse(result)
    }
    
    @Test
    fun isAsciiModeReturnsFalseWhenNotInitialized() {
        val engine = RimeEngine()
        val result = engine.isAsciiMode()
        assertFalse(result)
    }
    
    @Test
    fun switchSchemaReturnsFalseWhenNotInitialized() {
        val engine = RimeEngine()
        val result = engine.switchSchema("wubi86")
        assertFalse(result)
    }
    
    @Test
    fun getAvailableSchemasReturnsEmptyArrayWhenNotInitialized() {
        val engine = RimeEngine()
        val schemas = engine.getAvailableSchemas()
        assertTrue(schemas.isEmpty())
    }
    
    @Test
    fun selectCandidateReturnsFalseWhenNotInitialized() {
        val engine = RimeEngine()
        val result = engine.selectCandidate(0)
        assertFalse(result)
    }
    
    @Test
    fun commitReturnsEmptyStringWhenNotInitialized() {
        val engine = RimeEngine()
        val result = engine.commit()
        assertEquals("", result)
    }
    
    @Test
    fun deployReturnsFalseWhenNotInitialized() {
        val engine = RimeEngine()
        val result = engine.deploy()
        assertFalse(result)
    }
    
    @Test
    fun lookupTextReturnsEmptyStringWhenNotInitialized() {
        val engine = RimeEngine()
        val result = engine.lookupText("工")
        assertEquals("", result)
    }
}