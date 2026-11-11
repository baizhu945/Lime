package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 一次按下只触发一个输入分支；长按动作到时执行，滑动与菜单松手确认。 */
internal data class KeyGestureActions(
    val text: String,
    val onTap: () -> Unit,
    val onPress: () -> Unit,
    val onRelease: () -> Unit,
    val onPreview: (SwipeState) -> Unit,
    val upText: String? = null,
    val downText: String? = null,
    val onUp: ((String) -> Unit)? = null,
    val onDown: ((String) -> Unit)? = null,
    val leftText: String? = null,
    val onLeft: (() -> Unit)? = null,
    val rightText: String? = null,
    val onRight: (() -> Unit)? = null,
    val horizontalThresholdDp: Float = 50f,
    val verticalThresholdDp: Float = 50f,
    val reserveHorizontalSwipe: Boolean = false,
    val longPressItems: List<String> = emptyList(),
    val longPressDrawableIds: List<Int> = emptyList(),
    val keyWidth: Float = 1f,
    val onLongPress: (() -> Unit)? = null,
    val onLongPressSelect: ((String) -> Unit)? = null,
    val onLongPressFeedback: () -> Unit = {},
    val longPressTimeoutMillis: Long? = null,
    val blockUpSwipe: Boolean = false,
    val blockDownSwipe: Boolean = false,
    val inlineVerticalPreview: Boolean = false,
    val inlineHorizontalPreview: Boolean = false,
    val upPreviewText: String? = upText,
    val downPreviewText: String? = downText,
    val upPreviewFromTop: Boolean = true,
    val downPreviewFromTop: Boolean = false,
    /**
     * Four-way flick keys (nine-key flick letters, Japanese kana): the direction follows the finger's
     * current dominant axis, so the finger may re-aim without returning to the centre, and the release
     * point decides. Released within the threshold is a tap; past it in a direction without a choice
     * enters nothing.
     */
    val freeDirection: Boolean = false,
    val onTapAt: ((Offset) -> Unit)? = null,
    /** 概率字母区：按下时的触点，用来记录这次采样的分布。 */
    val onPressAt: ((Offset) -> Unit)? = null,
)

/**
 * 长按气泡里手指横向落在第几项：每项 [itemWidth] 宽，从键的中心算起，两边半格即算选中；
 * 只有一项或一项也没有时恒为 0。按住符号键当 Ctrl 的键要弹菜单时也用同一份算法。
 */
internal fun longPressItemIndex(dx: Float, itemWidth: Float, count: Int): Int {
    if (count <= 1) return 0
    return ((dx / itemWidth.coerceAtLeast(1f)) + 0.5f).toInt().coerceIn(0, count - 1)
}

internal suspend fun PointerInputScope.detectExclusiveKeyGestures(
    currentActions: () -> KeyGestureActions,
) = coroutineScope {
    val gestureScope = this
    val horizontalCancelThreshold = 60.dp.toPx()
    // 快速连打同一个键（小鹤 uu）时两只拇指会交叠：前一下还没抬，后一下已按在同一键上。
    // 后按下的手指结束前一下（按它当时的位置结算），再接着算自己的一下；不等所有手指抬起，否则这一下被吞。
    var handover: PointerInputChange? = null
    awaitPointerEventScope { while (true) {
        val down = handover ?: awaitNewDown()
        handover = null
        val actions = currentActions()
        val swipeThreshold = actions.verticalThresholdDp.dp.toPx()
        val horizontalThreshold = actions.horizontalThresholdDp.dp.toPx()
        down.consume()
        actions.onPress()
        actions.onPressAt?.invoke(down.position)
        actions.onPreview(SwipeState(isPressed = true, pressedText = actions.text))
        var longPressed = false
        var moved = false
        var crossedSwipeThreshold = false
        // Once a direction owns the drag, sideways drift cannot undo its preview.
        // Returning along that axis to the key centre releases the direction.
        var verticalDrag: Boolean? = null
        // A free flick keeps the touch once it has left the centre, even after coming back.
        var flickClaimed = false
        var selectedIndex = 0
        val items = actions.longPressItems
        val longPressJob = if (items.isNotEmpty() || actions.onLongPress != null) gestureScope.launch {
            delay(actions.longPressTimeoutMillis ?: if (items.isNotEmpty()) 400L else viewConfiguration.longPressTimeoutMillis)
            longPressed = true
            actions.onLongPressFeedback()
            if (items.isNotEmpty()) {
                actions.onPreview(SwipeState(isPressed = true, isLongPress = true,
                    longPressItems = items, selectedLongPressIndex = selectedIndex,
                    longPressDrawableIds = actions.longPressDrawableIds))
            } else actions.onLongPress?.invoke()
        } else null
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!longPressed && (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop)) {
                    moved = true
                    longPressJob?.cancel()
                }
                val slop = viewConfiguration.touchSlop
                // Back at the key's own centre also means the centre choice, for presses that began
                // off-centre; a fixed tolerance, so wide tablet keys still flick sideways.
                val atKeyCentre = actions.freeDirection && moved &&
                    abs(change.position.x - size.width / 2f) <= slop && abs(change.position.y - size.height / 2f) <= slop
                if (actions.freeDirection) {
                    if (!longPressed) verticalDrag = when {
                        maxOf(abs(dx), abs(dy)) <= slop || atKeyCentre -> null
                        abs(dy) >= abs(dx) -> true
                        else -> false
                    }
                    if (verticalDrag != null) flickClaimed = true
                } else if ((verticalDrag == true && abs(dy) <= slop) || (verticalDrag == false && abs(dx) <= slop))
                    verticalDrag = null
                if (verticalDrag == null && !longPressed && !actions.freeDirection) {
                    if (abs(dy) > slop && abs(dy) > abs(dx) * 1.1f &&
                        (actions.onUp != null || actions.onDown != null || actions.blockUpSwipe || actions.blockDownSwipe)) verticalDrag = true
                    else if (abs(dx) > slop && abs(dx) > abs(dy) * 1.1f &&
                        (actions.onLeft != null || actions.onRight != null)) verticalDrag = false
                }
                val up = verticalDrag == true && dy < -swipeThreshold && actions.onUp != null
                val downSwipe = verticalDrag == true && dy > swipeThreshold && actions.onDown != null
                val left = verticalDrag == false && dx < -horizontalThreshold && actions.onLeft != null
                val right = verticalDrag == false && dx > horizontalThreshold && actions.onRight != null
                val blockedUp = verticalDrag == true && actions.blockUpSwipe && dy < -swipeThreshold
                val blockedDown = verticalDrag == true && actions.blockDownSwipe && dy > swipeThreshold
                // Free flicks are decided where the finger lifts, so leaving the centre is not sticky.
                val outsideCentre = actions.freeDirection && !atKeyCentre && maxOf(abs(dx), abs(dy)) > swipeThreshold
                crossedSwipeThreshold = if (actions.freeDirection) outsideCentre
                    else crossedSwipeThreshold || up || downSwipe || left || right || blockedUp || blockedDown
                if (longPressed && items.isNotEmpty()) {
                    selectedIndex = longPressItemIndex(dx, actions.keyWidth / items.size, items.size)
                    actions.onPreview(SwipeState(isPressed = true, isLongPress = true,
                        longPressItems = items, selectedLongPressIndex = selectedIndex,
                        longPressDrawableIds = actions.longPressDrawableIds))
                } else if (!longPressed) {
                    val hint = if (up) actions.upText else if (downSwipe) actions.downText else if (left) actions.leftText else if (right) actions.rightText else null
                    actions.onPreview(SwipeState(isSwiping = hint != null, swipeText = hint,
                        isSwipeDown = downSwipe, isPressed = !moved, pressedText = actions.text,
                        keyFlick = (if (actions.inlineVerticalPreview && verticalDrag == true) keyFlickPreview(
                            0f, dy, viewConfiguration.touchSlop, swipeThreshold,
                            actions.upPreviewText.takeIf { actions.onUp != null && !actions.blockUpSwipe },
                            actions.downPreviewText.takeIf { actions.onDown != null && !actions.blockDownSwipe },
                            actions.upPreviewFromTop, actions.downPreviewFromTop,
                        ) else null) ?: if (actions.inlineHorizontalPreview && verticalDrag == false) horizontalKeyFlickPreview(
                            dx, 0f, viewConfiguration.touchSlop, horizontalThreshold,
                            actions.leftText.takeIf { actions.onLeft != null },
                            actions.rightText.takeIf { actions.onRight != null },
                        ) else null))
                }
                // 长按菜单正在选时不换手，保持原来的选择。
                val nextDown = if (longPressed) null
                    else event.changes.firstOrNull { it.id != down.id && it.changedToDown() && !it.isConsumed }
                if (!change.pressed || nextDown != null) {
                    change.consume()
                    handover = nextDown
                    when {
                        longPressed -> items.getOrNull(selectedIndex)?.let { actions.onLongPressSelect?.invoke(it) }
                        up -> actions.onUp?.invoke(actions.upText.orEmpty())
                        downSwipe -> actions.onDown?.invoke(actions.downText.orEmpty())
                        left -> actions.onLeft?.invoke()
                        right -> actions.onRight?.invoke()
                        !crossedSwipeThreshold && (actions.freeDirection || abs(dx) < horizontalCancelThreshold) -> {
                            actions.onTapAt?.invoke(down.position)
                            actions.onTap()
                        }
                    }
                    break
                }
                // Reserve paired-letter drags before the whole-keyboard cursor's
                // activation distance, even when the user sets a longer threshold.
                if (longPressed || crossedSwipeThreshold || verticalDrag != null || flickClaimed || actions.reserveHorizontalSwipe &&
                    abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy) * 1.1f) change.consume()
                else {
                    // 父层光标/滚动手势可能在 Main 后半段消费；取消后不得补出普通字符。
                    val finalChange = awaitPointerEvent(PointerEventPass.Final)
                        .changes.firstOrNull { it.id == down.id }
                    if (finalChange == null || finalChange.isConsumed) break
                }
            }
        } finally {
            longPressJob?.cancel()
            actions.onRelease()
            actions.onPreview(SwipeState())
        }
    } }
}

/** 这个键上新按下的一根手指；已经结算过、还按着的手指不算。 */
private suspend fun AwaitPointerEventScope.awaitNewDown(): PointerInputChange {
    while (true) {
        awaitPointerEvent().changes.firstOrNull { it.changedToDown() && !it.isConsumed }?.let { return it }
    }
}
