package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.kingzcheung.xime.settings.FrostedGlassConfig

/** 按下时键面向文字色靠拢的比例（所有键型共用）。 */
internal const val PRESSED_KEY_TINT = 0.14f

/** Only the keycap fill is translucent; labels and icons keep their theme colors. */
internal fun frostedKeyColor(
    background: Color,
    foreground: Color,
    config: FrostedGlassConfig,
    legacyStateColor: Color = background,
    pressed: Boolean = false,
    highlighted: Boolean = false,
    opacityScale: Float = 1f,
): Color {
    // 非玻璃主题的按下反馈也统一：键面向本键文字色靠一点（深色主题变亮、浅色主题变暗），
    // 不再有的键加深 10%、有的 20%、有的变透明 70%。高亮、禁用等其他状态仍用各自的 legacyStateColor。
    if (!config.enabled) return if (pressed) lerp(background, foreground.copy(alpha = background.alpha), PRESSED_KEY_TINT) else legacyStateColor

    val opacity = config.keyOpacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.22f
    // Dark keycaps must not lose their elevation twice: #1C1C1C at 20% would
    // become #060606 over black. Compensate the tint (not alpha), preserving
    // the theme's channel ratios and its original color at full opacity.
    // For light surfaces the same rule preserves their distance from white.
    val base = if (opacity < 1f) com.kingzcheung.xime.ui.theme.TransparentGlassTheme.glassKeyBase(background) else background
    // 早期近黑键按 1/不透明度提亮（#1C1C1C → #8C8C8C），不走黑白主题的 3 倍。
    val raisedTint = glassKeyTint(base, foreground, opacity, nearBlackLift = base == background)
    // Limited correction for neutral dark greys over a black panel and white
    // host. Fade it out at both opacity endpoints and at the colour boundaries;
    // low opacity or opacityScale can make 16 levels physically impossible.
    // Scale all channels together and cap by the strongest channel to keep hue.
    val peak = maxOf(base.red, base.green, base.blue)
    val low = minOf(base.red, base.green, base.blue)
    val weight = smoothRamp(0.04f, 0.08f, peak) * (1f - smoothRamp(0.15f, 0.20f, peak)) *
        (1f - smoothRamp(4f / 255f, 12f / 255f, peak - low)) *
        smoothRamp(0f, 0.2f, opacity) * (1f - smoothRamp(0.8f, 1f, opacity))
    val tintWithEdge = if (base == background && low > 0f && opacity > 0f && weight > 0f &&
        foreground.luminance() > base.luminance()) {
        val panel = 1f - (config.backgroundOpacity.takeIf { it.isFinite() } ?: 0.8f).coerceIn(0f, 1f)
        val required = (panel + 17f / (255f * opacity)).coerceAtMost(1f)
        val weakest = minOf(raisedTint.red, raisedTint.green, raisedTint.blue)
        val strongest = maxOf(raisedTint.red, raisedTint.green, raisedTint.blue)
        val targetScale = minOf(maxOf(1f, required / weakest), 1f / strongest)
        val factor = 1f + (targetScale - 1f) * weight
        Color(raisedTint.red * factor, raisedTint.green * factor, raisedTint.blue * factor, raisedTint.alpha)
    } else raisedTint
    val feedback = when {
        pressed -> 0.08f
        highlighted -> 0.04f
        else -> 0f
    }
    val feedbackTarget = if (maxOf(kotlin.math.abs(tintWithEdge.red - foreground.red),
        kotlin.math.abs(tintWithEdge.green - foreground.green), kotlin.math.abs(tintWithEdge.blue - foreground.blue)) < 0.05f)
        base else foreground
    val tint = lerp(tintWithEdge, feedbackTarget, feedback)
    // Frosted material owns its opacity independently of an imported theme's alpha.
    // Disabled controls and transparent hit regions can explicitly scale the material.
    return tint.copy(alpha = opacity * opacityScale.coerceIn(0f, 1f))
}

/** 近黑键帽与普通深色键帽之间的过渡中心。 */
internal const val NEAR_BLACK_KEY_PEAK = .18f

/** 近黑键帽在玻璃下的提亮倍数（可调参数；渲染测试会临时改它出对比图）。 */
internal var glassNearBlackLift = 3f

private fun smoothRamp(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun glassKeyTint(background: Color, foreground: Color, opacity: Float, nearBlackLift: Boolean = true): Color {
    if (opacity <= 0f || opacity >= 1f) return background
    val darkSurface = foreground.luminance() > background.luminance()
    val r = if (darkSurface) background.red else 1f - background.red
    val g = if (darkSurface) background.green else 1f - background.green
    val b = if (darkSurface) background.blue else 1f - background.blue
    val peak = maxOf(r, g, b)
    if (peak <= 0f) return background
    // 近黑键帽采用较小的提亮倍数，并在亮度边界及透明端平滑过渡；
    // 按 1/不透明度（默认 5 倍）会发灰，不提亮又和背景混在一起。
    val ordinaryLift = 1f / opacity.coerceAtLeast(1e-30f)
    val nearWeight = if (nearBlackLift && darkSurface)
        1f - smoothRamp(NEAR_BLACK_KEY_PEAK - 0.02f, NEAR_BLACK_KEY_PEAK + 0.02f, peak) else 0f
    val nearLift = 1f + (glassNearBlackLift - 1f) * smoothRamp(0f, 0.1f, opacity)
    val lift = nearLift * nearWeight + ordinaryLift * (1f - nearWeight)
    val scale = minOf(lift, ordinaryLift, 1f / peak)
    fun channel(value: Float) = if (darkSurface) value * scale else 1f - value * scale
    return Color(channel(r), channel(g), channel(b), background.alpha)
}

/**
 * 玻璃下键帽色按亮度统一提到同一层白（或黑），深色主题里功能键与字母键会变得一样。
 * 功能键（主题的功能键色）改用更透的材质，与 iOS 一样比字母键更暗/更淡，深浅色都看得出。
 */
internal const val GLASS_FUNCTION_KEY_SCALE = 0.5f

@androidx.compose.runtime.Composable
internal fun glassFunctionKeyScale(background: Color): Float {
    val palette = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current ?: return 1f
    return glassFunctionKeyScale(background, palette.key, palette.function)
}

internal fun glassFunctionKeyScale(background: Color, key: Color, function: Color): Float {
    if (com.kingzcheung.xime.ui.theme.TransparentGlassTheme.isGlassFunctionKey(background)) return 1f
    fun Color.sameRgb(other: Color) = kotlin.math.abs(red - other.red) < 0.004f &&
        kotlin.math.abs(green - other.green) < 0.004f && kotlin.math.abs(blue - other.blue) < 0.004f
    return if (background.sameRgb(function) && !key.sameRgb(function))
        GLASS_FUNCTION_KEY_SCALE else 1f
}

/** On glass, size and position distinguish hints; fading them would expose the host through text. */
internal fun keyHintColor(themeText: Color, config: FrostedGlassConfig, legacyAlpha: Float): Color =
    themeText.copy(alpha = if (config.enabled) 1f else legacyAlpha)

/** 玻璃材质上键面文字的最低对比度（按键字号大，取 WCAG AA 正文线 4.5:1）。 */
internal const val GLASS_LABEL_CONTRAST = 4.5f

/**
 * 玻璃材质的可读性下限：键盘后面可能是纯白页面，也可能是纯黑页面，两种最坏情况下键面文字都要清楚。
 * 本来清楚的设置原样保留。否则在不降低任一不透明度的 21×21 网格上
 * 最小化两项绝对增量之和；同代价选择透射率较高的配置。不可解时返回
 * 已搜索候选中最好的最差对比度，同分选择透射率较高的配置。
 * 只调整渲染值，不修改保存的用户偏好。
 */
internal fun FrostedGlassConfig.legibleOver(
    surface: Color,
    keys: List<Pair<Color, Color>>,
    target: Float = GLASS_LABEL_CONTRAST,
    scales: List<Float> = List(keys.size) { 1f },
): FrostedGlassConfig {
    require(scales.size == keys.size)
    if (!enabled || keys.isEmpty()) return this
    val base = normalized()
    fun worst(config: FrostedGlassConfig): Float = listOf(Color.White, Color.Black).minOf { backdrop ->
        val panel = surface.copy(alpha = config.backgroundOpacity).compositeOver(backdrop)
        keys.indices.minOf { i ->
            val (key, text) = keys[i]
            contrastRatio(text, frostedKeyColor(key, text, config, opacityScale = scales[i]).compositeOver(panel))
        }
    }
    val initial = worst(base)
    if (initial >= target) return base
    var best = base
    var bestWorst = initial
    var feasible = false
    var bestCost = Float.POSITIVE_INFINITY
    fun transmission(c: FrostedGlassConfig) = (1f - c.backgroundOpacity) * (1f - c.keyOpacity)
    for (bgStep in 0..20) for (keyStep in 0..20) {
        if (bgStep == 0 && keyStep == 0) continue
        val candidate = base.copy(
            backgroundOpacity = base.backgroundOpacity + (1f - base.backgroundOpacity) * bgStep / 20f,
            keyOpacity = base.keyOpacity + (1f - base.keyOpacity) * keyStep / 20f,
        )
        val cost = candidate.backgroundOpacity - base.backgroundOpacity + candidate.keyOpacity - base.keyOpacity
        // Once feasible, higher-cost candidates cannot improve the chosen result.
        if (feasible && cost > bestCost + 1e-6f) continue
        val score = worst(candidate)
        if (score >= target) {
            if (!feasible || cost < bestCost - 1e-6f ||
                (kotlin.math.abs(cost - bestCost) <= 1e-6f && transmission(candidate) > transmission(best))) {
                best = candidate; bestCost = cost; feasible = true
            }
        } else if (!feasible && (score > bestWorst + 1e-6f ||
                (kotlin.math.abs(score - bestWorst) <= 1e-6f && transmission(candidate) > transmission(best)))) {
            best = candidate; bestWorst = score
        }
    }
    return best
}

/** 当前主题的字母键与功能键都参与检查。 */
internal fun FrostedGlassConfig.legibleForTheme(themeId: String, isDark: Boolean): FrostedGlassConfig {
    if (!enabled) return this
    val palette = com.kingzcheung.xime.ui.theme.resolveKeyboardPalette(themeId, isDark)
    return legibleOver(palette.background,
        listOf(palette.key to palette.text, palette.function to palette.functionText),
        scales = listOf(1f, glassFunctionKeyScale(palette.function, palette.key, palette.function)))
}

private fun contrastRatio(a: Color, b: Color): Float {
    val light = maxOf(a.luminance(), b.luminance())
    val dark = minOf(a.luminance(), b.luminance())
    return (light + 0.05f) / (dark + 0.05f)
}
