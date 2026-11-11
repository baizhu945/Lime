package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.twotone.SwapHoriz
import androidx.compose.material.icons.twotone.OpenWith
import androidx.compose.material.icons.twotone.SpaceBar
import androidx.compose.material.icons.twotone.Gesture
import androidx.compose.material.icons.twotone.Abc
import androidx.compose.material.icons.twotone.Spellcheck
import androidx.compose.material.icons.twotone.Hearing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SpellingPatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.kingzcheung.xime.ui.keyboard.normalizeLetterSwipeDistance
import com.kingzcheung.xime.ui.keyboard.normalizeCandidateDragPercent
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences

internal enum class LayoutInputCategory(val route: String, val title: String, val description: String) {
    Qwerty("layout_input_qwerty", "26 键", "纠错、中英混输、生僻字、滑行输入、中文滑行、模糊音、字母错位、空格两侧标点、逗号键和 Shift 分词"),
    Nine("layout_input_nine", "九键与 14 键", "拂动或滑动选字母、触发距离（含日语假名）、方向圆盘、假名提示"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayoutInputSettingsContent(category: LayoutInputCategory, onBack: () -> Unit) {
    val context = LocalContext.current
    val saved = rememberKeyboardInputPreferences()
    val spellingScope = rememberCoroutineScope()
    var settings by remember(saved) { mutableStateOf(saved) }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface, topBar = {
        TopAppBar(title = { Text(category.title) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).settingsVerticalScroll().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (category) {
                LayoutInputCategory.Qwerty -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsSection(title = "26 键输入", content = {
                    SettingsToggleItem(Icons.TwoTone.Spellcheck, "邻键纠错",
                        "仅用于中文全拼 26 键；按实际键位补充候选，保留原输入，不用于双拼。",
                        checked = settings.neighborCorrection, onCheckedChange = {
                            settings = settings.copy(neighborCorrection = it); settings.save(context)
                        })
                    HorizontalDivider()
                    SettingsToggleItem(Icons.TwoTone.Spellcheck, "双拼邻键纠错",
                        "仅用于双拼 26 键（小鹤、自然码等）；按实际键位补充按到相邻键的候选，保留原输入。改动后方案重新部署，要几秒。",
                        checked = settings.doublePinyinCorrection, onCheckedChange = {
                            settings = settings.copy(doublePinyinCorrection = it); settings.save(context)
                            spellingScope.launch(Dispatchers.IO) { runCatching { SpellingPatches.apply(context) } }
                        })
                    HorizontalDivider()
                    SettingsToggleItem(Icons.TwoTone.Abc, "中英混输",
                        "仅用于中文全拼 26 键；拼音里夹着英文词（nihaohello）时首选“你好hello”。本身像拼音的英文词（game、nice）认不出。",
                        checked = settings.mixedInput, onCheckedChange = {
                            settings = settings.copy(mixedInput = it); settings.save(context)
                        })
                    HorizontalDivider()
                    // 「键盘滑动」移光标和滑行冲突：照常显示，被锁时置灰并写明被谁锁（见 InputGestureLocks）。
                    SettingsToggleItem(Icons.TwoTone.Gesture, "滑行输入",
                        "英文 26 键手指划过字母出整词，候选栏可换成其他词；竖着滑一行仍输入角标符号。",
                        checked = settings.glideTyping, onCheckedChange = {
                            settings = settings.copy(glideTyping = it); settings.save(context)
                        },
                        lockMessage = com.kingzcheung.xime.ui.keyboard.InputGestureLocks.lockFor(settings,
                            com.kingzcheung.xime.ui.keyboard.InputGestureLock.Option.GLIDE_TYPING, true)
                            ?.takeIf { !settings.glideTyping }?.message)
                    HorizontalDivider()
                    SettingsToggleItem(Icons.TwoTone.Gesture, "中文滑行输入",
                        "中文全拼、双拼 26 键划一个词的拼音，接着划或点按都接在后面，照常选字；竖着滑一行仍输入角标符号。",
                        checked = settings.chineseGlideTyping, onCheckedChange = {
                            settings = settings.copy(chineseGlideTyping = it); settings.save(context)
                        },
                        lockMessage = com.kingzcheung.xime.ui.keyboard.InputGestureLocks.lockFor(settings,
                            com.kingzcheung.xime.ui.keyboard.InputGestureLock.Option.GLIDE_TYPING, true)
                            ?.takeIf { !settings.chineseGlideTyping }?.message)
                    HorizontalDivider()
                    QwertyStaggerSettings(settings) { settings = it; it.save(context) }
                    HorizontalDivider()
                    Column(Modifier.settingsSearchAnchor("底排标点").padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("底排标点", style = MaterialTheme.typography.titleSmall)
                        Text("标准 ?123 底排把句号放在逗号旁，并从 ?123 腾出空间；旧双模式键布局保留原位置。" +
                            "两个都开时滑动输入另一种宽度，只开一个时滑动输入另一个标点。",  style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        SpacePunctuationIllustration(settings.qwertyCommaKey, settings.qwertyPeriodKey)
                    }
                    SettingsToggleItem(Icons.TwoTone.SpaceBar, "空格左侧逗号键", "关闭后空格向左加宽。",
                        checked = settings.qwertyCommaKey, onCheckedChange = {
                            settings = settings.copy(qwertyCommaKey = it); settings.save(context)
                        })
                    SettingsToggleItem(Icons.TwoTone.SpaceBar, "底排句号键", "标准 ?123 布局中位于逗号旁，缩短 ?123，不挤压空格。",
                        checked = settings.qwertyPeriodKey, onCheckedChange = {
                            settings = settings.copy(qwertyPeriodKey = it); settings.save(context)
                        })
                    // 逗号键分词（默认关）：下角标的方向跟「反转上下滑方向」走，说明照实际方向写。
                    SettingsToggleItem(Icons.TwoTone.SpaceBar, "逗号键分词",
                        "中文全拼、双拼的 26 键和 14 键：逗号键左下角加 '（只开句号键时在句号键上），" +
                            "打拼音时${if (settings.reverseSymbolSwipe) "下滑" else "上滑"}插入分词（xi'an），F 键角标的 ' 也一样；" +
                            "没在打拼音时输入 '。九键照旧用 1 键分词。",
                        checked = settings.syllableSplitKey, onCheckedChange = {
                            settings = settings.copy(syllableSplitKey = it); settings.save(context)
                        })
                    SettingsToggleItem(Icons.TwoTone.SpaceBar, "Shift 键分词",
                        "中文全拼、双拼的 26 键和 14 键：打拼音时 Shift 键写「分词」，点一下插入分词（xi'an）；" +
                            "没在打拼音时照旧切换大小写，按住 Shift 打大写也不变。",
                        checked = settings.shiftSyllableSplit, onCheckedChange = {
                            settings = settings.copy(shiftSyllableSplit = it); settings.save(context)
                        },
                        // 和微软双拼的「ing 放在 Shift 键上」互锁：打字时都要占 Shift 键。
                        lockMessage = com.kingzcheung.xime.ui.keyboard.InputGestureLocks.lockFor(settings, com.kingzcheung.xime.ui.keyboard.InputGestureLock.Option.SHIFT_SYLLABLE_SPLIT, true)
                            ?.takeIf { !settings.shiftSyllableSplit }?.message)
                })
                FuzzyPinyinSection()
                }
                LayoutInputCategory.Nine -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsSection(title = "九键", content = {
                        SettingsToggleItem(Icons.TwoTone.OpenWith, "拂动选字母",
                            "左、上、右、下依次输入键上的第 1～4 个字母（7、9 用到下），点按仍是九键输入；" +
                                "开启后长按输入数字、不再弹出字母菜单。",
                            checked = settings.t9FlickLetters, onCheckedChange = {
                                settings = settings.copy(t9FlickLetters = it); settings.save(context)
                            },
                            // 角标“长按”时数字占了长按，拂动必须开着：锁成开，不能关。
                            lockMessage = com.kingzcheung.xime.ui.keyboard.InputGestureLocks.lockFor(settings,
                                com.kingzcheung.xime.ui.keyboard.InputGestureLock.Option.T9_FLICK_LETTERS, false)
                                ?.takeIf { settings.t9FlickLetters }?.message)
                        HorizontalDivider()
                        Column(Modifier.settingsSearchAnchor("拖到候选选词的距离").padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("拖到候选选词的距离：键盘的 ${settings.candidateDragPercent.toInt()}%", style = MaterialTheme.typography.titleSmall)
                            Slider(value = settings.candidateDragPercent,
                                modifier = Modifier.testTag("t9-candidate-drag-percent"),
                                valueRange = 20f..80f, steps = 5,
                                onValueChange = { settings = settings.copy(candidateDragPercent = normalizeCandidateDragPercent(it)) },
                                onValueChangeFinished = { settings.save(context) })
                            Text("默认 50%。按住「下一项」拖过这么远才算拖到候选选词；没拖这么远仍是「下一项」自己的左滑、右滑。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    })
                    SettingsSection(title = "14 键双字母键", content = {
                        SettingsToggleItem(Icons.TwoTone.SwapHoriz, "左右滑动选字母",
                            "例如 qw：左滑输入 q，右滑输入 w，松手确认；点按仍使用合并键。适用于拼音和小鹤双拼 14 键。",
                            checked = settings.fourteenLetterSwipe, onCheckedChange = {
                                settings = settings.copy(fourteenLetterSwipe = it); settings.save(context)
                            })
                    })
                    SettingsSection(title = "滑动触发距离", content = {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("滑动触发距离：${settings.fourteenLetterSwipeDp.toInt()} dp", style = MaterialTheme.typography.titleSmall)
                            Slider(value = settings.fourteenLetterSwipeDp,
                                modifier = Modifier.testTag("fourteen-letter-distance"),
                                valueRange = 12f..72f, steps = 29,
                                onValueChange = { settings = settings.copy(fourteenLetterSwipeDp = normalizeLetterSwipeDistance(it)) },
                                onValueChangeFinished = { settings.save(context) })
                            Text("默认 24 dp，越大越不易误触。九键拂动选字母、14 键左右滑选字母和日语九键假名拂动共用。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    })
                    InputExperienceSettings(setOf(InputExperiencePart.FLICK_DISC, InputExperiencePart.KANA), "拂动与假名提示")
                }
            }
            Text("分体键盘、按键符号在上一页「键盘布局」；角标、光标与空格在「按键与手势」；字号在「主题与外观」。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


/** 模糊音：每组一行开关，默认全关；改了在后台写方案补丁，输入法重新部署后生效（见 [SpellingPatches]）。 */
@Composable
private fun FuzzyPinyinSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(SpellingPatches.fuzzy(context)) }
    var doublePinyin by remember { mutableStateOf(SpellingPatches.fuzzyDoublePinyin(context)) }
    // 现在打的是双拼而开关关着：下面各组对双拼不起作用（10-31 反馈小鹤 bkgr 出不来宾馆）。
    val usingDoublePinyin = remember { runCatching { SpellingPatches.usingDoublePinyin(context) }.getOrDefault(false) }
    Column(Modifier.settingsSearchAnchor("模糊音")) {
        SettingsSection(title = "模糊音", content = {
            Text("用于中文全拼 26 键（含 QWJRTK 等字母排列和自建布局），也用于双拼 26 键（可用下面的「模糊音也用于双拼」关掉）。" +
                "九键、14 键不变。打开一组后两边的读音都能打出对方的字，候选会多一些近音字；改动后方案重新部署，要几秒。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            SettingsToggleItem(Icons.TwoTone.Hearing, "模糊音也用于双拼",
                "下面打开的各组也用于双拼 26 键（小鹤、自然码、自定义双拼等），按读音算：打开 in 和 ing 后，" +
                    "按 in 的双拼键也出 ing 的字。关掉后只用于全拼。",
                checked = doublePinyin, onCheckedChange = { on ->
                    SpellingPatches.setFuzzyDoublePinyin(context, on)
                    doublePinyin = on
                    scope.launch(Dispatchers.IO) { runCatching { SpellingPatches.apply(context) } }
                })
            if (usingDoublePinyin && !doublePinyin) Text("你现在用的是双拼：要打开上面这个开关，下面打开的各组才对双拼起作用。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).testTag("fuzzy-double-pinyin-hint"))
            SpellingPatches.Fuzzy.entries.forEach { item ->
                HorizontalDivider()
                SettingsToggleItem(Icons.TwoTone.Hearing, item.title, item.example,
                    checked = item in enabled, onCheckedChange = { on ->
                        SpellingPatches.setFuzzy(context, item, on)
                        enabled = SpellingPatches.fuzzy(context)
                        scope.launch(Dispatchers.IO) { runCatching { SpellingPatches.apply(context) } }
                    })
            }
        })
    }
}

@Composable
private fun QwertyStaggerSettings(
    settings: com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences,
    onChange: (com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences) -> Unit,
) {
    val modes = com.kingzcheung.xime.ui.keyboard.QwertyStaggerMode.entries
    Column(Modifier.settingsSearchAnchor("三行字母错位").padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("三行字母错位", style = MaterialTheme.typography.titleSmall)
        Text("实体键盘：A 行右移 1/4 键、Z 行右移 3/4 键；手机常见：Z 与 S 上下对齐；按尺寸自动：手机用手机排布，" +
            "平板逐渐过渡到实体排布。分体与 14 键同样遵循。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(108.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEach { mode ->
                IllustratedChoiceCard(mode.label, settings.qwertyStagger == mode,
                    onClick = { onChange(settings.copy(qwertyStagger = mode)) },
                    modifier = Modifier.weight(1f), tag = "qwerty-stagger:${mode.name}") {
                    QwertyStaggerIllustration(when (mode) {
                        com.kingzcheung.xime.ui.keyboard.QwertyStaggerMode.PHYSICAL -> 1f
                        com.kingzcheung.xime.ui.keyboard.QwertyStaggerMode.PHONE -> 0f
                        com.kingzcheung.xime.ui.keyboard.QwertyStaggerMode.CUSTOM -> settings.qwertyStaggerCustom
                        com.kingzcheung.xime.ui.keyboard.QwertyStaggerMode.AUTO -> 0.5f
                    })
                }
            }
        }
        if (settings.qwertyStagger == com.kingzcheung.xime.ui.keyboard.QwertyStaggerMode.CUSTOM) {
            var custom by remember(settings.qwertyStaggerCustom) { mutableStateOf(settings.qwertyStaggerCustom) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("手机", style = MaterialTheme.typography.bodySmall)
                Slider(value = custom, onValueChange = { custom = it },
                    onValueChangeFinished = { onChange(settings.copy(qwertyStaggerCustom = custom)) },
                    valueRange = 0f..1f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        .testTag("qwerty-stagger-custom"))
                Text("实体", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
