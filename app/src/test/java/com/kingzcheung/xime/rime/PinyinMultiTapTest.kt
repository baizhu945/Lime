package com.kingzcheung.xime.rime

import org.junit.Assert.*
import org.junit.Test

class PinyinMultiTapTest {
    @Test fun fourteenKeyReadingUsesMatchedDictionarySpellingOnly() {
        assertEquals("wen", merged14Preedit("qeb", "qeb", "wen"))
        assertEquals("ni'hao", merged14Preedit("bugao", "bu gao", "ni hao"))
        assertEquals("qeb", merged14Preedit("qeb", "qeb", "quan"))
        val display = PinyinEditDisplay("bugao", "ni'hao", isMerged14 = true)
        assertEquals("ni'hao", display.text)
        assertEquals(2, display.rawOffset(3))
    }
    @Test fun onlyAmbiguousGroupTokensTriggerMergedMultiTap() {
        assertEquals("bn", merged14MultiTapGroup(MergedLetterInput.tapValue("bn", "b", true, false)))
        assertEquals("qw", merged14MultiTapGroup("qw"))
        assertNull(merged14MultiTapGroup("b")) // exact swipe/letter, not an ambiguous key
        assertNull(merged14MultiTapGroup("B"))
        assertNull(merged14MultiTapGroup("delete"))
        assertNull(merged14MultiTapGroup(null))
    }
    @Test fun repeatedGroupCyclesAndNewGroupAppends() {
        val tap = PinyinMultiTap()
        assertEquals("q" to 1, tap.press("", 0, "qw", 0))
        assertEquals("w" to 1, tap.press("q", 1, "qw", 100))
        assertEquals("we" to 2, tap.press("w", 1, "er", 200))
        assertEquals("web" to 3, tap.press("we", 2, "bn", 300))
        assertEquals("wen" to 3, tap.press("web", 3, "bn", 400))
    }
    @Test fun timeoutAndExplicitCaretChangeStartNewCharacter() {
        val tap = PinyinMultiTap()
        assertEquals("m" to 1, tap.press("", 0, "mno", 0))
        assertEquals("n" to 1, tap.press("m", 1, "mno", 100))
        assertEquals("o" to 1, tap.press("n", 1, "mno", 200))
        assertEquals("om" to 2, tap.press("o", 1, "mno", 1000))
        tap.reset()
        assertEquals("omm" to 3, tap.press("om", 2, "mno", 1100))
        assertEquals("momm" to 1, tap.press("omm", 0, "mno", 1200))
    }
}
