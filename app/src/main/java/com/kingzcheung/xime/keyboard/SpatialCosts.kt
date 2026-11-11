package com.kingzcheung.xime.keyboard

import java.util.Locale
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Touch → letter probabilities for the probabilistic 26-key letter field.
 *
 * A tap is a sample from a mixture of isotropic Gaussians, one per letter, centred on the
 * letter's key centre and measured in units of the key's own width and height. The letter with
 * the highest density is what the tap types; every nearby letter keeps its likelihood relative to
 * that letter as log-odds. The decoder adds these log-odds to the word's model score, so a word's
 * final probability is P(touch sequence | letters) × P(word | user history, general lexicon).
 *
 * [CAP] is the floor of log 0.01 that Rime already uses for a flat correction, so an unmeasured
 * tap (glide, hardware keyboard) ranks exactly as before.
 */
object SpatialCosts {
    /** Gaussian spread in key-width units. 0.45 keeps the gap between two letters informative. */
    const val SIGMA = 0.45f
    const val CAP = 4.6051702f

    /** Only letters this close (in averaged key widths) can be neighbours of the typed letter. */
    const val NEIGHBOR_LIMIT = 1.3f

    /** Native threshold: a correction above this may outrank the typed spelling. */
    const val CHEAP_LIMIT = -1.6f

    /** Log of the (unnormalised) Gaussian density of [key] at the touch point. */
    fun logDensity(
        touchX: Float,
        touchY: Float,
        key: LetterKeyRect,
        sigma: Float = SIGMA,
    ): Float {
        if (key.width <= 0f || key.height <= 0f || sigma <= 0f ||
            !touchX.isFinite() || !touchY.isFinite()
        ) return Float.NEGATIVE_INFINITY
        val dx = (touchX - (key.x + key.width / 2f)) / key.width
        val dy = (touchY - (key.y + key.height / 2f)) / key.height
        return -(dx * dx + dy * dy) / (2f * sigma * sigma)
    }

    /**
     * Relative log P(letter | touch) for the letters that may be read instead of [literal].
     * Values are ≤ 0 and floored at -[cap]; an empty map means no measured reading.
     */
    fun costs(
        touchX: Float,
        touchY: Float,
        literal: Char,
        keys: Map<Char, LetterKeyRect>,
        sigma: Float = SIGMA,
        cap: Float = CAP,
    ): Map<Char, Float> {
        val origin = keys[literal] ?: return emptyMap()
        if (origin.width <= 0f || origin.height <= 0f || sigma <= 0f ||
            !touchX.isFinite() || !touchY.isFinite()
        ) return emptyMap()
        val typed = logDensity(touchX, touchY, origin, sigma)
        if (!typed.isFinite()) return emptyMap()
        val out = LinkedHashMap<Char, Float>()
        for ((letter, key) in keys) {
            if (letter == literal || key.width <= 0f || key.height <= 0f) continue
            val gapX = ((key.x + key.width / 2f) - (origin.x + origin.width / 2f)) / ((origin.width + key.width) / 2f)
            val gapY = ((key.y + key.height / 2f) - (origin.y + origin.height / 2f)) / ((origin.height + key.height) / 2f)
            if (hypot(gapX, gapY) !in 0.01f..NEIGHBOR_LIMIT) continue
            val neighbor = logDensity(touchX, touchY, key, sigma)
            if (!neighbor.isFinite()) continue
            val relative = neighbor - typed
            if (!relative.isFinite()) continue
            out[letter] = max(relative, -cap).coerceAtMost(0f)
        }
        return out
    }

    /** The letter with the highest density at the touch point: what a probabilistic tap types. */
    fun pick(
        touchX: Float,
        touchY: Float,
        keys: Map<Char, LetterKeyRect>,
        sigma: Float = SIGMA,
    ): Char? {
        if (!touchX.isFinite() || !touchY.isFinite()) return null
        var best: Char? = null
        var bestDensity = Float.NEGATIVE_INFINITY
        for ((letter, key) in keys) {
            val density = logDensity(touchX, touchY, key, sigma)
            if (density > bestDensity) {
                bestDensity = density
                best = letter
            }
        }
        return best
    }

    /** Normalised P(letter | touch) over all letters, for the field's visual feedback. */
    fun distribution(
        touchX: Float,
        touchY: Float,
        keys: Map<Char, LetterKeyRect>,
        sigma: Float = SIGMA,
    ): Map<Char, Float> {
        if (keys.isEmpty()) return emptyMap()
        val densities = LinkedHashMap<Char, Float>()
        var top = Float.NEGATIVE_INFINITY
        for ((letter, key) in keys) {
            val value = logDensity(touchX, touchY, key, sigma)
            densities[letter] = value
            if (value > top) top = value
        }
        if (!top.isFinite()) return emptyMap()
        var sum = 0f
        val out = LinkedHashMap<Char, Float>()
        for ((letter, value) in densities) {
            val weight = exp((value - top).toDouble()).toFloat()
            out[letter] = weight
            sum += weight
        }
        if (sum <= 0f) return emptyMap()
        return out.mapValues { it.value / sum }
    }

    /** One record per composition character. Empty when no tap carried a measured cost. */
    fun encode(records: List<Map<Char, Float>>): String {
        if (records.isEmpty() || records.all { it.isEmpty() }) return ""
        return records.joinToString(";") { encodeRecord(it) }
    }

    /** One tap's ordered `letter=cost` list; lets the input log append in O(keys). */
    fun encodeRecord(tap: Map<Char, Float>): String =
        tap.entries.sortedBy { it.key }.joinToString(",") { (letter, cost) ->
            String.format(Locale.US, "%c=%.4f", letter, cost)
        }
}

/** Touch samples offered on the UI thread and consumed, in order, on the input thread. */
object SpatialTaps {
    private val pending = ArrayDeque<Pair<Char, Map<Char, Float>>>()

    fun offer(letter: Char, touchX: Float, touchY: Float, keys: Map<Char, LetterKeyRect>) {
        val costs = SpatialCosts.costs(touchX, touchY, letter, keys)
        synchronized(pending) {
            if (pending.size >= 64) pending.removeFirst()
            pending.addLast(letter to costs)
        }
    }

    /** Drops stale heads until [letter], then removes that sample. Null when none is waiting. */
    fun poll(letter: Char): Map<Char, Float>? = synchronized(pending) {
        while (pending.isNotEmpty() && pending.first().first != letter) pending.removeFirst()
        if (pending.isEmpty()) null else pending.removeFirst().second
    }

    fun clear() = synchronized(pending) { pending.clear() }
}

/**
 * Penalties aligned with the current spelling. [beforeAppend] publishes the prediction for the
 * letter about to be processed; [afterInput] keeps that alignment only when the engine stored the
 * same letters. Japanese romaji expands a doubled consonant into っ (input "k"+"k" becomes
 * "xtsuk"), so the per-character touch positions no longer line up; from then on the composition
 * keeps Rime's word model alone until it is cleared.
 */
internal class SpatialInputLog {
    private val records = mutableListOf<Map<Char, Float>>()
    private val typed = StringBuilder()
    private var pending: List<Map<Char, Float>>? = null
    private var pendingTyped: String? = null
    private var pendingEncoded: String? = null
    private var encoded = ""
    private var disabled = false

    fun beforeAppend(letter: Char, costs: Map<Char, Float>): String {
        if (disabled) return ""
        val next = records + costs
        pending = next
        pendingTyped = typed.toString() + letter
        // Append one tap instead of re-encoding the whole composition. Reuse this
        // string after the engine confirms the key (previously encoded twice/key).
        val record = SpatialCosts.encodeRecord(costs)
        val text = if (records.isEmpty()) record else encoded + ";" + record
        pendingEncoded = text
        return text
    }

    fun afterInput(input: String): String {
        if (input.isEmpty()) {
            clear()
            return ""
        }
        if (disabled) return ""
        val normalized = input.filter { it.isLetter() }.lowercase()
        val predicted = pending
        val typedNow = pendingTyped
        val confirmedEncoded = pendingEncoded
        pending = null
        pendingTyped = null
        pendingEncoded = null
        if (predicted != null && typedNow != null) {
            if (normalized != typedNow) {
                disabled = true
                records.clear()
                typed.setLength(0)
                encoded = ""
                return ""
            }
            records.clear()
            records.addAll(predicted)
            typed.setLength(0)
            typed.append(typedNow)
            encoded = confirmedEncoded ?: SpatialCosts.encode(records)
            return encoded
        }
        // A key without a measure (backspace, candidate, hardware): keep the records aligned
        // with the letters the engine still has.
        if (normalized.length <= records.size) {
            while (records.size > normalized.length) records.removeAt(records.lastIndex)
            if (typed.length > normalized.length) typed.setLength(normalized.length)
        } else {
            clear()
        }
        encoded = SpatialCosts.encode(records)
        return encoded
    }

    fun clear() {
        records.clear()
        typed.setLength(0)
        pending = null
        pendingTyped = null
        pendingEncoded = null
        encoded = ""
        disabled = false
    }
}
