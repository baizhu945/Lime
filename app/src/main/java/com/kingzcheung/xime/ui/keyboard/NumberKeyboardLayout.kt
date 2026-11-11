package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.KeyShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.keyboard.KeyboardInputPage
import com.kingzcheung.xime.util.SubcharHelper
import com.kingzcheung.xime.settings.InputProfile
import com.kingzcheung.xime.settings.LayoutKind

/** A keypad's number page inherits the text page's geometry settings. */
internal fun numberKeyboardSpacingSection(profile: InputProfile, ascii: Boolean): String = when {
    !ascii && profile.layout.kind == LayoutKind.KANA_KEYPAD -> "japanese_kana"
    !ascii && profile.layout.kind == LayoutKind.T9 -> "t9"
    else -> "number"
}

/** Numeric keypad; slot one opens symbols, slot two returns to the original text mode. */
@Composable
fun NumberKeyboardLayout(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = 8.dp,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    isFloatingMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
    isJapaneseKana: Boolean = false,
    /** 回车键动作名（换行、发送、搜索等），与主键盘同一来源，决定回车键图标。 */
    enterKeyText: String = "确定",
) {
    if (isJapaneseKana) {
        CompositionLocalProvider(LocalKeyboardPunctuation provides LocalKeyboardPunctuation.current?.copy(numberPanel = true)) {
            JapaneseKanaKeyboardLayout(
                onKanaAction = { if (it is JapaneseKanaAction.Input) onKeyPress(it.romaji) },
                onKeyPress = onKeyPress,
                keyBackgroundColor = keyBackgroundColor,
                keyTextColor = keyTextColor,
                specialKeyBackgroundColor = specialKeyBackgroundColor,
                specialKeyTextColor = specialKeyTextColor,
                keyboardBackgroundColor = keyboardBackgroundColor,
                modifier = modifier,
                onKeyPressDown = onKeyPressDown,
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                keyCornerRadius = keyCornerRadius,
                keySpacingX = keySpacingX,
                keySpacingY = keySpacingY,
                isFloatingMode = isFloatingMode,
                numberMode = true,
            )
        }
        return
    }
    val textSlot = LocalTextModeNavigationGeometry.current?.slots?.get(1)
    val density = LocalDensity.current
    var panelBounds by remember { mutableStateOf<Rect?>(null) }
    val inheritedInset = panelBounds?.let { bounds ->
        textSlot?.let { slot -> (slot.left - bounds.left).takeIf {
            it.isFinite() && it >= 0f && it < bounds.width / 2f
        } }?.let { with(density) { it.toDp() } }
    }
    val wide = LocalKeyboardInputPreferences.current.wideKeys
    // Keep the originating legacy text frame, including a full-width split frame;
    // centering a narrow keypad would leave its old mode-key position in empty space.
    val policy = if (inheritedInset != null) {
        (if (wide) KeyVisualPolicy.T9.widened() else KeyVisualPolicy.T9)
            .copy(maxKeyWidth = Float.MAX_VALUE, minGutter = 0f)
    } else KeyVisualPolicy.T9
    KeyboardKeySpacingScope(modifier.onGloballyPositioned {
        val next = it.boundsInRoot()
        if (panelBounds != next) panelBounds = next
    }, columns = 5f * 3f / 3.4f, horizontalInset = inheritedInset ?: 8.dp,
        policy = policy, allowShrink = isFloatingMode, applyGutter = true,
        widenKeys = wide && inheritedInset == null,
        growthSpacing = keySpacingX?.value to keySpacingY?.value) { bodyModifier ->

    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)

    val bubbleData = rememberSwipeBubbleDrawData(
        swipeState = swipeBubble.state,
        keyBounds = swipeBubble.keyBounds,
        keyBackgroundColor = bubbleBackgroundColor,
        keyTextColor = keyTextColor,
        accentColor = specialKeyTextColor,
        keyWidth = if (swipeBubble.state.isSwiping || swipeBubble.state.isPressed) swipeBubble.keyBounds.width else 0f,
        keyboardWidth = keyboardBounds.width
    )

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        val newState = if (state.isSwipeDown && state.swipeText != null) {
            state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
        } else state
        swipeBubble.update(
            newState,
            Rect(
                left = bounds.left - keyboardBounds.left,
                top = bounds.top - keyboardBounds.top,
                right = bounds.right - keyboardBounds.left,
                bottom = bounds.bottom - keyboardBounds.top
            )
        )
    }

    CompositionLocalProvider(LocalKeyCornerRadius provides keyCornerRadius,
        LocalKeyboardPunctuation provides LocalKeyboardPunctuation.current?.copy(numberPanel = true)) {
    Box(
        modifier = bodyModifier
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
            }
    ) {
        SwipeBubbleOverlay(bubbleData)
        run {
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 2.dp,
                )
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(bottom = 8.dp),
            ) {
                NumberRows(
                    onKeyPress = onKeyPress,
                    keyBackgroundColor = keyBackgroundColor,
                    keyTextColor = keyTextColor,
                    specialKeyBackgroundColor = specialKeyBackgroundColor,
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    onKeyPressDown = onKeyPressDown,
                    specialKeyTextColor = specialKeyTextColor,
                    enterKeyText = enterKeyText,
                    onSwipeStateChange = ::processSwipeState
                )
            }
            }
        }

    }
    }
    }
}

@Composable
private fun NumberRows(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    onKeyPressDown: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    compactMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
    enterKeyText: String = "确定",
) {
    val modeWeight = 0.8f
    val symFontSize = if (compactMode) 14.sp else 18.sp
    val keyFontSize = if (compactMode) 16.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    val ctrlFontSize = if (compactMode) 12.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    val suppressCursorMove = LocalSuppressCursorMove.current
    val symbols = listOf("+", "-", "*", "/")
    Column(Modifier.fillMaxSize()) {
        // Numeric navigation uses the operator-column width, independent of the text row.
        Row(Modifier.fillMaxWidth().weight(3f)) {
            Column(Modifier.weight(0.8f).fillMaxHeight().testTag("number-operators")
                .padding(scaledKeyVisualPadding())) {
                symbols.forEach { symbol ->
                    NumberSymbolKey(text = symbol, onClick = { onKeyPress(symbol) },
                        backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                        modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke(symbol) },
                        isFirst = symbol == "+", isLast = symbol == "/", fontSize = symFontSize,
                        shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius)
                }
            }
            Column(Modifier.weight(3.4f).fillMaxHeight()) {
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        row.forEach { key ->
                            KeyButton(text = key, onClick = { onKeyPress(key) },
                                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                                onPress = { onKeyPressDown?.invoke(key) },
                                modifier = Modifier.weight(1f).testTag("number-digit:$key"),
                                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius, fontSize = keyFontSize)
                        }
                    }
                }
            }
            Column(Modifier.weight(0.8f).fillMaxHeight().testTag("number-actions")) {
                SwipeableIconKeyButton(
                    icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                    onClick = { onKeyPress("delete") }, backgroundColor = specialKeyBackgroundColor,
                    iconColor = specialKeyTextColor, modifier = Modifier.weight(1f).testTag("number-delete-key"),
                    swipeText = "清空", onSwipe = { onKeyPress("clear_composition") },
                    onLongClick = { onKeyPress("delete") }, onPress = { onKeyPressDown?.invoke("delete") },
                    followSymbolSwipeDirection = true,
                    upFeedback = KeyFlickFeedback.DELETE,
                    swipeUpLabel = "上滑清空", downFeedback = KeyFlickFeedback.UNDO, swipeDownLabel = "下滑撤回",
                    onSwipeUp = { onKeyPress("clear_all") }, onSwipeDown = { onKeyPress("undo_clear") },
                    onSwipeLeft = { suppressCursorMove.value = true; onKeyPress("clear_composition") },
                    onSwipeStateChange = onSwipeStateChange,
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius)
                SpaceKeyButton(schemaName = "空格", onClick = { onKeyPress("space") },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("space") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius, fontSize = ctrlFontSize)
                IconKeyButton(icon = rememberVectorPainter(Icons.Default.EmojiEmotions),
                    onClick = { onKeyPress("emoji") }, backgroundColor = specialKeyBackgroundColor,
                    iconColor = specialKeyTextColor, modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("emoji") }, shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            for (slot in 1..2) KeyboardModeKey(slot = slot, page = KeyboardInputPage.NUMBERS,
                onKeyPress = onKeyPress, backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor, modifier = Modifier.weight(modeWeight),
                onKeyPressDown = onKeyPressDown, shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
            KeyButton(text = "0", onClick = { onKeyPress("0") },
                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                modifier = Modifier.weight(3.4f - 2 * modeWeight).testTag("number-zero"), onPress = { onKeyPressDown?.invoke("0") },
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius, fontSize = keyFontSize)
            KeyButton(text = ".", onClick = { onKeyPress(".") },
                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                modifier = Modifier.weight(0.8f), onPress = { onKeyPressDown?.invoke(".") },
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius, fontSize = keyFontSize)
            ActionKeyButton(text = enterKeyText, onClick = { onKeyPress("enter") },
                backgroundColor = specialKeyBackgroundColor, textColor = specialKeyTextColor,
                modifier = Modifier.weight(0.8f), onPress = { onKeyPressDown?.invoke("enter") },
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius, fontSize = ctrlFontSize)
        }
    }
}

@Composable
private fun NumberSymbolKey(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = 18.sp,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val cornerRadius = LocalKeyCornerRadius.current
    val shape = RoundedCornerShape(
        topStart = if (isFirst) cornerRadius else 0.dp,
        topEnd = if (isFirst) cornerRadius else 0.dp,
        bottomStart = if (isLast) cornerRadius else 0.dp,
        bottomEnd = if (isLast) cornerRadius else 0.dp
    )
    val density = LocalDensity.current
    val frostedGlass = LocalKeyboardInputPreferences.current.frostedGlass
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, KeyShapes.current, density, backgroundColor, frostedGlass.enabled) {
        if (shadowEnabled && !frostedGlass.enabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { KeyShapes.current.radius(shadowShapeRadius).toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(shadowModifier)
            .clip(shape)
            .background(frostedKeyColor(
                backgroundColor, textColor, frostedGlass,
                legacyStateColor = if (isPressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor,
                pressed = isPressed,
            ))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                }, onTap = { currentOnClick() })
            }, contentAlignment = Alignment.Center
    ) {
        val fittedSize = keyLabelSizeSp(text, fontSize.value, maxWidth.value, maxHeight.value - 4f, density.fontScale)
        Text(
            text = punctuationKeyLabel(text),
            color = textColor,
            fontSize = fittedSize.sp,
            lineHeight = (fittedSize * 1.2f).sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(vertical = 2.dp),
            fontFamily = AppFonts.keyFontFamily
        )
    }
}
