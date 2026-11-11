package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import com.kingzcheung.xime.keyboard.LetterKeyRect
import com.kingzcheung.xime.keyboard.SpatialCosts

/**
 * Shared state of the probabilistic 26-key letter field.
 *
 * Each letter registers its full cell in one keyboard-local coordinate space. The original
 * Gaussian touch model is unchanged; visible caps are clipped to its dominance regions.
 * Using local coordinates keeps both sampling and cap clipping correct under ancestor rotation.
 */
internal data class ProbabilityGeometrySnapshot(
    val cells: Map<Char, LetterKeyRect>,
    val coordinates: LayoutCoordinates,
)

/** Optional read-only geometry telemetry; it never supplies or changes touch probabilities. */
internal val LocalProbabilityGeometryObserver =
    staticCompositionLocalOf<((ProbabilityGeometrySnapshot) -> Unit)?> { null }

internal class ProbabilityFieldState(
    private val geometryObserver: ((ProbabilityGeometrySnapshot) -> Unit)? = null,
) {
    private data class Registration(val token: Any, val coordinates: LayoutCoordinates)
    private val registrations = mutableMapOf<Char, Registration>()
    private var frame: LayoutCoordinates? = null
    private var publishedCells: Map<Char, LetterKeyRect> = emptyMap()
    private var publishedFrame: LayoutCoordinates? = null

    /** Letter geometry in keyboard-local pixels, shared by painting and real tap sampling. */
    internal val centres = mutableStateMapOf<Char, Offset>()
    internal val cells = mutableStateMapOf<Char, LetterKeyRect>()

    /** Median letter width in pixels. */
    var cellPx by mutableFloatStateOf(0f)
        private set

    /** Current finger position while pressed (keyboard-local coordinates). */
    var touch by mutableStateOf<Offset?>(null)
        private set

    /** Normalised P(letter | touch) of the current press. */
    var probabilities by mutableStateOf<Map<Char, Float>>(emptyMap())
        private set

    /** The letter the current press would type. */
    var picked by mutableStateOf<Char?>(null)
        private set

    fun registerFrame(coordinates: LayoutCoordinates) {
        frame = coordinates
        refreshGeometry()
    }

    fun register(letter: Char, coordinates: LayoutCoordinates, token: Any) {
        registrations[letter] = Registration(token, coordinates)
        refreshGeometry()
    }

    fun unregister(letter: Char, token: Any) {
        if (registrations[letter]?.token !== token) return
        registrations.remove(letter)
        refreshGeometry()
    }

    private fun refreshGeometry() {
        val owner = frame?.takeIf { it.isAttached }
        val measured = if (owner == null) emptyMap() else registrations.mapNotNull { (letter, placed) ->
            val coordinates = placed.coordinates.takeIf { it.isAttached } ?: return@mapNotNull null
            val bounds = runCatching { owner.localBoundingBoxOf(coordinates, clipBounds = false) }.getOrNull()
                ?: return@mapNotNull null
            if (bounds.width <= 0f || bounds.height <= 0f || !bounds.width.isFinite() || !bounds.height.isFinite() ||
                !bounds.left.isFinite() || !bounds.top.isFinite()) null
            else letter to LetterKeyRect(bounds.left, bounds.top, bounds.width, bounds.height)
        }.toMap()
        cells.keys.filterNot { it in measured }.forEach { cells.remove(it); centres.remove(it) }
        measured.forEach { (letter, cell) ->
            if (cells[letter] != cell) cells[letter] = cell
            val center = Offset(cell.x + cell.width / 2f, cell.y + cell.height / 2f)
            if (centres[letter] != center) centres[letter] = center
        }
        val widths = measured.values.map { it.width }.sorted()
        cellPx = widths.getOrNull(widths.size / 2) ?: 0f
        if (geometryObserver != null && owner != null) {
            val current = snapshot()
            if (current.isNotEmpty() && (current != publishedCells || owner !== publishedFrame)) {
                publishedCells = current
                publishedFrame = owner
                geometryObserver.invoke(ProbabilityGeometrySnapshot(current, owner))
            }
        }
    }

    /** Never paint/measure a partially attached 26-letter layout or stale disposed cells. */
    fun snapshot(): Map<Char, LetterKeyRect> =
        if (frame?.isAttached == true && cells.size == 26 && ('a'..'z').all { it in cells })
            "qwertyuiopasdfghjklzxcvbnm".associateWith { cells.getValue(it) }
        else emptyMap()

    data class Sample(val point: Offset, val keys: Map<Char, LetterKeyRect>, val letter: Char)

    fun sample(coordinates: LayoutCoordinates, local: Offset): Sample? {
        val owner = frame?.takeIf { it.isAttached } ?: return null
        if (!coordinates.isAttached) return null
        refreshGeometry()
        val geometry = snapshot().takeIf { it.isNotEmpty() } ?: return null
        val point = owner.localPositionOf(coordinates, local)
        val letter = SpatialCosts.pick(point.x, point.y, geometry) ?: return null
        return Sample(point, geometry, letter)
    }

    fun press(coordinates: LayoutCoordinates, local: Offset) {
        val sample = sample(coordinates, local)
        touch = sample?.point
        picked = sample?.letter
        probabilities = if (sample == null) emptyMap()
            else SpatialCosts.distribution(sample.point.x, sample.point.y, sample.keys)
    }

    fun release() {
        touch = null
        picked = null
        probabilities = emptyMap()
    }
}

internal val LocalProbabilityField = staticCompositionLocalOf<ProbabilityFieldState?> { null }
