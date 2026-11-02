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

/**
 * 扩展：方案、模型、插件的统一入口，三页左右滑动。
 * 方案、模型页是商店（二级分类 chips 过滤、商店卡片、下拉刷新）；插件页只列已安装的插件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketHubContent(
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateToModelDetail: (String) -> Unit = {},
    onNavigateToPluginDetail: (String) -> Unit = {},
    onNavigateToPluginSettings: (String) -> Unit = {},
    onNavigateToSpeechToText: () -> Unit = {},
    onNavigateToClipboardSync: () -> Unit = {},
    onNavigateToLocal: () -> Unit = {},
    onNavigateToModelLocal: () -> Unit = {},
    initialTab: Int = 0,
) {
    // 三页可左右滑动切换，标签跟随；分页状态随返回栈保存，进详情再返回仍回到刚才那页。
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = initialTab) { 3 }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val tabIndex = pagerState.currentPage

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扩展") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 扩展是方案、模型、插件的唯一入口。方案、模型页右上角进已安装管理；
                    // 插件没有商店，插件页本身就是已安装列表，右上角是刷新和从文件/浏览器导入。
                    if (tabIndex == 2) {
                        InstalledPluginActions()
                    } else {
                        TextButton(onClick = if (tabIndex == 0) onNavigateToLocal else onNavigateToModelLocal) {
                            Text("已安装", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryTabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text("方案") },
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text("模型") },
                )
                Tab(
                    selected = tabIndex == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    text = { Text("插件") },
                )
            }
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().testTag("market-pages"),
                beyondViewportPageCount = 0,
            ) { page ->
                when (page) {
                    0 -> SchemesMarketTab(
                        onNavigateToDetail = onNavigateToDetail,
                    )
                    1 -> ModelsMarketTab(
                        onNavigateToDetail = onNavigateToModelDetail,
                    )
                    else -> InstalledPluginsList(
                        onNavigateToPluginSettings = onNavigateToPluginSettings,
                        onNavigateToPluginDetail = onNavigateToPluginDetail,
                        onNavigateToSpeechToText = onNavigateToSpeechToText,
                        onNavigateToClipboardSync = onNavigateToClipboardSync,
                    )
                }
            }
        }
    }
}

/* ------------------------------- 方案 Tab ------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SchemesMarketTab(
    onNavigateToDetail: (String) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SchemaMarketViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) isRefreshing = false
    }
    if (uiState.scriptApprovalId != null) {
        ImportApprovalDialog(
            title = "安装方案脚本",
            body = "这个方案包含输入法脚本：\n${uiState.scriptPaths.take(8).joinToString("\n")}\n脚本在输入法进程里运行，可以读写文件。确认后才会安装。",
            confirmLabel = "安装脚本",
            onConfirm = { viewModel.confirmMarketScripts() },
            onDismiss = { viewModel.cancelMarketScripts() },
        )
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            viewModel.loadSchemes()
        },
        state = pullRefreshState,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (uiState.availableTags.isNotEmpty()) {
                CategoryChips(
                    categories = uiState.availableTags,
                    selected = uiState.selectedTag,
                    onSelect = { viewModel.selectTag(if (it == uiState.selectedTag) null else it) },
                )
            }

            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.settingsGutterScroll(listState).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    uiState.isLoading && uiState.schemes.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(12.dp))
                                    Text("正在加载方案市场…", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        )
                    }

                    uiState.errorMessage != null && uiState.schemes.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        uiState.errorMessage ?: "加载失败",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = { viewModel.loadSchemes() }) { Text("重试") }
                                }
                            }
                        )
                    }

                    uiState.filteredSchemes.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Text("没有匹配的方案", style = MaterialTheme.typography.bodyMedium)
                            }
                        )
                    }

                    else -> {
                        // 同一页上面是已安装、下面是可安装；哪段为空就不显示。
                        val (installedGroup, availableGroup) = uiState.filteredSchemes.partition { it.installedVersion != null }
                        listOf("已安装" to installedGroup, "可安装" to availableGroup).forEach { (sectionLabel, group) ->
                            if (group.isNotEmpty()) {
                                item(key = "section:$sectionLabel") { MarketSectionLabel(sectionLabel) }
                                items(group, key = { it.scheme.id }) { item ->
                            val (iconContainer, iconContent) = schemeCategoryColors(item.scheme.tags.firstOrNull())
                            MarketStoreCard(
                                icon = schemeCategoryIcon(item.scheme.tags.firstOrNull()),
                                iconContainerColor = iconContainer,
                                iconContentColor = iconContent,
                                title = item.scheme.name.ifEmpty { item.scheme.id },
                                subtitle = item.scheme.description,
                                metaLine = buildString {
                                    if (item.scheme.author.isNotEmpty()) append("作者：${item.scheme.author}")
                                    val totalBytes = item.scheme.versions
                                        .firstOrNull { it.version == uiState.selectedVersions[item.scheme.id] }
                                        ?.downloadUrls?.sumOf { dl ->
                                            dl.size?.removeSuffix(" MB")?.trim()?.toDoubleOrNull()
                                                ?.let { (it * 1024.0 * 1024.0).toLong() } ?: 0L
                                        } ?: 0L
                                    if (totalBytes > 0) {
                                        if (item.scheme.author.isNotEmpty()) append("  ·  ")
                                        append("${"%.1f".format(totalBytes / (1024.0 * 1024.0))} MB")
                                    }
                                },
                                versions = item.scheme.versions.map { it.version },
                                selectedVersion = uiState.selectedVersions[item.scheme.id]
                                    ?: item.scheme.currentVersion,
                                onSelectVersion = { viewModel.selectVersion(item.scheme.id, it) },
                                onCardClick = { onNavigateToDetail(item.scheme.id) },
                                trailing = {
                                    SchemeTrailingButton(
                                        item = item,
                                        uiState = uiState,
                                        onDownload = { viewModel.downloadScheme(item) },
                                        onUpdate = { viewModel.updateScheme(item) },
                                    )
                                },
                            )
                        }
                            }
                        }
                        item {
                            Spacer(Modifier.height(8.dp))
                            val updatedAt = uiState.updatedAt.takeIf { it.isNotBlank() }
                            val sourceLabel = if (uiState.source.isNotBlank()) "来源：${uiState.source}" else "来自 Xime 官方源"
                            val footerText = buildString {
                                append("共 ${uiState.filteredSchemes.size} 个方案（$sourceLabel")
                                if (updatedAt != null) append(" · 更新于 $updatedAt")
                                append("）")
                            }
                            Text(
                                footerText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SchemeTrailingButton(
    item: MarketSchemeItem,
    uiState: SchemaMarketUiState,
    onDownload: () -> Unit,
    onUpdate: () -> Unit = onDownload,
) {
    val scheme = item.scheme
    when {
        uiState.downloadingId == scheme.id -> {
            OutlinedButton(onClick = onDownload, enabled = false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("${(uiState.downloadProgress * 100).toInt()}%")
                }
            }
        }

        !item.compatible -> OutlinedButton(onClick = {}, enabled = false) {
            Text("需 App ≥ ${item.minAppVersion}")
        }

        scheme.id in uiState.downloadedIds && item.hasUpdate -> Button(
            onClick = onUpdate,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("更新")
        }

        scheme.id in uiState.downloadedIds -> {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "已安装",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                item.installedVersion?.let { version ->
                    Text(
                        "$version",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        else -> Button(
            onClick = onDownload,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("获取")
        }
    }
}

/* ------------------------------- 模型 Tab ------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelsMarketTab(
    onNavigateToDetail: (String) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: ModelManagementViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

    // 从本地模型管理页删除后返回时，重新检查本地下载状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDownloadedState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) isRefreshing = false
    }

    val categories = listOf(
        null to "全部",
        ModelCategory.CANDIDATE to "候选增强",
        ModelCategory.PREDICTION to "联想",
        ModelCategory.HANDWRITING to "手写",
        ModelCategory.ASR to "语音",
        ModelCategory.SPEAKER to "声纹",
        ModelCategory.OTHER to "其他",
    )

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            viewModel.refresh()
        },
        state = pullRefreshState,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CategoryChips(
                categories = categories.map { it.second },
                selected = uiState.selectedCategory?.let { c ->
                    categories.firstOrNull { it.first == c }?.second
                },
                onSelect = { label ->
                    val target = categories.firstOrNull { it.second == label }?.first
                    viewModel.selectCategory(if (target == uiState.selectedCategory) null else target)
                },
            )

            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.settingsGutterScroll(listState).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    uiState.isLoading && uiState.models.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(12.dp))
                                    Text("正在加载模型市场…", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        )
                    }

                    uiState.filteredModels.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Text("暂无模型", style = MaterialTheme.typography.bodyMedium)
                            }
                        )
                    }

                    else -> {
                        // 同一页上面是已安装、下面是可安装；哪段为空就不显示。
                        val (installedGroup, availableGroup) = uiState.filteredModels.partition { it.isDownloaded }
                        listOf("已安装" to installedGroup, "可安装" to availableGroup).forEach { (sectionLabel, group) ->
                            if (group.isNotEmpty()) {
                                item(key = "section:$sectionLabel") { MarketSectionLabel(sectionLabel) }
                                items(group, key = { it.model.id }) { item ->
                            val (iconContainer, iconContent) = modelCategoryColors(item.model.category)
                            MarketStoreCard(
                                icon = modelCategoryIcon(item.model.category),
                                iconContainerColor = iconContainer,
                                iconContentColor = iconContent,
                                title = item.model.name,
                                subtitle = item.model.description.ifEmpty { item.model.id },
                                metaLine = buildString {
                                    append(modelCategoryLabel(item.model.category))
                                    val sizeLabel = if (item.isDownloaded && item.diskSize > 0) {
                                        modelFormatSize(item.diskSize)
                                    } else {
                                        item.model.size.ifEmpty { null }?.let { "约 $it" }
                                    }
                                    if (sizeLabel != null) {
                                        append("  ·  $sizeLabel")
                                    }
                                },
                                versions = item.model.downloadableVersions.map { it.version },
                                selectedVersion = uiState.selectedVersions[item.model.id]
                                    ?: item.model.downloadableVersions.firstOrNull()?.version.orEmpty(),
                                onSelectVersion = { viewModel.selectVersion(item.model.id, it) },
                                onCardClick = { onNavigateToDetail(item.model.id) },
                                trailing = {
                                    ModelTrailingButton(
                                        item = item,
                                        onDownload = { viewModel.downloadModel(item.model.id) },
                                    )
                                },
                            )
                        }
                            }
                        }
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "共 ${uiState.filteredModels.size} 个模型 · 模型为本地推理，耗电较高请斟酌使用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelTrailingButton(
    item: ModelItemState,
    onDownload: () -> Unit,
) {
    val downloadState = item.downloadState
    when {
        downloadState is ModelDownloadState.Downloading -> {
            Column(
                modifier = Modifier.width(96.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    "${(downloadState.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { downloadState.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                )
            }
        }
        downloadState is ModelDownloadState.Error -> {
            TextButton(onClick = onDownload) {
                Text("下载失败，重试", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        item.isDownloaded && item.hasUpdate -> {
            Column(horizontalAlignment = Alignment.End) {
                Button(
                    onClick = onDownload,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("更新")
                }
                Text(
                    item.installedVersion.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item.isDownloaded -> Text(
            "已安装",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        else -> Button(
            onClick = onDownload,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("获取")
        }
    }
}

/* ------------------------- 通用商店卡片 & 组件 ------------------------- */

/**
 * 商店式卡片（M3）：surfaceContainerLow 分层 + 大圆角；图标块用容器色；
 * 标题/描述 + 右侧操作区；底部 meta 行 + 版本选择。
 */
@Composable
internal fun MarketStoreCard(
    icon: ImageVector,
    iconContainerColor: Color,
    iconContentColor: Color,
    title: String,
    subtitle: String,
    metaLine: String,
    versions: List<String>,
    selectedVersion: String,
    onSelectVersion: (String) -> Unit,
    onCardClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCardClick() }
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconContainerColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconContentColor,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                trailing()
            }

            if (metaLine.isNotEmpty() || versions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (metaLine.isNotEmpty()) {
                        Text(
                            metaLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (versions.isNotEmpty()) {
                        MarketVersionSelector(
                            versions = versions,
                            selectedVersion = selectedVersion,
                            onSelectVersion = onSelectVersion,
                        )
                    }
                }
            }
        }
    }
}

/** 版本选择：单版本直接展示文本，多版本提供下拉。 */
@Composable
internal fun MarketVersionSelector(
    versions: List<String>,
    selectedVersion: String,
    onSelectVersion: (String) -> Unit,
) {
    if (versions.size <= 1) {
        Text(
            "${versions.firstOrNull().orEmpty()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            Text(
                "版本 ${selectedVersion.ifEmpty { versions.first() }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = "选择版本",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        CompactDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            versions.forEach { v ->
                CompactMenuRow(
                    label = "$v", selected = v == selectedVersion.ifEmpty { versions.first() },
                    onClick = {
                        onSelectVersion(v)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 横向滚动的分类 FilterChip 行（二级分类导航）。 */
@Composable
internal fun CategoryChips(
    categories: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(categories) { _, category ->
            FilterChip(
                selected = category == selected,
                onClick = { onSelect(category) },
                label = { Text(category, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

/** 居中容器：空态/加载/错误占位。 */
/** 扩展各页里「已安装」「可安装」两段的小标题。 */
@Composable
internal fun MarketSectionLabel(label: String) {
    Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp).testTag("market-section:$label"))
}

@Composable
internal fun MarketCenterBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    }
}

/** 详情页标签 chip。 */
@Composable
internal fun MarketTag(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 详情页元信息行（key: value）。 */
@Composable
internal fun DetailMetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
