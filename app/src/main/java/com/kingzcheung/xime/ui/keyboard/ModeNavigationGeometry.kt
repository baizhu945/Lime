package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** Owned by one KeyboardView, never shared between keyboards or populated by panel keys. */
internal class TextModeNavigationGeometry {
    var slots by mutableStateOf<Map<Int, Rect>>(emptyMap())
        private set
    private var hostSize: IntSize? = null

    fun record(slot: Int, bounds: Rect) {
        if (slot !in 1..2 || !bounds.left.isFinite() || !bounds.top.isFinite() ||
            !bounds.right.isFinite() || !bounds.bottom.isFinite() ||
            bounds.width <= 0f || bounds.height <= 0f) return
        if (slots[slot] != bounds) slots = slots + (slot to bounds)
    }

    fun resize(size: IntSize) {
        if (hostSize != null && hostSize != size) slots = emptyMap()
        hostSize = size
    }
}

internal val LocalTextModeNavigationGeometry = staticCompositionLocalOf<TextModeNavigationGeometry?> { null }

/** The four children remain mode 1, mode 2, categories, delete (including their callbacks). */
@Composable
internal fun SymbolNavigationRow(
    modifier: Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val slots = LocalTextModeNavigationGeometry.current?.slots.orEmpty()
    val first = slots[1]
    val second = slots[2]
    if (first == null || second == null) {
        Row(modifier, horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically, content = content)
        return
    }
    // Keep RowScope for the existing default weights; this layout measures the same four
    // children explicitly only when the originating legacy keyboard supplied both slots.
    var origin by remember { mutableStateOf<Offset?>(null) }
    Row(modifier) {
    val rowScope = this
    Layout(content = { rowScope.content() },
        modifier = Modifier.fillMaxSize().onGloballyPositioned {
            val next = it.boundsInRoot().topLeft
            if (origin != next) origin = next
        }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val start = origin
        val bottom = QwertyBottomRowWeights.Standard
        val defaultMode = (width * bottom.mode / bottom.total).roundToInt()
        val deleteWidth = (width * bottom.enter / bottom.total).roundToInt()
        // Reject stale/out-of-host guides, but allow a different text gutter from the panel.
        val guided = start != null && first.left >= start.x - width &&
            second.right <= start.x + width && first.right <= second.left + 1f &&
            first.width <= width && second.width <= width &&
            first.height <= height * 2 && second.height <= height * 2 &&
            kotlin.math.abs(first.top - second.top) <= 1f
        val rectangles = if (guided) {
            val x1 = (first.left - start!!.x).roundToInt()
            val x2 = (second.left - start.x).roundToInt()
            val y = (first.top - start.y).roundToInt().coerceIn(-height, height)
            val w1 = first.width.roundToInt().coerceIn(1, width)
            val w2 = second.width.roundToInt().coerceIn(1, width)
            val h = first.height.roundToInt().coerceIn(1, height * 2)
            val railStart = (x2 + w2).coerceIn(0, width - deleteWidth)
            listOf(intArrayOf(x1, y, w1, h), intArrayOf(x2, y, w2,
                second.height.roundToInt().coerceIn(1, height * 2)),
                intArrayOf(railStart, y, width - deleteWidth - railStart, h),
                intArrayOf(width - deleteWidth, y, deleteWidth, h))
        } else listOf(intArrayOf(0, 0, defaultMode, height),
            intArrayOf(defaultMode, 0, defaultMode, height),
            intArrayOf(2 * defaultMode, 0, (width - 2 * defaultMode - deleteWidth).coerceAtLeast(0), height),
            intArrayOf(width - deleteWidth, 0, deleteWidth, height))
        val placeables = measurables.mapIndexed { index, measurable ->
            val rect = rectangles[index]
            measurable.measure(Constraints.fixed(rect[2], rect[3]))
        }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place(rectangles[index][0], rectangles[index][1])
            }
        }
    }
    }
}
