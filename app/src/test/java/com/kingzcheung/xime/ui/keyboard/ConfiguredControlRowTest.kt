package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.GestureDef
import com.kingzcheung.xime.settings.KeyGestureConfig
import com.kingzcheung.xime.settings.KeyboardRow
import com.kingzcheung.xime.settings.KeyboardRowsLayout
import com.kingzcheung.xime.settings.LongPressConfig
import com.kingzcheung.xime.settings.RowAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConfiguredControlRowTest {
    private val rows = listOf(
        "qwertyuiop".map(Char::toString), "asdfghjkl".map(Char::toString),
        listOf("shift") + "zxcvbnm".map(Char::toString) + "delete",
        listOf("earth", "space", "comma", "mode_change", "enter"),
    )
    private val layout = KeyboardRowsLayout(rows.map { KeyboardRow(it) })

    @Test fun acceptsReorderedControlsAndSafeWidths() {
        val bindings = mapOf("space" to KeyGestureConfig(width = 3f),
            "earth" to KeyGestureConfig(tap = GestureDef(action = GestureAction.TOGGLE_ASCII)))
        val result = configuredControlRow(layout, true, bindings::get)!!
        assertEquals(rows.last(), result.controls)
        assertEquals("zxcvbnm".map(Char::toString), result.letterRows.last())
    }

    @Test fun legacyRowsAndSpecialModesDoNotActivate() {
        assertNull(configuredControlRow(layout.copy(rows = layout.rows.take(3)), true) { null })
        assertNull(configuredControlRow(layout, false) { null })
        assertEquals(listOf(rows[0], rows[1], "zxcvbnm".map(Char::toString)),
            legacyControlLayoutLetters(layout, rows))
        assertNull(legacyControlLayoutLetters(layout, rows.take(3)))
    }

    @Test fun unsupportedLayoutAndBindingsFallBack() {
        assertNull(configuredControlRow(layout.copy(columns = 10), true) { null })
        assertNull(configuredControlRow(layout.copy(rowAlign = RowAlign.STRETCH), true) { null })
        assertNull(configuredControlRow(layout.copy(rows = layout.rows.dropLast(1) + KeyboardRow(listOf("space"))), true) { null })
        assertNull(configuredControlRow(layout, true) { if (it == "delete") KeyGestureConfig(swipeRight = GestureDef()) else null })
        assertNull(configuredControlRow(layout, true) { if (it == "earth") KeyGestureConfig(longPress = LongPressConfig()) else null })
        assertNull(configuredControlRow(layout, true) { if (it == "space") KeyGestureConfig(width = Float.MIN_VALUE) else null })
        assertNotNull(configuredControlRow(layout, true) { null })
    }

    @Test fun periodBorrowsOnlyFromTheMergedModeSpan() {
        val enabled = configuredPeriodWeights(2.4f, 0.8f, true)
        assertEquals(1.6f, enabled.mode, 0.000001f)
        assertEquals(0.8f, enabled.period, 0.000001f)
        assertEquals(2.4f, enabled.mode + enabled.period, 0.000001f)
        assertEquals(ConfiguredPeriodWeights(2.4f, 0f), configuredPeriodWeights(2.4f, 0.8f, false))
    }

    @Test fun configuredDefaultsKeepSpaceLanguageAndEnterUnchanged() {
        val bindings = mapOf("mode_change" to KeyGestureConfig(width = 1.2f),
            "comma" to KeyGestureConfig(width = 0.8f), "space" to KeyGestureConfig(width = 3f),
            "earth" to KeyGestureConfig(width = 0.8f), "enter" to KeyGestureConfig(width = 1.2f))
        val before = configuredFooterWeights(QwertyBottomRowWeights.Standard, false, bindings::get)
        val after = configuredFooterWeights(QwertyBottomRowWeights.Standard, true, bindings::get)
        assertEquals(before.total, after.total, 0.000001f)
        assertEquals(before.space, after.space, 0f)
        assertEquals(before.language, after.language, 0f)
        assertEquals(before.enter, after.enter, 0f)
        assertEquals(before.comma, after.comma, 0f)
        assertEquals(after.comma, after.period, 0f)
        // Without width bindings the old enabled-period renderer total was nine, not ten.
        val unbound = configuredFooterWeights(QwertyBottomRowWeights.Standard, true) { null }
        assertEquals(9f, unbound.total, 0.000001f)
        assertEquals(2.9f, unbound.space, 0.000001f)
    }

    @Test fun narrowCustomModeBindingsLeaveBothKeysPositiveWithoutTakingOtherSpace() {
        for ((span, punctuation) in listOf(0.02f to 1000f, 1f to 2f, 3f to 1.3f)) {
            val split = configuredPeriodWeights(span, punctuation, true)
            org.junit.Assert.assertTrue(split.mode > 0f && split.period > 0f)
            org.junit.Assert.assertTrue(split.period <= punctuation)
            assertEquals(span, split.mode + split.period, 0.000001f)
        }
    }
}
