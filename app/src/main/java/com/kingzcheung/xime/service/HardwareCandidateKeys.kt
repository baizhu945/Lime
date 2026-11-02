package com.kingzcheung.xime.service

import android.util.Log
import android.view.KeyEvent
import com.kingzcheung.xime.rime.RimeProcessResult
import com.kingzcheung.xime.ui.keyboard.isT9Schema

/** Returns false when the key should continue through ordinary input handling. */
internal suspend fun ImeKeyRouter.processHardwareCandidateKey(event: KeyEvent, pageAtBoundary: Boolean = false): Boolean {
    val key = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> HardwareCandidateKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> HardwareCandidateKey.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> HardwareCandidateKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> HardwareCandidateKey.DOWN
        KeyEvent.KEYCODE_ESCAPE -> HardwareCandidateKey.CANCEL
        KeyEvent.KEYCODE_SPACE -> HardwareCandidateKey.SPACE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> HardwareCandidateKey.ENTER
        else -> if (hardwareCandidateDigitIndex(event.keyCode, event.isShiftPressed) != null)
            HardwareCandidateKey.DIGIT else return false
    }
    hardwareNavigation.startPress(key, repeat = event.repeatCount > 0)

    // This read waits for the engine lock and never consumes commit text. In
    // particular, a rapid letter followed by an arrow cannot see an old empty UI.
    val composition = service.rimeEngine.readQueuedComposition()
    if (composition != null &&
        service.candidateState.value.engineRevision != composition.engineRevision) {
        val transformed = service.candidateTransform.transform(composition.input, composition.preedit,
            composition.candidates.toList(), composition.isAsciiMode)
        val injections = if (isT9Schema(service.uiState.value.currentSchemaId)) {
            service.candidateTransform.transformForT9(com.kingzcheung.xime.rime.RimeProcessResult(
                true, "", composition.input, composition.preedit, composition.candidates,
                composition.isAsciiMode, composition.hasNextPage, composition.hasPrevPage,
                engineRevision = composition.engineRevision,
            )).orEmpty()
        } else emptyList()
        withEditor {
            if (service.rimeEngine.isCandidateRevisionCurrent(composition.engineRevision)) {
                service.sessionController.applyComposition(
                    transformed?.let { composition.copy(candidates = it.candidates.toTypedArray()) } ?: composition,
                    transformed?.actions ?: emptyList(), injections,
                )
            }
        }
    }
    val snapshot = withEditor {
        val ui = service.uiState.value
        hardwareCandidateSnapshot(service.candidateState.value,
            ui.isAsciiMode || ui.inputProfile.language.typesLettersDirectly,
            service.predictionManager.hasPendingPrediction)
    }
    val engineHasInput = composition
        ?.takeIf { service.rimeEngine.isCandidateRevisionCurrent(it.engineRevision) }
        ?.let { it.input.isNotEmpty() || service.t9PartialSegments.isNotEmpty() }
    val digitIndex = hardwareCandidateDigitIndex(event.keyCode, event.isShiftPressed)
    // While pinyin is being composed, physical arrows move through the candidates (as on
    // Samsung Keyboard and Gboard) instead of committing the raw letters and moving the caret.
    val composingArrow = key in listOf(HardwareCandidateKey.LEFT, HardwareCandidateKey.RIGHT,
        HardwareCandidateKey.UP, HardwareCandidateKey.DOWN) &&
        !snapshot.english && snapshot.state.pendingEnglishText.isEmpty() &&
        (engineHasInput == true || snapshot.state.isComposing) && snapshot.words.isNotEmpty()
    if (!pageAtBoundary && composingArrow && (key == HardwareCandidateKey.LEFT || key == HardwareCandidateKey.RIGHT)) {
        if (!isT9Schema(service.uiState.value.currentSchemaId)) {
            // 逐个字母移动，到两端停住；Rime 自带的左右键按音节跳、到头还会绕回另一端。
            val result = service.rimeEngine.moveCompositionCaret(if (key == HardwareCandidateKey.LEFT) -1 else 1)
            if (result != null) {
                // 拼音条标出光标位置，与屏幕键盘编辑拼音时看到的一致；光标回到末尾时不标。
                val snap = service.rimeEngine.readPinyinEditSnapshot()
                val marked = markPreeditCaret(result.preeditText, snap.getOrNull(0).orEmpty(),
                    snap.getOrNull(1)?.toIntOrNull() ?: -1, snap.getOrNull(2)?.toIntOrNull() ?: 0)
                sendTransformedResult(if (marked != null) result.copy(preeditText = marked) else result)
            }
        }
        hardwareNavigation.retainPress(key)
        return true
    }
    val decision = if (pageAtBoundary || composingArrow) hardwareNavigation.decide(key, snapshot, engineHasInput,
        pageAtBoundary = true, digitIndex = digitIndex)
    // 空格不读工具栏高亮，原因见 physicalCandidateDecision 的说明。
    else physicalCandidateDecision(key, snapshot, engineHasInput, digitIndex)
    if (com.kingzcheung.xime.BuildConfig.DEBUG) {
        Log.d("HardwareNavigation", "key=$key decision=$decision engineInput=$engineHasInput " +
            "words=${snapshot.words.size} compact=${service.uiState.value.isCompact} " +
            "revision=${snapshot.state.engineRevision}")
    }
    if (decision != HardwareCandidateDecision.Host) hardwareNavigation.retainPress(key)
    when (decision) {
        HardwareCandidateDecision.MoveCursor -> {
            if (commitHardwareLiteral("")) {
                withEditor { service.schemaController.moveHardwareCursor(event) }
            }
        }
        HardwareCandidateDecision.LiteralSpace -> commitHardwareLiteral(" ")
        HardwareCandidateDecision.CommitTyped -> commitHardwareLiteral("")
        is HardwareCandidateDecision.Highlight -> withEditor { publishHardwareCandidateSelection() }
        is HardwareCandidateDecision.Page -> {
            if (snapshot.expanded) {
                withEditor {
                    service.expandedPageScroll(decision.direction)
                    publishHardwareCandidateSelection()
                }
            } else {
                pageHardwareCandidates(snapshot.state, decision.direction)
                withEditor {
                    publishHardwareCandidateSelection()
                    service.updateUI()
                }
            }
        }
        is HardwareCandidateDecision.Confirm -> {
            if (decision.association) {
                withEditor {
                    val current = hardwareCandidateSnapshot(service.candidateState.value, snapshot.english,
                        service.predictionManager.hasPendingPrediction)
                    if (snapshot.sameSource(current)) {
                        selectAssociation(decision.index)
                    }
                }
            } else if (snapshot.expandedGlobalIndices != null) {
                selectCandidateGlobalAsync(snapshot.expandedGlobalIndices[decision.index], snapshot.state)
                withEditor { publishHardwareCandidateSelection() }
            } else {
                selectCandidateAsync(snapshot.windowStart + decision.index, snapshot = snapshot.state)
                withEditor { publishHardwareCandidateSelection() }
            }
        }
        HardwareCandidateDecision.Cancel -> {
            // Japanese conversion is separate from Rime composition. Cancel it
            // before clearing input; already committed English text is untouched.
            service.japaneseInputController.handleKey("clear_composition")
            clearInputStateForKeys()
            withEditor {
                publishHardwareCandidateSelection()
                service.maybeCollapseCandidatePage()
            }
        }
        // A touch candidate-arrow with no candidates has nothing to navigate.
        HardwareCandidateDecision.Host -> Unit
        HardwareCandidateDecision.Consume -> Unit
        HardwareCandidateDecision.DefaultInput -> {
            if (!snapshot.state.isComposing && snapshot.state.inputText.isEmpty()) {
                withEditor {
                    service.predictionManager.invalidatePendingPredictions()
                    service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList())
                    hardwareNavigation.clear()
                    publishHardwareCandidateSelection()
                }
            }
            return false
        }
    }
    return true
}
