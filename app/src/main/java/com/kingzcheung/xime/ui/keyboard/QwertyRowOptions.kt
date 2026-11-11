package com.kingzcheung.xime.ui.keyboard

/**
 * 26 键三行字母的错位方式。
 * - 实体键盘：A 行比 Q 行右移 1/4 键，Z 行右移 3/4 键（ANSI 键盘）。
 * - 手机常见：A 行右移半键，Z 与 S 上下对齐。
 * - 按尺寸自动：手机宽度用手机排布，键宽接近实体键距时逐渐过渡到实体排布（原有行为）。
 */
enum class QwertyStaggerMode(val label: String) {
    AUTO("按尺寸自动"), PHYSICAL("实体键盘"), PHONE("手机常见"), CUSTOM("自定义")
}

/** 0 = 手机常见（s 与 z 对齐），1 = 实体键盘。 */
internal fun resolvedQwertyStagger(
    mode: QwertyStaggerMode, custom: Float, cellWidthDp: Float, targetCellDp: Float?,
): Float = when (mode) {
    QwertyStaggerMode.AUTO -> wideQwertyStagger(cellWidthDp, targetCellDp)
    QwertyStaggerMode.PHYSICAL -> 1f
    QwertyStaggerMode.PHONE -> 0f
    QwertyStaggerMode.CUSTOM -> custom.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
}

/** 底排的一个标点键：点按输入 [primary]，滑动输入 [alternate]。 */
internal data class SpacePunctuationKey(val primary: String, val alternate: String)

/**
 * 底排逗号、句号各自独立开关；标准 ?123 布局相邻，旧双模式键布局仍分居空格两侧。
 * 整个键盘只有这两个键跟随全角/半角。
 * - 两个都开：点按输入当前宽度，滑动输入另一种宽度（日文的全角逗号是 、）。
 * - 只开一个：角标（滑动/长按）换成缺的那个符号，同宽度，保证逗号句号都能打；默认两键都开。
 * 英文输入不分全角半角：两个都开时只有 , 和 .，没有副字符；只开一个时角标是另一个。
 */
internal fun spacePunctuationKeys(comma: Boolean, period: Boolean, fullWidth: Boolean, english: Boolean = false,
    japanese: Boolean = false): Pair<SpacePunctuationKey?, SpacePunctuationKey?> {
    val fullComma = if (japanese) "、" else "，"
    val wide = fullWidth && !english
    val commaText = if (wide) fullComma else ","
    val periodText = if (wide) "。" else "."
    if (comma != period) {
        return if (comma) Pair(SpacePunctuationKey(commaText, periodText), null)
        else Pair(null, SpacePunctuationKey(periodText, commaText))
    }
    if (!comma) return Pair(null, null)
    val commaKey = when {
        english -> SpacePunctuationKey(",", "")
        fullWidth -> SpacePunctuationKey(fullComma, ",")
        else -> SpacePunctuationKey(",", fullComma)
    }
    val periodKey = when {
        english -> SpacePunctuationKey(".", "")
        fullWidth -> SpacePunctuationKey("。", ".")
        else -> SpacePunctuationKey(".", "。")
    }
    return Pair(commaKey, periodKey)
}

/** 拼音的音节分隔符，Rime 方案的 delimiter 里就是它（xi'an）。 */
internal const val SYLLABLE_SEPARATOR = "'"

/**
 * 「逗号键分词」（默认关，B 站评论要的）只管中文全拼、双拼：英文、日文、注音、仓颉等没有拼音音节，照旧。
 * 开着时：空格旁的标点键左下角是 '；打拼音时它是分词，没在打拼音时上屏 '（印什么出什么）。
 */
internal fun syllableSplitActive(setting: Boolean, ascii: Boolean, profile: com.kingzcheung.xime.settings.InputProfile): Boolean =
    setting && !ascii && profile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE &&
        profile.capabilities.editablePinyin

/**
 * 字母键帽这次显示大写吗（只管显示，送出的字母不变）：「字母键显示大写」一律大写；
 * 「中文键盘字母显示大写」只在中文（非英文模式）时大写，英文照 Shift，一看键帽就知道是中文还是英文。
 */
internal fun letterLabelsUppercase(prefs: KeyboardInputPreferences, ascii: Boolean, profile: com.kingzcheung.xime.settings.InputProfile): Boolean =
    prefs.uppercaseKeyLabels || prefs.chineseUppercaseKeyLabels && !ascii &&
        profile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE

/** 哪个标点键带分词角标：有逗号键放逗号键，只开句号键放句号键，两个都关就没有（first 左、second 右）。 */
internal fun syllableSplitOnPunctuation(active: Boolean, left: SpacePunctuationKey?, right: SpacePunctuationKey?): Pair<Boolean, Boolean> =
    if (!active) false to false else if (left != null) true to false else false to (right != null)

/**
 * 字母键角标里的 '（26 键 F 的下角标）在分词开着时走按键：打拼音时是分词，不再把字母原样上屏再接 '；
 * 没在打拼音时照旧上屏 '。其他角标符号不受影响。
 */
internal fun cornerTypesSeparator(active: Boolean, value: String): Boolean = active && value == SYLLABLE_SEPARATOR

/**
 * 「Shift 键分词」（默认关，B 站评论要的）：只在打拼音时把点按 Shift 换成分词；没在打拼音时、
 * 按住 Shift 打大写、拖到字母上都照旧。生效范围和逗号键分词一样，见 [syllableSplitActive]。
 */
internal fun shiftTapTypesSeparator(active: Boolean, composing: Boolean): Boolean = active && composing
