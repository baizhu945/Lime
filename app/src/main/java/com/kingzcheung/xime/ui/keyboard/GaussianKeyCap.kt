package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import com.kingzcheung.xime.keyboard.GaussianKeyBoundary
import com.kingzcheung.xime.keyboard.LetterKeyRect

/**
 * Clip the final rendered cap (including press motion) outside keyGlow's animated layer.
 * The original Gaussian likelihood stays untouched. An ordinary rounded cap is intersected
 * with a conservative subset of its measured Gaussian winning region, leaving the decision
 * boundary in a real gap. Draw-cache reads use the same live local geometry as tap sampling.
 */
internal fun Modifier.gaussianProbabilityCap(
    field: ProbabilityFieldState?,
    letter: Char?,
    visualPadding: PaddingValues,
    originalShape: Shape,
): Modifier {
    if (field == null || letter == null) return this
    return drawWithCache {
        val geometry = field.snapshot()
        val cell = geometry[letter]
        val mask = Path()
        if (cell != null && size.width > 0f && size.height > 0f) {
            // Match Modifier.padding's integer pixel placement, not ideal dp arithmetic.
            val left = visualPadding.calculateLeftPadding(layoutDirection).roundToPx().toFloat()
            val right = visualPadding.calculateRightPadding(layoutDirection).roundToPx().toFloat()
            val top = visualPadding.calculateTopPadding().roundToPx().toFloat()
            val bottom = visualPadding.calculateBottomPadding().roundToPx().toFloat()
            val paintedX = cell.x + left
            val paintedY = cell.y + top
            // AA can extend a fraction of a pixel beyond a nominal box. Protect its own
            // measured cell edges too, not only the pairwise Gaussian equality curves.
            val safeLeft = (1f - left).coerceAtLeast(0f)
            val safeRight = (1f - right).coerceAtLeast(0f)
            val safeTop = (1f - top).coerceAtLeast(0f)
            val safeBottom = (1f - bottom).coerceAtLeast(0f)
            val domain = LetterKeyRect(paintedX + safeLeft, paintedY + safeTop,
                size.width - safeLeft - safeRight, size.height - safeTop - safeBottom)
            // Even explicit zero gaps need an AA-safe sliver outside the equality curve.
            val clearance = maxOf(1f, minOf(left, right, top, bottom))
            val polygon = GaussianKeyBoundary.polygon(letter, geometry, domain, clearance)
            if (polygon.size >= 3) {
                val region = Path().apply {
                    moveTo(polygon[0].x - paintedX, polygon[0].y - paintedY)
                    polygon.drop(1).forEach { lineTo(it.x - paintedX, it.y - paintedY) }
                    close()
                }
                val rounded = originalShape.createOutline(Size(domain.width, domain.height), layoutDirection, this)
                    .asPath().apply { translate(Offset(safeLeft, safeTop)) }
                mask.addPath(Path.combine(PathOperation.Intersect, rounded, region))
            }
        }
        onDrawWithContent {
            // A partially attached/replaced layout must not expose an unverified key cap.
            if (!mask.isEmpty) clipPath(mask) { this@onDrawWithContent.drawContent() }
        }
    }
}

private fun Outline.asPath(): Path = when (this) {
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Generic -> path
}
