package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.Backspace
import androidx.compose.material.icons.automirrored.twotone.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.twotone.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.model.ModelCategory
import com.kingzcheung.xime.model.ModelManager
import androidx.compose.ui.res.stringResource
import com.kingzcheung.xime.R
import com.kingzcheung.xime.model.DeviceModelProfiles
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.viewmodel.SmartPredictionSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPredictionSettingsContent(
    onBack: () -> Unit,
    onNavigateToModelManagement: () -> Unit = {},
    onNavigateToModelDetail: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: SmartPredictionSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val exportFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportLearningData) }
    val importFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> pendingImport = uri }
    pendingImport?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入学习文件") },
            text = { Text("将替换本机学习记录和个人语料方案。导入前会保留一份本机备份；不会修改 Rime 用户词库或模型。") },
            confirmButton = { Button(onClick = { pendingImport = null; viewModel.importLearningData(uri) }) { Text("导入替换") } },
            dismissButton = { OutlinedButton(onClick = { pendingImport = null }) { Text("取消") } }
        )
    }

    // 模型清单来自远程 index，响应式收集以在加载完成后刷新（仅 PREDICTION 类别）
    val allModels by ModelManager.modelsFlow.collectAsStateWithLifecycle()
    // 联想关着时选模型不起作用：「选择模型」和「联想行为」一样只在开着时显示（列表为空就不画这一组）。
    val predictionModels = allModels.filter { it.category == ModelCategory.PREDICTION }.takeIf { uiState.isEnabled }.orEmpty()

    val savedModelId = remember {
        SettingsPreferences.getPredictionSelectedModel(context)
    }
    var selectedModelId by remember {
        mutableStateOf(savedModelId)
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            viewModel.showToast(message)
            viewModel.clearToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("智能联想") },
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
        val listState = rememberSettingsSearchListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.settingsGutterScroll(listState)
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                SettingsSection(title = "功能开关", content = {
                    Row(
                        modifier = Modifier.settingsSearchAnchor("启用智能联想")
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用智能联想",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "内置中文基础联想，可下载模型增强预测",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Switch(
                                checked = uiState.isEnabled,
                                onCheckedChange = { viewModel.setEnabled(it) },
                                colors = SwitchDefaults.colors()
                            )
                        }
                    }

                    if (!uiState.hasModel) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "已内置基础联想；下载模型可增强预测",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                })
            }

            if (uiState.isEnabled) {
                item {
                    var spaceCommitEnabled by remember {
                        mutableStateOf(SettingsPreferences.isSpaceCommitAssociationEnabled(context))
                    }
                    var singleMode by remember {
                        mutableStateOf(SettingsPreferences.isSingleAssociationMode(context))
                    }
                    var deleteSkipsAssociation by remember {
                        mutableStateOf(SettingsPreferences.isDeleteSkipsAssociation(context))
                    }
                    var deleteRecovery by remember {
                        mutableStateOf(SettingsPreferences.isDeleteRecovery(context))
                    }
                    SettingsSection(title = "联想行为", content = {
                        SettingsToggleItem(
                            icon = Icons.TwoTone.AutoAwesome,
                            title = "空格上屏联想候选",
                            subtitle = "有联想候选时，按空格键直接上屏第一个联想词",
                            checked = spaceCommitEnabled,
                            onCheckedChange = {
                                spaceCommitEnabled = it
                                SettingsPreferences.setSpaceCommitAssociationEnabled(context, it)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsToggleItem(
                            icon = Icons.AutoMirrored.TwoTone.Backspace,
                            title = "退格直接删字",
                            subtitle = "有联想词时，退格直接删掉刚上屏的字，不用先按一下收起联想",
                            checked = deleteSkipsAssociation,
                            onCheckedChange = {
                                deleteSkipsAssociation = it
                                SettingsPreferences.setDeleteSkipsAssociation(context, it)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsToggleItem(
                            icon = Icons.AutoMirrored.TwoTone.Undo,
                            title = "删字后给同音字",
                            subtitle = "退格删掉字后，候选栏先放回删掉的字，后面跟同音字，删错了点一下补回来",
                            checked = deleteRecovery,
                            onCheckedChange = {
                                deleteRecovery = it
                                SettingsPreferences.setDeleteRecovery(context, it)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        AssociationModeRow(
                            title = "连续联想",
                            subtitle = "联想词上屏后继续推理，可连续空格上屏联想词",
                            isSelected = !singleMode,
                            onClick = {
                                singleMode = false
                                SettingsPreferences.setAssociationSingleMode(context, false)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        AssociationModeRow(
                            title = "单次联想",
                            subtitle = "联想词上屏一次后停止推理，待下次输入再联想",
                            isSelected = singleMode,
                            onClick = {
                                singleMode = true
                                SettingsPreferences.setAssociationSingleMode(context, true)
                            }
                        )
                    })
                }
            }

            if (predictionModels.isNotEmpty()) {
                item { Column {
                    SettingsSection(title = stringResource(R.string.section_choose_model), searchKey = "选择模型", content = {
                        predictionModels.forEach { model ->
                            PredictionModelCard(
                                modelInfo = model,
                                isSelected = model.id == selectedModelId,
                                onSelect = {
                                    selectedModelId = model.id
                                    viewModel.selectModel(model.id)
                                },
                                onOpenInStore = { onNavigateToModelDetail(model.id) }
                            )
                            if (model != predictionModels.last()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 56.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    })
                    // 有人看不懂 base / small（用户 10-31 转述）：直接说该选哪个。
                    SettingsNote(stringResource(R.string.model_prediction_choose_note))
                } }
            }

            item {
                // 与键盘菜单「无痕」是同一个开关，两边改动立即互相反映。
                val toggles = com.kingzcheung.xime.ui.keyboard.rememberQuickToggleStates()
                SettingsSection(title = "无痕", content = {
                    SettingsToggleItem(
                        icon = Icons.TwoTone.VisibilityOff,
                        title = "无痕模式",
                        subtitle = "打字不学新词和选词顺序；复制的内容不记进键盘剪贴板、不同步，系统的复制粘贴照常能用；密码框始终自动无痕",
                        checked = toggles.incognito,
                        onCheckedChange = { SettingsPreferences.setIncognito(context, it) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // 删掉刚上屏的词不学习（B站反馈 10-31，默认关）；无痕开着时本来就什么都不学，置灰。
                    var unlearnRejected by remember { mutableStateOf(SettingsPreferences.isUnlearnRejectedCommit(context)) }
                    SettingsToggleItem(
                        icon = Icons.AutoMirrored.TwoTone.Backspace,
                        title = "删掉刚打的词不学习",
                        subtitle = "词一上屏就整个删掉（退格删完或上滑清空），这一次不记进词库，误触打出的怪词不会排到前面",
                        checked = unlearnRejected,
                        onCheckedChange = {
                            unlearnRejected = it
                            SettingsPreferences.setUnlearnRejectedCommit(context, it)
                        },
                        lockMessage = if (toggles.incognito) "无痕模式开着时什么都不学，不需要这项" else null,
                    )
                })
            }

            item {
                LearningDataControls(
                    unique = uiState.cacheSize, observations = uiState.observations,
                    profileSize = uiState.profileSize, busy = uiState.isSaving,
                    status = uiState.learningStatus,
                    onSave = viewModel::saveUserData, onRefresh = viewModel::refreshCacheSize,
                    onExport = { exportFile.launch("Lime-personal-learning.json") },
                    onImport = { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                )
            }

            // 模型的下载和删除统一在「扩展 › 模型」，这里只选用。
        }
    }
}

@Composable
private fun AssociationModeRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.settingsSearchAnchor(title)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(selected = isSelected, onClick = onClick)
    }
}

@Composable
private fun PredictionModelCard(
    modelInfo: com.kingzcheung.xime.model.ModelInfo,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onOpenInStore: () -> Unit
) {
    val context = LocalContext.current
    val revision by ModelManager.installedRevision.collectAsStateWithLifecycle()
    val downloads by ModelManager.downloadStates.collectAsStateWithLifecycle()
    val isDownloaded = remember(modelInfo, revision) { ModelManager.isModelReady(context, modelInfo.id) }
    val progress = downloads[modelInfo.id] as? com.kingzcheung.xime.model.ModelDownloadState.Downloading

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isDownloaded) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = if (isDownloaded) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                // 联想两档的名字随界面语言；其他模型（商店里来的）仍用目录里的中文名。
                text = when (modelInfo.id) {
                    DeviceModelProfiles.BASE -> stringResource(R.string.model_prediction_base_name)
                    DeviceModelProfiles.SMALL -> stringResource(R.string.model_prediction_small_name)
                    else -> modelInfo.name
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (modelInfo.size.isNotEmpty()) {
                    Text(
                        text = modelInfo.size,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isDownloaded)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = stringResource(if (isDownloaded) R.string.model_downloaded else R.string.model_not_downloaded),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDownloaded) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        if (progress != null) {
            Column(Modifier.width(84.dp).padding(start = 8.dp)) {
                androidx.compose.material3.LinearProgressIndicator(progress = { progress.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
                Text("${(progress.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
        } else if (isDownloaded) {
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onSelect,
                enabled = !isSelected,
                shape = RoundedCornerShape(50)
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(stringResource(if (isSelected) R.string.model_in_use else R.string.model_use))
            }
        } else {
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onOpenInStore,
                shape = RoundedCornerShape(50)
            ) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.model_go_to_store))
            }
        }
    }
}

@Composable
internal fun LearningDataControls(
    unique: Int, observations: Long, profileSize: Int, busy: Boolean, status: String,
    onSave: () -> Unit, onRefresh: () -> Unit, onExport: () -> Unit, onImport: () -> Unit,
) {
    SettingsSection(title = "用户学习数据") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本机学习：$unique 种组合 · 累计 $observations 次")
            Text("个人语料方案：$profileSize 条联想", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("输入后自动保存。语料方案与实际输入记录分开统计；可导出到电脑查看、删改后再导入。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Stack actions so large fonts and narrow keyboards cannot clip labels.
            Button(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导出文件") }
            OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导入学习文件") }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
