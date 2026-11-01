package com.kingzcheung.xime.settings

import com.kingzcheung.xime.keyboard.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XimeGestureModelTest {
    private val original = requireNotNull(javaClass.getResource("/xime-original-qwerty-gestures.yaml"))
        .readText(Charsets.UTF_8)

    @Test fun originalChineseAndEnglishBindingsArePreserved() {
        for (section in listOf("qwerty", "qwerty_en")) {
            val keys = requireNotNull(KeysConfigHelper.parseKeyboardYamlSection(original, section))
            val layout = KeysConfigHelper.mergeRowsLayout(original, null, section)
            assertEquals(listOf("mode_change", "comma", "space", "earth", "enter"), layout.rows.last().keys)
            assertEquals(GestureAction.COMMIT, keys.getValue("q").tap!!.action) // legacy CyIME route
            assertEquals(XimeAction.SEND_RIME, keys.getValue("q").tap!!.ximeAction)
            assertEquals(GestureAction.COMMAND, keys.getValue("shift").tap!!.action)
            assertEquals("shift_caps", keys.getValue("shift").doubleTap!!.value)
            assertEquals(GestureAction.DELETE, keys.getValue("delete").longPress!!.values.single().action)
            assertEquals("clear_composition", keys.getValue("delete").swipeLeft!!.value)
            assertEquals("clear_composition", keys.getValue("delete").swipeRight!!.value)
            val mode = keys.getValue("mode_change")
            assertFalse(mode.tap!!.bubble)
            assertEquals(listOf("mode_change_number", "mode_change_common_symbol"),
                mode.longPress!!.values.map { it.value })
            val voice = keys.getValue("space").longPress!!
            assertTrue(voice.values.isEmpty()) // unchanged legacy consumers
            assertEquals(XimeAction.VOICE, voice.directAction!!.ximeAction)
            assertNull(voice.directAction!!.action) // no new executable CyIME route
            assertFalse(voice.directAction!!.bubble)
            assertEquals("语音", voice.directAction!!.label)
            assertEquals("show_ime_picker", keys.getValue("earth").longPress!!.values.single().value)
        }
        val zh = requireNotNull(KeysConfigHelper.parseKeyboardYamlSection(original, "qwerty"))
        val en = requireNotNull(KeysConfigHelper.parseKeyboardYamlSection(original, "qwerty_en"))
        assertEquals(GestureAction.NONE, zh.getValue("z").swipeDown!!.action)
        assertFalse(zh.getValue("z").swipeDown!!.bubble)
        assertEquals("，", zh.getValue("comma").tap!!.label)
        assertEquals(".", en.getValue("comma").tap!!.label)
        assertFalse(en.getValue("earth").tap!!.bubble)
        assertTrue(zh.getValue("earth").tap!!.bubble)
    }

    @Test fun explicitActionsAndTapDefaultsRemainDistinct() {
        val yaml = """
            keyboard:
              qwerty:
                keys:
                  scalar: { tap: "x", swipe_up: "x" }
                  implicit: { tap: { value: "x" } }
                  explicit: { tap: { action: send_rime, value: "x" } }
                  commit: { tap: { action: commit, value: "x" } }
                  none: { tap: { action: none } }
                  unknown: { tap: { action: future_action, value: "x" } }
                  null_action: { tap: { action: null } }
        """.trimIndent()
        val keys = requireNotNull(KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty"))
        assertEquals(XimeAction.SEND_RIME, keys.getValue("scalar").tap!!.ximeAction)
        assertNull(keys.getValue("scalar").swipeUp!!.ximeAction)
        assertEquals(XimeAction.SEND_RIME, keys.getValue("implicit").tap!!.ximeAction)
        assertEquals(XimeAction.SEND_RIME, keys.getValue("explicit").tap!!.ximeAction)
        assertNull(keys.getValue("explicit").tap!!.action)
        assertNull(keys.getValue("commit").tap!!.ximeAction)
        assertEquals(GestureAction.NONE, keys.getValue("none").tap!!.action)
        assertNull(keys.getValue("unknown").tap!!.ximeAction)
        assertEquals("future_action", keys.getValue("unknown").tap!!.unsupportedAction)
        assertNull(keys.getValue("null_action").tap!!.ximeAction)
    }

    @Test fun directAndListActionsRetainLegacyViewsAndBubbleFallback() {
        val yaml = """
            keyboard:
              qwerty:
                keys:
                  direct: { long_press: { action: repeat_space, value: 5, bubble: false } }
                  list: { long_press: { values: [ { action: repeat_space, value: 5 } ] } }
                  old: { long_press: [a, b] }
                  bad_bubble: { tap: { value: x, bubble: invalid } }
                  no_action: { long_press: { display: bubble } }
                  both: { long_press: { action: voice, values: [{ action: command, value: test }] } }
        """.trimIndent()
        val keys = requireNotNull(KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty"))
        val direct = keys.getValue("direct").longPress!!
        assertTrue(direct.values.isEmpty())
        assertEquals(XimeAction.REPEAT_SPACE, direct.directAction!!.ximeAction)
        assertEquals("5", direct.directAction!!.value)
        assertFalse(direct.directAction!!.bubble)
        assertNull(keys.getValue("list").longPress!!.directAction)
        assertEquals(XimeAction.REPEAT_SPACE, keys.getValue("list").longPress!!.values.single().ximeAction)
        assertEquals(listOf("a", "b"), keys.getValue("old").longPress!!.values.map { it.value })
        assertTrue(keys.getValue("bad_bubble").tap!!.bubble)
        assertNull(keys.getValue("no_action").longPress!!.directAction)
        assertNull(keys.getValue("both").longPress!!.directAction)
        assertEquals(GestureAction.COMMAND, keys.getValue("both").longPress!!.values.single().action)
    }

    @Test fun customKeysReplaceWholeBindingAndMissingKeysFallBack() {
        val custom = """
            keyboard:
              qwerty:
                keys:
                  space: { long_press: { values: [{ action: repeat_space, value: 5 }] } }
        """.trimIndent()
        val builtIn = requireNotNull(KeysConfigHelper.parseKeyboardYamlSection(original, "qwerty"))
        val override = requireNotNull(KeysConfigHelper.parseKeyboardYamlSection(custom, "qwerty"))
        val merged = KeysConfigHelper.mergeGestureConfigs(builtIn, override)
        assertEquals(XimeAction.REPEAT_SPACE, merged.getValue("space").longPress!!.values.single().ximeAction)
        assertNull(merged.getValue("space").tap) // Xime/CyIME key-level, not slot-level merge
        assertEquals(builtIn.getValue("earth"), merged.getValue("earth"))
        assertEquals(builtIn, KeysConfigHelper.mergeGestureConfigs(builtIn, null))
    }
}
