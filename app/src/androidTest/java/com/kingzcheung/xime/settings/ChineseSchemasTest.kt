package com.kingzcheung.xime.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

class ChineseSchemasTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(base.cacheDir, "chinese-schema-tests")
    private var deviceWidth = 411
    private val context = object : ContextWrapper(base) {
        override fun getResources() = base.createConfigurationContext(
            android.content.res.Configuration(base.resources.configuration).apply { smallestScreenWidthDp = deviceWidth }).resources
        override fun getFilesDir() = directory
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("chinese_test_$name", mode)
    }
    @Before fun before() { File(directory, "rime").mkdirs(); SettingsPreferences.getPrefsPublic(context).edit().clear().commit() }
    @After fun after() {
        // Loading a missing file creates it; never create a layout file in the user's data.
        if (File(base.filesDir, "custom-keyboard-layouts.json").exists()) CustomKeyboardLayouts.load(base)
        else { File(directory, "custom-keyboard-layouts.json").writeText("[]"); CustomKeyboardLayouts.load(context) }
        directory.deleteRecursively()
        SettingsPreferences.getPrefsPublic(context).edit().clear().commit()
    }

    @Test fun freshPhoneAndTabletDefaultsLeaveSampleOptional() {
        // Defaults must not vary with screen dimensions or whether the sample file exists.
        for (width in listOf(411, 934)) {
            deviceWidth = width
            assertEquals(width, context.resources.configuration.smallestScreenWidthDp)
            SettingsPreferences.getPrefsPublic(context).edit().clear().commit()
            val rime = File(directory, "rime")
            ChineseSchemas.installAssets(context, rime)
            assertTrue(File(rime, "${QwjrtkLayout.ID}.schema.yaml").isFile)
            File(rime, "default.custom.yaml").delete()
            assertEquals(CyimeInputDefaults.recommended, SchemaManager.getEnabledSchemas(context))
            assertEquals(CyimeInputDefaults.recommended, SchemaManager.getEnabledSchemas(context))
            SchemaManager.setEnabledSchemas(context, listOf("t9_pinyin", QwjrtkLayout.ID))
            assertEquals(listOf("t9_pinyin", QwjrtkLayout.ID), SchemaManager.getEnabledSchemas(context))
            SchemaManager.setEnabledSchemas(context, listOf("t9_pinyin"))
            assertEquals(listOf("t9_pinyin"), SchemaManager.getEnabledSchemas(context))
        }
    }

    @Test fun migrationAddsOnlyMissingChineseModesOnceAndPreservesOrdering() {
        File(directory, "custom-keyboard-layouts.json").writeText("""[{"id":"pinyin_qwjrtk","name":"个人双指","rows":"q,w,j,r,t,k,u,i,o,p/a,s,d,f,g,h,e,n,l/z,x,c,y,b,v,m","redVowels":false}]""")
        CustomKeyboardLayouts.load(context)
        ChineseSchemas.installAssets(context, File(directory, "rime"))
        assertTrue(File(directory, "rime/${QwjrtkLayout.ID}.schema.yaml").isFile)
        assertEquals("个人双指", CustomKeyboardLayouts.find(QwjrtkLayout.ID)?.name)
        val old = listOf("my_custom", "japanese_kana")
        val updated = ChineseSchemas.addOnFirstUpgrade(context, old)
        assertEquals(old + CyimeInputDefaults.recommended, updated)
        assertFalse(updated.contains("wubi86"))
        assertFalse(updated.contains(QwjrtkLayout.ID))
        val disabledAgain = updated - "t9_pinyin"
        assertEquals(disabledAgain, ChineseSchemas.addOnFirstUpgrade(context, disabledAgain))
    }
    @Test fun absentLanguagePreferenceKeepsJapaneseDisabledAfterSetup() {
        SettingsPreferences.setSetupCompleted(context, true)
        assertEquals(setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH, InputLanguage.UNSPECIFIED), LanguagePreferences.enabled(context))
        LanguagePreferences.initialize(context)
        assertEquals(setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH, InputLanguage.UNSPECIFIED), LanguagePreferences.enabled(context))
        LanguagePreferences.initialize(context)
        assertEquals(setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH, InputLanguage.UNSPECIFIED), LanguagePreferences.enabled(context))
        SettingsPreferences.getPrefsPublic(context).edit()
            .putStringSet(LanguagePreferences.KEY, setOf("zh", "ja")).commit()
        assertEquals(setOf(InputLanguage.CHINESE, InputLanguage.JAPANESE, InputLanguage.ENGLISH, InputLanguage.UNSPECIFIED), LanguagePreferences.enabled(context))
    }
    @Test(expected = IllegalArgumentException::class)
    fun englishCannotBeDisabled() {
        LanguagePreferences.save(context, InputLanguage.ENGLISH, false)
    }
    @Test fun optionalSchemaInstallKeepsAnExistingPersonalLayout() {
        val rime = File(directory, "rime")
        val personal = File(rime, "${QwjrtkLayout.ID}.schema.yaml")
        personal.writeText("# personal schema\n")
        ChineseSchemas.installOptionalLayoutSchemas(context, rime)
        assertEquals("# personal schema\n", personal.readText())
        val cyletix = File(rime, "${Cyletix10Layout.ID}.schema.yaml")
        assertTrue(cyletix.isFile)
        val original = cyletix.readText()
        cyletix.writeText("# personal Cyletix10 schema\n")
        ChineseSchemas.installOptionalLayoutSchemas(context, rime)
        assertEquals("# personal Cyletix10 schema\n", cyletix.readText())
        assertTrue(original.contains("schema_id: ${Cyletix10Layout.ID}"))
    }
    @Test fun earlyReadsCannotPersistPartialAssetListsOrConsumeMigrations() {
        val rime = File(directory, "rime")
        File(rime, SchemaManager.ASSET_INSTALL_MARKER).writeText("installing")
        File(rime, "pinyin_simp.schema.yaml").writeText("schema: {schema_id: pinyin_simp, name: old}")
        val config = File(rime, "default.custom.yaml")
        config.writeText("patch:\n  schema_list:\n    - schema: pinyin_simp\n")
        val original = config.readText()
        assertEquals(CyimeInputDefaults.recommended, SchemaManager.getEnabledSchemas(context))
        assertEquals(original, config.readText())
        assertFalse(SettingsPreferences.isBuiltinSchemasMerged(context))
        assertFalse(SettingsPreferences.getPrefsPublic(context).getBoolean("cyime_chinese_defaults_v1", false))
        ChineseSchemas.installAssets(context, rime)
        File(rime, SchemaManager.ASSET_INSTALL_MARKER).delete()
        val available = SchemaManager.discoverSchemas(context).map { it.schemaId }
        assertTrue(available.containsAll(BuiltinLetterLayouts.ids))
        assertEquals(CyimeInputDefaults.recommended, SchemaManager.getEnabledSchemas(context))
        assertFalse(SchemaManager.getEnabledSchemas(context).any {
            it in JapaneseSchemas.ids || it == "pinyin_14jian" || it in BuiltinLetterLayouts.ids
        })
        SchemaManager.setEnabledSchemas(context, listOf("t9_pinyin", "japanese"))
        assertEquals(listOf("t9_pinyin", "japanese"), SchemaManager.getEnabledSchemas(context))
    }

    @Test fun packagedSchemaHasClearNameAndDoesNotOverwriteMarketReplacement() {
        val target = File(directory, "rime")
        ChineseSchemas.installAssets(context, target)
        val file = File(target, "pinyin_14jian.schema.yaml")
        assertEquals("中文14键", SchemaManager.parseSchemaYaml(file)!!.name)
        val market = file.readText().replace("version: \"1.0\"", "version: \"market\"") + "\n# market replacement\n"
        file.writeText(market)
        ChineseSchemas.installAssets(context, target)
        assertEquals(market, file.readText())
        assertEquals("中文26键", ChineseSchemas.displayName("rime_ice", "雾凇拼音"))
        assertEquals("个人方案", ChineseSchemas.displayName("custom", "个人方案"))
    }
}
