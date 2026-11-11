package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.keyboard.textMainType
import com.kingzcheung.xime.keyboard.afterEditorPreparation
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.PanelType
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.rime.t9CandidateNeedsNativeSelection
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.ui.keyboard.transition
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutAction
import com.kingzcheung.xime.ui.keyboard.KeyboardViewState
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.ui.keyboard.initialKeyboardLayoutState
import com.kingzcheung.xime.util.FileLogger

/**
 * 菜单栏中动态展示的一个方案开关（来自 schema 的 `switches`）。
 * [name] 非空为布尔开关，[options] 非空为多选一开关；[currentIndex] 为当前状态在 [states] 中的下标。
 * [abbrev] 为自定义缩写（可能每个状态一个），用于菜单栏标题展示。
 */
data class SchemaSwitchUiState(
    val name: String = "",
    val options: List<String> = emptyList(),
    val states: List<String> = emptyList(),
    val abbrev: List<String> = emptyList(),
    val currentIndex: Int = 0,
)

data class KeyboardUiState(
    val rejectedCommitText: String? = null,
    val isAsciiMode: Boolean = false,
    val schemaName: String = "",
    val currentSchemaId: String = "",
    val schemas: List<SchemaInfo> = emptyList(),
    val schemaSwitches: List<SchemaSwitchUiState> = emptyList(),
    val enterKeyText: String = "发送",
    val isDarkTheme: Boolean = false,
    val darkMode: Int = 2,
    val themeId: String = com.kingzcheung.xime.ui.theme.PureBlackTheme.ID,
    val handwritingExpanded: Boolean = false,
    val handwritingControlsWidthDp: Int = 0,
    val handwritingControlsOffsetX: Int = 0,
    val clipboardImagesExpanded: Boolean = false,
    val keyboardHeightDp: Int = 0,
    val keyboardBottomPaddingDp: Int = 0,
    val keyboardOpacity: Float = 1f,
    val isDeploying: Boolean = false,
    val deploymentMessage: String = "",
    val clipboardItems: List<ClipboardItem> = emptyList(),
    val quickSendItems: List<ClipboardItem> = emptyList(),
    val recentClipboardItems: List<ClipboardItem> = emptyList(),
    val isVoiceMode: Boolean = false,
    val voiceSticky: Boolean = false,
    val voiceBottomActive: Boolean = false,
    val voiceLeftActive: Boolean = false,
    val voiceRightActive: Boolean = false,
    val voicePluginName: String = "",
    val voiceNotice: String? = null,
    val voiceRecognitionState: RecognitionState = RecognitionState.IDLE,
    val voiceRecognizedText: String = "",
    val isSttEnabled: Boolean = true,
    val toolbarButtons: List<String> = ToolbarButton.DEFAULT_VISIBLE.map { it.id },
    val menuButtons: List<String> = ToolbarButton.DEFAULT_MENU.map { it.id },
    /** 已启用插件声明的工具栏按钮（候选池），渲染时与内置按钮合并。 */
    val toolbarPluginButtons: List<ToolbarButtonItem.Plugin> = emptyList(),
    val isCalculatorMode: Boolean = false,
    val inputSessionId: Long = 0L,
    val isAddressField: Boolean = false,
    val isFloatingMode: Boolean = false,
    val isHandwritingMode: Boolean = false,
    val floatingOffsetX: Int = 0,
    val floatingOffsetY: Int = 0,
    /** 悬浮卡片绕中心的旋转角（度，顺时针为正）。 */
    val floatingRotation: Float = 0f,
    val floatingMinOffsetY: Int = 0,
    val floatingScreenHeightDp: Int = 0,
    /** 调节中的悬浮宽度（dp）；0 = 本次没在调宽度。 */
    val resizePreviewWidthDp: Int = 0,
    /** 已保存的悬浮宽度（dp）；0 = 未设置，按高度推导。 */
    val floatingWidthDp: Int = 0,
    val fixedWidthDp: Int = 0,
    /** Width after window/layout aspect protection; independent of expanded tool panels. */
    val keyboardWidthDp: Int = 0,
    val fixedOffsetX: Int = 0,
    val t9ResetSignal: Long = 0L,
    val swipeCancelEpoch: Long = 0L,
    val t9RightCandidateSelectedCount: Long = 0L,
    val t9SelectedCandidatePinyin: String = "",
    val showQuickSendForm: Boolean = false,
    val ocrDraft: Boolean = false,
    val quickSendFormFocused: Boolean = false,
    val quickSendEditingItemId: Long? = null,
    val quickSendEditingItemText: String = "",
    val quickSendEditingItemCode: String = "",
    val toolPanelVisible: Boolean = false,
    val toolPanelInputFocused: Boolean = false,
    val clipboardSearchVisible: Boolean = false,
    /** Height of the clipboard results above the search bar: the board's pulled-up height minus keyboard and bar. */
    val clipboardSearchResultsHeight: Int = 0,
    /** Search bar on one row and results without their category row (short screens). */
    val clipboardSearchInline: Boolean = false,
    val toolPanelPluginId: String = "",
    val toolPanelTitle: String = "",
    val toolPanelPrefillText: String = "",
    val toolPanelItems: List<PluginResultItem> = emptyList(),
    val toolPanelLoading: Boolean = false,
    val toolPanelRequestEpoch: Long = 0,
    val toolPanelDisplay: String? = null,
    val toolPanelUiNodes: List<com.kingzcheung.xime.plugin.core.config.UiNode>? = null,
    val clipboardSyncEnabled: Boolean = false,
) {
    val inputProfile: com.kingzcheung.xime.settings.InputProfile get() {
        val entry = schemas.firstOrNull { it.schemaId == currentSchemaId }
        return com.kingzcheung.xime.settings.InputProfiles.current(currentSchemaId, isAsciiMode, entry?.language, entry?.scheme)
    }
}

class KeyboardViewModel(application: Application) : AndroidViewModel(application) {

    val clipboardManager = ClipboardManager.getInstance(application)

    private val shiftState = KeyboardShiftState()
    val shiftMode: StateFlow<ShiftMode> = shiftState.mode

    private val _keyboardState = MutableStateFlow<KeyboardLayoutState>(KeyboardLayoutState.Chinese)
    val keyboardState: StateFlow<KeyboardLayoutState> = _keyboardState.asStateFlow()

    /** 最近一次停留的主键盘布局（中文/英文全键盘、九键、笔画）；
     *  数字面板据此判断进入来源，决定「返回/符号」键的位置自适应 */
    private val _lastMainLayout = MutableStateFlow<KeyboardLayoutState>(KeyboardLayoutState.Chinese)
    val lastMainLayout: StateFlow<KeyboardLayoutState> = _lastMainLayout.asStateFlow()

    private val _page = MutableStateFlow<KeyboardPage>(KeyboardPage.Main(MainType.FULL))
    val page: StateFlow<KeyboardPage> = _page.asStateFlow()

    /** 候选展开页：候选栏的在位展开态（非 Overlay——顶部保持真实候选栏，实时跟随编码/删除）。 */
    private val _candidatePageExpanded = MutableStateFlow(false)
    val candidatePageExpanded: StateFlow<Boolean> = _candidatePageExpanded.asStateFlow()

    /** 打开/收起候选展开页。 */
    fun setCandidatePageExpanded(expanded: Boolean) {
        _candidatePageExpanded.value = expanded
        if (!expanded) {
            // 筛选开关只在展开页左栏：收起后候选栏若仍被过滤将无处可关，自动复位
            _singleCharFilter.value = false
        }
    }

    /** 收起的候选栏滑到后面时接着显示展开列表（不打开展开页）；换了编码由服务层复位。 */
    private val _candidateStripExtended = MutableStateFlow(false)
    val candidateStripExtended: StateFlow<Boolean> = _candidateStripExtended.asStateFlow()

    fun setCandidateStripExtended(extended: Boolean) {
        _candidateStripExtended.value = extended
    }

    // ── 展开页翻页联动 ──
    // 硬件键盘 DPAD 上/下经此事件流驱动展开页滚动一屏

    /** 硬件键盘翻页事件：+1 向下滚一屏、-1 向上滚一屏 */
    private val _expandedPageScrollEvents = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val expandedPageScrollEvents: SharedFlow<Int> = _expandedPageScrollEvents.asSharedFlow()

    /** 请求展开页滚动一屏（硬件键盘 DPAD 上/下） */
    fun requestExpandedPageScroll(direction: Int) {
        _expandedPageScrollEvents.tryEmit(direction)
    }

    /** "只看单字"过滤（展开页左栏底部切换按钮） */
    private val _singleCharFilter = MutableStateFlow(false)
    val singleCharFilter: StateFlow<Boolean> = _singleCharFilter.asStateFlow()

    fun toggleSingleCharFilter() {
        _singleCharFilter.value = !_singleCharFilter.value
    }


    /** 是否从 handwriting 进入英文键盘，用于 ASCII 切回时恢复 handwriting */
    var handwritingShouldReturn: Boolean = false

    /** 进入面板前保存的 keyboardState，用于 exitPanel 恢复 */
    private var _savedKbStateBeforePanel: KeyboardLayoutState? = null

    private data class HandwritingReturn(
        val page: KeyboardPage,
        val layout: KeyboardLayoutState,
        val panelLayout: KeyboardLayoutState?,
    )
    private var handwritingReturn: HandwritingReturn? = null
    val hasTemporaryHandwriting: Boolean get() = handwritingReturn != null

    fun enterTemporaryHandwriting() {
        if (handwritingReturn != null) return
        val current = _page.value
        val returnPage = if (current is KeyboardPage.Overlay) current.behind else current
        handwritingReturn = HandwritingReturn(returnPage, _keyboardState.value, _savedKbStateBeforePanel)
        _savedKbStateBeforePanel = null
        _page.value = KeyboardPage.Main(MainType.HANDWRITING)
        _syncViewState()
    }

    fun exitTemporaryHandwriting(): Boolean {
        val saved = handwritingReturn ?: return false
        handwritingReturn = null
        _page.value = saved.page
        _keyboardState.value = saved.layout
        _savedKbStateBeforePanel = saved.panelLayout
        _syncViewState()
        return true
    }

    fun discardTemporaryHandwriting() { handwritingReturn = null }

    /** 手写板挂上来的“把还没确认的手写字上屏”，实体键盘 Tab 先调它；手写板不在时为 null。 */
    @Volatile var confirmPendingHandwriting: (() -> Unit)? = null

    /** 键盘 ascii 状态机（顶层统一管理各键盘上下文的 ascii 记忆） */
    val asciiStateMachine = KeyboardAsciiStateMachine()

    private val inputSession = KeyboardInputSession()
    @Volatile private var currentKeyboardCallbacks: KeyboardCallbacks? = null
    private var retainedT9Controller: T9InputController? = null

    /**
     * Keep the controller and its queued input alive when only the keyboard surface changes.
     * The service also binds while compact so candidate actions work before the first expansion.
     */
    internal fun bindT9Controller(callbacks: KeyboardCallbacks): T9InputController {
        currentKeyboardCallbacks = callbacks
        // Null means a standalone UI handles unconsumed deletion in its result callback.
        val onUnconsumedDelete: (suspend (com.kingzcheung.xime.keyboard.RepeatInput?) -> Unit)? =
            if (callbacks.onT9UnconsumedDelete == null) null
            else { { hold -> currentKeyboardCallbacks?.onT9UnconsumedDelete?.invoke(hold); Unit } }
        val controller = retainedT9Controller ?: T9InputController(
            inputAdmissionTicket = { currentKeyboardCallbacks?.inputAdmissionTicket?.invoke() },
            captureInputContext = {
                currentKeyboardCallbacks?.captureInputContext?.invoke() ?: kotlin.coroutines.EmptyCoroutineContext
            },
            inputCommands = callbacks.inputCommands,
            leftContext = { currentKeyboardCallbacks?.t9LeftContext?.invoke() },
            onUnconsumedDelete = onUnconsumedDelete,
            onCompositionRefresh = { composition, snapshots ->
                currentKeyboardCallbacks?.onT9RefreshComposition?.invoke(composition, snapshots)
            },
            onRightCommitUndone = { currentKeyboardCallbacks?.onT9RightCommitUndone?.invoke(it) },
            candidateTransform = { currentKeyboardCallbacks?.onTCandidateTransform?.invoke(it) },
        ).also {
            retainedT9Controller = it
            // A physical-keyboard editor may already contain input before this first binding.
            // Read its snapshot; refreshRemainingInput would rewrite the engine's input.
            if (inputSession.initialized) it.refreshAfterPreeditEdit()
        }
        callbacks.onT9RefreshAfterPreeditEdit = { controller.refreshAfterPreeditEdit() }
        callbacks.onT9RightCandidateWillBeSelected = { pinyin, text, textLength, revision ->
            if (t9CandidateNeedsNativeSelection(pinyin, text))
                controller.onRightCandidateSelected(pinyin?.takeUnless { it.isBlank() }.orEmpty(), text, textLength, revision)
            else controller.onRightCandidateSelectedByDirectCommit(revision)
        }
        callbacks.onT9ForceSendToRime = { controller.refreshRemainingInput() }
        callbacks.onT9RunLiteralInput = { controller.enqueueLiteralInput(it) }
        callbacks.onT9CompositionCleared = { controller.resetDisplayAfterCommit() }
        callbacks.onFilterT9Candidates = { candidates, comments -> candidates to comments }
        return controller
    }

    /** Call at the editor boundary, including when no screen keyboard is mounted. */
    internal fun synchronizeInputSession(
        sessionId: Long,
        resetSignal: Long,
        isAsciiMode: Boolean,
        schemaId: String,
    ) {
        val change = inputSession.update(sessionId, resetSignal)
        if (change.resetController) retainedT9Controller?.reset()
        if (change.newSession) {
            asciiStateMachine.reset()
            dispatch(KeyboardDispatchAction.InputSessionStarted(isAsciiMode, schemaId))
        }
    }

    override fun onCleared() {
        retainedT9Controller?.close()
        retainedT9Controller = null
        currentKeyboardCallbacks = null
        super.onCleared()
    }

    /** 统一视图状态（替换 keyboardState + page 双轴） */
    private val _viewState = MutableStateFlow<KeyboardViewState>(KeyboardViewState.ChineseFull)
    val viewState: StateFlow<KeyboardViewState> = _viewState.asStateFlow()

    /**
     * 单⼀状态转移入口 — 替代所有散落的 LaunchedEffect、setKeyboardState、switchMain 等。
     */
    /**
     * 按 action 携带（或显式传入）的 schemaId 同步键布局缓存。
     * dispatch 的各分支都会经由 initialKeyboardLayoutState 依据 schemaId 推导布局，
     * 合并键布局（pinyin_14jian 等）的行数据/手势缓存需在此之前切换到位。
     */
    private fun applySchemaLayout(action: KeyboardDispatchAction, schemaId: String) {
        val sid = schemaId.ifEmpty {
            when (action) {
                is KeyboardDispatchAction.AsciiModeChanged -> action.schemaId
                is KeyboardDispatchAction.InputSessionStarted -> action.schemaId
                else -> ""
            }
        }
        if (sid.isNotEmpty()) {
            KeysConfigHelper.setActiveKeyboardSchema(sid)
        }
    }

    fun dispatch(
        action: KeyboardDispatchAction,
        isAsciiMode: Boolean = false,
        schemaId: String = "",
    ) {
        // 布局随方案切换：合并键方案（pinyin_14jian 等）切换行布局与手势缓存，
        // 必须在状态更新前同步执行，保证重组时读到新布局
        applySchemaLayout(action, schemaId)
        val current = _viewState.value
        val (newState, newPage, newKbState) = when (action) {
            is KeyboardDispatchAction.ToggleChineseEnglish -> {
                when (current) {
                    KeyboardViewState.ChineseFull -> Triple(KeyboardViewState.EnglishFull, KeyboardPage.Main(MainType.FULL), KeyboardLayoutState.English)
                    KeyboardViewState.EnglishFull -> Triple(KeyboardViewState.ChineseFull, KeyboardPage.Main(MainType.FULL), KeyboardLayoutState.Chinese)
                    is KeyboardViewState.NumberPanel -> {
                        Triple(KeyboardViewState.ChineseFull, KeyboardPage.Main(current.returnTo), initialKeyboardLayoutState(isAsciiMode, schemaId))
                    }
                    is KeyboardViewState.CommonSymbolPanel -> {
                        Triple(KeyboardViewState.ChineseFull, KeyboardPage.Main(current.returnTo), initialKeyboardLayoutState(isAsciiMode, schemaId))
                    }
                    else -> Triple(current, _page.value, _keyboardState.value)
                }
            }
            is KeyboardDispatchAction.ModeChange -> {
                val target = if (action.targetIsNumber) KeyboardLayoutAction.SwitchToNumber
                    else KeyboardLayoutAction.SwitchToCommonSymbol
                val newKb = initialKeyboardLayoutState(isAsciiMode, schemaId).transition(target, isAsciiMode, schemaId)
                val newVs: KeyboardViewState = when (newKb) {
                    KeyboardLayoutState.Number -> KeyboardViewState.NumberPanel(MainType.FULL)
                    KeyboardLayoutState.CommonSymbol -> KeyboardViewState.CommonSymbolPanel(MainType.FULL)
                    else -> when (newKb) {
                        is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                        is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                        is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                        is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                        else -> current
                    }
                }
                val newPage = if (newKb is KeyboardLayoutState.Number || newKb is KeyboardLayoutState.CommonSymbol)
                    KeyboardPage.Panel(
                        if (newKb is KeyboardLayoutState.Number) PanelType.NUMBER else PanelType.COMMON_SYMBOL,
                        MainType.FULL
                    )
                else KeyboardPage.Main(MainType.FULL)
                Triple(newVs, newPage, newKb)
            }
            is KeyboardDispatchAction.ShowNumber -> {
                val returnTo = when (current) {
                    is KeyboardViewState.NumberPanel -> current.returnTo
                    is KeyboardViewState.CommonSymbolPanel -> current.returnTo
                    else -> MainType.FULL
                }
                Triple(KeyboardViewState.NumberPanel(returnTo), KeyboardPage.Panel(PanelType.NUMBER, returnTo), KeyboardLayoutState.Number)
            }
            is KeyboardDispatchAction.ShowCommonSymbol -> {
                val returnTo = when (current) {
                    is KeyboardViewState.NumberPanel -> current.returnTo
                    is KeyboardViewState.CommonSymbolPanel -> current.returnTo
                    else -> MainType.FULL
                }
                Triple(KeyboardViewState.CommonSymbolPanel(returnTo), KeyboardPage.Panel(PanelType.COMMON_SYMBOL, returnTo), KeyboardLayoutState.CommonSymbol)
            }
            is KeyboardDispatchAction.ExitPanel -> {
                when (current) {
                    is KeyboardViewState.NumberPanel, is KeyboardViewState.CommonSymbolPanel -> {
                        val returnTo = (current as? KeyboardViewState.NumberPanel)?.returnTo
                            ?: (current as KeyboardViewState.CommonSymbolPanel).returnTo
                        val (vs, kb) = when (returnTo) {
                            MainType.FULL -> {
                                val kb = initialKeyboardLayoutState(isAsciiMode, schemaId)
                                val vs = when (kb) {
                                    is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                                    is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                                    is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                                    is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                                    else -> KeyboardViewState.ChineseFull
                                }
                                vs to kb
                            }
                            MainType.HANDWRITING -> KeyboardViewState.Handwriting to KeyboardLayoutState.Chinese
                            MainType.STROKE -> KeyboardViewState.StrokeFull to KeyboardLayoutState.Stroke
                            MainType.VOICE -> KeyboardViewState.Voice to KeyboardLayoutState.English
                        }
                        Triple(vs, KeyboardPage.Main(returnTo), kb)
                    }
                    else -> Triple(current, _page.value, _keyboardState.value)
                }
            }
            is KeyboardDispatchAction.SwitchToT9Pinyin -> {
                Triple(KeyboardViewState.T9PinyinFull, KeyboardPage.Main(MainType.FULL), KeyboardLayoutState.T9Pinyin)
            }
            is KeyboardDispatchAction.SwitchToStroke -> {
                Triple(KeyboardViewState.StrokeFull, KeyboardPage.Main(MainType.FULL), KeyboardLayoutState.Stroke)
            }
            is KeyboardDispatchAction.SwitchToHandwriting -> {
                Triple(KeyboardViewState.Handwriting, KeyboardPage.Main(MainType.HANDWRITING), KeyboardLayoutState.Chinese)
            }
            is KeyboardDispatchAction.AsciiModeChanged -> {
                if (action.explicitLanguageChoice) asciiStateMachine.reset()
                if (hasTemporaryHandwriting) {
                    // Only an accepted language choice replaces the return target. Late engine
                    // refreshes must not overwrite the layout captured when handwriting opened.
                    val typing = initialKeyboardLayoutState(action.isAsciiMode, action.schemaId)
                    if (action.explicitLanguageChoice) handwritingReturn = handwritingReturn?.let { saved ->
                        when (val page = saved.page) {
                            is KeyboardPage.Main -> if (page.type == MainType.FULL) saved.copy(layout = typing) else saved
                            is KeyboardPage.Panel -> if (page.returnTo == MainType.FULL) saved.copy(panelLayout = typing) else saved
                            else -> saved
                        }
                    }
                    Triple(current, _page.value, _keyboardState.value)
                } else if (current is KeyboardViewState.Overlay) {
                    FileLogger.i("XimeKeyboard", "AsciiModeChanged skipped: current=$current (overlay)")
                    Triple(current, _page.value, _keyboardState.value)
                } else if (current is KeyboardViewState.NumberPanel || current is KeyboardViewState.CommonSymbolPanel) {
                    if (action.explicitLanguageChoice && _page.value.textMainType() == MainType.FULL) {
                        _savedKbStateBeforePanel = initialKeyboardLayoutState(action.isAsciiMode, action.schemaId)
                    }
                    FileLogger.i("XimeKeyboard", "AsciiModeChanged kept panel: current=$current, languageChoice=${action.explicitLanguageChoice}")
                    Triple(current, _page.value, _keyboardState.value)
                } else if (!action.isAsciiMode && isHandwritingSchema(action.schemaId)) {
                    Triple(KeyboardViewState.Handwriting, KeyboardPage.Main(MainType.HANDWRITING), KeyboardLayoutState.Chinese)
                } else {
                    val kb = initialKeyboardLayoutState(action.isAsciiMode, action.schemaId)
                    val vs = when (kb) {
                        is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                        is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                        is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                        is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                        else -> KeyboardViewState.ChineseFull
                    }
                    FileLogger.i("XimeKeyboard", "AsciiModeChanged dispatch: current=$current, ascii=${action.isAsciiMode}, schemaId=${action.schemaId}, -> $vs / $kb")
                    Triple(vs, KeyboardPage.Main(MainType.FULL), kb)
                }
            }
            is KeyboardDispatchAction.InputSessionStarted -> {
                val currentPage = _page.value
                // 面板（数字/符号）不应被新会话事件重置，否则输入一个数字后键盘会切回全键盘
                if (current is KeyboardViewState.NumberPanel || current is KeyboardViewState.CommonSymbolPanel) {
                    Triple(current, _page.value, _keyboardState.value)
                } else if (currentPage is KeyboardPage.Overlay) {
                    // 还开着的面板（编辑/方向盘、剪贴板等）说明是同一输入框重启会话：真正换输入框或收起键盘时
                    // onFinishInput/onWindowHidden 已先关掉面板。有的应用粘贴后会重启输入（如短信），不能因此关面板。
                    Triple(current, currentPage, _keyboardState.value)
                } else if (currentPage is KeyboardPage.Main && currentPage.type == MainType.HANDWRITING
                    && (hasTemporaryHandwriting || action.schemaId.isEmpty() || isHandwritingSchema(action.schemaId))
                ) {
                    // 仅在当前确为手写方案（或方案未知）时保持手写页；事件携带其他
                    // schemaId 说明引擎已切换，手写页是残留状态，需走下方重置切回
                    Triple(_viewState.value, currentPage, _keyboardState.value)
                } else {
                    val kb = initialKeyboardLayoutState(action.isAsciiMode, action.schemaId)
                    val vs = when (kb) {
                        is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                        is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                        is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                        is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                        else -> KeyboardViewState.ChineseFull
                    }
                    Triple(vs, KeyboardPage.Main(MainType.FULL), kb)
                }
            }
            is KeyboardDispatchAction.ShowOverlay -> {
                showOverlay(action.route, action.backStack)
                return
            }
            is KeyboardDispatchAction.CloseOverlay -> {
                closeOverlay()
                return
            }
            is KeyboardDispatchAction.PushOverlay -> {
                pushOverlay(action.route)
                return
            }
            is KeyboardDispatchAction.PopOverlay -> {
                popOverlay()
                return
            }
        }
        if (newPage is KeyboardPage.Main) _savedKbStateBeforePanel = null
        _viewState.value = newState
        _page.value = newPage
        if (newKbState is KeyboardLayoutState.English) {
            resetShift()
        }
        _keyboardState.value = newKbState
        _syncViewState()
    }

    fun toggleShift() {
        shiftState.singleTap()
    }

    fun setShifted(shifted: Boolean) {
        shiftState.setShifted(shifted)
    }

    fun beginShiftHold() = shiftState.beginHold()
    fun endShiftHold(): Boolean = shiftState.endHold()

    fun singleTapShift() {
        shiftState.singleTap()
    }

    fun doubleTapShift() {
        shiftState.doubleTap()
    }

    fun onCharacterTyped() {
        shiftState.onCharacterTyped()
    }

    fun setKeyboardState(state: KeyboardLayoutState) {
        val prevKb = _keyboardState.value
        if (state is KeyboardLayoutState.English) {
            resetShift()
        } else {
            handwritingShouldReturn = false
        }
        _keyboardState.value = state
        _syncViewState()
        if (prevKb != state) {
            FileLogger.i("XimeKeyboard", "setKeyboardState: $prevKb -> $state, vs=${_viewState.value}, page=${_page.value}")
        }
    }
    
    private fun _syncViewState() {
        val kb = _keyboardState.value
        val p = _page.value
        // 主键盘布局真正显示时刷新「最近主布局」来源记录（数字面板返回/符号键位置自适应依赖）。
        // 必须放在 _syncViewState：切九键/笔画等走 dispatch 直接赋值，不经过 setKeyboardState
        if (p is KeyboardPage.Main && p.type == com.kingzcheung.xime.keyboard.MainType.FULL) {
            when (kb) {
                is KeyboardLayoutState.Chinese,
                is KeyboardLayoutState.English,
                is KeyboardLayoutState.T9Pinyin,
                is KeyboardLayoutState.Stroke -> _lastMainLayout.value = kb
                else -> {}
            }
        }
        val vs: KeyboardViewState = when (p) {
            is KeyboardPage.Overlay -> KeyboardViewState.Overlay(p.route, p.backStack, _viewState.value.let { if (it is KeyboardViewState.Overlay) it.behind else it })
            is KeyboardPage.Panel -> when (p.type) {
                com.kingzcheung.xime.keyboard.PanelType.NUMBER -> KeyboardViewState.NumberPanel(p.returnTo)
                com.kingzcheung.xime.keyboard.PanelType.COMMON_SYMBOL -> KeyboardViewState.CommonSymbolPanel(p.returnTo)
            }
            is KeyboardPage.Main -> when (p.type) {
                com.kingzcheung.xime.keyboard.MainType.FULL -> when (kb) {
                    is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                    is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                    is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                    is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                    is KeyboardLayoutState.Number -> KeyboardViewState.NumberPanel(com.kingzcheung.xime.keyboard.MainType.FULL)
                    is KeyboardLayoutState.CommonSymbol -> KeyboardViewState.CommonSymbolPanel(com.kingzcheung.xime.keyboard.MainType.FULL)
                    else -> KeyboardViewState.ChineseFull
                }
                com.kingzcheung.xime.keyboard.MainType.HANDWRITING -> KeyboardViewState.Handwriting
                com.kingzcheung.xime.keyboard.MainType.STROKE -> KeyboardViewState.StrokeFull
                com.kingzcheung.xime.keyboard.MainType.VOICE -> KeyboardViewState.Voice
            }
        }
        _viewState.value = vs
    }

    fun resetShift() {
        shiftState.reset()
    }

    // ── Page Navigation ──

    /** Level 1: 切换主键盘类型 */
    fun switchMain(type: MainType) {
        // 显式模式选择结束旧面板，其返回布局不能泄漏到下一次打开的面板。
        _savedKbStateBeforePanel = null
        _page.value = KeyboardPage.Main(type)
        if (type == MainType.FULL) {
            _keyboardState.value = KeyboardLayoutState.Chinese
        }
        _syncViewState()
    }

    private var savedKbStateBeforeVoice: KeyboardLayoutState? = null

    fun enterVoice() {
        savedKbStateBeforeVoice = _keyboardState.value
        _page.value = KeyboardPage.Main(MainType.VOICE)
        _syncViewState()
    }

    fun exitVoice() {
        val saved = savedKbStateBeforeVoice ?: return
        _page.value = KeyboardPage.Main(MainType.FULL)
        _keyboardState.value = saved
        savedKbStateBeforeVoice = null
        _syncViewState()
    }

    /** Level 2: 进入面板（编号/符号等），可从任意页面进入 */
    fun enterPanel(type: PanelType) {
        val current = _page.value
        val mainType = current.textMainType()
        if (_savedKbStateBeforePanel == null) {
            _savedKbStateBeforePanel = when (_keyboardState.value) {
                KeyboardLayoutState.Number, KeyboardLayoutState.CommonSymbol, KeyboardLayoutState.Symbol -> _lastMainLayout.value
                else -> _keyboardState.value
            }
        }
        _page.value = KeyboardPage.Panel(type, mainType)
        _keyboardState.value = when (type) {
            PanelType.NUMBER -> KeyboardLayoutState.Number
            PanelType.COMMON_SYMBOL -> KeyboardLayoutState.CommonSymbol
        }
        _syncViewState()
    }

    /** Both secondary modes return directly to the original text keyboard. */
    fun returnToTextKeyboard() {
        val mainType = _page.value.textMainType()
        _keyboardState.value = _savedKbStateBeforePanel ?: _lastMainLayout.value
        _savedKbStateBeforePanel = null
        _page.value = KeyboardPage.Main(mainType)
        _syncViewState()
    }

    fun exitPanel() = returnToTextKeyboard()

    /** Level 3: 打开覆盖页面，可从任意页面进入 */
    fun showOverlay(route: OverlayRoute, initialBackStack: List<OverlayRoute> = emptyList()) {
        leaveHandwritingForClipboard(route)
        // Keep the current input surface in place while the tool reveals over it.
        // Symbols still return to text, but only when leaving the symbol panel.
        val current = _page.value
        val behind = if (current is KeyboardPage.Overlay) current.behind else current
        _page.value = KeyboardPage.Overlay(route, initialBackStack, behind,
            returnToTextOnClose = route == OverlayRoute.Symbol ||
                (current as? KeyboardPage.Overlay)?.returnToTextOnClose == true)
        _syncViewState()
    }

    /** 同一工具栏图标再次点击，回到打开工具前的页面。 */
    fun toggleOverlay(route: OverlayRoute, initialBackStack: List<OverlayRoute> = emptyList()) {
        val currentRoute = (_page.value as? KeyboardPage.Overlay)?.route
        // Text, images and legacy pinned links are one clipboard tool, not separate toggles.
        if (currentRoute == route || currentRoute is OverlayRoute.Clipboard && route is OverlayRoute.Clipboard) leaveClipboard()
        // 从方向盘（编辑）面板打开剪贴板：记住编辑面板，粘贴或收起剪贴板后回到它，而不是回主键盘。
        else if (currentRoute == OverlayRoute.Edit && route is OverlayRoute.Clipboard && initialBackStack.isEmpty()) pushOverlay(route)
        else if (currentRoute == OverlayRoute.Menu && route == OverlayRoute.SchemaList && initialBackStack.isEmpty()) pushOverlay(route)
        else showOverlay(route, initialBackStack)
    }

    /**
     * 离开剪贴板（粘贴、返回或再次点剪贴板图标）：若剪贴板是从编辑面板打开的，回到编辑面板；
     * 否则照常关闭覆盖页。
     */
    fun leaveClipboard() {
        val current = _page.value
        val editIndex = (current as? KeyboardPage.Overlay)?.backStack?.lastIndexOf(OverlayRoute.Edit) ?: -1
        if (current is KeyboardPage.Overlay && current.route is OverlayRoute.Clipboard && editIndex >= 0) {
            _page.value = current.copy(route = OverlayRoute.Edit, backStack = current.backStack.take(editIndex))
            _syncViewState()
        } else closeOverlay()
    }

    /** Level 3: 在覆盖页面内推入子页 */
    fun pushOverlay(route: OverlayRoute) {
        if (leaveHandwritingForClipboard(route)) {
            showOverlay(route)
            return
        }
        val current = _page.value
        if (current is KeyboardPage.Overlay) {
            _page.value = current.copy(
                route = route,
                backStack = current.backStack + current.route,
                returnToTextOnClose = current.returnToTextOnClose || route == OverlayRoute.Symbol,
            )
            _syncViewState()
        }
    }

    /** Clipboard owns the normal keyboard area; voice and other tools keep their current rules. */
    private fun leaveHandwritingForClipboard(route: OverlayRoute): Boolean {
        if (route !is OverlayRoute.Clipboard || _page.value.textMainType() != MainType.HANDWRITING) return false
        if (!exitTemporaryHandwriting() || _page.value.textMainType() == MainType.HANDWRITING) {
            handwritingReturn = null
            _savedKbStateBeforePanel = null
            _keyboardState.value = _lastMainLayout.value
            _page.value = KeyboardPage.Main(MainType.FULL)
            _syncViewState()
        }
        handwritingShouldReturn = false
        return true
    }

    /** Level 3: 在覆盖页面内回退 */
    fun popOverlay() {
        val current = _page.value
        if (current is KeyboardPage.Overlay && current.backStack.isNotEmpty()) {
            val prev = current.backStack.last()
            _page.value = current.copy(
                route = prev,
                backStack = current.backStack.dropLast(1)
            )
            _syncViewState()
        }
    }

    /** Level 3: 关闭覆盖页面，回到背后页面 */
    fun closeOverlay() {
        val current = _page.value
        if (current is KeyboardPage.Overlay) {
            if (current.returnToTextOnClose) {
                returnToTextKeyboard()
                return
            }
            _page.value = current.behind
            _syncViewState()
        }
    }

    /** Called only after the input FIFO verifies that this is still the same editor session. */
    internal fun applyPreparedEditorKeyboard(
        isAsciiMode: Boolean, schemaId: String, handwriting: Boolean,
        restarting: Boolean, forceNumberPanel: Boolean,
    ) {
        if (restarting && !handwriting) return
        // The picker or the user may already have opened OCR while the engine was preparing.
        // Snapshot NOW, not when the background work started, so cancel/other tools also win.
        val visiblePage = _page.value
        if (handwriting) switchMain(MainType.HANDWRITING)
        else resetKeyboard(isAsciiMode, schemaId, forceNumberPanel)
        _page.value = visiblePage.afterEditorPreparation(_page.value)
        // A delayed engine preparation must not reintroduce handwriting beneath an open clipboard.
        if (visiblePage is KeyboardPage.Overlay && leaveHandwritingForClipboard(visiblePage.route)) {
            showOverlay(visiblePage.route, visiblePage.backStack)
        }
        _syncViewState()
    }

    fun resetKeyboard(isAsciiMode: Boolean, schemaId: String = "", forceNumberPanel: Boolean = false) {
        resetShift()
        _candidatePageExpanded.value = false
        _candidateStripExtended.value = false
        KeysConfigHelper.setActiveKeyboardSchema(schemaId)
        if (forceNumberPanel) {
            // 数字输入框自动进入数字面板：记录默认主布局，供面板"abc"返回键恢复
            // （exitPanel 对 FULL returnTo 优先用 _savedKbStateBeforePanel）
            _keyboardState.value = KeyboardLayoutState.Number
            _savedKbStateBeforePanel = initialKeyboardLayoutState(isAsciiMode, schemaId)
            _page.value = KeyboardPage.Panel(PanelType.NUMBER, MainType.FULL)
        } else {
            _savedKbStateBeforePanel = null
            _keyboardState.value = initialKeyboardLayoutState(isAsciiMode, schemaId)
            if (_page.value !is KeyboardPage.Main) {
                _page.value = KeyboardPage.Main(MainType.FULL)
            }
        }
        // 必须重新推导 viewState：否则 _viewState 停留在旧的面板/覆盖值，
        // 与 UI 实际渲染的 keyboardState 脱节，后续 AsciiModeChanged 会被误判为 panel/overlay 而跳过。
        _syncViewState()
    }

    // Clipboard operations

    fun removeClipboardItem(id: Long) {
        clipboardManager.removeItem(id)
    }

    fun removeClipboardItems(ids: List<Long>) {
        clipboardManager.removeItems(ids)
    }

    fun splitClipboardItem(id: Long) {
        clipboardManager.splitItem(id)
    }

    fun clearClipboard() {
        clipboardManager.clearClipboard()
    }

    fun addToQuickSend(id: Long) {
        clipboardManager.addToQuickSend(id)
    }

    fun addQuickSendText(text: String, code: String = "") {
        addPinnedText(text, code)
    }

    fun updateQuickSendItem(id: Long, text: String, code: String = "") {
        updateClipboardItem(id, text, code)
    }

    fun addPinnedText(text: String, code: String = "", groupId: String? = null) {
        clipboardManager.addPinnedText(text, code, groupId)
    }

    fun updateClipboardItem(id: Long, text: String, code: String? = null): Boolean =
        clipboardManager.updateClipboardItem(id, text, code)

    fun removeQuickSendItem(id: Long) {
        clipboardManager.removeFromQuickSend(id)
    }

    fun togglePinQuickSend(id: Long) {
        clipboardManager.togglePinQuickSend(id)
    }
}
