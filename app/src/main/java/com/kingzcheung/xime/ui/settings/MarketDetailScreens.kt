@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.kingzcheung.xime.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import com.kingzcheung.xime.ui.common.CompactDropdownMenu
import com.kingzcheung.xime.ui.common.CompactMenuRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.model.ModelCategory
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelInfo
import com.kingzcheung.xime.model.ModelVersion
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.MarketScheme
import com.kingzcheung.xime.settings.MarketSchemeItem
import com.kingzcheung.xime.settings.SchemeVersion
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.viewmodel.ModelManagementUiState
import com.kingzcheung.xime.viewmodel.ModelManagementViewModel
import com.kingzcheung.xime.viewmodel.ModelItemState
import com.kingzcheung.xime.viewmodel.SchemaMarketUiState
import com.kingzcheung.xime.viewmodel.SchemaMarketViewModel


@Composable
fun MarketSchemeDetailContent(
    schemeId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SchemaMarketViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val item = uiState.schemes.firstOrNull { it.scheme.id == schemeId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("方案详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        when {
            uiState.isLoading && item == null -> MarketCenterBox {
                CircularProgressIndicator()
            }

            item == null -> MarketCenterBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "未找到该方案",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.loadSchemes() }) { Text("重新加载") }
                }
            }

            else -> SchemeDetailBody(
                item = item,
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SchemeDetailBody(
    item: MarketSchemeItem,
    uiState: SchemaMarketUiState,
    viewModel: SchemaMarketViewModel,
    modifier: Modifier = Modifier,
) {
    val scheme = item.scheme
    val (iconContainer, iconContent) = schemeCategoryColors(scheme.tags.firstOrNull())
    val selectedVersion = uiState.selectedVersions[scheme.id] ?: scheme.currentVersion
    val selectedVersionObj = scheme.versions.firstOrNull { it.version == selectedVersion }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.settingsGutterScroll(listState).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(iconContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        schemeCategoryIcon(scheme.tags.firstOrNull()),
                        contentDescription = null,
                        tint = iconContent,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        scheme.name.ifEmpty { scheme.id },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (scheme.author.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            scheme.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (scheme.description.isNotEmpty()) {
            item {
                Text(
                    scheme.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (scheme.tags.isNotEmpty()) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    scheme.tags.forEach { tag -> MarketTag(tag) }
                }
            }
        }

        if (scheme.dependencies.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "依赖",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        scheme.dependencies.forEach { dep -> MarketTag(dep) }
                    }
                }
            }
        }

        if (scheme.warning.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                ) {
                    Text(
                        scheme.warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        item {
            Text(
                "版本历史",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (scheme.versions.isEmpty()) {
            item {
                Text(
                    "暂无版本信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            items(scheme.versions, key = { it.version }) { v ->
                SchemeVersionCard(
                    version = v,
                    selected = v.version == selectedVersion,
                    onClick = { viewModel.selectVersion(scheme.id, v.version) },
                    trailing = {
                        SchemeVersionDownloadButton(
                            item = item,
                            uiState = uiState,
                            version = v.version,
                            onDownload = { viewModel.downloadScheme(item, v.version) },
                        )
                    },
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (scheme.appVersion.isNotEmpty()) {
                    DetailMetaRow("APP 版本要求", scheme.appVersion)
                }
                if (scheme.license.isNotEmpty()) {
                    DetailMetaRow("许可证", scheme.license)
                }
                if (scheme.homepage.isNotEmpty()) {
                    DetailMetaRow("主页", scheme.homepage)
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 版本卡片内嵌下载按钮（Play Store 风格）。 */
@Composable
internal fun SchemeVersionDownloadButton(
    item: MarketSchemeItem,
    uiState: SchemaMarketUiState,
    version: String,
    onDownload: () -> Unit,
) {
    val scheme = item.scheme
    when {
        uiState.downloadingId == scheme.id -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${(uiState.downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        !item.compatible -> OutlinedButton(
            onClick = {},
            enabled = false,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text("需 App ≥ ${item.minAppVersion}", style = MaterialTheme.typography.labelSmall)
        }

        scheme.id in uiState.downloadedIds -> OutlinedButton(
            onClick = onDownload,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text("重新下载", style = MaterialTheme.typography.labelSmall)
        }

        else -> Button(
            onClick = onDownload,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Text("下载", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun SchemeVersionCard(
    version: SchemeVersion,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${version.version}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "当前版本",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (version.date.isNotEmpty()) {
                Text(
                    version.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (version.changelog.isNotEmpty()) {
                Text(
                    version.changelog,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val totalBytes = version.downloadUrls.sumOf { dl ->
                dl.size?.removeSuffix(" MB")?.trim()?.toDoubleOrNull()
                    ?.let { (it * 1024.0 * 1024.0).toLong() } ?: 0L
            }
            if (totalBytes > 0) {
                Text(
                    "${"%.1f".format(totalBytes / (1024.0 * 1024.0))} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/* ------------------------------- 模型详情页 ------------------------------- */

/** 模型详情页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketModelDetailContent(
    modelId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: ModelManagementViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val item = uiState.models.firstOrNull { it.model.id == modelId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        when {
            uiState.isLoading && item == null -> MarketCenterBox {
                CircularProgressIndicator()
            }

            item == null -> MarketCenterBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "未找到该模型",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.refresh() }) { Text("重新加载") }
                }
            }

            else -> ModelDetailBody(
                item = item,
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
internal fun ModelDetailBody(
    item: ModelItemState,
    uiState: ModelManagementUiState,
    viewModel: ModelManagementViewModel,
    modifier: Modifier = Modifier,
) {
    val model = item.model
    val (iconContainer, iconContent) = modelCategoryColors(model.category)
    val selectedVersion = uiState.selectedVersions[model.id]
        ?: model.downloadableVersions.firstOrNull()?.version.orEmpty()
    val selectedVersionObj = model.downloadableVersions.firstOrNull { it.version == selectedVersion }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.settingsGutterScroll(listState).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(iconContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        modelCategoryIcon(model.category),
                        contentDescription = null,
                        tint = iconContent,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        modelCategoryLabel(model.category),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (model.description.isNotEmpty()) {
            item {
                Text(
                    model.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "使用本地模型是有代价的，它可能会占用内存和 CPU，导致手机发热，请斟酌使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        if (selectedVersionObj != null && selectedVersionObj.size.isNotEmpty()) {
            item {
                DetailMetaRow("大小", selectedVersionObj.size)
            }
        } else if (model.size.isNotEmpty()) {
            item {
                DetailMetaRow("大小", model.size)
            }
        }

        item {
            Text(
                "版本历史",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        (item.downloadState as? ModelDownloadState.Error)?.let { failure ->
            item {
                Text(
                    failure.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (model.downloadableVersions.isEmpty()) {
            item {
                Text(
                    "暂无版本信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            items(model.downloadableVersions, key = { it.version }) { v ->
                ModelVersionCard(
                    version = v,
                    selected = v.version == selectedVersion,
                    onClick = { viewModel.selectVersion(model.id, v.version) },
                    trailing = {
                        ModelVersionDownloadButton(
                            item = item,
                            version = v,
                            onDownload = { viewModel.downloadModel(model.id, v) },
                        )
                    },
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 模型版本卡片内嵌下载按钮（Play Store 风格）。 */
@Composable
internal fun ModelVersionDownloadButton(
    item: ModelItemState,
    version: ModelVersion,
    onDownload: () -> Unit,
) {
    val downloadState = item.downloadState
    when {
        downloadState is ModelDownloadState.Downloading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${(downloadState.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        downloadState is ModelDownloadState.Error -> {
            TextButton(onClick = onDownload) {
                Text("重试", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }

        item.isDownloaded -> Text(
            "已安装",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        else -> Button(
            onClick = onDownload,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Text("下载", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun ModelVersionCard(
    version: ModelVersion,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${version.version}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "当前版本",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (version.date.isNotEmpty()) {
                Text(
                    version.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (version.changelog.isNotEmpty()) {
                Text(
                    version.changelog,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (version.size.isNotEmpty()) {
                Text(
                    version.size,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/* ------------------------------- 图标映射 ------------------------------- */

internal fun schemeCategoryIcon(tag: String?): ImageVector = Icons.Default.AutoAwesome

/** 方案分类色块：统一用 primaryContainer，随主题变化。 */
@Composable
internal fun schemeCategoryColors(tag: String?): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return scheme.primaryContainer to scheme.onPrimaryContainer
}

/** 模型分类色块：按类别用 M3 分层容器色。 */
@Composable
internal fun modelCategoryColors(category: ModelCategory): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (category) {
        ModelCategory.CANDIDATE -> scheme.primaryContainer to scheme.onPrimaryContainer
        ModelCategory.PREDICTION ->
            scheme.primaryContainer to scheme.onPrimaryContainer
        ModelCategory.HANDWRITING ->
            scheme.secondaryContainer to scheme.onSecondaryContainer
        ModelCategory.ASR, ModelCategory.SPEAKER ->
            scheme.tertiaryContainer to scheme.onTertiaryContainer
        ModelCategory.OTHER ->
            scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
}

internal fun modelCategoryIcon(category: ModelCategory): ImageVector = when (category) {
    ModelCategory.CANDIDATE -> Icons.Default.AutoAwesome
    ModelCategory.PREDICTION -> Icons.Default.AutoAwesome
    ModelCategory.ASR, ModelCategory.SPEAKER -> Icons.Default.GraphicEq
    ModelCategory.HANDWRITING -> Icons.Outlined.Gesture
    ModelCategory.OTHER -> Icons.Default.AutoAwesome
}

/** 插件分类图标（pluginType 用 PluginCategory 的 id：emoji/speech/prediction）。 */
internal fun pluginCategoryIcon(pluginType: String): ImageVector = when (pluginType) {
    "emoji" -> Icons.Default.SentimentSatisfiedAlt
    "speech" -> Icons.Default.GraphicEq
    "prediction" -> Icons.Default.AutoAwesome
    "clipboard_sync" -> Icons.Default.Sync
    "tool" -> Icons.Default.AutoFixHigh
    else -> Icons.Default.Extension
}

/** 插件分类色块。 */
@Composable
internal fun pluginCategoryColors(pluginType: String): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (pluginType) {
        "emoji" -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        "speech" -> scheme.primaryContainer to scheme.onPrimaryContainer
        "prediction" -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
}

internal fun pluginCategoryLabel(pluginType: String): String = when (pluginType) {
    "emoji" -> "表情"
    "speech" -> "语音转文本"
    "prediction" -> "智能预测"
    "clipboard_sync" -> "剪贴板同步"
    "tool" -> "工具"
    else -> "其他"
}

internal fun modelCategoryLabel(category: ModelCategory): String = when (category) {
    ModelCategory.CANDIDATE -> "候选增强"
    ModelCategory.PREDICTION -> "联想"
    ModelCategory.ASR -> "语音"
    ModelCategory.HANDWRITING -> "手写"
    ModelCategory.SPEAKER -> "声纹"
    ModelCategory.OTHER -> "其他"
}

internal fun modelFormatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }
}

/* ---------------------------- 插件详情页 ---------------------------- */

/** 已安装插件的详情：基本信息与网络权限。插件不再有商店，只从文件或浏览器导入。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailContent(
    pluginId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val plugin = remember(pluginId) { PluginManager.getAllInstallPlugins().find { it.id == pluginId } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        if (plugin == null) {
            MarketCenterBox {
                Text(
                    "未找到该插件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        val pluginType = plugin.category.id
        val (iconContainer, iconContent) = pluginCategoryColors(pluginType)
        val networkHosts = remember(plugin.id) {
            (plugin.declaredHosts +
                ExtensionManager.getConfiguredNetworkHosts(context, plugin.id) +
                SettingsPreferences.getPluginPendingHosts(context, plugin.id)).distinct()
        }
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.settingsGutterScroll(listState).fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(iconContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            pluginCategoryIcon(pluginType),
                            contentDescription = null,
                            tint = iconContent,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            plugin.name.ifEmpty { plugin.id },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            pluginCategoryLabel(pluginType).ifEmpty { "插件" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (plugin.description.isNotEmpty()) {
                item {
                    Text(
                        plugin.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (plugin.versionName.isNotEmpty()) {
                item { DetailMetaRow("版本", plugin.versionName) }
            }
            if (networkHosts.isNotEmpty()) {
                item {
                    Text(
                        "网络权限",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            NetworkAccessSection(
                                pluginId = plugin.id,
                                pluginName = plugin.name.ifEmpty { plugin.id },
                                hosts = networkHosts,
                            )
                        }
                    }
                }
            }
        }
    }
}
