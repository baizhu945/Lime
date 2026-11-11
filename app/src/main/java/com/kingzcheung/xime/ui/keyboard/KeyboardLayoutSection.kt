package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable

/**
 * A non-inline composition boundary, not a layout node. Keep the keyboard's generated
 * methods small enough for ART to compile while preserving the enclosing layout scopes.
 */
@Composable
internal fun KeyboardLayoutSection(content: @Composable () -> Unit) {
    content()
}
