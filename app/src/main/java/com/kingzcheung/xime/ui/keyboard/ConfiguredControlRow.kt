package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.KeyGestureConfig
import com.kingzcheung.xime.settings.KeyboardRowsLayout
import com.kingzcheung.xime.settings.RowAlign

internal fun legacyControlLayoutLetters(layout: KeyboardRowsLayout, legacyRows: List<List<String>>): List<List<String>>? {
    val rows = layout.rows.map { it.keys }
    if (rows.size != 4 || rows != legacyRows || rows[0] != "qwertyuiop".map(Char::toString) ||
        rows[1] != "asdfghjkl".map(Char::toString) ||
        rows[2] != listOf("shift") + "zxcvbnm".map(Char::toString) + "delete") return null
    return listOf(rows[0], rows[1], rows[2].drop(1).dropLast(1))
}

/** A deliberately conservative adapter: unsupported bindings always leave the legacy renderer in charge. */
internal data class ConfiguredControlRow(val letterRows: List<List<String>>, val controls: List<String>)

/** Keep the other footer keys fixed: the period takes its width only from the merged ?123 span. */
internal data class ConfiguredPeriodWeights(val mode: Float, val period: Float)

internal fun configuredPeriodWeights(modeSpan: Float, punctuation: Float, enabled: Boolean): ConfiguredPeriodWeights {
    if (!enabled) return ConfiguredPeriodWeights(modeSpan, 0f)
    // Very narrow custom mode bindings must still leave both controls a positive width.
    val period = punctuation.coerceAtMost(modeSpan / 2f)
    return ConfiguredPeriodWeights(modeSpan - period, period)
}

internal data class ConfiguredFooterWeights(
    val mode: Float, val comma: Float, val period: Float,
    val space: Float, val language: Float, val enter: Float,
) {
    val total: Float get() = mode + comma + period + space + language + enter
}

internal fun configuredFooterWeights(
    bottom: QwertyBottomRowWeights,
    periodEnabled: Boolean,
    bindings: (String) -> KeyGestureConfig?,
): ConfiguredFooterWeights {
    val punctuation = bindings("comma")?.width ?: bottom.punctuation
    val mode = configuredPeriodWeights((bindings("mode_change")?.width ?: bottom.mode) * 2f,
        punctuation, periodEnabled)
    return ConfiguredFooterWeights(mode.mode, punctuation, mode.period,
        bindings("space")?.width ?: (bottom.space - if (periodEnabled) bottom.punctuation else 0f),
        bindings("earth")?.width ?: bottom.language,
        bindings("enter")?.width ?: bottom.enter)
}

/** Shared eligibility for the typing footer and the expanded candidate geometry. */
internal fun resolveTypingConfiguredControlRow(
    layout: KeyboardRowsLayout,
    profile: com.kingzcheung.xime.settings.InputProfile,
    customLetters: Boolean,
    split: Boolean,
    comma: Boolean,
    voice: Boolean,
    bindings: (String) -> KeyGestureConfig?,
): ConfiguredControlRow? = configuredControlRow(layout,
    eligible = !customLetters && !split && comma && !voice &&
        (profile.layout == com.kingzcheung.xime.settings.InputLayout.QWERTY ||
            profile.layout.id == "qwerty_japanese") &&
        profile.scheme in setOf(com.kingzcheung.xime.settings.InputScheme.PINYIN,
            com.kingzcheung.xime.settings.InputScheme.DIRECT,
            com.kingzcheung.xime.settings.InputScheme.ROMAJI) &&
        profile.language in setOf(com.kingzcheung.xime.settings.InputLanguage.CHINESE,
            com.kingzcheung.xime.settings.InputLanguage.ENGLISH,
            com.kingzcheung.xime.settings.InputLanguage.JAPANESE),
    bindings = bindings)

internal fun configuredControlRow(
    layout: KeyboardRowsLayout,
    eligible: Boolean,
    bindings: (String) -> KeyGestureConfig?,
): ConfiguredControlRow? {
    if (!eligible || layout.rows.size != 4 || layout.columns != null || layout.rowAlign != RowAlign.UNIFORM ||
        layout.rows.any { it.align != null || it.width != null }) return null
    val rows = layout.rows.map { it.keys }
    if (rows[0] != "qwertyuiop".map(Char::toString) ||
        rows[1] != "asdfghjkl".map(Char::toString) ||
        rows[2] != listOf("shift") + "zxcvbnm".map(Char::toString) + "delete") return null
    val controls = rows[3]
    if (controls.size != 5 || controls.toSet() != setOf("mode_change", "comma", "space", "earth", "enter")) return null
    // Compose weights must be finite and large enough for the two mode slots.
    if ((controls + listOf("shift", "delete")).any { id ->
            bindings(id)?.width?.let { !it.isFinite() || it < 0.01f || it > 1000f } == true
        }) return null
    // These components own their gestures. Do not silently discard a user rebinding.
    for (id in listOf("shift", "delete", "mode_change", "comma", "space", "enter")) {
        val binding = bindings(id) ?: continue
        if (binding.copy(width = null) != KeyGestureConfig()) return null
    }
    val earth = bindings("earth")
    if (earth != null) {
        val tap = earth.tap
        if (earth.copy(width = null, tap = null) != KeyGestureConfig() ||
            (tap != null && (tap.action != GestureAction.TOGGLE_ASCII || tap.unsupportedAction != null))) return null
    }
    return ConfiguredControlRow(listOf(rows[0], rows[1], rows[2].drop(1).dropLast(1)), controls)
}
