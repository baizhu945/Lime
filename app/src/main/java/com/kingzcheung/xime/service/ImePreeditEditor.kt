package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.PinyinEditBuffer
import com.kingzcheung.xime.rime.PinyinEditSession
import com.kingzcheung.xime.rime.PinyinEditDisplay
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No InputConnection writes: only the unconfirmed Rime code can be edited. */
internal class ImePreeditEditor(private val service: XimeInputMethodService) {
    private val multiTap = com.kingzcheung.xime.rime.PinyinMultiTap()
    private var liveOwner: PinyinEditSession? = null
    private var liveSession: PinyinEditSession? = null

    private fun enqueue(schema: String, block: suspend () -> Unit) {
        val t9Queue = if (isT9Schema(schema)) service.keyboardCallbacks?.onT9RunLiteralInput else null
        if (t9Queue != null) t9Queue(block) else service.keyRouter.postRimeJob { block() }
    }

    fun open(reply: (PinyinEditSession?) -> Unit) {
        val state = service.uiState.value
        if (state.isAsciiMode || !PinyinEditBuffer.supports(state.currentSchemaId)) { reply(null); return }
        enqueue(state.currentSchemaId) {
            // Drain a preceding nine-key UI snapshot before reading its protected partial segments.
            withContext(Dispatchers.Main) { Unit }
            if (service.uiState.value.inputSessionId != state.inputSessionId ||
                service.uiState.value.currentSchemaId != state.currentSchemaId) return@enqueue
            val engine = service.rimeEngine
            val snapshot = engine.readPinyinEditSnapshot()
            val input = snapshot.getOrNull(0).orEmpty()
            val confirmed = snapshot.getOrNull(2)?.toIntOrNull()?.coerceIn(0, input.length) ?: 0
            val t9 = isT9Schema(state.currentSchemaId)
            val rawText = if (t9) snapshot.getOrNull(4).orEmpty() else input.drop(confirmed)
            val normalized = PinyinEditBuffer.normalized(rawText)
            val unsupportedCode = rawText.any { it !in 'a'..'z' && it !in 'A'..'Z' && it != '\'' && !it.isWhitespace() && it != 'ü' }
            val prefix = if (t9) service.t9PartialSegments.joinToString("") { it.text }
                else snapshot.getOrNull(3).orEmpty()
            val display = PinyinEditDisplay(normalized, snapshot.getOrNull(4).orEmpty().removePrefix(prefix).trim(), t9, isMerged14 = com.kingzcheung.xime.settings.InputProfiles.describe(state.currentSchemaId).layout == com.kingzcheung.xime.settings.InputLayout.MERGED14, groups = com.kingzcheung.xime.settings.CustomKeyboardLayouts.forSchema(state.currentSchemaId)?.rows?.flatten() ?: if (com.kingzcheung.xime.settings.InputProfiles.describe(state.currentSchemaId).layout == com.kingzcheung.xime.settings.InputLayout.MERGED14) com.kingzcheung.xime.rime.merged14Groups else emptyList())
            val text = display.text
            // Keep the visible separators in the editor buffer. Merely opening/moving
            // does not rewrite Rime or lock a nine-key translation; a real edit does.
            val session = if (input.isEmpty() || text.isEmpty() || unsupportedCode) null
            else PinyinEditSession(state.inputSessionId, state.currentSchemaId, input,
                if (t9) "" else input.take(confirmed), prefix, text,
                if (t9) com.kingzcheung.xime.rime.offsetAfterCodeKeys(text, com.kingzcheung.xime.rime.codeKeysBefore(input, snapshot.getOrNull(1)?.toIntOrNull() ?: input.length)) else display.displayOffset(((snapshot.getOrNull(1)?.toIntOrNull() ?: input.length) - confirmed).coerceIn(0, normalized.length)), t9, if (t9) engine.t9GetRemainingDigits() else "", service.uiState.value.t9RightCandidateSelectedCount)
            withContext(Dispatchers.Main) {
                if (service.uiState.value.inputSessionId == state.inputSessionId &&
                    service.uiState.value.currentSchemaId == state.currentSchemaId) {
                    if (unsupportedCode) android.widget.Toast.makeText(service, "请先选择左侧拼音，再编辑编码", android.widget.Toast.LENGTH_SHORT).show()
                    multiTap.reset()
                    liveOwner = session
                    liveSession = session
                    reply(session)
                }
            }
        }
    }

    /** Reuse the real keyboard. All edits share its FIFO and update composition immediately. */
    fun edit(owner: PinyinEditSession, key: String?, selection: Int?, replacement: String?, reply: (PinyinEditSession?) -> Unit) {
        val eventTime = android.os.SystemClock.uptimeMillis()
        enqueue(owner.schemaId) {
            val session = liveSession
            if (liveOwner !== owner || !owner.isActive() || session == null || !isOwnerCurrent(session)) {
                withContext(Dispatchers.Main) { reply(null) }; return@enqueue
            }
            var text = session.text
            var caret = (selection ?: session.caret).coerceIn(0, text.length)
            val letters = when {
                com.kingzcheung.xime.settings.InputProfiles.describe(session.schemaId).layout == com.kingzcheung.xime.settings.InputLayout.MERGED14 ->
                    com.kingzcheung.xime.rime.merged14MultiTapGroup(key)
                else -> null
            }
            // Automatic reading separators are editable display boundaries, not T9 digits.
            // Removing one must not rebuild or lock the underlying ambiguous code.
            val displaySeparatorDelete = if (session.isT9 && key == "delete" && replacement == null && selection == null)
                com.kingzcheung.xime.rime.t9DeleteAutomaticSeparator(text, caret, session.expectedInput) else null
            // Other T9 edits rebuild plain ambiguous keys rather than fixed letters.
            val t9Key = displaySeparatorDelete == null && session.isT9 && replacement == null && selection == null &&
                (key?.length == 1 && key[0] in '2'..'9' || key == "delete" && text.take(caret).any { it != '\'' })
            if (letters == null || replacement != null || selection != null) multiTap.reset()
            if (displaySeparatorDelete != null) {
                text = displaySeparatorDelete.first
                caret = displaySeparatorDelete.second
            } else if (t9Key) {
                val keys = text.filter { it != '\'' }.map(::t9Digit).joinToString("")
                val before = text.take(caret).count { it != '\'' }
                if (key == "delete") {
                    text = keys.removeRange(before - 1, before)
                    caret = before - 1
                } else {
                    text = keys.take(before) + key + keys.drop(before)
                    caret = before + 1
                }
            } else if (letters != null && replacement == null && selection == null) {
                val edited = multiTap.press(text, caret, letters, eventTime)
                text = edited.first; caret = edited.second
            } else if (replacement != null) {
                text = if (session.isT9) PinyinEditBuffer.normalizedT9(replacement) else PinyinEditBuffer.normalized(replacement)
                caret = text.length
            } else if (key?.startsWith("preedit_cursor:") == true) {
                val movement = key.substringAfter(":")
                caret = when (movement) {
                    "start" -> 0
                    "end" -> text.length
                    else -> (caret + (movement.toIntOrNull() ?: 0)).coerceIn(0, text.length)
                }
            } else if (key == "delete") {
                if (caret > 0) { text = text.removeRange(caret - 1, caret); caret-- }
            } else if (key != null) {
                val insertion = if (session.isT9 && key == "1") "'" else key.lowercase()
                text = text.take(caret) + insertion + text.drop(caret)
                caret += insertion.length
            }
            val engine = service.rimeEngine
            val changed = text != session.text && displaySeparatorDelete == null
            val result = engine.editPinyinAndReadState {
                // Candidates follow the caret. Merely moving it must not lock an ambiguous
                // nine-key reading, so only the engine caret moves; an edit rebuilds the keys first.
                if (session.isT9) (!changed || engine.applyT9PinyinEdit(session.expectedInput, text,
                    session.t9RemainingDigits, session.schemaId) { owner.isActive() && isOwnerCurrent(session) }) &&
                    engine.placeT9CodeCaret(com.kingzcheung.xime.rime.codeKeysBefore(text, caret),
                        if (changed) null else session.expectedInput, session.schemaId) { owner.isActive() && isOwnerCurrent(session) }
                else {
                    val engineText = if (changed) session.protectedInput + (com.kingzcheung.xime.settings.CustomKeyboardLayouts.find(session.schemaId)?.encode(text) ?: text) else session.expectedInput
                    val engineCaret = if (changed) caret else PinyinEditDisplay(
                        session.expectedInput.drop(session.protectedInput.length), session.text, isMerged14 = com.kingzcheung.xime.settings.InputProfiles.describe(session.schemaId).layout == com.kingzcheung.xime.settings.InputLayout.MERGED14, groups = com.kingzcheung.xime.settings.CustomKeyboardLayouts.forSchema(session.schemaId)?.rows?.flatten() ?: if (com.kingzcheung.xime.settings.InputProfiles.describe(session.schemaId).layout == com.kingzcheung.xime.settings.InputLayout.MERGED14) com.kingzcheung.xime.rime.merged14Groups else emptyList()).rawOffset(caret)
                    engine.applyPinyinEdit(session.expectedInput, engineText,
                        session.protectedInput.length + engineCaret, session.protectedInput.length,
                        session.protectedText, session.schemaId) { owner.isActive() && isOwnerCurrent(session) }
                }
            }
            if (result == null) { withContext(Dispatchers.Main) { reply(null) }; return@enqueue }
            if (t9Key) {
                // Show the engine's reading of the plain keys, caret right after the new key.
                val display = PinyinEditDisplay(text, result.preeditText.removePrefix(session.protectedText).trim(), true)
                caret = display.displayOffset(caret)
                text = display.text
            }
            val next = session.copy(expectedInput = result.inputText, text = text, caret = caret,
                t9RemainingDigits = if (session.isT9) engine.t9GetRemainingDigits() else "")
            liveSession = next
            withContext(Dispatchers.Main) {
                if (!owner.isActive() || !isOwnerCurrent(session)) return@withContext
                if (session.isT9) service.keyboardCallbacks?.onT9RefreshAfterPreeditEdit?.invoke()
                // Moving the code caret updates candidates only, never the host's
                // composing region or selection. A real code edit still updates it.
                service.sessionController.updateUIWithResult(result, updateInputBoxComposition = changed)
                reply(next)
            }
        }
    }

    private fun t9Digit(c: Char): Char = when (c) {
        in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'; in 'j'..'l' -> '5'
        in 'm'..'o' -> '6'; in 'p'..'s' -> '7'; in 't'..'v' -> '8'; in 'w'..'z' -> '9'; else -> c
    }

    private fun isOwnerCurrent(session: PinyinEditSession): Boolean = service.uiState.value.let {
        it.inputSessionId == session.inputSessionId && it.currentSchemaId == session.schemaId && !it.isAsciiMode &&
            (!session.isT9 || (it.t9RightCandidateSelectedCount == session.t9SelectionCount &&
                service.t9PartialSegments.joinToString("") { part -> part.text } == session.protectedText))
    }

}
