package com.kingzcheung.xime.ui.keyboard

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
