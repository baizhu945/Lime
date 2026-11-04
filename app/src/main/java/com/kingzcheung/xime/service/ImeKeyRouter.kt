package com.kingzcheung.xime.service

import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.kingzcheung.xime.association.AssociationManager
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.SpatialInputLog
import com.kingzcheung.xime.keyboard.SpatialTaps
import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.rime.resolveRimeCandidateIndex
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 按键输入路由。
 *
 * 承载按键派发（handleKeyPress）、长按退格合并、候选选择/翻页、计算器候选等逻辑。
 * 所有共享状态通过 service 引用访问（同模块 internal 成员）。
 */
/** 26-key schemas whose speller alphabet has A–Z, so a capital joins the composition (rime-ice family):
 *  rime_ice and the layouts made from its schema (QWJRTK, Cyletix10 and the user's own custom_pinyin_*). */
private fun isRimeIceCapitalSchema(id: String) =
    id == "rime_ice" || com.kingzcheung.xime.settings.CustomKeyboardLayouts.isCustom(id)

internal class ImeKeyRouter(internal val service: XimeInputMethodService) {
    @Volatile internal var letterNeighbors: Pair<String, String> = "" to ""
    private val spatialInput = SpatialInputLog()
    internal val hardwareNavigation = HardwareCandidateNavigation()
    private val hardwareSelectionRevision = androidx.compose.runtime.mutableIntStateOf(0)
    internal val usesDirectFieldInput: Boolean get() = service.editorInputPolicy.directInput &&
        !service.uiState.value.inputTarget.isInternal

    /** Literal field input bypasses the decoder, calculator, learning and candidate snapshots. */
    private suspend fun routeDirectFieldKey(key: String, shifted: Boolean, event: KeyEvent?): Boolean {
        val policy = service.editorInputPolicy
        when {
            key == "ime_switch" -> return policy.locksNativeInput
            key == com.kingzcheung.xime.viewmodel.ASCII_CONTEXT_ON ||
                key == com.kingzcheung.xime.viewmodel.ASCII_CONTEXT_OFF -> return false
            key in setOf("shift", "mode_change", "abc", "number", "common_symbol") -> return false
            key.startsWith("hardware_candidate_") -> {
                if (event != null && isHardwareArrow(event.keyCode)) withEditor {
                    service.schemaController.moveHardwareCursor(event)
                }
            }
            key == "\t" && event != null -> withEditor {
                service.currentInputConnection?.let { connection ->
                    connection.sendKeyEvent(event)
                    connection.sendKeyEvent(KeyEvent.changeAction(event, KeyEvent.ACTION_UP))
                }
            }
            key == "enter" -> withEditor {
                val options = service.currentInputEditorInfo?.imeOptions ?: 0
                val action = options and EditorInfo.IME_MASK_ACTION
                if (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 && action in setOf(
                        EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
                        EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_PREVIOUS)) {
                    service.currentInputConnection?.performEditorAction(action)
                } else service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
            key == "clear_all" -> withEditor {
                // Never read or retain a password in the clear/undo buffer.
                service.lastClearedText = ""
                service.lastClearedDocument = null
                service.currentInputConnection?.let { connection ->
                    if (com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(service).getBoolean(
                            com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.CLEAR_BEFORE_CURSOR_ONLY_KEY, false)) {
                        // 「只清空光标前的内容」：不读密码，删掉选中的和光标前的，光标后的留着。
                        connection.beginBatchEdit()
                        try {
                            connection.commitText("", 1)
                            connection.deleteSurroundingText(XimeInputMethodService.SAFE_TEXT_LIMIT, 0)
                        } finally {
                            connection.endBatchEdit()
                        }
                    } else if (connection.performContextMenuAction(android.R.id.selectAll)) connection.commitText("", 1)
                }
            }
            key == "undo_clear" || key == "clear_composition" || key == "emoji" -> Unit
            else -> {
                val literal = when (key) {
                    "space" -> " "
                    "word_separator" -> "'"
                    else -> if (key.length == 1) { if (shifted) key.uppercase() else key } else ""
                }
                commitDirectFieldLiteral(literal)
            }
        }
        withEditor {
            service.predictionManager.invalidatePendingPredictions()
            service.candidateState.value = CandidateState()
        }
        return true
    }

    /** Not a paste API: only a keyboard key or explicit keyboard-symbol gesture uses it. */
    internal suspend fun commitDirectFieldLiteral(text: String): Boolean = withEditor {
        if (!usesDirectFieldInput) return@withEditor false
        val literal = service.editorInputPolicy.field.typedLiteral(text)
        if (literal.isEmpty()) return@withEditor true
        service.predictionManager.invalidatePendingPredictions()
        service.candidateState.value = CandidateState()
        if (service.editorInputPolicy.field.kind in keyEventFieldKinds) {
            // PIN views in secure apps (e.g. Samsung Secure Folder, bank SDKs) ignore commitText and
            // only handle key events. sendKeyChar sends digits as KEYCODE_0..9, as stock IMEs do,
            // and still commits any other character as text.
            if (service.currentInputConnection == null) return@withEditor false
            literal.forEach(service::sendKeyChar)
            return@withEditor true
        }
        service.currentInputConnection?.commitText(literal, 1) == true
    }

    private val keyEventFieldKinds = setOf(EditorFieldPolicy.Kind.NUMBER_PASSWORD, EditorFieldPolicy.Kind.NUMBER,
        EditorFieldPolicy.Kind.PHONE, EditorFieldPolicy.Kind.PASSWORD)

    internal fun handleHardwareCandidateKey(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && hasPendingCandidateCommit) {
            cancelRejectedCommit()
            return true
        }
        if (service.inputReadiness.ticket() == null) {
            // Editor navigation does not require a decoder. Esc must never become
            // an Android window-dismiss key, even while the engine is starting.
            if (isHardwareArrow(keyCode)) {
                service.schemaController.moveHardwareCursor(event)
                return true
            }
            return keyCode == KeyEvent.KEYCODE_ESCAPE
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            val state = service.candidateState.value
            val composing = state.isComposing || state.inputText.isNotEmpty() || state.preeditText.isNotEmpty() ||
                service.t9PartialSegments.isNotEmpty()
            if (!composing) return false
        }
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "hardware_candidate_left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "hardware_candidate_right"
            KeyEvent.KEYCODE_DPAD_UP -> "hardware_candidate_up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "hardware_candidate_down"
            KeyEvent.KEYCODE_ESCAPE -> "hardware_candidate_cancel"
            KeyEvent.KEYCODE_SPACE -> "space"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
            else -> if (hardwareCandidateDigitIndex(keyCode, event.isShiftPressed) != null)
                keyCodeToKey(keyCode, false) ?: return false else return false
        }
        handleKeyPress(key, event.isShiftPressed, hardwareEvent = KeyEvent(event))
        return true
    }

    internal fun releaseHardwareCandidateKey(keyCode: Int) {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> HardwareCandidateKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> HardwareCandidateKey.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> HardwareCandidateKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> HardwareCandidateKey.DOWN
            else -> return
        }
        postRimeJob { hardwareNavigation.releasePress(key) }
    }

    /** Touch arrow buttons navigate candidates; physical arrows always edit the text. */
    internal fun moveHardwareCandidate(direction: Int) {
        val code = if (direction < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        postRimeJob {
            if (!hasPendingCandidateCommit) {
                processHardwareCandidateKey(KeyEvent(KeyEvent.ACTION_DOWN, code), pageAtBoundary = true)
            }
            hardwareNavigation.releasePress(if (direction < 0) HardwareCandidateKey.LEFT else HardwareCandidateKey.RIGHT)
        }
    }

    internal fun hardwareCandidateHighlight(
        state: CandidateState,
        english: Boolean,
        predictionPending: Boolean = false,
    ): Int {
        return hardwareCandidateSelection(state, english, predictionPending) ?: 0
    }

    internal fun hardwareCandidateSelection(
        state: CandidateState, english: Boolean, predictionPending: Boolean = false,
    ): Int? {
        val snapshot = hardwareCandidateSnapshot(state, english, predictionPending)
        return hardwareSelectionRevision.intValue.let { hardwareNavigation.selectedIndex(snapshot) }
    }

    internal fun resetHardwareCandidateSelection() {
        postRimeJob {
            hardwareNavigation.clear()
            withEditor { publishHardwareCandidateSelection() }
        }
    }

    internal fun publishHardwareCandidateSelection() {
        hardwareSelectionRevision.intValue++
    }

    internal fun hardwareCandidateSnapshot(
        state: CandidateState,
        english: Boolean,
        predictionPending: Boolean,
    ) = HardwareCandidateSnapshot(service.uiState.value.inputSessionId, service.uiState.value.inputProfile,
        state, english, predictionPending, service.predictionManager.lastCommittedText,
        associationLimit = service.hardwareAssociationLimit(),
        expanded = !service.uiState.value.isCompact && service.keyboardViewModel.candidatePageExpanded.value,
        singleCharOnly = service.keyboardViewModel.singleCharFilter.value,
        windowStart = if (service.uiState.value.isCompact) hardwareNavigation.windowStart(state) else 0)

    /** First engine-page index the physical-keyboard toolbar shows; read in composition, so window moves redraw it. */
    internal fun hardwareWindowStart(state: CandidateState): Int = hardwareSelectionRevision.intValue.let {
        if (service.uiState.value.isCompact) hardwareNavigation.windowStart(state) else 0
    }

    @Volatile private var pendingCandidateCommit: PendingCandidateCommit? = null
    internal val hasPendingCandidateCommit: Boolean get() = pendingCandidateCommit != null

    internal fun discardPendingCandidateCommit() {
        pendingCandidateCommit = null
        service.uiState.value = service.uiState.value.copy(rejectedCommitText = null)
    }

    internal fun retryRejectedCommit() {
        val pending = pendingCandidateCommit ?: return
        if (!service.inputReadiness.accepts(pending.owner.admission) ||
            service.uiState.value.inputSessionId != pending.owner.editor) {
            discardPendingCandidateCommit()
            return
        }
        pending.owner.runInline {
            postRimeJob {
                if (pendingCandidateCommit === pending) deliverCandidateCommit(pending)
            }
        }
    }

    internal fun cancelRejectedCommit() {
        val pending = pendingCandidateCommit ?: return
        if (!service.inputReadiness.accepts(pending.owner.admission) ||
            service.uiState.value.inputSessionId != pending.owner.editor) {
            discardPendingCandidateCommit()
            return
        }
        pending.owner.runInline {
            postRimeJob {
                if (pendingCandidateCommit !== pending) return@postRimeJob
                clearInputStateForKeys()
                withEditor { discardPendingCandidateCommit() }
            }
        }
    }

    private fun commandOwner(admission: Long): InputCommandOwner {
        val editor = service.uiState.value.inputSessionId
        return InputCommandOwner(admission, editor) {
            if (!service.inputReadiness.accepts(admission) || service.uiState.value.inputSessionId != editor) {
                throw kotlinx.coroutines.CancellationException("Expired input command")
            }
        }
    }

    /** Called at the Main user-operation boundary, before work enters the input queue. */
    internal fun captureInputContext(): kotlin.coroutines.CoroutineContext {
        val inherited = InputCommandOwner.current.get()
        if (inherited != null) return inherited.context()
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val admission = service.inputReadiness.ticket()
            ?: throw kotlinx.coroutines.CancellationException("Input is not ready")
        return commandOwner(admission).context()
    }

    internal suspend fun <T> withEditor(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.Main) {
            InputCommandOwner.requireOwner().requireCurrent(service.inputReadiness, service.uiState.value.inputSessionId)
            block()
        }

    private fun publishUi(block: suspend () -> Unit) {
        val owner = InputCommandOwner.requireOwner()
        service.uiEventChannel.trySend {
            if (service.inputReadiness.accepts(owner.admission) && service.uiState.value.inputSessionId == owner.editor) {
                KeyLatencyProbe.handed()
                withContext(owner.context()) { block() }
                KeyLatencyProbe.applied()
            }
        }
    }

    /**
     * 候选词变换（hotPath 插件能力）+ 发送 UI 更新。
     * 必须在 key-processing 线程调用：同步等插件至多 15ms；
     * 超时/失败/插件不干预（null）均回退原始候选（actions 为空 = 纯引擎语义）。
     */
    internal fun sendTransformedResult(
        result: com.kingzcheung.xime.rime.RimeProcessResult,
        afterUpdate: (suspend () -> Unit)? = null,
    ) {
        // 日语罗马音的显示归一化收口在 updateUIWithResult（刷新路径同样生效），此处不再单独加工
        service.rimeEngine.setSpatialMap(spatialInput.afterInput(result.inputText))
        // The property written above matches the spelling just stored. The next letter's
        // prediction is published immediately before that key is processed.
        val transformed = service.candidateTransform.transformFor(result)
        KeyLatencyProbe.posted(result.inputText.length)
        publishUi {
            if (!service.rimeEngine.isCandidateRevisionCurrent(result.engineRevision)) return@publishUi
            service.sessionController.updateUIWithResult(
                transformed?.let { result.copy(candidates = it.candidates.toTypedArray()) } ?: result,
                transformed?.actions ?: emptyList()
            )
            if (afterUpdate != null) afterUpdate()
        }
    }

    /**
     * Measured touch penalties for one spelling letter. Neighbour correction is already on by
     * default; an empty sample (glide, hardware, a tap before the keys are measured) keeps the
     * flat penalty. The property has to be in place before [RimeEngine.processQueuedKeyAndGetResult].
     */
    private fun publishSpatialTap(key: String) {
        val letter = key.lowercase().singleOrNull()?.takeIf { it in 'a'..'z' } ?: return
        val neighbors = letterNeighbors
        if (neighbors.second.isEmpty() || neighbors.first != service.uiState.value.currentSchemaId) {
            SpatialTaps.poll(letter)
            return
        }
        service.rimeEngine.setSpatialMap(spatialInput.beforeAppend(letter, SpatialTaps.poll(letter) ?: emptyMap()))
    }

    /** 英文选词后自动补的空格（默认关），只管紧接着的一个键，见 [EnglishAutoSpace]。 */
    internal val englishAutoSpace = EnglishAutoSpace()

    /**
     * 紧跟自动空格的键：空格不再多打，句末标点放到空格前面，右括号去掉空格；返回 true 表示这个键已处理完。
     * 其他键、或光标前已不是刚上屏的“词+空格”时返回 false，键照常处理、空格保留。
     */
    internal suspend fun consumeEnglishAutoSpace(key: String): Boolean {
        if (EnglishAutoSpace.isNeutral(key)) return false
        val armed = englishAutoSpace.take() ?: return false
        val replacement = EnglishAutoSpace.replacementFor(key) ?: return false
        return withEditor {
            if (service.uiState.value.inputTarget.isInternal) return@withEditor false
            val ic = service.currentInputConnection ?: return@withEditor false
            if (!ic.getSelectedText(0).isNullOrEmpty()) return@withEditor false
            val before = runCatching { ic.getTextBeforeCursor(armed.length, 0)?.toString() }.getOrNull()
            if (before != armed) return@withEditor false
            if (replacement.isEmpty()) return@withEditor true
            ic.beginBatchEdit()
            try {
                if (!ic.deleteSurroundingText(1, 0)) return@withEditor false
                // 宿主不收标点就把空格放回去，什么都不丢。
                if (!service.commitTextAndPredict(replacement, isPaste = false, allowPrediction = false).accepted) ic.commitText(" ", 1)
                // 开着「标点后自动加空格」时，换到标点后面的空格同样只管下一个键：连打 ... ?! 空格一直留在最后。
                else if (replacement.length == 2 && replacement[1] == ' ' && englishPunctuationSpaceEnabled())
                    englishAutoSpace.arm(replacement.substring(0, 1))
            } finally {
                ic.endBatchEdit()
            }
            service.candidateState.value = service.candidateState.value.copy(
                pendingEnglishText = "", associationCandidates = emptyList())
            true
        }
    }

    private fun englishPunctuationSpaceEnabled(): Boolean =
        SettingsPreferences.getPrefsPublic(service).getBoolean(EnglishAutoSpace.PUNCTUATION_PREF_KEY, false)

    /**
     * 英文标点后自动加空格（默认关）：英文、西班牙语屏幕键盘在字母后面打 , . ? ! : ; 时上屏“标点+空格”，
     * 补的空格按 [EnglishAutoSpace] 的规则只管下一个键。返回 true 表示已上屏；
     * 中文、日文、实体键盘、数字键盘、网址/邮箱/密码/数字框、终端、有选区或在组字时都返回 false，照常处理。
     */
    internal suspend fun commitEnglishPunctuationSpace(key: String): Boolean {
        if (key.length != 1 || !englishPunctuationSpaceEnabled()) return false
        return withEditor {
            val ui = service.uiState.value
            if (ui.inputTarget.isInternal || !(ui.isAsciiMode || ui.inputProfile.language.typesLettersDirectly)) return@withEditor false
            if (service.textCommit.isJapaneseProfile()) return@withEditor false
            if (service.editorInputPolicy.field.scoped || service.isSecretEditor() || service.isTerminalEditor()) return@withEditor false
            if (service.keyboardViewModel.keyboardState.value is KeyboardLayoutState.Number) return@withEditor false
            val candidates = service.candidateState.value
            if (candidates.isComposing || candidates.inputText.isNotEmpty() || service.t9PartialSegments.isNotEmpty()) return@withEditor false
            val ic = service.currentInputConnection ?: return@withEditor false
            if (!ic.getSelectedText(0).isNullOrEmpty()) return@withEditor false
            val before = runCatching { ic.getTextBeforeCursor(1, 0) }.getOrNull()
            val after = runCatching { ic.getTextAfterCursor(1, 0) }.getOrNull()
            if (!EnglishAutoSpace.spacesAfterPunctuation(key, before, after)) return@withEditor false
            if (!service.commitTextAndPredict("$key ", isPaste = false, allowPrediction = false).accepted) return@withEditor false
            service.candidateState.value = service.candidateState.value.copy(
                pendingEnglishText = "", associationCandidates = emptyList())
            englishAutoSpace.arm(key)
            true
        }
    }

    /** 紧挨着的上一笔英文滑行（候选、大小写），连着划时连同它一起选；屏上不再紧跟它就作废。 */
    private var lastGlide: com.kingzcheung.xime.glide.PreviousGlide? = null

    /**
     * 英文滑行整词上屏：与逐字母英文输入同一提交边界。前面紧挨文字时补空格；
     * 按光标前的词选这一笔的词，紧挨着上一笔滑行时前后两个词一起选，必要时先改掉上一个词（I'm done）。
     * 上屏后记为当前英文词，其余候选沿用英文候选的回删替换；整对候选连上一个词一起替换。
     */
    internal fun commitGlideWord(candidates: List<com.kingzcheung.xime.glide.GlideCandidate>, case: (String) -> String) {
        if (candidates.isEmpty() || hasPendingCandidateCommit) return
        postRimeJob {
            withEditor {
                if (!service.uiState.value.isAsciiMode) return@withEditor
                service.predictionManager.invalidatePendingPredictions()
                val state = service.candidateState.value
                val textBefore = service.currentInputConnection?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
                val replaceSupported = service.supportsEnglishCandidateReplace()
                val previous = lastGlide?.takeIf {
                    replaceSupported && state.glidedWord.isNotEmpty() && it.text == state.glidedWord &&
                        state.pendingEnglishText.endsWith(state.glidedWord) && textBefore.endsWith(state.glidedWord)
                }
                fun planFor(previous: com.kingzcheung.xime.glide.PreviousGlide?) = com.kingzcheung.xime.glide.planGlideCommit(
                    candidates, case, previous,
                    com.kingzcheung.xime.glide.glideContextWord(if (previous != null) textBefore.dropLast(previous.text.length) else textBefore),
                    com.kingzcheung.xime.glide.GlideLexicon.loadedBigrams(), com.kingzcheung.xime.glide.GlideLexicon.loadedContractions())
                var plan = planFor(previous)
                val head = if (previous == null) null else plan.replacePrevious?.let { replacement ->
                    if (service.replaceBeforeCursor(previous.text, replacement)) replacement else null
                } ?: previous?.text
                // 换上一个词没成功（宿主拒绝）就只选这一笔，不留整对候选。
                if (previous != null && plan.replacePrevious != null && head == previous.text) plan = planFor(null)
                val word = plan.word
                val before = service.currentInputConnection?.getTextBeforeCursor(1, 0)?.lastOrNull()
                val text = com.kingzcheung.xime.glide.glideCommitText(word, before)
                if (!service.commitTextAndPredict(text, isPaste = false, allowPrediction = false).accepted) return@withEditor
                lastGlide = com.kingzcheung.xime.glide.PreviousGlide(word, candidates.take(com.kingzcheung.xime.glide.PAIR_TOP), case)
                service.candidateState.value = state.copy(
                    inputText = "", preeditText = "", isComposing = false,
                    candidates = emptyList(), candidateComments = emptyList(), candidateActions = emptyList(),
                    pendingEnglishText = if (plan.pair && head != null) head + text else word, glidedWord = word,
                    associationCandidates = if (service.isSecretEditor()) emptyList() else plan.alternatives,
                    englishReplaceSupported = replaceSupported,
                    hasNextPage = false, hasPrevPage = false, isShowingRecentClipboard = false,
                )
            }
        }
    }

    /**
     * 中文滑行：一个词的编码按点按同一路径逐键送进 Rime，已打的拼音、分段选词、邻键纠错和候选刷新都不变。
     * 接在未上屏的编码后面时先补音节分隔符（xi 后面划 an 是 xi'an，不会并成 xian）。
     */
    internal fun typeGlideCode(code: String) {
        if (code.isEmpty() || hasPendingCandidateCommit) return
        val input = service.candidateState.value.inputText
        if (input.isNotEmpty() && input.last() != '\'') handleKeyPress("'", false)
        code.forEach { handleKeyPress(it.toString(), false) }
    }

    /** 当前方案把分号当编码键：微软双拼的 ing，或自建双拼把某个韵母放在分号上（[DoublePinyinScheme.usesSemicolon]）。 */
    internal fun semicolonIsCode(): Boolean = service.uiState.value.let {
        !it.isAsciiMode && com.kingzcheung.xime.settings.DoublePinyinSchemes.find(it.currentSchemaId)?.usesSemicolon == true
    }

    internal fun handleKeyPress(key: String, isShifted: Boolean, hardwarePageDirection: Int = 0,
        languageSwitchMode: com.kingzcheung.xime.settings.LanguageSwitchMode? = null,
        hardwareEvent: KeyEvent? = null, mergedKey: Boolean = false) {
        if (hasPendingCandidateCommit) return
        val inherited = InputCommandOwner.current.get()
        if (inherited != null) {
            inherited.requireCurrent(service.inputReadiness, service.uiState.value.inputSessionId)
            routeKeyPress(key, isShifted, hardwarePageDirection, languageSwitchMode, hardwareEvent, mergedKey)
            return
        }
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "Only a direct Main input entry may create command ownership"
        }
        val admission = service.inputReadiness.ticket() ?: return
        // 实体键盘 Tab：手写板上还没确认的字先排队上屏，再处理 Tab（否则 Tab 换了输入框，这些字就丢了）
        if (hardwareEvent?.keyCode == KeyEvent.KEYCODE_TAB) service.keyboardViewModel.confirmPendingHandwriting?.invoke()
        commandOwner(admission).runInline { routeKeyPress(key, isShifted, hardwarePageDirection, languageSwitchMode, hardwareEvent, mergedKey) }
    }

    private fun routeKeyPress(key: String, isShifted: Boolean, hardwarePageDirection: Int,
        languageSwitchMode: com.kingzcheung.xime.settings.LanguageSwitchMode?, hardwareEvent: KeyEvent?, mergedKey: Boolean) {
        // 空键无任何按键语义，且下游 Rime 路由按 key[0] 取码（key.lowercase()[0]），
        // 空串会越界崩溃（2026-09-14 真机实证：滑动手势 commit 值为空时触发）。
        if (key.isEmpty()) return
        val admission = service.inputReadiness.ticket() ?: return
        // Capture what Space meant at touch time; a later Next tap must not change this queued commit.
        val spaceSnapshot = service.candidateState.value.takeIf {
            hardwareEvent == null && key == "space" &&
                (it.candidates.isNotEmpty() || it.focusedT9AssociationIndex != null) &&
                it.usesT9CandidateNavigation(service.uiState.value.inputProfile)
        }
        // A language key does not edit text. Keep an outstanding voice final while the
        // language observer decides whether this is the same native/English pair.
        if (key != "ime_switch") service.voiceRecognitionHandler.abandonPendingOnManualInput()
        if (service.uiState.value.toolPanelInputFocused) {
            val candState = service.candidateState.value
            val hasComposing = candState.isComposing || candState.inputText.isNotEmpty()
            when (key) {
                "enter" -> {
                    if (hasComposing) {
                        // 组合态：先把拼音提交进面板输入框，再触发生成。剪贴板搜索提交拼音首字母，
                        // 与边打边筛同一口径（"di'zhi" → dz）。
                        // 编码以屏上显示的为准（九键高亮换了就是高亮候选的拼音；九键、双字母键不交原始键码）。
                        val profile = service.uiState.value.inputProfile
                        val shown = candState.shownPreedit(profile)
                        val input = if (service.uiState.value.clipboardSearchVisible)
                            com.kingzcheung.xime.clipboard.ClipboardSearch.composingInitials(shown,
                                com.kingzcheung.xime.clipboard.AndroidPinyinInitials::initialOf)
                        else rawCompositionCommitText(candState.inputText, shown,
                            isT9Schema(service.uiState.value.currentSchemaId), usesMergedLetterKeys(profile.layout))
                        service.mainHandler.post {
                            if (!service.inputReadiness.accepts(admission)) return@post
                            ToolPanelEditTextHolder.editText?.let { et ->
                                val start = et.selectionStart.coerceAtLeast(0)
                                et.text?.replace(start, et.selectionEnd.coerceAtLeast(start), input)
                                try { et.setSelection(start + input.length) } catch (_: Exception) {}
                            }
                            service.rimeEngine.endComposition(isT9Schema(service.uiState.value.currentSchemaId))
                            service.candidateState.value = service.candidateState.value.copy(
                                inputText = "",
                                preeditText = "",
                                pendingEnglishText = "",
                                candidates = emptyList(),
                                candidateComments = emptyList(),
                                associationCandidates = emptyList(),
                                isComposing = false,
                                candidateActions = emptyList()
                            )
                        }
                    }
                    // 排在上面写入拼音的 post 之后：生成要带上最后这段还没上屏的编码（审计 F08）。
                    if (service.uiState.value.clipboardSearchVisible) service.mainHandler.post { service.submitClipboardSearch() }
                    else service.mainHandler.post {
                        if (service.inputReadiness.accepts(admission)) service.triggerToolPanelGenerate()
                    }
                    return
                }
                "delete" -> {
                    if (hasComposing) {
                        // 组合态：退格走 Rime，更新候选栏
                        service.rimeEngine.processKey(0xff08, 0)
                        val result = service.rimeEngine.getProcessResult(true)
                        if (result.inputText.isEmpty()) {
                            service.rimeEngine.clearComposition()
                        }
                        sendTransformedResult(result)
                    } else {
                        deleteInToolPanelField()
                    }
                    return
                }
                "space" -> {
                    if (hardwareEvent == null) {
                        if (hasComposing && candState.candidates.isNotEmpty()) {
                            val snapshot = spaceSnapshot ?: candState
                            val index = snapshot.spaceCandidateIndex(service.uiState.value.inputProfile)
                            postRimeJob { selectCandidateAsync(index, snapshot = snapshot) }
                        } else {
                            ToolPanelEditTextHolder.editText?.let { et ->
                                val start = et.selectionStart.coerceAtLeast(0)
                                et.text?.insert(start, service.textCommit.keyboardLiteral(" "))
                                try { et.setSelection(start + 1) } catch (_: Exception) {}
                            }
                        }
                        return
                    }
                }
                else -> {
                    // The clipboard search field is one line: a hardware Tab (or other control
                    // character) has nothing to insert there.
                    if (key.length == 1 && key[0].isISOControl() && service.uiState.value.clipboardSearchVisible) return
                    if (key.length == 1) {
                        val isLetter = key.matches(Regex("[a-zA-Z]"))
                        val isChineseMode = !service.uiState.value.isAsciiMode
                        // 微软双拼的分号：打字中和字母一样进 Rime（ing），没在打字时照旧按标点上屏。
                        val semicolonCode = key == ";" && semicolonIsCode()
                        if (semicolonCode && !hasComposing && hardwareEvent == null) {
                            service.textCommit.commitLiteralText(key)
                            return
                        }
                        if ((isLetter || semicolonCode && hasComposing) && isChineseMode) {
                            // 中文模式：字母进 Rime 拼音组合，候选栏选词后经 commitText 重定向进面板输入框
                            val keyCode = if (mergedKey) service.rimeEngine.mergedLetterKeyCode(key[0]) else key.lowercase()[0].code
                            publishSpatialTap(key)
                            val result = service.rimeEngine.processKeyAndGetResult(keyCode, 0)
                            if (result.processed) {
                                sendTransformedResult(result)
                            }
                            return
                        }
                        val char = if (isShifted) key.uppercase() else key
                        ToolPanelEditTextHolder.editText?.let { et ->
                            val start = et.selectionStart.coerceAtLeast(0)
                            et.text?.insert(start, char)
                            try { et.setSelection(start + char.length) } catch (_: Exception) {}
                        }
                        return
                    }
                }
            }
        }
        // Enter edits this draft; saving is exclusively an explicit button action.
        if (service.uiState.value.showQuickSendForm) {
            when (key) {
                "enter" -> {
                    val editor = QuickSendFormEditTextHolder.editText ?: return
                    val wasCode = service.uiState.value.quickSendCodeFocused
                    editor.requestFocus()
                    // The single-line shortcut code uses Enter to return to the text editor.
                    if (wasCode) return
                }
                "delete" -> {
                    val candState = service.candidateState.value
                    val isComposing = candState.isComposing || candState.inputText.isNotEmpty()
                    if (isComposing) {
                        // Rime 有组合态 → 转发退格到 Rime 清空候选字母/联想词
                        service.rimeEngine.processKey(0xff08, 0)
                        val result = service.rimeEngine.getProcessResult(true)
                        if (result.inputText.isEmpty()) {
                            service.rimeEngine.clearComposition()
                        }
                        sendTransformedResult(result)
                    } else {
                        // 无组合态 → 按焦点路由删除表单内（文本框/触发编码框）已上屏文字
                        service.deleteInQuickSendForm()
                    }
                    return
                }
            }
        }
        // 长按只允许一个待处理重复；每次独立点击仍按原始顺序执行。
        if (key == "delete") {
            handleDeleteKey()
            return
        }
        // 只记按键类别，不记具体字符。
        val latencyKind = when {
            key == "space" -> "space"
            key == "enter" -> "enter"
            key.length == 1 && key[0].isLetter() -> "letter"
            key.length == 1 && key[0].isDigit() -> "digit"
            else -> "key"
        }
        if (latencyKind == "letter" && hardwareEvent == null) KeyLatencyProbe.fired(service.uiState.value.inputProfile.layout.id)
        service.inputCommands.submit(InputCommandOwner.requireOwner().context(), kind = latencyKind) command@{
            KeyLatencyProbe.started()
            InputCommandOwner.requireOwner().requireCurrent(service.inputReadiness, service.uiState.value.inputSessionId)
            if (!service.inputReadiness.accepts(admission) || hasPendingCandidateCommit) return@command
            if (usesDirectFieldInput && routeDirectFieldKey(key, isShifted, hardwareEvent)) return@command
            // 分号是编码键的方案（微软双拼）：没在打字时点屏幕上的分号键仍按标点上屏，和以前一样；打字中往下送进 Rime 组成 ing。
            if (hardwareEvent == null && key == ";" && semicolonIsCode() && service.t9PartialSegments.isEmpty() &&
                service.rimeEngine.readQueuedComposition()?.input.isNullOrEmpty()) {
                service.textCommit.literalSubmission(key)?.invoke()
                return@command
            }
            if (englishAutoSpace.isArmed && consumeEnglishAutoSpace(key)) return@command
            if (hardwareEvent != null) {
                // Idle Tab belongs to the editor: single-line fields move focus (Shift+Tab backwards),
                // multi-line ones insert a tab. Only an unfinished composition keeps Tab in the IME.
                if (hardwareEvent.keyCode == KeyEvent.KEYCODE_TAB && service.t9PartialSegments.isEmpty() &&
                    service.rimeEngine.readQueuedComposition()?.input.isNullOrEmpty()) {
                    withEditor {
                        service.predictionManager.invalidatePendingPredictions()
                        service.candidateState.value = service.candidateState.value.copy(
                            pendingEnglishText = "", associationCandidates = emptyList())
                        service.currentInputConnection?.let { connection ->
                            connection.sendKeyEvent(hardwareEvent)
                            connection.sendKeyEvent(KeyEvent.changeAction(hardwareEvent, KeyEvent.ACTION_UP))
                        }
                    }
                    return@command
                }
                if (hardwareEvent.keyCode == KeyEvent.KEYCODE_TAB) {
                    // 组字时 Tab 换到下一个候选、Shift+Tab 换回上一个，和 Windows 版同一规则（用户 10-21）；
                    // 走候选栏左右按钮那条路（不移动拼音里的光标）。没在组字时上面已把 Tab 交给编辑框跳输入框。
                    val direction = if (hardwareEvent.isShiftPressed) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                    processHardwareCandidateKey(KeyEvent(KeyEvent.ACTION_DOWN, direction), pageAtBoundary = true)
                    hardwareNavigation.releasePress(if (hardwareEvent.isShiftPressed) HardwareCandidateKey.LEFT else HardwareCandidateKey.RIGHT)
                    return@command
                }
                if (processHardwareCandidateKey(hardwareEvent)) return@command
                if (hardwareCandidateDigitIndex(hardwareEvent.keyCode, hardwareEvent.isShiftPressed) != null) {
                    // DefaultInput here means no numbered candidate owns this digit.
                    // Never send it back into the active T9 decoder as a new spelling.
                    commitHardwareLiteral(key)
                    return@command
                }
                if (key.length == 1 && (hardwareEvent.isShiftPressed && !key[0].isLetter() ||
                        hardwareEvent.keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_EQUALS)) {
                    commitHardwareLiteral(key)
                    return@command
                }
                // 打拼音时不按 Shift 的 ; / \ 等：先把字母原样上屏（不上屏首选，Gboard 规则，用户 2026-10-24），
                // 再按空闲时的标点规则出符号。翻页键另有入口，拼音分隔符 ' 照旧。
                // 分号是编码键的方案（微软双拼）分号照样送进 Rime：打字中是 ing，没在打字时由 Rime 出标点。
                if (hardwarePageDirection == 0 && key.length == 1 && key != "'" && !(key == ";" && semicolonIsCode()) &&
                    isLiteralPunctuation(key) &&
                    !service.uiState.value.isAsciiMode &&
                    service.uiState.value.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE &&
                    (service.rimeEngine.readQueuedComposition()?.input?.isNotEmpty() == true ||
                        service.t9PartialSegments.isNotEmpty()) && !commitHardwareLiteral("")) return@command
            } else {
                hardwareNavigation.clear()
            }
            if (hardwarePageDirection != 0 &&
                (service.rimeEngine.compositionActiveForDeletion() != false || service.t9PartialSegments.isNotEmpty())) {
                // Consume even at either boundary or when decoding has no candidates yet.
                // Never turn a reserved paging key into punctuation in an unfinished composition.
                if (service.keyboardViewModel.candidatePageExpanded.value) {
                    withEditor { service.expandedPageScroll(hardwarePageDirection) }
                } else {
                    pageHardwareCandidates(service.candidateState.value, hardwarePageDirection)
                    withEditor { service.updateUI() }
                }
                withEditor { publishHardwareCandidateSelection() }
                return@command
            }
            val english = service.uiState.value.let {
                it.isAsciiMode || it.inputProfile.language.typesLettersDirectly
            }
            // 西班牙语键盘上 ñ、á 这些也是字母：同样逐字上屏、参与补全。
            val spanish = service.uiState.value.let {
                !it.isAsciiMode && it.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.SPANISH
            }
            if (english && isDirectLetter(key, spanish)) {
                val next = withEditor {
                    // Clear and commit in the same Main visit, including when the
                    // previous letter already has completions on screen.
                    service.predictionManager.invalidatePendingPredictions()
                    service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList())
                    val current = service.candidateState.value
                    // 滑行出词后直接点字母：光标仍紧跟那个词时自动补空格；空格、删除或标点之后不补。
                    // 连着划两个词时当前英文词是整对（I'm done），光标仍紧跟这一笔的词就算。
                    val afterGlide = current.glidedWord.isNotEmpty() && current.pendingEnglishText.endsWith(current.glidedWord) &&
                        service.currentInputConnection?.getTextBeforeCursor(current.glidedWord.length, 0)
                            ?.toString() == current.glidedWord
                    // Japanese 英数 follows 全角／半角, so full-width letters can be typed directly.
                    val fullWidthLetter = service.textCommit.isJapaneseProfile() && service.textCommit.isFullWidthPunctuation()
                    val next = commitEnglishLetter(current, key, isShifted,
                        service::supportsEnglishCandidateReplace, afterGlide = afterGlide) {
                        val letter = if (fullWidthLetter) fullWidthLetters(it) else it
                        service.commitTextAndPredict(letter, isPaste = false, allowPrediction = false).accepted
                    }
                    if (next != null) service.candidateState.value = next
                    next
                }
                if (next != null && !service.isSecretEditor() && next.englishReplaceSupported) {
                    val pending = next.pendingEnglishText
                    service.serviceScope.launch(InputCommandOwner.requireOwner().context()) {
                        val words = service.predictionManager.getEnglishAssociations(pending)
                        withEditor {
                            val current = service.candidateState.value
                            if (current.pendingEnglishText == pending && service.uiState.value.let {
                                    it.isAsciiMode || it.inputProfile.language.typesLettersDirectly }) {
                                service.candidateState.value = current.copy(associationCandidates = words)
                            }
                        }
                    }
                }
                return@command
            }
            // Most composing keys have nothing to clear. Do not make every key wait
            // for a Main round trip before it can even reach the decoder.
            if (key.length == 1) {
                val hadPendingPrediction = service.predictionManager.hasPendingPrediction
                // Also invalidate not-yet-started prediction callbacks. Checking only
                // isPending would let a previous commit post a prediction after this key.
                service.predictionManager.invalidatePendingPredictions()
                if (hadPendingPrediction || service.candidateState.value.associationCandidates.isNotEmpty()) {
                    withEditor {
                        service.predictionManager.invalidatePendingPredictions()
                        service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList())
                    }
                }
            }
            // Literal English input has no decoder work and must not wait for its lock.
            // 邻键纠错只对触屏有意义：物理键盘按键是精确的，不做邻键纠错。
            val geometry = letterNeighbors
            val neighborMap = if (hardwareEvent == null && geometry.first == service.uiState.value.currentSchemaId) geometry.second else ""
            service.rimeEngine.setNeighborMap(neighborMap)
            if (neighborMap.isEmpty()) {
                spatialInput.clear()
                SpatialTaps.clear()
                service.rimeEngine.setSpatialMap("")
            }
            // 设置页的候选开关（中英混输、繁体输出、时间日期、表情、符号名、生僻字）：设置一变已由
            // XimeInputMethodService 的设置监听立刻写进引擎；Rime 选项随会话重建而清零，按键前再补一次。
            // 只在变了时写，免得每键触发选项通知；九键数字键也走这里。
            if (key.length == 1 && key[0].isLetterOrDigit()) service.sessionController.applyCandidateOptions()
            if (service.japaneseInputController.handleKey(key)) return@command
            val state = service.uiState.value
            val candState = service.candidateState.value
            var needsUIUpdate = false
            var pendingResult: com.kingzcheung.xime.rime.RimeProcessResult? = null
            var committedText: String? = null

            if (!state.isAsciiMode && state.inputProfile.scheme == com.kingzcheung.xime.settings.InputScheme.ZHUYIN &&
                routeZhuyinKey(key, hardwareEvent)) return@command
            
            when (key) {
                "clear_composition" -> {
                    // 只清输入态（预编辑/候选/联想/partial 累积/计算器/左栏），不动已上屏文本。
                    // 清理逻辑见 clearInputStateForKeys()（与 clear_all 输入态分支共用）。
                    clearInputStateForKeys()
                    needsUIUpdate = true
                }
                "clear_all" -> {
                    // 上滑清空 = 多次退格快捷方式（对标主流输入法）：输入态只清输入态，空闲态清空全部已上屏。
                    // 引擎繁忙时不把未知状态当成空闲，更不能清正文。
                    if (service.rimeEngine.compositionActiveForDeletion() == null) return@command
                    // 「打字中清空整个输入框」（默认关）：打字中也直接清整个输入框，没上屏的拼音丢掉，撤回只放回输入框原来的字。
                    val clearPrefs = com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(service)
                    val wholeField = clearPrefs.getBoolean(
                        com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.CLEAR_ALL_WHILE_COMPOSING_KEY, false)
                    // 「只清空光标前的内容」（默认关）：清输入框时光标后的字留着。
                    val beforeOnly = clearPrefs.getBoolean(
                        com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.CLEAR_BEFORE_CURSOR_ONLY_KEY, false)
                    val scope = ClearAllScope.of(hasInputState(candState), wholeField)
                    if (!scope.field) {
                        service.lastClearedDocument = null
                        // 输入态：只清输入态（等价于 clear_composition），并记录 lastClearedText 供下滑撤回。
                        // 需在 clearInputStateForKeys() 之前记录（该函数会清空 preeditText/inputText）。
                        val pendingEnglish = candState.pendingEnglishText
                        if (pendingEnglish.isNotEmpty()) {
                            // 直接上屏模式：英文编码已逐字落盘，"清输入态"需回删屏上对应字符；
                            // 校验光标前文本一致才删除并记录撤回，否则只清状态不动已上屏文本。
                            val removed = withEditor {
                                val ic = service.currentInputConnection ?: return@withEditor false
                                val before = runCatching {
                                    ic.getTextBeforeCursor(pendingEnglish.length, 0)?.toString()
                                }.getOrNull()
                                before == pendingEnglish && ic.deleteSurroundingText(pendingEnglish.length, 0)
                            }
                            if (removed) service.lastClearedText = pendingEnglish
                        } else {
                            // 撤回重放用 inputText（原始键入串）：preeditText 现为带回显分隔符的
                            // 展示串（如 ni'hao），上屏必须无分隔符版本。T9 时 inputText 为合成显示态，
                            // 与 preeditText 同值，行为不变。
                            service.lastClearedText = candState.inputText
                        }
                        clearInputStateForKeys()
                    } else {
                        // 直接上屏的英文已经在输入框里，跟着下面整框一起删、一起撤回，这里不单独回删。
                        // 输入框本来是空的（只有拼音）时，撤回照原来一样放回拼音。
                        val composedText = if (scope.composition && candState.pendingEnglishText.isEmpty()) candState.inputText else ""
                        if (scope.composition) clearInputStateForKeys()
                        // 空闲态：清空整个输入框（光标前、选区、光标后），记下来供下滑撤回原位恢复。
                        service.calculatorEngine.clear()
                        updateCalculatorCandidates()
                        // 清空 partial 累积，避免残留词被 buildT9DisplayState 拼进下一轮 preedit。
                        service.t9PartialSegments.clear()
                        endQueuedComposition()
                        service.candidateState.value = service.candidateState.value.copy(
                            candidates = emptyList(),
                            candidateComments = emptyList(),
                            associationCandidates = emptyList(),
                            pendingEnglishText = "",
                            inputText = "",
                            isComposing = false,
                            isShowingRecentClipboard = false,
                            candidateActions = emptyList()
                        )
                        var rejected: String? = null
                        withEditor {
                            service.lastClearedText = ""
                            service.lastClearedDocument = null
                            rejected = rejectedCommitBefore(clearing = true)
                            service.currentInputConnection?.let { ic ->
                                service.endComposingInputBox()
                                val limit = XimeInputMethodService.SAFE_TEXT_LIMIT
                                val document = ic.readDocumentForClear(limit) ?: return@let
                                service.lastClearedDocument = ic.clearDocument(document, limit,
                                    stillCurrent = { service.currentInputConnection === ic }, beforeOnly = beforeOnly)
                            }
                            if (service.lastClearedDocument == null) service.lastClearedText = composedText
                        }
                        rejected?.let(::unlearnRejectedCommit)
                    }
                    needsUIUpdate = true
                }
                "undo_clear" -> {
                    // 下滑撤回 = 撤销"上滑清空"，仅空闲态有效（输入态恢复会插入错误位置），
                    // 判定与 clear_all 共用 hasInputState()。
                    if (!hasInputState(candState)) {
                        val text = service.lastClearedText
                        val document = service.lastClearedDocument
                        if (document != null) withEditor {
                            // 整框清空的撤回：原位放回、光标回原处；清空后输入框被改过就不放（审计 F06）。
                            val ic = service.currentInputConnection ?: return@withEditor
                            if (ic.restoreDocument(document, XimeInputMethodService.SAFE_TEXT_LIMIT)) service.lastClearedDocument = null
                        } else if (text.isNotEmpty()) {
                            service.lastClearedText = ""
                            withEditor {
                                val ic = service.currentInputConnection
                                if (ic != null) {
                                    // newCursorPosition=1：光标停在撤回内容末尾；
                                    // 传 text.length 会被 clamp 到整段文本末尾。
                                    ic.commitText(text, 1)
                                }
                            }
                        }
                    }
                    needsUIUpdate = true
                }
                "enter" -> {
                    service.calculatorEngine.clear()
                    val japaneseResult = service.rimeEngine.processJapaneseEnterIfComposing()
                    if (japaneseResult != null) {
                        // 原生 Return 按日语方案提交假名读音；commit 不能经过可覆盖的 UI channel。
                        if (japaneseResult.committedText.isNotEmpty()) {
                            withEditor { service.commitText(japaneseResult.committedText) }
                        }
                        if (japaneseResult.processed || japaneseResult.committedText.isNotEmpty()) {
                            sendTransformedResult(japaneseResult)
                        }
                        // 未处理时保留输入；成功时按结果刷新，不能再 clearComposition 或重复取 commit。
                        return@command
                    }
                    val engineInput = service.rimeEngine.getInput()
                    // UI publication may lag the FIFO. Enter consumes unfinished input;
                    // only a subsequent, idle Enter may invoke the editor action.
                    // 西班牙语的字母已逐字上屏，没有可提交的输入：回车照常换行或执行动作。
                    if (engineInput.isNotEmpty() || candState.isComposing ||
                        (!state.isAsciiMode && !state.inputProfile.language.typesLettersDirectly &&
                            candState.pendingEnglishText.isNotEmpty())) {
                        // T9 和 14 键提交屏上的解码拼音（T9 含 partial commit 累积），其他用 RIME input。
                        val isT9 = isT9Schema(state.currentSchemaId)
                        val input = rawCompositionCommitText(engineInput, candState.shownPreedit(state.inputProfile), isT9,
                            usesMergedLetterKeys(state.inputProfile.layout))
                        if (input.isNotEmpty()) {
                            val accepted = withEditor { service.submitText(input).accepted }
                            if (!accepted) return@command
                            // English typed by hand (Obsidian) is offered again next time, dictionary or not.
                            if (!isT9 && isRimeIceCapitalSchema(state.currentSchemaId) &&
                                !com.kingzcheung.xime.settings.IncognitoState.active) {
                                com.kingzcheung.xime.settings.EnglishWordMemory.remember(
                                    com.kingzcheung.xime.settings.SchemaManager.getRimeDir(service), input)
                            }
                        }
                        // 同步清空，避免异步 postRimeJob 延迟导致后续 backspace 拿到旧状态。
                        if (isT9) service.t9PartialSegments.clear()
                        endQueuedComposition()
                        withEditor { service.endComposingInputBox() }
                        needsUIUpdate = true
                    } else {
                        service.rimeEngine.clearComposition()
                        withEditor {
                            val imeOptions = service.currentInputEditorInfo?.imeOptions ?: 0
                            val action = imeOptions and EditorInfo.IME_MASK_ACTION
                            val noEnterAction = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
                            when {
                                state.showQuickSendForm -> service.submitText("\n")
                                noEnterAction -> service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                                action == EditorInfo.IME_ACTION_GO ||
                                action == EditorInfo.IME_ACTION_SEARCH ||
                                action == EditorInfo.IME_ACTION_SEND ||
                                action == EditorInfo.IME_ACTION_NEXT ||
                                action == EditorInfo.IME_ACTION_DONE ->
                                    service.currentInputConnection?.performEditorAction(action)
                                else -> service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                            }
                        }
                    }
                    withEditor {
                        service.candidateState.value = service.candidateState.value.copy(
                            inputText = "",
                            preeditText = "",
                            pendingEnglishText = "",
                            candidates = emptyList(),
                            candidateComments = emptyList(),
                            associationCandidates = emptyList(),
                            isComposing = false
                        )
                        if (isT9Schema(state.currentSchemaId)) {
                            service.keyboardCallbacks?.onT9CompositionCleared?.invoke()
                            service.uiState.value = service.uiState.value.copy(
                                t9RightCandidateSelectedCount = 0,
                                t9SelectedCandidatePinyin = ""
                            )
                        }
                    }
                }
                "space" -> {
                    if (spaceSnapshot != null) {
                        if (!spaceSnapshot.isComposing) withEditor {
                            if (spaceSnapshot.hasSameSelectionSource(service.candidateState.value) &&
                                spaceSnapshot.associationCandidates == service.candidateState.value.associationCandidates) {
                                selectAssociation(spaceSnapshot.focusedT9AssociationIndex ?: return@withEditor)
                            }
                        } else if (!withEditor { commitSpaceCandidateEnglish(spaceSnapshot, spaceSnapshot.highlightedCandidateIndex) }) {
                            selectCandidateAsync(spaceSnapshot.highlightedCandidateIndex, snapshot = spaceSnapshot)
                        }
                        return@command
                    }
                    val pendingEnglish = candState.pendingEnglishText

                    if (pendingEnglish.isNotEmpty()) {
                        // 直接上屏模式：词已逐字落盘，此处只提交空格并结束本轮英文输入。
                        withEditor {
                            service.commitText(" ")
                            service.candidateState.value = service.candidateState.value.copy(
                                pendingEnglishText = "",
                                associationCandidates = emptyList()
                            )
                        }
                    } else if (candState.isComposing) {
                        if (candState.candidates.isNotEmpty()) {
                            val index = candState.spaceCandidateIndex(state.inputProfile)
                            if (hardwareEvent != null || !withEditor { commitSpaceCandidateEnglish(candState, index) }) {
                                selectCandidateAsync(index, snapshot = candState)
                            }
                        } else {
                            val input = candState.inputText
                            if (input.isNotEmpty()) {
                                withEditor {
                                    service.commitText(input)
                                    service.candidateState.value = service.candidateState.value.copy(
                                        inputText = "",
                                        preeditText = "",
                                        isComposing = false,
                                        pendingEnglishText = "",
                                        candidates = emptyList(),
                                        candidateComments = emptyList(),
                                        associationCandidates = emptyList(),
                                        candidateActions = emptyList()
                                    )
                                }
                                endQueuedComposition()
                                // T9模式：清空partialCommit累积文本，避免下一轮输入
                                // preedit中残留上一轮的提交内容（如"看"→下一轮"看jihua"）
                                if (isT9Schema(state.currentSchemaId)) {
                                    withEditor {
                                        service.t9PartialSegments.clear()
                                        service.keyboardCallbacks?.onT9CompositionCleared?.invoke()
                                        service.uiState.value = service.uiState.value.copy(
                                            t9RightCandidateSelectedCount = 0,
                                            t9SelectedCandidatePinyin = ""
                                        )
                                    }
                                }
                                needsUIUpdate = true
                            }
                        }
                    } else if (!state.isAsciiMode &&
                        hardwareEvent == null && SettingsPreferences.isSpaceCommitAssociationEnabled(service) &&
                        candState.associationCandidates.isNotEmpty() && !candState.isDeletionRecovery
                    ) {
                        // 空格上屏联想候选（中文模式 + 开关开启 + 联想候选存在）：上屏第一个联想词。
                        // 仅中文联想——英文模式此分支不生效，空格保持上屏空格字符；
                        // 连续联想模式：commitText 会自动触发下一轮推理（一直上屏一直推理）；
                        // 单次联想模式：先置抑制标志，上屏引发的那轮推理被跳过并清空联想候选。
                        val association = candState.associationCandidates.first()
                        withEditor {
                            if (SettingsPreferences.isSingleAssociationMode(service)) {
                                service.predictionManager.suppressNextPredictionOnce()
                            }
                            service.commitText(association)
                        }
                    } else {
                        withEditor {
                            service.commitText(service.textCommit.keyboardLiteral(" "))
                        }
                    }
                }
                "word_separator" -> {
                    if (candState.isComposing || candState.inputText.isNotEmpty()) {
                        val result = service.rimeEngine.processQueuedKeyAndGetResult(0x27, 0)
                        if (result.processed) {
                            sendTransformedResult(result)
                        } else {
                            needsUIUpdate = true
                        }
                    } else {
                        needsUIUpdate = true
                    }
                }
                "shift" -> {
                }
                "mode_change" -> {
                }
                "ime_switch" -> {
                    // Preserve the existing immediate ASCII preview for the default behavior.
                    // A configured cycle/pair may select another native language and cannot invert ASCII.
                    val switchOptions = com.kingzcheung.xime.settings.LanguageSwitchPreferences.forRequest(
                        com.kingzcheung.xime.settings.LanguageSwitchPreferences.read(service), languageSwitchMode)
                    if (switchOptions.mode ==
                        com.kingzcheung.xime.settings.LanguageSwitchMode.CURRENT_ENGLISH) {
                        val state = service.uiState.value
                        val schemaId = service.rimeEngine.getCurrentSchema()
                        val nativeLanguageEnabled = com.kingzcheung.xime.settings.InputModes.languageOf(
                            schemaId, state.schemas) in com.kingzcheung.xime.settings.LanguagePreferences.enabled(service)
                        val optimisticTarget = if (nativeLanguageEnabled) !state.isAsciiMode else true
                        withEditor {
                            service.uiState.value = service.uiState.value.copy(isAsciiMode = optimisticTarget)
                            service.keyboardViewModel.dispatch(
                                com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(optimisticTarget, schemaId)
                            )
                        }
                    }
                    // 在 key-processing 线程上执行切换：toggleAsciiMode 阻塞等待 rimeLock
                    // （部署/维护持锁时排队，完成后自动切换），不在主线程阻塞避免 ANR。
                    val t0 = System.nanoTime()
                    FileLogger.i(XimeInputMethodService.TAG, "ime_switch dispatched, ui ascii=${service.uiState.value.isAsciiMode}, thread=${Thread.currentThread().name}")
                    if (!service.schemaController.switchInputMethod(useLanguageKeyPreference = true,
                            languageSwitchMode = languageSwitchMode)) {
                        // 失败后继续显示引擎的实际状态。
                        withEditor {
                            val actualAscii = service.rimeEngine.isAsciiMode()
                            service.uiState.value = service.uiState.value.copy(isAsciiMode = actualAscii)
                            service.keyboardViewModel.dispatch(
                                com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(actualAscii, service.rimeEngine.getCurrentSchema())
                            )
                        }
                    }
                    FileLogger.i(XimeInputMethodService.TAG, "ime_switch handled, total ${(System.nanoTime() - t0) / 1_000_000}ms (queue+rimeLock+main)")
                }
                com.kingzcheung.xime.viewmodel.ASCII_CONTEXT_ON,
                com.kingzcheung.xime.viewmodel.ASCII_CONTEXT_OFF -> {
                    // 符号页和主键盘各自记住中文还是英文。只切当前方案的 ascii，不按地球键的语言顺序循环。
                    val schemaId = service.rimeEngine.getCurrentSchema()
                    val native = com.kingzcheung.xime.settings.InputModes.languageOf(
                        schemaId, service.uiState.value.schemas)
                    val target = if (key == com.kingzcheung.xime.viewmodel.ASCII_CONTEXT_ON)
                        com.kingzcheung.xime.settings.InputLanguage.ENGLISH else native
                    if (target != com.kingzcheung.xime.settings.InputLanguage.UNSPECIFIED) {
                        service.schemaController.switchInputMethod(targetLanguage = target, keepCurrentSchema = true)
                    }
                }
                "abc" -> {
                    service.calculatorEngine.clear()
                    updateCalculatorCandidates()
                }
                "number", "common_symbol" -> {
                    // Number/CommonSymbol 内部切换由 KeyboardView 的 key handler 处理
                }
                "emoji" -> {
                    withEditor {
                        service.commitText("😊")
                    }
                }
                else -> {
                    val isNumberKeyboard = service.keyboardViewModel.keyboardState.value is com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.Number
                    val isCommonSymbolKeyboard = service.keyboardViewModel.keyboardState.value is com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.CommonSymbol

                    val routeResult = com.kingzcheung.xime.calculator.routeCalculatorKey(
                        key = key,
                        isNumberKeyboard = isNumberKeyboard,
                        isCommonSymbolKeyboard = isCommonSymbolKeyboard,
                        calculatorEngine = service.calculatorEngine,
                    )
                    if (routeResult is com.kingzcheung.xime.calculator.CalculatorRouteResult.Handled) {
                        withEditor { service.commitText(service.textCommit.keyboardLiteral(routeResult.commitText, numberPanel = isNumberKeyboard)) }
                        if (isNumberKeyboard) updateCalculatorCandidates()
                        needsUIUpdate = true
                        return@command
                    }

                    val pendingEnglish = candState.pendingEnglishText
                    
                    // 非计算器键清除计算器状态
                    if (!key.matches(Regex("[0-9]")) && key !in listOf("+", "-", "*", "/", ".")) {
                        if (service.calculatorEngine.isActive() || service.calculatorEngine.getCandidate() != null) {
                            service.calculatorEngine.clear()
                            updateCalculatorCandidates()
                        }
                    }
                    
                    // 计算器模式：追踪数字、运算符和小数点
                    if (key.matches(Regex("[0-9]")) || key in listOf("+", "-", "*", "/", ".")) {
                        if (key.matches(Regex("[0-9]")) || key == ".") {
                            service.calculatorEngine.handleDigit(key)
                        } else {
                            service.calculatorEngine.handleOperator(key)
                        }
                        updateCalculatorCandidates()
                    }
                    
                    // 数字选词拦截（插件候选变换存在时）：数字键不进入引擎——rime 会自行选
                    // 引擎候选并返回 committedText，绕过插件候选；映射为对应位置显示候选的
                    // 点击（selectCandidateAsync 按 candidateActions 分流上屏）。
                    // actions 为空（无变换）时保持原生数字选词行为不变。
                    if (!state.isAsciiMode && candState.isComposing &&
                        key.length == 1 && key[0] in '1'..'9'
                    ) {
                        val actions = service.candidateState.value.candidateActions
                        val digitIndex = key[0] - '1'
                        if (actions.isNotEmpty() && digitIndex < service.candidateState.value.candidates.size) {
                            selectCandidateAsync(digitIndex)
                            return@command
                        }
                    }

                    // 所有按键统一经过 Rime 引擎
                    // 字母键不进入此分支（即使 pendingEnglish 非空），需要继续积累编码
                    // 英文模式（isAsciiMode）下非字母键（QWERTY 上滑的数字/符号、数字/符号面板）
                    // 直接上屏，不进入 Rime 引擎与 pendingEnglish 累积（上滑字符即输即上）。
                    if ((state.isAsciiMode || pendingEnglish.isNotEmpty()) && !key.matches(Regex("[a-zA-Z]"))) {
                        val finalKey = key
                        withEditor {
                            // 直接上屏模式：pendingEnglish 对应字符已逐字落盘，只提交增量键值。
                            service.commitText(finalKey)
                            service.candidateState.value = service.candidateState.value.copy(
                                pendingEnglishText = "",
                                associationCandidates = emptyList()
                            )
                        }
                    } else {
                        val isChinese = !state.isAsciiMode
                        val char = key
                        val japaneseCase = JapaneseTyping.usesKanaCase(state.currentSchemaId, state.isAsciiMode)
                        val keyCode = if (japaneseCase) JapaneseTyping.keyCode(key, isShifted) else if (mergedKey) service.rimeEngine.mergedLetterKeyCode(key[0]) else key.lowercase()[0].code
                        val mask = if (isShifted && !japaneseCase) KeyEvent.META_SHIFT_ON else 0
                        val isLetter = key.matches(Regex("[a-zA-Z]"))
                        val isShiftedChinese = isShifted && isChinese && isLetter && !japaneseCase

                        // 非 ASCII 可打印字符（全角符号/中文标点）：直接上屏，不进入 Rime 引擎。
                        // Rime processKey 只接受标准键码，全角键码（如 U+FF0F）无法识别会被静默
                        // 丢弃，导致中文模式下符号面板点击全角字符无输出（与 Trime onText 行为一致）。
                        if (char.isNotEmpty() && char.any { it.code > 0x7E }) {
                            committedText = char
                            needsUIUpdate = true
                        } else if (isShifted && !isLetter) {
                            if (char.length == 1) {
                                val charCode = char[0].code
                                val processed = service.rimeEngine.processKey(charCode, 0)
                                if (processed) {
                                    val result = service.rimeEngine.getProcessResult(processed)
                                    // commitText 不走 CONFLATED channel：channel 会覆盖丢弃未消费事件，
                                    // 快速打字时中间的 commitText 会被吞（吃键）。
                                    if (result.committedText.isNotEmpty()) {
                                        withEditor { service.commitText(result.committedText) }
                                    }
                                    sendTransformedResult(result) { if (service.calculatorEngine.isActive()) updateCalculatorCandidates() }
                                } else {
                                    committedText = char
                                    needsUIUpdate = true
                                }
                            } else {
                                committedText = char
                                needsUIUpdate = true
                            }
                        } else {
                            // 注：T9 数字键不经过此处（T9KeyboardLayout 直接调
                            // controller.onDigitPressed → applyComposition）。
                            // 大写字母（Shift、Caps Lock 或 Shift 拖线中途在字母区松手）在雾凇系 26 键方案里
                            // 参与组字：字母表含 A–Z，大写串进当前输入（zuogeDNAjiance → 做个DNA检测，
                            // Transformer、KFC 由英文词库和自动大写给出），已打的拼音不被顶上屏。
                            val capitalsInRime = isShiftedChinese && !mergedKey &&
                                isRimeIceCapitalSchema(state.currentSchemaId) && service.t9PartialSegments.isEmpty()
                            // 其他方案（双拼、14 键合并键等）没有大写字母编码：已打的字母按原样连同大写字母上屏，
                            // 不丢弃拼音（与桌面端“整串按原样处理”一致）。
                            if (isShiftedChinese && !capitalsInRime && (service.rimeEngine.readQueuedComposition()?.input?.isNotEmpty() == true ||
                                    service.t9PartialSegments.isNotEmpty())) {
                                commitHardwareLiteral(char)
                                return@command
                            }
                            publishSpatialTap(key)
                            val result = if (capitalsInRime) service.rimeEngine.processQueuedKeyAndGetResult(char.uppercase()[0].code, 0)
                                else service.rimeEngine.processQueuedKeyAndGetResult(keyCode, mask)
                            if (result.processed) {
                                if (isShiftedChinese && !capitalsInRime && result.committedText != char) {
                                    service.rimeEngine.clearComposition()
                                    committedText = char
                                    needsUIUpdate = true
                                } else {
                                    val committed = result.committedText
                                    if (state.isAsciiMode && committed.isNotEmpty() && result.inputText.isEmpty() && result.candidates.isEmpty()) {
                                        val current = candState.pendingEnglishText
                                        val newPending = current + committed
                                        service.candidateState.value = service.candidateState.value.copy(pendingEnglishText = newPending)
                                        // 直接上屏模式：只提交 Rime 返回的增量字符（如智能引号转换结果），
                                        // 整段 pending 对应的文本已逐字上屏，不可重复提交。
                                        withEditor {
                                            service.commitText(committed)
                                        }
                                        sendTransformedResult(result) { if (service.calculatorEngine.isActive()) updateCalculatorCandidates() }
                                    } else {
                                        if (committed.isNotEmpty()) {
                                            withEditor { service.commitText(committed) }
                                        }
                                        sendTransformedResult(result) { if (service.calculatorEngine.isActive()) updateCalculatorCandidates() }
                                    }
                                }
                            } else {
                                val isAscii = state.isAsciiMode
                                if (!candState.isComposing || isShiftedChinese) {
                                                    if (isAscii) {
                                                        val charToCommit = if (isShifted) char.uppercase() else char.lowercase()
                                                        val currentPending = candState.pendingEnglishText
                                                        val newPending = currentPending + charToCommit
                                                        // 英文直接上屏模式：字符不经 composing region，即输即落盘；
                                                        // pendingEnglishText 仅作编码记录（供联想与选中候选后回删替换校验）。
                                                        withEditor {
                                                            service.commitText(charToCommit)
                                                        }
                                                        service.candidateState.value = service.candidateState.value.copy(
                                                            pendingEnglishText = newPending,
                                                            associationCandidates = emptyList(),
                                                            englishReplaceSupported = service.supportsEnglishCandidateReplace()
                                                        )
                                                        needsUIUpdate = true
                                    } else {
                                        committedText = char
                                        needsUIUpdate = true
                                    }
                                } else {
                                    val candidateText = if (service.rimeEngine.selectCandidate(0)) {
                                        service.rimeEngine.commit()
                                    } else {
                                        ""
                                    }
                                    committedText = candidateText + char
                                    needsUIUpdate = true
                                }
                            }
                        }
                    }
                }
            }
            
            if (needsUIUpdate) {
                val result = pendingResult
                val textToCommit = committedText
                if (result != null) {
                    if (textToCommit != null) {
                        withEditor { service.commitText(textToCommit) }
                    }
                    sendTransformedResult(result) {
                        if (service.calculatorEngine.isActive()) {
                            updateCalculatorCandidates()
                        }
                    }
                } else {
                    val capturedInputText = service.rimeEngine.getInput()
                    val capturedCandidates = service.rimeEngine.getCandidatesWithComments()
                    val capturedIsAscii = service.rimeEngine.isAsciiMode()
                    val capturedHasNext = service.rimeEngine.hasNextPage()
                    val capturedHasPrev = service.rimeEngine.hasPrevPage()
                    // 候选词变换（hotPath 插件能力）：内联刷新路径（无 RimeProcessResult），
                    // key-processing 线程同步调用；ascii 场景不变换（调用点语义与 transformFor 一致）
                    val transformed = if (capturedInputText.isNotEmpty() && !capturedIsAscii) {
                        service.candidateTransform.transform(
                            capturedInputText, "", capturedCandidates.toList(), capturedIsAscii
                        )
                    } else {
                        null
                    }
                    val displayCandidates: List<com.kingzcheung.xime.rime.RimeCandidate> =
                        transformed?.candidates ?: capturedCandidates.toList()
                    if (textToCommit != null) {
                        withEditor { service.commitText(textToCommit) }
                    }
                    publishUi {
                        val pendingEnglish = service.candidateState.value.pendingEnglishText
                        val (filteredTexts, filteredComments) = if (capturedIsAscii) {
                            val filtered = capturedCandidates.filterNot { candidate ->
                                candidate.text.any { it.code in 0x4E00..0x9FFF }
                            }
                            filtered.map { it.text } to filtered.map { it.comment }
                        } else {
                            displayCandidates.map { it.text } to displayCandidates.map { it.comment }
                        }
                        // 秘密输入框（密码/终端）禁英文联想：联想会泄漏输入前缀，
                        // 回删替换也会破坏受限宿主的输入。NO_SUGGESTIONS 只是宿主
                        // 不要内联补全，候选栏联想仍提供（与退格/applyComposition 路径同口径）。
                        val secret = service.isSecretEditor()
                        service.candidateState.value = service.candidateState.value.copy(
                            inputText = capturedInputText,
                            candidates = filteredTexts,
                            candidateComments = filteredComments,
                            correctedCandidates = if (capturedIsAscii) emptyMap() else displayCandidates
                                .filter { it.correction.isNotEmpty() }.associate { it.text to it.correction },
                            isComposing = capturedInputText.isNotEmpty(),
                            associationCandidates = if (secret || ((capturedIsAscii || !service.isChineseMode) && pendingEnglish.isEmpty())) emptyList() else service.candidateState.value.associationCandidates,
                            isShowingRecentClipboard = false,
                            hasNextPage = capturedHasNext,
                            hasPrevPage = capturedHasPrev,
                            candidateActions = transformed?.actions ?: emptyList()
                        )
                        if (capturedIsAscii != service.uiState.value.isAsciiMode) {
                            FileLogger.i(XimeInputMethodService.TAG, "keyRouter UI refresh: ascii ${service.uiState.value.isAsciiMode}->$capturedIsAscii")
                        }
                        service.uiState.value = service.uiState.value.copy(isAsciiMode = capturedIsAscii)
                        if (pendingEnglish.isNotEmpty() && !secret && service.supportsEnglishCandidateReplace()) {
                            service.serviceScope.launch(InputCommandOwner.requireOwner().context()) {
                                val candidates = service.predictionManager.getEnglishAssociations(pendingEnglish, PredictionManager.MAX_ASSOCIATION_COUNT)
                                withEditor {
                                    val current = service.candidateState.value
                                    // 在途联想过期校验：pendingEnglish 已变/已清 → 丢弃迟到回填
                                    if (current.pendingEnglishText == pendingEnglish) {
                                        service.candidateState.value = current.copy(associationCandidates = candidates)
                                    }
                                }
                            }
                        }
                        if (service.calculatorEngine.isActive()) {
                            updateCalculatorCandidates()
                        }
                    }
                }
            }
        }
    }

    /** Caps Lock while composing commits the letters as typed; the system still toggles the lock. */
    internal fun commitCompositionAsTyped() {
        postRimeJob {
            if (hasPendingCandidateCommit) return@postRimeJob
            if (service.rimeEngine.readQueuedComposition()?.input?.isNotEmpty() == true ||
                service.t9PartialSegments.isNotEmpty()) commitHardwareLiteral("")
        }
    }

    /** On the compact toolbar, page through its windows before turning the engine page. */
    internal fun pageHardwareCandidates(shown: CandidateState, direction: Int) {
        if (service.uiState.value.isCompact && hardwareNavigation.moveWindow(shown, direction)) return
        val paged = if (direction < 0) service.rimeEngine.hasPrevPage() && service.rimeEngine.pageUp()
            else service.rimeEngine.hasNextPage() && service.rimeEngine.pageDown()
        if (paged) hardwareNavigation.enginePaged(shown, direction)
    }

    /**
     * 注音（大千）：键帽上的注音符号换成方案里的大千键码交给 Rime（[ZhuyinKeys]），空格跟在音节后面是第一声。
     * 声调只跟在音节后面：没打字时照原样上屏这个符号，刚打过声调再按声调不收（Rime 会把它当成上屏键）。
     * 大千里数字和 , . - / ; 也是注音键，屏幕上别处送来的这些符号照符号上屏，不当拼写；实体键盘照大千键位打。
     * 处理了返回 true；音节后已有声调的空格、回车、删除和数字键盘等走原来的路。
     */
    private suspend fun routeZhuyinKey(key: String, hardwareEvent: KeyEvent?): Boolean {
        val input = service.rimeEngine.readQueuedComposition()?.input.orEmpty()
        val code = when {
            key == "space" -> if (ZhuyinKeys.takesTone(input)) ' ' else return false
            hardwareEvent == null && key.length == 1 && key[0].code in 0x21..0x7E -> {
                if (service.keyboardViewModel.keyboardState.value is KeyboardLayoutState.Number) return false
                commitHardwareLiteral(service.textCommit.keyboardLiteral(key))
                return true
            }
            else -> ZhuyinKeys.code(key) ?: return false
        }
        if (ZhuyinKeys.isTone(code) && !ZhuyinKeys.takesTone(input)) {
            if (input.isEmpty()) withEditor { service.commitText(key) }
            return true
        }
        val result = service.rimeEngine.processQueuedKeyAndGetResult(code.code, 0)
        if (result.processed) {
            if (result.committedText.isNotEmpty()) withEditor { service.commitText(result.committedText) }
            sendTransformedResult(result)
        } else if (input.isEmpty()) {
            withEditor { service.commitText(key) }
        }
        return true
    }

    /** Finish unfinished raw input without choosing a word; clear only after acceptance. */
    internal suspend fun commitHardwareLiteral(suffix: String): Boolean {
        val state = service.candidateState.value
        val composition = service.rimeEngine.readQueuedComposition()
        val hasComposition = composition?.input?.isNotEmpty() == true || state.isComposing ||
            state.inputText.isNotEmpty() || service.t9PartialSegments.isNotEmpty()
        if (hasComposition && composition == null) return false
        val t9 = isT9Schema(service.uiState.value.currentSchemaId)
        // Read the current engine result, not a previous UI frame: rapid typing
        // followed by a shifted symbol must not drop the last letters.
        val preedit = if (t9) com.kingzcheung.xime.rime.buildT9DisplayState(
            service.t9PartialSegments.map { it.text }, composition?.preedit.orEmpty(),
            composition?.input.orEmpty(), emptyList(), emptyList()).displayText
        else composition?.preedit.orEmpty()
        val raw = if (hasComposition) rawCompositionCommitText(composition?.input.orEmpty(), preedit, t9,
            usesMergedLetterKeys(service.uiState.value.inputProfile.layout)) else ""
        val text = raw + suffix
        if (text.isNotEmpty() && !withEditor { service.commitTextAndPredict(text, false, allowPrediction = false).accepted }) return false
        if (text.isNotEmpty()) withEditor {
            if (!service.uiState.value.inputTarget.isInternal)
                service.finishComposingInputBoxKeepingText()
        }
        service.japaneseInputController.handleKey("clear_composition")
        clearInputStateForKeys()
        withEditor {
            hardwareNavigation.clear()
            publishHardwareCandidateSelection()
            service.maybeCollapseCandidatePage()
        }
        return true
    }

    /** Main-thread commit shared by touch and physical keys; rejection retains the selection. */
    internal fun selectAssociation(index: Int): Boolean {
        if (usesDirectFieldInput) return false
        val state = service.candidateState.value
        service.predictionManager.invalidatePendingPredictions()
        englishAutoSpace.disarm()
        // 英文选词后自动加空格（默认关）：只跟在英文补全词后面，中文联想不加。
        val autoSpace = state.pendingEnglishText.isNotEmpty() &&
            service.uiState.value.let { (it.isAsciiMode || it.inputProfile.language.typesLettersDirectly) && !it.inputTarget.isInternal } &&
            SettingsPreferences.getPrefsPublic(service).getBoolean(EnglishAutoSpace.PREF_KEY, false)
        val accepted = commitAssociationSelection(state, index,
            commit = { text ->
                service.commitTextAndPredict(text, isPaste = false,
                    allowPrediction = !SettingsPreferences.isSingleAssociationMode(service)).accepted
            },
            replace = service::replaceBeforeCursor,
            suffix = if (autoSpace) " " else "")
        if (!accepted) return false
        if (autoSpace) associationWordAt(state, index)?.let(englishAutoSpace::arm)
        service.candidateState.value = service.candidateState.value.copy(
            pendingEnglishText = "", associationCandidates = emptyList())
        hardwareNavigation.clear()
        publishHardwareCandidateSelection()
        return true
    }

    /** Backspace in the tool panel / clipboard search field: the selection, or the character before the caret. */
    private fun deleteInToolPanelField() {
        ToolPanelEditTextHolder.editText?.let { et ->
            val start = et.selectionStart.coerceAtLeast(0)
            val end = et.selectionEnd.coerceAtLeast(start)
            if (start == end && start > 0) {
                et.text?.delete(start - 1, start)
                try { et.setSelection(start - 1) } catch (_: Exception) {}
            } else if (end > start) {
                et.text?.delete(start, end)
                try { et.setSelection(start) } catch (_: Exception) {}
            }
        }
    }

    /**
     * 「删字后给同音字」（默认关）：这一下退格要删掉的字和删后光标前的文字（主线程调用）。开关关着、英文、密码框、
     * 实体键盘紧凑模式、有选区（退格删的是选区）时不读输入框，返回 null。
     */
    private fun characterAboutToBeDeleted(): Pair<String, String>? {
        val state = service.uiState.value
        if (!SettingsPreferences.isDeleteRecovery(service) || state.isAsciiMode || !service.isChineseMode ||
            state.isCompact || state.inputTarget.isInternal || service.isSecretEditor()) return null
        val ic = service.currentInputConnection ?: return null
        if (runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(true)) return null
        // 多读两格：删掉的是代理对时，删后剩下的仍有联想上下文那么长（25）。
        val before = runCatching { ic.getTextBeforeCursor(27, 0)?.toString() }.getOrNull()
        if (before.isNullOrEmpty()) return null
        val remaining = before.dropLastCharacter()
        return before.substring(remaining.length) to remaining
    }

    /**
     * 「删掉刚上屏的词不学习」（默认关）：这一下退格（或上滑清空）把 Rime 刚上屏的那段整个删掉时，返回要减去学习的词。
     * 开关关着、没有在跟踪时不读输入框。主线程调用。
     */
    private fun rejectedCommitBefore(clearing: Boolean): String? {
        val tracker = service.rimeEngine.rejectedCommit
        val needed = tracker.neededLength()
        if (needed == 0) return null
        val ic = service.currentInputConnection
        if (!SettingsPreferences.isUnlearnRejectedCommit(service) || service.uiState.value.inputTarget.isInternal || ic == null) {
            tracker.clear()
            return null
        }
        val before = runCatching { ic.getTextBeforeCursor(needed, 0) }.getOrNull()
        if (clearing) return tracker.beforeClear(before)
        val selected = runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(true)
        return tracker.beforeBackspace(before, selected)
    }

    /** 在引擎线程上从用户词库减去刚被删掉的那一次上屏。 */
    private fun unlearnRejectedCommit(word: String) {
        val taken = service.rimeEngine.unlearn("$word\t1")
        Log.d(XimeInputMethodService.TAG, "rejected commit unlearned: $taken")
    }

    /** Each tap keeps its FIFO position; a hold allows only one outstanding repeat. */
    internal fun handleDeleteKey() {
        // Keep this physical action at its original position, including D-D-A and D-A-D.
        val hold = com.kingzcheung.xime.keyboard.RepeatInput.current.get()
        if (hold != null && !hold.acquire()) return
        postRimeJob("delete") {
            try { if (!hasPendingCandidateCommit && hold?.isActive != false) processDeleteKey(hold) }
            finally { hold?.completed() }
        }
    }

    /** 单次退格处理（service.keyProcessingDispatcher 上执行）。 */
    internal suspend fun processDeleteKey(hold: com.kingzcheung.xime.keyboard.RepeatInput?) {
        val owner = service.uiState.value.inputSessionId
        // 退格照常删掉自动补的空格；之后再打标点、空格不再按自动空格处理。
        englishAutoSpace.disarm()
        // 快捷发送表单显示：退格按焦点路由到表单内 EditText（与单击退格同一路径），不进入 Rime
        if (service.uiState.value.showQuickSendForm) {
            withEditor {
                if (owner != service.uiState.value.inputSessionId) return@withEditor
                service.deleteInQuickSendForm()
            }
            return
        }
        // 工具面板/剪贴板搜索框：九键的退格不经 routeKeyPress，没有拼音时由九键控制器直接转到这里。
        // 删框里的字，不能落到后面应用的输入框（用户 10-30：剪贴板搜索只能输入，不能删除）。
        if (service.uiState.value.toolPanelInputFocused) {
            withEditor {
                if (owner != service.uiState.value.inputSessionId) return@withEditor
                deleteInToolPanelField()
            }
            return
        }
        if (usesDirectFieldInput) {
            withEditor {
                service.predictionManager.invalidatePendingPredictions()
                service.candidateState.value = CandidateState()
                service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
            return
        }
        if (service.japaneseInputController.deleteKana()) {
            hold?.allowsDeletion(imeContent = true)
            return
        }
        // 退格改变输入上下文：使在途的联想预测结果失效，防止过期结果迟到回填
        // associationCandidates，导致长按退格删除时候选栏在"联想词↔空"之间闪动。
        // 先作废再读候选：联想恰好在两步之间送达时，要么算“在等”，要么已经看得到，不会落成删正文。
        val predictionWasPending = service.predictionManager.invalidatePendingPredictions()
        val candState = service.candidateState.value
        val target = deletionTarget(
            engineComposing = service.rimeEngine.compositionActiveForDeletion(),
            uiComposing = candState.isComposing || candState.inputText.isNotEmpty() || candState.preeditText.isNotEmpty(),
            partialSegments = service.t9PartialSegments.isNotEmpty(),
            pendingEnglish = candState.pendingEnglishText.isNotEmpty(),
            // 删字后放回的那一组不挡退格：接着删照常删字（见 DeletionRecovery）。
            candidatesVisible = (candState.associationCandidates.isNotEmpty() && !candState.isDeletionRecovery) ||
                candState.isShowingRecentClipboard,
            predictionPending = predictionWasPending,
        ).skippingAssociation(SettingsPreferences.isDeleteSkipsAssociation(service), candState.isShowingRecentClipboard)
        if (target == DeletionTarget.WAIT) return
        if (hold?.allowsDeletion(target == DeletionTarget.COMPOSITION || target == DeletionTarget.CANDIDATES) == false) return
        // 计算器模式：追踪退格
        val calculatorWasActive = service.calculatorEngine.isActive()
        service.calculatorEngine.handleDelete()
        // Only replace candidates owned by the calculator. Clearing ordinary pinyin
        // candidates here momentarily empties the list and collapses its expanded page.
        if (calculatorWasActive) updateCalculatorCandidates()

        // 数字/符号键盘：直接发送系统退格，不经过 Rime
        // 防止 T9 残留状态被 Rime 退格修改导致 UI 不一致
        val layoutState = service.keyboardViewModel.keyboardState.value
        if ((layoutState is KeyboardLayoutState.Number || layoutState is KeyboardLayoutState.Symbol) &&
            target == DeletionTarget.DOCUMENT) {
            withEditor {
                if (owner != service.uiState.value.inputSessionId) return@withEditor
                service.sendDeleteKey()
            }
        } else when {
            // 1. 英文待处理文本：逐个删除字符，重新加载联想
            target == DeletionTarget.ENGLISH -> {
                val newPending = candState.pendingEnglishText.dropLast(1)
                if (newPending.isNotEmpty()) {
                    withEditor {
                        if (owner != service.uiState.value.inputSessionId) return@withEditor
                        service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                        service.candidateState.value = service.candidateState.value.copy(
                            pendingEnglishText = newPending,
                            candidates = emptyList(),
                            candidateComments = emptyList(),
                            associationCandidates = emptyList(),
                            candidateActions = emptyList()
                        )
                    }
                    if (!service.isSecretEditor() && service.supportsEnglishCandidateReplace()) {
                        service.serviceScope.launch(InputCommandOwner.requireOwner().context()) {
                            val candidates = service.predictionManager.getEnglishAssociations(newPending, PredictionManager.MAX_ASSOCIATION_COUNT)
                            withEditor {
                                if (owner != service.uiState.value.inputSessionId) return@withEditor
                                if (service.candidateState.value.pendingEnglishText == newPending) {
                                    service.candidateState.value = service.candidateState.value.copy(associationCandidates = candidates)
                                }
                            }
                        }
                    }
                } else {
                    withEditor {
                        if (owner != service.uiState.value.inputSessionId) return@withEditor
                        service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                        service.candidateState.value = service.candidateState.value.copy(
                            pendingEnglishText = "",
                            candidates = emptyList(),
                            candidateComments = emptyList(),
                            associationCandidates = emptyList(),
                            isShowingRecentClipboard = false,
                            candidateActions = emptyList()
                        )
                    }
                    // 待确认英文已删空：候选展开页若开着则收起（无内容可展示）
                    service.maybeCollapseCandidatePage()
                }
            }

            // 2. Rime 编码中：让 Rime 处理退格，更新候选
            target == DeletionTarget.COMPOSITION -> {
                val result = service.rimeEngine.deleteCompositionAndGetResult()
                if (owner != service.uiState.value.inputSessionId) return
                if (result.inputText.isEmpty()) {
                    // T9 部分提交：剩余编码删完后，已上屏/ composing 的部分候选词无法用
                    // RIME 退格删除，会一直卡在候选栏。这里撤销最近一次部分提交：
                    // 清空自己持有的 composing 区域并从累积列表移除，不删除宿主正文。
                    if (service.t9PartialSegments.isNotEmpty()) {
                        // 已撤销段只存在于输入法状态（候选栏/输入框 composing 的显示），
                        // 从未上屏到正文：候选栏模式不能按段文本长度删宿主文本，
                        // 否则会误删光标前的正文（2026-09-25 真机实证）。
                        val removed = service.t9PartialSegments.rollbackPartialSegments(1)
                        withEditor {
                            if (owner != service.uiState.value.inputSessionId) return@withEditor
                            if (TerminalEditor.inputTextLocation(service)
                                == SettingsPreferences.INPUT_TEXT_INPUT_BOX) {
                                service.endComposingInputBox()
                            }
                            if (removed.isNotEmpty()) {
                                service.uiState.value = service.uiState.value.copy(
                                    t9RightCandidateSelectedCount =
                                        (service.uiState.value.t9RightCandidateSelectedCount - removed.size)
                                            .coerceAtLeast(0L),
                                    t9SelectedCandidatePinyin = ""
                                )
                            }
                        }
                        // undo 联动：撤销段时回滚用户词典调频（按段，不按字符）。
                        // Undo changes composition only; no user dictionary write.
                    }
                }
                sendTransformedResult(result) { if (service.calculatorEngine.isActive()) updateCalculatorCandidates() }
            }

            // 3. 联想词或剪贴板：仅清空候选栏，不回删已上屏字符
            target == DeletionTarget.CANDIDATES -> {
                service.candidateState.value = service.candidateState.value.copy(
                    candidates = emptyList(),
                    candidateComments = emptyList(),
                    associationCandidates = emptyList(),
                    isShowingRecentClipboard = false
                )
                // 候选展开页：联想/剪贴板候选清空后无内容，收起
                service.maybeCollapseCandidatePage()
            }

            // 4. 无候选也无编码：直接回删已上屏文本
            else -> {
                service.predictionManager.deleteLastChar()

                var deletedBefore: Pair<String, String>? = null
                val rejected = withEditor {

                    if (owner != service.uiState.value.inputSessionId) return@withEditor null
                    val word = rejectedCommitBefore(clearing = false)
                    deletedBefore = characterAboutToBeDeleted()
                    service.sendDeleteKey()
                    word
                }
                rejected?.let(::unlearnRejectedCommit)

                val recovery = deletedBefore?.let { (deleted, remaining) ->
                    val run = DeletionRecovery.nextRun(
                        candState.takeIf { it.isDeletionRecovery }?.deletionRecovery?.firstOrNull(), deleted) ?: return@let null
                    val homophones = if (service.traditionalChineseOutput()) emptyList()
                        else DeletionHomophones.homophones(service, deleted)
                    // 删后的上下文以输入框为准，选区回报时不会把这一组当成上下文变了而清掉。
                    service.predictionManager.replaceCommittedText(remaining)
                    DeletionRecovery.words(run, homophones)
                }.orEmpty()
                service.candidateState.value = service.candidateState.value.copy(
                    candidates = emptyList(),
                    candidateComments = emptyList(),
                    associationCandidates = recovery,
                    deletionRecovery = recovery,
                    isShowingRecentClipboard = false
                )
                // 候选展开页：无候选无编码，收起
                service.maybeCollapseCandidatePage()
            }
        }
    }

    /**
     * Posts a rime operation to the shared input FIFO for sequential execution.
     * Ensures no interleaving with key processing.
     */
    internal fun postRimeJob(kind: String = "job", block: suspend CoroutineScope.() -> Unit) {
        val owner = InputCommandOwner.current.get() ?: run {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                "A background input job cannot acquire the current editor"
            }
            commandOwner(service.inputReadiness.ticket() ?: return)
        }
        service.inputCommands.submit(owner.context(), kind = kind) {
            owner.requireCurrent(service.inputReadiness, service.uiState.value.inputSessionId)
            block()
        }
    }

    suspend fun selectCandidateAsync(index: Int, expandedCandidate: RimeCandidate? = null, snapshot: CandidateState = service.candidateState.value) {
        InputCommandOwner.requireOwner().requireCurrent(service.inputReadiness, service.uiState.value.inputSessionId)
        if (usesDirectFieldInput) return
        if (hasPendingCandidateCommit) return
        if (!snapshot.hasSameSelectionSource(service.candidateState.value)) {
            // 只记发生次数，用来统计点击因候选已变化而作废的频率；不记候选内容。
            FileLogger.w("InputLatency", "candidate tap dropped: source changed")
            return
        }
        if (service.japaneseInputController.commit(index)) return
        if (!service.rimeEngine.isCandidateRevisionCurrent(snapshot.engineRevision)) {
            FileLogger.w("InputLatency", "candidate tap dropped: revision changed")
            return
        }
        // 展开页点选（expandedCandidate 非空）：取词/注释以展开页显示的同一份
        // 全量列表为准（所见即所得）。index 是全量索引，不得用于索引引擎当前页的
        // candidates/candidateComments——部分选拼音（SELECTION 态）或候选超页时
        // 两列表错位，全局索引套在当前页上会取错词（如点长词只上屏另一短词）。
        // 插件候选 actions 按页内索引记录，展开页索引同样不适用，直接跳过检测。
        val pendingAction = if (expandedCandidate == null) {
            snapshot.candidateActions.getOrNull(index)
        } else {
            null
        }
        if (pendingAction != null && pendingAction.isPluginCandidate) {
            // 防御：中英/方案切换后引擎组合已清空但 candidateState 残留旧候选+actions，
            // 此时点选必须回落原生路径（引擎侧 selectCandidate 失败自动防呆，与旧行为一致），
            // 否则残留插件候选会绕过引擎校验直接上屏。
            val engineHasComposition = service.rimeEngine.getInput().isNotEmpty() ||
                service.rimeEngine.getCandidates().isNotEmpty()
            if (engineHasComposition) {
                commitSelectedText(pendingAction.commitText)
                return
            }
        }

        val selectedCandidate = expandedCandidate?.text
            ?: if (index < snapshot.candidates.size) {
                snapshot.candidates[index]
            } else null

        val isT9 = isT9Schema(service.uiState.value.currentSchemaId)
        val candidatePinyin = if (isT9) {
            expandedCandidate?.comment
                ?: if (index < snapshot.candidateComments.size) {
                    snapshot.candidateComments[index]
                } else {
                    null
                }
        } else {
            null
        }

        // 在 RIME 真正 select/commit 之前，先同步通知 T9 控制器消费数字。
        // 控制器返回 true 表示输入序列已被该候选词完整消费，服务层应视为 full commit。
        // 传入候选文本用于 C++ (comment, text) 双条件精确定位（注释歧义防错码）。
        val candidateTextLength = selectedCandidate?.length ?: 0
        val fullyConsumed = if (isT9) {
            service.keyboardCallbacks?.onT9RightCandidateWillBeSelected?.invoke(candidatePinyin, selectedCandidate, candidateTextLength, snapshot.engineRevision) ?: return
        } else {
            false
        }

        // T9：跳过 service.rimeEngine.selectCandidate，消费已由 T9 处理器（t9_processor）
        // 独立完成。selectCandidate 会遗留 [confirmed, phony] 残留 composition 状态，
        // 导致后续 forceSendToRime 的 setInput 无法正常重建候选项（对齐 main 分支）。
        // 非 T9 才调用 selectCandidate，并用 resolveRimeCandidateIndex 修正候选 index，
        // 避免 UI 候选被过滤/重排后上屏错词。
        val selection = if (isT9) {
            null
        } else {
            val rimeIndex = if (pendingAction != null && pendingAction.engineIndex >= 0) {
                // 变换映射记录的引擎候选索引（比按显示文本回查更准）
                pendingAction.engineIndex
            } else if (selectedCandidate != null) {
                resolveRimeCandidateIndex(index, selectedCandidate, service.rimeEngine.getCandidates().toList())
            } else {
                index
            }
            service.rimeEngine.selectCandidateAtRevision(rimeIndex, snapshot.engineRevision) ?: return
        }

        // T9：跳过 service.rimeEngine.commit()，用用户点选的 selectedCandidate 作为权威上屏文本。
        val committedText = selection?.committedText.orEmpty()
        // T9 模式下 fullyConsumed 是判断 full/partial commit 的唯一权威：
        // 当控制器明确 partial commit 时，即使 RIME commit() 返回非空文本
        // （RIME 内部做了 partial commit），也不应走 full commit 路径。
        val isFullCommit = if (isT9) {
            fullyConsumed && selectedCandidate != null
        } else {
            committedText.isNotEmpty()
        }
        if (isFullCommit) {
            // T9 full commit：以用户点选的候选词文本为权威上屏文本；
            // 非 T9 仍用 RIME committedText（可能含简繁转换等处理）。
            val textToMerge = if (isT9 && selectedCandidate != null) {
                selectedCandidate
            } else if (committedText.isNotEmpty()) {
                committedText
            } else {
                selectedCandidate!!
            }
            // T9 full commit：partial commit 累积文本（未单独上屏，只存在于
            // service.t9PartialSegments 的 composing 区）与本次上屏文本是独立词，必须拼接，
            // 不能去重（mergePartialCommitText 仅用于显示，会吞掉重复的词）。
            // 但若点击的是 partial 词本身（RIME 无菜单、候选栏显示的就是 partial 词，
            // 无拼音注释 candidatePinyin==null），则只提交累积的 partial 文本，避免重复。
            val partialTexts = service.t9PartialSegments.map { it.text }
            val fullCommitText = if (isT9 && partialTexts.isNotEmpty()) {
                if (candidatePinyin == null) {
                    partialTexts.joinToString("")
                } else {
                    partialTexts.joinToString("") + textToMerge
                }
            } else {
                textToMerge
            }
            deliverCandidateCommit(PendingCandidateCommit(
                InputCommandOwner.requireOwner(), fullCommitText, isT9
            ))
        } else {
            withEditor {
                if (isT9) {
                    // partial commit：累积候选文本与拼音，供合并显示与 full commit 调频拼接。
                    if (selectedCandidate != null) {
                        service.t9PartialSegments.add(T9PartialSegment(selectedCandidate, candidatePinyin ?: ""))
                    }
                    // 保留状态字段，供 UI 层感知右侧选词事件
                    service.uiState.value = service.uiState.value.copy(
                        t9RightCandidateSelectedCount = service.uiState.value.t9RightCandidateSelectedCount + 1,
                        t9SelectedCandidatePinyin = candidatePinyin ?: ""
                    )
                } else {
                    service.updateUI()
                }
            }
            // Rebuild remaining code on the worker, within this selection's FIFO slot.
            if (isT9) service.keyboardCallbacks?.onT9ForceSendToRime?.invoke()
        }
    }

    /**
     * 插件候选直接上屏（所见即所得）：不经引擎 select/commit。
     * 先上屏并清空候选状态（与 enter 提交分支同序），再清引擎组合态。
     */
    internal suspend fun commitSelectedText(text: String) {
        deliverCandidateCommit(PendingCandidateCommit(
            InputCommandOwner.requireOwner(), text, false
        ))
    }

    /** A rejected commit retains its original owner and text. Retry is an explicit user action. */
    internal suspend fun commitPreparedText(text: String, t9: Boolean, caret: CommitCaret = CommitCaret.NONE): TextCommitResult =
        deliverCandidateCommit(PendingCandidateCommit(InputCommandOwner.requireOwner(), text, t9, learnSelection = false,
            caret = caret))

    private suspend fun deliverCandidateCommit(pending: PendingCandidateCommit): TextCommitResult =
        pending.deliver(
            submit = { text -> withEditor {
                val previousText = service.predictionManager.lastCommittedText
                val outcome = service.submitText(text, pending.caret)
                if (outcome.accepted) {
                    if (pending.learnSelection && previousText.isNotEmpty() && service.isChineseMode &&
                        SettingsPreferences.isSmartPredictionEnabled(service) && AssociationManager.isInitialized()) {
                        service.predictionManager.recordInputPair(previousText.last().toString(), text)
                    }
                }
                outcome
            } },
            rejected = { withEditor {
                pendingCandidateCommit = it
                service.uiState.value = service.uiState.value.copy(rejectedCommitText = it.text)
                FileLogger.w("InputLatency", "candidate commit not accepted: retained for retry")
            } },
            accepted = {
                // Learning failure is diagnostic; it must never undo accepted text.
                if (it.t9 && it.learnSelection && !service.rimeEngine.t9MemorizeSelection(it.text)) {
                    FileLogger.w("ImeKeyRouter", "Selected T9 phrase was committed but learning failed (missing captured code or dictionary write failure)")
                }
                if (it.t9) service.rimeEngine.clearQueuedT9Composition() else service.rimeEngine.clearQueuedComposition()
                withEditor {
                    discardPendingCandidateCommit()
                    service.keyboardViewModel.setCandidatePageExpanded(false)
                    service.keyboardCallbacks?.onT9CompositionCleared?.invoke()
                    service.t9PartialSegments.clear()
                    service.candidateState.value = service.candidateState.value.copy(
                        inputText = "", preeditText = "", pendingEnglishText = "",
                        candidates = emptyList(), candidateComments = emptyList(),
                        candidateActions = emptyList(), expandedCandidates = emptyList(),
                        expandedCandidatesLoaded = false, isComposing = false,
                        hasNextPage = false, hasPrevPage = false, isShowingRecentClipboard = false,
                    )
                    service.uiState.value = service.uiState.value.copy(
                        t9RightCandidateSelectedCount = 0, t9SelectedCandidatePinyin = ""
                    )
                }
            },
        )
    
    /**
     * 输入态判定（clear_all 与 undo_clear 共用）。
     *
     * 输入态 = 存在未上屏内容：RIME 组合（candidateState 派生字段）或英文输入
     *（pendingEnglishText 非空——英文按键不经 RIME，故 inputText/preeditText/isComposing 恒空，
     * 漏判会让 clear_all 把 composing 区文本与 pendingEnglishText 重复拼接）
     * 或 T9 partial 累积。
     * 不能用 RIME getInput()——tryLocked 锁竞争时静默返回空，会误判空闲态。
     */
    private fun hasInputState(candState: CandidateState): Boolean =
        candState.isComposing ||
            candState.inputText.isNotEmpty() ||
            candState.preeditText.isNotEmpty() ||
            candState.pendingEnglishText.isNotEmpty() ||
            service.t9PartialSegments.isNotEmpty() ||
            service.rimeEngine.compositionActiveForDeletion() != false ||
            (candState.associationCandidates.isNotEmpty() && !candState.isDeletionRecovery) ||
            candState.isShowingRecentClipboard ||
            service.predictionManager.hasPendingPrediction

    /** Ends the composition in the input FIFO, T9's own digit state included (see [com.kingzcheung.xime.rime.RimeEngine.endQueuedComposition]). */
    internal fun endQueuedComposition() =
        service.rimeEngine.endQueuedComposition(isT9Schema(service.uiState.value.currentSchemaId))

    /**
     * 清空输入态（预编辑/候选/联想/partial 累积/计算器），不动已上屏文本。
     * 供 clear_composition 与 clear_all 输入态分支共用。
     *
     * 显式清 preeditText（与提交路径"残留根治"一致），不依赖 updateUI 自愈；
     * 输入框模式清 composing 区；T9 方案重置左侧候选区（其他键盘无左栏，跳过）。
     */
    internal suspend fun clearInputStateForKeys() {
        service.predictionManager.invalidatePendingPredictions()
        service.calculatorEngine.clear()
        updateCalculatorCandidates()
        service.t9PartialSegments.clear()
        endQueuedComposition()
        service.candidateState.value = service.candidateState.value.copy(
            candidates = emptyList(),
            candidateComments = emptyList(),
            associationCandidates = emptyList(),
            pendingEnglishText = "",
            inputText = "",
            preeditText = "",
            isComposing = false,
            isShowingRecentClipboard = false,
            candidateActions = emptyList(),
            expandedCandidates = emptyList(),
            expandedCandidatesLoaded = false,
            hasNextPage = false,
            hasPrevPage = false,
            candidateFocus = null
        )
        if (TerminalEditor.inputTextLocation(service) ==
            SettingsPreferences.INPUT_TEXT_INPUT_BOX
        ) {
            withEditor { service.endComposingInputBox() }
        }
        if (isT9Schema(service.uiState.value.currentSchemaId)) {
            withEditor {
                service.keyboardCallbacks?.onT9CompositionCleared?.invoke()
                service.uiState.value = service.uiState.value.copy(
                    t9RightCandidateSelectedCount = 0,
                    t9SelectedCandidatePinyin = ""
                )
            }
        }
    }

    /**
     * 更新计算器候选栏显示
     * 显示两个候选：
     * - index 0: 计算结果（如 "2"），点击直接替换为结果
     * - index 1: 带公式的结果（如 "1+1=2"），点击显示公式和结果
     */
    internal fun updateCalculatorCandidates() {
        val candidate = service.calculatorEngine.getCandidate()
        val result = service.calculatorEngine.getResult()
        service.candidateState.value = if (candidate != null && result.isNotEmpty()) {
            service.candidateState.value.copy(
                candidates = listOf(result, candidate),
                candidateComments = emptyList()
            )
        } else {
            // 如果计算器之前有显示但现在已清除，也要清空候选栏
            if (service.candidateState.value.candidates.isNotEmpty() && !service.calculatorEngine.isActive()) {
                service.candidateState.value.copy(
                    candidates = emptyList(),
                    candidateComments = emptyList()
                )
            } else {
                service.candidateState.value
            }
        }
    }

    /** Main-thread display-only action. No engine mutation, buffer consumption or host commit. */
    internal fun highlightNextCandidate(snapshot: CandidateState) = highlightCandidate(snapshot, forward = true)

    internal fun highlightPreviousCandidate(snapshot: CandidateState) = highlightCandidate(snapshot, forward = false)

    private fun highlightCandidate(snapshot: CandidateState, forward: Boolean) {
        if (service.inputReadiness.ticket() == null || hasPendingCandidateCommit) return
        val current = service.candidateState.value
        if (!snapshot.hasSameSelectionSource(current) || snapshot.associationCandidates != current.associationCandidates ||
            (snapshot.isComposing && !service.rimeEngine.isCandidateRevisionCurrent(snapshot.engineRevision))) return
        val profile = service.uiState.value.inputProfile
        service.candidateState.value = if (forward) current.advanceT9Candidate(profile)
            else current.retreatT9Candidate(profile)
        service.sessionController.refreshInputBoxComposition()
    }

    internal fun selectCandidate(index: Int) {
        if (usesDirectFieldInput && !service.candidateState.value.isShowingRecentClipboard) return
        service.composeViewRef?.let { service.feedbackManager.performKeyPressEffect(view = it) }

        // 计算器模式（仅在数字/常用符号键盘下生效，防止状态残留导致其他键盘候选词点击异常）
        val layoutState = service.keyboardViewModel.keyboardState.value
        val isCalculatorKeyboard = layoutState is com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.Number
                || layoutState is com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.CommonSymbol
        if (service.calculatorEngine.isActive() && isCalculatorKeyboard) {
            val result = service.calculatorEngine.getResult()
            val expression = service.calculatorEngine.getExpression()
            val formulaResult = service.calculatorEngine.getFormulaResult()
            if (result.isNotEmpty() && expression.isNotEmpty()) {
                val textToCommit: String
                // index 0: 纯结果（如 "2"）
                // index 1: 公式结果（如 "1+1=2"）
                textToCommit = when (index) {
                    0 -> result
                    1 -> formulaResult
                    else -> ""
                }
                if (textToCommit.isNotEmpty()) {
                    service.calculatorEngine.clear()
                    run {
                        val ic = service.currentInputConnection
                        if (ic != null) {
                            // 删除输入框中已键入的表达式
                            ic.deleteSurroundingText(expression.length, 0)
                            // 提交选中的文本
                            ic.commitText(textToCommit, textToCommit.length)
                        }
                        service.candidateState.value = CandidateState()
                    }
                }
            }
            return
        }
        
        if (service.candidateState.value.isShowingRecentClipboard && index >= 0 && index < service.recentClipboardItemsState.value.size) {
            val text = service.recentClipboardItemsState.value[index].text
            service.textCommit.selectClipboardItem(text)
            service.candidateState.value = service.candidateState.value.copy(
                isShowingRecentClipboard = false,
                candidates = emptyList(),
                candidateComments = emptyList()
            )
        } else {
            val snapshot = service.candidateState.value
            val screenShiftHeld = service.keyboardViewModel.shiftMode.value == com.kingzcheung.xime.viewmodel.ShiftMode.HELD
            if (com.kingzcheung.xime.gloss.CandidateGlossary.swapHeld(screenShiftHeld) &&
                commitCandidateEnglish(snapshot.candidates.getOrNull(index))) {
                // 屏幕 Shift 这次用来选了英文，松开时不再切大小写。
                if (screenShiftHeld) service.keyboardViewModel.onCharacterTyped()
                return
            }
            postRimeJob("select") {
                selectCandidateAsync(index, snapshot = snapshot)
            }
        }
    }

    /** 中文键盘且不在临时英文状态。物理键盘下 isChineseMode 依赖屏幕键盘上报，改看输入方案的语言。 */
    private fun chineseKeyboardActive(): Boolean = service.uiState.value.let {
        !it.isAsciiMode && it.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE
    }

    /** 屏幕键盘按住 Shift（或九键长按“下一项”）时按空格：这条候选有英文就上屏英文，返回 true。 */
    private fun commitSpaceCandidateEnglish(snapshot: CandidateState, index: Int): Boolean {
        val screenShiftHeld = service.keyboardViewModel.shiftMode.value == com.kingzcheung.xime.viewmodel.ShiftMode.HELD
        if (!com.kingzcheung.xime.gloss.CandidateGlossary.swapHeld(screenShiftHeld)) return false
        if (!commitCandidateEnglish(snapshot.candidates.getOrNull(index))) return false
        if (screenShiftHeld) service.keyboardViewModel.onCharacterTyped()
        return true
    }

    /**
     * 物理键盘按住 Shift 加数字或空格选候选（释义词典已加载、中文正在组词时）：有英文上屏英文，
     * 没有照常上中文。按编码处理完后的候选来定位，与不按 Shift 时选的是同一条。返回是否接管了这个键。
     */
    internal fun selectHardwareCandidateWithShift(keyCode: Int): Boolean {
        val before = service.candidateState.value
        if (!takesOverShiftedCandidateKey(keyCode, shiftPressed = true, chinese = chineseKeyboardActive(),
                glossLoaded = com.kingzcheung.xime.gloss.CandidateGlossary.isLoaded,
                composingWithCandidates = before.isComposing && before.candidates.isNotEmpty())) return false
        postRimeJob("select") {
            val (state, index) = withEditor {
                val current = service.candidateState.value
                current to hardwareWindowStart(current) + shiftedCandidateOffset(keyCode) {
                    hardwareCandidateHighlight(current, english = false,
                        predictionPending = service.predictionManager.hasPendingPrediction)
                }!!
            }
            val word = state.candidates.getOrNull(index) ?: return@postRimeJob
            val english = shiftSelectionEnglish(word, com.kingzcheung.xime.gloss.CandidateGlossary::englishFor)
            if (english != null) commitPreparedText(english, isT9Schema(service.uiState.value.currentSchemaId))
            else selectCandidateAsync(index, snapshot = state)
        }
        return true
    }

    /**
     * 按住 Shift 选候选：有完整英文的中文候选上屏英文（不加空格、大小写照词典），清掉当前编码。
     * 只在中文键盘生效；没有英文时返回 false，调用方照常选中文。
     */
    internal fun commitCandidateEnglish(candidate: String?): Boolean {
        if (candidate.isNullOrEmpty() || !chineseKeyboardActive()) return false
        val english = com.kingzcheung.xime.gloss.CandidateGlossary.englishFor(candidate) ?: return false
        val t9 = isT9Schema(service.uiState.value.currentSchemaId)
        postRimeJob("select") { commitPreparedText(english, t9) }
        // 九键长按切到的对调只管这一次选词。
        com.kingzcheung.xime.gloss.CandidateGlossary.t9NextHeld.value = false
        return true
    }

    /**
     * 长按删除 = 忘掉这条候选的学习记录（所有方案一致）：词库里本来有的词回到原位，
     * 自造词消失，整句忘掉句里学过的词；没有学习记录时只提示，排序不变。
     * 显示索引经 resolveRimeCandidateIndex 映射回引擎原始索引（插件候选
     * 注入会使两者错位，与 selectCandidateAsync 同口径），之后刷新候选。
     */
    internal fun deleteCandidate(displayIndex: Int) {
        val snapshot = service.candidateState.value
        postRimeJob {
            if (!snapshot.hasSameSelectionSource(service.candidateState.value)) return@postRimeJob
            val text = snapshot.candidates.getOrNull(displayIndex)
            if (text.isNullOrEmpty()) return@postRimeJob
            if (forgetEnglishWord(text)) return@postRimeJob
            val engineIndex = resolveRimeCandidateIndex(
                displayIndex, text, service.rimeEngine.getCandidates().toList()
            )
            val result = service.rimeEngine.forgetCandidateAtRevision(engineIndex, snapshot.engineRevision)
            FileLogger.i(
                XimeInputMethodService.TAG,
                "ForgetCandidate: text='$text' display=$displayIndex engine=$engineIndex result=$result"
            )
            if (result != 0) {
                withEditor {
                    service.updateUI()
                }
                showForgetResult(text, result)
            }
        }
    }
    
    internal fun pageDown() {
        postRimeJob {
            service.rimeEngine.pageDown()
            withEditor {
                service.updateUI()
            }
        }
    }
    
    internal fun pageUp() {
        postRimeJob {
            service.rimeEngine.pageUp()
            withEditor {
                service.updateUI()
            }
        }
    }

    /**
     * 候选展开页点选：按跨页全局索引选词（select_candidate，区别于候选栏的
     * 当前页内索引 select_candidate_on_current_page）。
     *
     * 关键：rime 的 select 只是把候选放进内部 commit 缓冲，宿主必须再调
     * [RimeEngine.commit]（get_commit）拉取文本并自行上屏——只刷新 composition
     * 拿不到上屏内容（编码已消费、字却丢失，即此前"展开页点选无法上屏"的根因）。
     */
    internal fun selectCandidateGlobal(globalIndex: Int) {
        // 点选震动反馈与候选栏同源（performKeyPressEffect），保证手感一致
        service.composeViewRef?.let { service.feedbackManager.performKeyPressEffect(view = it) }
        // 入队前先按全局索引取全量候选：调用线程读到的 expandedCandidates 正是
        // UI 渲染的同一份列表；入队后再取可能被编码刷新清空/重建而扑空
        val snapshot = service.candidateState.value
        postRimeJob("select") {
            selectCandidateGlobalAsync(globalIndex, snapshot)
        }
    }

    internal suspend fun selectCandidateGlobalAsync(globalIndex: Int, snapshot: CandidateState) {
        if (usesDirectFieldInput) return
            val expandedCandidate = snapshot.expandedCandidates.getOrNull(globalIndex) ?: return
            if (!snapshot.hasSameSelectionSource(service.candidateState.value)) return
            if (service.japaneseInputController.commit(globalIndex)) return
            if (!service.rimeEngine.isCandidateRevisionCurrent(snapshot.engineRevision)) return
            // T9 方案的选词消费由 t9_processor 独立完成，直接调引擎 select 会
            // 遗留 [confirmed, phony] 残留组合态（见 selectCandidateAsync 注释），
            // 降级走候选栏同款路径；取词/注释必须用展开页同一份全量候选——
            // 全局索引与引擎当前页列表错位时会取错词/丢字
            if (isT9Schema(service.uiState.value.currentSchemaId)) {
                selectCandidateAsync(globalIndex, expandedCandidate, snapshot)
                return
            }
            val selection = service.rimeEngine.selectCandidateAtRevision(globalIndex, snapshot.engineRevision, global = true) ?: return
            val committedText = selection.committedText
            if (committedText.isNotEmpty()) {
                deliverCandidateCommit(PendingCandidateCommit(
                    InputCommandOwner.requireOwner(), committedText, false
                ))
            } else {
                // 引擎未产生 commit（选中后继续组句的多段场景）：刷新组合态
                withEditor {
                    service.updateUI()
                }
            }
    }

    /** 展开页使用同一原生候选版本与全局索引执行反馈，再重读候选。 */
    internal fun deleteCandidateGlobal(globalIndex: Int) {
        val snapshot = service.candidateState.value
        postRimeJob {
            if (!snapshot.hasSameSelectionSource(service.candidateState.value)) return@postRimeJob
            val text = snapshot.expandedCandidates.getOrNull(globalIndex)?.text
            if (text.isNullOrEmpty()) return@postRimeJob
            if (forgetEnglishWord(text)) return@postRimeJob
            val result = service.rimeEngine.forgetCandidateAtRevision(globalIndex, snapshot.engineRevision, global = true)
            FileLogger.i(
                "ImeKeyRouter",
                "forgetCandidateGlobal: text='$text' index=$globalIndex result=$result"
            )
            if (result == 0) return@postRimeJob
            withEditor {
                service.updateUI()
            }
            showForgetResult(text, result)
        }
    }

    /** A remembered hand-typed English word (lua/cyime_en_memory.lua) has no Rime record: forget it from its file. */
    private suspend fun forgetEnglishWord(text: String): Boolean {
        if (!isRimeIceCapitalSchema(service.uiState.value.currentSchemaId)) return false
        val rimeDir = com.kingzcheung.xime.settings.SchemaManager.getRimeDir(service)
        if (!com.kingzcheung.xime.settings.EnglishWordMemory.forget(rimeDir, text)) return false
        // Translate the same input again so the forgotten word leaves the menu.
        service.rimeEngine.setInput(service.rimeEngine.getInput())
        withEditor { service.updateUI() }
        showForgetResult(text, 3)
        return true
    }

    /**
     * 忘掉学习的结果提示：用户看不到词库内部状态，只有这句话说明发生了什么。
     * result 同 forgetCandidate：1 忘掉后仍是词库里的词、2 没有学习记录、3 忘掉后不再出现（自造词）。
     */
    private fun showForgetResult(text: String, result: Int) {
        val message = when (result) {
            1 -> "已忘掉「$text」的学习记录，回到词库原来的位置"
            3 -> "已删除自造的「$text」，以后再用会重新记住"
            else -> "「$text」没有学习记录，不会删掉，排序不变"
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(service, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

}

/** 物理键盘 Shift 加数字（1…9、0）或空格对应的候选位置，相对工具栏当前窗口；空格取高亮项，其它键为 null。 */
internal fun shiftedCandidateOffset(keyCode: Int, highlight: () -> Int): Int? =
    if (keyCode == KeyEvent.KEYCODE_SPACE) highlight() else hardwareCandidateDigitIndex(keyCode, shifted = false)

/**
 * 物理键盘带 Shift 的键是否由“按住 Shift 选候选”接管：只在中文组词、有候选时。Shift+空格总是接管
 * （否则它被当成“输入一个空格”：先上中文首选、再多补一个空格）；Shift+数字只在释义词典生效时接管，
 * 平时仍输入 ! @ # 等符号。没按 Shift 的键一律不接管，行为不变。
 */
internal fun takesOverShiftedCandidateKey(
    keyCode: Int, shiftPressed: Boolean, chinese: Boolean, glossLoaded: Boolean, composingWithCandidates: Boolean,
): Boolean = shiftPressed && chinese && composingWithCandidates &&
    (keyCode == KeyEvent.KEYCODE_SPACE || glossLoaded && hardwareCandidateDigitIndex(keyCode, shifted = false) != null)

/** 按住 Shift 选中的候选要上屏的英文；null 表示照常上屏中文。两种情况都只上屏这个词本身，不附加空格。 */
internal fun shiftSelectionEnglish(candidate: String, englishFor: (String) -> String?): String? = englishFor(candidate)
