package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.HardwareKeyboardPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareKeyboardSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    var options by remember { mutableStateOf(HardwareKeyboardPreferences.read(context)) }
    fun save(key: String, value: Boolean) {
        HardwareKeyboardPreferences.set(context, key, value)
        options = HardwareKeyboardPreferences.read(context)
    }
    Scaffold(topBar = { TopAppBar(title = { Text("物理键盘") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).settingsVerticalScroll()
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("语言切换", style = MaterialTheme.typography.titleMedium)
            HardwareOption("Ctrl + 空格", "按已启用语言的顺序循环切换，不输入空格", options.ctrlSpace) { save("ctrl_space", it) }
            HardwareOption("单独轻按 Shift", "当前非英语语言与英语互切；半秒内松开，与其他按键组合时不切换", options.shiftTap) { save("shift_tap", it) }
            Text("工具快捷键", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.settingsSearchAnchor("工具快捷键", bleed = 8.dp, bleedVertical = 4.dp).fillMaxWidth())
            Text("Ctrl + .：切换中英文标点（英文模式固定半角）。\nAlt + V：打开或关闭剪贴板，方向键选择，回车粘贴并关闭，Esc 取消。\nAlt + H：开始或停止语音输入。", style = MaterialTheme.typography.bodyMedium)
            Text("候选翻页", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.settingsSearchAnchor("候选翻页", bleed = 8.dp, bleedVertical = 4.dp).fillMaxWidth())
            Text("可多选。有待选内容时翻页，没有待选内容时输入符号；取消勾选后按普通符号处理。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CandidatePageOption("减号和等号", "− 上一页 / = 下一页", "page_minus_equals", options.pageMinusEquals) {
                save("page_minus_equals", it)
            }
            CandidatePageOption("中括号", "[ 上一页 / ] 下一页", "page_brackets", options.pageBrackets) {
                save("page_brackets", it)
            }
            CandidatePageOption("逗号和句号", ", 上一页 / . 下一页（含 < / >）", "page_comma_period", options.pageCommaPeriod) {
                save("page_comma_period", it)
            }
            Text("浮窗", style = MaterialTheme.typography.titleMedium)
            HardwareOption("候选跟随光标", "应用提供光标位置时生效；否则显示在底部", options.followCursor) { save("follow_cursor", it) }
            HardwareOption("联想也显示在候选窗", "上屏后的联想词和打字候选显示在同一个浮窗里；关闭时联想显示在工具条上",
                options.associationInWindow) { save("association_in_window", it) }
            HardwareOption("自动避开输入区域", "有位置数据时避让输入框；大文档优先避让光标附近", options.avoidEditor) { save("avoid_editor", it) }
            HardwareOption("靠边停靠", "左右边缘变为竖条，底部变为候选长条，拖近时显示蓝色轮廓", options.dockAtEdge) { save("dock_edge", it) }
            HardwareOption("底边使用三星式通栏", "吸附到底边时改为贴住屏幕底部的整宽候选栏，九个候选带 1–9 编号",
                options.bottomBar) { save("bottom_bar", it) }
            Text("浮条任意位置均可拖动；长按语言键选择语言。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HardwareOption(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.settingsSearchAnchor(title, bleed = 8.dp, bleedVertical = 4.dp).fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun CandidatePageOption(title: String, detail: String, key: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        // Keep the search anchor on an outer node so its testTag does not replace the
        // hardware option's own tag on the same semantics node.
        Box(Modifier.settingsSearchAnchor(title, bleed = 8.dp, bleedVertical = 4.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().testTag("hardware-$key")
                .toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
                .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Checkbox(checked = checked, onCheckedChange = null)
            }
        }
    }
}
