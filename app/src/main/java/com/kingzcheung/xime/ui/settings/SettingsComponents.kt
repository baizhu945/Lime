package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.settings.BackgroundConfig
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.KeyboardColorsConfig
import com.kingzcheung.xime.ui.theme.KeyboardColorScheme
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.ui.theme.visualMaterial
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.keyboard.legibleForTheme

@Composable
fun SettingsSection(
    title: String,
    /** 设置搜索跳转用的定位名；标题随界面语言翻译时传中文原名，搜索仍能找到这里。 */
    searchKey: String = title,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 0.dp
        ) {
            Column(Modifier.settingsSearchAnchor(searchKey)
                .visualMaterial(VisualStyles.current, 12.dp, level = com.kingzcheung.xime.ui.theme.MaterialLevel.RAISED), content = content)
        }
    }
}

/**
 * 列表页（词库、布局管理、已安装的方案）的一行：和 [SettingsSection] 同一种底色和圆角。每行单独放进 LazyColumn，
 * 组里第一行圆上边、最后一行圆下边，中间用和首页一样的细分隔线，几行连起来就是一张卡片，长列表也不用一次排完。
 */
@Composable
internal fun SettingsGroupRow(index: Int, count: Int, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val radius = 12.dp
    val top = if (index == 0) radius else 0.dp
    val bottom = if (index >= count - 1) radius else 0.dp
    Column(modifier.fillMaxWidth()
        .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
        .background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        if (index > 0) SettingsRowDivider()
        content()
    }
}

/** 卡片里两行之间的分隔线，和设置首页相同。 */
@Composable
internal fun SettingsRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(start = 16.dp), thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** 卡片上方的组名，和 [SettingsSection] 的标题同一个样子。 */
@Composable
internal fun SettingsGroupTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 8.dp))
}

/** 卡片下面的一句说明：小字、次要色。提醒色只留给真正的警告。 */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}

/** 卡片里的一行：标题、灰色第二行，右边放这一行的开关或按钮。和 [SettingsItem] 同样的字号字重，只是不带图标。 */
@Composable
internal fun SettingsListRow(title: String, detail: String, modifier: Modifier = Modifier,
                             trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showArrow: Boolean = false,
    /** 右侧状态小字，如「已设置」「未连接」或当前主题名；[statusTone] 决定颜色。 */
    status: String? = null,
    statusTone: SettingsStatusTone = SettingsStatusTone.NEUTRAL,
    /** 搜索定位和测试标签用的名字，见 [SettingsSection]。 */
    searchKey: String = title,
) {
    Row(
        modifier = Modifier
            .settingsSearchAnchor(searchKey)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标圆角背景
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (status != null) {
            Spacer(Modifier.width(12.dp))
            Text(status, style = MaterialTheme.typography.labelMedium, color = statusTone.color(),
                maxLines = 1, modifier = Modifier.testTag("settings-status:$searchKey"))
        }
        if (showArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

enum class SettingsStatusTone { OK, WARNING, NEUTRAL }

/** 绿色和提醒色按页面底色深浅取不同明度，浅色、深色与半透明玻璃底上都看得清。 */
@Composable
private fun SettingsStatusTone.color(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return when (this) {
        SettingsStatusTone.OK -> if (dark) Color(0xFF81C995) else Color(0xFF1E8E3E)
        SettingsStatusTone.WARNING -> MaterialTheme.colorScheme.error
        SettingsStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
    showArrow: Boolean = false,
    /** 被别的设置锁住时的原因（如「已被『角标输入方式：长按』锁定」）：开关置灰不可点，并在说明下显示这句。 */
    lockMessage: String? = null,
    /** 搜索定位用的名字，见 [SettingsSection]。 */
    searchKey: String = title,
) {
    val locked = lockMessage != null
    Row(
        modifier = Modifier
            .settingsSearchAnchor(searchKey)
            .fillMaxWidth()
            .then(
                if (locked) Modifier
                else if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier.clickable { onCheckedChange(!checked) }
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (lockMessage != null) SettingsLockNote(lockMessage)
        }
        // 说明写满一行时也不能贴着开关：和其他“标题＋说明＋开关”的行一样留 12dp。
        Spacer(modifier = Modifier.width(12.dp))
        if (showArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = !locked,
            colors = settingsSwitchColors()
        )
    }
}

/**
 * 设置里所有可能不能拨的开关（英文始终开启、被别的设置锁住、条件不满足、正在保存）都用这套颜色：
 * Material 默认「不能拨且开着」是灰底深色滑块，深色主题下看着像关着；「不能拨且关着」的底几乎透明，
 * 深色主题下是一块近黑的槽，和旁边能拨的关着开关不像一类（10-31 走查「右半边也放 G 键」）。
 * 这里两种都照能拨时的样子调淡：开着保留主题色，关着保留灰底、描边和滑块，一眼能分清开关状态，也能看出现在拨不动。
 */
@Composable
internal fun settingsSwitchColors(): SwitchColors {
    val scheme = MaterialTheme.colorScheme
    fun faded(color: Color) = color.copy(alpha = 0.55f).compositeOver(scheme.surface)
    return SwitchDefaults.colors(
        disabledCheckedThumbColor = scheme.onPrimary,
        disabledCheckedTrackColor = faded(scheme.primary),
        disabledCheckedIconColor = faded(scheme.primary),
        disabledCheckedBorderColor = Color.Transparent,
        disabledUncheckedThumbColor = faded(scheme.outline),
        disabledUncheckedTrackColor = faded(scheme.surfaceContainerHighest),
        disabledUncheckedIconColor = faded(scheme.surfaceContainerHighest),
        disabledUncheckedBorderColor = faded(scheme.outline),
    )
}

/** 被锁住的设置下面那一行「已被…锁定」，各页统一样式。 */
@Composable
fun SettingsLockNote(message: String, modifier: Modifier = Modifier) {
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        modifier = modifier.padding(top = 2.dp).testTag("settings-lock:$message"))
}

@Composable
fun SchemaItem(
    schema: SchemaInfo,
    isSelected: Boolean,
    isDownloaded: Boolean,
    isLoading: Boolean = false,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onUpdate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isDownloaded && !isLoading, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = schema.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (!isDownloaded) MaterialTheme.colorScheme.outline
                else if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            if (schema.description.isNotEmpty()) {
                Text(
                    text = schema.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (schema.version.isNotEmpty()) {
                    Text(
                        text = "版本: ${schema.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (schema.author.isNotEmpty()) {
                    Text(
                        text = "作者: ${schema.author}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (!isDownloaded) {
                    Text(
                        text = "未下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (!isDownloaded) {
            OutlinedButton(
                onClick = onDownload,
                enabled = !isLoading,
                shape = RoundedCornerShape(50)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(if (isLoading) "下载中" else "下载")
            }
        } else {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选择",
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            OutlinedButton(
                onClick = onUpdate,
                enabled = !isLoading,
                shape = RoundedCornerShape(50)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(if (isLoading) "更新中" else "更新")
            }
        }
    }
}

@Composable
fun SchemaSelectDialog(
    schemas: List<SchemaInfo>,
    currentSchema: String,
    onSchemaSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择输入方案") },
        text = {
            Column {
                schemas.forEach { schema ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (currentSchema != schema.schemaId) {
                                    onSchemaSelected(schema.schemaId)
                                    onDismiss()
                                } else {
                                    onDismiss()
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = schema.schemaId == currentSchema,
                            onClick = {
                                if (currentSchema != schema.schemaId) {
                                    onSchemaSelected(schema.schemaId)
                                    onDismiss()
                                } else {
                                    onDismiss()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = schema.name, fontWeight = FontWeight.Medium)
                            Text(
                                text = schema.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        }
    )
}

@Composable
fun ThemeCard(
    title: String,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSystem: Boolean = false,
    accentColor: Color? = null,
    keyBgColor: Color? = null,
) {
    val backgroundColor = if (isDark) Color(0xFF202124) else Color(0xFFE8EAED)
    val keyColor = keyBgColor ?: if (isDark) Color(0xFF35363A) else Color(0xFFFFFFFF)
    val specialKeyColor = accentColor ?: if (isDark) Color(0xFF4A4A4A) else Color(0xFFD3E3FD)
    val textColor = if (isDark) Color(0xFFE8EAED) else Color(0xFF202124)
    val candidateBarColor = if (isDark) Color(0xFF2D2D2D) else Color(0xFFF8F9FA)

    Column(
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.3f)
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(12.dp)
                        )
                    } else {
                        Modifier
                    }
                ),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 0.dp,
            onClick = onClick
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                if (isSystem) {
                    // 跟随系统: 左半浅色 + 右半深色
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            // 左半浅色
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                                    .background(Color(0xFFE8EAED))
                                    .padding(3.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color(0xFFF8F9FA))
                                            .padding(horizontal = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(12.dp)
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(accentColor ?: Color(0xFF1A73E8))
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color.White)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(1.dp))
                            // 右半深色
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                                    .background(Color(0xFF202124))
                                    .padding(3.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color(0xFF2D2D2D))
                                            .padding(horizontal = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(12.dp)
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(Color(0xFF8AB4F8))
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color(0xFF35363A))
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(backgroundColor)
                            .padding(4.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(candidateBarColor)
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(16.dp)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            accentColor ?: if (isDark) Color(0xFF8AB4F8) else Color(
                                                0xFF1A73E8
                                            )
                                        )
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            repeat(3) { rowIndex ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(vertical = 1.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    val keysInRow = if (rowIndex == 2) 3 else 10
                                    repeat(keysInRow) { keyIndex ->
                                        val isSpecialKey =
                                            (rowIndex == 2 && (keyIndex == 0 || keyIndex == 2))
                                        val isSpaceKey = (rowIndex == 2 && keyIndex == 1)
                                        Box(
                                            modifier = Modifier
                                                .weight(if (isSpaceKey) 4f else 1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(if (isSpecialKey) specialKeyColor else keyColor)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CodeDisplayCard(
    title: String,
    isSelected: Boolean,
    showCodeInInputBox: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val selectedBg = primary.copy(alpha = 0.15f)

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = primary,
                            shape = RoundedCornerShape(12.dp)
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp,
                onClick = onClick
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (showCodeInInputBox) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Lime shu ru fa",
                                        fontSize = 12.sp,
                                        color = onSurface,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.width(1.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(13.dp)
                                            .background(primary)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Column(
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(vertical = 0.dp)
                            ) {
                                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Text(
                                        "shu ru fa",
                                        fontSize = 10.sp,
                                        lineHeight = 1.sp,
                                        color = onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(selectedBg)
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "输入法",
                                            fontSize = 13.sp,
                                            color = primary,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "力学",
                                        fontSize = 13.sp,
                                        color = onSurface,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) primary else onSurface
            )
        }
    }
}

// ========== Previews ==========

@Preview(name = "CodeDisplayCard - 选中+显示码")
@Composable
fun CodeDisplayCardPreview_SelectedWithCode() {
    XimeTheme {
        CodeDisplayCard(
            title = "Lime",
            isSelected = true,
            showCodeInInputBox = true,
            onClick = {},
            modifier = Modifier
                .padding(16.dp)
                .height(160.dp)
        )
    }
}

@Preview(name = "CodeDisplayCard - 不显示码")
@Composable
fun CodeDisplayCardPreview_NotSelectedNoCode() {
    XimeTheme {
        CodeDisplayCard(
            title = "Lime",
            isSelected = true,
            showCodeInInputBox = false,
            onClick = {},
            modifier = Modifier
                .padding(16.dp)
                .height(160.dp)
        )
    }
}

@Composable
fun CommentDisplayCard(
    title: String,
    isSelected: Boolean,
    showComment: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(modifier = modifier) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onClick() }
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                width = 2.dp,
                                color = primary,
                                shape = RoundedCornerShape(12.dp)
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "输入法",
                                        fontSize = 14.sp,
                                        color = primary,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    if (showComment) {
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = "ltif",
                                            fontSize = 9.sp,
                                            color = onSurface.copy(alpha = 0.5f),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "力学",
                                fontSize = 14.sp,
                                color = onSurface,
                                maxLines = 1
                            )
                            if (showComment) {
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "lixue",
                                    fontSize = 9.sp,
                                    color = onSurface.copy(alpha = 0.5f),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) primary else onSurface,
                    maxLines = 1
                )
            }
        }
    }
}

@Preview(name = "CommentDisplayCard - 显示注释", heightDp = 120)
@Composable
fun CommentDisplayCardPreview_Show() {
    XimeTheme {
        CommentDisplayCard(
            title = "显示",
            isSelected = true,
            showComment = true,
            onClick = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(10.dp)
        )
    }
}

@Preview(name = "CommentDisplayCard - 隐藏注释", heightDp = 120)
@Composable
fun CommentDisplayCardPreview_Hide() {
    XimeTheme {
        CommentDisplayCard(
            title = "隐藏",
            isSelected = false,
            showComment = false,
            onClick = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(10.dp)
        )
    }
}


@Composable
fun KeyboardThemeCard(
    theme: KeyboardColorScheme,
    glass: com.kingzcheung.xime.settings.FrostedGlassProfiles,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    previewDark: Boolean? = null,
    previewAspectRatio: Float = 1.75f,
) {
    val kbColors = KeysConfigHelper.getKeyboardColors()
    val selectionColor = MaterialTheme.colorScheme.primary
    val swatchColor = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) theme.accentDark else theme.accentLight
    val longToColor: (Long) -> Color = {
        if (it > 0xFFFFFF) Color(it.toInt()) else Color((0xFF000000 or it).toInt())
    }
    val globalKeyBgLight = KeyboardThemes.getKeyBgColorOverride(theme.id, false)
        ?: longToColor(kbColors.keyBgColor)
    val globalKeyBgDark = KeyboardThemes.getKeyBgColorOverride(theme.id, true)
        ?: longToColor(kbColors.keyBgColorDark)
    Column(
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(previewAspectRatio)
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = selectionColor,
                            shape = RoundedCornerShape(12.dp)
                        )
                    } else {
                        Modifier
                    }
                ),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 0.dp,
            onClick = onClick
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (previewDark != true) ThemeHalfPreview(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        glass = glass.light.legibleForTheme(theme.id, false),
                        keyboardBackground = theme.keyboardBackground,
                        isDark = false,
                        fallbackBg = theme.keyboardBgLight,
                        candidateBarColor = theme.candidateBarBgLight,
                        accentColor = theme.accentLight,
                        keyColor = globalKeyBgLight,
                        specialKeyColor = com.kingzcheung.xime.ui.theme.resolvedSpecialKeyColor(theme, false, kbColors.specialKeyBgColor?.let(longToColor)),
                        enterKeyColor = KeyboardThemes.getEnterKeyColor(theme.id, false),
                        isLeft = true,
                        single = previewDark != null,
                    )
                    if (previewDark != false) ThemeHalfPreview(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        glass = glass.dark.legibleForTheme(theme.id, true),
                        keyboardBackground = theme.keyboardBackground,
                        isDark = true,
                        fallbackBg = theme.keyboardBgDark,
                        candidateBarColor = theme.candidateBarBgDark,
                        accentColor = theme.accentDark,
                        keyColor = globalKeyBgDark,
                        specialKeyColor = com.kingzcheung.xime.ui.theme.resolvedSpecialKeyColor(theme, true, kbColors.specialKeyBgColorDark?.let(longToColor)),
                        enterKeyColor = KeyboardThemes.getEnterKeyColor(theme.id, true),
                        isLeft = false,
                        single = previewDark != null,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(swatchColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title ?: theme.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) selectionColor else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ThemeHalfPreview(
    modifier: Modifier = Modifier,
    glass: com.kingzcheung.xime.settings.FrostedGlassConfig,
    keyboardBackground: BackgroundConfig?,
    isDark: Boolean,
    fallbackBg: Color,
    candidateBarColor: Color,
    accentColor: Color,
    keyColor: Color,
    specialKeyColor: Color,
    enterKeyColor: Color,
    isLeft: Boolean,
    single: Boolean = false,
) {
    val shape = when {
        single -> RoundedCornerShape(8.dp)
        isLeft -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
        else -> RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
    }
    Box(
        modifier = modifier
            .clip(shape)
            // 玻璃预览透出同明暗的页面，而不是卡片本身的底色（与键盘实际效果一致）。
            .then(if (glass.enabled) Modifier.background(glassPreviewHost(isDark)) else Modifier)
            .keyboardBackground(keyboardBackground, isDark, fallbackBg, glass)
            .padding(4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isLeft) {
                    Box(
                        modifier = Modifier
                            .width(16.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(accentColor)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            repeat(3) { rowIndex ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.5.dp)
                ) {
                    val keysInRow = if (rowIndex == 2) 2 else 5
                    repeat(keysInRow) { keyIndex ->
                        val isSpecial =
                            (isLeft && rowIndex == 2 && (keyIndex == 0)) || (!isLeft && rowIndex == 2 && keyIndex == 1)
                        val isSpace = (rowIndex == 2 && keyIndex == if (isLeft) 1 else 0)
                        Box(
                            modifier = Modifier
                                .weight(if (isSpace) 3f else 1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(com.kingzcheung.xime.ui.keyboard.frostedKeyColor(when {
                                    !isLeft && rowIndex == 2 && keyIndex == 1 -> enterKeyColor
                                    isSpecial -> specialKeyColor
                                    else -> keyColor
                                }, if (isDark) Color.White else Color.Black, glass))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CandidateTextSizeCard(
    candidateTextSize: Float,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        "shu ru fa",
                        fontSize = 10.sp,
                        lineHeight = 1.sp,
                        color = onSurface.copy(alpha = 0.6f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(primary.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "输入法",
                            fontSize = candidateTextSize.sp,
                            color = primary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "力学",
                        fontSize = candidateTextSize.sp,
                        color = onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Preview(name = "CandidateTextSizeCard - default")
@Composable
fun CandidateTextSizeCardPreview() {
    XimeTheme {
        CandidateTextSizeCard(
            candidateTextSize = 19f,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        )
    }
}
