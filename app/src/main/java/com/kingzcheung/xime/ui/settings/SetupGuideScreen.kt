package com.kingzcheung.xime.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ui.theme.CyMotion
import com.kingzcheung.xime.model.BuiltinModelCatalog
import com.kingzcheung.xime.model.FuzzyHabit
import com.kingzcheung.xime.model.InputPlan
import com.kingzcheung.xime.model.KeyboardWay
import com.kingzcheung.xime.model.SetupAnswers
import com.kingzcheung.xime.model.SetupFacts
import com.kingzcheung.xime.model.SetupGuide
import com.kingzcheung.xime.model.SetupGuidePlanner
import com.kingzcheung.xime.model.SetupItem
import com.kingzcheung.xime.model.SetupPlan
import com.kingzcheung.xime.model.SetupSource
import com.kingzcheung.xime.model.TypingWay
import com.kingzcheung.xime.model.VoiceUse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 按设备推荐配置：首次设置完成后出现一次，之后从设置首页「配置选择」进入。
 * 先问打字习惯（全拼／双拼哪一种／五笔、26 键／14 键／九键、分不清的读音）和用法（语音、字幕），再按设备情况给出
 * 打字方式要改的几项和要留、要下载、可以删的模型，全部用白话说明；只有点了“按推荐设置”才改设置和删文件。
 *
 * 引导页原则（用户 10-30）：字要大，选项卡要大，选中的那个一目了然（底色、粗边框、打勾三样一起变），
 * 过渡放慢到看得清（[CyMotion.GUIDE]），不靠小圆点或开关位置让人猜选了哪个。
 */
@Composable
fun SetupGuideScreen(firstRun: Boolean, onDone: () -> Unit, onOpenPerformance: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var facts by remember { mutableStateOf<SetupFacts?>(null) }
    var answers by remember { mutableStateOf<SetupAnswers?>(null) }
    var applying by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SetupGuide.Result?>(null) }
    var allowMetered by remember { mutableStateOf(false) }
    var hideDoublePinyin by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val read = withContext(Dispatchers.IO) { SetupGuide.readFacts(context) }
        facts = read
        answers = SetupGuidePlanner.suggest(read)
    }
    val plan = remember(facts, answers) {
        val f = facts; val a = answers
        if (f != null && a != null) SetupGuidePlanner.plan(f, a) else null
    }
    // 默认全部勾上“删掉”；换了回答，方案变了就重新全选。
    var removeIds by remember(plan) { mutableStateOf(plan?.remove?.map { it.id }?.toSet().orEmpty()) }
    SetupGuideContent(
        facts = facts, plan = plan, removeIds = removeIds, allowMetered = allowMetered,
        applying = applying, result = result, firstRun = firstRun,
        hideDoublePinyin = hideDoublePinyin, onHideDoublePinyin = { hideDoublePinyin = it },
        onOpenPerformance = onOpenPerformance,
        onAnswers = { answers = it },
        onToggleRemove = { id, on -> removeIds = if (on) removeIds + id else removeIds - id },
        onAllowMetered = { allowMetered = it },
        onApply = {
            plan?.let { current ->
                applying = true
                scope.launch {
                    result = withContext(Dispatchers.IO) {
                        SetupGuide.apply(context, current, removeIds, allowMetered, hideDoublePinyin)
                    }
                    applying = false
                }
            }
        },
        onSkip = { SetupGuide.markShown(context); onDone() },
        onDone = onDone,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupGuideContent(
    facts: SetupFacts?,
    plan: SetupPlan?,
    removeIds: Set<String>,
    allowMetered: Boolean,
    applying: Boolean,
    result: SetupGuide.Result?,
    firstRun: Boolean,
    onAnswers: (SetupAnswers) -> Unit,
    onToggleRemove: (String, Boolean) -> Unit,
    onAllowMetered: (Boolean) -> Unit,
    onApply: () -> Unit,
    onSkip: () -> Unit,
    onDone: () -> Unit,
    hideDoublePinyin: Boolean = true,
    onHideDoublePinyin: (Boolean) -> Unit = {},
    /** 首次设置时不给：先走完这一页。 */
    onOpenPerformance: (() -> Unit)? = null,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("按设备推荐配置", style = GuideText.heading()) },
                navigationIcon = {
                    if (!firstRun) IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = { if (firstRun && result == null) TextButton(onClick = onSkip) { Text("以后再说", style = GuideText.body()) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text("告诉 Lime 你平时怎么打字、打算怎么用，它会照你的习惯设好打字方式和键盘，" +
                "再看这台设备的情况推荐留哪些模型、删掉哪些用不到的，让应用少占空间。",
                style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (facts == null || plan == null) {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            } else if (result != null) {
                DeviceSection(facts, onOpenPerformance)
                ResultSection(result, onDone)
                Spacer(Modifier.height(24.dp))
            } else {
                // 先问怎么打字（打开就看到），设备情况放到模型推荐前面。
                AnswerSection(facts, plan.answers, onAnswers)
                InputSection(plan.input, hideDoublePinyin, onHideDoublePinyin)
                DeviceSection(facts, onOpenPerformance)
                KeepSection(plan.keep, facts.device)
                if (plan.remove.isNotEmpty()) RemoveSection(plan.remove, removeIds, onToggleRemove)
                SummarySection(facts, plan, removeIds)
                if (facts.metered && facts.online && plan.downloadBytes > 0) GuideCard {
                    GuideSwitchRow(title = "现在用流量下载",
                        subtitle = "要下载 ${SetupGuidePlanner.mbText(plan.downloadBytes)}；不打开就等连上 Wi-Fi 自动下载",
                        checked = allowMetered, tag = "setup-metered", onCheckedChange = onAllowMetered)
                }
                Button(onClick = onApply, enabled = !applying,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("setup-guide-apply")) {
                    if (applying) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("正在整理…", style = GuideText.optionTitle())
                    } else Text("按推荐设置", style = GuideText.optionTitle())
                }
                Text("以后想换，随时可以在 设置 → 配置选择 → 按设备推荐配置 重新选；删掉的模型在用到时会提示下载。",
                    style = GuideText.note(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 引导页字号：比普通设置页大一档。 */
private object GuideText {
    @Composable fun heading(): TextStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
    @Composable fun optionTitle(): TextStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold)
    @Composable fun body(): TextStyle = MaterialTheme.typography.bodyLarge
    @Composable fun note(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp)
}

@Composable
private fun GuideHeading(text: String) {
    Text(text, style = GuideText.heading(), color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun GuideCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().animateContentSize(tween(CyMotion.GUIDE, easing = CyMotion.Standard)), content = content)
    }
}

@Composable
private fun DeviceSection(facts: SetupFacts, onOpenPerformance: (() -> Unit)?) {
    GuideHeading("这台${facts.device}")
    GuideCard {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 和「性能与省电」的“本机配置推荐”同一个判断、同一个叫法。
            Text(tierNames.getValue(facts.performanceTier) +
                if (facts.performanceTier == com.kingzcheung.xime.runtime.adaptation.PerformanceTier.LOW) "，优先省内存" else "",
                style = GuideText.optionTitle())
            val hardware = buildList {
                facts.memoryBytes?.let { add("内存 ${(it + SetupGuidePlanner.GIB / 2) / SetupGuidePlanner.GIB} GB") }
                facts.cores?.let { add("$it 核") }
                facts.maxCpuKHz?.let { add("最高 ${"%.1f".format(it / 1_000_000.0)} GHz") }
            }
            if (hardware.isNotEmpty()) FactLine(hardware.joinToString("，"))
            facts.soc?.let { FactLine("处理器 $it") }
            facts.freeBytes?.let { FactLine("剩余空间 ${SetupGuidePlanner.gbText(it)}") }
            FactLine("网络 " + when { !facts.online -> "未联网"; facts.metered -> "移动数据"; else -> "Wi-Fi" })
            facts.language?.let { FactLine("系统语言 $it") }
        }
        onOpenPerformance?.let { open ->
            GuideDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag("setup-open-performance")
                .clickable(role = Role.Button, onClick = open).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("性能与省电", style = GuideText.optionTitle())
                    Text("按键动效、炫光和语音模型常驻多久", style = GuideText.body(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FactLine(text: String) {
    Text(text, style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * 一个大选项卡：至少 76dp 高，整张卡可点。选中时底色、2dp 主题色边框和右侧打勾一起慢慢变过去，
 * 没选中的是浅底、细边框、空心圆；读屏按单选按钮报“已选中”。
 */
@Composable
private fun GuideOption(selected: Boolean, title: String, subtitle: String, tag: String, multi: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) colors.primaryContainer else colors.surfaceContainerLow,
        tween(CyMotion.GUIDE, easing = CyMotion.Standard), label = "guide-option-container")
    val edge by animateColorAsState(if (selected) colors.primary else colors.outlineVariant,
        tween(CyMotion.GUIDE, easing = CyMotion.Standard), label = "guide-option-edge")
    val edgeWidth by animateDpAsState(if (selected) 2.dp else 1.dp, tween(CyMotion.GUIDE, easing = CyMotion.Standard),
        label = "guide-option-edge-width")
    val content by animateColorAsState(if (selected) colors.onPrimaryContainer else colors.onSurface,
        tween(CyMotion.GUIDE, easing = CyMotion.Standard), label = "guide-option-content")
    val shape = RoundedCornerShape(16.dp)
    Surface(shape = shape, color = container, border = BorderStroke(edgeWidth, edge),
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag(tag).clip(shape)
            .then(if (multi) Modifier.toggleable(selected, role = Role.Checkbox, onValueChange = { onClick() })
                else Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick))) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = GuideText.optionTitle(), color = content)
                Text(subtitle, style = GuideText.body(), color = content.copy(alpha = 0.8f))
            }
            Spacer(Modifier.width(16.dp))
            CheckBadge(selected)
        }
    }
}

@Composable
private fun CheckBadge(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (selected) colors.primary else Color.Transparent,
        tween(CyMotion.GUIDE, easing = CyMotion.Standard), label = "guide-check-fill")
    val ring by animateColorAsState(if (selected) colors.primary else colors.outline,
        tween(CyMotion.GUIDE, easing = CyMotion.Standard), label = "guide-check-ring")
    Box(Modifier.size(32.dp).clip(CircleShape).background(fill).border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center) {
        AnimatedVisibility(selected,
            enter = scaleIn(tween(CyMotion.GUIDE, easing = CyMotion.Enter), initialScale = 0.4f) + fadeIn(tween(CyMotion.GUIDE)),
            exit = scaleOut(tween(CyMotion.GUIDE, easing = CyMotion.Exit), targetScale = 0.4f) + fadeOut(tween(CyMotion.GUIDE))) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(22.dp))
        }
    }
}

/** 一个问题：标题（可带一段说明）＋竖排的大选项卡（不并排，手机上每张卡都够宽）。 */
@Composable
private fun <T> GuideQuestion(title: String, options: List<Triple<T, String, String>>, selected: T, tagPrefix: String,
    note: String? = null, onSelect: (T) -> Unit) {
    GuideHeading(title)
    note?.let { Text(it, style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (value, optionTitle, subtitle) ->
            GuideOption(selected == value, optionTitle, subtitle, "$tagPrefix:$value") { onSelect(value) }
        }
    }
}

@Composable
private fun AnswerSection(facts: SetupFacts, answers: SetupAnswers, onAnswers: (SetupAnswers) -> Unit) {
    GuideQuestion("打中文用哪种方式？", listOfNotNull(
        Triple(TypingWay.FULL_PINYIN, "全拼", "拼音全部打出来，如“中国”打 zhongguo；不用记键位"),
        Triple(TypingWay.DOUBLE_PINYIN, "双拼", "每个字只按两个键，如小鹤双拼“中国”打 vsgo；打得快，要先记键位"),
        Triple(TypingWay.WUBI, "五笔", "按字形拆成字根打，不看读音；" +
            if (facts.wubiInstalled || facts.typing == TypingWay.WUBI) "五笔方案已在${facts.device}上" else "要下载五笔86方案，约 2 MB"),
        Triple(TypingWay.KEEP, "保持现在的${facts.typingName}", "不改打字方式").takeIf { facts.typing == TypingWay.KEEP },
    ), answers.typing, "setup-typing") { onAnswers(answers.copy(typing = it)) }
    if (answers.typing == TypingWay.DOUBLE_PINYIN && facts.doublePinyinChoices.isNotEmpty()) {
        GuideQuestion("用哪一种双拼？", facts.doublePinyinChoices.map { choice ->
            Triple(choice.id, choice.name, "“双拼”两个字打 ${choice.example}" + if (choice.builtin) "" else "（你自己建的）")
        }, answers.doublePinyin, "setup-double-pinyin") { onAnswers(answers.copy(doublePinyin = it)) }
    }
    val keyboards = SetupGuidePlanner.keyboards(facts, answers)
    val keyboard = SetupGuidePlanner.keyboardOf(facts, answers)
    if (keyboards.isNotEmpty()) {
        val double = answers.typing == TypingWay.DOUBLE_PINYIN
        GuideQuestion("用哪种键盘打？", keyboards.map { way ->
            Triple(way, if (way == KeyboardWay.KEEP) "保持现在的${facts.keyboardName.ifEmpty { "键盘" }}" else way.title, when (way) {
                KeyboardWay.QWERTY -> "一个字母一个键，和电脑键盘一样" + if (double) "；每个字按两个键" else ""
                KeyboardWay.K14 -> "两个字母合用一个键，键更大、更好按；点按自动组词，左右滑选单个字母"
                KeyboardWay.T9 -> if (double) "按双拼键所在的数字键，每个字按两下；适合单手" else "按字母所在的数字键，像老手机；适合单手"
                KeyboardWay.KEEP -> "不换键盘"
            })
        }, keyboard, "setup-keyboard",
            note = "以后随时可以在键盘上的「输入方式」里换。".takeIf { keyboards.size > 1 }
                ?: "${facts.doublePinyinChoices.firstOrNull { it.id == answers.doublePinyin }?.name ?: "这种双拼"}只有 26 键（ing 在分号键上，14 键、九键没有分号键）。") {
            onAnswers(answers.copy(keyboard = it))
        }
    }
    if (answers.typing == TypingWay.FULL_PINYIN) {
        // 多选：可以同时分不清几组；“都分得清”和其他互斥，让“一个都不选”也是看得见的选择。
        GuideHeading("有分不清的读音吗？")
        Text("选上的打开模糊音，两边的读音都能打出对方的字，候选会多一些近音字。只对全拼 26 键起作用。",
            style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GuideOption(answers.fuzzy.isEmpty(), "都分得清", "不打开模糊音，候选最准", "setup-fuzzy:none") {
                onAnswers(answers.copy(fuzzy = emptySet()))
            }
            FuzzyHabit.entries.forEach { habit ->
                GuideOption(habit in answers.fuzzy, habit.title, habit.example, "setup-fuzzy:${habit.name}", multi = true) {
                    onAnswers(answers.copy(fuzzy = if (habit in answers.fuzzy) answers.fuzzy - habit else answers.fuzzy + habit))
                }
            }
        }
    }
    // 选了九键就不再问一遍：全拼九键要用九键整句组词模型（自动留下），双拼九键只用双拼词库、用不到它。
    if (keyboard != KeyboardWay.T9) GuideQuestion("也用九键打字吗？", listOf(
        Triple(true, "用九键", "九键按整句常用说法组词，长句更容易一次打对；需要九键整句组词模型"),
        Triple(false, "不用九键", "只用 26 键、14 键或手写，不留九键模型"),
    ), answers.t9, "setup-t9") { onAnswers(answers.copy(t9 = it)) }
    GuideQuestion("语音输入打算怎么用？", listOf(
        Triple(VoiceUse.OFTEN, "经常用，想边说边看到字", "说话时字就跟着出来，停下后再自动改错、加标点"),
        Triple(VoiceUse.SOMETIMES, "偶尔用，说完出字就行", "说完一句再出字，错字少、带标点，占的空间少一半"),
        Triple(VoiceUse.NEVER, "不用语音", "关掉语音输入，不留语音模型（字幕要用的除外）"),
    ), answers.voice, "setup-voice") { onAnswers(answers.copy(voice = it)) }
    val speaker = facts.sizes[BuiltinModelCatalog.SPEAKER_MODEL]?.takeIf { it > 0 }?.let { "（${SetupGuidePlanner.mbText(it)}）" }.orEmpty()
    val icon = when {
        !facts.captionIconChangeable -> "桌面上的「字幕」图标保留"
        facts.usesCaption -> "桌面上已有「字幕」图标，点一下就打开"
        else -> "桌面上加一个「字幕」图标（系统会问一次要不要添加），点一下就打开"
    }
    GuideQuestion("要用「字幕」吗？", listOf(
        Triple(true, "用字幕", "$icon；键盘菜单里也能进。会留下分清谁在说话的模型$speaker"),
        Triple(false, "不用字幕", (if (facts.captionIconChangeable) "桌面上不放「字幕」图标（已有的会变灰，可长按移除），" else "") +
            "删掉分清谁在说话的模型。以后想用，在 设置 → 语音转文本 → 字幕 打开"),
    ), answers.caption, "setup-caption",
        note = "「字幕」是 Lime 里单独的一个页面：把身边的人说的话实时变成大字显示在屏幕上，还能分清是谁在说。" +
            "适合开会、上课，或和听力不好的人当面说话。用不用都不影响打字和语音输入。") {
        onAnswers(answers.copy(caption = it))
    }
}

/** 打字方式这一块要改的：每项一句；“方案栏里藏起用不到的双拼”单独一行开关，默认开。 */
@Composable
private fun InputSection(input: InputPlan, hideDoublePinyin: Boolean, onHide: (Boolean) -> Unit) {
    if (input.lines.isEmpty() && input.hideLine == null) return
    GuideHeading("打字方式会这样改")
    GuideCard {
        if (input.lines.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            input.lines.forEach { Text(it, style = GuideText.body()) }
        }
        input.hideLine?.let { line ->
            if (input.lines.isNotEmpty()) GuideDivider()
            GuideSwitchRow(title = "方案栏只留用得到的", subtitle = line, checked = hideDoublePinyin,
                tag = "setup-hide-double-pinyin", onCheckedChange = onHide)
        }
    }
}

@Composable
private fun GuideDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

@Composable
private fun KeepSection(items: List<SetupItem>, device: String) {
    GuideHeading("推荐留下的")
    GuideCard {
        items.forEachIndexed { index, item ->
            if (index > 0) GuideDivider()
            Row(Modifier.fillMaxWidth().testTag("setup-keep:${item.id}").padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, style = GuideText.optionTitle())
                    Text(item.reason, style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.modelName.isNotEmpty()) Text("设置里叫「${item.modelName}」", style = GuideText.note(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                }
                Spacer(Modifier.width(12.dp))
                val size = SetupGuidePlanner.mbText(item.bytes)
                Text(when (item.source) {
                    SetupSource.ON_PHONE -> "已在${device}上\n$size"
                    SetupSource.IN_PACKAGE -> "从安装包取出\n$size"
                    SetupSource.DOWNLOAD -> "要下载\n$size"
                }, style = GuideText.note(),
                    color = if (item.source == SetupSource.DOWNLOAD) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End)
            }
        }
    }
}

/** 标题＋说明＋开关的整行（设置页规则），字号放大到引导页这一档；说明和开关之间留 12dp。 */
@Composable
private fun GuideSwitchRow(title: String, subtitle: String, checked: Boolean, tag: String, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag(tag)
        .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = GuideText.optionTitle())
            Text(subtitle, style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RemoveSection(items: List<SetupItem>, removeIds: Set<String>, onToggle: (String, Boolean) -> Unit) {
    GuideHeading("可以删掉的")
    GuideCard {
        items.forEachIndexed { index, item ->
            if (index > 0) GuideDivider()
            val saving = SetupGuidePlanner.mbText(item.bytes)
            GuideSwitchRow(title = item.title,
                subtitle = buildString {
                    append(item.reason)
                    append(if (item.source == SetupSource.ON_PHONE) "删掉省 $saving。" else "不从安装包解压，省 $saving。")
                    if (item.modelName.isNotEmpty()) append("设置里叫「${item.modelName}」。")
                },
                checked = item.id in removeIds, tag = "setup-remove:${item.id}", onCheckedChange = { onToggle(item.id, it) })
        }
    }
}

@Composable
private fun SummarySection(facts: SetupFacts, plan: SetupPlan, removeIds: Set<String>) {
    val now = facts.installed.values.sum()
    val removed = plan.remove.filter { it.id in removeIds && it.source == SetupSource.ON_PHONE }.sumOf { it.bytes }
    val download = plan.keep.filter { it.source == SetupSource.DOWNLOAD }.sumOf { it.bytes }
    val unpack = plan.keep.filter { it.source == SetupSource.IN_PACKAGE }.sumOf { it.bytes }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(SetupGuidePlanner.sizeSummary(now, download, unpack, removed), style = GuideText.optionTitle())
        plan.notes.forEach {
            Text(it, style = GuideText.body(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultSection(result: SetupGuide.Result, onDone: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("✓ 已按推荐设置好", style = GuideText.heading(), color = MaterialTheme.colorScheme.onPrimaryContainer)
            val lines = buildList {
                if (result.removed > 0) add("删掉了 ${result.removed} 个用不到的模型，空出 ${SetupGuidePlanner.mbText(result.freedBytes)}。")
                // 写明是哪些模型在下载（语音、联想…），不只写多少 MB。
                if (result.downloading > 0) add(if (result.waitingForWifi)
                    "${SetupGuidePlanner.kindsText(result.downloadingIds)}还要下载 ${SetupGuidePlanner.mbText(result.downloading)}，连上 Wi-Fi 后自动下载。"
                    else "${SetupGuidePlanner.kindsText(result.downloadingIds)}正在后台下载（${SetupGuidePlanner.mbText(result.downloading)}），下好就能用。")
                if (result.unpackingIds.isNotEmpty())
                    add("${SetupGuidePlanner.kindsText(result.unpackingIds)}正在从安装包取出，取好就能用。")
                if (result.failed.isNotEmpty()) add("这些没删成：${result.failed.joinToString("、")}，可以在 扩展 → 已安装的模型 里再删。")
                addAll(result.inputDone)
                addAll(result.inputFailed)
            }
            lines.forEach { Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer, style = GuideText.body()) }
        }
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("完成", style = GuideText.optionTitle()) }
}
