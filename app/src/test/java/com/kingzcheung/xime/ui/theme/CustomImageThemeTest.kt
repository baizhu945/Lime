package com.kingzcheung.xime.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** 「我的图片」：只在图片还在时出现，配色和遮罩沿用选图时的主题。 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class CustomImageThemeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val themes = KeyboardThemes.themes.filter { it.id != CustomImageTheme.ID }

    private fun choose(base: String): File {
        val file = File(SchemaManager.getRimeDir(context), "themes/custom-keyboard-bg-1.jpg")
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1))
        SettingsPreferences.getPrefsPublic(context).edit()
            .putString("custom_theme_image_src", "themes/custom-keyboard-bg-1.jpg")
            .putString("custom_theme_image_base", base).commit()
        return file
    }

    @After fun clear() {
        SettingsPreferences.getPrefsPublic(context).edit()
            .remove("custom_theme_image_src").remove("custom_theme_image_base")
            .remove("custom_theme_image_placement").commit()
        File(SchemaManager.getRimeDir(context), "themes").listFiles()
            ?.filter { it.name.startsWith("custom-keyboard-bg-") }?.forEach { it.delete() }
    }

    @Test fun absentUntilAnImageIsChosen() {
        assertNull(CustomImageTheme.build(context, themes))
        choose("mint_green").delete()
        assertNull(CustomImageTheme.build(context, themes))
    }

    @Test fun keepsTheBaseColoursAndTintsTheImageWithItsBackground() {
        choose("mint_green")
        val base = themes.first { it.id == "mint_green" }
        val custom = CustomImageTheme.build(context, themes)!!
        assertEquals(CustomImageTheme.ID, custom.id)
        assertEquals(base.keyBgLight, custom.keyBgLight)
        assertEquals(base.candidateTextColorDark, custom.candidateTextColorDark)
        val bg = custom.keyboardBackground!!
        assertEquals("image", bg.type)
        assertEquals("themes/custom-keyboard-bg-1.jpg", bg.src)
        assertEquals((base.keyboardBgLight.toArgb() and 0xFFFFFF).toLong(), bg.overlayColor)
        assertEquals((base.keyboardBgDark.toArgb() and 0xFFFFFF).toLong(), bg.overlayColorDark)
        assertEquals(ThemeIconColors.forTheme("mint_green"), ThemeIconColors.forTheme(CustomImageTheme.ID))
    }

    @Test fun glassHasNoSolidBackgroundSoSoftBlueIsUsed() {
        choose(TransparentGlassTheme.ID)
        assertEquals(themes.first { it.id == SoftBlueTheme.ID }.keyBgLight, CustomImageTheme.build(context, themes)!!.keyBgLight)
    }

    @Test fun oldSettingsWithoutPlacementStillCoverCentred() {
        choose("mint_green")
        val bg = CustomImageTheme.build(context, themes)!!.keyboardBackground!!
        assertEquals("cover", bg.fit)
        assertEquals(1f, bg.zoom)
        assertEquals(0.5f, bg.alignX)
        assertEquals(0.5f, bg.alignY)
    }

    @Test fun commitKeepsTheAdjustedPlacementAndRemovesOtherImages() {
        val old = choose("mint_green")
        val fresh = File(old.parentFile, "custom-keyboard-bg-2.jpg").apply { writeBytes(byteArrayOf(2)) }
        CustomImageTheme.commit(context, "themes/custom-keyboard-bg-2.jpg", "mint_green",
            ThemeImagePlacement("fit_height", 1f, 0f, 0.5f))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        val bg = CustomImageTheme.build(context, themes)!!.keyboardBackground!!
        assertEquals("themes/custom-keyboard-bg-2.jpg", bg.src)
        assertEquals("fit_height", bg.fit)
        assertEquals(0f, bg.alignX)
    }

    @Test fun cancellingANewPickDeletesOnlyThatFile() {
        val inUse = choose("mint_green")
        val picked = File(inUse.parentFile, "custom-keyboard-bg-3.jpg").apply { writeBytes(byteArrayOf(3)) }
        CustomImageTheme.discard(context, "themes/custom-keyboard-bg-3.jpg")
        assertFalse(picked.exists())
        // 调整已在用的图片后取消，不能把它删掉。
        CustomImageTheme.discard(context, "themes/custom-keyboard-bg-1.jpg")
        assertTrue(inUse.exists())
    }

    @Test fun deleteRemovesTheThemeAndReturnsTheBase() {
        val file = choose("mint_green")
        assertEquals("mint_green", CustomImageTheme.delete(context))
        assertFalse(file.exists())
        assertFalse(CustomImageTheme.hasImage(context))
        assertNull(CustomImageTheme.build(context, themes))
    }

    @Test fun temporaryImageInheritsExplicitFunctionAndEnterColorsFromSameBase() {
        val base = SoftBlueTheme.create("imported", "Imported").copy(
            specialKeyDark = androidx.compose.ui.graphics.Color.Red,
            specialKeyTextColorDark = androidx.compose.ui.graphics.Color.Green,
            enterKeyDark = androidx.compose.ui.graphics.Color.Blue,
            useThemeColors = false,
        )
        val catalogue = themes + base
        val preview = CustomImageTheme.preview(catalogue, base.id, "themes/pending.jpg", ThemeImagePlacement())!!
        assertEquals(base.id, CustomImageTheme.previewColorThemeId(catalogue, base.id))
        assertEquals(base.specialKeyDark, preview.specialKeyDark)
        assertEquals(base.specialKeyTextColorDark, preview.specialKeyTextColorDark)
        assertEquals(base.enterKeyDark, preview.enterKeyDark)
        assertEquals(androidx.compose.ui.graphics.Color.Yellow,
            resolvedSpecialKeyColor(preview, true, androidx.compose.ui.graphics.Color.Yellow))
        assertEquals(resolvedSpecialKeyColor(base, true, androidx.compose.ui.graphics.Color.Yellow),
            resolvedSpecialKeyColor(preview, true, androidx.compose.ui.graphics.Color.Yellow))
    }

    /** 第一次选图时主题表里还没有「我的图片」：调整页预览按沿用的主题取键色，不能落到全局的半透明白键。 */
    @Test fun previewTakesKeyColoursFromTheBaseBeforeTheImageIsSaved() {
        val id = CustomImageTheme.previewColorThemeId(themes, PureBlackTheme.ID)
        assertEquals(PureBlackTheme.ID, id)
        val base = themes.first { it.id == PureBlackTheme.ID }
        assertEquals(base.keyBgDark, KeyboardThemes.getKeyBgColorOverride(id, true))
        assertEquals(base.keyTextColorDark, KeyboardThemes.getKeyTextColorOverride(id, true))
        assertEquals(SoftBlueTheme.ID, CustomImageTheme.previewColorThemeId(themes, TransparentGlassTheme.ID))
    }
}
