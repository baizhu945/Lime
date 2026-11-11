package com.kingzcheung.xime.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.kingzcheung.xime.settings.KeysConfigHelper

/** Resolved keyboard colors, shared by the keyboard, material samples and preview export. */
@Immutable
data class KeyboardPalette(
    val background: Color, val key: Color, val text: Color, val accent: Color,
    val function: Color, val functionText: Color, val enter: Color,
    val candidateText: Color, val selectedText: Color,
)

fun resolveKeyboardPalette(themeId: String, dark: Boolean): KeyboardPalette {
    val legacy = KeysConfigHelper.getKeyboardColors()
    fun color(value: Long) = Color(
        if (value > 0xFFFFFF) value.toInt() else (0xFF000000 or value).toInt())
    return KeyboardPalette(
        background = KeyboardThemes.getKeyboardBackgroundColor(themeId, dark),
        key = KeyboardThemes.getKeyBgColorOverride(themeId, dark)
            ?: color(if (dark) legacy.keyBgColorDark else legacy.keyBgColor),
        text = KeyboardThemes.getKeyTextColorOverride(themeId, dark)
            ?: color(if (dark) legacy.keyTextColorDark else legacy.keyTextColor),
        accent = KeyboardThemes.getAccentColor(themeId, dark),
        function = resolvedSpecialKeyColor(KeyboardThemes.getRenderingScheme(themeId), dark,
            (if (dark) legacy.specialKeyBgColorDark else legacy.specialKeyBgColor)?.let(::color)),
        functionText = resolvedSpecialKeyTextColor(themeId, dark),
        enter = KeyboardThemes.getEnterKeyColor(themeId, dark),
        candidateText = KeyboardThemes.getCandidateTextColorOverride(themeId, dark)
            ?: color(if (dark) legacy.candidateTextColorDark else legacy.candidateTextColor),
        selectedText = KeyboardThemes.getCandidateSelectedTextColorOverride(themeId, dark)
            ?: KeyboardThemes.getCandidateSelectedTextColor(themeId, dark),
    )
}

internal fun resolvedSpecialKeyTextColor(themeId: String, dark: Boolean): Color =
    if (dark) resolvedDarkSpecialKeyTextColor(KeyboardThemes.getRenderingScheme(themeId))
    else KeyboardThemes.getSpecialKeyTextColor(themeId, false)

internal fun resolvedDarkSpecialKeyTextColor(theme: KeyboardColorScheme): Color =
    theme.specialKeyTextColorDark ?: Color.White

internal val LocalKeyboardPalette = staticCompositionLocalOf<KeyboardPalette?> { null }

/** 功能键使用带少量主题色的中性容器，避免直接把高饱和主色铺满深色键盘。 */
internal fun softDarkKeyContainer(accent: Color): Color =
    lerp(Color(0xFF30343C), accent, 0.30f)

/** 中性黑白配色：浅色保持浅底深字，深色使用纯黑画布，键帽保留可辨边界。 */
object PureBlackTheme {
    const val ID = "pure_black"

    // 浅色：按键与底色对比 ≥1.2，功能键比字母键更突出；数值见 tools/theme_audit.py。
    // 深色以黑为主（2026-10-22 用户选定）：底纯黑，按键近黑、功能键与回车再浅一档，靠填充看出键边；
    // 玻璃下近黑键帽不提亮（FrostedKeyColors.NEAR_BLACK_KEY_PEAK），面板卡片只描边不铺灰。
    fun create() = SoftBlueTheme.create(ID, "黑白").copy(
        keyboardBgLight = Color(0xFFE8E8EA), keyboardBgDark = Color.Black,
        candidateBarBgLight = Color(0xFFE8E8EA), candidateBarBgDark = Color.Black,
        surfaceLight = Color(0xFFE8E8EA), surfaceDark = Color.Black,
        keyBgLight = Color.White, keyBgDark = Color(0xFF1C1C1E),
        specialKeyLight = Color(0xFFD4D4D8), specialKeyDark = Color(0xFF29292B),
        specialKeyTextColorLight = Color(0xFF202020), specialKeyTextColorDark = Color.White,
        enterKeyLight = Color(0xFFD4D4D8), enterKeyDark = Color(0xFF29292B),
        keyTextColorLight = Color(0xFF202020), keyTextColorDark = Color.White,
        candidateTextColorLight = Color(0xFF454545), candidateTextColorDark = Color(0xFFE0E0E0),
        candidateSelectedTextColorLight = Color.Black, candidateSelectedTextColorDark = Color.White,
        accentLight = Color(0xFF454545), accentDark = Color(0xFFE0E0E0),
        primaryLight = Color(0xFF454545), primaryDark = Color(0xFFE0E0E0),
        primaryContainerLight = Color(0xFFE0E0E0), primaryContainerDark = Color(0xFF353535),
        dividerColorLight = Color(0xFFCDCDCD), dividerColorDark = Color(0xFF353535),
    )
}

/** 固定蓝灰调色板；不读取壁纸，因此手机、平板及不同 Android 版本的色值相同。 */
object SoftBlueTheme {
    const val ID = "soft_blue"

    fun create(id: String = ID, name: String = "柔和蓝") = KeyboardColorScheme(
        id = id,
        name = name,
        specialKeyLight = Color(0xFFDCE6FA),
        specialKeyDark = Color(0xFF495A7D),
        accentLight = Color(0xFF42639C),
        accentDark = Color(0xFFB4CAFA),
        primaryLight = Color(0xFF42639C),
        primaryDark = Color(0xFFB4CAFA),
        primaryContainerLight = Color(0xFFDCE6FA),
        primaryContainerDark = Color(0xFF495A7D),
        surfaceLight = Color(0xFFEAEEF5),
        surfaceDark = Color(0xFF191C22),
        keyboardBgLight = Color(0xFFEAEEF5),
        keyboardBgDark = Color(0xFF191C22),
        keyBgLight = Color.White,
        keyBgDark = Color(0xFF2E323A),
        candidateBarBgLight = Color(0xFFEAEEF5),
        candidateBarBgDark = Color(0xFF191C22),
        keyTextColorLight = Color(0xFF20242C),
        keyTextColorDark = Color(0xFFE9EDF5),
        candidateTextColorLight = Color(0xFF42639C),
        candidateTextColorDark = Color(0xFFB4CAFA),
        candidateSelectedTextColorLight = Color(0xFF2C4D85),
        candidateSelectedTextColorDark = Color(0xFFDCE6FA),
        dividerColorLight = Color(0xFFD4DBE7),
        dividerColorDark = Color(0xFF454C5B),
        useThemeColors = true,
    )
}

/** 完整主题的功能键与预览同源；兼容旧式自定义主题的全局覆盖。 */
fun resolvedSpecialKeyColor(theme: KeyboardColorScheme, isDark: Boolean, legacyOverride: Color?): Color {
    val themed = if (isDark) theme.specialKeyDark else theme.specialKeyLight
    return if (theme.isDynamic || theme.useThemeColors) themed else legacyOverride ?: themed
}

/** Xime 内置薰衣草兜底；实际键盘颜色由配置和全局颜色覆盖。 */
object SoftLavenderTheme {
    fun create() = KeyboardColorScheme(
        id = "lavender_purple", name = "薰衣草紫",
        specialKeyLight = Color(0xFFE8DEF8), specialKeyDark = Color(0xFF6750A4),
        accentLight = Color(0xFF8F73E2), accentDark = Color(0xFFD0BCFF),
        primaryLight = Color(0xFF8F73E2), primaryDark = Color(0xFFD0BCFF),
        primaryContainerLight = Color(0xFFEADDFF), primaryContainerDark = Color(0xFF4F378B),
        surfaceLight = Color(0xFFFAF8FC), surfaceDark = Color(0xFF2B2930),
        // 与 Xime 内置的候选文字色一致，避免磨砂对比度修正改写品牌色。
        candidateTextColorLight = Color(0xFF202124), candidateTextColorDark = Color(0xFFE8EAED),
    )
}

/** 固定 iOS 风格三色渐变；透明度与模糊独立于主题设置。保留原 ID 以沿用用户覆盖。 */
object TransparentGlassTheme {
    const val ID = "transparent_glass"
    private val originalGradient = listOf(0x9CACCCL, 0xD6BCC9L, 0x85BFBFL)
    // The original renderer used a 20% white / 80% black wash. Bake the
    // old appearance into this fixed theme, separately from live opacity.
    private fun fixedBackground(dark: Boolean) = originalGradient.map { hex ->
        fun channel(shift: Int): Long {
            val value = (hex shr shift) and 255L
            return if (dark) (value * 2 + 5) / 10 else (value * 8 + 255 * 2 + 5) / 10
        }
        (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    // 不透明的深色渐变底上，字母键要亮出来、功能键压暗（与 iOS 深色键盘相同的层次）。
    private val keyDark = Color(0xFF55555A)
    private val functionDark = Color(0xFF3A3A3C)

    /**
     * 玻璃下沿用 10-09 改色前的近黑键（#1C1C1C / #353535）：提亮后字母键是一层淡灰、功能键更亮，
     * 后面的页面透得出来。用上面的灰键会被提成近白的一层，像实心灰块（2026-10-30 用户反馈）。
     */
    internal fun glassKeyBase(color: Color): Color = when (color) {
        keyDark -> Color(0xFF1C1C1C)
        functionDark -> Color(0xFF353535)
        else -> color
    }

    /** 早期玻璃的功能键与字母键同样不透明，靠更亮的键色分开，不再减半。 */
    internal fun isGlassFunctionKey(color: Color): Boolean = color == functionDark

    fun create() = PureBlackTheme.create().copy(
        id = ID, name = "iOS 渐变",
        keyBgDark = keyDark,
        specialKeyDark = functionDark, enterKeyDark = functionDark,
        keyboardBackground = com.kingzcheung.xime.settings.BackgroundConfig(
            type = "gradient", angle = 135, colors = fixedBackground(false), colorsDark = fixedBackground(true)),
    )
}
