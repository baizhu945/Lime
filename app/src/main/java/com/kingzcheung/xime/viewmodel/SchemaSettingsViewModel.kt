package com.kingzcheung.xime.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.ImportManager
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.settings.SchemaMeta
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SchemaUiState(
    val allSchemas: List<SchemaMeta> = emptyList(),
    val enabledSchemas: List<String> = emptyList(),
    val currentSchema: String = "t9_pinyin",
    val isDeploying: Boolean = false,
    val toastMessage: String? = null,
    /** 部署没过或有方案没编译出来时的说明，页面弹窗显示（Toast 在 Android 12 以后最多两行，放不下方案名和原因）。 */
    val deployReport: String? = null,
)

class SchemaSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(SchemaUiState())
    val uiState: StateFlow<SchemaUiState> = _uiState.asStateFlow()

    /** 每次导入完成发一次；方案包有同名文件待确认时带上它，页面接着问用户。 */
    private val _importCompleted = MutableSharedFlow<ImportManager.ImportResult.Content?>(extraBufferCapacity = 1)
    val importCompleted: SharedFlow<ImportManager.ImportResult.Content?> = _importCompleted.asSharedFlow()
    private val _scriptPrompt = MutableSharedFlow<ImportManager.ImportResult.NeedsScriptConfirmation>(extraBufferCapacity = 1)
    val scriptPrompt: SharedFlow<ImportManager.ImportResult.NeedsScriptConfirmation> = _scriptPrompt.asSharedFlow()
    private val _pluginPrompt = MutableSharedFlow<ImportManager.ImportResult.NeedsPluginConfirmation>(extraBufferCapacity = 1)
    val pluginPrompt: SharedFlow<ImportManager.ImportResult.NeedsPluginConfirmation> = _pluginPrompt.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        // 在 IO 线程读盘（discoverSchemas/getEnabledSchemas 扫描文件），避免 ON_RESUME 在主线程卡顿
        viewModelScope.launch {
            val (allSchemas, enabledSchemas, currentSchema) = withContext(Dispatchers.IO) {
                if (!KeysConfigHelper.hasLoadedLayoutBindings) KeysConfigHelper.loadConfig(context)
                Triple(
                    SchemaManager.discoverSchemas(context),
                    SchemaManager.getEnabledSchemas(context),
                    SettingsPreferences.getCurrentSchema(context),
                )
            }
            // 组合配置（如 14 键小鹤双拼）跟随方案与布局自动启用，不单列。
            val sorted = allSchemas.filterNot { com.kingzcheung.xime.settings.CyimeInputDefaults.isCompanion(it.schemaId) }
                .sortedByDescending { it.schemaId in enabledSchemas }
            _uiState.update {
                it.copy(
                    allSchemas = sorted,
                    enabledSchemas = enabledSchemas,
                    currentSchema = currentSchema
                )
            }
        }
    }

    fun toggleSchema(schema: SchemaMeta) {
        val enabled = _uiState.value.enabledSchemas.toMutableList()
        if (schema.schemaId in enabled) {
            if (enabled.size <= 1) {
                showToast("至少启用一个方案")
                return
            }
            enabled.remove(schema.schemaId)
        } else {
            enabled.add(schema.schemaId)
        }
        val turnedOn = schema.schemaId in enabled
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SchemaManager.setEnabledSchemas(context, enabled)
                if (turnedOn) com.kingzcheung.xime.settings.LanguagePreferences.enableForSchema(context, schema.schemaId)
            }
            _uiState.update { it.copy(enabledSchemas = enabled) }
        }
    }

    fun selectSchema(schema: SchemaMeta) {
        if (_uiState.value.currentSchema == schema.schemaId) {
            com.kingzcheung.xime.settings.InputModes.selectProfile(context, schema.toSchemaInfo())
            return
        }
        val previous = _uiState.value.currentSchema
        SettingsPreferences.setCurrentSchema(context, schema.schemaId)
        _uiState.update { it.copy(currentSchema = schema.schemaId) }
        if (RimeEngine.isInitialized()) {
            val available = RimeEngine.getInstance().getAvailableSchemas()
            if (schema.schemaId in available) {
                // 部署/编译进行中 switchSchema 不阻塞返回 false，避免主线程等待
                val switched = RimeEngine.getInstance().switchSchema(schema.schemaId)
                if (switched) {
                    com.kingzcheung.xime.settings.InputModes.selectProfile(context, schema.toSchemaInfo())
                    showToast("已切换到${schema.name}")
                } else {
                    rollbackSchemaSelection(previous)
                    showToast("词库部署中，请稍后再切换方案")
                }
            } else {
                rollbackSchemaSelection(previous)
                showToast("请先点「应用方案更改」")
            }
        } else {
            com.kingzcheung.xime.settings.InputModes.selectProfile(context, schema.toSchemaInfo())
        }
    }

    /**
     * 切换失败时回滚偏好与 UI 状态。不回滚有两个后果：再次点击会被
     * [selectSchema] 开头的同值短路拦住（用户永远无法重试）；偏好与
     * 引擎实际方案脱节后，IME 重启的方案恢复逻辑会拿到偏差好。
     */
    private fun rollbackSchemaSelection(previous: String) {
        SettingsPreferences.setCurrentSchema(context, previous)
        _uiState.update { it.copy(currentSchema = previous) }
    }

    fun importSchemaFile(uri: Uri) {
        viewModelScope.launch {
            when (val result = ImportManager.import(context, uri)) {
                is ImportManager.ImportResult.Content -> {
                    refresh()
                    _importCompleted.tryEmit(result)
                    showToast(result.message)
                }
                is ImportManager.ImportResult.NeedsScriptConfirmation -> _scriptPrompt.tryEmit(result)
                is ImportManager.ImportResult.NeedsPluginConfirmation -> _pluginPrompt.tryEmit(result)
                is ImportManager.ImportResult.Plugin -> {
                    if (result.pluginInfo?.enabled == true) withContext(Dispatchers.IO) { PluginManager.loadEnabledPlugins() }
                    refresh()
                    _importCompleted.tryEmit(null)
                    showToast(if (result.pluginInfo?.enabled == true) "插件「${result.pluginInfo?.name}」已更新"
                        else "插件「${result.pluginInfo?.name ?: ""}」已安装，默认关闭。在插件页打开后才会运行")
                }
                is ImportManager.ImportResult.Failed -> showToast("导入失败：${result.reason}")
                is ImportManager.ImportResult.Unsupported -> showToast("不支持的文件类型")
            }
        }
    }

    fun confirmLooseScript(uri: Uri) {
        viewModelScope.launch {
            when (val result = ImportManager.import(context, uri, allowScripts = true)) {
                is ImportManager.ImportResult.Content -> {
                    refresh()
                    showToast(result.message)
                }
                is ImportManager.ImportResult.Failed -> showToast("导入失败：${result.reason}")
                else -> showToast("导入失败")
            }
        }
    }

    fun confirmPluginInstall(uri: android.net.Uri) {
        viewModelScope.launch {
            when (val result = ImportManager.import(context, uri, installPlugin = true)) {
                is ImportManager.ImportResult.Plugin -> {
                    refresh()
                    showToast("插件「${result.pluginInfo?.name ?: ""}」已安装，默认关闭。在插件页打开后才会运行")
                }
                is ImportManager.ImportResult.Failed -> showToast("安装失败：${result.reason}")
                else -> showToast("安装失败")
            }
        }
    }

    fun deploySchema() {
        if (_uiState.value.isDeploying) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeploying = true) }
            val success = try {
                withContext(Dispatchers.IO) {
                    KeysConfigHelper.loadConfig(context)
                    KeyboardThemes.reload(context)
                    RimeConfigHelper.redeploy(context)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                com.kingzcheung.xime.util.FileLogger.e("SchemaSettings", "Deployment repair failed", error)
                RimeConfigHelper.recordFailure(context, error)
                false
            } finally {
                _uiState.update { it.copy(isDeploying = false) }
            }
            val problem = RimeConfigHelper.deploymentProblem(context)
            when {
                problem.isNotEmpty() -> _uiState.update { it.copy(deployReport = problem) }
                success -> showToast("部署完成")
                else -> _uiState.update { it.copy(deployReport = "部署失败，原因见「关于 › 日志查看器」。") }
            }
            refresh()
        }
    }

    fun clearDeployReport() {
        _uiState.update { it.copy(deployReport = null) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }
}
