package com.kingzcheung.xime.ui.settings

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.settings.FrostedGlassPreferences
import com.kingzcheung.xime.settings.KeyboardHeightProfiles
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.legibleForTheme
import com.kingzcheung.xime.ui.keyboard.resolvedFixedKeyboardWidth
import com.kingzcheung.xime.ui.theme.CustomImageTheme
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.ThemeImageFrame
import com.kingzcheung.xime.ui.theme.ThemeImagePlacement
import com.kingzcheung.xime.ui.theme.clampThemeImage
import com.kingzcheung.xime.ui.theme.openThemeImageStream
import com.kingzcheung.xime.ui.theme.placeThemeImage
import com.kingzcheung.xime.ui.theme.placementOf
import com.kingzcheung.xime.ui.theme.snapThemeImage
import com.kingzcheung.xime.ui.theme.themeImageWidthRange
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「我的图片」调整页：图片放在与当前键盘同宽高比的框里，拖动移动、双指缩放，
 * 宽或高接近键盘时贴齐，四条边和中线吸到键盘的边和中线。
 * 可以上下贴齐、两边留白，或左右贴齐、上下留白；留白处是沿用主题的底色。
 * 框外露出的部分压暗显示，松手后框里换回带按键的预览（玻璃、深浅色与键盘一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomImageEditorSheet(
    src: String,
    baseThemeId: String,
    initial: ThemeImagePlacement,
    onConfirm: (ThemeImagePlacement) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var bitmap by remember(src) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(src) {
        bitmap = withContext(Dispatchers.IO) { runCatching { decodeEditorImage(context, src) }.getOrNull() }
    }
    var placement by remember(src) { mutableStateOf(initial) }

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
                text = "调整图片",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = "拖动移动，双指缩放，靠近键盘边缘或中线时自动吸附；图片比键盘小时，空出的地方用主题底色。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            val image = bitmap
            if (image == null) {
                Box(Modifier.fillMaxWidth().height(200.dp))
            } else {
                CustomImageEditorCanvas(image, src, baseThemeId, placement) { placement = it }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "当前：${placementLabel(placement)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("custom-image-fit"),
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "铺满" to ThemeImagePlacement("cover"),
                    "上下对齐" to ThemeImagePlacement("fit_height"),
                    "左右对齐" to ThemeImagePlacement("fit_width"),
                ).forEach { (label, target) ->
                    OutlinedButton(
                        onClick = { placement = target },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) { Text(label, fontSize = 14.sp, maxLines = 1) }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                ) { Text("取消", fontSize = 16.sp) }
                Button(
                    onClick = { onConfirm(placement) },
                    enabled = bitmap != null,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                ) { Text("应用", fontSize = 16.sp) }
            }
        }
    }
}

private fun placementLabel(placement: ThemeImagePlacement): String = when (placement.fit) {
    "cover" -> if (placement.zoom > 1.001f) "铺满（已放大）" else "铺满"
    "fit_height" -> "上下对齐，两边留白"
    "fit_width" -> "左右对齐，上下留白"
    else -> "自由大小，四周留白"
}

@Composable
private fun CustomImageEditorCanvas(
    image: ImageBitmap,
    src: String,
    baseThemeId: String,
    placement: ThemeImagePlacement,
    onPlacementChange: (ThemeImagePlacement) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // 框的宽高比与当前方向的固定键盘相同（高度含工具栏）。
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val keyboardWidthDp = resolvedFixedKeyboardWidth(configuration.screenWidthDp,
        SettingsPreferences.getFixedWidthDp(context, landscape))
    val keyboardHeightDp = KeyboardHeightProfiles.fixed(context, landscape)
    val aspect = keyboardHeightDp.toFloat() / keyboardWidthDp.coerceAtLeast(1)

    val isDark = remember { FrostedGlassPreferences.isDark(context) }
    val glass = remember { FrostedGlassPreferences.readProfiles(context) }
    val themes = KeyboardThemes.themes
    val colorThemeId = remember(themes, baseThemeId) { CustomImageTheme.previewColorThemeId(themes, baseThemeId) }
    val previewGlass = glass.forAppearance(isDark).legibleForTheme(colorThemeId, isDark)
    val previewTheme = remember(themes, src, baseThemeId, placement) {
        CustomImageTheme.preview(themes, baseThemeId, src, placement)
    }
    val fallback = previewTheme?.let { if (isDark) it.keyboardBgDark else it.keyboardBgLight }
        ?: MaterialTheme.colorScheme.surface
    val guideColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val marginDp = 36.dp
        // 框最高 360dp；更高时整体缩窄，宽高比不变。
        val frameWidthDp = minOf(maxWidth - 40.dp, 360.dp / aspect)
        val frameHeightDp = frameWidthDp * aspect
        val frameW = with(density) { frameWidthDp.toPx() }
        val frameH = with(density) { frameHeightDp.toPx() }
        val origin = with(density) { Offset(((maxWidth - frameWidthDp) / 2).toPx(), marginDp.toPx()) }
        val threshold = with(density) { 12.dp.toPx() }
        val srcW = image.width.toFloat()
        val srcH = image.height.toFloat()

        // 手势中的原始位置（不吸附）；null 时按已存的摆放画。
        var live by remember(image, frameW, frameH) { mutableStateOf<ThemeImageFrame?>(null) }
        val shown = live?.let { snapThemeImage(it, frameW, frameH, threshold) }
            ?: placeThemeImage(srcW, srcH, frameW, frameH, placement)
        val currentShown by androidx.compose.runtime.rememberUpdatedState(shown)
        val currentPlacement by androidx.compose.runtime.rememberUpdatedState(placement)

        Box(
            Modifier
                .fillMaxWidth()
                .height(frameHeightDp + marginDp * 2)
                .clipToBounds()
                .testTag("custom-image-editor")
                .pointerInput(image, frameW, frameH) {
                    val widthRange = themeImageWidthRange(srcW, srcH, frameW, frameH)
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var raw: ThemeImageFrame? = null
                        do {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            if (pan != Offset.Zero || zoom != 1f) {
                                val start = raw ?: currentShown
                                val centroid = event.calculateCentroid(useCurrent = false)
                                    .takeIf { it.isSpecified }?.minus(origin)
                                    ?: Offset(start.left + start.width / 2f, start.top + start.height / 2f)
                                val width = (start.width * zoom).coerceIn(widthRange)
                                val factor = width / start.width
                                raw = clampThemeImage(ThemeImageFrame(
                                    centroid.x - (centroid.x - start.left) * factor + pan.x,
                                    centroid.y - (centroid.y - start.top) * factor + pan.y,
                                    width, start.height * factor,
                                ), frameW, frameH)
                                live = raw
                            }
                            // 吃掉移动，免得底部面板跟着下拉或页面滚动。
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (event.changes.any { it.pressed })
                        raw?.let {
                            val snapped = snapThemeImage(it, frameW, frameH, threshold)
                            val next = placementOf(snapped, srcW, srcH, frameW, frameH)
                            live = null
                            if (next != currentPlacement) onPlacementChange(next)
                        }
                    }
                },
        ) {
            val touching = live != null
            val corner = with(density) { 12.dp.toPx() }
            // 框外露出的部分压暗。
            Canvas(Modifier.fillMaxSize()) {
                drawImage(image, dstOffset = IntOffset((origin.x + shown.left).roundToInt(), (origin.y + shown.top).roundToInt()),
                    dstSize = IntSize(shown.width.roundToInt(), shown.height.roundToInt()), alpha = 0.35f)
            }
            // 带按键的预览一直留在组合里（不重新解码），手势中隐藏，换成下面的原图。
            if (previewTheme != null) {
                Box(
                    Modifier
                        .offset { IntOffset(origin.x.roundToInt(), origin.y.roundToInt()) }
                        .size(frameWidthDp, frameHeightDp)
                        .graphicsLayer { alpha = if (touching) 0f else 1f }
                        .clip(RoundedCornerShape(12.dp))
                        .background(glassPreviewHost(isDark)),
                ) {
                    CompositionLocalProvider(LocalKeyboardInputPreferences provides KeyboardInputPreferences(
                        frostedGlass = previewGlass)) {
                        ThemeKeyboardPreview(theme = previewTheme, isDark = isDark, modifier = Modifier.fillMaxSize(),
                            colorThemeId = colorThemeId)
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                if (touching) {
                    // 手势中框里是原图（不压遮罩、不画按键），方便对齐。
                    val frame = RoundRect(origin.x, origin.y, origin.x + frameW, origin.y + frameH, CornerRadius(corner))
                    clipPath(Path().apply { addRoundRect(frame) }) {
                        drawRect(fallback, topLeft = origin, size = Size(frameW, frameH))
                        drawImage(image, dstOffset = IntOffset((origin.x + shown.left).roundToInt(), (origin.y + shown.top).roundToInt()),
                            dstSize = IntSize(shown.width.roundToInt(), shown.height.roundToInt()))
                    }
                }
                // 键盘边框；手势中贴齐的边和中线高亮。
                drawRoundRect(outlineColor, topLeft = origin, size = Size(frameW, frameH),
                    cornerRadius = CornerRadius(corner), style = Stroke(1.dp.toPx()))
                if (!touching) return@Canvas
                val guide = 2.dp.toPx()
                fun near(a: Float, b: Float) = abs(a - b) < 0.5f
                fun vertical(x: Float) = drawLine(guideColor, Offset(origin.x + x, origin.y),
                    Offset(origin.x + x, origin.y + frameH), guide)
                fun horizontal(y: Float) = drawLine(guideColor, Offset(origin.x, origin.y + y),
                    Offset(origin.x + frameW, origin.y + y), guide)
                if (near(shown.left, 0f)) vertical(0f)
                if (near(shown.right, frameW)) vertical(frameW)
                if (near(shown.left + shown.width / 2f, frameW / 2f)) vertical(frameW / 2f)
                if (near(shown.top, 0f)) horizontal(0f)
                if (near(shown.bottom, frameH)) horizontal(frameH)
                if (near(shown.top + shown.height / 2f, frameH / 2f)) horizontal(frameH / 2f)
            }
        }
    }
}

/** 调整页只做预览，长边解到 1536 以内就够清楚。 */
private fun decodeEditorImage(context: android.content.Context, src: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openThemeImageStream(context, src)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1536) sample *= 2
    return openThemeImageStream(context, src)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}
