package com.kingzcheung.xime.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.rime.RimeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import java.util.UUID
import org.junit.Test

class SettingsPreferencesTest {

    private lateinit var context: Context
    private lateinit var testContext: Context
    private val preferenceFiles = mutableSetOf<String>()
    private val prefix = "settings_preferences_test_${UUID.randomUUID()}_"

    @Before
    fun setup() {
        // Instrumentation runs as the target UID; use unique disposable names there,
        // never the target app's real preference namespaces.
        testContext = InstrumentationRegistry.getInstrumentation().targetContext
        context = object : ContextWrapper(testContext) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = prefix + name
                preferenceFiles.add(isolatedName)
                return testContext.getSharedPreferences(isolatedName, mode)
            }
        }
    }

    @After
    fun tearDown() {
        preferenceFiles.forEach { testContext.deleteSharedPreferences(it) }
    }

    private fun rimeStoredSchema(): String? = try {
        if (RimeEngine.isInitialized()) {
            RimeEngine.getInstance().getUserConfigString("var/previously_selected_schema")
                ?.takeIf { it.isNotBlank() }
        } else null
    } catch (_: Throwable) {
        null
    }

    @Test
    fun currentSchemaUsesRimeOrDefaultAndDualWriteTakesPriority() {
        val prefs = context.getSharedPreferences("kime_settings", Context.MODE_PRIVATE)
        val rime = rimeStoredSchema()
        assertEquals(rime ?: "rime_ice", SettingsPreferences.getCurrentSchema(context))
        assertEquals(rime != null, prefs.getBoolean("current_schema_dual", false))
        assertTrue(prefs.edit().putString("current_schema", "wubi98")
            .putBoolean("current_schema_dual", true).commit())
        assertEquals("wubi98", SettingsPreferences.getCurrentSchema(context))
    }

    @Test
    fun darkModePersistsIntegerSetting() {
        assertEquals(SettingsPreferences.defaultDarkMode, SettingsPreferences.getDarkMode(context))

        SettingsPreferences.setDarkMode(context, 0)

        assertEquals(0, SettingsPreferences.getDarkMode(context))
    }

    @Test
    fun darkModeValues() {
        SettingsPreferences.setDarkMode(context, 0)
        assertEquals(0, SettingsPreferences.getDarkMode(context))
        
        SettingsPreferences.setDarkMode(context, 1)
        assertEquals(1, SettingsPreferences.getDarkMode(context))
        
        SettingsPreferences.setDarkMode(context, 2)
        assertEquals(2, SettingsPreferences.getDarkMode(context))
    }

    @Test
    fun soundAndVibrationDefaultsAndUpdatesWork() {
        assertTrue(SettingsPreferences.isSoundEnabled(context))
        assertEquals(20, SettingsPreferences.getSoundVolume(context))
        assertTrue(SettingsPreferences.isVibrationEnabled(context))

        SettingsPreferences.setSoundEnabled(context, false)
        SettingsPreferences.setSoundVolume(context, 72)
        SettingsPreferences.setVibrationEnabled(context, false)

        assertFalse(SettingsPreferences.isSoundEnabled(context))
        assertEquals(72, SettingsPreferences.getSoundVolume(context))
        assertFalse(SettingsPreferences.isVibrationEnabled(context))
        assertEquals("disabled", SettingsPreferences.getHapticMode(context))
        SettingsPreferences.setVibrationEnabled(context, true)
        assertEquals("following_system", SettingsPreferences.getHapticMode(context))
    }

    @Test
    fun soundVolumeBoundaryValues() {
        SettingsPreferences.setSoundVolume(context, 0)
        assertEquals(0, SettingsPreferences.getSoundVolume(context))
        
        SettingsPreferences.setSoundVolume(context, 100)
        assertEquals(100, SettingsPreferences.getSoundVolume(context))
    }

    @Test
    fun hapticModeDefaultsToFollowingSystem() {
        assertEquals("following_system", SettingsPreferences.getHapticMode(context))
    }

    @Test
    fun hapticModePersistsValues() {
        SettingsPreferences.setHapticMode(context, "enabled")
        assertEquals("enabled", SettingsPreferences.getHapticMode(context))

        SettingsPreferences.setHapticMode(context, "disabled")
        assertEquals("disabled", SettingsPreferences.getHapticMode(context))

        SettingsPreferences.setHapticMode(context, "following_system")
        assertEquals("following_system", SettingsPreferences.getHapticMode(context))
    }

    @Test
    fun hapticOnKeyUpDefaultsToFalse() {
        assertFalse(SettingsPreferences.isHapticOnKeyUp(context))
    }

    @Test
    fun vibrationDurationAndAmplitudeDefaultsToZero() {
        assertEquals(0, SettingsPreferences.getVibrationPressDuration(context))
        assertEquals(0, SettingsPreferences.getVibrationLongPressDuration(context))
        assertEquals(0, SettingsPreferences.getVibrationPressAmplitude(context))
        assertEquals(0, SettingsPreferences.getVibrationLongPressAmplitude(context))
    }

    @Test
    fun vibrationDurationAndAmplitudePersist() {
        SettingsPreferences.setVibrationPressDuration(context, 30)
        assertEquals(30, SettingsPreferences.getVibrationPressDuration(context))

        SettingsPreferences.setVibrationLongPressDuration(context, 50)
        assertEquals(50, SettingsPreferences.getVibrationLongPressDuration(context))

        SettingsPreferences.setVibrationPressAmplitude(context, 128)
        assertEquals(128, SettingsPreferences.getVibrationPressAmplitude(context))

        SettingsPreferences.setVibrationLongPressAmplitude(context, 200)
        assertEquals(200, SettingsPreferences.getVibrationLongPressAmplitude(context))
    }

    @Test
    fun keyboardThemeAndToolbarButtonsPersist() {
        assertEquals(SettingsPreferences.defaultKeyboardTheme, SettingsPreferences.getKeyboardTheme(context))
        assertEquals(
            listOf("schema", "emoji", "edit", "clipboard", "handwriting_lookup", "voice"),
            SettingsPreferences.getToolbarButtons(context),
        )

        SettingsPreferences.setKeyboardTheme(context, "sunset")
        SettingsPreferences.setToolbarButtons(context, listOf("symbols", "clipboard"))

        assertEquals("sunset", SettingsPreferences.getKeyboardTheme(context))
        assertEquals(listOf("symbols", "clipboard"), SettingsPreferences.getToolbarButtons(context))
    }

    @Test
    fun multipleThemeChanges() {
        SettingsPreferences.setKeyboardTheme(context, "ocean_blue")
        assertEquals("ocean_blue", SettingsPreferences.getKeyboardTheme(context))
        
        SettingsPreferences.setKeyboardTheme(context, "lavender_purple")
        assertEquals("lavender_purple", SettingsPreferences.getKeyboardTheme(context))
        
        SettingsPreferences.setKeyboardTheme(context, "sunset")
        assertEquals("sunset", SettingsPreferences.getKeyboardTheme(context))
    }

    @Test
    fun pluginEnabledStateIsIsolatedByPluginId() {
        val predictionPlugin = "settings-test-first"
        val emojiPlugin = "settings-test-second"

        // 未保存偏好时会读取插件注册表，未知插件默认启用。
        // 本用例只验证按 id 隔离的显式覆盖，不依赖设备上安装的插件状态。
        SettingsPreferences.setPluginEnabled(context, predictionPlugin, false)
        SettingsPreferences.setPluginEnabled(context, emojiPlugin, false)

        assertFalse(SettingsPreferences.isPluginEnabled(context, predictionPlugin))
        assertFalse(SettingsPreferences.isPluginEnabled(context, emojiPlugin))

        SettingsPreferences.setPluginEnabled(context, predictionPlugin, true)

        assertTrue(SettingsPreferences.isPluginEnabled(context, predictionPlugin))
        assertFalse(SettingsPreferences.isPluginEnabled(context, emojiPlugin))

        SettingsPreferences.setPluginEnabled(context, predictionPlugin, false)
        assertFalse(SettingsPreferences.isPluginEnabled(context, predictionPlugin))
    }
    
    @Test
    fun multiplePluginsCanBeEnabledIndependently() {
        val plugins = listOf("plugin1", "plugin2", "plugin3")
        
        for (plugin in plugins) {
            SettingsPreferences.setPluginEnabled(context, plugin, true)
        }
        
        for (plugin in plugins) {
            assertTrue("Plugin $plugin should be enabled", 
                SettingsPreferences.isPluginEnabled(context, plugin))
        }
        
        SettingsPreferences.setPluginEnabled(context, "plugin2", false)
        
        assertTrue("plugin1 should still be enabled", 
            SettingsPreferences.isPluginEnabled(context, "plugin1"))
        assertFalse("plugin2 should be disabled", 
            SettingsPreferences.isPluginEnabled(context, "plugin2"))
        assertTrue("plugin3 should still be enabled", 
            SettingsPreferences.isPluginEnabled(context, "plugin3"))
    }
    
    @Test
    fun smartPredictionSettings() {
        assertTrue("Smart prediction should be enabled by default",
            SettingsPreferences.isSmartPredictionEnabled(context))
        
        SettingsPreferences.setSmartPredictionEnabled(context, true)
        assertTrue("Smart prediction should be enabled", 
            SettingsPreferences.isSmartPredictionEnabled(context))
        
        SettingsPreferences.setSmartPredictionEnabled(context, false)
        assertFalse("Smart prediction should be disabled", 
            SettingsPreferences.isSmartPredictionEnabled(context))
    }
    
    @Test
    fun sttSettings() {
        assertTrue("STT should be enabled by default",
            SettingsPreferences.isSttEnabled(context))

        SettingsPreferences.setSttEnabled(context, false)
        assertFalse("STT should be disabled", SettingsPreferences.isSttEnabled(context))
        SettingsPreferences.setSttEnabled(context, true)
        assertTrue("STT should be enabled", SettingsPreferences.isSttEnabled(context))
    }
    
    @Test
    fun schemaLegacyMigration() {
        val schemas = listOf("wubi86", "wubi98", "wubi_pinyin")

        val prefs = context.getSharedPreferences("kime_settings", Context.MODE_PRIVATE)
        val rime = rimeStoredSchema()
        for (schema in schemas) {
            assertTrue(prefs.edit().putString("current_schema", schema)
                .remove("current_schema_dual").commit())
            assertEquals("Rime takes priority over legacy, if available",
                rime ?: schema, SettingsPreferences.getCurrentSchema(context))
            assertEquals(rime != null, prefs.getBoolean("current_schema_dual", false))
            assertEquals(rime ?: schema, prefs.getString("current_schema", null))
        }
    }
    
    @Test
    fun toolbarButtonsPersist() {
        SettingsPreferences.setToolbarButtons(context, listOf("symbols", "clipboard"))
        assertEquals(listOf("symbols", "clipboard"), SettingsPreferences.getToolbarButtons(context))
        
        SettingsPreferences.setToolbarButtons(context, emptyList())
        assertTrue(SettingsPreferences.getToolbarButtons(context).isEmpty())
        
        SettingsPreferences.setToolbarButtons(context, listOf("symbols"))
        assertEquals(listOf("symbols"), SettingsPreferences.getToolbarButtons(context))
    }
    
    @Test
    fun clearingPreferencesResetsToDefaults() {
        val defaultDarkMode = SettingsPreferences.defaultDarkMode
        SettingsPreferences.setDarkMode(context, if (defaultDarkMode == 2) 0 else 2)
        SettingsPreferences.setSoundEnabled(context, false)
        
        val prefs = context.getSharedPreferences("kime_settings", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("current_schema", "test_override")
            .putBoolean("current_schema_dual", true).commit())
        assertEquals("test_override", SettingsPreferences.getCurrentSchema(context))
        val rime = rimeStoredSchema()
        assertTrue(prefs.edit().clear().commit())
        assertTrue(prefs.all.isEmpty())

        assertEquals(rime ?: "rime_ice", SettingsPreferences.getCurrentSchema(context))
        assertEquals(rime != null, prefs.getBoolean("current_schema_dual", false))
        assertEquals(rime, prefs.getString("current_schema", null))
        assertEquals("Reading isolated preferences must not alter Rime", rime, rimeStoredSchema())
        assertEquals(defaultDarkMode, SettingsPreferences.getDarkMode(context))
        assertTrue(SettingsPreferences.isSoundEnabled(context))
    }
}
