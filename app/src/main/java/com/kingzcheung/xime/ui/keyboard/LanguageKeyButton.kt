package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.common.CompactMenuStyle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.compositeOver
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.ui.theme.KeyboardPalette
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.LocalKeyboardPalette
import com.kingzcheung.xime.ui.theme.MaterialLevel
import com.kingzcheung.xime.ui.theme.visualMaterial
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kingzcheung.xime.settings.ButtonLayout
import com.kingzcheung.xime.settings.SchemaInfo
import kotlinx.coroutines.delay
import kotlin.math.roundToLong
import com.kingzcheung.xime.ui.theme.cyPopupEnter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun languageMenuSelection(
    schemas: List<SchemaInfo>,
    pointer: Offset,
    viewport: Rect,
    scrollOffset: Int,
    rowHeight: Float,
    previousSelection: String? = null,
): String? {
    if (schemas.isEmpty() || rowHeight <= 0f || !rowHeight.isFinite() ||
        viewport.width <= 0f || viewport.height <= 0f ||
        !pointer.x.isFinite() || !pointer.y.isFinite()) return null
    val previous = previousSelection?.takeIf { id -> schemas.any { it.schemaId == id } }
    // 尚未进入菜单时，原地松手仍只是关闭菜单。选中过后越界不能退回当前语言。
    if (!viewport.contains(pointer) && previous == null) return null
    if (pointer.x < viewport.left || pointer.x >= viewport.right) return previous
    val y = pointer.y.coerceIn(viewport.top, Math.nextDown(viewport.bottom))
    val index = ((y - viewport.top + scrollOffset.coerceAtLeast(0)) / rowHeight).toInt()
        .coerceIn(0, schemas.lastIndex)
    return schemas[index].schemaId
}

/** 统一语言键：点按切换英文、长按选语言；不接受方案定义的文字预览和滑动菜单。 */
@Composable
fun LanguageKeyButton(
    onClick: () -> Unit,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    text: String = "",
    icon: Painter? = null,
    textColor: Color = Color.Unspecified,
    iconColor: Color = textColor,
    layoutMode: ButtonLayout = ButtonLayout.STANDARD,
    fontSize: TextUnit = TextUnit.Unspecified,
    swipeFontSize: TextUnit = 9.sp,
    swipeText: String? = null,
    swipeDownText: String? = null,
    swipeUpKeyLabel: String? = null,
    swipeDownKeyLabel: String? = null,
    badgeText: String? = null,
    onSwipe: ((String) -> Unit)? = null,
    onSwipeDown: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    longPressItems: List<String>? = null,
    onLongPressSelect: ((String) -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    languageLabel: String? = null,
) {
    val actions = LocalKeyboardInputActions.current
    val context = LocalContext.current
    // Default arguments can leave these Unspecified; never feed that sentinel into
    // key-feedback color math, which expects a real sRGB value.
    val effectiveTextColor = if (textColor.isSpecified) textColor else MaterialTheme.colorScheme.onSurface
    val effectiveIconColor = if (iconColor.isSpecified) iconColor else effectiveTextColor
    val schemas by rememberUpdatedState(com.kingzcheung.xime.settings.InputModes.languageChoices(
        actions.schemas, actions.currentInputModeId,
        com.kingzcheung.xime.settings.InputModes.rememberedModes(context, actions.schemas),
        com.kingzcheung.xime.settings.InputModes.languageOrder(context),
        com.kingzcheung.xime.settings.InputModes.selectedProfiles(context), actions.fieldNativeModeId))
    val switchSchema by rememberUpdatedState(actions.onSwitchSchema)
    val hasMenu = schemas.isNotEmpty() && switchSchema != null
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val suppressCursorMove = LocalSuppressCursorMove.current
    var keyWindowBounds by remember { mutableStateOf(Rect.Zero) }
    var keyScreenPosition by remember { mutableStateOf(Offset.Zero) }
    var menuSchemas by remember { mutableStateOf<List<SchemaInfo>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var pointer by remember { mutableStateOf(Offset.Unspecified) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    // 松手后菜单仍开着（点选模式）。
    var menuSticky by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val panelBounds = LocalLanguageMenuPanel.current
    val fallbackBounds = Rect(0f, 0f, view.width.toFloat(), view.height.toFloat())
    val menuGeometry = languageMenuGeometry(
        panelBounds.takeIf { it.width > 0f && it.height > 0f } ?: fallbackBounds,
        keyWindowBounds, density.density, menuSchemas.size)
    val rowHeight = menuGeometry.rowHeightPx
    val edgeSize = with(density) { 20.dp.toPx() }
    val scrollStep = with(density) { 8.dp.toPx() }

    LaunchedEffect(menuOpen) { if (menuOpen) scroll.scrollTo((menuSchemas.indexOfFirst { it.schemaId == actions.currentInputModeId }.coerceAtLeast(0) * rowHeight).toInt()) }
    // 仅在手指进入边缘时滚动；新菜单不继承上次手势的位置。
    LaunchedEffect(menuOpen, pointer, viewport) {
        if (!menuOpen || selectedId == null || !pointer.x.isFinite() || !pointer.y.isFinite() ||
            pointer.x < viewport.left || pointer.x >= viewport.right) return@LaunchedEffect
        val delta = when {
            pointer.y < viewport.top + edgeSize -> -scrollStep
            pointer.y > viewport.bottom - edgeSize -> scrollStep
            else -> return@LaunchedEffect
        }
        while (menuOpen) {
            if (scroll.scrollBy(delta) == 0f) break
            selectedId = languageMenuSelection(menuSchemas, pointer, viewport, scroll.value, rowHeight, selectedId)
            delay(30L)
        }
    }

    val menuHoldSeconds by rememberUpdatedState(
        normalizeLanguageMenuHoldSeconds(LocalKeyboardInputPreferences.current.languageMenuHoldSeconds))
    fun closeMenu() {
        menuOpen = false
        menuSticky = false
        selectedId = null
        pointer = Offset.Unspecified
    }
    fun choose(id: String) {
        closeMenu()
        switchSchema?.invoke(id)
    }
    // 常驻菜单时键盘被收起，下次弹出不再残留上次的菜单。
    DisposableEffect(view, menuOpen) {
        if (!menuOpen) return@DisposableEffect onDispose { }
        val stop = watchWindowHidden(view) { closeMenu() }
        onDispose { stop() }
    }
    val menuPanel = panelBounds.takeIf { it.width > 0f && it.height > 0f } ?: fallbackBounds
    val glass = LocalKeyboardInputPreferences.current.frostedGlass
    val blurBehind = glass.enabled && glass.normalized().blurRadiusDp >= 1f && rememberLanguageMenuBlurAvailable()
    // 玻璃下菜单和面板一样透出后面：菜单区域先叠一层实时模糊（盖在按键上面），再画半透明主题色。
    // 必须先于下面的 Popup 加入窗口，同类子窗口按加入顺序叠放。
    if (menuOpen && blurBehind) {
        val blurRadiusPx = with(density) { glass.normalized().blurRadiusDp.dp.roundToPx() }
        val cornerPx = with(density) { CompactMenuStyle.CornerRadius.toPx() }
        val blur = remember(view) {
            com.kingzcheung.xime.service.ImeGlassBlurWindow(context, "language-menu",
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL) { view.rootView }
        }
        val bounds = menuGeometry.bounds
        val region = android.graphics.Rect(bounds.left.roundToInt(), bounds.top.roundToInt(),
            bounds.right.roundToInt(), bounds.bottom.roundToInt())
        DisposableEffect(blur) {
            blur.configure(true, blurRadiusPx.coerceAtLeast(1), cornerPx)
            blur.setRegion(region)
            blur.setVisible(true)
            onDispose { blur.dispose() }
        }
        SideEffect {
            blur.configure(true, blurRadiusPx.coerceAtLeast(1), cornerPx)
            blur.setRegion(region)
        }
    }
    Box(
        modifier.fillMaxWidth().fillMaxHeight()
                        .onGloballyPositioned {
                keyWindowBounds = it.boundsInWindow()
                keyScreenPosition = it.positionOnScreen()
            }
            .pointerInput(hasMenu) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.isConsumed) return@awaitEachGesture
                    var chosenId: String? = null
                    var keepOpen = false
                    var moved = false
                    // 按在语言键上就不让键盘的滑动移光标接手，菜单弹出前后都一样。
                    suppressCursorMove.value = true
                    val menuJob = if (hasMenu) scope.launch {
                        delay((menuHoldSeconds * 1000).roundToLong())
                        menuSchemas = schemas
                        // The current mode is shown through the card's selectedId fallback;
                        // keeping this null means releasing without moving leaves the menu
                        // open instead of committing a choice.
                        selectedId = null
                        viewport = Rect.Zero
                        pointer = Offset.Unspecified
                        menuSticky = false
                        menuOpen = true
                        suppressCursorMove.value = true
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    } else null
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            if (menuOpen) {
                                change.consume()
                                if (change.position != down.position) moved = true
                                pointer = keyScreenPosition + change.position
                                selectedId = languageMenuSelection(menuSchemas, pointer, viewport, scroll.value, rowHeight,
                                    if (moved) selectedId ?: actions.currentInputModeId else null)
                                if (!change.pressed) {
                                    chosenId = selectedId
                                    // 菜单弹出后还没滑进列表就松手：菜单留着，再点一项选择（用户 10-24 定）。
                                    keepOpen = chosenId == null
                                    break
                                }
                            } else {
                                // 语言键不认滑动（用户 10-19 定）：按住期间手指在键内怎么挪都继续算长按，
                                // 只有滑出键外才取消。原来超过 touchSlop 就取消，平板上手指一滚就弹不出列表。
                                val p = change.position
                                val inside = p.x >= 0f && p.y >= 0f && p.x <= size.width && p.y <= size.height
                                if (!inside) {
                                    // The language key owns cancellation: consume so the
                                    // inner swipeable button cannot turn it into a tap.
                                    change.consume()
                                    break
                                }
                                if (!change.pressed) break
                            }
                        }
                    } finally {
                        menuJob?.cancel()
                        suppressCursorMove.value = false
                        if (keepOpen) {
                            menuSticky = true
                            pointer = Offset.Unspecified
                        } else closeMenu()
                    }
                    chosenId?.let { switchSchema?.invoke(it) }
                }
            }
    ) {
        SwipeableKeyButton(
            text = languageLabel ?: "语言切换",
            icon = if (languageLabel == null) rememberVectorPainter(Icons.Default.Language) else null, onClick = onClick,
            backgroundColor = backgroundColor,
            textColor = if (icon != null) effectiveIconColor else effectiveTextColor,
            modifier = Modifier.fillMaxSize().testTag("language-key-control"), layoutMode = ButtonLayout.STANDARD,
            fontSize = fontSize, swipeFontSize = swipeFontSize,
            swipeText = null, swipeDownText = null,
            swipeUpKeyLabel = null, swipeDownKeyLabel = null,
            badgeText = null, onSwipe = null, onSwipeDown = null,
            onSwipeStateChange = null,
            onPress = onPress, onRelease = onRelease,
            longPressItems = null,
            onLongPressSelect = null,
            shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
            shadowShapeRadius = shadowShapeRadius,
        )
        if (menuOpen) {
            // 弹窗铺满整块面板：常驻时点菜单外任何地方只关菜单，不会顺手打出下面的键。
            val positionProvider = remember(menuPanel) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect, windowSize: IntSize,
                        layoutDirection: LayoutDirection, popupContentSize: IntSize,
                    ): IntOffset = IntOffset(
                        menuPanel.left.roundToInt()
                            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                        menuPanel.top.roundToInt()
                            .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
                    )
                }
            }
            Popup(
                popupPositionProvider = positionProvider,
                properties = PopupProperties(focusable = false, dismissOnBackPress = false,
                    dismissOnClickOutside = false, clippingEnabled = false),
            ) {
                // Popup owns a separate view; retain the keyboard density and font scale.
                androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides density) {
                    Box(Modifier
                        .size(with(density) { menuPanel.width.toDp() }, with(density) { menuPanel.height.toDp() })
                        .then(if (menuSticky) Modifier.testTag("language-menu-dismiss")
                            .pointerInput(Unit) { detectTapGestures { closeMenu() } } else Modifier)) {
                        LanguageMenuCard(
                            schemas = menuSchemas,
                            selectedId = selectedId ?: actions.currentInputModeId,
                            width = with(density) { menuGeometry.bounds.width.toDp() },
                            height = with(density) { menuGeometry.bounds.height.toDp() },
                            rowHeight = with(density) { rowHeight.toDp() },
                            glass = glass, blurBehind = blurBehind,
                            scroll = scroll,
                            onViewport = { viewport = it },
                            onChoose = { choose(it) },
                            modifier = Modifier.offset {
                                IntOffset((menuGeometry.bounds.left - menuPanel.left).roundToInt(),
                                    (menuGeometry.bounds.top - menuPanel.top).roundToInt())
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 键盘窗口不再可见时回调一次，返回取消函数。
 * 窗口可见性监听从 Android 14（API 34）才可用；
 * 更早的系统改为菜单开着时每 200ms 查一次窗口可见性。
 */
private fun watchWindowHidden(view: android.view.View, onHidden: () -> Unit): () -> Unit {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return WindowVisibilityListenerApi34.watch(view, onHidden)
    }
    val check = object : Runnable {
        override fun run() {
            if (view.windowVisibility != android.view.View.VISIBLE) onHidden()
            else view.postDelayed(this, 200L)
        }
    }
    view.postDelayed(check, 200L)
    return { view.removeCallbacks(check) }
}

@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private object WindowVisibilityListenerApi34 {
    fun watch(view: android.view.View, onHidden: () -> Unit): () -> Unit {
        val listener = android.view.ViewTreeObserver.OnWindowVisibilityChangeListener { visibility ->
            if (visibility != android.view.View.VISIBLE) onHidden()
        }
        view.viewTreeObserver.addOnWindowVisibilityChangeListener(listener)
        return { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowVisibilityChangeListener(listener) }
    }
}

/** 实时窗口模糊可用（Android 12 跨窗口模糊或 One UI 模糊）；省电模式等关掉时为否，菜单改用不透明底。 */
@Composable
private fun rememberLanguageMenuBlurAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && runCatching {
            context.getSystemService(android.view.WindowManager::class.java)?.isCrossWindowBlurEnabled == true ||
                com.kingzcheung.xime.ui.theme.SamsungWindowBlur.isAvailable
        }.getOrDefault(false)
    }
}

/** 语言菜单的底色和文字色。底色取主题键帽色（字母键与功能键之间）；文字不够清楚时向黑/白调，不再退回 Material 白底。 */
internal data class LanguageMenuColors(
    val surface: Color, val text: Color, val secondary: Color, val accent: Color,
    val selectedSurface: Color,
)

/**
 * 不开玻璃或没有实时模糊时菜单不透明，盖住下面按键的字。
 * 玻璃且有模糊时，菜单按“玻璃面板上的一颗键帽”取透明度，和按键用同一条可读性规则：
 * 后面是纯白或纯黑页面，文字对比都不低于 4.5:1，不够就把透明度往不透明调。
 */
internal fun languageMenuColors(
    palette: KeyboardPalette?, fallbackSurface: Color, fallbackText: Color, fallbackAccent: Color,
    glass: FrostedGlassConfig, blurBehind: Boolean,
): LanguageMenuColors {
    val opaque = palette?.let { lerp(it.function.copy(alpha = 1f), it.key.copy(alpha = 1f), 0.5f) }
        ?: fallbackSurface.copy(alpha = 1f)
    val text = resolveFrostedTextSurface(opaque, palette?.text ?: fallbackText).text
    val surface = if (glass.enabled && blurBehind) {
        // 目标比 4.5 稍高：sRGB 颜色按 8 位存，两次叠加的取整会把刚好达标的结果压到线下。
        val legible = glass.legibleOver(opaque, listOf(opaque to text), GLASS_LABEL_CONTRAST + 0.1f)
        frostedKeyColor(opaque, text, legible).compositeOver(opaque.copy(alpha = legible.normalized().backgroundOpacity))
    } else opaque
    val accent = palette?.accent ?: fallbackAccent
    val pages = listOf(Color.Black, Color.White)
    val target = GLASS_LABEL_CONTRAST + 0.1f
    // A selection tint must not erase the main label's contrast over either possible host.
    val selected = readableMenuBlend(lerp(surface, accent.copy(alpha = 1f), 0.22f), surface) { fill ->
        pages.all { textContrast(text, fill.compositeOver(it)) >= target }
    }
    val seenSurfaces = listOf(surface, selected).flatMap { fill -> pages.map { fill.compositeOver(it) } }
    // Opaque does not by itself mean readable: constrain the muted caption against the
    // actual translucent surfaces, keeping as much of its secondary tone as contrast allows.
    val secondary = readableMenuBlend(lerp(text, opaque, 0.28f), text) { foreground ->
        seenSurfaces.all { textContrast(foreground, it) >= target }
    }
    return LanguageMenuColors(surface, text, secondary, accent, selected)
}

private fun readableMenuBlend(candidate: Color, fallback: Color, readable: (Color) -> Boolean): Color {
    if (readable(candidate)) return candidate
    if (!readable(fallback)) return fallback
    var low = 0f
    var high = 1f
    repeat(12) {
        val middle = (low + high) / 2f
        if (readable(lerp(candidate, fallback, middle))) high = middle else low = middle
    }
    return lerp(candidate, fallback, high)
}

@Composable
internal fun LanguageMenuCard(
    schemas: List<SchemaInfo>,
    selectedId: String?,
    width: Dp,
    height: Dp,
    rowHeight: Dp,
    glass: FrostedGlassConfig,
    blurBehind: Boolean,
    scroll: androidx.compose.foundation.ScrollState,
    onViewport: (Rect) -> Unit,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = languageMenuColors(LocalKeyboardPalette.current, MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.primary, glass, blurBehind)
    val menuText = colors.text
    val accent = colors.accent
    // 选中行：菜单底向强调色偏一点，再加强调色细边，玻璃开关都一样。
    val selectedBackground = colors.selectedSurface
    Surface(
        shape = CompactMenuStyle.Shape, tonalElevation = 0.dp, shadowElevation = CompactMenuStyle.Elevation,
        color = colors.surface,
        contentColor = menuText,
        border = if (glass.enabled) BorderStroke(1.dp, menuText.copy(alpha = 0.14f)) else null,
        modifier = modifier.width(width).height(height)
            // 只淡入不缩放：按住滑动选择按行的屏幕位置命中，缩放会让行位置在动画中偏移。
            .cyPopupEnter(scale = false)
            .testTag("language-menu"),
    ) {
        Column(Modifier
            .then(if (glass.enabled) Modifier else Modifier.visualMaterial(
                VisualStyles.current, CompactMenuStyle.CornerRadius, MaterialLevel.FLOATING)).padding(6.dp)) {
            Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                Text("语言", fontSize = CompactMenuStyle.FontSize, lineHeight = CompactMenuStyle.LineHeight, textAlign = TextAlign.Center,
                    color = if (glass.enabled) colors.secondary else menuText)
            }
            Column(
                Modifier.fillMaxWidth().height((height - 40.dp).coerceAtLeast(1.dp))
                    .onGloballyPositioned {
                        val topLeft = it.positionOnScreen()
                        onViewport(Rect(topLeft, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())))
                    }
                    .verticalScroll(scroll)
            ) {
                schemas.forEach { schema ->
                    val modeName = schema.profile.summary
                    val isSelected = schema.schemaId == selectedId
                    Column(
                        Modifier.fillMaxWidth().height(rowHeight)
                            .testTag("language-schema:${schema.schemaId}")
                            .semantics {
                                selected = isSelected
                                contentDescription = "选择${schema.name}"
                            }
                            .clip(CompactMenuStyle.Shape)
                            .background(if (isSelected) selectedBackground else Color.Transparent)
                            .then(if (isSelected) Modifier.border(
                                1.dp, accent.copy(alpha = 0.32f), CompactMenuStyle.Shape) else Modifier)
                            // 常驻菜单点一项即选择；按住滑动时手势归语言键，这里收不到。
                            .clickable(interactionSource = null, indication = null) { onChoose(schema.schemaId) }
                            .padding(horizontal = CompactMenuStyle.HorizontalPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (isSelected) Icon(Icons.Default.Check, contentDescription = null,
                                tint = menuText,
                                modifier = Modifier.align(Alignment.CenterStart).size(CompactMenuStyle.IconSize))
                            Text(schema.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 20.dp),
                                fontSize = CompactMenuStyle.FontSize, lineHeight = CompactMenuStyle.LineHeight, textAlign = TextAlign.Center,
                                color = menuText)
                        }
                        Text(modeName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected && !glass.enabled) menuText else colors.secondary)
                    }
                }
            }
        }
    }
}
