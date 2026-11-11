package com.kingzcheung.xime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class GaussianKeyBoundaryTest {
    private fun ansi(width: Float = 40f, height: Float = 60f): Map<Char, LetterKeyRect> {
        val keys = linkedMapOf<Char, LetterKeyRect>()
        listOf("qwertyuiop" to 0f, "asdfghjkl" to .5f, "zxcvbnm" to 1.5f)
            .forEachIndexed { row, (letters, offset) ->
                letters.forEachIndexed { column, letter ->
                    keys[letter] = LetterKeyRect((column + offset) * width, row * height, width, height)
                }
            }
        return keys
    }

    /** No tolerance on dominance: samples use the real production Float density and picker. */
    private fun checkPolygon(
        letter: Char,
        keys: Map<Char, LetterKeyRect>,
        domain: LetterKeyRect = keys.getValue(letter),
        clearance: Float = 0f,
    ): List<GaussianKeyBoundary.Point> {
        val poly = GaussianKeyBoundary.polygon(letter, keys, domain = domain, clearance = clearance)
        assertTrue("Unexpected empty region for $letter", poly.size >= 3)
        val own = keys.getValue(letter)
        fun sample(x: Float, y: Float) {
            assertTrue(x.isFinite() && y.isFinite())
            assertTrue(x.toDouble() >= domain.x.toDouble())
            assertTrue(x.toDouble() <= domain.x.toDouble() + domain.width.toDouble())
            assertTrue(y.toDouble() >= domain.y.toDouble())
            assertTrue(y.toDouble() <= domain.y.toDouble() + domain.height.toDouble())
            val density = SpatialCosts.logDensity(x, y, own)
            for ((other, key) in keys) {
                if (other != letter) assertTrue("$letter loses to $other at ($x,$y)",
                    density >= SpatialCosts.logDensity(x, y, key))
            }
            assertEquals(letter, SpatialCosts.pick(x, y, keys))
        }
        val centerX = poly.sumOf { it.x.toDouble() } / poly.size
        val centerY = poly.sumOf { it.y.toDouble() } / poly.size
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val c = poly[(i + 2) % poly.size]
            val cross = (b.x.toDouble() - a.x) * (c.y.toDouble() - b.y) -
                (b.y.toDouble() - a.y) * (c.x.toDouble() - b.x)
            assertTrue("Not convex", cross >= 0.0)
            sample(a.x, a.y)
            // Dense fan covers edges and the full interior, not merely key centres.
            for (step in 0..40) {
                val t = step / 40.0
                val ex = a.x * (1.0 - t) + b.x * t
                val ey = a.y * (1.0 - t) + b.y * t
                for (depth in 0..20) {
                    val r = depth / 20.0
                    sample((centerX * (1.0 - r) + ex * r).toFloat(),
                        (centerY * (1.0 - r) + ey * r).toFloat())
                }
            }
        }
        return poly
    }

    @Test fun equalWidthSameRowAndStaggeredRowsAreConservative() {
        val row = linkedMapOf('a' to LetterKeyRect(0f, 0f, 40f, 60f),
            'b' to LetterKeyRect(40f, 0f, 40f, 60f))
        val a = checkPolygon('a', row)
        val b = checkPolygon('b', row)
        assertTrue(a.maxOf { it.x } < 40f)
        assertTrue(b.minOf { it.x } > 40f)
        assertTrue(a.maxOf { it.x } > 39.9f)
        for (letter in ansi().keys) checkPolygon(letter, ansi())
    }

    @Test fun onePixelWidthAndHeightResidualsKeepEverySampleSafe() {
        val keys = ansi().mapValues { (letter, rect) ->
            rect.copy(width = rect.width + (letter.code % 3 - 1),
                height = rect.height + (letter.code % 2))
        }
        for (letter in keys.keys) checkPolygon(letter, keys)
        // Exercise both signs of quadratic coefficients in both axes explicitly.
        val pair = mapOf('a' to LetterKeyRect(0f, 0f, 40f, 61f),
            'b' to LetterKeyRect(40f, 30f, 41f, 60f))
        checkPolygon('a', pair)
        checkPolygon('b', pair)
    }

    @Test fun translationAndUniformScalingPreserveGeometryAndDominance() {
        val original = ansi()
        val baseline = checkPolygon('a', original)
        for ((scale, tx, ty) in listOf(Triple(.5f, 0f, 0f), Triple(2f, 1234f, -567f),
            Triple(1f, 1000000f, -1000000f))) {
            val transformed = original.mapValues { (_, rect) ->
                LetterKeyRect(rect.x * scale + tx, rect.y * scale + ty,
                    rect.width * scale, rect.height * scale)
            }
            for (letter in transformed.keys) checkPolygon(letter, transformed)
            val moved = GaussianKeyBoundary.polygon('a', transformed)
            assertEquals(baseline.size, moved.size)
            // ULP-dependent safety inset can grow at large translations.
            for (i in moved.indices) {
                assertEquals(baseline[i].x.toDouble(), ((moved[i].x - tx) / scale).toDouble(), .6)
                assertEquals(baseline[i].y.toDouble(), ((moved[i].y - ty) / scale).toDouble(), .6)
            }
        }
    }

    @Test fun actualAnsiACornerIsWonByQAndExcludedFromA() {
        val keys = ansi()
        assertEquals('q', SpatialCosts.pick(25f, 63f, keys))
        assertTrue(SpatialCosts.logDensity(25f, 63f, keys.getValue('q')) >
            SpatialCosts.logDensity(25f, 63f, keys.getValue('a')))
        val poly = checkPolygon('a', keys)
        // For a CCW convex polygon an exterior point violates at least one left half-plane.
        assertTrue(poly.indices.any { i ->
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            (b.x.toDouble() - a.x) * (63.0 - a.y) -
                (b.y.toDouble() - a.y) * (25.0 - a.x) < 0.0
        })
    }

    @Test fun paintedDomainIsClippedAndMayExtendBeyondOwnMeasurement() {
        val keys = ansi()
        val inset = LetterKeyRect(24f, 64f, 32f, 52f)
        checkPolygon('a', keys, inset)
        checkPolygon('a', keys, LetterKeyRect(10f, 50f, 70f, 90f))
        val single = mapOf('a' to LetterKeyRect(0f, 0f, 40f, 60f))
        val domain = LetterKeyRect(-10f, -20f, 80f, 100f)
        val poly = checkPolygon('a', single, domain)
        assertEquals(-10f, poly.minOf { it.x }, 0f)
        assertEquals(70f, poly.maxOf { it.x }, 0f)
        assertEquals(-20f, poly.minOf { it.y }, 0f)
        assertEquals(80f, poly.maxOf { it.y }, 0f)
    }

    @Test fun clearanceMovesBothSidesIntoTheirOwnRegionAndCanEraseIt() {
        val keys = linkedMapOf('a' to LetterKeyRect(0f, 0f, 40f, 60f),
            'b' to LetterKeyRect(40f, 0f, 40f, 60f))
        val a = checkPolygon('a', keys, clearance = 2f)
        val b = checkPolygon('b', keys, clearance = 2f)
        assertTrue(a.maxOf { it.x } <= 38f)
        assertTrue(b.minOf { it.x } >= 42f)
        assertTrue(a.maxOf { it.x } > 37.9f)
        assertTrue(GaussianKeyBoundary.polygon('a', keys, clearance = 100f).isEmpty())
        for (letter in ansi().keys) checkPolygon(letter, ansi(), clearance = 1.5f)
    }

    @Test fun invalidAndDegenerateInputsFailClosedButInvalidCompetitorsAreSkipped() {
        val own = LetterKeyRect(0f, 0f, 40f, 60f)
        val single = mapOf('a' to own)
        assertTrue(GaussianKeyBoundary.polygon('z', single).isEmpty())
        assertTrue(GaussianKeyBoundary.polygon('a', emptyMap()).isEmpty())
        val invalid = listOf(own.copy(width = 0f), own.copy(height = -1f),
            own.copy(x = Float.NaN), own.copy(y = Float.POSITIVE_INFINITY),
            own.copy(width = Float.POSITIVE_INFINITY), own.copy(height = Float.NaN))
        for (rect in invalid) {
            assertTrue(GaussianKeyBoundary.polygon('a', mapOf('a' to rect)).isEmpty())
            assertTrue(GaussianKeyBoundary.polygon('a', single, domain = rect).isEmpty())
            assertEquals(GaussianKeyBoundary.polygon('a', single),
                GaussianKeyBoundary.polygon('a', mapOf('a' to own, 'b' to rect)))
        }
        for (margin in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(GaussianKeyBoundary.polygon('a', single, clearance = margin).isEmpty())
        }
        assertTrue(GaussianKeyBoundary.polygon('a', mapOf('a' to own, 'b' to own)).isEmpty())
        assertTrue(GaussianKeyBoundary.polygon('a', mapOf('a' to own,
            'b' to LetterKeyRect(-.5f, -.5f, 41f, 61f))).isEmpty())
        // A positive Double interval with no distinct Float endpoints cannot form an area.
        assertTrue(GaussianKeyBoundary.polygon('a', single,
            domain = LetterKeyRect(1e20f, 0f, 1f, 1f)).isEmpty())
        // Domain completely in the competitor's region, or touching only the bisector.
        val pair = mapOf('a' to own, 'b' to own.copy(x = 40f))
        assertTrue(GaussianKeyBoundary.polygon('a', pair,
            domain = LetterKeyRect(40f, 0f, 20f, 60f)).isEmpty())
        assertFalse(GaussianKeyBoundary.polygon('a', single).isEmpty()) // self skipped by letter
    }

    @Test fun floatRoundingOnFractionalDomainsStillNeverCrossesARealBoundary() {
        val keys = ansi().mapValues { (_, r) ->
            r.copy(x = r.x + .12345f, y = r.y - .23456f, width = r.width + 1f)
        }
        for ((letter, rect) in keys) {
            checkPolygon(letter, keys, rect.copy(x = rect.x + .137f, y = rect.y + .163f,
                width = rect.width - .291f, height = rect.height - .319f))
        }
    }

    @Test fun originalGaussianEquationsAndSoftNeighborProbabilitiesRemainUnchanged() {
        val key = LetterKeyRect(0f, 0f, 40f, 60f)
        assertEquals(.45f, SpatialCosts.SIGMA, 0f)
        val divisor = 2f * .45f * .45f
        assertEquals(0f, SpatialCosts.logDensity(20f, 30f, key), 0f)
        assertEquals(-1f / divisor, SpatialCosts.logDensity(60f, 30f, key), 0f)
        assertEquals(-1f / divisor, SpatialCosts.logDensity(20f, 90f, key), 0f)
        assertEquals(-2f / divisor, SpatialCosts.logDensity(60f, 90f, key), 0f)
        assertEquals(-.25f / divisor, SpatialCosts.logDensity(40f, 30f, key), 0f)
        assertEquals(-.5f / divisor, SpatialCosts.logDensity(40f, 60f, key), 0f)
        val keys = linkedMapOf('a' to key, 'b' to key.copy(x = 40f))
        val before = SpatialCosts.distribution(20f, 30f, keys)
        val costsBefore = SpatialCosts.costs(20f, 30f, 'a', keys)
        GaussianKeyBoundary.polygon('a', keys, clearance = 2f)
        assertEquals(before, SpatialCosts.distribution(20f, 30f, keys))
        assertEquals(costsBefore, SpatialCosts.costs(20f, 30f, 'a', keys))
        assertEquals('a', SpatialCosts.pick(20f, 30f, keys))
        val ratio = exp((-1f / divisor).toDouble()).toFloat()
        assertEquals(ratio / (1f + ratio), before.getValue('b'), 1e-7f)
        assertTrue(before.getValue('b') > 0f)
        assertTrue(before.getValue('b') < before.getValue('a'))
        assertTrue(costsBefore.getValue('b') < 0f)
        val bisector = SpatialCosts.distribution(40f, 30f, keys)
        assertEquals(.5f, bisector.getValue('a'), 0f)
        assertEquals(.5f, bisector.getValue('b'), 0f)
    }
}
