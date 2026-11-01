package com.kingzcheung.xime.ui.theme

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.kingzcheung.xime.settings.XimeConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XimeThemeCatalogTest {
    @Test
    fun assetContainsXimeCatalogAndRepresentativeColors() {
        val asset = listOf(File("src/main/assets/xime.yaml"), File("app/src/main/assets/xime.yaml"))
            .first { it.isFile }
        val config = Yaml(configuration = YamlConfiguration(strictMode = false))
            .decodeFromString(XimeConfig.serializer(), asset.readText())
        val schemes = requireNotNull(config.colorSchemes)
        val ids = setOf(
            "dynamic", "lavender_purple", "ocean_blue", "sunset_orange", "slate_gray",
            "rose_pink", "teal_cyan", "sakura_pink", "sky_blue", "mint_green",
            "lilac_purple", "peach_orange", "cream_beige", "aurora_gradient",
            "sunset_gradient", "ocean_gradient", "purple_gradient", "rose_gradient",
            "forest_gradient", "kitosonita_landscape", "mrfawdi_nature", "olivcelso_desert",
        )
        assertTrue(schemes.keys.containsAll(ids))
        assertEquals(0xFAD2E0L, schemes.getValue("sakura_pink").specialKeyBgColorDark)
        assertEquals(0x5affffffL, schemes.getValue("aurora_gradient").keyBgColorDark)
        assertEquals(listOf(0x001915L, 0x003d38L), schemes.getValue("aurora_gradient").keyboardBackground?.colorsDark)
        assertEquals("themes/olivcelso-desert-bg.jpg", schemes.getValue("olivcelso_desert").keyboardBackground?.src)
    }

    @Test
    fun overlappingIdsUseConfigInsteadOfCyimeRebuiltPaletteOnlyWhenPresent() {
        ximeOverlappingThemeIds.forEach { id ->
            assertTrue(useXimeConfigTheme(id, setOf(id)))
            assertFalse(useXimeConfigTheme(id, emptySet()))
        }
        assertFalse(useXimeConfigTheme("pure_black", setOf("pure_black")))
        assertFalse(useXimeConfigTheme("lavender_purple", setOf("lavender_purple")))
    }
}
