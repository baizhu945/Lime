package com.kingzcheung.xime.keyboard

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A conservative convex subset of a key's Gaussian winning region, in keyboard-local pixels. */
object GaussianKeyBoundary {
    data class Point(val x: Float, val y: Float)

    private data class Vertex(val x: Double, val y: Double)
    private data class Plane(val x: Double, val y: Double, val c: Double, val guard: Double) {
        fun value(p: Vertex) = x * p.x + y * p.y + c
    }

    /**
     * Does not change the probability model. [domain] defaults to the measured key rectangle;
     * callers may instead supply its actually painted bounds. Negative/nonfinite clearance fails
     * closed. Coincident competitors and numerically unrepresentable/degenerate results do too.
     *
     * For each competitor, D_own - D_other = Ax*u² + Bx*u + Ay*v² + By*v + C,
     * with u/v relative to the domain centre. On |u|<=hx, |v|<=hy, replacing each
     * quadratic by max(A,0)*h² is an upper bound (a concave term has centre tangent 0).
     * Clipping this affine upper bound below -clearance*|B| therefore implies true Gaussian
     * dominance everywhere, including edges/interior. Extra guard covers Float evaluation and
     * output rounding; the final vertices are checked against the guarded half-planes again.
     */
    fun polygon(
        letter: Char,
        keys: Map<Char, LetterKeyRect>,
        domain: LetterKeyRect? = null,
        clearance: Float = 0f,
    ): List<Point> {
        val own = keys[letter] ?: return emptyList()
        val bounds = domain ?: own
        if (!valid(own) || !valid(bounds) || !clearance.isFinite() || clearance < 0f) {
            return emptyList()
        }
        val left = bounds.x.toDouble()
        val top = bounds.y.toDouble()
        val right = left + bounds.width.toDouble()
        val bottom = top + bounds.height.toDouble()
        val hx = bounds.width.toDouble() / 2.0
        val hy = bounds.height.toDouble() / 2.0
        val cx = left + hx
        val cy = top + hy
        // Round rectangle edges inward before any clipping, never outside the requested domain.
        fun lower(v: Double): Double {
            val f = v.toFloat()
            return (if (f.toDouble() < v) Math.nextUp(f) else f).toDouble()
        }
        fun upper(v: Double): Double {
            val f = v.toFloat()
            return (if (f.toDouble() > v) Math.nextDown(f) else f).toDouble()
        }
        val l = lower(left)
        val r = upper(right)
        val t = lower(top)
        val b = upper(bottom)
        if (!l.isFinite() || !r.isFinite() || !t.isFinite() || !b.isFinite() || l >= r || t >= b) {
            return emptyList()
        }
        var vertices = listOf(Vertex(l - cx, t - cy), Vertex(r - cx, t - cy),
            Vertex(r - cx, b - cy), Vertex(l - cx, b - cy))
        val planes = ArrayList<Plane>()
        // Use the very same Float centres as SpatialCosts.logDensity, then expand in Double.
        val ox = (own.x + own.width / 2f).toDouble()
        val oy = (own.y + own.height / 2f).toDouble()
        val iw = 1.0 / own.width.toDouble() / own.width.toDouble()
        val ih = 1.0 / own.height.toDouble() / own.height.toDouble()
        val dx = cx - ox
        val dy = cy - oy
        val roundX = Math.ulp(max(abs(l), abs(r)).toFloat()).toDouble()
        val roundY = Math.ulp(max(abs(t), abs(b)).toFloat()).toDouble()
        for ((otherLetter, other) in keys) {
            if (otherLetter == letter || !valid(other)) continue
            val kx = (other.x + other.width / 2f).toDouble()
            val ky = (other.y + other.height / 2f).toDouble()
            if (ox == kx && oy == ky) return emptyList()
            val jw = 1.0 / other.width.toDouble() / other.width.toDouble()
            val jh = 1.0 / other.height.toDouble() / other.height.toDouble()
            val ex = cx - kx
            val ey = cy - ky
            val ax = iw - jw
            val ay = ih - jh
            val bx = 2.0 * (dx * iw - ex * jw)
            val by = 2.0 * (dy * ih - ey * jh)
            val constant = dx * dx * iw + dy * dy * ih - ex * ex * jw - ey * ey * jh +
                max(ax, 0.0) * hx * hx + max(ay, 0.0) * hy * hy
            // Bounds on normalized squared distances also bound Float arithmetic error in the
            // unchanged density implementation. 64 Float eps is deliberately conservative.
            val scale = 1.0 + (abs(dx) + hx) * (abs(dx) + hx) * iw +
                (abs(dy) + hy) * (abs(dy) + hy) * ih +
                (abs(ex) + hx) * (abs(ex) + hx) * jw +
                (abs(ey) + hy) * (abs(ey) + hy) * jh
            val guard = 64.0 * 1.1920928955078125e-7 * scale +
                2.0 * (abs(bx) * roundX + abs(by) * roundY)
            val plane = Plane(bx, by, constant + clearance.toDouble() * hypot(bx, by), guard)
            if (!plane.c.isFinite() || !guard.isFinite()) return emptyList()
            planes.add(plane)
            vertices = clip(vertices, plane)
            if (vertices.size < 3) return emptyList()
        }
        val result = vertices.map { Point((it.x + cx).toFloat(), (it.y + cy).toFloat()) }.distinct()
        if (result.size < 3) return emptyList()
        for (point in result) {
            if (!point.x.isFinite() || !point.y.isFinite() || point.x.toDouble() !in left..right ||
                point.y.toDouble() !in top..bottom) return emptyList()
            val local = Vertex(point.x.toDouble() - cx, point.y.toDouble() - cy)
            if (planes.any { it.value(local) > -it.guard / 2.0 }) return emptyList()
            val density = SpatialCosts.logDensity(point.x, point.y, own)
            if (!density.isFinite() || keys.any { (ch, key) -> ch != letter && valid(key) &&
                    density < SpatialCosts.logDensity(point.x, point.y, key) }) return emptyList()
        }
        // Relative coordinates avoid cancellation under large translations.
        val anchor = result.first()
        val area = result.indices.sumOf { i ->
            val p = result[i]
            val q = result[(i + 1) % result.size]
            (p.x.toDouble() - anchor.x) * (q.y.toDouble() - anchor.y) -
                (p.y.toDouble() - anchor.y) * (q.x.toDouble() - anchor.x)
        }
        return if (area > 0.0) result else emptyList()
    }

    private fun valid(rect: LetterKeyRect): Boolean = rect.x.isFinite() && rect.y.isFinite() &&
        rect.width.isFinite() && rect.height.isFinite() && rect.width > 0f && rect.height > 0f &&
        (rect.x + rect.width / 2f).isFinite() && (rect.y + rect.height / 2f).isFinite()

    private fun clip(input: List<Vertex>, plane: Plane): List<Vertex> {
        if (input.isEmpty()) return emptyList()
        val output = ArrayList<Vertex>()
        var previous = input.last()
        var previousValue = plane.value(previous) + plane.guard
        for (current in input) {
            val value = plane.value(current) + plane.guard
            if ((value <= 0.0) != (previousValue <= 0.0)) {
                val fraction = previousValue / (previousValue - value)
                output.add(Vertex(previous.x + fraction * (current.x - previous.x),
                    previous.y + fraction * (current.y - previous.y)))
            }
            if (value <= 0.0) output.add(current)
            previous = current
            previousValue = value
        }
        return output
    }
}
