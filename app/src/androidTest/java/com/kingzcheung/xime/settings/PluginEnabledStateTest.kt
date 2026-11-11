package com.kingzcheung.xime.settings

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluginEnabledStateTest {
    private lateinit var context: Context
    private val prefix = "instrumentation-${UUID.randomUUID()}"
    private val plugin1 get() = "$prefix-1"
    private val plugin2 get() = "$prefix-2"
    private val plugin3 get() = "$prefix-3"
    private val prefs get() = context.getSharedPreferences("kime_settings", Context.MODE_PRIVATE)
    private fun key(id: String) = "plugin_enabled_$id"

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun restore() {
        // Only remove keys created by this test; never clear the user's settings.
        check(prefs.edit().remove(key(plugin1)).remove(key(plugin2)).remove(key(plugin3)).commit())
    }

    @Test
    fun unknownPluginEnabledStateDefaultsToTrue() {
        // Unknown plugins use the enabled-by-default fallback.
        assertTrue(SettingsPreferences.isPluginEnabled(context, plugin1))
    }

    @Test
    fun pluginEnabledStateCanBeSetToTrue() {
        SettingsPreferences.setPluginEnabled(context, plugin1, true)
        assertTrue(SettingsPreferences.isPluginEnabled(context, plugin1))
    }

    @Test
    fun pluginEnabledStateCanBeToggled() {
        SettingsPreferences.setPluginEnabled(context, plugin1, true)
        assertTrue(SettingsPreferences.isPluginEnabled(context, plugin1))
        SettingsPreferences.setPluginEnabled(context, plugin1, false)
        assertFalse(SettingsPreferences.isPluginEnabled(context, plugin1))
    }

    @Test
    fun differentPluginsHaveIndependentEnabledStates() {
        SettingsPreferences.setPluginEnabled(context, plugin1, true)
        SettingsPreferences.setPluginEnabled(context, plugin2, false)
        assertTrue(SettingsPreferences.isPluginEnabled(context, plugin1))
        assertFalse(SettingsPreferences.isPluginEnabled(context, plugin2))
        SettingsPreferences.setPluginEnabled(context, plugin2, true)
        assertTrue(SettingsPreferences.isPluginEnabled(context, plugin1))
        assertTrue(SettingsPreferences.isPluginEnabled(context, plugin2))
    }

    @Test
    fun pluginEnabledStatePersistsAcrossClear() {
        SettingsPreferences.setPluginEnabled(context, plugin1, false)
        assertFalse(SettingsPreferences.isPluginEnabled(context, plugin1))
        assertTrue(prefs.contains(key(plugin1)))

        // Removing the override is the safe equivalent of clearing this key.
        check(prefs.edit().remove(key(plugin1)).commit())
        assertFalse(prefs.contains(key(plugin1)))
        assertTrue("Removing the override restores the unknown-plugin default", SettingsPreferences.isPluginEnabled(context, plugin1))
    }

    @Test
    fun pluginEnabledStateKeysAreIsolated() {
        SettingsPreferences.setPluginEnabled(context, plugin1, true)
        assertTrue(prefs.getBoolean(key(plugin1), false))
        assertFalse(prefs.contains(key(plugin2)))
        assertFalse(prefs.contains(key(plugin3)))
    }

    @Test
    fun multiplePluginsEnabledStatesAreTrackedIndependently() {
        listOf(plugin1, plugin2, plugin3).forEachIndexed { index, id ->
            SettingsPreferences.setPluginEnabled(context, id, index % 2 == 0)
        }
        assertTrue(SettingsPreferences.isPluginEnabled(context, plugin1))
        assertFalse(SettingsPreferences.isPluginEnabled(context, plugin2))
        assertTrue(SettingsPreferences.isPluginEnabled(context, plugin3))
    }
}
