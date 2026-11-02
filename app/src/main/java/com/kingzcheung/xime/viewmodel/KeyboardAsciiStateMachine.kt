package com.kingzcheung.xime.viewmodel

import com.kingzcheung.xime.ui.keyboard.AsciiKeyboardContext

/** 把当前方案切到英文，不走语言键的循环。 */
internal const val ASCII_CONTEXT_ON = "ascii_context_on"
/** 把当前方案切回它自己的语言，不走语言键的循环。 */
internal const val ASCII_CONTEXT_OFF = "ascii_context_off"

/**
 * 键盘 ascii 状态机 — 主键盘和符号面板各自记住中文还是英文。
 *
 * 引擎状态是权威源，本状态机只在键盘切换时（attach/detach）同步引擎：
 * - detach（离开键盘）：把引擎当前状态保存为该键盘的记忆
 * - attach（进入键盘）：返回该键盘的记忆作为目标模式，与引擎不一致时由调用方发起切换
 *
 * 语义：
 * - 符号面板内的中英切换只影响该面板的记忆，退出面板后主键盘恢复自己的记忆
 * - 面板记忆持久，再次进入面板恢复上次的状态
 * - 符号面板首次进入默认英文
 * - 数字页没有中英键，进出都不改引擎。不能拿语言键去“恢复记忆”：语言键在中文、英文、日语之间循环，
 *   英文下点 ?123 就会在数字页曾用过中文时跳到下一种语言
 */
class KeyboardAsciiStateMachine {

    private var mainAsciiMode: Boolean? = null
    private var numberAsciiMode: Boolean? = null
    private var symbolAsciiMode: Boolean? = null

    /** detach：保存当前键盘的引擎状态为该键盘的记忆 */
    fun saveMemory(context: AsciiKeyboardContext, engineAscii: Boolean) {
        when (context) {
            AsciiKeyboardContext.MAIN -> mainAsciiMode = engineAscii
            AsciiKeyboardContext.NUMBER_PANEL -> numberAsciiMode = engineAscii
            AsciiKeyboardContext.SYMBOL_PANEL -> symbolAsciiMode = engineAscii
        }
    }

    /**
     * attach：返回目标引擎模式；与引擎一致时返回 null（无需切换）。
     * 尚未记录记忆的上下文以引擎当前状态为目标（符号面板首次进入默认英文）。
     */
    fun targetFor(context: AsciiKeyboardContext, engineAscii: Boolean): Boolean? {
        // 数字页的记忆不参与切换。上次在中文里打开过数字页后，英文下再点 ?123 不能因此改语言。
        if (context == AsciiKeyboardContext.NUMBER_PANEL) return null
        val target = when (context) {
            AsciiKeyboardContext.MAIN -> mainAsciiMode ?: engineAscii
            AsciiKeyboardContext.SYMBOL_PANEL -> symbolAsciiMode ?: true
            AsciiKeyboardContext.NUMBER_PANEL -> engineAscii
        }
        return if (target != engineAscii) target else null
    }

    /** 新输入会话：清空全部记忆，从引擎当前状态重新开始 */
    fun reset() {
        mainAsciiMode = null
        numberAsciiMode = null
        symbolAsciiMode = null
    }
}