package com.kingzcheung.xime.ui.settings

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.ChineseSchemas
import com.kingzcheung.xime.settings.DoublePinyinScheme
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class SetupStatus(
    val imeEnabled: Boolean = false,
    val imeSelected: Boolean = false,
    val preparing: Boolean = true,
    val schemas: List<String> = emptyList(),
    val error: String? = null,
    /** 准备没过、正在等下一次自动重试时的原因（部署问题 + 几秒后重试）；正常准备中为 null。 */
    val detail: String? = null,
) {
    val ready: Boolean get() = !preparing && error == null && schemas.isNotEmpty()
    val complete: Boolean get() = imeEnabled && imeSelected && ready
}

/**
 * vivo（OriginOS / Funtouch，含 iQOO）上 [InputMethodManager.showInputMethodPicker] 弹出的
 * 「选择输入法」框可能一个输入法都不列，只剩「取消」（OriginOS 6 用户反馈，CyIME 已启用）。
 * 这类系统上第 3 步改为先去系统输入法设置里选默认输入法，返回 null 表示系统选择框可用、保持原样。
 */
internal fun imePickerFallbackHint(manufacturer: String, brand: String): String? {
    val vivo = listOf(manufacturer, brand).any { it.equals("vivo", true) || it.equals("iqoo", true) }
    return if (vivo) "vivo / iQOO（OriginOS）的输入法选择框可能是空的。请点下面按钮打开系统输入法设置，" +
        "在「默认输入法」（或「当前输入法」）里选 Lime；如果打开的页面里没有，就进" +
        "「设置 → 系统管理（或更多设置）→ 语言与输入法 → 默认输入法」，也可以在设置顶部搜索「默认输入法」。"
    else null
}

/** 打开系统输入法设置；个别系统没有这个页面时退回设置首页。 */
private fun openImeSettings(context: Context) {
    try { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    } catch (e: ActivityNotFoundException) {
        Log.w("SetupWizard", "No input method settings page", e)
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
fun SetupWizardScreen(
    visible: Boolean = true,
    onNavigateToSchemaSettings: () -> Unit,
    onCompleted: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember { mutableStateOf(SetupStatus()) }
    var attempt by remember { mutableIntStateOf(0) }
    // 后台线程写的重试原因单独放一个状态：status 由主线程每 500ms 整体改写，混写会互相覆盖。
    var preparationDetail by remember { mutableStateOf<String?>(null) }
    val pickerFallbackHint = remember { imePickerFallbackHint(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty()) }

    fun refreshImeState() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val selected = ComponentName.unflattenFromString(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty())
        status = status.copy(
            imeEnabled = imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true,
            imeSelected = selected?.packageName == context.packageName,
        )
    }
    LaunchedEffect(visible, lifecycle) {
        if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // The system picker can close without a new activity resume event.
            while (true) { refreshImeState(); delay(500) }
        }
    }
    LaunchedEffect(visible, attempt) {
        if (!visible) return@LaunchedEffect
        status = status.copy(preparing = true, error = null)
        preparationDetail = null
        try {
            val schemas = withContext(Dispatchers.IO) {
                // 准备没过时会一直自动重试；把原因摆出来，不再只有一根进度条转十几分钟看不出卡在哪（GitHub #36）。
                RimeConfigHelper.prepareAutomatically(context) { message ->
                    preparationDetail = message.takeIf { "重试" in it }
                }
                // 用方案文件里的名字（小鹤双拼等），和「语言与方案」页一致；读不到才退回内置名或 ID。
                val names = SchemaManager.discoverSchemas(context).associate { it.schemaId to it.name }
                // 双拼的 14 键版本是组合配置（双拼 × 14 键），不单独列一项。
                SchemaManager.getEnabledSchemas(context).filterNot(com.kingzcheung.xime.settings.CyimeInputDefaults::isCompanion).map { id ->
                    names[id] ?: DoublePinyinScheme.BUILTINS.firstOrNull { it.id == id }?.name ?: ChineseSchemas.displayName(id, id)
                }
            }
            preparationDetail = null
            status = status.copy(preparing = false, schemas = schemas,
                error = if (schemas.isEmpty()) "请至少启用一个输入方案" else null)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            Log.e("SetupWizard", "Automatic preparation failed", e)
            status = status.copy(preparing = false, error = "词库准备失败，请重试")
        }
    }
    if (visible) SetupWizardContent(
        status = status.copy(detail = preparationDetail.takeIf { status.preparing }),
        onEnable = { openImeSettings(context) },
        onSchemas = onNavigateToSchemaSettings,
        onRetry = { attempt++ },
        onSwitch = {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm != null) imm.showInputMethodPicker() else openImeSettings(context)
        },
        pickerFallbackHint = pickerFallbackHint,
        onOpenImeSettings = { openImeSettings(context) },
        onFinish = {
            refreshImeState()
            if (status.complete) {
                val enabled = SchemaManager.getEnabledSchemas(context)
                if (enabled.isNotEmpty()) {
                    if (SettingsPreferences.getCurrentSchema(context) !in enabled)
                        SettingsPreferences.setCurrentSchema(context, enabled.first())
                    SettingsPreferences.setSetupCompleted(context, true)
                    onCompleted()
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupWizardContent(
    status: SetupStatus,
    onEnable: () -> Unit,
    onSchemas: () -> Unit,
    onRetry: () -> Unit,
    onSwitch: () -> Unit,
    onFinish: () -> Unit,
    pickerFallbackHint: String? = null,
    onOpenImeSettings: () -> Unit = {},
) {
    Scaffold(topBar = { TopAppBar(title = { Text("设置向导") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).settingsVerticalScroll()
            .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("按顺序完成下面三项，即可开始输入。", style = MaterialTheme.typography.bodyLarge)
            SetupCard(1, "启用 Lime", status.imeEnabled,
                if (status.imeEnabled) "已启用" else "待启用") {
                if (!status.imeEnabled) {
                    Text("在系统输入法列表中打开 Lime，返回后会自动检查。")
                    Button(onClick = onEnable) { Text("去系统设置启用") }
                }
            }
            SetupCard(2, "准备输入方案", status.ready,
                when { status.preparing -> "准备中"; status.error != null -> "需要处理"; status.ready -> "已就绪"; else -> "待准备" }) {
                when {
                    status.preparing -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在准备词库，首次使用可能需要几分钟…")
                        status.detail?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    status.error != null -> {
                        Text(status.error, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onRetry) { Text("重试准备") }
                    }
                    else -> Text(status.schemas.joinToString(" · "))
                }
                // 老人包不出下一页（按设备推荐配置）。
                if (!com.kingzcheung.xime.BuildConfig.ELDER) Text("用全拼、双拼还是五笔，26 键、14 键还是九键，完成后下一页会问你。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onSchemas, enabled = !status.preparing) { Text("选择其他方案（可选）") }
            }
            SetupCard(3, "切换到 Lime", status.imeEnabled && status.imeSelected,
                if (status.imeEnabled && status.imeSelected) "已切换" else "待切换") {
                if (!status.imeEnabled || !status.imeSelected) {
                    val canSwitch = status.imeEnabled && status.ready
                    if (pickerFallbackHint == null) {
                        Text("启用并准备完成后，在输入法选择器中选中 Lime。")
                        Button(onClick = onSwitch, enabled = canSwitch) { Text("选择 Lime") }
                    } else {
                        Text(pickerFallbackHint)
                        Button(onClick = onOpenImeSettings, enabled = canSwitch) { Text("去系统设置选择 Lime") }
                        TextButton(onClick = onSwitch, enabled = canSwitch) { Text("仍用输入法选择框") }
                    }
                }
            }
            if (status.complete) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text("✓ 设置成功，可以开始输入了", Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                }
            }
            Button(onClick = onFinish, enabled = status.complete, modifier = Modifier.fillMaxWidth()) {
                Text(if (status.complete) "开始使用" else "完成三项后即可开始使用")
            }
        }
    }
}

@Composable
private fun SetupCard(number: Int, title: String, complete: Boolean, state: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (complete)
        MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (complete) "✓" else number.toString(), fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 12.dp))
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(state, style = MaterialTheme.typography.labelLarge)
            }
            content()
        }
    }
}
