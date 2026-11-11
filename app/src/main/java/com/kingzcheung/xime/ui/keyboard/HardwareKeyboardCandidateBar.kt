package com.kingzcheung.xime.ui.keyboard

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import com.kingzcheung.xime.settings.SettingsPreferences
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.floor
import com.kingzcheung.xime.service.HardwareCursorAnchor

private const val MAX_VISIBLE_CANDIDATES = com.kingzcheung.xime.service.HARDWARE_PAGE_SIZE
internal val HardwareCandidateCornerRadius = 8.dp

@Composable
fun HardwareKeyboardCandidateBar(
    inputText: String,
    preeditText: String,
    candidates: List<String>,
    hasNextPage: Boolean,
    hasPrevPage: Boolean,
    cursorAnchor: HardwareCursorAnchor?,
    highlightIndex: Int,
    cardBackgroundColor: Color,
    candidateTextColor: Color,
    activeColor: Color,
    selectedTextColor: Color = activeColor,
    onCandidateSelect: ((Int) -> Unit)? = null,
    onBoundsChanged: (Rect?) -> Unit = {},
    avoidBoundsInWindow: Rect? = null,
    preeditBackgroundColor: Color = cardBackgroundColor,
    comments: List<String> = emptyList(),
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    surface: (@Composable () -> Modifier)? = null,
    bottomDocked: Boolean = false,
    onPreeditBoundsChanged: (Rect?) -> Unit = {},
    showNumberLabels: Boolean = true,
    frostedGlass: com.kingzcheung.xime.settings.FrostedGlassConfig = com.kingzcheung.xime.settings.FrostedGlassConfig(),
    /** 「自动避开输入区域」开启时传入光标位置：浮卡盖住这一行就上下挪开。与 [cursorAnchor] 跟随无关。 */
    avoidCaret: HardwareCursorAnchor? = null,
) {
    val reportBounds by rememberUpdatedState(onBoundsChanged)
    val reportPreeditBounds by rememberUpdatedState(onPreeditBoundsChanged)
    if (candidates.isEmpty() && inputText.isEmpty() && preeditText.isEmpty()) {
        LaunchedEffect(Unit) { reportBounds(null); reportPreeditBounds(null) }
        return
    }
    DisposableEffect(Unit) {
        onDispose { reportBounds(null); reportPreeditBounds(null) }
    }

    val density = LocalDensity.current
    val context = LocalContext.current
    val fontSize = rememberCandidateTextSize().sp
    val measurer = rememberTextMeasurer()
    val themeKey = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current?.key ?: cardBackgroundColor
    val shadowRadius = with(density) { 1.dp.toPx() }
    val textSurface = remember(frostedGlass, cardBackgroundColor, themeKey, shadowRadius, candidateTextColor, selectedTextColor, activeColor) {
        if (frostedGlass.enabled) resolveFrostedTextSurface(cardBackgroundColor, candidateTextColor, selectedTextColor, activeColor, frostedGlass, themeKey, shadowRadius) else null
    }
    val visuals = CandidateBarVisuals(cardBackgroundColor, candidateTextColor, Color.Transparent,
        activeColor, selectedTextColor, preeditBackgroundColor = preeditBackgroundColor).withTextSurface(textSurface)
    val showCandidates = !bottomDocked && candidates.isNotEmpty()
    // Like Gboard's floating window: the chevron opens the page as a grid of several rows.
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(inputText.isEmpty() && preeditText.isEmpty()) { if (inputText.isEmpty() && preeditText.isEmpty()) expanded = false }
    LaunchedEffect(showCandidates) { if (!showCandidates) reportBounds(null) }

    val displayText = if (preeditText.isNotEmpty()) preeditText else inputText
    LaunchedEffect(displayText) { if (displayText.isEmpty()) reportPreeditBounds(null) }

    val view = LocalView.current
    val viewLoc = remember { IntArray(2) }
    var hostPositionInRoot by remember { mutableStateOf<IntOffset?>(null) }
    var hostPositionInWindow by remember { mutableStateOf<IntOffset?>(null) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().testTag("hardware-candidate-host").onGloballyPositioned { coordinates ->
            val position = coordinates.positionInRoot()
            hostPositionInRoot = IntOffset(position.x.roundToInt(), position.y.roundToInt())
            val windowPosition = coordinates.positionInWindow()
            hostPositionInWindow = IntOffset(windowPosition.x.roundToInt(), windowPosition.y.roundToInt())
        }
    ) {
        // Use the actual host and measured card, including split-window origins.
        // The insertion marker is already in screen pixels; zero is a valid position.
        val marginPx = with(density) { 8.dp.roundToPx() }
        // A floating candidate window must stay narrower than the editor so it can follow
        // the caret; a full-width card is pinned to both margins and cannot move.
        val maxCardWidth = minOf(maxWidth - 16.dp, 560.dp, maxWidth * 0.8f).coerceAtLeast(1.dp)
        val maxCardHeight = (maxHeight - 16.dp).coerceAtLeast(1.dp)
        view.getLocationOnScreen(viewLoc)
        val origin = (hostPositionInRoot ?: IntOffset.Zero) + IntOffset(viewLoc[0], viewLoc[1])
        val toolbarExclusion = hostPositionInWindow?.let { hostOrigin ->
            avoidBoundsInWindow?.let { excluded ->
                HardwareCandidateExclusion(
                    excluded.left - hostOrigin.x, excluded.top - hostOrigin.y,
                    excluded.right - hostOrigin.x, excluded.bottom - hostOrigin.y,
                )
            }
        }
        val placement = hardwareCandidatePosition(
            constraints.maxWidth, constraints.maxHeight, cardSize.width, cardSize.height,
            cursorAnchor.takeIf { hostPositionInRoot != null && !bottomDocked }, origin.x, origin.y,
            marginPx, with(density) { 8.dp.roundToPx() },
            avoidBounds = toolbarExclusion,
        )
        val textWidth = candidates.take(MAX_VISIBLE_CANDIDATES).mapIndexed { index, text ->
            measurer.measure(AnnotatedString("${(index + 1) % 10} $text"),
                candidatePrimaryTextStyle(fontSize, AppFonts.candidateFontFamily, index == highlightIndex), softWrap = false).size.width
        }.sum()
        val contentWidth = (with(density) { textWidth.toDp() } + (12 * candidates.size + 104).dp)
            .coerceIn(minOf(160.dp, maxCardWidth), maxCardWidth)
        val dockBounds = if (bottomDocked) hostPositionInWindow?.let { originInWindow ->
            avoidBoundsInWindow?.let { IntOffset(it.left - originInWindow.x, it.top - originInWindow.y) }
        } else null
        // 光标所在行按整行宽避让：只要浮卡压住这一行的任何位置，正在打的字就被挡住。
        val caretLine = avoidCaret?.takeIf { hostPositionInRoot != null && it.top.isFinite() && it.bottom.isFinite() }
            ?.let { HardwareCandidateExclusion(0, (it.top - origin.y).roundToInt(), constraints.maxWidth, (it.bottom - origin.y).roundToInt()) }
        val (x, y) = avoidHardwareCaretLine(HardwareCandidatePosition(
            dockBounds?.x?.coerceIn(marginPx, maxOf(marginPx, constraints.maxWidth - cardSize.width - marginPx)) ?: placement.x,
            dockBounds?.let { (it.y - cardSize.height - marginPx).coerceAtLeast(marginPx) } ?: placement.y),
            cardSize.width, cardSize.height, constraints.maxHeight, caretLine, toolbarExclusion,
            marginPx, with(density) { 4.dp.roundToPx() })
        // This group has no painted background or pointer handler. Each visible surface reports
        // its own touch region, leaving the gap and the rest of the editor usable.
        Column(Modifier.absoluteOffset { IntOffset(x, y) }.widthIn(max = maxCardWidth)
            .heightIn(max = maxCardHeight).onSizeChanged { cardSize = it },
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (displayText.isNotEmpty()) {
                val shape = RoundedCornerShape(4.dp)
                // Let the displayed text and its real padding determine the width. A second
                // text measurement can disagree with font resolution or pixel-rounded padding.
                Box(Modifier.widthIn(min = minOf(24.dp, maxCardWidth), max = maxCardWidth)
                    .clip(shape)
                    .then(surface?.invoke() ?: Modifier.background(preeditBackgroundColor))
                    .testTag("hardware-preedit-card")
                    .onGloballyPositioned { reportPreeditBounds(it.boundsInWindow().toAndroidBounds()) }
                    .glassTextHalo(textSurface?.halo)) {
                    PreeditLabel(displayText, visuals.copy(preeditBackgroundColor = Color.Transparent),
                        followTextTail = true)
                }
            }
            if (showCandidates) {
                val shape = RoundedCornerShape(HardwareCandidateCornerRadius)
                Box(Modifier.width(contentWidth).clip(shape)
                    .then(surface?.invoke() ?: Modifier.background(cardBackgroundColor))
                    .testTag("hardware-candidate-card")
                    .onGloballyPositioned { reportBounds(it.boundsInWindow().toAndroidBounds()) }
                    // 展开、收起和屏幕键盘的候选展开同一套动效：卡片高度按面板时长过渡，内容交叉淡入（用户 10-21）。
                    .animateContentSize(com.kingzcheung.xime.ui.theme.CyMotion.panel(expanded))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .glassTextHalo(textSurface?.halo)) {
                    androidx.compose.animation.Crossfade(expanded,
                        animationSpec = com.kingzcheung.xime.ui.theme.CyMotion.enter(com.kingzcheung.xime.ui.theme.CyMotion.MEDIUM),
                        label = "hardware-candidate-expand") { open ->
                    if (open) HardwareCandidateGrid(candidates.take(MAX_VISIBLE_CANDIDATES), highlightIndex,
                        visuals, onCandidateSelect, onCollapse = { expanded = false })
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            HardwareCandidateRow(candidates, comments, highlightIndex, hasPrevPage, hasNextPage,
                                visuals, onCandidateSelect, onPrevious, onNext, showNumberLabels)
                        }
                        if (candidates.size > 1) KeyboardToolbarButton({ expanded = true }, Color.Transparent,
                            modifier = Modifier.testTag("hardware-candidate-expand")) {
                            Icon(Icons.Default.ExpandMore, "展开候选", tint = visuals.textColor)
                        }
                    }
                    }
                }
            }
        }
    }
}

/** Expanded floating candidates: rows of five, each numbered for the digit keys. */
@Composable
private fun HardwareCandidateGrid(
    candidates: List<String>, highlightIndex: Int, visuals: CandidateBarVisuals,
    onCandidateSelect: ((Int) -> Unit)?, onCollapse: () -> Unit,
) {
    val fontSize = rememberCandidateTextSize().sp
    Column(Modifier.testTag("hardware-candidate-grid")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            KeyboardToolbarButton(onCollapse, Color.Transparent, modifier = Modifier.testTag("hardware-candidate-collapse")) {
                Icon(Icons.Default.ExpandLess, "收起候选", tint = visuals.textColor)
            }
        }
        candidates.chunked(5).forEachIndexed { row, words ->
            Row(Modifier.fillMaxWidth()) {
                words.forEachIndexed { column, word ->
                    val index = row * 5 + column
                    val selected = index == highlightIndex
                    Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (selected) visuals.accentColor.copy(alpha = .18f) else Color.Transparent)
                        .clickable { onCandidateSelect?.invoke(index) }.testTag("hardware-grid-candidate:$index"),
                        contentAlignment = Alignment.Center) {
                        Text("${(index + 1) % 10} $word", color = if (selected) visuals.selectedTextColor else visuals.textColor,
                            fontSize = fontSize, maxLines = 1, softWrap = false)
                    }
                }
                repeat(5 - words.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun androidx.compose.ui.geometry.Rect.toAndroidBounds() = Rect(
    floor(left).toInt(), floor(top).toInt(), ceil(right).toInt(), ceil(bottom).toInt())

/** The exact same single-row renderer is used beside the caret and inside the dock. */
@Composable
internal fun hardwareCandidateRowHeight(): androidx.compose.ui.unit.Dp =
    (candidateItemHeight(SettingsPreferences.getCandidateTextSize(LocalContext.current).sp) + 8.dp)
        .coerceAtLeast(48.dp)

@Composable
internal fun HardwareCandidateRow(
    candidates: List<String>, comments: List<String>, highlightIndex: Int,
    hasPrevPage: Boolean, hasNextPage: Boolean, visuals: CandidateBarVisuals,
    onCandidateSelect: ((Int) -> Unit)?, onPrevious: (() -> Unit)?, onNext: (() -> Unit)?,
    showNumberLabels: Boolean = true,
) {
    val protectedVisuals = visuals.withTextSurface(LocalFrostedTextSurface.current)
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        FixedCandidateStrip(candidates.take(MAX_VISIBLE_CANDIDATES), comments = comments,
            visuals = protectedVisuals,
            callbacks = CandidateBarCallbacks(onCandidateSelect = { onCandidateSelect?.invoke(it) }),
            fontSize = SettingsPreferences.getCandidateTextSize(context).sp,
            highlightIndex = highlightIndex, showNumberLabels = showNumberLabels, itemSpacing = 8.dp,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp))
        if (candidates.isNotEmpty() && onPrevious != null && (hasPrevPage || highlightIndex > 0)) {
            KeyboardToolbarButton(onPrevious, Color.Transparent,
                modifier = Modifier.testTag("hardware-candidate-previous")) {
                Icon(Icons.Default.ChevronLeft, "上一候选", tint = protectedVisuals.textColor)
            }
        }
        if (candidates.isNotEmpty() && onNext != null && (hasNextPage || highlightIndex < candidates.lastIndex)) {
            KeyboardToolbarButton(onNext, Color.Transparent,
                modifier = Modifier.testTag("hardware-candidate-next")) {
                Icon(Icons.Default.ChevronRight, "下一候选", tint = protectedVisuals.textColor)
            }
        }
    }
}
