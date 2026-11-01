package com.kingzcheung.xime.ui.keyboard

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kingzcheung.xime.gloss.CandidateGlossary
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.ToolbarAction
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.service.focusedT9AssociationIndex
import com.kingzcheung.xime.service.highlightedCandidateIndex
import com.kingzcheung.xime.service.isSingleCharacter
import com.kingzcheung.xime.service.shownPreedit
import com.kingzcheung.xime.service.usesT9CandidateNavigation
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.util.InputLatencyTrace
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel

/**
 * 候选栏宿主。候选/预编辑状态**只在这个组合作用域里读**：
 * 候选或编码一变只有这个子树（候选栏、栏上的工具按钮）重组，
 * 字母键盘、数字/符号页、手写画布和外层 [KeyboardView] 的作用域不跟着失效。
 *
 * 这里只把 [KeyboardView] 里原来内联在候选栏 lambda 中的读取搬进一个可独立重组的
 * 私有组合作用域，读的东西、判断顺序、渲染参数与之前逐项相同，没有任何行为变化。
 * 组合外用的快照（[CandidateState]）由参数原样带入，点选/长按时的过期校验
 * （[KeyboardCallbacks.isCandidateSnapshotCurrent]）仍用这一帧渲染的那份状态。
 */
@Composable
internal fun CandidateBarHost(
    candidateState: State<CandidateState>,
    /** 候选栏所在的页面（主键盘 / 面板 / 覆盖层），决定栏内显示哪些按钮。 */
    page: KeyboardPage,
    uiState: KeyboardUiState,
    viewModel: KeyboardViewModel,
    callbacks: KeyboardCallbacks,
    candidatePageExpanded: Boolean,
    candidateStripExtended: Boolean,
    singleCharFilter: Boolean,
    /** 预编辑编辑器没开时才画候选栏上的编码预览。 */
    showPreeditPreview: Boolean,
    hardwareHighlightIndex: Int?,
    hardwareKeyboardConnected: Boolean,
    toolbarActions: List<ToolbarAction>,
    onToolbarFeedback: () -> Unit,
    /** 候选栏随键盘半速放大：读 [State] 而不是读值，缩放变化也不牵动外层。 */
    barScale: State<Float>,
    voiceAmplitude: State<Float>,
    voiceSpectrum: State<FloatArray>,
    handwritingCandidates: List<String>,
    handwritingComments: List<String>,
    showHandwritingCandidates: Boolean,
    isHandwritingPage: Boolean,
    /** 手写模板库的上下文（长按忘掉笔迹用）。 */
    handwritingContext: Context,
    textColor: Color,
    dividerColor: Color,
    accentColor: Color,
    selectedTextColor: Color,
    keyBackgroundColor: Color,
    /** 长按候选待确认：词、说明、确认后的动作，交回宿主弹键盘内的确认卡。 */
    onDeletePending: (word: String, message: String?, confirm: () -> Unit) -> Unit,
    onVisibleCandidatesChanged: (List<String>) -> Unit,
    onVisibleAssociationsChanged: (List<String>) -> Unit,
    onClosePreeditEditor: () -> Unit,
    onOpenPreeditEditor: () -> Unit,
    onSelectHandwriting: (Int) -> Unit,
    onClearHandwriting: () -> Unit,
    inlineSuggestions: List<*>,
) {
    // 收起的候选栏滑到后面接上展开列表时，数据源同展开态（不打开展开页）。
    val expandedDataMode = (candidatePageExpanded || candidateStripExtended) &&
        candidateState.value.expandedCandidatesLoaded

    val cs = candidateState.value
    androidx.compose.runtime.SideEffect { com.kingzcheung.xime.service.KeyLatencyProbe.composed() }
    // 展开态候选栏数据源：全量列表（筛选态取单字子列表，仍是同源关系）。
    // 每项携带全量原索引：筛选态过滤掉词组后位置索引 ≠ 全局索引，
    // 点选/长按必须经 first 换算回全局索引，与展开页 globalIndices 同口径
    val railExpanded = if (expandedDataMode) {
        if (singleCharFilter) {
            cs.expandedCandidates.withIndex()
                .mapNotNull { (i, c) -> if (c.text.isSingleCharacter()) i to c else null }
        } else {
            cs.expandedCandidates.withIndex().map { it.index to it.value }
        }
    } else {
        emptyList()
    }
    // 释义模式只换显示用注释；点选仍按索引回到原候选数据（九键靠其中的拼音定位）。
    val glossRevision = CandidateGlossary.revision
    val glossChinese = !uiState.isAsciiMode && uiState.inputProfile.language == InputLanguage.CHINESE
    // 按住 Shift（屏幕键、物理键或九键长按“下一项”）时有英文的候选中英对调，此时点选上屏英文。
    val shiftModeNow by viewModel.shiftMode.collectAsStateWithLifecycle()
    val glossSwap = glossChinese &&
        CandidateGlossary.swapHeld(shiftModeNow == com.kingzcheung.xime.viewmodel.ShiftMode.HELD)
    val candidateBarState = remember(
        cs.candidates, cs.candidateComments, cs.inputText, cs.preeditText, cs.isComposing,
        cs.associationCandidates, cs.pendingEnglishText, cs.isShowingRecentClipboard, cs.hasNextPage,
        uiState.isCalculatorMode, handwritingCandidates, handwritingComments, showHandwritingCandidates,
        railExpanded, expandedDataMode, isHandwritingPage, cs.smsVerificationCode, cs.clipboardImage,
        cs.candidateFocus, cs.engineRevision, cs.candidateActions, uiState.inputProfile,
        hardwareHighlightIndex, glossRevision, glossChinese, glossSwap,
    ) {
        if (showHandwritingCandidates) {
            CandidateBarState.AssociationOnly(
                candidates = handwritingCandidates,
                comments = handwritingComments,
                highlightIndex = 0,
            )
        } else if (isHandwritingPage) {
            CandidateBarState.Idle
        } else {
            val barTexts = if (expandedDataMode) railExpanded.map { it.second.text } else cs.candidates
            val barComments = if (expandedDataMode) railExpanded.map { it.second.comment } else cs.candidateComments
            val (shownTexts, shownComments) = when {
                glossSwap -> CandidateGlossary.swapped(barTexts, barComments)
                glossChinese -> barTexts to CandidateGlossary.annotate(barTexts, barComments)
                else -> barTexts to barComments
            }
            CandidateBarState.from(
                candidates = shownTexts,
                candidateComments = shownComments,
                inputText = cs.inputText,
                preeditText = cs.shownPreedit(uiState.inputProfile),
                isComposing = cs.isComposing,
                associationCandidates = if (cs.pendingEnglishText.isNotEmpty() && cs.englishReplaceSupported) {
                    listOf(cs.pendingEnglishText) + cs.associationCandidates
                } else {
                    cs.associationCandidates
                },
                isShowingRecentClipboard = cs.isShowingRecentClipboard,
                hasNextPage = cs.hasNextPage,
                isCalculatorActive = uiState.isCalculatorMode,
                smsVerificationCode = cs.smsVerificationCode,
                clipboardImage = cs.clipboardImage,
                highlightIndex = if (expandedDataMode || !cs.usesT9CandidateNavigation(uiState.inputProfile)) 0
                    else cs.highlightedCandidateIndex,
                hardwareHighlightIndex = hardwareHighlightIndex,
                associationHighlightIndex = cs.focusedT9AssociationIndex,
            )
        }
    }

    CompositionLocalProvider(
        LocalLearnedCandidates provides cs.learnedCandidates,
        LocalCorrectedCandidates provides cs.correctedCandidates) {
    ScaledCandidateBar(barScale.value) {
    CandidateBar(
        modifier = Modifier.clipboardPanelExpandGesture().drawWithContent {
            drawContent()
            // 画到才结束计时：这里只在绘制阶段读，订阅不到这个作用域。
            InputLatencyTrace.finish(candidateState.value.traceEventId, "draw-submitted")
            InputLatencyTrace.finishSimple()
        },
        state = if (page is KeyboardPage.Overlay || candidateBarState is CandidateBarState.ClipboardDisplay &&
            (uiState.voiceSticky || uiState.isHandwritingMode || viewModel.hasTemporaryHandwriting))
            CandidateBarState.Idle else candidateBarState,
        page = page,
        candidatePageExpanded = candidatePageExpanded,
        showNumberLabels = hardwareKeyboardConnected && !uiState.isAsciiMode && cs.pendingEnglishText.isEmpty() &&
            !showHandwritingCandidates && !expandedDataMode,
        isFloatingMode = uiState.isFloatingMode,
        isVoiceSticky = uiState.voiceSticky,
        voiceAmplitude = voiceAmplitude.value,
        voiceSpectrum = voiceSpectrum.value,
        voiceRecognitionState = uiState.voiceRecognitionState,
        voicePluginName = uiState.voicePluginName,
        toolbarActions = toolbarActions,
        visuals = CandidateBarVisuals(
            backgroundColor = Color.Transparent,
            textColor = textColor,
            dividerColor = dividerColor,
            accentColor = accentColor,
            selectedTextColor = selectedTextColor,
            isDarkTheme = uiState.isDarkTheme,
            preeditBackgroundColor = keyBackgroundColor,
        ),
        callbacks = CandidateBarCallbacks(
            onVisibleCandidatesChanged = { onVisibleCandidatesChanged(it) },
            onVisibleAssociationsChanged = { onVisibleAssociationsChanged(it) },
            onReorderToolbar = callbacks.onUpdateToolbarButtons,
            onCancelInput = {
                onClosePreeditEditor()
                onToolbarFeedback()
                viewModel.setCandidatePageExpanded(false)
                if (showHandwritingCandidates) {
                    onClearHandwriting()
                } else {
                    callbacks.onKeyPress("clear_composition", false)
                }
            },
            onDismissClipboardPreview = {
                onToolbarFeedback()
                callbacks.onDismissClipboardPreview?.invoke()
            },
            onOpenClipboard = {
                onToolbarFeedback()
                callbacks.onDismissClipboardPreview?.invoke()
                viewModel.showOverlay(OverlayRoute.Clipboard(0))
            },
            onClipboardImageSelect = callbacks.onClipboardImageSelect,
            onExpandClipboardImages = callbacks.onExpandClipboardImages,
            onCandidateSelect = select@{ index ->
                if (!showHandwritingCandidates && !callbacks.isCandidateSnapshotCurrent(cs)) return@select
                onClosePreeditEditor()
                if (showHandwritingCandidates && index in handwritingCandidates.indices) {
                    onSelectHandwriting(index)
                } else if (expandedDataMode) {
                    // 展开态候选栏数据源=全量列表（筛选态为其单字子列表）：
                    // 位置索引经 railExpanded 换算全局索引再走全局链路
                    val globalIndex = railExpanded.getOrNull(index)?.first
                    if (globalIndex != null) {
                        callbacks.onGlobalCandidateSelect?.invoke(globalIndex)
                    }
                } else {
                    callbacks.onCandidateSelect(index)
                }
            },
            onCandidateStripScrolled = {
                // 展开着滑候选栏：收起展开页，候选栏接着用展开列表滑下去，同一个候选不出现两次。
                if (candidatePageExpanded) {
                    viewModel.setCandidateStripExtended(true)
                    viewModel.setCandidatePageExpanded(false)
                }
            },
            onCandidateStripNearEnd = {
                // 只在引擎第一页、没有键盘焦点时接：展开列表从第一个候选排起，位置才对得上。
                if (!candidatePageExpanded && !showHandwritingCandidates && cs.hasNextPage &&
                    !cs.hasPrevPage && cs.candidateFocus == null) callbacks.onExtendCandidateStrip?.invoke()
            },
            onCandidateLongPress = { index ->
                // 手写候选：读手写列表（不是引擎列表），长按＝忘掉这个字从手写里学到的笔迹
                val handwritingWord = if (showHandwritingCandidates) handwritingCandidates.getOrNull(index) else null
                val railEntry = if (expandedDataMode && !showHandwritingCandidates) railExpanded.getOrNull(index) else null
                val word = railEntry?.second?.text
                    ?: cs.candidates.getOrNull(index)
                if (showHandwritingCandidates) {
                    if (!handwritingWord.isNullOrEmpty()) onDeletePending(handwritingWord,
                        "忘掉「$handwritingWord」从手写里学到的笔迹？内置的写法和训练样本照常能认。") {
                        com.kingzcheung.xime.handwriting.HandwritingTemplates.forget(handwritingContext, handwritingWord)
                    }
                } else if (!word.isNullOrEmpty()) {
                    onDeletePending(word, null) {
                        // 与之前同一道过期校验：不通过就什么都不做。
                        if (callbacks.isCandidateSnapshotCurrent(cs)) {
                            if (railEntry != null) {
                                callbacks.onGlobalCandidateDelete?.invoke(railEntry.first)
                            } else {
                                callbacks.onCandidateDelete?.invoke(index)
                            }
                        }
                    }
                }
            },
            onClearAssociation = {
                onToolbarFeedback()
                if (showHandwritingCandidates) {
                    onClearHandwriting()
                } else {
                    callbacks.onClearAssociation?.invoke()
                }
            },
            onLogoClick = {
                onToolbarFeedback()
                viewModel.showOverlay(OverlayRoute.Menu)
            },
            onBack = {
                onToolbarFeedback()
                if (page is KeyboardPage.Overlay) {
                    if ((page as KeyboardPage.Overlay).backStack.isEmpty()) viewModel.closeOverlay()
                    else viewModel.popOverlay()
                } else if (isHandwritingPage) {
                    callbacks.onHandwritingToggle?.invoke()
                } else if (showHandwritingCandidates) {
                    onClearHandwriting()
                } else if (page is KeyboardPage.Panel) {
                    viewModel.exitPanel()
                } else {
                    viewModel.setCandidatePageExpanded(false)
                }
            },
            onHideKeyboard = {
                onToolbarFeedback()
                callbacks.onHideKeyboard?.invoke()
                viewModel.resetKeyboard(uiState.isAsciiMode, uiState.currentSchemaId)
            },
            onShowMoreCandidates = {
                onToolbarFeedback()
                viewModel.setCandidatePageExpanded(true)
                // 拉取跨页全量候选（展开页数据源，含首次展开）
                callbacks.onRequestExpandedCandidates?.invoke()
            },
            onInputTextClick = {
                if (candidateState.value.inputText.isNotEmpty()) {
                    callbacks.onClipboardSelect?.invoke(candidateState.value.inputText)
                }
            },
            onAssociationSelect = { index ->
                if (showHandwritingCandidates && index in handwritingCandidates.indices) {
                    onSelectHandwriting(index)
                } else {
                    callbacks.onAssociationSelect?.invoke(index)
                }
            },
        ),
        inlineSuggestions = if (page is KeyboardPage.Overlay) emptyList<Any>() else inlineSuggestions,
        showPreeditPreview = showPreeditPreview,
        onEditPreedit = if (!uiState.isAsciiMode && !isHandwritingPage &&
            com.kingzcheung.xime.rime.PinyinEditBuffer.supports(uiState.currentSchemaId) &&
            callbacks.onOpenPreeditEditor != null) ({
            onOpenPreeditEditor()
        }) else null,
    )
    }
    }
}

/**
 * 预编辑编辑条：编码预览取的是候选状态里的 preeditText。
 * 读它的地方原来在 [KeyboardView] 的 Column 内容里（等于把整块键盘订阅到每次按键），
 * 现在收进这个只有编辑条自己的作用域。
 */
@Composable
internal fun PreeditEditorBarHost(
    candidateState: State<CandidateState>,
    frame: com.kingzcheung.xime.rime.PinyinEditSession,
    keyBackgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    onClose: () -> Unit,
    onCaret: (Int) -> Unit,
    onReplace: (String) -> Unit,
) {
    PreeditEditorBar(
        frame, keyBackgroundColor, textColor, accentColor,
        onClose = onClose,
        onCaret = onCaret,
        onReplace = onReplace,
        preedit = candidateState.value.preeditText,
    )
}
