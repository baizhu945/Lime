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
}
