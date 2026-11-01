package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.keyboard.MODE_CHANGE_LAST
import com.kingzcheung.xime.keyboard.MODE_CHANGE_LONG_PRESS_LABELS
import com.kingzcheung.xime.keyboard.modeChangeActionForSelection

/** 键面写的就是 Xime 原来的 ?123；点按进上次用过的数字/常用符号页。 */
internal const val MODE_KEY_LABEL = "?123"

/** 配置控制行（Xime 外观）里这颗单键的测试标签，取代原来的 mode-slot-1／mode-slot-2。 */
internal const val MODE_KEY_TAG = "configured-mode-key"

/**
 * 底排 ?123 单键（只在 Xime 配置行用）：点按 = 上次用过的页面（偏好默认「数字」），
 * 长按 = 既有长按气泡弹出「数字」「常用符号」两项，各自直达。
 *
 * 原来这一格是两个 [KeyboardModeKey]（!@# 和 123），Ctrl 拖选挂在 !@# 上；
 * 收成一个键以后仍然走同一条 Ctrl 通道（[CtrlSymbolKeyButton]）：按下就当 Ctrl，
 * 拖出键帽发 Ctrl+字母、另一根手指点字母也照旧，只有按住不放才换成「数字」「常用符号」菜单。
 */
@Composable
internal fun XimeModeKey(
    onKeyPress: (String) -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    /** 26 键传入：按住 ?123 当 Ctrl。 */
    ctrlTargets: ShiftSlideTargets? = null,
    onCtrlShortcut: ((Char) -> Unit)? = null,
    /** 长按气泡要画在键盘层，交给外层既有的 SwipeBubble；不传就不弹气泡（只发按键）。 */
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
) {
    // 键面写「，」「。」这类符号时按键本身不该再被标点宽度设置转一次；和 KeyboardModeKey 一致。
    CompositionLocalProvider(LocalKeyboardPunctuation provides null) {
        if (ctrlTargets != null && onCtrlShortcut != null) CtrlSymbolKeyButton(
            text = MODE_KEY_LABEL,
            targets = ctrlTargets,
            holdMillis = LocalKeyboardInputPreferences.current.symbolKeyHoldMs,
            onTap = { onKeyPress(MODE_CHANGE_LAST) },
            onShortcut = onCtrlShortcut,
            onPressDown = { onKeyPressDown?.invoke(MODE_CHANGE_LAST) },
            backgroundColor = backgroundColor,
            textColor = textColor,
            modifier = modifier.testTag(MODE_KEY_TAG),
            shadowEnabled = shadowEnabled,
            shadowElevation = shadowElevation,
            shadowShapeRadius = shadowShapeRadius,
            longPressItems = MODE_CHANGE_LONG_PRESS_LABELS,
            onLongPressSelect = { label ->
                modeChangeActionForSelection(label)?.let(onKeyPress)
            },
            onSwipeStateChange = onSwipeStateChange,
        ) else SwipeableKeyButton(
            text = MODE_KEY_LABEL,
            onClick = { onKeyPress(MODE_CHANGE_LAST) },
            backgroundColor = backgroundColor,
            textColor = textColor,
            modifier = modifier.testTag(MODE_KEY_TAG),
            onPress = { onKeyPressDown?.invoke(MODE_CHANGE_LAST) },
            onLongPressSelect = { label -> modeChangeActionForSelection(label)?.let(onKeyPress) },
            longPressItems = MODE_CHANGE_LONG_PRESS_LABELS,
            onSwipeStateChange = onSwipeStateChange,
            shadowEnabled = shadowEnabled,
            shadowElevation = shadowElevation,
            shadowShapeRadius = shadowShapeRadius,
        )
    }
}
