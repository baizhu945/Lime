package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.twotone.ViewModule
import androidx.compose.material.icons.twotone.Inventory
import androidx.compose.material.icons.twotone.Schedule
import androidx.compose.material.icons.twotone.Translate
import androidx.compose.material.icons.twotone.SpaceBar
import androidx.compose.material.icons.twotone.EmojiEmotions
import androidx.compose.material.icons.twotone.Functions
import androidx.compose.material.icons.twotone.FontDownload
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kingzcheung.xime.settings.*
import com.kingzcheung.xime.ui.menubar.rememberDragOrder
import com.kingzcheung.xime.ui.menubar.dragOrderItem
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.kingzcheung.xime.ui.theme.MaterialLevel
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.visualMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsContent(onOpenSchemaResources: () -> Unit = {}, onOpenDoublePinyin: () -> Unit = {}, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var languages by remember { mutableStateOf(LanguagePreferences.enabled(context)) }
    var orderedLanguages by remember { mutableStateOf(InputModes.languageOrder(context)) }
    var saving by remember { mutableStateOf(false) }
    var expandedLanguage by remember { mutableStateOf<InputLanguage?>(orderedLanguages.firstOrNull()) }
    var message by remember { mutableStateOf("") }
    val profilesModel: com.kingzcheung.xime.viewmodel.SchemaSettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val profilesState by profilesModel.uiState.collectAsState()
    var remembered by remember { mutableStateOf(InputModes.rememberedModes(context)) }
    val availableProfiles = InputModes.ordered(context, profilesState.allSchemas
        .filter { it.schemaId in profilesState.enabledSchemas }.map { it.toSchemaInfo() })
    val dragOrder = rememberDragOrder(orderedLanguages.map { it.id }) { ids ->
        InputModes.saveLanguageOrder(context, ids)
        orderedLanguages = InputModes.languageOrder(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        languages = LanguagePreferences.enabled(context)
        orderedLanguages = InputModes.languageOrder(context)
        profilesModel.refresh()
        remembered = InputModes.rememberedModes(context)
    }
    Scaffold(modifier = Modifier.testTag("language-settings"), containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopAppBar(title = { Text("语言与方案") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) }
    ) { padding ->
        SettingsSearchAutoScroll(dragOrder.list)
        LazyColumn(modifier = Modifier.settingsGutterScroll(dragOrder.list).fillMaxSize().testTag("language-settings-list").padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
            state = dragOrder.list, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("点开语言，分别设置输入方案和键盘布局；下次切换到该语言时使用。长按可调整语言顺序。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(dragOrder.order + InputLanguage.supported.filterNot { it in languages }.map { it.id }, key = { it }) { id ->
                val language = InputLanguage.entries.first { it.id == id }
                val checked = language in languages
                val canChange = language != InputLanguage.ENGLISH && !saving
                val selected = InputProfileSelection.preferred(availableProfiles, language,
                    remembered[language], profilesState.currentSchema)
                val expanded = checked && expandedLanguage == language
                Surface(modifier = Modifier.fillMaxWidth().testTag("language-drag:${language.id}")
                    .then(dragOrderItem(dragOrder, id, checked && orderedLanguages.size > 1 && !saving)),
                    shape = RoundedCornerShape(12.dp),
                    border = if (dragOrder.dragging == id) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    color = if (dragOrder.dragging == id) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().testTag("language-${language.id}")
                        .visualMaterial(VisualStyles.current, 12.dp, level = MaterialLevel.RAISED)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                            .clickable(enabled = checked) { expandedLanguage = if (expanded) null else language }
                            .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                // Dynamic font-scale previews must recreate the text layout,
                                // otherwise the cached TextLayout keeps the old line height.
                                val cardFontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                                androidx.compose.runtime.key(cardFontScale) {
                                    androidx.compose.foundation.layout.Column {
                                        Text(language.displayName, style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        Text(if (!checked) "未启用" else selected?.selectionLabel ?: "正在准备可用组合",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (checked) IconButton(onClick = { expandedLanguage = if (expanded) null else language },
                                modifier = Modifier.testTag("language-expand:${language.id}")) {
                                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    if (expanded) "收起${language.displayName}设置" else "展开${language.displayName}设置")
                            }
                            Switch(checked, enabled = canChange, colors = settingsSwitchColors(), modifier = Modifier.testTag("language-toggle:${language.id}"),
                                onCheckedChange = { enabled ->
                                scope.launch {
                                    saving = true
                                    try {
                                        withContext(Dispatchers.IO) { LanguagePreferences.save(context, language, enabled) }
                                        languages = LanguagePreferences.enabled(context)
                                        orderedLanguages = InputModes.languageOrder(context)
                                        profilesModel.refresh()
                                        if (enabled) expandedLanguage = language
                                        message = if (enabled) "已开启，输入方案会在后台自动准备" else "已关闭，已下载词库保留"
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (error: Exception) { message = error.message ?: "保存失败，请稍后再试" }
                                    finally { saving = false }
                                }
                            })
                        }
                        if (expanded && selected != null) InputProfileSelectors(availableProfiles, selected,
                            Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) { entry ->
                            InputModes.selectProfile(context, entry)
                            remembered = remembered + (language to entry.schemaId)
                            message = "已保存：${language.displayName} · ${entry.selectionLabel}，下次切换到该语言时使用"
                        }
                    }
                }
            }
            item(key = "chinese-output") {
                val input = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences()
                SettingsSection(title = "中文输出") {
                    SettingsToggleItem(Icons.TwoTone.Translate, "繁体输出",
                        "候选和上屏改用台湾正体（台灣、計畫、了解），全拼、双拼、九键、14 键和仓颉都适用（关着时仓颉也出简体）；注音始终出正体。词库和学习记录不变，开启时不给中文联想。",
                        checked = input.traditionalOutput, onCheckedChange = { input.copy(traditionalOutput = it).save(context) })
                    HorizontalDivider()
                    SettingsToggleItem(Icons.TwoTone.FontDownload, "生僻字",
                        "全拼 26 键和九键打一个完整拼音（chan、gui）时，在常用字后面补上约 1.8 万个生僻字（瀺、槻）；双拼、14 键不适用。",
                        checked = input.rareCharacters, onCheckedChange = { input.copy(rareCharacters = it).save(context) })
                    HorizontalDivider()
                    SettingsToggleItem(Icons.TwoTone.Schedule, "时间日期候选",
                        "打“时间”“日期”“星期”时，词后面紧跟着给设备当前的时间和日期（15:35、2026-10-31、星期六），词本身的位置不变；全拼、双拼、九键、14 键都适用。",
                        checked = input.datetimeCandidates, onCheckedChange = { input.copy(datetimeCandidates = it).save(context) })
                    HorizontalDivider()
                    SettingsToggleItem(Icons.TwoTone.EmojiEmotions, "候选里显示表情",
                        "打「开心」「爱心」这类词时，在词后面多出对应的表情（😄、❤️）；全拼、双拼、九键和 14 键都适用。",
                        checked = input.candidateEmoji, onCheckedChange = { input.copy(candidateEmoji = it).save(context) })
                    HorizontalDivider()
                    SettingsToggleItem(Icons.TwoTone.Functions, "打符号名出符号",
                        "打「加号」「问号」「括号」这类符号名时，在词后面多出对应的符号（+、？、（）），词本身的位置不变；全拼、双拼、九键和 14 键都适用。",
                        checked = input.symbolNames, onCheckedChange = { input.copy(symbolNames = it).save(context) })
                }
            }
            item(key = "english-input") {
                val input = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences()
                SettingsSection(title = "英文输入") {
                    SettingsToggleItem(Icons.TwoTone.SpaceBar, "选词后自动加空格",
                        "英文（含西班牙语）点候选栏的词后自动补一个空格，接着打下一个词不用再按空格。紧接着按空格不会多出一个；" +
                            "打 , . ? ! : ; 时标点挪到空格前面；退格删掉这个空格。",
                        checked = input.englishAutoSpace, onCheckedChange = { input.copy(englishAutoSpace = it).save(context) })
                    HorizontalDivider()
                    SettingsToggleItem(Icons.TwoTone.SpaceBar, "标点后自动加空格",
                        "英文（含西班牙语）键盘在字母后面打 , . ? ! : ; 时自动跟一个空格（Hello, world）。紧接着按空格不会多出一个，" +
                            "连打 ... ?! 空格留在最后；3.14、1,000、10:30 和网址、邮箱、密码框不加；退格删掉这个空格。",
                        checked = input.englishPunctuationSpace, onCheckedChange = { input.copy(englishPunctuationSpace = it).save(context) })
                }
            }
            item(key = "language-key-switch") {
                LanguageKeySwitchSettings(availableProfiles, orderedLanguages, profilesState.currentSchema)
            }
            // 已装的方案组合（启停、排序、应用更改）和导入，原首页「输入资源」。
            item(key = "schema-resources") {
                SettingsSection(title = "方案资源") {
                    SettingsItem(Icons.TwoTone.Inventory, "已安装的方案",
                        "启停和排序语言、方案与布局的组合，导入方案文件", onClick = onOpenSchemaResources, showArrow = true)
                    SettingsItem(Icons.TwoTone.ViewModule, "双拼方案",
                        "小鹤、自然码、微软等 6 种内置双拼的键位，新建和修改自己的双拼方案", onClick = onOpenDoublePinyin, showArrow = true)
                }
            }
            item { if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
