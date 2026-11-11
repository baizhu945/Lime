package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.EngineProfile
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputLayout
import com.kingzcheung.xime.settings.InputProfile
import com.kingzcheung.xime.settings.InputScheme
import com.kingzcheung.xime.settings.KeyGestureConfig
import com.kingzcheung.xime.settings.KeyboardRow
import com.kingzcheung.xime.settings.KeyboardRowsLayout
import org.junit.Assert.*
import org.junit.Test

class CandidateQwertyGeometryTest {
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { it.map(Char::toString) }

    @Test fun standardRailsUseTheModeDeleteAndEnterWeightsOfTheTypingKeyboard() {
        val geometry = candidateQwertyGeometry(rows, false, false, false)
        assertEquals(10f, geometry.columns, 0f)
        for (width in listOf(224f, 344f, 640f, 1024f)) {
            assertEquals(width * 1.2f / 10f, width * geometry.modeFraction, .001f)
            assertEquals(width * geometry.enterFraction, geometry.deleteWidth(width), .001f)
            assertEquals(width * 1.7f / 10f, width * geometry.enterFraction, .001f)
        }
        val layout = KeyboardRowsLayout(listOf(rows[0], rows[1],
            listOf("shift") + rows[2] + "delete",
            listOf("mode_change", "comma", "space", "earth", "enter")).map { KeyboardRow(it) })
        // Shipped qwerty and qwerty_en controls have the same widths, not a ten-unit footer.
        val shipped = mapOf("mode_change" to 1.2f, "comma" to .8f, "space" to 3f,
            "earth" to .8f, "enter" to 1.2f).mapValues { KeyGestureConfig(width = it.value) }
        val customBindings = shipped + mapOf("mode_change" to KeyGestureConfig(width = .3f),
            "space" to KeyGestureConfig(width = 4.2f), "earth" to KeyGestureConfig(width = .9f),
            "enter" to KeyGestureConfig(width = 1.6f))
        val profiles = listOf(
            InputProfile(InputLanguage.CHINESE, InputScheme.PINYIN, InputLayout.QWERTY,
                EngineProfile.Rime("luna_pinyin")),
            InputProfile(InputLanguage.ENGLISH, InputScheme.DIRECT, InputLayout.QWERTY, EngineProfile.Direct))
        for (profile in profiles) for (bindings in listOf(shipped, customBindings)) {
            val configured = resolveTypingConfiguredControlRow(layout, profile, false, false, true, false,
                bindings::get)!!
            assertEquals(rows, configured.letterRows)
            val bottom = letterKeyboardBottomRow(configured.letterRows, false)
            val modeSpan = bindings.getValue("mode_change").width!! * 2f
            val expectedTotal = modeSpan + listOf("comma", "space", "earth", "enter")
                .sumOf { bindings.getValue(it).width!!.toDouble() }.toFloat()
            assertTrue(expectedTotal != 10f)
            if (bindings == shipped) assertEquals(8.2f, expectedTotal, .0001f)
            for (period in listOf(false, true)) {
                val footer = configuredFooterWeights(bottom, period, bindings::get)
                val periodWidth = if (period) minOf(bindings.getValue("comma").width!!, modeSpan / 2f) else 0f
                assertEquals(expectedTotal, footer.total, .0001f)
                assertEquals(modeSpan - periodWidth, footer.mode, .0001f)
                assertEquals(periodWidth, footer.period, .0001f)
                val modern = candidateQwertyGeometry(configured.letterRows, false, false, false, footer)
                assertEquals(footer.mode / expectedTotal, modern.modeFraction, .0001f)
                assertEquals(bindings.getValue("enter").width!! / expectedTotal, modern.enterFraction, .0001f)
                assertEquals(modern.enterFraction, modern.deleteFraction, 0f)
                assertEquals(1f, modern.widthFraction, 0f)
                for (width in listOf(224f, 344f, 640f, 1024f))
                    assertEquals(width * footer.enter / expectedTotal, modern.deleteWidth(width), .001f)
            }
        }
        // A legacy double-mode row must never borrow modern footer fractions.
        val legacy = layout.copy(rows = layout.rows.dropLast(1) +
            KeyboardRow(listOf("mode_change", "comma", "space", "earth", "mode_change", "enter")))
        val rejected = resolveTypingConfiguredControlRow(legacy, profiles.first(), false, false, true, false,
            shipped::get)
        assertNull(rejected)
        val fallback = candidateQwertyGeometry(rows, false, false, false,
            rejected?.let { configuredFooterWeights(letterKeyboardBottomRow(it.letterRows, false), true, shipped::get) })
        assertEquals(geometry, fallback)
    }

    @Test fun mergedAndCustomRowsKeepTheirRealBottomAndSideWeights() {
        val merged = candidateQwertyGeometry(listOf(listOf("qw", "er", "ty", "ui", "op")), true, false, false)
        assertEquals(10f, merged.columns, 0f)
        assertEquals(QwertyBottomRowWeights.Standard.mode / QwertyBottomRowWeights.Standard.total, merged.modeFraction, 0f)
        assertEquals(merged.enterFraction, merged.deleteFraction, 0f)
        val custom = candidateQwertyGeometry(listOf(List(12) { "a" }), false, true, false)
        assertEquals(12f, custom.columns, 0f)
        assertEquals(QwertyBottomRowWeights.Legacy.mode / QwertyBottomRowWeights.Legacy.total, custom.modeFraction, 0f)
    }

    @Test fun splitRowsFollowTypingWeightsAndResolvedGapWithoutExtraInset() {
        val template = candidateQwertyGeometry(rows, false, false, true)
        val bottom = letterKeyboardBottomRow(rows, false)
        assertEquals(1f, template.widthFraction, 0f) // Normalized until the viewport resolves its gap.
        assertEquals(0f, template.innerInsetDp, 0f)
        for (width in listOf(224f, 344f, 800f)) for (gap in listOf(.1f, .2f, .3f)) {
            val gutter = SplitKeyboardGeometry.gutterDp(false)
            val frameWidth = width + 2f * gutter
            val split = template.copy(widthFraction = SplitKeyboardGeometry.keyWidthFraction(frameWidth, gap, gutter))
            val keyWidth = width - frameWidth * gap
            assertEquals(keyWidth * bottom.mode / bottom.total,
                width * split.widthFraction * split.modeFraction, .001f)
            assertEquals(width * split.widthFraction * split.enterFraction, split.deleteWidth(width), .001f)
            assertEquals(0f, split.deleteEndInsetDp, 0f)
            assertEquals(keyWidth * bottom.enter / bottom.total, split.deleteWidth(width), .001f)
            assertTrue(split.deleteWidth(width) + split.deleteEndInsetDp < width / 2f)
        }
    }
}
