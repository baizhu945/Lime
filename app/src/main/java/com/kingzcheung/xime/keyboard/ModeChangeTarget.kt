package com.kingzcheung.xime.keyboard

/**
 * 底排 ?123 单键点按去哪个面板：上次用过的「数字」或「常用符号」，默认数字（同 Xime 原实现）。
 *
 * 这里只描述路由，不碰输入引擎、不建队列；KeyboardView 读它以后仍走原来的
 * `viewModel.enterPanel(PanelType.NUMBER/COMMON_SYMBOL)`。
 */
enum class ModeChangeTarget(val prefValue: String, val longPressLabel: String) {
    NUMBER("number", "数字"),
    COMMON_SYMBOL("common_symbol", "常用符号");

    /** 长按气泡选中这一项后发出的既有按键路由（和长按菜单外的老配置一致）。 */
    val action: String get() = when (this) {
        NUMBER -> MODE_CHANGE_NUMBER
        COMMON_SYMBOL -> MODE_CHANGE_COMMON_SYMBOL
    }

    /** 这个目标对应的键盘页；单键点按和长按直达都用它。 */
    val panel: PanelType get() = when (this) {
        NUMBER -> PanelType.NUMBER
        COMMON_SYMBOL -> PanelType.COMMON_SYMBOL
    }

    companion object {
        /** 没设置过（新装、旧版本没写过这个键）就是数字页。 */
        val DEFAULT = NUMBER
    }
}

/** ?123 单键点按：进上次用过的页面。 */
const val MODE_CHANGE_LAST = "mode_change_last"
/** 直达数字页（既有路由，语义不变）。 */
const val MODE_CHANGE_NUMBER = "mode_change_number"
/** 直达常用符号页（既有路由，语义不变）。 */
const val MODE_CHANGE_COMMON_SYMBOL = "mode_change_common_symbol"

/** 长按气泡里的两项，顺序就是气泡里的左右顺序。 */
val MODE_CHANGE_LONG_PRESS_LABELS: List<String> = ModeChangeTarget.entries.map { it.longPressLabel }

/** 存下来的偏好值 → 目标；空值和认不出的旧值一律回到默认「数字」。 */
fun modeChangeTargetFromPreference(value: String?): ModeChangeTarget =
    ModeChangeTarget.entries.firstOrNull { it.prefValue == value } ?: ModeChangeTarget.DEFAULT

/** 长按气泡里选中的那一项 → 目标；键面文字和配置里的动作名都认，认不出返回 null（不猜）。 */
fun modeChangeTargetForSelection(selection: String): ModeChangeTarget? = when (selection) {
    ModeChangeTarget.NUMBER.longPressLabel, MODE_CHANGE_NUMBER -> ModeChangeTarget.NUMBER
    ModeChangeTarget.COMMON_SYMBOL.longPressLabel, MODE_CHANGE_COMMON_SYMBOL -> ModeChangeTarget.COMMON_SYMBOL
    else -> null
}

/** 选中一项后要发哪条既有路由；认不出的选项不发任何按键。 */
fun modeChangeActionForSelection(selection: String): String? =
    modeChangeTargetForSelection(selection)?.action
