package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.keyboard.CtrlShortcuts
import com.kingzcheung.xime.settings.FrostedGlassConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 符号键按住当 Ctrl（用户 10-24）。拖到字母上松手发 Ctrl+字母，拖法和 Shift 拖动一样；
 * 也可以另一根手指直接点字母（见 [ShiftSlideTargets.ctrlTap]），都是按下就生效。
 * 字母键的快捷键键面要按满 [holdMillis]（同长按）或拖出符号键才渐变出来，点一下开符号不闪（用户 10-31）。
 * 没拖动、没点过字母、在 [holdMillis] 内松手才是点按，走 [onTap]；
 * 超过时限或拖出去以后松手都不打开，没落在字母上就什么都不做。
 *
 * 带 [longPressItems] 时（底排 ?123 单键：“数字”/“常用符号”）按满 [longPressTimeoutMillis]
 * 且手指还在键帽里，就用既有的长按气泡（[onLongPressPreview] 转给 SwipeBubbleOverlay）弹出选项，
 * 松开选中那一项交给 [onLongPressSelect]；这时不再当 Ctrl，字母键不会闪快捷键键面。
 * 拖出键帽仍然走原来的 Ctrl 路，两者不抢。
 */
internal fun Modifier.ctrlSymbolKeyGesture(
    targets: ShiftSlideTargets,
    holdMillis: Long,
    onPressDown: () -> Unit,
    onTap: () -> Unit,
    onShortcut: (Char) -> Unit,
    onPressedChange: (Boolean) -> Unit,
    longPressItems: List<String> = emptyList(),
    longPressTimeoutMillis: Long = MODE_KEY_LONG_PRESS_MILLIS,
    onLongPressPreview: ((SwipeState, Rect) -> Unit)? = null,
    onLongPressSelect: ((String) -> Unit)? = null,
): Modifier = composed {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val hold by rememberUpdatedState(holdMillis)
    val down by rememberUpdatedState(onPressDown)
    val tapAction by rememberUpdatedState(onTap)
    val shortcut by rememberUpdatedState(onShortcut)
    val pressed by rememberUpdatedState(onPressedChange)
    val items by rememberUpdatedState(longPressItems)
    val menuHold by rememberUpdatedState(longPressTimeoutMillis)
    val preview by rememberUpdatedState(onLongPressPreview)
    val choose by rememberUpdatedState(onLongPressSelect)
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    onGloballyPositioned { bounds = it.boundsInRoot() }.pointerInput(targets, longPressItems) {
        awaitEachGesture {
            // 先于键帽自己的手势拿到按下（Initial），键帽只负责画。
            val first = awaitFirstDown(pass = PointerEventPass.Initial)
            first.consume()
            pressed(true)
            down()
            targets.beginCtrl { shortcut(it) }
            val faces = scope.launch {
                delay(hold)
                targets.showCtrlFaces()
            }
            var dragged = false
            var tap = false
            var menuOpen = false
            var selected = 0
            var target: ShiftSlideTargets.Target? = null
            val reportMenu = {
                preview?.invoke(SwipeState(isPressed = true, isLongPress = true, longPressItems = items,
                    selectedLongPressIndex = selected), bounds)
            }
            // 长按菜单：手指没拖出键帽才算长按；拖出去还是按住当 Ctrl。
            val menu = if (items.isEmpty()) null else scope.launch {
                delay(menuHold)
                if (!dragged) {
                    menuOpen = true
                    selected = 0
                    faces.cancel()
                    // 菜单期间不再当 Ctrl：字母键不闪快捷键键面，另一根手指点字母也照常输入。
                    targets.finish()
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    reportMenu()
                }
            }
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == first.id } ?: break
                    change.consume()
                    if (menuOpen) {
                        // 菜单出来后只按横向位置选，松手就上屏那一项。
                        selected = longPressItemIndex(change.position.x - first.position.x,
                            bounds.width / items.size, items.size)
                        reportMenu()
                    } else {
                        val pointer = bounds.topLeft + change.position
                        dragged = dragged || !bounds.contains(pointer) ||
                            (change.position - first.position).getDistance() > viewConfiguration.touchSlop
                        if (dragged) menu?.cancel()
                        target = if (dragged) targets.move(bounds, pointer) else null
                    }
                    if (!change.pressed) {
                        when {
                            menuOpen -> items.getOrNull(selected)?.let { chosen -> choose?.invoke(chosen) }
                            dragged -> target?.key?.singleOrNull()?.let(shortcut)
                            else -> tap = !targets.ctrlUsed && change.uptimeMillis - first.uptimeMillis < hold
                        }
                        break
                    }
                }
            } finally {
                menu?.cancel()
                faces.cancel()
                targets.finish()
                // 长按气泡跟其他键一样在松手时收起来（菜单已经选过一项也收）。
                preview?.invoke(SwipeState(), bounds)
                pressed(false)
            }
            if (tap) tapAction()
        }
    }
}

/** 底排 ?123 长按弹出「数字」「常用符号」的等待时长，和其他键的长按菜单一致。 */
internal const val MODE_KEY_LONG_PRESS_MILLIS = 400L

/** 快捷键键面渐变出来的时长；松手不渐变，立刻换回字母。 */
internal const val CTRL_FACE_FADE_MS = 250

/** Ctrl 键面：中间写用途，左上角小字母；没有常用用途的字母只淡淡写字母，拖上去照样发 Ctrl+字母。 */
@Composable
internal fun BoxScope.CtrlShortcutFace(
    letter: Char,
    textColor: Color,
    frostedGlass: FrostedGlassConfig,
    labelSp: Float,
    hintSize: TextUnit,
    hintsVisible: Boolean,
    keyWidthDp: Float,
    fontFamily: FontFamily?,
) {
    val action = CtrlShortcuts.label(letter)
    val fontScale = LocalDensity.current.fontScale
    if (action == null) {
        Text(letter.uppercase(), Modifier.align(Alignment.Center), color = keyHintColor(textColor, frostedGlass, 0.45f),
            fontSize = labelSp.sp, maxLines = 1, textAlign = TextAlign.Center, fontFamily = fontFamily)
        return
    }
    // 两个汉字占两倍字号宽，留出键帽左右的边。
    val actionSp = minOf(labelSp * 0.62f, keyWidthDp * 0.4f / fontScale)
    Text(action, Modifier.align(Alignment.Center), color = textColor, fontSize = actionSp.sp,
        lineHeight = (actionSp * 1.2f).sp, fontWeight = FontWeight.Medium, maxLines = 1, textAlign = TextAlign.Center)
    if (hintsVisible) Text(letter.uppercase(), Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 2.dp),
        color = keyHintColor(textColor, frostedGlass, 0.6f), fontSize = hintSize,
        lineHeight = (hintSize.value * 1.2f).sp, maxLines = 1, fontFamily = fontFamily)
}

/** [KeyboardModeKey] 里的符号键：按住当 Ctrl。 */
@Composable
internal fun CtrlSymbolKeyButton(
    text: String,
    targets: ShiftSlideTargets,
    holdMillis: Long,
    onTap: () -> Unit,
    onShortcut: (Char) -> Unit,
    onPressDown: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier,
    shadowEnabled: Boolean,
    shadowElevation: androidx.compose.ui.unit.Dp,
    shadowShapeRadius: androidx.compose.ui.unit.Dp,
    /** 长按弹出的选项（如 ?123 的「数字」「常用符号」）；不设时和原来一样只当 Ctrl。 */
    longPressItems: List<String>? = null,
    onLongPressSelect: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    KeyButton(
        text = text,
        fontSize = 16.sp,
        onClick = {},
        backgroundColor = backgroundColor,
        textColor = textColor,
        modifier = modifier.ctrlSymbolKeyGesture(targets, holdMillis, onPressDown, onTap, onShortcut,
            onPressedChange = { pressed = it },
            longPressItems = longPressItems.orEmpty(),
            onLongPressPreview = onSwipeStateChange,
            onLongPressSelect = onLongPressSelect),
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        externalPressed = pressed,
    )
}
