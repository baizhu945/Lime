package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.twotone.AspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.keyboard.KeyboardPanelLaunch
import kotlinx.coroutines.delay

internal fun keyboardPanelTitle(panel: KeyboardPanelLaunch.Panel) = when (panel) {
    KeyboardPanelLaunch.Panel.RESIZE -> "键盘尺寸与位置"
    KeyboardPanelLaunch.Panel.ARRANGE -> "工具栏与菜单排列"
}

/**
 * 键盘尺寸、悬浮与分体，以及工具栏／菜单排列都在键盘上直接拖着改。这一页弹出试打键盘，
 * 并打开与键盘菜单「键盘调节」「排列」同一个面板，数据和规则只有一份。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyboardPanelSettingsContent(panel: KeyboardPanelLaunch.Panel, onBack: () -> Unit) {
    val title = keyboardPanelTitle(panel)
    val description = when (panel) {
        KeyboardPanelLaunch.Panel.RESIZE -> "与键盘菜单里的「键盘调节」是同一个面板：拖动边框和四角改高度、宽度，" +
            "切换悬浮与分体，调透明度和底部留白，改好后点面板上的「确认」。"
        KeyboardPanelLaunch.Panel.ARRANGE -> "与键盘菜单里的「排列」是同一个面板：把入口拖到工具栏、菜单或不使用，" +
            "并调整先后顺序，改好后点右上角的 ✓。"
    }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var openCount by remember { mutableIntStateOf(0) }
    // 进页即打开一次；再点「打开」时先放掉焦点再拿回，让输入法重新开始这次输入并取走请求。
    LaunchedEffect(openCount) {
        KeyboardPanelLaunch.request(panel)
        if (openCount > 0) { focusManager.clearFocus(); delay(150) }
        focus.requestFocus()
        keyboard?.show()
    }
    DisposableEffect(Unit) { onDispose { KeyboardPanelLaunch.cancel() } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(title = title, content = {
                Text(description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                SettingsItem(
                    icon = if (panel == KeyboardPanelLaunch.Panel.RESIZE) Icons.TwoTone.AspectRatio else Icons.TwoTone.Padding,
                    title = if (panel == KeyboardPanelLaunch.Panel.RESIZE) "打开键盘调节" else "打开排列面板",
                    subtitle = "需要 Lime 是当前输入法；面板关掉后可再点这里",
                    onClick = { openCount++ },
                )
            })
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(
                        if (focused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusEvent { focused = it.hasFocus }
                        .testTag("keyboard-panel-test-field"),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 3,
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) Text("改好后可在这里试打", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            inner()
                        }
                    }
                )
            }
        }
    }
}
