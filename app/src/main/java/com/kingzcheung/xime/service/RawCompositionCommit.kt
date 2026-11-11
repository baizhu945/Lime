package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.InputLayout
import com.kingzcheung.xime.settings.LayoutKind

/** Layouts with double-letter keys: 14/17/18 键 and custom layouts that put two letters on a key. Their engine
 * input is the key code (bn → b), so Enter and symbols commit the reading shown on screen, never the codes.
 * 注音 too: its engine input is the 大千 key codes (ㄋㄧˇ is su3), the screen shows the bopomofo. */
internal fun usesMergedLetterKeys(layout: InputLayout): Boolean = layout.kind == LayoutKind.MERGED || layout.kind == LayoutKind.ZHUYIN

/** Raw alphabetic encoding is not the formatted preedit. T9 has numeric engine input and 14-key has
 * merged-key codes (bn → b), so both use the decoded reading shown on screen, keeping partial Chinese
 * selections but removing display separators; never the merged codes ("bugao" for ni'hao). */
internal fun rawCompositionCommitText(engineInput: String, preedit: String, t9: Boolean, merged14: Boolean = false): String =
    when {
        (t9 || merged14) && preedit.isNotEmpty() ->
            preedit.filterNot { it.isWhitespace() || it == '\'' || it == com.kingzcheung.xime.service.PREEDIT_CARET }
        merged14 -> engineInput.lowercase()
        else -> engineInput
    }

/**
 * Text that finishes an unfinished composition before a literal (symbol, digit) or a language switch.
 * Gboard rule (user 2026-10-24): symbols and the language key end it as typed — the letters, never the
 * first candidate, and no space; [asTyped] false keeps the first-candidate commit for voice/handwriting.
 * T9 and 14-key take the same decoded reading as Enter (T9 partial selections are already in [preedit]).
 */
internal fun compositionCommitPrefix(asTyped: Boolean, engineInput: String, preedit: String, t9: Boolean,
    merged14: Boolean, partial: String, englishDelivered: Boolean, japanesePreview: String?,
    firstCandidate: () -> String): String = when {
    // English has already been delivered character by character.
    englishDelivered && engineInput.isEmpty() -> ""
    engineInput.isEmpty() -> partial
    japanesePreview != null -> partial + japanesePreview
    asTyped -> rawCompositionCommitText(engineInput, preedit, t9, merged14)
    else -> partial + firstCandidate()
}
