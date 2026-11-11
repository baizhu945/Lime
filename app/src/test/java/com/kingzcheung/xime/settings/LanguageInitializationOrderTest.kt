package com.kingzcheung.xime.settings

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LanguageInitializationOrderTest {
    private lateinit var app: Application
    private val preferenceFiles = mutableSetOf<String>()
    private var nextContext = 0

    @Before fun setup() { app = ApplicationProvider.getApplicationContext() }
    @After fun cleanup() { preferenceFiles.forEach { app.deleteSharedPreferences(it) } }

    private fun context(): Context {
        val namespace = "language-initialization-${nextContext++}"
        return object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences("$namespace-$name".also { preferenceFiles += it }, mode)
        }
    }

    @Test fun freshAndCompletedSetupDefaultsEnableOnlyChineseAndEnglish() {
        for (completed in listOf(false, true)) {
            val context = context()
            if (completed) SettingsPreferences.setSetupCompleted(context, true)
            LanguagePreferences.initialize(context)
            val prefs = SettingsPreferences.getPrefsPublic(context)
            assertEquals(setOf("zh", "en"), prefs.getStringSet(LanguagePreferences.KEY, emptySet()))
            assertEquals(setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH, InputLanguage.UNSPECIFIED),
                LanguagePreferences.enabled(context))
            assertEquals(listOf(InputLanguage.CHINESE, InputLanguage.ENGLISH), InputModes.languageOrder(context))
            val initialized = prefs.all
            LanguagePreferences.initialize(context)
            assertEquals(initialized, prefs.all)
        }
    }

    @Test fun newlyRequiredEnglishFollowsLegacyNativeChoicesWithoutEnablingJapanese() {
        val cases = listOf(setOf("ja") to listOf("ja", "en"), setOf("es") to listOf("es", "en"),
            setOf("zh", "ja") to listOf("zh", "ja", "en"))
        for ((chosen, expectedOrder) in cases) {
            val context = context()
            val prefs = SettingsPreferences.getPrefsPublic(context)
            prefs.edit().putStringSet(LanguagePreferences.KEY, chosen).commit()
            LanguagePreferences.initialize(context)
            assertEquals(expectedOrder, InputModes.languageOrder(context).map { it.id })
            assertEquals(chosen + "en", prefs.getStringSet(LanguagePreferences.KEY, emptySet()))
            assertEquals("ja" in chosen, InputLanguage.JAPANESE in LanguagePreferences.enabled(context))
        }
    }

    @Test fun explicitSavedOrderIsPreservedWhenEnglishIsAdded() {
        val context = context()
        val prefs = SettingsPreferences.getPrefsPublic(context)
        prefs.edit().putStringSet(LanguagePreferences.KEY, setOf("ja")).commit()
        InputModes.saveLanguageOrder(context, listOf("en", "ja", "zh"))
        val saved = prefs.getString(InputModes.LANGUAGE_ORDER_KEY, null)
        LanguagePreferences.initialize(context)
        assertEquals(saved, prefs.getString(InputModes.LANGUAGE_ORDER_KEY, null))
        assertEquals(listOf(InputLanguage.ENGLISH, InputLanguage.JAPANESE), InputModes.languageOrder(context))
        assertEquals(setOf("ja", "en"), prefs.getStringSet(LanguagePreferences.KEY, emptySet()))
    }

    @Test fun personalJapaneseChoiceAndLegacyMarkerAreNeverReset() {
        val context = context()
        val prefs = SettingsPreferences.getPrefsPublic(context)
        prefs.edit().putStringSet(LanguagePreferences.KEY, setOf("zh", "ja", "en"))
            .putBoolean("japanese_language_added_v1", false).commit()
        InputModes.saveLanguageOrder(context, listOf("ja", "zh", "en"))
        val personal = prefs.all
        LanguagePreferences.initialize(context)
        assertEquals(personal, prefs.all)
        assertEquals(listOf("ja", "zh", "en"), InputModes.languageOrder(context).map { it.id })
        prefs.edit().putStringSet(LanguagePreferences.KEY, setOf("zh", "en")).commit()
        LanguagePreferences.initialize(context)
        assertFalse(InputLanguage.JAPANESE in LanguagePreferences.enabled(context))
        assertFalse(prefs.getBoolean("japanese_language_added_v1", true))
    }
}
