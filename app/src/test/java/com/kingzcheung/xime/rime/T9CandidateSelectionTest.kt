package com.kingzcheung.xime.rime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class T9CandidateSelectionTest {
    @Test fun annotatedCandidatesKeepNativeSelection() {
        listOf(null, "你好", "steam", "😀").forEach { text ->
            assertTrue(t9CandidateNeedsNativeSelection("ni hao", text))
        }
    }

    @Test fun commentFreeEnglishCapturesItsNativeCode() {
        for (comment in listOf(null, "", "  ")) {
            for (text in listOf("steam", "Dota2", "CS2", "Counter-Strike 2", "Steam Deck", "3D")) {
                assertTrue("$comment / $text", t9CandidateNeedsNativeSelection(comment, text))
            }
        }
    }

    @Test fun unannotatedSymbolsAndChineseSummariesKeepDirectDelivery() {
        for (comment in listOf(null, "", "  ")) {
            for (text in listOf(null, "", "你好", "😀", "OK👍", "中文ABC", "！")) {
                assertFalse("$comment / $text", t9CandidateNeedsNativeSelection(comment, text))
            }
        }
    }

    @Test fun numericAndPunctuationOnlyCandidatesAreNotEnglishGuesses() {
        for (text in listOf("2026", "1-2", "/", "...", " ")) {
            assertFalse(text, t9CandidateNeedsNativeSelection("", text))
        }
    }
}
