package com.kingzcheung.xime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialCostsTest {
    private fun keyboard(): Map<Char, LetterKeyRect> = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").flatMapIndexed { row, text ->
        text.mapIndexed { col, key -> key to LetterKeyRect(col * 40f + row * 8f, row * 60f, 40f, 60f) }
    }.toMap()

    private fun centerOf(letter: Char) = keyboard().getValue(letter).let { it.x + it.width / 2f to it.y + it.height / 2f }

    @Test fun centerOfHIsNotACheapReadingOfG() {
        val (x, y) = centerOf('h')
        val cost = SpatialCosts.costs(x, y, 'h', keyboard()).getValue('g')
        assertTrue("center penalty $cost", cost <= SpatialCosts.CHEAP_LIMIT)
        assertTrue(cost >= -SpatialCosts.CAP - 0.01f)
    }

    @Test fun touchOnTheGSideOfHIsACheapReadingOfG() {
        val h = keyboard().getValue('h')
        val cost = SpatialCosts.costs(h.x + h.width * 0.15f, h.y + h.height / 2f, 'h', keyboard()).getValue('g')
        assertTrue("boundary penalty $cost", cost > SpatialCosts.CHEAP_LIMIT)
        assertTrue(cost < 0f)
    }

    @Test fun theReadingBecomesMoreLikelyAsTheTouchMovesTowardIt() {
        val h = keyboard().getValue('h')
        val y = h.y + h.height / 2f
        fun costAt(fractionFromCenter: Float) = SpatialCosts.costs(
            h.x + h.width * (0.5f + fractionFromCenter), y, 'h', keyboard()).getValue('g')
        val nearH = costAt(0.4f)
        val middle = costAt(0.1f)
        val nearG = costAt(-0.3f)
        assertTrue("$nearH $middle $nearG", nearH < middle && middle < nearG)
        assertTrue(middle < 0f)
    }

    @Test fun distantKeysAreNotCandidates() {
        val (x, y) = centerOf('h')
        val costs = SpatialCosts.costs(x, y, 'h', keyboard())
        assertNull(costs['p'])
        assertNull(costs['h'])
    }

    @Test fun theHighestDensityLetterIsWhatTheTouchTypes() {
        val (hx, hy) = centerOf('h')
        assertEquals('h', SpatialCosts.pick(hx, hy, keyboard()))
        val g = keyboard().getValue('g')
        assertEquals('g', SpatialCosts.pick(g.x + g.width / 2f, g.y + g.height / 2f, keyboard()))
        val (gx, gy) = centerOf('g')
        val between = (hx + gx) / 2f
        // A touch past the midpoint reads as the other letter, even though the finger never left the
        // h cell: there are no key boundaries in the field.
        assertEquals('g', SpatialCosts.pick(between - 2f, hy, keyboard()))
        assertEquals('h', SpatialCosts.pick(gx + (hx - gx) * 0.6f, gy, keyboard()))
    }

    @Test fun distributionSumsToOneAndPeaksAtTheTouchedLetter() {
        val (x, y) = centerOf('h')
        val distribution = SpatialCosts.distribution(x, y, keyboard())
        assertEquals(1f, distribution.values.sum(), 1e-4f)
        assertEquals('h', distribution.maxByOrNull { it.value }?.key)
        assertTrue(distribution.getValue('h') > distribution.getValue('g'))
    }

    @Test fun encodeKeepsOneFieldPerCharacter() {
        val records = listOf(emptyMap(), emptyMap(), mapOf('g' to -0.4f, 'n' to -2.1f))
        assertEquals(";;g=-0.40,n=-2.10", SpatialCosts.encode(records))
        assertEquals("", SpatialCosts.encode(listOf(emptyMap(), emptyMap())))
    }

    @Test fun logAdoptsThePredictedSpellingAndRebuildsAfterAShorterOne() {
        val log = SpatialInputLog()
        assertEquals("g=-0.40", log.beforeAppend('h', mapOf('g' to -0.4f)))
        assertEquals("g=-0.40", log.afterInput("h"))
        // Backspace (no touch prediction) trims the records with the spelling.
        assertEquals("", log.afterInput(""))
        assertEquals("n=-1.20", log.beforeAppend('h', mapOf('n' to -1.2f)))
        assertEquals("n=-1.20", log.afterInput("h"))
        assertEquals("", log.afterInput(""))
    }

    @Test fun logStopsFeedingCostsWhenTheEngineRewritesTheLetters() {
        val log = SpatialInputLog()
        assertEquals("", log.beforeAppend('k', emptyMap()))
        assertEquals("", log.afterInput("k"))
        // Japanese romaji: the second k becomes っ, so the input is longer than the typed letters.
        assertEquals(";g=-0.40", log.beforeAppend('k', mapOf('g' to -0.4f)))
        assertEquals("", log.afterInput("xtsuk"))
        // The rest of the composition keeps Rime's word model alone.
        assertEquals("", log.beforeAppend('o', mapOf('l' to -0.4f)))
        assertEquals("", log.afterInput("xtsuko"))
        assertEquals("", log.afterInput(""))
    }
}
