package com.kingzcheung.xime.service

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class AssociationSelectionTest {
    @Test fun numberRowMapsExactlyToDisplayedLabelsAndShiftNeverSelects() {
        for (number in 1..9) {
            assertEquals(number - 1, hardwareCandidateDigitIndex(KeyEvent.KEYCODE_0 + number, false))
            assertNull(hardwareCandidateDigitIndex(KeyEvent.KEYCODE_0 + number, true))
        }
        assertEquals(9, hardwareCandidateDigitIndex(KeyEvent.KEYCODE_0, false))
        assertEquals("!", keyCodeToKey(KeyEvent.KEYCODE_1, true))
        assertEquals("@", keyCodeToKey(KeyEvent.KEYCODE_2, true))
    }

    @Test fun rejectedCommitIsNotReportedAsSelectionSuccess() {
        val state = CandidateState(associationCandidates = listOf("世界", "朋友"))
        var attempted = ""
        assertFalse(commitAssociationSelection(state, 1, { attempted = it; false }, { _, _ -> error("not a replacement") }))
        assertEquals("朋友", attempted)
        assertTrue(commitAssociationSelection(state, 1, { it == "朋友" }, { _, _ -> false }))
        assertFalse(commitAssociationSelection(state, 9, { error("out of range") }, { _, _ -> false }))
    }

    @Test fun englishOriginalDoesNotDuplicateAndStaleReplacementNeverAppends() {
        val state = CandidateState(pendingEnglishText = "tes", associationCandidates = listOf("test"))
        assertTrue(commitAssociationSelection(state, 0, { error("already typed") }, { _, _ -> error("original") }))
        assertFalse(commitAssociationSelection(state, 1, { error("must not append") }, { old, new ->
            assertEquals("tes", old); assertEquals("test", new); false
        }))
        assertTrue(commitAssociationSelection(state, 1, { error("must replace") }, { _, _ -> true }))
    }

    @Test fun rawReturnHasNoPresentationSpacingAndKeepsT9PartialText() {
        assertEquals("fuck", rawCompositionCommitText("fuck", "fu c k", false))
        assertEquals("fuck", rawCompositionCommitText("3825", "fu c k", true))
        assertEquals("你好ceshi", rawCompositionCommitText("23744", "你好 ce shi", true))
        assertEquals("caret bar is display-only", "nihao",
            rawCompositionCommitText("nihao", "ni|hao", true))
        assertEquals("xi'an", rawCompositionCommitText("xi'an", "xi an", false))
        assertEquals("", rawCompositionCommitText("", "", false))
    }
}
