package com.kingzcheung.xime.settings

import com.kingzcheung.xime.keyboard.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QwertyRowsConfigTest {
    private val builtIn = """
        keyboard:
          qwerty:
            layout:
              columns: 10
              row_align: uniform
              rows:
                - [q, w, e]
                - [a, s]
                - [z, x]
            keys:
              delete: { width: 1.4, tap: { action: delete } }
          qwerty_en:
            layout:
              rows:
                - [a, b]
    """.trimIndent()

    @Test fun legacyThreeRowsStayUnchanged() {
        val rows = listOf(listOf("q", "w", "e"), listOf("a", "s"), listOf("z", "x"))
        assertEquals(rows, KeysConfigHelper.parseKeyboardLayoutYamlText(builtIn, "qwerty"))
        assertEquals(rows, KeysConfigHelper.mergeRowsLayout(builtIn, null, "qwerty").rows.map { it.keys })
        assertEquals(listOf(listOf("a", "b")), KeysConfigHelper.parseKeyboardLayoutYamlText(builtIn, "qwerty_en"))
    }

    @Test fun customRowsReplaceWholeListAndMetadataFallsBackIndependently() {
        val custom = """
            keyboard:
              qwerty:
                layout:
                  row_align: stretch
                  rows:
                    - { keys: [shift, [q, w], delete], align: uniform, width: 0.7 }
                    - [mode_change, comma, space, earth, enter]
        """.trimIndent()
        val result = KeysConfigHelper.mergeRowsLayout(builtIn, custom, "qwerty")
        assertEquals(10, result.columns)
        assertEquals(RowAlign.STRETCH, result.rowAlign)
        assertEquals(KeyboardRow(listOf("shift", "qw", "delete"), RowAlign.UNIFORM, 0.7f), result.rows[0])
        assertEquals(listOf("mode_change", "comma", "space", "earth", "enter"), result.rows[1].keys)
        assertEquals(listOf(listOf("mode_change", "comma", "space", "earth", "enter")),
            KeysConfigHelper.parseKeyboardLayoutYamlText(custom, "qwerty"))
        assertEquals(listOf(KeyboardRow(listOf("a", "b"))),
            KeysConfigHelper.mergeRowsLayout(builtIn, custom, "qwerty_en").rows)
    }

    @Test fun invalidMetadataFallsBackWithoutDiscardingValidRows() {
        val custom = """
            keyboard:
              qwerty:
                layout:
                  columns: -1
                  row_align: nonsense
                  rows:
                    - { keys: [space, enter], align: bad, width: .nan }
        """.trimIndent()
        val result = KeysConfigHelper.mergeRowsLayout(builtIn, custom, "qwerty")
        assertEquals(10, result.columns)
        assertEquals(RowAlign.UNIFORM, result.rowAlign)
        assertEquals(listOf(KeyboardRow(listOf("space", "enter"))), result.rows)
    }

    @Test fun metadataOnlyOverrideKeepsBuiltInRows() {
        val custom = """
            keyboard:
              qwerty:
                layout: { columns: 12, row_align: stretch }
        """.trimIndent()
        val result = KeysConfigHelper.mergeRowsLayout(builtIn, custom, "qwerty")
        assertEquals(12, result.columns)
        assertEquals(RowAlign.STRETCH, result.rowAlign)
        assertEquals(KeysConfigHelper.parseKeyboardLayoutYamlText(builtIn, "qwerty"), result.rows.map { it.keys })
    }

    @Test fun controlBindingsPreserveNewFieldsAndKeyLevelOverride() {
        val custom = """
            keyboard:
              qwerty:
                keys:
                  shift:
                    width: 1.4
                    tap: { action: command, value: shift_single }
                    double_tap: { action: command, value: shift_caps }
                  delete:
                    width: 2
                    swipe_left: { action: command, value: clear_composition }
                    swipe_right: { action: delete }
        """.trimIndent()
        val defaults = KeysConfigHelper.parseKeyboardYamlSection(builtIn, "qwerty")!!
        val overrides = KeysConfigHelper.parseKeyboardYamlSection(custom, "qwerty")!!
        val keys = KeysConfigHelper.mergeGestureConfigs(defaults, overrides)
        assertEquals(1.4f, keys["shift"]!!.width!!, 0f)
        assertEquals("shift_caps", keys["shift"]!!.doubleTap!!.value)
        assertEquals(GestureAction.COMMAND, keys["delete"]!!.swipeLeft!!.action)
        assertEquals(GestureAction.DELETE, keys["delete"]!!.swipeRight!!.action)
        assertNull(keys["delete"]!!.tap)
        assertEquals(2f, keys["delete"]!!.width!!, 0f)
    }

    @Test fun invalidWidthsDoNotBecomeWeights() {
        val yaml = """
            keyboard:
              qwerty:
                keys:
                  shift: { width: -2, double_tap: [invalid] }
                  delete: { width: .inf, swipe_left: [invalid] }
        """.trimIndent()
        val keys = KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty")!!
        assertNull(keys["shift"]!!.width)
        assertNull(keys["shift"]!!.doubleTap)
        assertNull(keys["delete"]!!.width)
        assertNull(keys["delete"]!!.swipeLeft)
    }
}
