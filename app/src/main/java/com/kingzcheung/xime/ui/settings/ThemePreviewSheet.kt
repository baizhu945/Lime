package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.ui.theme.KeyShapes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.FrostedGlassProfiles
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.frostedKeyColor
import com.kingzcheung.xime.ui.keyboard.legibleForTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyCornerRadius
import com.kingzcheung.xime.ui.keyboard.crispShadowColor
import com.kingzcheung.xime.ui.theme.KeyboardColorScheme
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.keyboardBackground
import kotlinx.coroutines.launch

private val QWERTY_ROW0 = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
private val QWERTY_ROW1 = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
private val QWERTY_ROW2 = listOf("Z", "X", "C", "V", "B", "N", "M")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePreviewSheet(
    theme: KeyboardColorScheme,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val glass = remember(theme.id) { FrostedGlassPreferences.readProfiles(context) }
    AppearancePreviewSheet(theme.name, theme, glass, onApply, onDismiss)
}

@Composable
internal fun FrostedGlassSettingsSheet(
    theme: KeyboardColorScheme,
    onApply: (FrostedGlassProfiles) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var glass by rememberSaveable(theme.id, stateSaver = listSaver<FrostedGlassProfiles, Any>(
        save = { listOf(it.dark.enabled, it.light.blurRadiusDp, it.light.backgroundOpacity, it.light.keyOpacity,
            it.dark.blurRadiusDp, it.dark.backgroundOpacity, it.dark.keyOpacity) },
        restore = { FrostedGlassProfiles(
            light = FrostedGlassConfig(it[0] as Boolean, it[1] as Float, it[2] as Float, it[3] as Float),
            dark = FrostedGlassConfig(it[0] as Boolean, it[4] as Float, it[5] as Float, it[6] as Float),
        ) },
    )) { mutableStateOf(FrostedGlassPreferences.readProfiles(context)) }
    AppearancePreviewSheet("透明度与模糊", theme, glass, { onApply(glass) }, onDismiss) { isDark ->
        Spacer(Modifier.height(12.dp))
        FrostedThemeControls(glass.forAppearance(isDark), isDark) {
            glass = glass.withAppearance(it, isDark)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearancePreviewSheet(
    title: String,
    theme: KeyboardColorScheme,
    glass: FrostedGlassProfiles,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    controls: @Composable (isDark: Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = if (FrostedGlassPreferences.isDark(context)) 1 else 0,
        pageCount = { PREVIEW_PAGES.size })
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            ThemePreviewPager(theme = theme, glass = glass, pagerState = pagerState)
            controls(PREVIEW_PAGES[pagerState.currentPage].isDark)

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text("取消", fontSize = 16.sp)
                }
                Button(
                    onClick = onApply,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text("应用", fontSize = 16.sp)
                }
            }
        }
    }
}

private data class PreviewPage(val label: String, val isDark: Boolean)

private val PREVIEW_PAGES = listOf(
    PreviewPage("浅色", false),
    PreviewPage("深色", true),
)

@Composable
private fun ThemePreviewPager(
    theme: KeyboardColorScheme,
    glass: FrostedGlassProfiles,
    pagerState: PagerState,
) {
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            PREVIEW_PAGES.forEachIndexed { index, preview ->
                TextButton(onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    modifier = Modifier.testTag(if (preview.isDark) "theme-preview-dark" else "theme-preview-light")) {
                    Text(preview.label,
                        fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal,
                        color = if (pagerState.currentPage == index) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        ) { page ->
            val preview = PREVIEW_PAGES[page]
            // 与键盘相同：过透时按该主题加深到键面文字清楚（legibleForTheme）；
            // 玻璃透出的是同明暗的页面，而不是设置页本身（浅色设置里看深色页会发灰）。
            val previewGlass = glass.forAppearance(preview.isDark).legibleForTheme(theme.id, preview.isDark)
            Box(
                modifier = Modifier.fillMaxSize()
                    .then(if (previewGlass.enabled) Modifier.clip(RoundedCornerShape(12.dp))
                        .background(glassPreviewHost(preview.isDark)) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(
                    frostedGlass = previewGlass)) {
                    ThemeKeyboardPreview(
                        theme = theme,
                        isDark = preview.isDark,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 2.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        PageIndicator(
            pageCount = PREVIEW_PAGES.size,
            currentPage = pagerState.currentPage,
            activeColor = MaterialTheme.colorScheme.primary,
            inactiveColor = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** 玻璃预览后面的“宿主页面”：浅色时的浅底、深色时的深底。 */
internal fun glassPreviewHost(isDark: Boolean): Color = if (isDark) Color(0xFF121214) else Color(0xFFF4F4F6)

@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    activeColor: Color,
    inactiveColor: Color,
    dotSize: Dp = 8.dp,
    spacing: Dp = 6.dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = spacing / 2)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(if (index == currentPage) activeColor else inactiveColor),
            )
        }
    }
}

@Composable
internal fun ThemeKeyboardPreview(
    theme: KeyboardColorScheme,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    // 键色、字色按这个 id 去主题表里取。还没存的主题（「我的图片」调整页）表里没有，
    // 按 theme.id 取会落到全局默认色（深色是半透明白键），要传它沿用的主题。
    colorThemeId: String = theme.id,
) {
    val bgColor = if (isDark) theme.keyboardBgDark else theme.keyboardBgLight
    val accent = if (isDark) theme.accentDark else theme.accentLight

    val kbColors = KeysConfigHelper.getKeyboardColors()
    val longToColor: (Long) -> Color = { if (it > 0xFFFFFF) Color(it) else Color(0xFF000000 or it) }
    val specialKeyColor = com.kingzcheung.xime.ui.theme.resolvedSpecialKeyColor(theme, isDark,
        (if (isDark) kbColors.specialKeyBgColorDark else kbColors.specialKeyBgColor)?.let(longToColor))
    val enterKeyColor = KeyboardThemes.getEnterKeyColor(colorThemeId, isDark)
    val specialTextColor = if (isDark) Color.White else KeyboardThemes.getSpecialKeyTextColor(colorThemeId, false)
    val textColor = KeyboardThemes.getKeyTextColorOverride(colorThemeId, isDark)
        ?: if (isDark) longToColor(kbColors.keyTextColorDark) else longToColor(kbColors.keyTextColor)
    val keyColor = KeyboardThemes.getKeyBgColorOverride(colorThemeId, isDark)
        ?: if (isDark) longToColor(kbColors.keyBgColorDark) else longToColor(kbColors.keyBgColor)
    val candidateTextColor = KeyboardThemes.getCandidateTextColorOverride(colorThemeId, isDark)
        ?: if (isDark) longToColor(kbColors.candidateTextColorDark)
        else longToColor(kbColors.candidateTextColor)
    val selectedTextColor = KeyboardThemes.getCandidateSelectedTextColorOverride(colorThemeId, isDark)
        ?: if (isDark) theme.candidateSelectedTextColorDark else theme.candidateSelectedTextColorLight
    val context = LocalContext.current
    val candidateTextSize = SettingsPreferences.getCandidateTextSize(context)

    val cornerRadius = KeysConfigHelper.getKeyboardKeyConfig().cornerRadius.dp

    CompositionLocalProvider(
        LocalKeyCornerRadius provides cornerRadius,
    ) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .keyboardBackground(theme.keyboardBackground, isDark, bgColor, LocalKeyboardInputPreferences.current.frostedGlass),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            ) {
                CandidateBarPreview(accent, candidateTextColor, selectedTextColor, candidateTextSize)

                Column(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    // Row 1: QWERTYUIOP
                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            QWERTY_ROW0.forEach { key ->
                                PreviewKey(
                                    key,
                                    keyColor,
                                    textColor,
                                    1f,
                                    extraModifier = Modifier.padding(0.5.dp, 2.dp)
                                )
                            }
                        }
                    }

                    // Row 2: ASDFGHJKL
                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            QWERTY_ROW1.forEach { key ->
                                PreviewKey(
                                    key,
                                    keyColor,
                                    textColor,
                                    1f,
                                    extraModifier = Modifier.padding(0.5.dp, 2.dp)
                                )
                            }
                        }
                    }

                    // Row 3: Shift ZXCVBNM Backspace
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PreviewKey(
                            label = "",
                            icon = rememberVectorPainter(com.kingzcheung.xime.ui.keyboard.ShiftKeyIcons.Off),
                            color = specialKeyColor, textColor = specialTextColor, weight = 1.4f,
                            extraModifier = Modifier.padding(0.5.dp, 2.dp)
                        )

                        Row(
                            modifier = Modifier
                                .weight(7f)
                                .fillMaxHeight(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            QWERTY_ROW2.forEach { key ->
                                PreviewKey(
                                    key,
                                    keyColor,
                                    textColor,
                                    1f,
                                    extraModifier = Modifier.padding(0.5.dp, 2.dp)
                                )
                            }
                        }

                        PreviewKey(
                            label = "",
                            icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                            color = specialKeyColor, textColor = specialTextColor, weight = 1.4f,
                            extraModifier = Modifier.padding(0.5.dp, 2.dp)
                        )
                    }

                    // Row 4: ?123 , 空格 中/En 确定
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PreviewKey("?123", specialKeyColor, specialTextColor, 1.2f,extraModifier = Modifier.padding(0.5.dp, 2.dp))
                        PreviewKey("，", keyColor, textColor, 0.8f,extraModifier = Modifier.padding(0.5.dp, 2.dp))
                        PreviewKey("空格", keyColor, textColor, 3f, fontSize = 9.sp,extraModifier = Modifier.padding(0.5.dp, 2.dp))
                        PreviewKey("中/En", specialKeyColor, specialTextColor, 0.8f, fontSize = 8.sp,extraModifier = Modifier.padding(0.5.dp, 2.dp))
                        PreviewKey("确定", enterKeyColor, specialTextColor, 1.2f,extraModifier = Modifier.padding(0.5.dp, 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateBarPreview(
    accent: Color,
    textColor: Color,
    selectedTextColor: Color,
    textSize: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewCandidate(
                text = "Lime",
                isSelected = true,
                accent = accent,
                textColor = textColor,
                selectedTextColor = selectedTextColor,
                textSize = textSize
            )
            Spacer(modifier = Modifier.width(4.dp))
            PreviewCandidate(
                text = "输入法",
                isSelected = false,
                accent = accent,
                textColor = textColor,
                selectedTextColor = selectedTextColor,
                textSize = textSize
            )
        }
    }
}

@Composable
private fun PreviewCandidate(
    text: String,
    isSelected: Boolean,
    accent: Color,
    textColor: Color,
    selectedTextColor: Color,
    textSize: Int,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (isSelected) accent.copy(alpha = 0.2f) else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = if (isSelected) selectedTextColor else textColor,
            fontSize = textSize.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun RowScope.PreviewKey(
    label: String,
    color: Color,
    textColor: Color,
    weight: Float,
    icon: Painter? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    extraModifier: Modifier = Modifier,
) {
    val glass = LocalKeyboardInputPreferences.current.frostedGlass
    val shadow = KeysConfigHelper.getKeyboardShadow()
    val density = LocalDensity.current
    val shadowModifier = remember(shadow, KeyShapes.current, density, color, glass.enabled) {
        if (shadow.enabled && !glass.enabled) {
            val offsetPx = with(density) { shadow.elevation.dp.toPx() }
            val cornerPx = with(density) { KeyShapes.current.radius(shadow.shapeRadius).dp.toPx() }
            Modifier.drawBehind {
                drawRoundRect(
                    color = crispShadowColor(color),
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx),
                )
            }
        } else Modifier
    }

    Box(
        modifier = extraModifier
            .weight(weight)
            .fillMaxHeight()
            .fillMaxWidth()
            .then(shadowModifier)
            .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
            .background(frostedKeyColor(color, textColor, glass)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(
                text = label,
                fontSize = fontSize,
                color = textColor,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
