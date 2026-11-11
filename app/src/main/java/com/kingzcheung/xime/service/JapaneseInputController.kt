package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeProcessResult
import com.kingzcheung.xime.rime.japanesePreedit
import com.kingzcheung.xime.rime.deleteJapaneseRomaji
import com.kingzcheung.xime.settings.JapaneseSchemas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 所有方法在按键队列串行调用；会话或原编码改变就作废旧转换预览。 */
internal class JapaneseInputController(private val service: XimeInputMethodService) {
    /**
     * 转换预览：非空表示 preedit 已是假名/汉字预览。
     * UI 出口可能在主线程经 [normalizePreedit] 读取，故用 volatile。
     */
    @Volatile private var conversion: JapaneseConversion? = null
    private var session = -1L
    private var schema = ""
    private val engine get() = service.rimeEngine
    /** 按键路径的判定：引擎是权威源（切方案/部署期间可能领先或落后 uiState 一帧）。 */
    private fun available(): Boolean = !engine.isAsciiMode() &&
        com.kingzcheung.xime.settings.InputProfiles.describe(engine.getCurrentSchema()).capabilities.japaneseConversion

    /** 显示出口的判定：只读 uiState，不碰引擎——UI 每次刷新都会调用，不付出 JNI / 锁开销。 */
    private fun displayAvailable(): Boolean {
        val state = service.uiState.value
        return state.inputProfile.capabilities.japaneseConversion
    }
    fun displayCandidates(input: String) = conversion?.takeIf { it.input == input && session == service.uiState.value.inputSessionId }?.candidates?.toList()

    /**
     * 显示层归一化：末尾尚未拼完的罗马音显示为按下的字母（引擎回显会给 っ / ん）。
     *
     * 纯字符串运算、不改引擎状态、不持锁，因此由两个 UI 出口统一调用
     * （updateUIWithResult / applyComposition），按键后的刷新路径同样生效。
     * 转换预览期间 preedit 是假名/汉字预览，跳过归一化；非日语方案原样返回。
     */
    fun normalizePreedit(input: String, preedit: String): String =
        if (conversion == null && preedit.isNotEmpty() && displayAvailable()) japanesePreedit(input, preedit) else preedit

    private fun current(): JapaneseConversion? {
        if (!available() || session != service.uiState.value.inputSessionId || schema != engine.getCurrentSchema() || conversion?.input != engine.getInput()) conversion = null
        return conversion
    }
    private fun start(converted: Boolean = true, caretKana: Int? = null): JapaneseConversion? {
        current()?.let { return it }
        val input = engine.getInput()
        if (input.isEmpty()) return null
        session = service.uiState.value.inputSessionId
        schema = engine.getCurrentSchema()
        return JapaneseConversion(engine, input, converted, caretKana).also { conversion = it; it.refresh() }
    }
    private suspend fun show() {
        val active = current()
        val result = engine.getProcessResult(true).let {
            // 转换预览显示假名本身；无转换时由 updateUIWithResult 出口做罗马音归一化（显示按下的字母）
            if (active == null) it
            else it.copy(preeditText = active.preview, candidates = active.candidates, hasNextPage = false, hasPrevPage = false)
        }
        val owner = service.uiState.value.inputSessionId
        withContext(Dispatchers.Main) {
            InputCommandOwner.requireOwner().requireCurrent(service.inputReadiness, service.uiState.value.inputSessionId)
            if (owner == service.uiState.value.inputSessionId) service.sessionController.updateUIWithResult(result)
        }
    }
    suspend fun cancel() { conversion = null; show() }
    /** 只挪了光标、没转换时没有转换预览（预览里有光标竖线，不能上屏）。 */
    fun conversionPreview(): String? = current()?.takeIf { it.converted }?.preview

    /** A literal ends Japanese as displayed kana, not its romaji encoding or first kanji candidate. */
    fun literalPreview(): String? {
        if (!available()) return null
        conversionPreview()?.let { return it }
        // Read the engine on the serial key queue, not candidateState which can lag a UI frame.
        val composition = engine.getComposition()
        return japanesePreedit(composition.input, composition.preedit)
            .filterNot { it.isWhitespace() || it == '\'' || it == PREEDIT_CARET }
    }

    suspend fun commit(index: Int? = null): Boolean {
        val active = current() ?: return false
        // 点候选：只上屏这一段，后面的读音留着接着打。
        if (index != null) return confirmSegment(index)
        // 只挪了光标：回到平常的打字状态，由回车等按键按原来的规则结束。
        if (!active.converted) { conversion = null; return false }
        val text = active.preview
        val owner = session
        conversion = null
        if (owner == service.uiState.value.inputSessionId) service.keyRouter.commitSelectedText(text)
        return true
    }

    /**
     * 确认当前段（空格、点候选）：上屏光标前或转换段转出的字，后面的读音留在编码里接着打、接着转换
     * （Gboard、iOS 日文键盘同样）。当前段已到末尾时与整段上屏相同。
     */
    private suspend fun confirmSegment(index: Int? = null): Boolean {
        val active = current() ?: return false
        if (index != null) active.choose(index)
        val text = active.segment
        val rest = active.rest
        val owner = session
        conversion = null
        if (owner != service.uiState.value.inputSessionId) return true
        if (rest.isEmpty()) { service.keyRouter.commitSelectedText(text); return true }
        if (service.keyRouter.commitPreparedText(text, t9 = false).accepted && owner == service.uiState.value.inputSessionId) {
            engine.setInput(rest)
            show()
        }
        return true
    }
    suspend fun prepareKana(modify: Boolean) { if (current() != null) { if (modify) cancel() else commit() } }
    suspend fun handleKey(key: String): Boolean {
        if (!available()) { conversion = null; return false }
        when (key) {
            // 已转换：再按换下一个候选；只挪过光标：把光标前那段转成首选。
            "japanese_convert" -> {
                val old = current(); val active = start()
                if (old != null) { if (old.converted) active?.cycle() else active?.convert() }
                show(); return true
            }
            "japanese_undo" -> { cancel(); return true }
            // 日文翻页键盘的左右键：没在打字时移动正文光标；打字中按假名移动读音里的光标（候选只给光标前那段）；
            // 按过変換后调整转换段长短。光标回到末尾时回到平常的打字状态。
            "japanese_left", "japanese_right" -> {
                val steps = if (key.endsWith("left")) -1 else 1
                val old = current()
                if (old == null && engine.getInput().isEmpty()) {
                    service.schemaController.moveJapaneseCursorFromKeyQueue(steps)
                    return true
                }
                // 光标已在读音末尾，右键无处可去。
                if (old == null && steps > 0) return true
                val active = old ?: start(converted = false) ?: return true
                val moved = active.moveRange(steps)
                if (!active.converted && !active.partial) conversion = null
                if (moved || old == null) show()
                return true
            }
            "delete", "clear_composition", "clear_all" -> conversion = null
            "space" -> if (confirmSegment()) return true
            "enter" -> if (commit()) return true
            // 只挪过光标时不上屏，先收起光标再交给原来的按键处理。
            else -> current()?.let { if (it.converted) commit() else cancel() }
        }
        return false
    }
    suspend fun deleteKana(): Boolean {
        if (!available()) return false
        val input = engine.getInput()
        if (input.isEmpty()) { conversion = null; return false }
        val active = current()
        conversion = null
        if (active != null && !active.converted && active.partial) {
            // 光标在读音中间：删光标前的那个假名，光标留在原处（到了开头就回到末尾）。
            val (next, caretKana) = active.withoutKanaBeforeCaret()
            engine.setInput(next)
            if (caretKana > 0) start(converted = false, caretKana = caretKana)
            if (conversion?.partial == false) conversion = null
            show()
            return true
        }
        // 删除单位与显示同源（romajiDeleteStart）：未拼完的罗马音只删一个字母，
        // 已拼完的假名整体删。不能再用引擎读音长度探测边界——ん 与 っ 等长时
        // 判不出假名边界，会把整串一次删光（2026-09-25 复现）。
        engine.setInput(deleteJapaneseRomaji(input))
        show()
        return true
    }
}
