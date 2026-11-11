package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.settings.CandidateCommentMode
import com.kingzcheung.xime.ui.keyboard.CursorGestureMode

/**
 * 带示意图的单选卡片（与「编码显示」同一外观）：上方是这个选项在键盘上的样子，下方是名称。
 * 选中时描边加粗并标为 selected，读屏与测试都能识别。
 */
@Composable
internal fun IllustratedChoiceCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    enabled: Boolean = true,
    /** 标题最多几行；名字长（如「Rain75 紫薇轴」）时设 2，窄屏也显示完整。 */
    titleMaxLines: Int = 1,
    illustration: @Composable ColumnScope.() -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier.alpha(if (enabled) 1f else 0.45f)) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().weight(1f)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .semantics { this.selected = selected }
                .border(if (selected) 2.dp else 0.5.dp,
                    if (selected) primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                content = illustration,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = titleMaxLines)
    }
}

/** 一个迷你按键：主字符居中，上方角标在右上，下方角标在左下（与键盘实际位置一致）。 */
@Composable
private fun MiniKey(main: String, top: String?, bottom: String?, highlight: Color? = null) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.size(width = 44.dp, height = 50.dp).clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(7.dp)),
    ) {
        Text(main, Modifier.align(Alignment.Center), fontSize = 20.sp, color = onSurface)
        if (top != null) Text(top, Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 5.dp),
            fontSize = 11.sp, color = highlight ?: onSurface.copy(alpha = 0.6f), fontWeight = FontWeight.Medium)
        if (bottom != null) Text(bottom, Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 2.dp),
            fontSize = 11.sp, color = highlight ?: onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun GestureLine(gesture: String, symbol: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(gesture, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text("  →  ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(symbol, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

/** 按键角标怎么输入：随「滑动／长按」和「反转上下滑方向」实时变化。 */
@Composable
internal fun SymbolGestureIllustration(longPress: Boolean, reversed: Boolean) {
    val down = "↓ 下滑"
    val up = "↑ 上滑"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniKey("a", "@", "1")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GestureLine(if (longPress) "长按" else if (reversed) up else down, "@")
            GestureLine(if (reversed) down else up, "1")
        }
    }
}

/** 文字中的光标与移动方式。 */
@Composable
internal fun CursorGestureIllustration(mode: CursorGestureMode) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("你好", fontSize = 13.sp, color = onSurface)
        Box(Modifier.width(2.dp).height(15.dp).background(if (mode == CursorGestureMode.NONE) onSurface.copy(alpha = 0.4f) else primary))
        Text("世界", fontSize = 13.sp, color = onSurface)
    }
    Spacer(Modifier.height(8.dp))
    val arrow = if (mode == CursorGestureMode.NONE) "" else "←  →"
    when (mode) {
        CursorGestureMode.KEYBOARD -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 手指横扫过的一排按键。
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(5) {
                    Box(Modifier.size(width = 14.dp, height = 18.dp).clip(RoundedCornerShape(3.dp))
                        .background(primary.copy(alpha = 0.35f)))
                }
            }
            Text("滑过按键 $arrow", fontSize = 12.sp, color = primary)
        }
        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(84.dp).height(18.dp).clip(RoundedCornerShape(4.dp))
                .background(if (mode == CursorGestureMode.SPACE) primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center) {
                Text("空格", fontSize = 10.sp, color = onSurface)
            }
            Text(if (mode == CursorGestureMode.SPACE) "按住 $arrow" else "不移动", fontSize = 12.sp,
                color = if (mode == CursorGestureMode.SPACE) primary else onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun CandidateStrip(withComments: Boolean, narrow: Boolean = false) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val hint = onSurface.copy(alpha = 0.55f)
    Row(
        Modifier.then(if (narrow) Modifier.width(70.dp) else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text("你好", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, maxLines = 1)
        if (withComments) Text(" nǐ hǎo", fontSize = 9.sp, color = hint, maxLines = 1, overflow = TextOverflow.Clip)
        if (!narrow) {
            Spacer(Modifier.width(8.dp))
            Text("拟好", fontSize = 13.sp, color = onSurface, maxLines = 1)
            if (withComments) Text(" nǐ hǎo", fontSize = 9.sp, color = hint, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

/** 候选旁的拼音／编码：显示、按宽度自动、隐藏。 */
@Composable
internal fun CandidateCommentIllustration(mode: CandidateCommentMode) {
    when (mode) {
        CandidateCommentMode.SHOW -> CandidateStrip(withComments = true)
        CandidateCommentMode.HIDE -> CandidateStrip(withComments = false)
        CandidateCommentMode.AUTO -> Column(verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
            CandidateStrip(withComments = true)
            CandidateStrip(withComments = false, narrow = true)
        }
    }
}

/**
 * 候选栏取消按钮：打字时候选栏最左边的「<」，点它取消当前输入；关掉后候选从最左边开始。
 * 两端圆底按钮和真实候选栏一样（左取消、右展开），随开关实时变化。
 */
@Composable
internal fun CandidateCancelIllustration(show: Boolean) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val muted = onSurface.copy(alpha = 0.6f)
    @Composable
    fun EdgeButton(icon: ImageVector, highlight: Boolean) {
        Box(Modifier.size(26.dp).clip(CircleShape)
            .background(if (highlight) primary.copy(alpha = 0.3f) else onSurface.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (highlight) primary else muted, modifier = Modifier.size(20.dp))
        }
    }
    Column(Modifier.fillMaxWidth().testTag("candidate-cancel-illustration").clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (show) EdgeButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, highlight = true)
            Spacer(Modifier.width(if (show) 8.dp else 4.dp))
            Text("你好", fontSize = 13.sp, color = primary, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Text("拟好", fontSize = 13.sp, color = onSurface, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Text("你", fontSize = 13.sp, color = onSurface, maxLines = 1)
            Spacer(Modifier.weight(1f))
            EdgeButton(Icons.Filled.KeyboardArrowUp, highlight = false)
        }
        Spacer(Modifier.height(4.dp))
        // 开着时「↑」对着左箭头（按钮中心离边 4 + 13 = 17dp）。
        Text(if (show) "↑ 点它取消当前输入" else "候选词从最左边开始", fontSize = 12.sp, maxLines = 1,
            color = if (show) primary else muted, modifier = Modifier.padding(start = if (show) 13.dp else 4.dp))
    }
}

/** 候选注释内容：拼音／编码，或中英释义。 */
@Composable
internal fun CandidateCommentContentIllustration(content: com.kingzcheung.xime.settings.CandidateCommentContent) {
    val hint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val gloss = content == com.kingzcheung.xime.settings.CandidateCommentContent.GLOSS
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(if (gloss) "你好" to " hello" else "你好" to " nǐ hǎo",
            if (gloss) "apple" to " 苹果" else "苹果" to " píng guǒ").forEachIndexed { index, (word, note) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.Bottom) {
                Text(word, fontSize = 13.sp, maxLines = 1, fontWeight = if (index == 0) FontWeight.Medium else null,
                    color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(note, fontSize = 9.sp, color = hint, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
    }
}

/** 空格键其他长按行为：连续空格或语音开关。 */
@Composable
internal fun SpaceHoldIllustration(voice: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.width(96.dp).height(22.dp).clip(RoundedCornerShape(5.dp))
        .background(primary.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
        Text("空格", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    }
    Spacer(Modifier.height(6.dp))
    Text(if (voice) "长按 → 开始／结束语音" else "长按 → 连续输入空格", fontSize = 12.sp, color = primary, maxLines = 1)
}

/** 振动反馈：跟随系统、始终振动、关闭。 */
@Composable
internal fun HapticModeIllustration(mode: String) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val (mark, caption) = when (mode) {
        "enabled" -> "〰▮〰" to "按键振动"
        "disabled" -> "▮" to "不振动"
        else -> "⚙→▮" to "系统设置"
    }
    // 行高写明，不继承正文 24sp 行高；卡片高度按同样的行高算（见 [HAPTIC_CARD_TEXT_HEIGHT]）。
    // 窄屏三张卡并排时图示不换行，否则会把下面的小字挤出卡片。
    Text(mark, fontSize = 20.sp, lineHeight = 24.sp, color = if (mode == "disabled") muted else primary, maxLines = 1, softWrap = false)
    Spacer(Modifier.height(4.dp))
    Text(caption, fontSize = 12.sp, lineHeight = 16.sp, color = if (mode == "disabled") muted else primary, maxLines = 1)
}

/** [HapticModeIllustration] 两行文字的行高之和，随系统字号缩放。 */
internal val HAPTIC_CARD_TEXT_HEIGHT = 40.sp

/** 三行字母错位示意：按真实单位画出 10/9/7 键，突出 s 与 z 看是否对齐。 */
@Composable
internal fun QwertyStaggerIllustration(stagger: Float) {
    val geometry = com.kingzcheung.xime.ui.keyboard.LetterKeyboardFrame(listOf(10, 9, 7)).geometry(1f, stagger)
    val key = MaterialTheme.colorScheme.surface
    val mark = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val unit = size.width / 10f
        val rowHeight = size.height / 3f
        val gap = unit * 0.12f
        fun row(index: Int, start: Float, count: Int, highlight: Int) {
            repeat(count) { i ->
                drawRoundRect(if (i == highlight) mark else key,
                    androidx.compose.ui.geometry.Offset((start + i) * unit + gap / 2f, index * rowHeight + gap / 2f),
                    androidx.compose.ui.geometry.Size(unit - gap, rowHeight - gap),
                    androidx.compose.ui.geometry.CornerRadius(unit * 0.15f))
            }
        }
        row(0, 0f, 10, -1)
        row(1, geometry.middleRowInsetDp, 9, 1)   // s
        row(2, geometry.outerKeyWeight, 7, 0)     // z
    }
}

/** 标准 ?123 底排预览：句号紧邻逗号，仅从模式键借位，按设置实时变化。 */
@Composable
internal fun SpacePunctuationIllustration(comma: Boolean, period: Boolean) {
    val (left, right) = com.kingzcheung.xime.ui.keyboard.spacePunctuationKeys(comma, period, fullWidth = true)
    val onSurface = MaterialTheme.colorScheme.onSurface
    @Composable
    fun RowScopeKey(label: String, sub: String?, weight: Float, scope: androidx.compose.foundation.layout.RowScope, accent: Boolean = false) {
        with(scope) {
            Box(Modifier.weight(weight).height(34.dp).padding(horizontal = 2.dp).clip(RoundedCornerShape(5.dp))
                .background(if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface)) {
                Text(label, Modifier.align(Alignment.Center), fontSize = 12.sp, color = onSurface, maxLines = 1)
                if (sub != null) Text(sub, Modifier.align(Alignment.TopEnd).padding(end = 3.dp), fontSize = 9.sp,
                    color = onSurface.copy(alpha = 0.6f))
            }
        }
    }
    val mode = com.kingzcheung.xime.ui.keyboard.configuredPeriodWeights(2.4f, 0.8f, period)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RowScopeKey("?123", null, mode.mode, this)
        if (comma) RowScopeKey(left?.primary ?: "，", left?.alternate ?: "。", 0.8f, this, accent = true)
        if (right != null) RowScopeKey(right.primary, right.alternate, mode.period, this, accent = true)
        RowScopeKey("空格", null, 3f + if (comma) 0f else 0.8f, this)
        RowScopeKey("中", null, 0.8f, this)
        RowScopeKey("↵", null, 1.2f, this)
    }
}
