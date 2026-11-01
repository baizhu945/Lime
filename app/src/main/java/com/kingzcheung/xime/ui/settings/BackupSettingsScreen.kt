package com.kingzcheung.xime.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Download
import androidx.compose.material.icons.twotone.Person
import androidx.compose.material.icons.twotone.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复：把设置导出为本地 zip，或从 zip 恢复。
 *
 * 文件位置完全由系统文件选择器决定，备份包不上传、不发送到其他地方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 和其他开关一样记住选择（以前只存在页面状态里，退出再进来就变回关）。
    var includePersonal by remember { mutableStateOf(BackupChoices.includePersonal(context)) }
    // 当前操作（"export"/"import"）：loading 只出现在触发它的按钮上，其余按钮仅禁用
    var busyOp by remember { mutableStateOf<String?>(null) }
    val busy = busyOp != null
    var message by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busyOp = "export"
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { BackupManager.exportTo(context, uri, includePersonal) }
            busyOp = null
            message = result.fold(
                onSuccess = { "备份已导出" + if (includePersonal) "（含个人词库与学习数据）" else "" },
                onFailure = { "导出失败：${it.message ?: "未知错误"}" }
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busyOp = "import"
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { BackupManager.readBackup(context, uri) }
            busyOp = null
            result.fold(
                onSuccess = { pendingImport = it },
                onFailure = { message = "无法导入：${it.message ?: "未知错误"}" }
            )
        }
    }

    pendingImport?.let { bytes ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("从备份恢复") },
            text = { Text("备份中的设置会覆盖当前对应的设置；若备份含个人词库，也会覆盖当前词库。恢复后输入方案会重新部署。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    busyOp = "import"
                    message = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { BackupManager.importBackup(context, bytes) }
                        busyOp = null
                        message = result.fold(
                            onSuccess = { "恢复完成，设置已生效" },
                            onFailure = { "恢复失败：${it.message ?: "未知错误"}" }
                        )
                    }
                }) { Text("覆盖并恢复") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .settingsVerticalScroll(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(
                title = "备份内容",
                content = {
                    SettingsToggleItem(
                        icon = Icons.TwoTone.Person,
                        title = "包含个人词库与学习数据",
                        subtitle = "自造词、调频记录、自定义短语和个人词库；属于隐私数据，体积也较大",
                        checked = includePersonal,
                        onCheckedChange = { includePersonal = it; BackupChoices.setIncludePersonal(context, it) }
                    )
                    Text(
                        text = "始终包含：全部设置项、工具栏与菜单排列、主题与背景图、键盘布局与尺寸、自定义布局和方案定制。" +
                            "不含插件、下载的模型、补充词库和剪贴板内容。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    )
                }
            )

            SettingsSection(
                title = "操作",
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                                    exportLauncher.launch("Lime备份-$date.zip")
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !busy
                            ) {
                                BusyIcon(busyOp == "export") {
                                    Icon(Icons.TwoTone.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Text("导出备份", modifier = Modifier.padding(start = 6.dp))
                            }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                                modifier = Modifier.weight(1f),
                                enabled = !busy
                            ) {
                                BusyIcon(busyOp == "import") {
                                    Icon(Icons.TwoTone.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Text("从备份恢复", modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        Text(
                            text = "备份文件只保存到你选择的位置，不会上传到任何地方。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        message?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun BusyIcon(busy: Boolean, icon: @Composable () -> Unit) {
    if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else icon()
}

/** 备份页记住的选项。 */
internal object BackupChoices {
    const val KEY_INCLUDE_PERSONAL = "backup_include_personal"
    fun includePersonal(context: android.content.Context): Boolean =
        com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context).getBoolean(KEY_INCLUDE_PERSONAL, false)
    fun setIncludePersonal(context: android.content.Context, include: Boolean) {
        com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context).edit()
            .putBoolean(KEY_INCLUDE_PERSONAL, include).apply()
    }
}
