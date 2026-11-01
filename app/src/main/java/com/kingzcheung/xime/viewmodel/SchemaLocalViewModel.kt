package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.settings.FileConflictInfo
import com.kingzcheung.xime.settings.ImportManager
import com.kingzcheung.xime.settings.SchemaManifestManager
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SchemaManager.InstallFromDirResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LocalPackageItem(
    val packageId: String,
    val displayName: String,
    val version: String,
    val downloaded: Boolean,
    val installed: Boolean,
    val schemaCount: Int = 0,
    val fileCount: Int = 0,
) {
    val isImport: Boolean get() = packageId.startsWith("import_")
    val statusLabel: String
        get() = when {
            installed && downloaded -> "已安装"
            installed -> "已安装"
            downloaded -> "已下载"
            else -> ""
        }
}

data class SchemaLocalUiState(
    val packages: List<LocalPackageItem> = emptyList(),
    val isLoading: Boolean = false,
    val installingId: String? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val conflictPackageId: String? = null,
    val conflictingSchemeIds: List<String> = emptyList(),
    val conflictingFiles: List<FileConflictInfo> = emptyList(),
)

class SchemaLocalViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(SchemaLocalUiState())
    val uiState: StateFlow<SchemaLocalUiState> = _uiState.asStateFlow()

    init {
        loadLocalPackages()
    }

    fun loadLocalPackages() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val installedPkgs = withContext(Dispatchers.IO) {
                SchemaManifestManager.getInstalledPackages(context)
            }
            // 已下载未安装的包没有清单，用里面的压缩包名当显示名，不显示 import_日期 这种目录名。
            val downloaded = withContext(Dispatchers.IO) {
                val marketDir = SchemaManager.getMarketDir(context)
                if (!marketDir.exists()) emptyMap()
                else marketDir.listFiles()?.mapNotNull { sub ->
                    val archive = sub.listFiles()?.firstOrNull { it.isFile } ?: return@mapNotNull null
                    sub.name to if (sub.name.startsWith("import_")) ImportManager.archiveTitle(archive.name) else sub.name
                }?.toMap() ?: emptyMap()
            }
            val downloadedIds = downloaded.keys
            val installedMap = installedPkgs.associateBy { it.packageId }
            val allIds = (downloadedIds + installedMap.keys).sorted()
            val packages = allIds.map { id ->
                val info = installedMap[id]
                LocalPackageItem(
                    packageId = id,
                    displayName = if (id == "builtin") "Lime 内置资源" else info?.displayName ?: downloaded[id] ?: id,
                    version = info?.version ?: "",
                    downloaded = id in downloadedIds,
                    installed = info != null,
                    schemaCount = info?.schemaCount ?: 0,
                    fileCount = info?.fileCount ?: 0,
                )
            }
            _uiState.update { it.copy(packages = packages, isLoading = false) }
        }
    }

    fun installPackage(item: LocalPackageItem) = install(item, replaceConflictingFiles = false)

    /** 刚导入、因同名文件没装上的包：接着走一遍安装，冲突时弹「更新共用文件」确认。 */
    fun installImported(packageId: String, displayName: String) = installPackage(
        LocalPackageItem(packageId, displayName.ifBlank { packageId }, "", downloaded = true, installed = false))

    private var conflictItem: LocalPackageItem? = null

    private fun install(item: LocalPackageItem, replaceConflictingFiles: Boolean) {
        if (_uiState.value.installingId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(installingId = item.packageId, conflictPackageId = null) }
            val result = try {
                SchemaManager.installPackageFromMarketDir(
                    context = context, packageId = item.packageId,
                    displayName = item.displayName, version = item.version,
                    fromMarket = !item.isImport, replaceConflictingFiles = replaceConflictingFiles,
                )
            } catch (e: Exception) {
                android.util.Log.e("SchemaLocalViewModel", "installPackage exception", e)
                InstallFromDirResult(false, failureReason = "安装异常: ${e.message}")
            }
            _uiState.update { it.copy(installingId = null, conflictingFiles = emptyList(), conflictingSchemeIds = emptyList()) }
            when {
                result.success -> showToast(buildString {
                    append("已安装「${item.displayName}」，点「应用方案更改」生效")
                    if (result.parseFailures.isNotEmpty()) append("\n" + result.parseFailures.joinToString("\n"))
                })
                result.conflicts.isNotEmpty() -> {
                    conflictItem = item
                    _uiState.update {
                        it.copy(conflictPackageId = item.packageId, conflictingFiles = result.conflicts,
                            conflictingSchemeIds = result.conflicts.flatMap { conflict -> conflict.claimedBy }.distinct())
                    }
                }
                else -> showToast(result.failureReason ?: "安装失败")
            }
            loadLocalPackages()
        }
    }

    fun confirmInstallWithReplace() {
        val item = conflictItem?.takeIf { it.packageId == _uiState.value.conflictPackageId } ?: return
        install(item, replaceConflictingFiles = true)
    }

    fun cancelConflictInstall() {
        _uiState.update { it.copy(conflictPackageId = null, conflictingSchemeIds = emptyList(), conflictingFiles = emptyList()) }
    }

    fun deleteDownloaded(item: LocalPackageItem) {
        if (item.packageId == "builtin") { showToast("内置方案不可删除"); return }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val dir = SchemaManager.getMarketDir(context, item.packageId)
                if (dir.exists()) { dir.deleteRecursively(); true } else false
            }
            showToast(if (ok) "已删除" else "删除失败")
            loadLocalPackages()
        }
    }

    fun uninstall(item: LocalPackageItem) {
        if (item.packageId == "builtin") { showToast("内置方案不可卸载"); return }
        viewModelScope.launch {
            val result = SchemaManager.uninstallPackage(context, item.packageId)
            showToast(result.message)
            loadLocalPackages()
        }
    }

    fun clearToast() = _uiState.update { it.copy(toastMessage = null) }

    private fun showToast(message: String) = _uiState.update { it.copy(toastMessage = message) }
}
