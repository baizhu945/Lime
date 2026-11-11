package com.kingzcheung.xime.rime

import org.junit.Assert.*
import org.junit.Test

class PinyinEditBufferTest {
    @Test fun t9AutomaticSeparatorDeletionDoesNotDeleteDigit() {
        assertEquals("nihao" to 2, t9DeleteAutomaticSeparator("ni'hao", 3, "64426"))
        assertNull(t9DeleteAutomaticSeparator("ni'hao", 2, "64426"))
        assertNull(t9DeleteAutomaticSeparator("ni'hao", 3, "64'426"))
        assertNull(t9DeleteAutomaticSeparator("ni'hao", 0, "64426"))
    }
    @Test fun limitsEditingToPhoneticChineseModes() {
        listOf("rime_ice", "t9_pinyin", "pinyin_14jian", "double_pinyin_flypy").forEach { assertTrue(PinyinEditBuffer.supports(it)) }
        listOf("japanese", "japanese_kana", "wubi86", "english").forEach { assertFalse(PinyinEditBuffer.supports(it)) }
    }
    @Test fun keepsRealBoundariesAndUmlautAsV() {
        assertEquals("nv'er", PinyinEditBuffer.normalized("Nü er"))
        assertEquals("ni'hao", PinyinEditBuffer.normalized("ni hao"))
    }
    @Test fun engineBoundariesAreVisibleButDoNotChangeTheEditableCode() {
        val display = PinyinEditDisplay("xiugaishuru", "xiu'gai'shu'ru")
        assertEquals("xiu'gai'shu'ru", display.text)
        assertEquals(3, display.rawOffset(3))
        assertEquals(3, display.rawOffset(4))
        assertEquals(4, display.displayOffset(3))
        assertEquals(11, display.rawOffset(display.text.length))
        val explicit = PinyinEditDisplay("ni''hao", "ni'hao")
        assertEquals("ni''hao", explicit.text)
        assertEquals(4, explicit.rawOffset(4))
    }
    @Test fun incompatibleOrOldPreeditNeverRewritesRawKeys() {
        assertEquals("nih", PinyinEditDisplay("nih", "ni'hao").text)
        assertEquals("nihk", PinyinEditDisplay("nihk", "ni'hao").text)
        assertEquals("ni'426", PinyinEditBuffer.normalizedT9("ni 426"))
    }
    @Test fun nineKeyDeleteKeepsDigitCaretAndUsesNewAmbiguousReading() {
        val before = "nihao"
        val caret = 2
        assertEquals(2, codeKeysBefore(before, caret))
        val remainingDigits = "64426".removeRange(caret - 1, caret)
        assertEquals("6426", remainingDigits)
        val display = PinyinEditDisplay(remainingDigits, "niao", isT9 = true)
        assertEquals("niao", display.text)
        assertEquals(1, display.displayOffset(caret - 1))
    }
    @Test fun nineKeyDigitsShowEngineReadingWithReversibleCaretOffsets() {
        val display = PinyinEditDisplay("ni'426", "ni'hao", true)
        assertEquals("ni'hao", display.text)
        for (i in 0..6) assertEquals(i, display.rawOffset(display.displayOffset(i)))
        assertEquals("ni'hen", PinyinEditDisplay("ni436", "ni'hen", true).text)
        // A stale reading cannot replace literal letters the user explicitly entered.
        assertEquals("ni'426", PinyinEditDisplay("ni'426", "mi'hao", true).text)
    }

    @Test fun editorKeepsItsEditableSeparatorsWhenEngineCaretChangesSegmentation() {
        assertEquals("ni'hao", PinyinEditDisplay("ni'hao", "ni h ao", includeAutomaticBoundaries = false).text)
        assertEquals("nihao", PinyinEditDisplay("nihao", "ni hao", includeAutomaticBoundaries = false).text)
        assertEquals("你ni'hao", pinyinPreviewText("你ni hao"))
        assertEquals("xiu'gai'shu'ru", pinyinPreviewText("xiu gai shu ru"))
    }

    @Test fun nineKeyCaretInsideTheReadingMapsToTheKeysBeforeIt() {
        // xu'yao|jia'ge: candidates must cover the five keys of xu yao, not the whole code.
        val text = "xu'yao'jia'ge"
        assertEquals(5, codeKeysBefore(text, 6))
        assertEquals(5, codeKeysBefore(text, 7))
        assertEquals(5, offsetAfterCodeKeys("9892654243", 5))
        // Manual delimiters in the code are skipped both ways; the caret stays before them.
        assertEquals(5, offsetAfterCodeKeys("98926'54243", 5))
        assertEquals(6, offsetAfterCodeKeys(text, codeKeysBefore("98926'54243", 5)))
        assertEquals(0, offsetAfterCodeKeys("9892654243", codeKeysBefore(text, 0)))
        assertEquals(10, offsetAfterCodeKeys("9892654243", codeKeysBefore(text, text.length)))
        assertEquals(text.length, offsetAfterCodeKeys(text, 99))
    }

    @Test fun keysLeftAfterAPartialChoiceKeepTheReadingTheUserSaw() {
        // xu'ya|o'jia'ge: 需 takes xu, the rest still reads yao jia ge; 呀 then leaves o jia ge.
        assertEquals("yao jia ge", dropReadingKeys("xu yao jia ge", 2))
        assertEquals("ao jia ge", dropReadingKeys("xu yao jia ge", 3))
        assertEquals("o jia ge", dropReadingKeys("yao jia ge", 2))
        assertEquals("", dropReadingKeys("xu yao", 5))
    }
}
