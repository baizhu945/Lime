package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.KeyShapes
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.visualMaterial

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.service.ExpandedCandidatePager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 候选展开页条目：候选文本 + 拼音注释 + 跨页全局索引。
 */
data class CandidateEntry(
    val text: String,
    val comment: String = "",
    val globalIndex: Int = -1,
)

/**
 * 候选展开页数据。
 *
 * @param candidates 按引擎顺序排列的候选，实际换行由中间区域的测量宽度决定
 * @param keyBackgroundColor 左右两栏按键底色（键盘按键色）；[Color.Unspecified] 时
 *                          用 textColor 半透明兜底，保证单独预览时也不失形。
 */
data class CandidatePageState(
    val candidates: List<CandidateEntry> = emptyList(),
    val engineRevision: Long = 0L,
    val associationCandidates: List<String> = emptyList(),
    val backgroundColor: Color,
    val textColor: Color,
    val keyBackgroundColor: Color = Color.Unspecified,
    val bottomPaddingDp: Int = 0,
    /** "只看单字"过滤开启中（左栏底部切换按钮的选中态） */
    val singleCharFilter: Boolean = false,
    /** 左栏符号列表（九键/笔画复刻各自键盘左栏的 side_symbols）；空=通用快捷符号 */
    val railSymbols: List<String> = emptyList(),
    /** 左栏音节拼音候选；拼音输入期间即使暂时为空也不回退显示符号。 */
    val railPinyinOptions: List<String> = emptyList(),
    val usePinyinRail: Boolean = false,
    /** 拼音候选项选中索引（九键 SELECTION 态），-1 无选中 */
    val railSelectedPinyinIndex: Int = -1,
    /** 拼音选中胶囊强调色（对齐九键 CandidateItem）；Unspecified 时用 textColor 兜底 */
    val railAccentColor: Color = Color.Unspecified,
    val enterKeyText: String = "回车",
    val matchT9Geometry: Boolean = false,
    val qwertyGeometry: CandidateQwertyGeometry? = null,
    val floating: Boolean = false,
)

/**
 * 候选展开页回调。
 *
 * @param onCandidateSelect 页内点选（携带跨页全局索引，KeyboardView 直接路由引擎）
 * @param onToggleSingleCharFilter 左栏底部"候选/单字"切换
 * @param onCommitText 左栏快捷符号上屏
 * @param onDelete     右栏退格键
 * @param onEnter      右栏回车键
 */
data class CandidatePageCallbacks(
    val onCandidateSelect: (CandidateEntry) -> Unit,
    val onAssociationSelect: ((Int) -> Unit)? = null,
    val onToggleSingleCharFilter: (() -> Unit)? = null,
    /** 长按候选删除自造词 */
    val onCandidateLongPress: ((CandidateEntry) -> Unit)? = null,
    /** 左栏拼音候选点选（九键音节切换，index 对应 railPinyinOptions） */
    val onRailPinyinSelect: ((Int) -> Unit)? = null,
    val onCommitText: ((String) -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    val onClear: (() -> Unit)? = null,
    val onEnter: (() -> Unit)? = null,
    val onKeyPressDown: ((String) -> Unit)? = null,
    val onKeyRelease: ((String) -> Unit)? = null,
    val onClearComposition: (() -> Unit)? = null,
    val onUndoClear: (() -> Unit)? = null,
)

/** 左栏快捷符号（对齐主流输入法候选展开页的符号栏）。 */
private val QUICK_SYMBOLS = listOf("？", "！", "……", "~")

/** 性能打点开关 */
private const val debugPerfLogging = true

/**
 * 候选展开页主体（三栏）——渲染在真实候选栏正下方（候选栏的在位展开态，非 Overlay 页）：
 * ┌────────┬────────────────────────────┬───────┐
 * │ ？     │  词 词词 词 词词词（流式换行）│ │ 退格  │
 * │ ！     │  词 词 词 词（附拼音注释）  │ │ 上一页│
 * │ ……     │  （行分组惰性渲染只画可见行，│ │ 下一页│
 * │ ~      │    超出视口即上下滑动）     │ │ 回车  │
 * │ 候选/单字│                          │ │       │
 * └────────┴────────────────────────────┴───────┘
 * 左栏样式对齐数字键盘左栏：上部快捷符号键、底部"候选/单字"切换。
 * 数据源为跨页全量候选，不做本地分页；收起按钮在上方候选栏右侧；
 * 编码删空时由宿主自动收起本页；翻页键 = 视口滚动一屏（上下到头自动置灰），
 * [pageScrollEvents] 供硬件键盘 DPAD 上/下联动同样的滚动。
 */
@Composable
fun CandidatePage(
    state: CandidatePageState,
    callbacks: CandidatePageCallbacks,
    modifier: Modifier = Modifier,
    pageScrollEvents: Flow<Int>? = null,
    onHapticFeedback: (() -> Unit)? = null,
) {
    val qwerty = state.qwertyGeometry
    if (!state.matchT9Geometry && qwerty == null) {
        CandidatePageContent(state, callbacks, modifier, pageScrollEvents, onHapticFeedback)
        return
    }
    val spacing = com.kingzcheung.xime.settings.KeysConfigHelper.getKeyboardKeyConfig()
        .spacingFor(if (qwerty != null) "qwerty" else "t9")
    BoxWithConstraints(modifier.fillMaxSize().keyboardPanelBackground(state.backgroundColor)) {
        val geometry = if (qwerty?.split == true) {
            val prefs = LocalKeyboardInputPreferences.current
            val context = LocalContext.current
            val display = LocalView.current.display
            val configuration = LocalConfiguration.current
            val pixelsPerDp = LocalDensity.current.density
            val pitchDp = remember(context, display, configuration, pixelsPerDp) {
                keyboardPhysicalMetrics(context, display, pixelsPerDp)
                    ?.dpFromMillimeters(CALIBRATED_QWERTY_PITCH_MM)
            }
            val gutterDp = SplitKeyboardGeometry.gutterDp(state.floating)
            val savedGap = LocalSplitGapPreview.current?.value ?: if (
                configuration.screenWidthDp > configuration.screenHeightDp) prefs.splitGapLandscape else prefs.splitGapPortrait
            val gap = SplitKeyboardGeometry.resolvedGap(savedGap, maxWidth.value, gutterDp, pitchDp)
            qwerty.copy(widthFraction = SplitKeyboardGeometry.keyWidthFraction(maxWidth.value, gap, gutterDp))
        } else qwerty
        CompositionLocalProvider(LocalKeyboardExplicitWidth provides
            (LocalKeyboardExplicitWidth.current || geometry?.split == true)) {
        Column(Modifier.fillMaxSize()) {
        KeyboardKeySpacingScope(Modifier.weight(1f).fillMaxWidth(), columns = geometry?.columns ?: (5f * 3f / 3.4f),
            widthFraction = geometry?.widthFraction ?: 1f,
            policy = if (qwerty != null) KeyVisualPolicy.Qwerty else KeyVisualPolicy.T9,
            allowShrink = state.floating, applyGutter = true,
            growthSpacing = if (geometry != null) spacing ?: (null to null) else spacing,
            // 和身后的键盘同一网格：分体时键盘不加宽，这里也不加宽。
            widenKeys = LocalKeyboardInputPreferences.current.wideKeys && geometry?.split != true,
            // 候选展开页是内容网格，不是传统键盘键区。
            ) { body ->
            CompositionLocalProvider(LocalKeyVisualPadding provides PaddingValues(
                horizontal = spacing.first?.dp ?: 2.dp, vertical = spacing.second?.dp ?: 2.dp)) {
                CandidatePageContent(state.copy(bottomPaddingDp = 0, qwertyGeometry = geometry), callbacks,
                    body.padding(start = (geometry?.innerInsetDp ?: 0f).dp,
                        end = (geometry?.innerInsetDp ?: 0f).dp, bottom = 8.dp), pageScrollEvents, onHapticFeedback)
            }
        }
        Spacer(Modifier.height(state.bottomPaddingDp.dp))
        }
        }
    }
}

@Composable
private fun CandidatePageContent(
    state: CandidatePageState,
    callbacks: CandidatePageCallbacks,
    modifier: Modifier = Modifier,
    pageScrollEvents: Flow<Int>? = null,
    onHapticFeedback: (() -> Unit)? = null,
) {
    val configuration = LocalConfiguration.current
    // 展开页候选字号与候选栏一起随键盘放大。
    val candidateTextSize = (rememberCandidateTextSize() * LocalCandidateBarScale.current).roundToInt()
    val isLandscape =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val leftRailWidth = if (isLandscape) 48.dp else 40.dp
    val rightRailWidth = if (isLandscape) 40.dp else 46.dp
    val keyBg = if (state.keyBackgroundColor == Color.Unspecified)
        state.textColor.copy(alpha = 0.12f) else state.keyBackgroundColor
    val dividerColor = state.textColor.copy(alpha = 0.12f)
    val railSymbols = state.railSymbols.ifEmpty { QUICK_SYMBOLS }
    // 查询尚未完成或没有可选音节时，拼音输入也不能显示无关的标点。
    val isPinyinRail = state.usePinyinRail || state.railPinyinOptions.isNotEmpty()
    val railItems = if (isPinyinRail) state.railPinyinOptions else railSymbols
    // 中间候选区滚动；翻页 = 滚动一屏
    val listState = rememberLazyListState()
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val scrollScope = rememberCoroutineScope()
    fun scrollPage(direction: Int) {
        val viewport = viewportHeightPx
        if (viewport > 0) {
            scrollScope.launch { listState.animateScrollBy(direction.toFloat() * viewport) }
        }
    }
    // 候选内容变化（新输入/切过滤/删词）回到顶部（宽度变化只重排，不重置滚动）
    // More batches of this composition append in place; only a new query/filter resets scroll.
    LaunchedEffect(if (state.engineRevision != 0L) state.engineRevision else state.candidates, state.singleCharFilter) {
        listState.scrollToItem(0)
    }
    // 硬件键盘 DPAD 上/下的翻页联动
    LaunchedEffect(pageScrollEvents) {
        pageScrollEvents?.collect { direction -> scrollPage(direction) }
    }

    // 与候选栏同一规则：玻璃太薄时候选字垫反色光晕（侧栏键帽本身已够清楚，不生晕）。
    val glass = LocalKeyboardInputPreferences.current.frostedGlass
    val halo = remember(glass, state.backgroundColor, state.textColor) {
        if (glass.enabled) glassTextHalo(listOf(state.textColor), state.backgroundColor.copy(alpha = 1f),
            glass.normalized().backgroundOpacity) else null
    }
    Column(modifier.fillMaxSize().keyboardPanelBackground(state.backgroundColor).glassTextHalo(halo)) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val t9 = state.matchT9Geometry
            val qwerty = state.qwertyGeometry
            val matchKeyboard = t9 || qwerty != null
            val compactRail = matchKeyboard || isLandscape || maxHeight < 226.dp
            val leftWidth = qwerty?.let { maxWidth * (it.modeFraction * it.widthFraction) }
                ?: if (t9) maxWidth * (0.8f / 5f) else leftRailWidth
            val deleteWidth = qwerty?.deleteWidth(maxWidth.value)?.dp
            val enterWidth = qwerty?.let { maxWidth * (it.enterFraction * it.widthFraction) }
            val rightWidth = qwerty?.let {
                maxOf(maxWidth * (it.enterFraction * it.widthFraction), it.deleteWidth(maxWidth.value).dp + it.deleteEndInsetDp.dp)
            } ?: if (t9) maxWidth * (0.8f / 5f) else rightRailWidth
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (matchKeyboard) 0.dp else 8.dp)
            ) {
                // ── 左栏：九键为音节拼音候选（输入/选择态）或 side_symbols（空闲态，
                // 连体键——首尾圆角、中间直角，对齐数字键盘左栏）+ 候选/单字切换（下）。
                // 条目 ≤4 均分填满；>4 最多显示 4 条、LazyColumn 滚动（对齐九键左栏）──
                if (matchKeyboard) {
                    SizedExpandedLeftRail(state, callbacks, railItems, keyBg,
                        Modifier.width(leftWidth).fillMaxHeight().testTag("candidate-left-rail"))
                } else {
                Column(
                    modifier = Modifier.fillMaxHeight()
                        .width(leftRailWidth)
                        .testTag("candidate-left-rail")
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    var railListHeightPx by remember { mutableIntStateOf(0) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(3f)
                            .testTag("expanded-pinyin-options")
                            .onSizeChanged { railListHeightPx = it.height }
                    ) {
                        if (railItems.size <= 4) {
                            railItems.forEachIndexed { index, item ->
                                CandidateRailSymbolKey(
                                    text = item,
                                    onClick = {
                                        if (isPinyinRail) callbacks.onRailPinyinSelect?.invoke(index)
                                        else callbacks.onCommitText?.invoke(item)
                                    },
                                    keyBg = keyBg,
                                    textColor = state.textColor,
                                    modifier = Modifier.weight(1f),
                                    isFirst = index == 0,
                                    isLast = index == railItems.lastIndex,
                                    isSelected = isPinyinRail && index == state.railSelectedPinyinIndex,
                                    accentColor = state.railAccentColor,
                                    isPinyin = isPinyinRail,
                                )
                            }
                        } else {
                            // 每条高 = 列表区高/4（视口恰好显示 4 条），超出滚动查看
                            val itemHeightDp = with(LocalDensity.current) {
                                (railListHeightPx / 4).coerceAtLeast(1).toDp()
                            }
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(railItems) { index, item ->
                                    CandidateRailSymbolKey(
                                        text = item,
                                        onClick = {
                                            if (isPinyinRail) callbacks.onRailPinyinSelect?.invoke(index)
                                            else callbacks.onCommitText?.invoke(item)
                                        },
                                        keyBg = keyBg,
                                        textColor = state.textColor,
                                        modifier = Modifier.height(itemHeightDp),
                                        isFirst = index == 0,
                                        isLast = index == railItems.lastIndex,
                                        isSelected = isPinyinRail && index == state.railSelectedPinyinIndex,
                                        accentColor = state.railAccentColor,
                                        isPinyin = isPinyinRail,
                                    )
                                }
                            }
                        }
                    }
                    RailKey(
                        onClick = { callbacks.onToggleSingleCharFilter?.invoke() },
                        keyBg = if (state.singleCharFilter) state.textColor.copy(alpha = 0.28f) else keyBg,
                        textColor = state.textColor,
                        opacityScale = if (state.singleCharFilter) 0.28f else 1f,
                        modifier = Modifier.weight(1f).testTag("expanded-filter-key")
                    ) {
                        // 写的是点了以后去哪（用户 10-23）：平时「单字」，筛选中（高亮底色）「候选」回到全部
                        Text(
                            text = if (state.singleCharFilter) "候选" else "单字",
                            color = state.textColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(dividerColor)
                )
                Spacer(modifier = Modifier.width(8.dp))

                }
                // ── 中间：候选行分组列表（LazyColumn 只渲染可见行），联想词在末尾
                // 随内容一并滚动 ──
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val showComments = candidateCommentsVisible(maxWidth.value, LocalDensity.current.fontScale,
                        candidateTextSize.toFloat(), rememberCandidateCommentsAllowed())
                    val rowWidthUnits = with(LocalDensity.current) {
                        ExpandedCandidatePager.rowWidthUnits(maxWidth.toPx(), density * fontScale * candidateTextSize / 18f)
                    }
                    val candidateRows = remember(state.candidates, rowWidthUnits, showComments) {
                        // Only layout measurements omit annotations; selection keeps the original entry.
                        val all = state.candidates.map { RimeCandidate(it.text, if (showComments) it.comment else "") }
                        ExpandedCandidatePager.flowRows(all.indices.toList(), all, rowWidthUnits)
                            .map { row -> row.map { state.candidates[it] } }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                            .onSizeChanged { viewportHeightPx = it.height }
                            .testTag("expanded-candidates"),
                        state = listState,
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        itemsIndexed(
                            candidateRows,
                            // Reflow changes row membership; do not reuse a cached row by its first word alone.
                            key = { _, row -> row.joinToString(",") { it.globalIndex.toString() } },
                            contentType = { _, _ -> "candidateRow" }
                        ) { _, row ->
                            // 估算只负责惰性分组；最终按真实文字宽度分配空间，长短词不会等分挤压。
                            FlexRow(Modifier.fillMaxWidth(), horizontalSpacing = 6.dp, verticalSpacing = 0.dp) {
                                row.forEachIndexed { colIndex, entry ->
                                    if (colIndex > 0) FlexRowDivider(dividerColor)
                                    CandidatePageItem(
                                        entry = entry,
                                        textSizeSp = candidateTextSize,
                                        showComment = showComments,
                                        onClick = { callbacks.onCandidateSelect(entry) },
                                        onLongClick = { callbacks.onCandidateLongPress?.invoke(entry) },
                                        textColor = state.textColor,
                                        modifier = Modifier.testTag("expanded-candidate:${entry.globalIndex}")
                                    )
                                }
                            }
                        }

                        if (state.associationCandidates.isNotEmpty()) {
                            item(key = "assoc") {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(dividerColor)
                                    )
                                    FlexRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalSpacing = 6.dp,
                                        verticalSpacing = 6.dp
                                    ) {
                                        state.associationCandidates.forEachIndexed { index, candidate ->
                                            if (index > 0) FlexRowDivider(dividerColor)
                                            CandidatePageItem(
                                                entry = CandidateEntry(text = candidate),
                                                textSizeSp = candidateTextSize,
                                                onClick = { callbacks.onAssociationSelect?.invoke(index) },
                                                textColor = state.textColor
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                }

                Spacer(modifier = Modifier.width(if (matchKeyboard) 0.dp else 8.dp))

                // ── 右栏：退格 / 上一页 / 下一页 / 回车 ──
                // Typing layouts reuse their four row cells and visual spacing, including tall keyboards.
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(rightWidth)
                        .testTag("candidate-right-rail")
                        .padding(vertical = if (matchKeyboard) 0.dp else 6.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = if (matchKeyboard) Arrangement.spacedBy(0.dp) else if (compactRail) Arrangement.spacedBy(4.dp)
                        else Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                ) {
                    val railKeyModifier = if (compactRail) Modifier.weight(1f) else Modifier.size(46.dp)
                    CompositionLocalProvider(
                        LocalKeyCornerRadius provides if (matchKeyboard) LocalKeyCornerRadius.current else KeyShapes.current.radius(14.dp),
                        LocalKeyVisualPadding provides if (matchKeyboard) LocalKeyVisualPadding.current else PaddingValues(0.dp),
                        LocalKeyboardKeyVisualMetrics provides if (matchKeyboard) LocalKeyboardKeyVisualMetrics.current else KeyVisualMetrics.Unspecified,
                        LocalKeyboardKeyContentScale provides if (matchKeyboard) LocalKeyboardKeyContentScale.current else 1f,
                    ) {
                        SwipeableIconKeyButton(
                            icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                            onClick = { callbacks.onDelete?.invoke() },
                            onLongClick = callbacks.onDelete,
                            onPress = { callbacks.onKeyPressDown?.invoke("delete") },
                            onRelease = { callbacks.onKeyRelease?.invoke("delete") },
                            onSwipe = callbacks.onClearComposition,
                            onSwipeUp = callbacks.onClear,
                            onSwipeDown = callbacks.onUndoClear,
                            onSwipeLeft = callbacks.onClearComposition,
                            followSymbolSwipeDirection = true,
                            upFeedback = KeyFlickFeedback.DELETE,
                            swipeUpLabel = "清空",
                            downFeedback = KeyFlickFeedback.UNDO,
                            swipeDownLabel = "撤回",
                            backgroundColor = LocalFunctionKeyColors.current?.background ?: keyBg,
                            iconColor = LocalFunctionKeyColors.current?.foreground ?: state.textColor,
                            modifier = railKeyModifier.then(if (deleteWidth != null) Modifier.width(deleteWidth)
                                .offset(x = -(qwerty?.deleteEndInsetDp ?: 0f).dp) else Modifier)
                                .semantics { contentDescription = "退格" }
                                .testTag("expanded-delete-key"),
                            shadowEnabled = false,
                            visualPadding = if (matchKeyboard) null else PaddingValues(0.dp),
                        )
                        CandidatePagingButton(
                            onClick = { onHapticFeedback?.invoke(); scrollPage(-1) },
                            enabled = listState.canScrollBackward,
                            previous = true, keyBg = keyBg, foreground = state.textColor, matchKeyboard = matchKeyboard,
                            modifier = railKeyModifier.testTag("expanded-page-previous"),
                        )
                        CandidatePagingButton(
                            onClick = { onHapticFeedback?.invoke(); scrollPage(1) },
                            enabled = listState.canScrollForward,
                            previous = false, keyBg = keyBg, foreground = state.textColor, matchKeyboard = matchKeyboard,
                            modifier = railKeyModifier.testTag("expanded-page-next"),
                        )
                        ActionKeyButton(
                            text = state.enterKeyText,
                            onClick = { callbacks.onEnter?.invoke() },
                            backgroundColor = keyBg,
                            textColor = state.textColor,
                            modifier = railKeyModifier.then(if (enterWidth != null) Modifier.width(enterWidth) else Modifier)
                                .testTag("expanded-enter-key"),
                            onPress = { callbacks.onKeyPressDown?.invoke("enter") },
                            onRelease = { callbacks.onKeyRelease?.invoke("enter") },
                            shadowEnabled = false,
                        )
                    }
                }
            }

        }
        Spacer(Modifier.height(state.bottomPaddingDp.dp))
    }
}

/**
 * 候选条目：候选词与拼音注释拼进同一文本（注释用次级色 + 注释字体），
 * 与候选栏共用用户字号；比一整行还长的候选折成多行，全文都能看到（用户 10-23）。
 * 主候选与联想都按真实文字宽度流式排布，余量均分给条目。
 */
@Composable
private fun CandidatePageItem(
    entry: CandidateEntry,
    onClick: () -> Unit,
    textColor: Color,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    showComment: Boolean = false,
    textSizeSp: Int = 18,
) {
    val displayComment = if (showComment) entry.comment.replace("~", "") else ""
    val annotated = remember(entry.text, displayComment, textColor, textSizeSp) {
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = textColor,
                    fontSize = textSizeSp.sp,
                    fontFamily = AppFonts.candidateFontFamily
                )
            ) {
                append(entry.text)
            }
            if (displayComment.isNotEmpty()) {
                withStyle(
                    SpanStyle(
                        color = textColor.copy(alpha = 0.5f),
                        fontSize = (textSizeSp * 11f / 18f).sp,
                        fontFamily = AppFonts.commentFontFamily
                    )
                ) {
                    append(" ")
                    append(displayComment)
                }
            }
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isPressed) textColor.copy(alpha = 0.12f) else Color.Transparent)
            .tolerantClick(
                showRipple = false,
                interactionSource = interactionSource,
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = annotated,
            style = TextStyle(fontWeight = FontWeight.Normal, lineHeight = 25.sp),
        )
    }
}

/**
 * 简化 flexbox 行布局：条目先按内容宽度贪心分行（放不下自动换行），再把每行
 * 剩余宽度均分给该行所有条目拉宽，保证每行两端饱满、行尾不留空白。
 * content 中的 [FlexRowDivider] 子项被摆放在其前条目与下一条目的间隙正中，
 * 行尾条目后的分隔线不绘制。
 */
@Composable
private fun FlexRow(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 0.dp,
    verticalSpacing: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        when {
            measurables.isEmpty() -> layout(0, 0) {}
            constraints.maxWidth == Constraints.Infinity -> {
                // 无界宽度兜底：条目按自然尺寸线性排列，不做分行拉伸，跳过分隔线
                val items = measurables.filter { it.layoutId != FLEX_ROW_DIVIDER_ID }
                val placeables = items.map { it.measure(constraints) }
                layout(placeables.sumOf { it.width }, placeables.maxOf { it.height }) {
                    var x = 0
                    placeables.forEach { p ->
                        p.placeRelative(x, 0)
                        x += p.width
                    }
                }
            }
            else -> {
                val hSpace = horizontalSpacing.roundToPx()
                val vSpace = verticalSpacing.roundToPx()
                val maxWidth = constraints.maxWidth
                val perfT0 = android.os.SystemClock.elapsedRealtime()

                // 解析子项：条目 + 条目间分隔线（分隔线归属其前条目）
                val itemMeasurables = mutableListOf<Measurable>()
                val dividerOf = HashMap<Int, Measurable>()
                measurables.forEach { m ->
                    if (m.layoutId == FLEX_ROW_DIVIDER_ID) {
                        if (itemMeasurables.isNotEmpty()) dividerOf[itemMeasurables.lastIndex] = m
                    } else {
                        itemMeasurables.add(m)
                    }
                }

                // 第一遍：条目按完整单行宽度贪心分行（同一 Measurable 只允许 measure
                // 一次，探尺寸必须走 intrinsic；须用 maxIntrinsicWidth —— min 对中文
                // 返回约一字宽，会导致分行过多、条目被压出省略号）
                val naturalWidths = itemMeasurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
                val rows = mutableListOf<MutableList<Int>>()
                var current = mutableListOf<Int>()
                var currentWidth = 0
                naturalWidths.forEachIndexed { index, w ->
                    val nextWidth = if (current.isEmpty()) w else currentWidth + hSpace + w
                    if (current.isNotEmpty() && nextWidth > maxWidth) {
                        rows.add(current)
                        current = mutableListOf()
                        currentWidth = 0
                    }
                    current.add(index)
                    currentWidth = if (current.size == 1) w else currentWidth + hSpace + w
                }
                if (current.isNotEmpty()) rows.add(current)

                // 第二遍：按行测量条目（行内剩余宽度均分拉宽，余数摊给前几项凑满整行）
                // 与行内条目间竖线（约 60% 行高，居中），每个子项仅 measure 一次
                val rowPlacements = mutableListOf<List<Triple<Placeable, Int, Int>>>()
                val rowHeights = mutableListOf<Int>()
                rows.forEach { row ->
                    val naturalSum = row.sumOf { naturalWidths[it] } + hSpace * (row.size - 1)
                    val extra = (maxWidth - naturalSum).coerceAtLeast(0)
                    val per = extra / row.size
                    val remainder = extra % row.size
                    val measured = row.mapIndexed { i, itemIdx ->
                        val w = (naturalWidths[itemIdx] + per + if (i < remainder) 1 else 0)
                            .coerceAtMost(maxWidth)
                        itemIdx to itemMeasurables[itemIdx].measure(
                            Constraints(
                                minWidth = w,
                                maxWidth = w,
                                minHeight = 0,
                                maxHeight = constraints.maxHeight
                            )
                        )
                    }
                    val rowHeight = measured.maxOf { it.second.height }
                    val entries = mutableListOf<Triple<Placeable, Int, Int>>()
                    var x = 0
                    measured.forEachIndexed { i, (itemIdx, p) ->
                        entries.add(Triple(p, x, (rowHeight - p.height) / 2))
                        x += p.width
                        // 行内非末尾条目之后摆放竖分隔线（行尾条目后的线不绘制）
                        if (i < measured.lastIndex) {
                            dividerOf[itemIdx]?.let { div ->
                                val divHeight = (rowHeight * 0.6f).toInt().coerceAtLeast(1)
                                val divPlaceable = div.measure(
                                    Constraints(minHeight = divHeight, maxHeight = divHeight)
                                )
                                entries.add(
                                    Triple(
                                        divPlaceable,
                                        x + (hSpace - divPlaceable.width) / 2,
                                        (rowHeight - divHeight) / 2
                                    )
                                )
                            }
                        }
                        x += hSpace
                    }
                    rowPlacements.add(entries)
                    rowHeights.add(rowHeight)
                }

                val totalHeight = rowHeights.sum() +
                    vSpace * (rowHeights.size - 1).coerceAtLeast(0)
                if (debugPerfLogging) {
                    val cost = android.os.SystemClock.elapsedRealtime() - perfT0
                    if (cost > 3) {
                        android.util.Log.d(
                            "CandidatePerf",
                            "FlexRow measure: items=${itemMeasurables.size} rows=${rows.size} cost=${cost}ms"
                        )
                    }
                }
                layout(maxWidth, totalHeight) {
                    var y = 0
                    rowPlacements.forEachIndexed { ri, entries ->
                        entries.forEach { (p, x, entryY) -> p.placeRelative(x, y + entryY) }
                        y += rowHeights[ri] + vSpace
                    }
                }
            }
        }
    }
}

/** FlexRow 条目间竖分隔线的 layoutId 标记。 */
private const val FLEX_ROW_DIVIDER_ID = "flex_row_divider"

/**
 * FlexRow 条目间竖分隔线：宽度 1dp，高度由 FlexRow 按行高的 60% 指定；
 * 位于行尾条目之后时不会被绘制。调用处约定插在每个条目之前（首个条目前不插）。
 */
@Composable
private fun FlexRowDivider(color: Color) {
    Box(
        modifier = Modifier
            .layoutId(FLEX_ROW_DIVIDER_ID)
            .width(1.dp)
            .background(color)
    )
}

/**
 * 左栏连体符号键（样式对齐数字键盘 NumberSymbolKey）：同一列内首条目上圆角、
 * 末条目下圆角、中间直角——整列背景连成一体；按压背景加深（0.7 透明度）。
 * [isSelected] 时渲染选中胶囊（对齐九键 CandidateItem 的 accentColor 高亮），
 * [isPinyin] 用拼音字号（13sp，对齐九键左栏），否则符号字号 16sp。
 */
@Composable
private fun CandidateRailSymbolKey(
    text: String,
    onClick: () -> Unit,
    keyBg: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    isSelected: Boolean = false,
    accentColor: Color = Color.Unspecified,
    isPinyin: Boolean = false,
) {
    val cornerRadius = LocalKeyCornerRadius.current
    val shape = RoundedCornerShape(
        topStart = if (isFirst) cornerRadius else 0.dp,
        topEnd = if (isFirst) cornerRadius else 0.dp,
        bottomStart = if (isLast) cornerRadius else 0.dp,
        bottomEnd = if (isLast) cornerRadius else 0.dp
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pillColor = if (accentColor == Color.Unspecified) textColor else accentColor

    Box(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick { onClick(); true }
            }
            .clip(shape)
            .background(frostedKeyColor(
                keyBg, textColor, LocalKeyboardInputPreferences.current.frostedGlass,
                legacyStateColor = if (isPressed) keyBg.copy(alpha = 0.7f) else keyBg,
                pressed = isPressed,
            ))
            .tolerantClick(
                showRipple = false,
                interactionSource = interactionSource,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(pillColor.copy(alpha = 0.2f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text(
                    text = text,
                    color = pillColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    fontFamily = AppFonts.candidateFontFamily
                )
            }
        } else {
            Text(
                text = text,
                color = textColor,
                fontSize = if (isPinyin) 13.sp else 16.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                fontFamily = AppFonts.candidateFontFamily
            )
        }
    }
}

/** 右栏实体按键：圆角方块、按压加深（enabled=false 时淡化）。等分栏高由调用方传 weight。 */
@Composable
private fun RailKey(
    onClick: () -> Unit,
    keyBg: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    opacityScale: Float = 1f,
    cornerRadius: Dp = 14.dp,
    visualPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (enabled) onClick { onClick(); true } else disabled()
            }
            .tolerantClick(
                enabled = enabled, showRipple = false,
                interactionSource = interactionSource, onClick = onClick,
            )
            .padding(visualPadding)
            .clip(RoundedCornerShape(KeyShapes.current.radius(cornerRadius)))
            .background(frostedKeyColor(
                keyBg, textColor, LocalKeyboardInputPreferences.current.frostedGlass,
                legacyStateColor = when {
                    !enabled -> keyBg.copy(alpha = 0.4f)
                    isPressed -> keyBg.copy(alpha = 0.7f)
                    else -> keyBg
                },
                pressed = isPressed,
                opacityScale = opacityScale * if (enabled) 1f else 0.4f,
            ))
            .visualMaterial(VisualStyles.current, cornerRadius),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** 原始上下翻页方块，底色沿用当前主题。 */
@Composable
private fun CandidatePagingButton(
    onClick: () -> Unit, enabled: Boolean, previous: Boolean, keyBg: Color, foreground: Color,
    modifier: Modifier,
    matchKeyboard: Boolean = false,
) {
    if (matchKeyboard) {
        BoxWithConstraints(modifier.semantics(mergeDescendants = true) { if (!enabled) disabled() }) {
            val padding = scaledKeyVisualPadding()
            val capHeight = (maxHeight - padding.calculateTopPadding() - padding.calculateBottomPadding()).coerceAtLeast(0.dp)
            val radius = minOf(14.dp * keyContentScale(maxWidth.value, capHeight.value), minOf(maxWidth, capHeight) * .3f)
            val iconSize = keyIconSizeDp(maxWidth.value, capHeight.value, 20f).dp
            RailKey(onClick, keyBg, foreground, Modifier.fillMaxSize(), enabled,
                cornerRadius = radius, visualPadding = padding) {
                Icon(if (previous) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (previous) "上一页" else "下一页",
                    tint = foreground.copy(alpha = if (enabled) 1f else .3f),
                    modifier = Modifier.size(iconSize))
            }
        }
        return
    }
    RailKey(onClick, keyBg, foreground, modifier, enabled) {
        Icon(if (previous) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (previous) "上一页" else "下一页",
            tint = foreground.copy(alpha = if (enabled) 1f else .3f), modifier = Modifier.size(20.dp))
    }
}

/** Same three-row list and bottom key cell as the underlying typing layout. */
@Composable
private fun SizedExpandedLeftRail(state: CandidatePageState, callbacks: CandidatePageCallbacks,
    items: List<String>, keyBg: Color, modifier: Modifier) {
    Column(modifier) {
        BoxWithConstraints(Modifier.weight(3f).fillMaxWidth().padding(scaledKeyVisualPadding())
            .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
            .background(frostedKeyColor(keyBg, state.textColor, LocalKeyboardInputPreferences.current.frostedGlass))) {
            val metrics = t9CandidateMetrics(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale,
                LocalKeyboardInputPreferences.current.keyTextScale, keyContentScale(maxWidth.value, maxHeight.value / 4f))
            val sample = androidx.compose.ui.text.rememberTextMeasurer().measure("shuang",
                TextStyle(fontSize = metrics.fontSizeSp.sp, fontFamily = AppFonts.candidateFontFamily),
                maxLines = 1, softWrap = false)
            val textWidth = with(LocalDensity.current) { (maxWidth - 8.dp).toPx() }.coerceAtLeast(1f)
            val font = (metrics.fontSizeSp * minOf(1f, textWidth / sample.size.width.coerceAtLeast(1))).sp
            LazyColumn(Modifier.fillMaxSize().testTag("expanded-pinyin-options")) {
                itemsIndexed(items) { index, item ->
                    T9CandidateItem(text = item, onClick = {
                        if (state.railPinyinOptions.isNotEmpty()) callbacks.onRailPinyinSelect?.invoke(index)
                        else callbacks.onCommitText?.invoke(item)
                    }, onPress = { callbacks.onKeyPressDown?.invoke(item) }, textColor = state.textColor,
                        backgroundColor = keyBg, accentColor = state.railAccentColor,
                        fontSize = font, isSelected = index == state.railSelectedPinyinIndex,
                        modifier = Modifier.fillMaxWidth().height(metrics.rowHeightDp.dp))
                }
            }
        }
        KeyButton(text = if (state.singleCharFilter) "候选" else "单字",
            onClick = { callbacks.onToggleSingleCharFilter?.invoke() },
            backgroundColor = LocalFunctionKeyColors.current?.background ?: keyBg,
            textColor = LocalFunctionKeyColors.current?.foreground ?: state.textColor,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("expanded-filter-key"), shadowEnabled = false)
    }
}
