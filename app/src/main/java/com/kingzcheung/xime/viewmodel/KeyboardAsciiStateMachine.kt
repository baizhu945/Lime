package com.kingzcheung.xime.viewmodel

import com.kingzcheung.xime.ui.keyboard.AsciiKeyboardContext

/** 把当前方案切到英文，不走语言键的循环。 */
internal const val ASCII_CONTEXT_ON = "ascii_context_on"
/** 把当前方案切回它自己的语言，不走语言键的循环。 */
internal const val ASCII_CONTEXT_OFF = "ascii_context_off"

/**
 * 面板进入时跟随当前引擎语言；面板内的切换不改变主键盘的记忆。
 * 从任意面板返回主键盘时恢复离开主键盘时的语言。数字页进出不主动切换语言。
 * 引擎状态是权威源，调用方在上下文切换时保存离开的状态并应用目标状态。
 */
class KeyboardAsciiStateMachine {

    private var mainAsciiMode: Boolean? = null

    /** 仅记录主键盘的状态，面板状态不影响下次进入面板。 */
    fun saveMemory(context: AsciiKeyboardContext, engineAscii: Boolean) {
        if (context == AsciiKeyboardContext.MAIN) mainAsciiMode = engineAscii
    }

    /** 返回需要切换到的模式；无需切换时返回 null。 */
    fun targetFor(context: AsciiKeyboardContext, engineAscii: Boolean): Boolean? {
        if (context != AsciiKeyboardContext.MAIN) return null
        val target = mainAsciiMode ?: engineAscii
        return if (target != engineAscii) target else null
    }

    /** 新输入会话：清空记忆，从引擎当前状态重新开始。 */
    fun reset() {
        mainAsciiMode = null
    }
}
