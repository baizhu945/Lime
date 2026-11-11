package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.twotone.Dialpad
import androidx.compose.material.icons.twotone.FormatListNumbered
import androidx.compose.material.icons.twotone.FormatColorFill
import androidx.compose.material.icons.twotone.AspectRatio
import androidx.compose.material.icons.twotone.DataObject
import androidx.compose.material.icons.twotone.EmojiSymbols
import androidx.compose.material.icons.twotone.DashboardCustomize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Straighten
import androidx.compose.material.icons.twotone.ViewWeek
import androidx.compose.material.icons.twotone.Padding
import androidx.compose.material.icons.twotone.Keyboard
import androidx.compose.material.icons.twotone.Lightbulb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.SettingsPreferences

/** 「键盘布局」里各布局一行的图标：26 键用键盘，九键与 14 键用拨号盘。 */
private val LayoutInputCategory.icon: androidx.compose.ui.graphics.vector.ImageVector
    get() = when (this) {
        LayoutInputCategory.Qwerty -> Icons.TwoTone.Keyboard
        LayoutInputCategory.Nine -> Icons.TwoTone.Dialpad
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutDisplaySettingsContent(
    onBack: () -> Unit,
    onOpenLayout: (String) -> Unit = {},
    /** 首页或设置搜索直接打开某个子页；返回回到进来的地方。 */
    initialPage: String? = null,
) {
    // One index page; each group opens as its own sub-page. Settings themselves are unchanged.
    var openPage by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(initialPage?.takeIf { p -> LayoutDisplayPages.any { it.first == p } })
    }
    // 首页直接进入的子页（候选词、按键与手势）返回首页，不经过「键盘布局」。
    val direct = initialPage != null
    SettingsSubPages(openPage, onBack = { if (direct) onBack() else openPage = null }) { page ->
        LayoutDisplayPage(page, onBack, onOpenLayout, openPage = { openPage = it },
            closePage = { if (direct) onBack() else openPage = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayoutDisplayPage(
    page: String?,
    onBack: () -> Unit,
    onOpenLayout: (String) -> Unit,
    openPage: (String) -> Unit,
    closePage: () -> Unit,
) {
    val context = LocalContext.current
    val pageTitle = LayoutDisplayPages.firstOrNull { it.first == page }?.second

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(pageTitle ?: "键盘布局") },
                navigationIcon = {
                    IconButton(onClick = { if (page != null) closePage() else onBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        val listState = rememberSettingsSearchListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.settingsGutterScroll(listState)
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .testTag("layout-display-list"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (page == null) item {
                SettingsSection(title = "各布局", content = {
                    LayoutInputCategory.entries.forEachIndexed { index, category ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        SettingsItem(category.icon, category.title, category.description,
                            onClick = { onOpenLayout(category.route) }, showArrow = true)
                    }
                })
            }
            if (page == null) item {
                SettingsSection(title = "布局与符号", content = {
                    SettingsItem(Icons.TwoTone.DashboardCustomize, "自定义布局", "新建、复制和编辑按键排列",
                        onClick = { context.startActivity(android.content.Intent(context, com.kingzcheung.xime.CustomLayoutActivity::class.java)) },
                        showArrow = true)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    SettingsItem(Icons.TwoTone.EmojiSymbols, "按键符号", "26 键、14 键和自定义布局的上下角标，九键与笔画侧栏、手写和常用符号栏，一处按位置编辑",
                        onClick = { context.startActivity(android.content.Intent(context, com.kingzcheung.xime.CustomLayoutActivity::class.java)
                            .putExtra("edit_symbols", true)) },
                        showArrow = true)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    var pairedSymbols by remember { mutableStateOf(SettingsPreferences.isPairedSymbolsEnabled(context)) }
                    SettingsToggleItem(Icons.TwoTone.DataObject, "输入成对符号",
                        "点 “ （ 《 【 等开头的一半时连结尾一起输入，光标停在中间；接着点结尾会跳过已有的那个，" +
                            "退格删掉空的一对。所有屏幕键盘、符号面板和侧栏都一样，实体键盘不变。",
                        checked = pairedSymbols, onCheckedChange = {
                            pairedSymbols = it; SettingsPreferences.setPairedSymbolsEnabled(context, it)
                        })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val split = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().splitKeyboardEnabled
                    SettingsToggleItem(SplitKeyboardIcon, "分体键盘",
                        "适用于 26 键和 14 键；14 键跨中线的双字母键分体时拆成两个单字母键。间距与尺寸在键盘调整面板中设置。",
                        checked = split, onCheckedChange = { SettingsPreferences.setSplitKeyboardEnabled(context, it) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val rightG = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().splitRightG
                    SettingsToggleItem(Icons.TwoTone.Keyboard, "右半边也放 G 键",
                        "26 键分体时，右半 h j k l 那排开头再加一个 G，这一排向右对齐到 p 下面，两只手都能按 G。只改分体键盘，14 键和不分体的键盘不变。",
                        checked = rightG, onCheckedChange = { SettingsPreferences.setSplitRightGEnabled(context, it) },
                        lockMessage = if (split) null else "打开上面的「分体键盘」后可用")
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val filled = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().fillBlankAreas
                    SettingsToggleItem(Icons.TwoTone.FormatColorFill, "填满留空处",
                        "分体中缝和固定键盘两侧的空白用键盘背景铺满，遮住后面的应用；关闭时镂空，透出后面的内容。",
                        checked = filled, onCheckedChange = { SettingsPreferences.setKeyboardBlankFilled(context, it) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val wideKeys = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().wideKeys
                    SettingsToggleItem(Icons.TwoTone.Straighten, "加宽按键",
                        "键盘两侧留白和按键之间的横向缝变窄，每个键更宽，手机上不容易按到旁边的键；分体键盘不变。",
                        checked = wideKeys, onCheckedChange = { SettingsPreferences.setWideKeysEnabled(context, it) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    val evenMerged = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().evenMergedKeys
                    SettingsToggleItem(Icons.TwoTone.ViewWeek, "合并键均匀排布",
                        "14 键和自己排的合并键布局（如 17、18、20 键）里，合并键只比单字母键宽三分之一，单字母键跟着变宽，" +
                            "每排更匀，小屏上好按。关闭时合并键严格占两个 26 键的位置。分体键盘不变。",
                        checked = evenMerged, onCheckedChange = { SettingsPreferences.setEvenMergedKeysEnabled(context, it) },
                        // 分体按 26 键中线拆左右，均匀排布在分体时不起作用：置灰并说明，不让它看着开着却没用。
                        lockMessage = if (split) "分体键盘开着时不起作用，关掉上面的「分体键盘」后可用" else null)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    SettingsItem(Icons.TwoTone.AspectRatio, keyboardPanelTitle(com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Panel.RESIZE),
                        "高度、宽度、悬浮与分体、透明度，打开键盘菜单里同一个调节面板",
                        onClick = { onOpenLayout(SettingsRoutes.KeyboardResize) }, showArrow = true)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    SettingsItem(Icons.TwoTone.Padding, keyboardPanelTitle(com.kingzcheung.xime.keyboard.KeyboardPanelLaunch.Panel.ARRANGE),
                        "哪些入口放工具栏、哪些放菜单，以及先后顺序",
                        onClick = { onOpenLayout(SettingsRoutes.ToolbarArrange) }, showArrow = true)
                })
            }
            if (page == null) item {
                SettingsSection(title = "数字键盘", content = {
                    var autoNumberKeyboard by remember {
                        mutableStateOf(SettingsPreferences.isAutoNumberKeyboardEnabled(context))
                    }
                    Row(
                        modifier = Modifier.settingsSearchAnchor("数字框自动数字键盘")
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "数字框自动数字键盘",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "进入号码、验证码等数字输入框时自动弹出数字键盘",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoNumberKeyboard,
                            onCheckedChange = { newValue ->
                                autoNumberKeyboard = newValue
                                SettingsPreferences.setAutoNumberKeyboardEnabled(context, newValue)
                            }
                        )
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // 数字行（默认关）：点开关打开，点这一行进「按键符号 → 数字行」改键和顺序。
                    val numberRow = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().numberRow
                    SettingsToggleItem(Icons.TwoTone.FormatListNumbered, "数字行",
                        "26 键、14 键字母上方加一排数字，键盘总高度不变、字母键变矮。点这一行改每个键和顺序。",
                        checked = numberRow, onCheckedChange = { SettingsPreferences.setNumberRowEnabled(context, it) },
                        onClick = { com.kingzcheung.xime.ui.keyboard.openSideSymbolEditor(context,
                            com.kingzcheung.xime.ui.keyboard.SideSymbolTarget.NUMBER_ROW) },
                        showArrow = true)
                })
            }
            if (page == "candidates") item {
                SettingsSection(title = "候选词", content = {
                    val candidateTextSizePref = SettingsPreferences.getCandidateTextSize(context)
                    var candidateTextSize by remember(candidateTextSizePref) {
                        mutableStateOf(candidateTextSizePref.toFloat())
                    }

                    Column(
                        modifier = Modifier.settingsSearchAnchor("候选字大小").padding(16.dp)
                    ) {
                        Text(
                            text = "候选字大小",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        CandidateTextSizeCard(
                            candidateTextSize = candidateTextSize,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Slider(
                            value = candidateTextSize,
                            onValueChange = { candidateTextSize = it },
                            onValueChangeFinished = {
                                SettingsPreferences.setCandidateTextSize(context, candidateTextSize.toInt())
                            },
                            valueRange = 12f..22f,
                            steps = 9
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    var wideCandidateSpacing by remember {
                        mutableStateOf(SettingsPreferences.isWideCandidateSpacing(context))
                    }
                    Row(
                        modifier = Modifier.settingsSearchAnchor("候选词间距加宽")
                            .fillMaxWidth()
                            .testTag("wide-candidate-spacing-setting")
                            .toggleable(
                                value = wideCandidateSpacing,
                                role = Role.Switch,
                                onValueChange = {
                                    wideCandidateSpacing = it
                                    SettingsPreferences.setWideCandidateSpacing(context, it)
                                },
                            )
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("候选词间距加宽", style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium)
                            Text("候选栏里候选之间空出约一个字，单字候选不挤在一起",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = wideCandidateSpacing, onCheckedChange = null)
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    var showCandidateCancel by remember {
                        mutableStateOf(SettingsPreferences.shouldShowCandidateCancelButton(context))
                    }
                    // 图例放在开关下面，随开关实时变化；整块都可点。
                    Column(
                        modifier = Modifier.settingsSearchAnchor("候选栏取消按钮")
                            .fillMaxWidth()
                            .testTag("candidate-cancel-setting")
                            .toggleable(
                                value = showCandidateCancel,
                                role = Role.Switch,
                                onValueChange = {
                                    showCandidateCancel = it
                                    SettingsPreferences.setShowCandidateCancelButton(context, it)
                                },
                            )
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("候选栏取消按钮", style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium)
                                Text("点候选栏最左边的左箭头，取消当前输入",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(checked = showCandidateCancel, onCheckedChange = null)
                        }
                        Spacer(Modifier.height(10.dp))
                        CandidateCancelIllustration(showCandidateCancel)
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    // 只在取消按钮开着时起作用，关着就不显示。
                    if (showCandidateCancel) {
                        var associationCancelRight by remember {
                            mutableStateOf(SettingsPreferences.isAssociationCancelOnRight(context))
                        }
                        Row(
                            modifier = Modifier.settingsSearchAnchor("联想时关闭键放右边")
                                .fillMaxWidth()
                                .testTag("association-cancel-right-setting")
                                .toggleable(
                                    value = associationCancelRight,
                                    role = Role.Switch,
                                    onValueChange = {
                                        associationCancelRight = it
                                        SettingsPreferences.setAssociationCancelOnRight(context, it)
                                    },
                                )
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("联想时关闭键放右边", style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium)
                                Text("上屏后出联想词时，左箭头换成右边的 ⊗，点第一个联想词不会点到关闭",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(checked = associationCancelRight, onCheckedChange = null)
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }

                    var commentMode by remember { mutableStateOf(SettingsPreferences.candidateCommentMode(context)) }
                    Column(Modifier.settingsSearchAnchor("候选注释")) {
                        Text("候选注释", style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
                        Text("26／14／九键和手写共用；自动：宽时显示、窄时隐藏，手写里照样显示。中文手写的注释总是读音。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp))
                        Row(Modifier.fillMaxWidth().height(136.dp).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.kingzcheung.xime.settings.CandidateCommentMode.entries.forEach { mode ->
                                IllustratedChoiceCard(when (mode) {
                                    com.kingzcheung.xime.settings.CandidateCommentMode.AUTO -> "自动"
                                    com.kingzcheung.xime.settings.CandidateCommentMode.SHOW -> "显示"
                                    com.kingzcheung.xime.settings.CandidateCommentMode.HIDE -> "隐藏"
                                }, commentMode == mode,
                                    onClick = {
                                        commentMode = mode
                                        SettingsPreferences.setCandidateCommentMode(context, mode)
                                        com.kingzcheung.xime.gloss.CandidateGlossary.prepare(context)
                                    },
                                    modifier = Modifier.weight(1f), tag = "candidate-comments:${mode.name}") {
                                    CandidateCommentIllustration(mode)
                                }
                            }
                        }
                    }

                    // 注释隐藏时内容选项无意义：整段不显示，也不会触发释义词典下载。
                    if (commentMode != com.kingzcheung.xime.settings.CandidateCommentMode.HIDE) {
                        var commentContent by remember { mutableStateOf(SettingsPreferences.candidateCommentContent(context)) }
                        Column(Modifier.settingsSearchAnchor("候选注释内容")) {
                            Text("候选注释内容", style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
                            Text("释义：中文候选显示英文，混出的英文候选显示中文，九键长按「下一项」或按住 Shift 再选候选就上屏英文单词；选释义会自动下载释义词典，并把上面的「自动」改成「显示」，查不到的候选仍显示拼音。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp))
                            var glossDownloadRequest by remember { mutableStateOf(0) }
                            if (commentContent == com.kingzcheung.xime.settings.CandidateCommentContent.GLOSS) {
                                GlossDictionaryStatus(glossDownloadRequest)
                            }
                            Row(Modifier.fillMaxWidth().height(136.dp).padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                com.kingzcheung.xime.settings.CandidateCommentContent.entries.forEach { content ->
                                    IllustratedChoiceCard(when (content) {
                                        com.kingzcheung.xime.settings.CandidateCommentContent.PINYIN -> "拼音"
                                        com.kingzcheung.xime.settings.CandidateCommentContent.GLOSS -> "释义"
                                    }, commentContent == content,
                                        onClick = {
                                            // 刚选释义且词典没下载：直接开始下载，不让用户去商店找。
                                            if (content == com.kingzcheung.xime.settings.CandidateCommentContent.GLOSS &&
                                                commentContent != content) glossDownloadRequest++
                                            // 「自动」在手机竖屏（候选栏不到 480dp）不显示注释，选了释义也看不到英文（B 站反馈 10-31）。
                                            // 选释义就是要看释义：自动改成显示，上面的选择跟着变，想窄屏隐藏可以再改回自动。
                                            if (content == com.kingzcheung.xime.settings.CandidateCommentContent.GLOSS &&
                                                commentMode == com.kingzcheung.xime.settings.CandidateCommentMode.AUTO) {
                                                commentMode = com.kingzcheung.xime.settings.CandidateCommentMode.SHOW
                                                SettingsPreferences.setCandidateCommentMode(context, commentMode)
                                            }
                                            commentContent = content
                                            SettingsPreferences.setCandidateCommentContent(context, content)
                                            com.kingzcheung.xime.gloss.CandidateGlossary.prepare(context)
                                        },
                                        modifier = Modifier.weight(1f), tag = "candidate-comment-content:${content.name}") {
                                        CandidateCommentContentIllustration(content)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    var inputTextLocation by remember {
                        mutableStateOf(SettingsPreferences.getInputTextLocation(context))
                    }

                    Column(Modifier.settingsSearchAnchor("编码显示")) {
                        Text(
                            text = "编码显示",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                        )
                        Text(
                            text = "选择输入编码的显示位置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CodeDisplayCard(
                                title = "显示在输入框",
                                isSelected = inputTextLocation == SettingsPreferences.INPUT_TEXT_INPUT_BOX,
                                showCodeInInputBox = true,
                                onClick = {
                                    inputTextLocation = SettingsPreferences.INPUT_TEXT_INPUT_BOX
                                    SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_INPUT_BOX)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            CodeDisplayCard(
                                title = "显示在候选栏",
                                isSelected = inputTextLocation == SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR,
                                showCodeInInputBox = false,
                                onClick = {
                                    inputTextLocation = SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR
                                    SettingsPreferences.setInputTextLocation(context, SettingsPreferences.INPUT_TEXT_CANDIDATE_BAR)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    val pageSizePref = SettingsPreferences.getPageSize(context)
                    val effectiveValue = if (pageSizePref == 0) 20f else pageSizePref.toFloat()
                    var pageSizeSlider by remember(effectiveValue) {
                        mutableStateOf(effectiveValue)
                    }

                    Column(
                        modifier = Modifier.settingsSearchAnchor("每页候选词数").padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "每页候选词数",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${pageSizeSlider.toInt()} 个",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = pageSizeSlider,
                            onValueChange = { pageSizeSlider = it },
                            onValueChangeFinished = {
                                val intValue = pageSizeSlider.toInt()
                                SettingsPreferences.setPageSize(context, intValue)
                            },
                            valueRange = 20f..50f,
                            steps = 29
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "下次开始输入时生效，无需重新部署",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                })
            }

            if (page == "key_tips") item { KeyTipsSettings() }
            if (page == "gestures") item { GesturePresetSettings() }
            if (page == "gestures") item {
                InputExperienceSettings(setOf(InputExperiencePart.SYMBOLS), "角标与长按")
            }
            if (page == "gestures") item { InputExperienceSettings(setOf(InputExperiencePart.CURSOR), "光标与空格") }
            // 大多数人不会去调的时长、距离滑条收在一起，默认收起（用户 10-31）；设置搜索点中会自动展开。
            if (page == "gestures") item {
                InputExperienceSettings(setOf(InputExperiencePart.TIMING), "时长与距离",
                    collapsedSummary = "长按空格、光标移动距离、长按语言键、!@# 松手时限")
            }
            // 隐藏操作的说明（用户 10-31）：这一页最下面一行，点进去是「按键技巧」；它不是设置，不占上面的位置。
            if (page == "gestures") item {
                SettingsSection(title = "说明", searchKey = "按键技巧入口", content = {
                    SettingsItem(Icons.TwoTone.Lightbulb, "按键技巧",
                        "Shift、Ctrl（!@#）、空格、删除键的隐藏操作", showArrow = true,
                        onClick = { onOpenLayout("${SettingsRoutes.LayoutDisplay}?page=key_tips") })
                })
            }
        }
    }
}

/** 「键盘布局」之外直接从首页进入的两页：route, title, one-line description. */
internal val LayoutDisplayPages = listOf(
    Triple("candidates", "候选词", "字号、候选注释（拼音或中英释义）、取消按钮、输入框或候选栏显示"),
    Triple("gestures", "按键与手势", "角标输入方式、上下滑方向、长按语言键、滑动移动光标、长按空格"),
    // 不在首页列出，从「按键与手势」最下面进入。
    Triple("key_tips", "按键技巧", "Shift、Ctrl（!@#）、空格、删除键的隐藏操作"),
)

/** 按键显示：字号、角标提示、点按气泡、顶部和底部圆角；放在「主题与外观」。 */
@Composable
internal fun KeyboardAppearanceSettings() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        InputExperienceSettings(setOf(InputExperiencePart.KEY_TEXT), "按键文字")
                SettingsSection(title = "角标与气泡", content = {
                    // 和「双拼键位提示」互锁（InputGestureLocks）：提示写在按键角上，开着时两个角标提示都关着不能开。
                    val cornerLock = com.kingzcheung.xime.ui.keyboard.InputGestureLocks.lockFor(
                        com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences(),
                        com.kingzcheung.xime.ui.keyboard.InputGestureLock.Option.CORNER_HINTS, true)
                    var swipeUpEnabled by remember {
                        mutableStateOf(SettingsPreferences.isSwipeUpHintsEnabled(context))
                    }
                    Row(
                        modifier = Modifier.settingsSearchAnchor("上方角标提示")
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "上方角标提示",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "输入方式和滑动方向在「按键与手势」设置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (cornerLock != null && !swipeUpEnabled) SettingsLockNote(cornerLock.message)
                        }
                        Switch(
                            checked = swipeUpEnabled,
                            enabled = cornerLock == null || swipeUpEnabled,
                            onCheckedChange = { newValue ->
                                swipeUpEnabled = newValue
                                SettingsPreferences.setSwipeUpHintsEnabled(context, newValue)
                            }
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    var swipeDownEnabled by remember {
                        mutableStateOf(SettingsPreferences.isSwipeDownHintsEnabled(context))
                    }
                    Row(
                        modifier = Modifier.settingsSearchAnchor("下方角标提示")
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "下方角标提示",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "输入方式和滑动方向在「按键与手势」设置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (cornerLock != null && !swipeDownEnabled) SettingsLockNote(cornerLock.message)
                        }
                        Switch(
                            checked = swipeDownEnabled,
                            enabled = cornerLock == null || swipeDownEnabled,
                            onCheckedChange = { newValue ->
                                swipeDownEnabled = newValue
                                SettingsPreferences.setSwipeDownHintsEnabled(context, newValue)
                            }
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    val showPressBubble = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().showPressBubble
                    Row(
                        modifier = Modifier.settingsSearchAnchor("点按弹出气泡")
                            .fillMaxWidth()
                            .toggleable(value = showPressBubble, role = Role.Switch) {
                                SettingsPreferences.setShowPressBubble(context, it)
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "点按弹出气泡",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "点按时显示按下的字符，多一点动画开销",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = showPressBubble, onCheckedChange = null)
                    }
                })
                SettingsSection(title = "键盘顶部样式", content = {
                    var roundedTop by remember { mutableStateOf(SettingsPreferences.roundedKeyboardTop(context)) }
                    // 玻璃「柔化顶部边缘」开着时上沿是渐变，不能再开圆角（互锁，见主题页「材质效果」）。
                    val softTopEdge = com.kingzcheung.xime.ui.keyboard.rememberGlassSoftTopEdge()
                    val glassOn = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences().frostedGlass.enabled
                    val softTopEdgeLock = softTopEdge && glassOn && !roundedTop
                    Row(
                        Modifier.settingsSearchAnchor("顶部圆角").fillMaxWidth().testTag("rounded-keyboard-top-setting")
                            .toggleable(value = roundedTop, role = Role.Switch, enabled = !softTopEdgeLock, onValueChange = {
                                roundedTop = it
                                SettingsPreferences.setRoundedKeyboardTop(context, it)
                            }).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("顶部圆角", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (roundedTop) "固定键盘顶部两角为圆角" else "固定键盘顶部直角；悬浮键盘仍是圆角卡片",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (softTopEdgeLock) SettingsLockNote("玻璃「柔化顶部边缘」开着时不能用，先在本页「材质效果」关掉")
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = roundedTop, onCheckedChange = null, enabled = !softTopEdgeLock, colors = settingsSwitchColors())
                    }
                })
                SettingsSection(title = "键盘底部样式", content = {
                    var rounded by remember { mutableStateOf(SettingsPreferences.roundedKeyboardBottom(context)) }
                    Row(
                        Modifier.settingsSearchAnchor("底部圆角与透明导航栏").fillMaxWidth().testTag("rounded-keyboard-bottom-setting")
                            .toggleable(value = rounded, role = Role.Switch, onValueChange = {
                                rounded = it
                                SettingsPreferences.setRoundedKeyboardBottom(context, it)
                            }).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("底部圆角与透明导航栏", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (rounded) "底部保留圆角，导航栏透明；图标跟随系统明暗，系统可能添加对比度底色"
                                else "底部直角，导航栏跟随键盘背景；图标深浅跟随按键文字",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = rounded, onCheckedChange = null)
                    }
                })
    }
}

/** 手写：停顿识别、公式标记；放在「语音与手写」。 */
@Composable
internal fun HandwritingSettings() {
    InputExperienceSettings(setOf(InputExperiencePart.HANDWRITING), "手写")
}

/** 「按键与手势」最上面的整套预设：滑动 / 长按 / 滑行输入；单独改过下面某项就显示为自定义（与性能页档位同一写法）。 */
@Composable
private fun GesturePresetSettings() {
    val context = LocalContext.current
    val settings = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences()
    val current = com.kingzcheung.xime.ui.keyboard.InputGesturePreset.of(settings)
    SettingsSection(title = "手势预设", content = {
        com.kingzcheung.xime.ui.keyboard.InputGesturePreset.presets.forEachIndexed { index, preset ->
            if (index > 0) RowDivider()
            TierRow(preset.label, preset.summary(settings.reverseSymbolSwipe), current == preset) { preset.applyTo(settings).save(context) }
        }
        if (current == com.kingzcheung.xime.ui.keyboard.InputGesturePreset.CUSTOM) {
            RowDivider()
            TierRow(current.label, current.summary(settings.reverseSymbolSwipe), true, enabled = false) {}
        }
    })
}
