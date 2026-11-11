package com.kingzcheung.xime.rime

import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.util.InputLatencyTrace
import com.kingzcheung.xime.service.KeyLatencyProbe
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.asContextElement

/**
 * 九键拼音输入控制器（薄包装）。
 *
 * T9 核心逻辑（数字缓冲、三态状态机、右选消费算法、撤销管理等）已整体迁移到
 * librime-t9（C++ 插件）中实现。本类仅通过 [RimeEngine] 的 JNI 接口与 C++ 层
 * 交互，并维护 UI 展示所需的 Compose 状态。
 *
 * 原 Kotlin 侧 T9Buffer / T9StateMachine / T9UndoManager / T9RightCommitHandler /
 * T9RimeBridge / T9PinyinMap 等类均已删除，由 C++ 同名组件替代。
 *
 * 数字、退格、普通输入及候选交付共享宿主的 [InputCommandQueue]。
 * 一个命令等待 Main 上屏时，后续命令仍排在它的学习和清理之后。
 * 仅测试/独立控制器使用私有队列；关闭控制器不会关闭宿主队列。
 */
class T9InputController(
    private val rimeEngine: RimeEngine = RimeEngine.getInstance(),
    private val onCompositionRefresh: ((RimeComposition, List<com.kingzcheung.xime.service.T9CandidateInjection>) -> Unit)? = null,
    private val onRightCommitUndone: ((Int) -> Unit)? = null,
    /** 候选词变换（hotPath 插件能力）：后台取数后、post 主线程前同步调用
     *  （阻塞至多 15ms，主线程零等待）；返回带引擎锚点的插件候选注入列表
     *  （text 追加项），引擎结果原样使用（T9 不支持引擎引用替换）。null = 不干预。 */
    private val inputAdmissionTicket: () -> Long? = { 0L },
    private val captureInputContext: () -> kotlin.coroutines.CoroutineContext = { kotlin.coroutines.EmptyCoroutineContext },
    inputCommands: InputCommandQueue? = null,
    /** 组合为空时光标前的已上屏文字（九键词组模型的上文）；null 表示未知。 */
    private val leftContext: () -> String? = { null },
    private val onUnconsumedDelete: (suspend (com.kingzcheung.xime.keyboard.RepeatInput?) -> Unit)? = null,
    private val candidateTransform: ((RimeProcessResult) -> List<com.kingzcheung.xime.service.T9CandidateInjection>?)? = null,
) {
    companion object {
        private const val TAG = "T9InputController"
        const val CLEAR_COMPOSITION_ONLY = "clear_composition"
        const val CLEAR_ALL = "clear_all"
    }

    enum class LeftPanelState { IDLE, INPUT, SELECTION }

    enum class DeleteResult {
        DELETED, UNDO_CHOICE, UNDO_COMMIT, NOT_CONSUMED
    }

    /** 音节选项（原 T9PinyinMap.SyllableOption，已内联至此） */
    data class SyllableOption(
        val pinyin: String,
        val digitLength: Int
    )

    // ── 异步执行模型 ──
    private val ownsQueue = inputCommands == null
    private val commands = inputCommands ?: InputCommandQueue(CoroutineScope(Dispatchers.Default), Dispatchers.Default)
    private val enqueueLock = Any()
    // Opt-in diagnostic hook for instrumentation; never records input text.
    internal var timingObserver: ((Int, String) -> Unit)? = null
    private val timingSequence = java.util.concurrent.atomic.AtomicInteger()
    private fun timing(id: Int, event: String) { if (id != 0) timingObserver?.invoke(id, event) }
    private fun timingId(): Int = if (com.kingzcheung.xime.BuildConfig.DEBUG && timingObserver != null) timingSequence.incrementAndGet() else 0

    /** UI 更新投递目标（后台任务通过 post 派发，不阻塞任务完成）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * UI 刷新代际号：每次发起刷新自增。异步 post 到达 Main 时若代际已过期则丢弃，
     * 防止「同步刷新（如右选）→ 旧的异步刷新 post 后执行」把 UI 回退到旧状态。
     */
    private val uiGeneration = AtomicLong()
    private val inputEpoch = AtomicLong()
    @Volatile private var closed = false


    /**
     * 最近一次已发布 flush 快照的 input 缓存（@Volatile，Main 应用快照时写入）。
     * bufferString/inputBuffer 不再主线程调 [RimeEngine.getInput]（消除主线程 JNI），
     * 且与 preedit/候选/左栏同源于一次 getProcessResult → 保证 UI 元素同帧同步。
     */
    @Volatile
    private var cachedInput: String = ""

    /** 将 T9 处理任务排入单线程后台队列（FIFO 保序）。 */
    private fun enqueue(kind: String = "t9-other", block: suspend CoroutineScope.() -> Unit) {
        synchronized(enqueueLock) {
            if (closed) return
            commands.submit(kind = kind) { if (!closed) block() }
        }
    }

    private fun enqueueInput(kind: String = "t9-other", block: suspend CoroutineScope.(Long, Long) -> Unit) {
        val admission = inputAdmissionTicket() ?: return
        val epoch = inputEpoch.get()
        val ownerContext = captureInputContext()
        enqueue(kind) {
            kotlinx.coroutines.withContext(ownerContext + commandValidity(admission, epoch)) {
                if (accepts(admission, epoch)) block(admission, epoch)
            }
        }
    }

    private fun commandValidity(admission: Long, epoch: Long) = RimeCommandContext.validity.asContextElement {
        if (!accepts(admission, epoch)) throw kotlinx.coroutines.CancellationException("Expired T9 command")
    }

    private fun accepts(admission: Long, epoch: Long): Boolean =
        !closed && inputAdmissionTicket() == admission && inputEpoch.get() == epoch

    /** Dispose local callbacks without cancelling the service's shared input queue. */
    fun close() {
        synchronized(enqueueLock) {
            closed = true
            inputEpoch.incrementAndGet()
            if (ownsQueue) commands.close()
        }
        mainHandler.removeCallbacksAndMessages(null)
    }

    /** UI 显示的缓冲区字符串 = 最近一次 flush 快照的 input（缓存，非主线程 JNI） */
    val bufferString: String get() {
        val input = cachedInput
        return when {
            input.isEmpty() && _committedText != null -> _committedText!!
            input.isEmpty() -> ""
            else -> input
        }
    }

    /** 当前输入缓冲区（供 KeyboardView 判断右选是否完整消费；缓存值，非主线程 JNI） */
    val inputBuffer: String get() = cachedInput

    var firstOptions: List<SyllableOption> by mutableStateOf(emptyList())
        private set

    var leftPanelState: LeftPanelState by mutableStateOf(LeftPanelState.IDLE)
        private set

    var selectedOption: SyllableOption? by mutableStateOf(null)
        private set

    var selectionCandidateDigits: String? by mutableStateOf(null)
        private set

    var leftColumnLocked: Boolean by mutableStateOf(false)
        private set

    val selectionHistory: List<SyllableOption> get() = _selectionHistory
    private var _selectionHistory: List<SyllableOption> = emptyList()

    private var _committedText: String? = null

    /**
     * 重置（输入会话开始/切换键盘时调用，主线程）。
     * 本地 Compose 状态立即复位；C++ 状态清空排入后台队列——与后续按键同队列保序，
     * 主线程零等待（方案 B）。
     */
    fun reset() {
        resetLocalState()
        enqueue { rimeEngine.clearQueuedT9Composition() }
    }

    /** 字面输入和普通九键触摸共用 FIFO，随后输入不能越过数字提交。 */
    internal fun enqueueLiteralInput(block: suspend () -> Unit) {
        enqueueInput { _, _ -> block() }
    }

    /** Refresh is enqueued behind the editor replacement, ahead of the user's next key. */
    internal fun refreshAfterPreeditEdit() {
        uiGeneration.incrementAndGet()
        enqueueInput { admission, epoch -> refreshOnBackground(admission = admission, epoch = epoch) }
    }

    /** 引擎已在 T9 队列清空时只重置 UI，不能另排一个 clear 误删后续按键。 */
    internal fun resetLocalState() {
        // Old work belongs to the old editor, including work still inside JNI.
        inputEpoch.incrementAndGet()
        resetDisplayAfterCommit()
    }

    /** An accepted commit ends the old composition, not the editor or already queued next keys. */
    internal fun resetDisplayAfterCommit() {
        uiGeneration.incrementAndGet()
        _selectionHistory = emptyList()
        firstOptions = emptyList()
        leftPanelState = LeftPanelState.IDLE
        selectedOption = null
        selectionCandidateDigits = null
        leftColumnLocked = false
        _committedText = null
        cachedInput = ""
    }

    /**
     * 候选词变换（hotPath 插件能力）：引擎结果原样 + 插件候选注入列表。
     * t9Dispatcher 线程同步等插件至多 15ms；失败/不干预返回空注入。
     */
    private fun transformInjections(result: RimeProcessResult): Pair<RimeProcessResult, List<com.kingzcheung.xime.service.T9CandidateInjection>> {
        val injections = candidateTransform?.invoke(result) ?: emptyList()
        return result to injections
    }

    /**
     * 后台线程：flush 后一次取全量结果 + 左栏数据，经 [mainHandler] 投递到 Main
     * 更新 Compose 状态，并携带 composition 通知服务层刷新（避免其内部重复
     * getComposition）。post 为 fire-and-forget，任务完成不依赖 Main 线程；
     * 携带代际号，过期刷新在 Main 执行时丢弃。
     */
    private fun refreshOnBackground(
        traceId: Int = 0,
        result: RimeProcessResult? = null,
        admission: Long,
        epoch: Long,
        refineWhenIdle: Boolean = true,
        timingId: Int = 0,
    ) {
        val data = InputLatencyTrace.phase(traceId, "snapshot-jni") { fetchAll(result) } ?: run {
            InputLatencyTrace.finish(traceId, "engine-unavailable")
            return
        }
        val (finalResult, injections) = InputLatencyTrace.phase(traceId, "candidate-transform") { transformInjections(data.result) }
        val composition = finalResult.toComposition().also { it.traceEventId = traceId }
        val gen = uiGeneration.incrementAndGet()
        KeyLatencyProbe.posted(finalResult.inputText.length)
        mainHandler.post {
            timing(timingId, "main-arrive")
            if (!accepts(admission, epoch) || gen != uiGeneration.get()) {
                timing(timingId, "main-superseded")
                InputLatencyTrace.finish(traceId, "superseded")
                return@post
            }
            KeyLatencyProbe.handed()
            InputLatencyTrace.phase(traceId, "state-publish") {
                onCompositionRefresh?.invoke(composition, injections)
                applyCandidates(finalResult, data.panel, data.options)
            }
            KeyLatencyProbe.applied()
            timing(timingId, "main-published")
            if (refineWhenIdle && onCompositionRefresh != null)
                scheduleRefinement(finalResult.engineRevision, gen, admission, epoch)
        }
    }

    private var refinementPoll: Runnable? = null
    private fun scheduleRefinement(revision: Long, generation: Long, admission: Long, epoch: Long, attempt: Int = 0) {
        refinementPoll?.let(mainHandler::removeCallbacks)
        if (attempt >= 40 || !accepts(admission, epoch) || generation != uiGeneration.get()) return
        val ownerContext = captureInputContext()
        val poll = Runnable {
            if (!accepts(admission, epoch) || generation != uiGeneration.get()) return@Runnable
            enqueue("t9-refine") {
                kotlinx.coroutines.withContext(ownerContext + commandValidity(admission, epoch)) {
                    if (!accepts(admission, epoch) || generation != uiGeneration.get() || !rimeEngine.isCandidateRevisionCurrent(revision)) return@withContext
                    when (rimeEngine.t9RefinementState()) {
                        2 -> rimeEngine.refineQueuedT9Result(revision)?.let {
                            refreshOnBackground(result = it, admission = admission, epoch = epoch, refineWhenIdle = false)
                        }
                        1 -> mainHandler.post { scheduleRefinement(revision, generation, admission, epoch, attempt + 1) }
                    }
                }
            }
        }
        refinementPoll = poll
        mainHandler.postDelayed(poll, 100)
    }

    /** 一次 flush 后取回的全部刷新数据（composition 全量 + 左栏面板 + 首音节候选）。 */
    private data class T9RefreshData(
        val result: RimeProcessResult,
        val panel: LeftPanelInfo,
        val options: List<Pair<String, Int>>,
    )

    /**
     * 后台取全量数据：一次 JNI（getProcessResult）拿 composition + T9 左栏面板 +
     * 首音节候选（C++ readCurrentState 填充），Main 线程只做解析与状态赋值。
     */
    private fun fetchAll(snapshot: RimeProcessResult? = null): T9RefreshData? {
        val result = snapshot ?: rimeEngine.readQueuedResult() ?: return null
        val panel = parseLeftPanelState(result.t9PanelState.ifEmpty { "IDLE;;;;;0" })
        val options = parseSyllableOptions(result.t9SyllableOptions)
        return T9RefreshData(result, panel, options)
    }

    /** 解析 JNI 返回的 "pinyin|digitLength,..." 首音节候选串。 */
    private fun parseSyllableOptions(raw: String): List<Pair<String, Int>> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) {
                Pair(parts[0], parts[1].toIntOrNull() ?: 1)
            } else null
        }
    }

    /**
     * 基于一次取回的全量结果重建左栏/面板状态（Main 线程执行）。
     * PANEL_DIGITS 已内含分词键锁定 + unassigned + selectionCandidateDigits +
     * separatorConsumedDigits 回退逻辑，确保混合输入（如 5'43）时左侧候选只显示
     * 当前数字段（5 → j/k/l）而非整段过滤结果（543 → jie/lie）。
     */
    private fun applyCandidates(
        result: RimeProcessResult,
        panel: LeftPanelInfo,
        options: List<Pair<String, Int>>,
    ) {
        val rawInput = result.inputText
        cachedInput = rawInput

        if (rawInput.isEmpty() && _committedText != result.committedText) {
            _committedText = result.committedText
        }

        // 僵尸 RC 态（如测试文档 bs6 场景：右选"策"后删完剩余数字，仅剩消费区 '23'）：
        // C++ SendToRime 有意清空 RIME input（kZombieClear，避免 preedit 拼接"策ce"），
        // 但 T9 buffer 仍处于 SELECTION（panel.state 非 IDLE，如 SELECTION/ce/23）。
        // 此时不能因 rawInput 为空误置左栏 IDLE——必须以 C++ 面板状态为准渲染。
        if (rawInput.isEmpty() && panel.state == LeftPanelState.IDLE) {
            if (leftPanelState != LeftPanelState.IDLE) {
                firstOptions = emptyList()
                leftPanelState = LeftPanelState.IDLE
                selectedOption = null
                selectionCandidateDigits = null
            }
            return
        }

        // 从数字段用 librime-t9 (C++) 计算左栏候选
        firstOptions = options.map { SyllableOption(it.first, it.second) }

        // 同步 C++ 状态机状态（分词键锁定 / 左选高亮 / 选择候选数字段）
        leftPanelState = panel.state
        selectedOption = if (panel.selectedPinyin.isNotEmpty()) {
            SyllableOption(panel.selectedPinyin, panel.selectedDigitLength)
        } else {
            null
        }
        selectionCandidateDigits = panel.selectionCandidateDigits.ifEmpty { null }
        leftColumnLocked = panel.leftLocked
    }

    /** C++ 左侧面板状态（t9GetLeftPanelState 解析结果） */
    private data class LeftPanelInfo(
        val state: LeftPanelState,
        val selectedPinyin: String,
        val selectedDigitLength: Int,
        val selectionCandidateDigits: String,
        val panelDigits: String,
        val leftLocked: Boolean,
    )

    /** 解析 "STATE;PINYIN;DIGIT_LEN;SEL_DIGITS;PANEL_DIGITS;LEFT_LOCKED" 格式 */
    private fun parseLeftPanelState(raw: String): LeftPanelInfo {
        val parts = raw.split(";")
        return LeftPanelInfo(
            state = when (parts.getOrNull(0)) {
                "INPUT" -> LeftPanelState.INPUT
                "SELECTION" -> LeftPanelState.SELECTION
                else -> LeftPanelState.IDLE
            },
            selectedPinyin = parts.getOrNull(1) ?: "",
            selectedDigitLength = parts.getOrNull(2)?.toIntOrNull() ?: 0,
            selectionCandidateDigits = parts.getOrNull(3) ?: "",
            panelDigits = parts.getOrNull(4) ?: "",
            leftLocked = parts.getOrNull(5) == "1",
        )
    }

    fun onDigitPressed(digit: String) {
        if (inputAdmissionTicket() == null) return
        val code = digit[0].code
        val traceId = InputLatencyTrace.begin()
        // Read before queuing: the engine uses it only if this digit starts a composition.
        val context = leftContext()
        KeyLatencyProbe.fired("t9")
        val timingId = timingId()
        timing(timingId, "digit-enqueue")
        enqueueInput("t9-digit") { admission, epoch ->
            timing(timingId, "queue-start")
            KeyLatencyProbe.started()
            try {
                val transaction = InputLatencyTrace.phase(traceId, "engine-jni-flush") {
                    rimeEngine.processQueuedT9KeyAndGetResult(code, context, if (timingId == 0) null else ({ timing(timingId, "engine-lock-acquired") }))
                }
                timing(timingId, "engine-complete")
                if (transaction == null) InputLatencyTrace.finish(traceId, "engine-unavailable")
                else refreshOnBackground(traceId, transaction.state, admission, epoch, timingId = timingId)
            } catch (e: Throwable) {
                InputLatencyTrace.finish(traceId, "failed")
                throw e
            }
        }
    }

    /**
     * 拂动字母：并入当前拼音串（该位置固定为这个字母），不清空已输入的数字。
     * 没有组合中的数字时返回 false，调用方按普通字母输入处理。
     */
    fun onLetterFlicked(letter: String): Boolean {
        if (leftPanelState == LeftPanelState.IDLE) return false
        enqueueInput("t9-fix-letter") { admission, epoch ->
            val result = rimeEngine.fixQueuedT9Letter(letter) ?: return@enqueueInput
            refreshOnBackground(result = result, admission = admission, epoch = epoch)
        }
        return true
    }

    fun onChoiceSelected(option: SyllableOption) {
        enqueueInput("t9-syllable") { admission, epoch ->
            val result = rimeEngine.selectQueuedT9Pinyin(option.pinyin, option.digitLength) ?: return@enqueueInput
            refreshOnBackground(result = result, admission = admission, epoch = epoch)
        }
    }

    /**
     * 右选作为宿主选词事务中的一步执行，后续按键要等整个上屏事务结束。
     * 调频不在此进行——由服务层在 full commit 上屏后经 rimeEngine.t9Memorize 单独调用。
     *
     * @param candidatePinyin 候选词拼音注释（comment），null 表示无注释候选（如 emoji）
     * @param candidateText 候选词文本，供 C++ (comment, text) 双条件定位（防同注释错码）
     * @param candidateTextLength 候选词字数
     */
    suspend fun onRightCandidateSelected(
        candidatePinyin: String? = null,
        candidateText: String? = null,
        candidateTextLength: Int = 0,
        revision: Long,
    ): Boolean? = commands.execute {
        val admission = inputAdmissionTicket() ?: return@execute null
        val epoch = inputEpoch.get()
        if (!accepts(admission, epoch)) return@execute null
        val transaction = candidatePinyin?.let {
            rimeEngine.selectQueuedT9Candidate(it, candidateText, candidateTextLength, revision)
        } ?: return@execute null
        val generation = uiGeneration.incrementAndGet()
        val data = fetchAll(transaction.second)
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            if (data != null && accepts(admission, epoch) && generation == uiGeneration.get()) {
                applyCandidates(data.result, data.panel, data.options)
            }
        }
        if (!accepts(admission, epoch)) return@execute null
        transaction.first
    }

    /**
     * 退格处理（异步，结果通过回调返回）。
     *
     * 每次点击独立入队；长按仅保留一个尚未完成的重复，抬手后不补发积压。
     *
     * @param callback 在 Main 线程调用，参数为退格结果。
     */
    fun onDeleted(callback: (DeleteResult) -> Unit) {
        val admission = inputAdmissionTicket() ?: return
        val epoch = inputEpoch.get()
        val ownerContext = captureInputContext()
        val hold = com.kingzcheung.xime.keyboard.RepeatInput.current.get()
        if (hold != null && !hold.acquire()) return
        val timingId = timingId()
        timing(timingId, "delete-enqueue")
        enqueue("t9-delete") {
            timing(timingId, "queue-start")
            // A later delete must not jump ahead of a digit already in this FIFO.
            try {
                kotlinx.coroutines.withContext(ownerContext + commandValidity(admission, epoch)) {
                    if (accepts(admission, epoch) && hold?.isActive != false) processDelete(callback, admission, epoch, hold, timingId)
                }
            } finally { hold?.completed() }
        }
    }

    /** 单次退格：processKey → flush → 撤销计数 → 取全量结果 → Main 刷新 + 回调。 */
    private suspend fun processDelete(callback: (DeleteResult) -> Unit, admission: Long, epoch: Long, hold: com.kingzcheung.xime.keyboard.RepeatInput?, timingId: Int) {
        val transaction = rimeEngine.processQueuedT9KeyAndGetResult(0xff08, onLockAcquired = if (timingId == 0) null else ({ timing(timingId, "engine-lock-acquired") }))
        timing(timingId, "engine-complete")
        if (transaction == null) return
        val undoneCount = transaction.undoneSegments
        val data = fetchAll(transaction.state) ?: return
        val (finalResult, injections) = transformInjections(data.result)
        val composition = finalResult.toComposition()
        val deleteResult = if (t9DeleteConsumed(transaction.state.processed, undoneCount, transaction.composingBefore))
            DeleteResult.DELETED else DeleteResult.NOT_CONSUMED
        if (deleteResult == DeleteResult.DELETED) hold?.allowsDeletion(imeContent = true)
        val gen = uiGeneration.incrementAndGet()
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            timing(timingId, "main-arrive")
            if (!accepts(admission, epoch)) return@withContext
            // 撤销计数与退格结果始终回调；仅当刷新仍是最新代际时应用，
            // 避免后续更快的按键刷新被本退格的旧状态覆盖。
            if (undoneCount > 0) {
                onRightCommitUndone?.invoke(undoneCount)
            }
            if (gen == uiGeneration.get()) {
                onCompositionRefresh?.invoke(composition, injections)
                applyCandidates(finalResult, data.panel, data.options)
                timing(timingId, "main-published")
            } else timing(timingId, "main-superseded")
            if (deleteResult != DeleteResult.NOT_CONSUMED ||
                (onUnconsumedDelete == null && hold?.allowsDeletion(imeContent = false) != false)) {
                callback(deleteResult)
            }
        }
        // Editor fallback is still this physical delete, not a new action at the FIFO tail.
        if (deleteResult == DeleteResult.NOT_CONSUMED && accepts(admission, epoch) && hold?.isActive != false) {
            onUnconsumedDelete?.invoke(hold)
        }
    }

    fun forceSendToRime() {
        enqueueInput { admission, epoch ->
            refreshRemainingInput(admission, epoch)
        }
    }

    /** Partial-choice refresh stays inside the selection action, ahead of the next physical key. */
    internal suspend fun refreshRemainingInput() = commands.execute {
        val admission = inputAdmissionTicket() ?: return@execute
        refreshRemainingInput(admission, inputEpoch.get())
    }

    private fun refreshRemainingInput(admission: Long, epoch: Long) {
        if (!accepts(admission, epoch)) return
        val remaining = rimeEngine.t9GetRemainingDigits()
        if (remaining.isNotEmpty()) rimeEngine.setInput(remaining)
        rimeEngine.restoreT9CaretAfterChoice(remaining)
        refreshOnBackground(admission = admission, epoch = epoch)
    }

    /**
     * 右侧候选直接提交上屏：不经过消耗算法，清空缓冲区并进入空闲状态。
     * 用于 emoji/符号等无拼音注释的候选词，RIME 引擎已匹配输入序列到候选词，
     * T9 控制器无需做音节级消费计算。
     */
    suspend fun onRightCandidateSelectedByDirectCommit(revision: Long): Boolean? = commands.execute("t9-select") {
        val admission = inputAdmissionTicket() ?: return@execute null
        val epoch = inputEpoch.get()
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            if (!accepts(admission, epoch) || !rimeEngine.isCandidateRevisionCurrent(revision)) null
            // Delivery owns cleanup. Clearing here would lose codes/text before the
            // editor has accepted a direct (comment-free) candidate.
            else true
        }
    }

    fun clearRimeAndResend() {
        enqueueInput { admission, epoch ->
            rimeEngine.clearComposition()
            refreshOnBackground(admission = admission, epoch = epoch)
        }
    }

    /**
     * 清空（主线程 ResetKey/上滑手势调用）。
     * 本地 Compose 状态立即复位；C++ 状态清空排入后台队列——若队列中已有未完成的
     * 按键处理，清空在其后执行（与后续按键同队列保序），主线程零等待（方案 B）。
     */
    fun clearAll() {
        inputEpoch.incrementAndGet()
        uiGeneration.incrementAndGet()
        firstOptions = emptyList()
        leftPanelState = LeftPanelState.IDLE
        selectedOption = null
        selectionCandidateDigits = null
        leftColumnLocked = false
        _selectionHistory = emptyList()
        _committedText = null
        cachedInput = ""
        enqueue { rimeEngine.clearQueuedT9Composition() }
    }

    fun onEnterCommit() { clearAll() }

    fun isSelectedOptionInCurrentCandidates(): Boolean = selectedOption in firstOptions


}
