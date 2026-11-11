package com.kingzcheung.xime.settings

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

class ChineseAssetInstallTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    // Cache files can be reclaimed while these large dictionary assets are copied.
    private val directory = File(base.filesDir, "chinese-asset-install-${java.util.UUID.randomUUID()}")
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir() = directory
    }
    private val target get() = File(directory, "rime")
    @Before fun before() { check(target.mkdirs()) }
    @After fun after() { check(directory.deleteRecursively()) }
    private fun old14() = context.assets.open("rime_chinese/pinyin_14jian.schema.yaml").bufferedReader().use { it.readText() }
        .replace("dictionary: rime_ice", "dictionary: pinyin_simp")

    @Test fun unmodifiedLegacy14MovesToSharedDictionaryWhileMissingResourcesInstall() {
        val schema = File(target, "pinyin_14jian.schema.yaml")
        schema.writeText(old14())
        ChineseSchemas.installAssets(context, target)
        assertTrue(schema.readText().contains("dictionary: rime_ice"))
        assertTrue(File(target, "cn_dicts/tencent.dict.yaml").length() > 10_000_000)
    }

    @Test fun marketOwnershipProtectsEvenAnExactCopyOfTheOldBuiltinSchema() {
        val schema = File(target, "pinyin_14jian.schema.yaml")
        val content = old14()
        schema.writeText(content)
        SchemaManifestManager.getRegistryFile(context).writeText(
            """{"version":1,"files":{"pinyin_14jian.schema.yaml":{"claimedBy":["market-14"]}}}"""
        )
        ChineseSchemas.installAssets(context, target)
        assertEquals(content, schema.readText())
    }

    @Test fun repeatedInstallRetainsMarketDictionaryAndRepairsOnlyMissingResources() {
        ChineseSchemas.installAssets(context, target)
        val flypy14 = SchemaManager.discoverSchemas(context).first { it.schemaId == "double_pinyin_flypy_14jian" }
        assertEquals(InputScheme.DOUBLE_PINYIN, flypy14.scheme)
        assertEquals(InputLanguage.CHINESE, flypy14.language)
        assertTrue(CyimeInputDefaults.visibleSchema(flypy14.schemaId, setOf(flypy14.schemaId)))
        assertEquals(InputLayout.MERGED14, InputProfiles.describe(flypy14.schemaId).layout)
        val dictionary = File(target, "cn_dicts/tencent.dict.yaml")
        val market = "# personal dictionary replacement\n"
        dictionary.writeText(market)
        val schema = File(target, "pinyin_14jian.schema.yaml")
        assertTrue(schema.delete())
        ChineseSchemas.installAssets(context, target)
        assertEquals(market, dictionary.readText())
        assertTrue(schema.readText().contains("dictionary: rime_ice"))
    }
}
