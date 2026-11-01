package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.GraphicEq
import androidx.compose.material.icons.twotone.Tune
import androidx.compose.material.icons.twotone.DirectionsCar
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.api.AsrPlugin
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.speech.AsrBackendFactory
import com.kingzcheung.xime.ui.caption.CaptionLauncherIcon
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechToTextSettingsContent(
    onBack: () -> Unit,
    onNavigateToPluginSettings: (String) -> Unit = {},
    onNavigateToSetupGuide: () -> Unit = {},
) {
    val context = LocalContext.current


    var saveTuningAudio by remember {
        mutableStateOf(SettingsPreferences.isSttDebugRecord(context))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("语音转文本", style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                .settingsVerticalScroll()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // 语音总开关放在页内最上面（以前挂在首页那一行上，进来反而找不到）。
            var sttEnabled by remember { mutableStateOf(SettingsPreferences.isSttEnabled(context)) }
            SettingsSection(title = "语音输入") {
                SettingsToggleItem(icon = Icons.TwoTone.GraphicEq, title = "语音转文本",
                    subtitle = if (sttEnabled) "点工具栏麦克风说话，转成文字上屏；想按住空格说话，在「按键与手势 › 长按空格」选「语音开关」"
                        else "已关闭：工具栏麦克风、按住空格说话和物理键盘 Alt + H 都不会开麦",
                    checked = sttEnabled, onCheckedChange = { sttEnabled = it; SettingsPreferences.setSttEnabled(context, it) })
                // 开车连续语音：别人提的需求，默认关（VoicePillResume）。只在语音打开时生效，关着语音就不显示。
                if (sttEnabled) {
                    var pillResume by remember { mutableStateOf(SettingsPreferences.isSttPillResume(context)) }
                    SettingsToggleItem(icon = Icons.TwoTone.DirectionsCar, title = "开车连续语音",
                        subtitle = "语音收起成悬浮条后，发送完回到输入框自动接着听",
                        checked = pillResume, onCheckedChange = { pillResume = it; SettingsPreferences.setSttPillResume(context, it) })
                }
            }

            // 模型名（Paraformer、SenseVoice…）多数人看不懂：先给一个按手机和用法自动选好的入口。
            SettingsSection(title = "不知道怎么选") {
                SettingsItem(icon = Icons.TwoTone.Tune, title = "按设备推荐配置",
                    subtitle = "回答几个问题，自动选好语音模型，删掉用不到的",
                    onClick = onNavigateToSetupGuide, showArrow = true)
            }

            val scope = rememberCoroutineScope()

            if (OfflineAsrSettings.isSupported()) {
                // 不再有「使用本地模型」开关：一律先用本地模型；在线接口测试通过后才可选「优先使用在线接口」。
                run {
                    // 模型常驻与空闲释放统一在「性能与省电」设置，这里不再重复（两处会互相覆盖）。
                    SettingsSection(title = "本地识别") {
                        Text(
                            text = "语音模型是否常驻、空闲多久释放，在「性能与省电」中设置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
                        )
                        // 调优录音是开发者工具：只在自用测试包和调试包里显示。
                        if (com.kingzcheung.xime.BuildConfig.DEV_TOOLS || com.kingzcheung.xime.BuildConfig.DEBUG) Column(Modifier.settingsSearchAnchor("保存语音调优录音").fillMaxWidth().padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "保存语音调优录音",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = saveTuningAudio,
                                    onCheckedChange = {
                                        saveTuningAudio = it
                                        SettingsPreferences.setSttDebugRecord(context, it)
                                    },
                                )
                            }
                            Text(
                                text = "每次本地语音的录音和识别过程只存在本机应用目录，用于连接电脑后回放分析、调整断句和模型；最多保留 300 段或 500 MB，不会上传。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 本地模型下载与管理
                    OfflineAsrSettings.ModelSection()
                }
            }

            SettingsSection(title = "字幕") {
                // CaptionController.chineseMode：字幕自己挑模型，和上面语音输入的选择分开，这里写明，免得两处看起来矛盾。
                Text(
                    text = "字幕的中文识别不跟上面的模型选择，按已下载的模型自动选。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
                )
                var captionIcon by remember { mutableStateOf(CaptionLauncherIcon.isEnabled(context)) }
                Column(Modifier.settingsSearchAnchor("桌面显示“字幕”图标").fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "桌面显示“字幕”图标",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = captionIcon,
                            onCheckedChange = {
                                captionIcon = it
                                CaptionLauncherIcon.setEnabled(context, it)
                            },
                        )
                    }
                    Text(
                        text = "打开后确认添加到桌面，点一下直接进字幕页；图标颜色跟随键盘主题。关闭后桌面图标变灰，可长按移除；也可从下拉快捷开关或键盘菜单的“字幕”进入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                var captionAutoSave by remember { mutableStateOf(com.kingzcheung.xime.ui.caption.CaptionAutoSave.isOn(context)) }
                Column(Modifier.settingsSearchAnchor("自动保存字幕").fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "自动保存字幕",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = captionAutoSave,
                            onCheckedChange = {
                                captionAutoSave = it
                                com.kingzcheung.xime.ui.caption.CaptionAutoSave.setOn(context, it)
                            },
                        )
                    }
                    Text(
                        text = "从打开字幕到按“退出”算一次，每次存成一个文件，边听边更新，在“下载/Lime字幕”里。关闭后只在点“保存”时存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                var keepThreeDays by remember { mutableStateOf(com.kingzcheung.xime.ui.caption.CaptionAutoSave.keepsThreeDays(context)) }
                Column(Modifier.settingsSearchAnchor("字幕只保留 3 天").fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "字幕只保留 3 天",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = keepThreeDays,
                            onCheckedChange = {
                                keepThreeDays = it
                                com.kingzcheung.xime.ui.caption.CaptionAutoSave.setKeepsThreeDays(context, it)
                            },
                        )
                    }
                    Text(
                        text = "“下载/Lime字幕”里超过 3 天的字幕记录在下次打开字幕时自动删掉。关闭后一直保留。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                var nameAlert by remember { mutableStateOf(com.kingzcheung.xime.ui.caption.CaptionNameAlert.isOn(context)) }
                var alertNames by remember { mutableStateOf(com.kingzcheung.xime.ui.caption.CaptionNameAlert.names(context)) }
                Column(Modifier.settingsSearchAnchor("有人叫我名字时振动").fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "有人叫我名字时振动",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = nameAlert,
                            onCheckedChange = {
                                nameAlert = it
                                com.kingzcheung.xime.ui.caption.CaptionNameAlert.setOn(context, it)
                            },
                        )
                    }
                    Text(
                        text = "字幕里别人说的话出现下面的名字时，手机振动三下。几个叫法用空格隔开，比如“老王 王叔 爷爷”。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (nameAlert) androidx.compose.material3.OutlinedTextField(
                        value = alertNames,
                        onValueChange = {
                            alertNames = it
                            com.kingzcheung.xime.ui.caption.CaptionNameAlert.setNames(context, it)
                        },
                        label = { Text("我的名字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                var captionFloat by remember { mutableStateOf(com.kingzcheung.xime.ui.caption.CaptionFloat.isOn(context)) }
                val callCaptionOn = remember(captionFloat) { com.kingzcheung.xime.ui.caption.CallCaptionService.isEnabled(context) }
                Column(Modifier.settingsSearchAnchor("后台浮窗").fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "后台浮窗",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = captionFloat,
                            onCheckedChange = {
                                captionFloat = it
                                com.kingzcheung.xime.ui.caption.CaptionFloat.setOn(context, it)
                                // The bar lives in the call caption service; without it there is nothing to float in.
                                if (it && !com.kingzcheung.xime.ui.caption.CallCaptionService.isEnabled(context)) runCatching {
                                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            },
                        )
                    }
                    Text(
                        text = "离开字幕页（回桌面或切到别的应用）后，字幕继续在屏幕底部显示，点一下回到字幕页；按“退出”才停止。打电话时只显示通话字幕，挂断后接着显示。" +
                            if (callCaptionOn) "" else "需要先在系统无障碍里打开“通话字幕”。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OnlineSpeechApiSection()
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** 内置在线语音：接口地址、密钥、模型，一个测试按钮和接口状态。密钥只存本机、不进备份。 */
@Composable
internal fun OnlineSpeechApiSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { com.kingzcheung.xime.speech.OnlineSpeechApi.config(context) }
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var status by remember { mutableStateOf(com.kingzcheung.xime.speech.OnlineSpeechApi.status(context)) }
    var testing by remember { mutableStateOf(false) }
    var usable by remember { mutableStateOf(com.kingzcheung.xime.speech.OnlineSpeechApi.isUsable(context)) }
    var preferOnline by remember { mutableStateOf(!SettingsPreferences.isSttUseLocal(context)) }
    fun save() {
        com.kingzcheung.xime.speech.OnlineSpeechApi.save(context,
            com.kingzcheung.xime.speech.OnlineSpeechApi.Config(baseUrl, apiKey, model))
        status = com.kingzcheung.xime.speech.OnlineSpeechApi.status(context)
        // 改了地址、密钥或模型就要重新测试；测试通过前一律用本地模型。
        usable = false; preferOnline = false
        SettingsPreferences.setSttUseLocal(context, true)
    }
    SettingsSection(title = "在线语音接口") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it; save() },
                label = { Text("接口地址") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("speech-api-url"))
            androidx.compose.material3.OutlinedTextField(value = apiKey, onValueChange = { apiKey = it; save() },
                label = { Text("API 密钥") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("speech-api-key"))
            androidx.compose.material3.OutlinedTextField(value = model, onValueChange = { model = it; save() },
                label = { Text("模型") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("speech-api-model"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("状态：$status", style = MaterialTheme.typography.bodyMedium,
                    color = if (status.startsWith("可用")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag("speech-api-status"))
                androidx.compose.material3.OutlinedButton(enabled = !testing, onClick = {
                    save(); testing = true; status = "测试中…"
                    scope.launch {
                        status = withContext(Dispatchers.IO) { com.kingzcheung.xime.speech.OnlineSpeechApi.test(context) }
                        usable = com.kingzcheung.xime.speech.OnlineSpeechApi.isUsable(context)
                        testing = false
                    }
                }, modifier = Modifier.testTag("speech-api-test")) { Text("测试") }
            }
            if (usable) SettingsToggleItem(icon = Icons.TwoTone.GraphicEq, title = "优先使用在线接口",
                subtitle = "说话时先用在线接口识别；失败时这次自动改用本地模型，并在键盘上提示",
                checked = preferOnline, onCheckedChange = {
                    preferOnline = it
                    SettingsPreferences.setSttUseLocal(context, !it)
                })
            Text("选填。兼容 OpenAI 语音转写接口（/audio/transcriptions），如 OpenAI、Groq、硅基流动；说完一句整段上传识别。" +
                "不填或测试没通过时一律用本地模型。密钥只存在本机，不进备份。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
