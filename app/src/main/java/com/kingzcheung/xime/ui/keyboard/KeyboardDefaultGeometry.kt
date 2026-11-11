package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.EngineProfile
import com.kingzcheung.xime.settings.InputProfile
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.KeyboardRow
import com.kingzcheung.xime.settings.KeyboardRowsLayout
import com.kingzcheung.xime.settings.LayoutKind
import kotlin.math.floor
import kotlin.math.roundToInt

/** Available space, not a phone/tablet or orientation classification, determines the default. */
internal data class LetterKeyboardDefaults(
    val availableHeightDp: Int,
    val spacingX: Float? = null,
    val spacingY: Float? = null,
    val floating: Boolean = false,
    val calibratedMaxCellWidthDp: Float? = null,
    val maximumHeightDp: Int? = null,
)

/** Grouped and reordered alphabetic derivatives share the same default physical frame. */
internal fun letterKeyboardDefaults(
    profile: InputProfile, availableHeightDp: Int, isAsciiMode: Boolean, floating: Boolean = false,
    split: Boolean = false,
    physicalMetrics: KeyboardPhysicalMetrics? = null,
    maximumHeightDp: Int? = null,
): LetterKeyboardDefaults? {
    if ((!isAsciiMode && profile.layout.kind !in setOf(LayoutKind.ALPHABETIC, LayoutKind.MERGED)) || split) return null
    val schemaId = (profile.engineProfile as? EngineProfile.Rime)?.schemaId.orEmpty()
    val custom = KeysConfigHelper.letterLayout(schemaId, isAsciiMode)
    val rows = custom?.typingRows() ?: KeysConfigHelper.getKeyRows(schemaId, isAsciiMode)
    // The schema-aware API preserves the configured Shift/delete and control row.
    // Normalize the same recognized standard layout as the renderer before checking
    // its three-letter-row frame; leave arbitrary/custom layouts untouched.
    val letterRows = legacyControlLayoutLetters(KeyboardRowsLayout(rows.map { KeyboardRow(it) }), rows) ?: rows
    if (standardLetterRowGeometry(letterRows, 1f, custom != null, false) == null) return null
    val (x, y) = KeysConfigHelper.getKeyboardKeyConfig().spacingFor("qwerty")
    val pitch = physicalMetrics?.dpFromMillimeters(CALIBRATED_QWERTY_PITCH_MM)
    return LetterKeyboardDefaults(availableHeightDp, x, y, floating, pitch, maximumHeightDp)
}

/** A 56dp minimum row keeps a narrow keyboard usable. Once width permits, letter caps
 * become square. A measured physical pitch bounds calibrated QWERTY defaults; unknown
 * displays keep the original 88dp cap and 45% default-height budget. A calibrated target
 * may raise that budget continuously to at most 50% (retaining the old 228dp floor),
 * within the existing resize bounds. Manual sizes never enter here. Includes the 44dp toolbar and 8dp bottom.
 * Default sizes stay as chosen: on large panels the scaled candidate bar ([candidateBarScale]) takes its
 * extra height from the rows, about 2dp per row, rather than widening or narrowing the default. */
internal fun defaultLetterKeyboardSize(
    availableWidthDp: Int, preferredWidthDp: Int, defaults: LetterKeyboardDefaults,
): ProtectedKeyboardSize {
    val available = availableWidthDp.coerceAtLeast(1)
    val preferred = preferredWidthDp.coerceIn(1, available)
    val availableHeight = defaults.availableHeightDp.coerceAtLeast(1)
    val calibratedPitch = defaults.calibratedMaxCellWidthDp
        ?.takeIf { it.isFinite() && it >= 1f && (16f + 10f * it).isFinite() }
    val maximumHeight = if (calibratedPitch != null)
        (defaults.maximumHeightDp ?: availableHeight).coerceIn(1, availableHeight).toFloat()
        else availableHeight.toFloat()
    var heightBudget = maxOf(228f, availableHeight * 0.45f).coerceAtMost(maximumHeight)
    // No four-row geometry can fit below the toolbar plus four 1dp rows. Preserve
    // horizontal usability rather than collapsing to a 1dp strip in this degenerate case.
    if (heightBudget < 56f) return ProtectedKeyboardSize(preferred, heightBudget.toInt(), 0)
    val minRow = ((heightBudget - 52f) / 4f).coerceIn(1f, 56f)
    val policy = KeyVisualPolicy.Qwerty.copy(maxKeyWidth = Float.MAX_VALUE)

    fun geometry(width: Float): Pair<Float, Float> {
        val cellWidth = ((width - 16f) / 10f).coerceAtLeast(1f)
        var rowHeight = minRow
        var capWidth = cellWidth
        // Gap Y is capped by the row height. Solve the same metrics used by rendering.
        repeat(6) {
            val metrics = keyboardGridMetrics(policy, width, rowHeight * 4f + 8f, 10f,
                allowShrink = defaults.floating, growthSpacing = defaults.spacingX to defaults.spacingY)
            val gapX = defaults.spacingX?.takeUnless { it == KeyVisualPolicy.DeclaredDefaultGap }
                ?: ((metrics.insetX ?: 0f) * 2f)
            val gapY = defaults.spacingY?.takeUnless { it == KeyVisualPolicy.DeclaredDefaultGap }
                ?: ((metrics.insetY ?: 0f) * 2f)
            capWidth = (cellWidth - gapX).coerceAtLeast(1f)
            rowHeight = maxOf(minRow, capWidth + gapY)
        }
        return capWidth to (52f + rowHeight * 4f)
    }

    if (calibratedPitch != null) {
        // Derive the budget from the available viewport, not a previously resolved
        // preferred width: resolving that returned width again must not shrink it.
        val targetWidth = minOf(available.toFloat(), 16f + 10f * calibratedPitch)
        val progress = wideQwertyStagger((targetWidth - 16f) / 10f, calibratedPitch)
        val expandedHeightLimit = maxOf(heightBudget, availableHeight * 0.50f).coerceAtMost(maximumHeight)
        // Keep this continuous. Rounding before interpolation made 832->833dp of
        // available width jump four dp. The half dp only allows final integer-dp
        // rounding; neither it nor the physical target may exceed the height limit.
        val targetHeight = (geometry(targetWidth).second + 0.5f).coerceAtMost(expandedHeightLimit)
        heightBudget += (targetHeight - heightBudget).coerceAtLeast(0f) * progress
    }

    fun fits(width: Float): Boolean {
        val (capWidth, height) = geometry(width)
        val withinWidth = calibratedPitch?.let { (width - 16f) / 10f <= it } ?: (capWidth <= 88f)
        return withinWidth && height <= heightBudget + 0.001f
    }
    var width = preferred.toFloat()
    if (!fits(width)) {
        var low = 1f
        var high = width
        repeat(24) {
            val middle = (low + high) / 2f
            if (fits(middle)) low = middle else high = middle
        }
        width = low
    }
    val roundedWidth = floor(width).toInt().coerceIn(1, available)
    val height = geometry(roundedWidth.toFloat()).second.roundToInt()
        .coerceIn(1, heightBudget.toInt().coerceAtLeast(1))
    return ProtectedKeyboardSize(roundedWidth, height, 0)
}
