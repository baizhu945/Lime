package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.kingzcheung.xime.service.keyboardLiteralWidth

internal data class KeyboardPunctuation(val full: Boolean, val japanese: Boolean, val numberPanel: Boolean = false,
    val chinese: Boolean = false)
internal val LocalKeyboardPunctuation = staticCompositionLocalOf<KeyboardPunctuation?> { null }

/** Render literal key labels through the exact same policy used by commitLiteralText. */
@Composable
internal fun punctuationKeyLabel(raw: String): String {
    val policy = LocalKeyboardPunctuation.current ?: return raw
    return keyboardLiteralWidth(raw, policy.full, policy.japanese, policy.numberPanel, chineseKeyboard = policy.chinese)
}

/** Default panel presets follow the width selector; custom glyphs and letter-key corners do not. */
@Composable
internal fun presetPanelSymbol(raw: String): String {
    val policy = LocalKeyboardPunctuation.current ?: return raw
    return keyboardLiteralWidth(raw, policy.full, policy.japanese, policy.numberPanel)
}
