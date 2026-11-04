package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.kingzcheung.xime.keyboard.LetterKeyRect
import com.kingzcheung.xime.keyboard.SpatialCosts
import com.kingzcheung.xime.keyboard.SpatialTaps

/**
 * Shared state of the probabilistic 26-key letter field.
 *
 * The field has no key caps and no key boundaries: every letter is a Gaussian bump of touch
 * probability centred on its conventional position, and the whole area is one continuous
 * surface. A tap types the highest-density letter and hands the decoder the relative
 * log-probability of every neighbouring letter.
 */
internal class ProbabilityFieldState {
    /** Letter centres in root coordinates, registered by the invisible touch cells. */
    internal val centres = mutableStateMapOf<Char, Offset>()
    internal val cells = mutableStateMapOf<Char, LetterKeyRect>()

    /** Median letter width in pixels, used only for drawing. */
    var cellPx by mutableFloatStateOf(0f)
        private set

    /** Current finger position while pressed (root coordinates), for the live feedback. */
    var touch by mutableStateOf<Offset?>(null)
        private set

    /** Normalised P(letter | touch) of the current press. */
    var probabilities by mutableStateOf<Map<Char, Float>>(emptyMap())
        private set

    /** The letter the current press would type. */
    var picked by mutableStateOf<Char?>(null)
        private set

    fun register(letter: Char, bounds: Rect) {
        if (bounds.width <= 0f || bounds.height <= 0f) return
        val cell = LetterKeyRect(bounds.left, bounds.top, bounds.width, bounds.height)
        val centre = bounds.center
        if (cells[letter] == cell && centres[letter] == centre) return
        cells[letter] = cell
        centres[letter] = centre
        val widths = cells.values.map { it.width }.sorted()
        cellPx = widths[widths.size / 2]
    }

    fun press(root: Offset) {
        touch = root
        val snapshot = cells.toMap()
        val best = SpatialCosts.pick(root.x, root.y, snapshot)
        picked = best
        probabilities = if (best == null) emptyMap()
            else SpatialCosts.distribution(root.x, root.y, snapshot)
    }

    fun release() {
        touch = null
        picked = null
        probabilities = emptyMap()
    }
}

internal val LocalProbabilityField = staticCompositionLocalOf<ProbabilityFieldState?> { null }

/**
 * The soup itself: one soft density bump per letter, drawn behind the invisible touch cells.
 * Every letter row draws the same field clipped to its own bounds, so the rows join into one
 * continuous surface without a seam.
 */
internal fun DrawScope.drawProbabilityField(
    field: ProbabilityFieldState,
    origin: Offset,
    textColor: Color,
    accentColor: Color,
    uppercase: Boolean,
    measurer: TextMeasurer,
) {
    val centres = field.centres
    if (centres.isEmpty()) return
    val cell = field.cellPx.takeIf { it > 0f } ?: size.minDimension
    val touch = field.touch
    val probabilities = field.probabilities
    val picked = field.picked
    val top = probabilities.values.maxOrNull() ?: 0f
    val dark = textColor.luminance() < 0.5f
    val radius = cell * 0.98f
    val baseAlpha = if (dark) 0.16f else 0.065f

    // Ambient density: the overlap of the bumps is the "soup". A pressed finger lifts the
    // bumps around it in proportion to their probability.
    for ((letter, centre) in centres) {
        val local = centre - origin
        if (local.x < -radius || local.y < -radius ||
            local.x > size.width + radius || local.y > size.height + radius
        ) continue
        val probability = probabilities[letter] ?: 0f
        val lift = if (touch != null && top > 0f) 0.6f * (probability / top) else 0f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = baseAlpha + lift * baseAlpha * 2.5f),
                    Color.Transparent,
                ),
                center = local,
                radius = radius,
            ),
            radius = radius,
            center = local,
        )
    }

    // The finger's own glow shows where the distribution is being sampled.
    if (touch != null) {
        val local = touch - origin
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accentColor.copy(alpha = 0.34f), Color.Transparent),
                center = local,
                radius = cell * 1.3f,
            ),
            radius = cell * 1.3f,
            center = local,
        )
    }

    // Letter glyphs. They keep their conventional positions but no box: size and opacity follow
    // the probability of the current touch, and the typed letter takes the accent.
    for ((letter, centre) in centres) {
        val local = centre - origin
        if (local.x < -cell || local.y < -cell ||
            local.x > size.width + cell || local.y > size.height + cell
        ) continue
        val probability = probabilities[letter] ?: 0f
        val emphasis = if (touch != null && top > 0f) (probability / top).coerceIn(0f, 1f) else 0f
        val fontPx = cell * 0.38f * (1f + 0.14f * emphasis)
        val style = TextStyle(
            color = if (letter == picked) accentColor.copy(alpha = 0.95f)
            else textColor.copy(alpha = (0.82f + 0.16f * emphasis).coerceAtMost(1f)),
            fontSize = with(density) { fontPx.toSp() },
            fontWeight = if (letter == picked) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = AppFonts.keyFontFamily,
        )
        val label = if (uppercase) letter.uppercaseChar().toString() else letter.toString()
        val layout = measurer.measure(
            AnnotatedString(label), style = style, maxLines = 1, softWrap = false,
        )
        drawText(
            textLayoutResult = layout,
            topLeft = local - Offset(layout.size.width / 2f, layout.size.height / 2f),
        )
    }
}

/** Canvas wrapper for the rows that own their own area (first and second letter rows). */
@Composable
internal fun ProbabilityBackdrop(
    textColor: Color,
    accentColor: Color,
    uppercase: Boolean,
    modifier: Modifier = Modifier,
    onKeyPress: ((String) -> Unit)? = null,
    onKeyPressDown: ((String) -> Unit)? = null,
) {
    val field = LocalProbabilityField.current ?: return
    val measurer = rememberTextMeasurer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val letterAt: (Offset) -> Unit = { local ->
        val root = origin + local
        val keys = field.cells.toMap()
        val picked = SpatialCosts.pick(root.x, root.y, keys)
        if (picked != null) {
            SpatialTaps.offer(picked, root.x, root.y, keys)
            val committed = if (uppercase) picked.uppercaseChar() else picked
            onKeyPressDown?.invoke(committed.toString())
            onKeyPress?.invoke(committed.toString())
        }
    }
    Canvas(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .then(if (onKeyPress != null) Modifier.pointerInput(Unit) {
                detectTapGestures(onTap = letterAt)
            } else Modifier)
    ) {
        drawProbabilityField(field, origin, textColor, accentColor, uppercase, measurer)
    }
}
