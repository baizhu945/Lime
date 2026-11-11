package com.kingzcheung.xime.ui.keyboard

/** Geometry of the typing layout behind the expanded candidate page. Fractions exclude its gutter. */
data class CandidateQwertyGeometry(
    val columns: Float,
    val split: Boolean,
    val modeFraction: Float,
    val deleteFraction: Float,
    val enterFraction: Float,
    /** Fraction of the gutter-free body left for keys after the actual split gap. */
    val widthFraction: Float = 1f,
) {
    val innerInsetDp: Float get() = 0f
    val deleteEndInsetDp: Float get() = 0f
    fun deleteWidth(widthDp: Float): Float = (widthDp * widthFraction * enterFraction).coerceAtLeast(1f)
}

internal fun candidateQwertyGeometry(
    rows: List<List<String>>,
    fourteenKey: Boolean,
    custom: Boolean,
    split: Boolean,
): CandidateQwertyGeometry {
    val columns = letterKeyboardColumns(rows, fourteenKey)
    val bottom = letterKeyboardBottomRow(rows, fourteenKey)
    val right = bottom.enter / bottom.total
    return CandidateQwertyGeometry(columns, split,
        modeFraction = bottom.mode / bottom.total,
        deleteFraction = right,
        enterFraction = right)
}
