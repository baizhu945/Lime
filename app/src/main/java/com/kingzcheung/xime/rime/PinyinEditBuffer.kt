package com.kingzcheung.xime.rime

/** Owns an uncommitted composition; editing never rewrites host text. */
data class PinyinEditSession(
    val inputSessionId: Long,
    val schemaId: String,
    val expectedInput: String,
    val protectedInput: String,
    val protectedText: String,
    val text: String,
    val caret: Int,
    val isT9: Boolean,
    val t9RemainingDigits: String = "",
    val t9SelectionCount: Long = 0,
) {
    private val active = java.util.concurrent.atomic.AtomicBoolean(true)
    internal fun invalidate() { active.set(false) }
    internal fun isActive(): Boolean = active.get()
}

internal object PinyinEditBuffer {
    fun supports(schema: String): Boolean =
        com.kingzcheung.xime.settings.InputProfiles.describe(schema).capabilities.editablePinyin

    fun normalizedT9(text: String): String = text.lowercase().replace('ü', 'v')
        .replace(' ', '\'').filter { it in 'a'..'z' || it in '2'..'9' || it == '\'' }

    fun normalized(text: String): String = text.lowercase().replace('ü', 'v')
        .replace(' ', '\'').filter { it in 'a'..'z' || it == '\'' }

}

/** Maps engine syllables to code. Editor sessions retain these boundaries as editable text. */
internal class PinyinEditDisplay(raw: String, preedit: String, isT9: Boolean = false,
    includeAutomaticBoundaries: Boolean = true, isMerged14: Boolean = false, groups: List<String> = if (isMerged14) merged14Groups else emptyList()) {
    val text: String
    private val offsets: List<Int>
    init {
        val visible = PinyinEditBuffer.normalized(preedit)
        val boundaries = mutableSetOf<Int>()
        val reading = visible.replace("'", "")
        val input = raw.replace("'", "")
        fun digit(c: Char): Char = when (c) {
            in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'
            in 'j'..'l' -> '5'; in 'm'..'o' -> '6'; in 'p'..'s' -> '7'
            in 't'..'v' -> '8'; in 'w'..'z' -> '9'; else -> c
        }
        // Numeric keys remain ambiguous in the engine, but display its actual reading.
        // Literal letters and caret offsets retain their original identity.
        val matches = input.length == reading.length && input.indices.all {
            input[it] == reading[it] || isT9 && input[it] in '2'..'9' && input[it] == digit(reading[it]) ||
                groups.isNotEmpty() && groupRepresentative(input[it], groups) == groupRepresentative(reading[it], groups)
        }
        if (matches) {
            var letters = 0
            visible.forEach { if (it == '\'') boundaries.add(letters) else letters++ }
        }
        val mapping = mutableListOf(0)
        text = buildString {
            var letters = 0
            raw.forEachIndexed { index, character ->
                if (includeAutomaticBoundaries && character != '\'' && letters in boundaries && isNotEmpty() && last() != '\'') {
                    append('\''); mapping.add(index)
                }
                append(if (matches && (character in '2'..'9' || groups.isNotEmpty() && character != '\'')) reading[letters] else character); mapping.add(index + 1)
                if (character != '\'') letters++
            }
        }
        offsets = mapping
    }
    fun rawOffset(display: Int): Int = offsets[display.coerceIn(0, offsets.lastIndex)]
    fun displayOffset(raw: Int): Int = offsets.indexOfLast { it <= raw }.coerceAtLeast(0)
}

/** Remove an automatic display boundary without deleting or locking a nine-key digit. */
internal fun t9DeleteAutomaticSeparator(text: String, caret: Int, raw: String): Pair<String, Int>? {
    if ('\'' in raw || caret !in 1..text.length || text[caret - 1] != '\'') return null
    return text.removeRange(caret - 1, caret) to caret - 1
}

/** Nine-key code and its displayed reading match key for key; syllable delimiters are not keys. */
internal fun codeKeysBefore(text: String, caret: Int): Int = text.take(caret.coerceAtLeast(0)).count { it != '\'' }

/** Offset just after the first [keys] keys of [text]; 0 for none, the end when it has fewer. */
internal fun offsetAfterCodeKeys(text: String, keys: Int): Int {
    if (keys <= 0) return 0
    var seen = 0
    text.forEachIndexed { index, c -> if (c != '\'' && ++seen == keys) return index + 1 }
    return text.length
}

/** The reading after its first [keys] keys, keeping its own syllable boundaries. */
internal fun dropReadingKeys(reading: String, keys: Int): String {
    val start = offsetAfterCodeKeys(reading.replace(' ', '\''), keys)
    return reading.drop(start).trimStart(' ', '\'')
}

/** Preserve confirmed Chinese text and real codes; make Latin syllable spaces visible. */
internal fun pinyinPreviewText(preedit: String): String = preedit.trim()
    .replace(Regex("(?<=[a-zA-ZüÜ'])\\s+(?=[a-zA-ZüÜ'])"), "'")

internal val merged14Groups = listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")
/** Group tokens are ambiguous taps; a single letter is an exact key or flick. */
internal fun merged14MultiTapGroup(key: String?): String? = key?.takeIf { it.length > 1 && it in merged14Groups }
internal fun merged14Representative(c: Char): Char = merged14Groups.firstOrNull { c in it }?.first() ?: c

/** Only substitute a dictionary reading when every entered key is accounted for. */
internal fun groupRepresentative(c: Char, groups: List<String>): Char = groups.firstOrNull { c.lowercaseChar() in it }?.first() ?: c
internal fun merged14Preedit(raw: String, preedit: String, spelling: String, groups: List<String> = merged14Groups): String {
    val reading = spelling.trim()
    if (reading.isEmpty() || reading.any { it !in 'a'..'z' && it != '\'' && !it.isWhitespace() && it != 'ü' }) return preedit
    val input = PinyinEditBuffer.normalized(raw).replace("'", "")
    val letters = PinyinEditBuffer.normalized(reading).replace("'", "")
    if (input.isEmpty() || input.length != letters.length || input.indices.any {
            groupRepresentative(input[it], groups) != groupRepresentative(letters[it], groups) }) return preedit
    return PinyinEditDisplay(raw, reading, groups = groups).text
}

/** Multi-tap exists only in the explicit pinyin editor, never in ordinary composition. */
internal class PinyinMultiTap {
    private var group = ""
    private var previousText = ""
    private var previousCaret = -1
    private var previousTime = Long.MIN_VALUE
    private var index = 0
    fun reset() { group = ""; previousCaret = -1 }
    fun press(text: String, caret: Int, letters: String, timeMs: Long): Pair<String, Int> {
        val cycling = group == letters && text == previousText && caret == previousCaret &&
            caret > 0 && timeMs - previousTime in 0..650
        index = if (cycling) (index + 1) % letters.length else 0
        val position = if (cycling) caret - 1 else caret
        val next = text.take(position) + letters[index] + text.drop(caret)
        group = letters; previousText = next; previousCaret = position + 1; previousTime = timeMs
        return next to previousCaret
    }
}
