package com.kingzcheung.xime.service

/** 触觉按操作类型分发；后续线性马达波形只需在反馈层按此类型绑定。 */
enum class KeyFeedbackType(val sound: String = "standard") {
    CHARACTER, SYMBOL, NUMBER, SPACE("space"), DELETE("delete"), ENTER("enter"),
    MODE_SWITCH, MODIFIER, NAVIGATION, EDITING, TOOLBAR, CURSOR_STEP, HANDWRITING_ERASE("handwriting_erase");

    companion object {
        fun fromKey(key: String): KeyFeedbackType = when (key.lowercase().removePrefix("select_").removePrefix("arrow_")) {
            "delete", "backspace", "clear_composition", "clear_all" -> DELETE
            "enter" -> ENTER
            "space" -> SPACE
            "ime_switch", "ascii", "mode_change", "mode_change_symbol", "mode_change_number", "mode_change_common_symbol",
            "mode_change_last" -> MODE_SWITCH
            "shift", "japanese_modify", "japanese_convert", "japanese_undo" -> MODIFIER
            "left", "right", "up", "down", "home", "end", "line_start", "line_end", "paragraph_start", "paragraph_end", "japanese_left", "japanese_right" -> NAVIGATION
            "copy", "cut", "paste", "all", "begin" -> EDITING
            "cursor_step" -> CURSOR_STEP
            "handwriting_erase" -> HANDWRITING_ERASE
            "toolbar" -> TOOLBAR
            "number" -> NUMBER
            "symbol" -> SYMBOL
            else -> if (key.length == 1 && key[0].isDigit()) NUMBER
                else if (key.length == 1 && !key[0].isLetter()) SYMBOL else CHARACTER
        }
    }
}
