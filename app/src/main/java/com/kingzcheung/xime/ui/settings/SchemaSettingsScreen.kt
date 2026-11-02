package com.kingzcheung.xime.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SchemaMeta
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.settings.InputModes
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.viewmodel.LocalPackageItem
import com.kingzcheung.xime.viewmodel.SchemaLocalViewModel
import com.kingzcheung.xime.viewmodel.SchemaSettingsViewModel
import com.kingzcheung.xime.ui.menubar.rememberDragOrder
import com.kingzcheung.xime.ui.menubar.dragOrderItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaSettingsContent(
    onBack: () -> Unit,
    onNavigateToRimeFileBrowser: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: SchemaSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val localViewModel: SchemaLocalViewModel = viewModel()
    val localUiState by localViewModel.uiState.collectAsStateWithLifecycle()
    var showExtraSchemas by remember { mutableStateOf(false) }
    var englishEnabled by remember { mutableStateOf(InputLanguage.ENGLISH in LanguagePreferences.enabled(context)) }
    var modeOrderRevision by remember { mutableIntStateOf(0) }
    // F6: 从方案市场/导入返回时自动重扫描，新装方案立即出现
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
        localViewModel.loadLocalPackages()
        englishEnabled = InputLanguage.ENGLISH in LanguagePreferences.enabled(context)
        modeOrderRevision++
    }
    // 导入完成后刷新方案包；有同名文件没装上的，接着问用户（弹「更新共用文件」）。
    LaunchedEffect(Unit) {
        viewModel.importCompleted.collect { result ->
            localViewModel.loadLocalPackages()
            result?.pendingPackageId?.let { localViewModel.installImported(it, result.pendingName) }
        }
    }
    var pendingPlugin by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingPluginName by remember { mutableStateOf("") }
    var pendingLooseScript by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingLooseScriptName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        viewModel.scriptPrompt.collect { prompt ->
            val packageId = prompt.packageId
            if (packageId != null) localViewModel.noteScriptPrompt(packageId, prompt.displayName, prompt.scripts)
            else if (prompt.uri != null) {
                pendingLooseScript = prompt.uri
                pendingLooseScriptName = prompt.scripts.joinToString("\n").ifBlank { prompt.displayName }
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.pluginPrompt.collect { prompt ->
            pendingPlugin = prompt.uri
            pendingPluginName = prompt.displayName
        }
    }
    // 方案包装好或卸掉后，上面的方案列表跟着刷新。
    LaunchedEffect(localUiState.installingId, localUiState.packages) {
        if (localUiState.installingId == null) viewModel.refresh()
    }
    var showImportWarning by remember { mutableStateOf(false) }
    var dontShowImportWarning by remember { mutableStateOf(false) }
    var pendingImportAction by remember { mutableStateOf<() -> Unit>({}) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        for (uri in uris) {
            viewModel.importSchemaFile(uri)
        }
    }

    // 导入前说清楚认哪些文件、导入后去哪里；看过可勾「不再显示」，以后点「导入方案」直接选文件。
    if (showImportWarning) {
        AlertDialog(
            onDismissRequest = {
                showImportWarning = false
            },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text("导入方案", fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column {
                    Text("可以导入：", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "· Rime 方案压缩包（.zip、.tar.gz），比如从 GitHub 下载的方案仓库\n" +
                            "· 单个方案或词库文件（.schema.yaml、.dict.yaml）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "导入后直接安装并打开其中的主方案，出现在「已启用的输入组合」里；点下方「应用方案更改」后就能在键盘上用。" +
                            "和已有文件同名时会先问你，确认后备份再替换，卸载时恢复。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "五笔等常用方案在「扩展 › 方案」可以直接获取。也可以在手机的文件管理里把文件「分享」给 Lime 导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { dontShowImportWarning = !dontShowImportWarning }
                    ) {
                        Checkbox(checked = dontShowImportWarning, onCheckedChange = { dontShowImportWarning = it })
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("不再显示", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (dontShowImportWarning) SettingsPreferences.setSchemaImportWarningDismissed(context, true)
                        showImportWarning = false
                        pendingImportAction()
                    }
                ) { Text("选择文件") }
            },
            dismissButton = {
                TextButton(onClick = { showImportWarning = false }) {
                    Text("取消")
                }
            }
        )
    }

    fun requireImportWarning(action: () -> Unit) {
        if (SettingsPreferences.isSchemaImportWarningDismissed(context)) {
            action()
        } else {
            pendingImportAction = action
            showImportWarning = true
        }
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    LaunchedEffect(localUiState.toastMessage) {
        localUiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            localViewModel.clearToast()
        }
    }

    uiState.deployReport?.let { report ->
        AlertDialog(
            onDismissRequest = { viewModel.clearDeployReport() },
            title = { Text(if (report.startsWith("部署失败")) "部署失败" else "部署完成") },
            text = { Text(report) },
            confirmButton = { TextButton(onClick = { viewModel.clearDeployReport() }) { Text("知道了") } },
        )
    }

    if (pendingLooseScript != null) {
        ImportApprovalDialog(
            title = "安装方案脚本",
            body = "要安装脚本「$pendingLooseScriptName」吗？脚本在输入法进程里运行，可以读写文件。",
            confirmLabel = "安装脚本",
            onConfirm = {
                pendingLooseScript?.let { viewModel.confirmLooseScript(it) }
                pendingLooseScript = null
            },
            onDismiss = { pendingLooseScript = null },
        )
    }
    if (pendingPlugin != null) {
        ImportApprovalDialog(
            title = "安装插件",
            body = "安装插件「$pendingPluginName」？\n安装后默认关闭，不会看到你的输入。在插件页打开之后，它才能收到上屏文字。",
            confirmLabel = "安装并保持关闭",
            onConfirm = {
                pendingPlugin?.let { viewModel.confirmPluginInstall(it) }
                pendingPlugin = null
            },
            onDismiss = { pendingPlugin = null },
        )
    }
    if (localUiState.scriptPackageId != null) {
        ImportApprovalDialog(
            title = "安装方案脚本",
            body = "这个方案包含输入法脚本：\n${localUiState.scriptPaths.take(8).joinToString("\n")}\n脚本在输入法进程里运行，可以读写文件。",
            confirmLabel = "安装脚本",
            onConfirm = { localViewModel.confirmScripts() },
            onDismiss = { localViewModel.cancelScripts() },
        )
    }
    if (localUiState.conflictPackageId != null) {
        AlertDialog(
            onDismissRequest = { localViewModel.cancelConflictInstall() },
            title = { Text("更新共用文件") },
            text = {
                Text("以下 ${localUiState.conflictingFiles.size} 个同名文件将备份并更新：\n${localUiState.conflictingFiles.joinToString("、") { it.fileName }}\n不会卸载其他输入模式；卸载新方案时可恢复原文件。")
            },
            confirmButton = {
                TextButton(onClick = { localViewModel.confirmInstallWithReplace() }) {
                    Text("备份并更新")
                }
            },
            dismissButton = {
                TextButton(onClick = { localViewModel.cancelConflictInstall() }) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("已安装的方案") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { requireImportWarning { importLauncher.launch(arrayOf("*/*")) } }) {
                        Text("导入方案", style = MaterialTheme.typography.labelMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val installedIds = uiState.allSchemas.map { it.schemaId }.toSet()
            val visibleSchemas = uiState.allSchemas.filter {
                com.kingzcheung.xime.settings.CyimeInputDefaults.visibleSchema(it.schemaId, installedIds)
            }
            val schemaById = visibleSchemas.associateBy { it.schemaId }
            val fullOrder = remember(uiState.allSchemas, modeOrderRevision) {
                orderedInstalledModeIds(context, uiState.allSchemas)
            }
            val enabledIds = uiState.enabledSchemas.toSet()
            val savedEnabledIds = fullOrder.filter { id ->
                (id == InputModes.ENGLISH && englishEnabled) || (id in enabledIds && id in schemaById)
            }
            val dragOrder = rememberDragOrder(savedEnabledIds) { reordered ->
                if (reorderEnabledModes(context, uiState.allSchemas, enabledIds, englishEnabled, reordered)) modeOrderRevision++
            }
            val orderedEnabledIds = dragOrder.order
            // 和词库、布局管理同一套：组名 + 连成一张卡片的行 + 卡片下的小字说明（不再用红字）。
            LazyColumn(
                state = dragOrder.list,
                modifier = Modifier.settingsGutterScroll(dragOrder.list)
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                item {
                    SettingsGroupTitle("已启用的输入组合", Modifier.padding(top = 8.dp))
                }

                if (orderedEnabledIds.isEmpty()) {
                    item {
                        Text(
                            text = "暂未启用任何方案",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                itemsIndexed(orderedEnabledIds, key = { _, id -> id }) { row, id ->
                    val isEnglish = id == InputModes.ENGLISH
                    val schema = if (isEnglish) SchemaMeta(id, "英文") else schemaById.getValue(id)
                    SchemaToggleItem(
                        schema = schema,
                        enabled = true,
                        isCompiled = isEnglish || SchemaManager.isSchemaCompiled(context, id),
                        isCurrent = !isEnglish && id == uiState.currentSchema,
                        onToggle = { if (!isEnglish) viewModel.toggleSchema(schema) },
                        onSelect = { if (!isEnglish) viewModel.selectSchema(schema) },
                        isBuiltIn = isEnglish,
                        isDragging = dragOrder.dragging == id,
                        isDropTarget = false,
                        reorderModifier = dragOrderItem(dragOrder, id, orderedEnabledIds.size > 1),
                        groupIndex = row,
                        groupSize = orderedEnabledIds.size,
                    )
                }

                item {
                    SettingsNote("长按可排序；语言和默认组合在「语言与方案」选择。",
                        Modifier.padding(top = 4.dp))
                    SettingsNote("安装或启停其他方案后，点「应用方案更改」生效。")
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 关着的方案：常用的（14 键、双拼）一直列出；其余收在「其他已安装方案（高级）」里，折叠行写明有几个、带展开箭头。
                // 以前按收起后的列表判断，收起时写「没有其他可用方案」，点开却是一长串（用户 10-31）。
                val disabledAll = visibleSchemas.filter { it.schemaId !in uiState.enabledSchemas }
                val listedOff = disabledAll.filter { it.schemaId in com.kingzcheung.xime.settings.CyimeInputDefaults.listedWhenOff }
                val extraOff = disabledAll.filterNot { it.schemaId in com.kingzcheung.xime.settings.CyimeInputDefaults.listedWhenOff }

                itemsIndexed(listedOff, key = { _, schema -> schema.schemaId }) { row, schema ->
                    SchemaToggleItem(
                        schema = schema,
                        enabled = false,
                        isCompiled = SchemaManager.isSchemaCompiled(context, schema.schemaId),
                        isCurrent = false,
                        onToggle = { viewModel.toggleSchema(schema) },
                        onSelect = { viewModel.selectSchema(schema) },
                        groupIndex = row,
                        groupSize = listedOff.size,
                    )
                }

                if (extraOff.isNotEmpty()) item(key = "extra-schemas-fold") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                            .clickable { showExtraSchemas = !showExtraSchemas }
                            .testTag("extra-schemas-fold")
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (showExtraSchemas) "收起其他已安装方案" else "其他已安装方案（高级）",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text("${extraOff.size} 个", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(if (showExtraSchemas) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (showExtraSchemas) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }

                if (disabledAll.isEmpty()) {
                    item {
                        Text(
                            text = "没有其他可用方案",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                if (showExtraSchemas) itemsIndexed(extraOff, key = { _, schema -> schema.schemaId }) { row, schema ->
                    SchemaToggleItem(
                        schema = schema,
                        enabled = false,
                        isCompiled = SchemaManager.isSchemaCompiled(context, schema.schemaId),
                        isCurrent = false,
                        onToggle = { viewModel.toggleSchema(schema) },
                        onSelect = { viewModel.selectSchema(schema) },
                        groupIndex = row,
                        groupSize = extraOff.size,
                    )
                }
                // 导入和从「扩展 › 方案」获取的方案包：卸载在这里；同名文件没确认的包留着，可以再装或删。
                val packages = localUiState.packages.filterNot { it.packageId == "builtin" }
                if (packages.isNotEmpty()) {
                    item { SettingsGroupTitle("导入和下载的方案包", Modifier.padding(top = 8.dp)) }
                    itemsIndexed(packages, key = { _, pkg -> "package:${pkg.packageId}" }) { row, pkg ->
                        SettingsGroupRow(row, packages.size) {
                            SchemaPackageRow(
                                item = pkg,
                                installing = localUiState.installingId == pkg.packageId,
                                onInstall = { localViewModel.installPackage(pkg) },
                                onDelete = { localViewModel.deleteDownloaded(pkg) },
                                onUninstall = { localViewModel.uninstall(pkg) },
                            )
                        }
                    }
                }
                // 直接看、改 Rime 文件只用来排错，放在高级方案后面。
                item {
                    TextButton(onClick = onNavigateToRimeFileBrowser) {
                        Text("Rime 配置文件（高级）")
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            Button(
                onClick = { viewModel.deploySchema() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (uiState.isDeploying) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text("应用方案更改")
            }
        }
    }
}

internal fun orderedInstalledModeIds(context: Context, schemas: List<SchemaMeta>): List<String> =
    InputModes.ordered(context, schemas.map { schema ->
        schema.toSchemaInfo()
    }).map { it.schemaId }

/** Keep inactive modes in their saved slots while reordering the visible cards. */
internal fun reorderEnabledModes(context: Context, schemas: List<SchemaMeta>, enabledIds: Set<String>,
    englishEnabled: Boolean, reordered: List<String>): Boolean {
    val installedIds = schemas.map { it.schemaId }.toSet()
    val visibleIds = schemas.filter {
        com.kingzcheung.xime.settings.CyimeInputDefaults.visibleSchema(it.schemaId, installedIds)
    }.mapTo(mutableSetOf()) { it.schemaId }
    val fullOrder = orderedInstalledModeIds(context, schemas)
    val enabledOrder = fullOrder.filter { id ->
        (id == InputModes.ENGLISH && englishEnabled) || (id in enabledIds && id in visibleIds)
    }
    return InputModes.saveReorderedModes(context, fullOrder, enabledOrder, reordered)
}

@Composable
internal fun SchemaToggleItem(
    schema: SchemaMeta,
    enabled: Boolean,
    isCompiled: Boolean,
    isCurrent: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    isBuiltIn: Boolean = false,
    reorderModifier: Modifier = Modifier,
    isDragging: Boolean = false,
    isDropTarget: Boolean = false,
    /** 在同一张卡片里的第几行、共几行（见 SettingsGroupRow）；单独一行时四角都圆。正拖着的那行单独浮起、四角圆。 */
    groupIndex: Int = 0,
    groupSize: Int = 1,
) {
    val radius = 12.dp
    val top = if (isDragging || groupIndex == 0) radius else 0.dp
    val bottom = if (isDragging || groupIndex >= groupSize - 1) radius else 0.dp
    val groupColor = MaterialTheme.colorScheme.surfaceContainerLow
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("schema-card:${schema.schemaId}")
            .then(reorderModifier)
            .then(if (isBuiltIn) Modifier else Modifier.clickable { if (enabled && isCompiled) onSelect() }),
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging)
                MaterialTheme.colorScheme.secondaryContainer
            else if (isDropTarget)
                MaterialTheme.colorScheme.primaryContainer
            else if (isCurrent)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f).compositeOver(groupColor)
            else
                groupColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 6.dp else 0.dp),
    ) {
        if (groupIndex > 0 && !isDragging) SettingsRowDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = schema.toSchemaInfo().resourceLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline
                    )
                    if (isCurrent && isCompiled) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "当前方案",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                val profile = schema.toSchemaInfo().profile
                Text("${profile.language.displayName} · ${profile.summary}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                com.kingzcheung.xime.settings.CyimeInputDefaults.description(schema.schemaId)?.let { description ->
                    Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isBuiltIn) {
                    Text("内置英文模式 · 始终开启", style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.outline)
                } else Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (schema.version.isNotEmpty()) {
                        Text(
                            text = "${schema.version}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (schema.author.isNotEmpty()) {
                        Text(
                            text = schema.author,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (isCompiled) {
                        Text(
                            text = "已编译",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Switch(
                checked = enabled,
                enabled = !isBuiltIn,
                onCheckedChange = { if (!isBuiltIn) onToggle() },
                colors = settingsSwitchColors(),
                modifier = Modifier.testTag("schema-toggle:${schema.schemaId}")
            )
        }
    }
}

/** 一个方案包一行：名字、状态，右边是卸载，或没装上时的安装和删除。 */
@Composable
private fun SchemaPackageRow(
    item: LocalPackageItem,
    installing: Boolean,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
    onUninstall: () -> Unit,
) {
    val detail = buildString {
        append(if (item.installed) "已安装" else "未安装")
        if (item.version.isNotEmpty()) append(" · ${item.version}")
        if (item.fileCount > 0) append(" · ${item.fileCount} 个文件")
    }
    SettingsListRow(item.displayName, detail) {
        when {
            installing -> CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
            item.installed -> TextButton(onClick = onUninstall) { Text("卸载") }
            else -> {
                TextButton(onClick = onInstall) { Text("安装") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除下载文件", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
