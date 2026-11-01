package com.kingzcheung.xime.ui.caption

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ui.theme.MaterialLevel
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.visualMaterial
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kingzcheung.xime.model.ModelDownloadState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LightBackground = Color(0xFFF5F5F0)
private val LightText = Color(0xFF111111)
private val DarkBackground = Color(0xFF121212)
private val DarkText = Color(0xFFEDEDED)

internal fun speakerColor(index: Int) = Color(CaptionController.COLORS[index.coerceIn(0, CaptionController.COLORS.size - 1)])

@Composable
fun CaptionScreen(controller: CaptionController, onExit: () -> Unit) {
    // The app's own theme (keyboard theme, dynamic colour, light/dark with the system).
    val context = androidx.compose.ui.platform.LocalContext.current
    val darkMode = remember { com.kingzcheung.xime.settings.SettingsPreferences.getEffectiveDarkMode(context) }
    val dark = when (darkMode) { 2 -> androidx.compose.foundation.isSystemInDarkTheme(); 1 -> true; else -> false }
    // Exactly the settings app's theme: its saved light/dark choice and the keyboard theme.
    com.kingzcheung.xime.ui.theme.XimeTheme(darkTheme = dark, themeId = com.kingzcheung.xime.settings.SettingsPreferences.getKeyboardTheme(context)) {
        // 再套一层：颜色全部换成键盘主题（键帽、回车键、文字、玻璃透明度），对话框里也一样。
        CaptionThemed(dark) {
            // 玻璃主题时整页不铺不透明底，让窗口后面的（模糊）壁纸透出来，面板和气泡才是真的半透明。
            val glass = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences(dark).frostedGlass.enabled
            Surface(Modifier.fillMaxSize(), color = if (glass) Color.Transparent else MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface) {
                Box(Modifier.fillMaxSize().captionPageBackground()) { CaptionPage(controller, onExit) }
            }
        }
    }
}

@Composable
private fun CaptionPage(controller: CaptionController, onExit: () -> Unit) {
    val fontSp = CaptionController.FONT_SP[controller.fontLevel]
    var menuSpeaker by remember { mutableStateOf<SpeakerUi?>(null) }
    var reassignTurn by remember { mutableStateOf<Turn?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var replying by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    // 横屏快捷回复是页面里的一条（不是对话框）：返回键只收起它，回到字幕；字幕主界面按返回才退出。
    androidx.activity.compose.BackHandler(enabled = replying) { replying = false }
    var flipped by remember { mutableStateOf<String?>(null) }

    // The navigation bar stays (with its back button); bottom buttons sit above it and the
    // system gesture area, so swiping up for the bar never presses "请再说一遍" (1.13.10).
    // 状态栏是隐藏的，但下拉或麦克风指示点会临时出现：顶部始终按状态栏高度留白，不盖住按钮。
    @OptIn(ExperimentalLayoutApi::class)
    val statusBarSpace = androidx.compose.foundation.layout.WindowInsets.statusBarsIgnoringVisibility
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val portrait = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    // 手机横屏高度只有约 400 dp：控制条和按钮挪到右侧一栏，字幕占满左边整个高度。
    val sideRail = !portrait && configuration.screenHeightDp < 600
    val pageInsets = Modifier.fillMaxSize().windowInsetsPadding(statusBarSpace.only(androidx.compose.foundation.layout.WindowInsetsSides.Top))
        .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
        .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.systemGestures.only(
            androidx.compose.foundation.layout.WindowInsetsSides.Bottom))
        .padding(horizontal = CaptionStyle.Edge, vertical = 8.dp)
    val context = androidx.compose.ui.platform.LocalContext.current
    // 保存本次字幕：同一次字幕始终是同一个文件（再点只是更新它），另可复制全部或分享。
    var saving by remember { mutableStateOf(false) }
    val save: () -> Unit = { saving = true }
    val banners: @Composable () -> Unit = {
        // 换成没下载过的语言时：在这里看下载进度，下完自动开始听。
        val languageDownload = controller.languageDownload(controller.language)
        if (languageDownload !is ModelDownloadState.Idle && controller.missingModel(controller.language) != null)
            ModelBanner("${controller.language.label}字幕要用的语音模型", "重新下载", languageDownload) { controller.downloadLanguage(controller.language) }
        if (!controller.streamingReady) ModelBanner("没有实时出字模型：要等对方说完一句才整句出字", "下载实时出字模型（226 MB）",
            controller.streamingDownload) { controller.downloadStreaming() }
        if (!controller.refinementReady) ModelBanner("没有 SenseVoice 校正：字幕没有标点、错字多", "下载 SenseVoice（228 MB）",
            controller.refinementDownload) { controller.downloadRefinement() }
        // 通话字幕要开无障碍，有的银行应用见到无障碍开着就不让用：可以“不用了”关掉，以后不再出现。
        if (!controller.callCaptionEnabled && !controller.callCaptionCardDismissed) {
            val context = androidx.compose.ui.platform.LocalContext.current
            ModelBanner("打电话、微信视频时也想看字幕？开启后通话中会自动在屏幕底部显示", "去开启通话字幕", ModelDownloadState.Idle,
                onDismiss = {
                    controller.dismissCallCaptionCard()
                    android.widget.Toast.makeText(context, "以后要用，可以在系统设置 → 无障碍里打开“Lime 通话字幕”", android.widget.Toast.LENGTH_LONG).show()
                }) {
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        if (!controller.speakerModelReady) ModelBanner("还没有声纹模型，暂时分不出是谁在说", "下载声纹模型（27 MB）",
            controller.downloadState) { controller.downloadSpeakerModel() }
    }
    val transcript: @Composable (Modifier) -> Unit = { modifier ->
        NewSegmentPull(modifier.fadeTopEdge(), onNewSegment = {
            controller.newSegment()
            android.widget.Toast.makeText(context, "已开始新的一段，刚才的字幕已保存", android.widget.Toast.LENGTH_SHORT).show()
        }) {
            // Landscape: one track per speaker on a time axis. Portrait: a chat log of messages.
            RevealTicker(controller)
            if (portrait) CaptionChat(controller, fontSp, onSpeakerTap = { menuSpeaker = it }, onMessageLongPress = { reassignTurn = it })
            else CaptionTimeline(controller, fontSp, onSpeakerTap = { menuSpeaker = it }, onBlockLongPress = { reassignTurn = it })
            if (controller.status == CaptionController.Status.Paused) PausedOverlay { controller.resume() }
            if (controller.status == CaptionController.Status.Starting) LoadingOverlay(controller.message)
            if (controller.status == CaptionController.Status.Error) ErrorOverlay(controller.message) { controller.start() }
        }
    }
    // Sound made visible above the buttons, in its own strip so it never covers words or buttons.
    val ribbon: @Composable (Dp) -> Unit = { height ->
        SoundRibbon(controller.spectrum, controller.status == CaptionController.Status.Listening, MaterialTheme.colorScheme.primary,
            Modifier.fillMaxWidth().height(height))
    }

    if (sideRail) Row(pageInsets, horizontalArrangement = Arrangement.spacedBy(CaptionStyle.Gap)) {
        // 字幕占左边几乎整个屏幕：提示条限高、底下一条状态和声音丝带。
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = (configuration.screenHeightDp * .3f).dp).verticalScroll(rememberScrollState())) {
                banners()
            }
            transcript(Modifier.weight(1f).fillMaxWidth())
            RailStatus(controller) { ribbon(it) }
            // 横屏快捷回复：字幕下面一条矮横排（分组一行、常用的话一行，左右滑），字幕仍占大半屏。
            if (replying) ReplyStrip(controller, onShow = { flipped = it }, onWrite = { replying = false; writing = true },
                onClose = { replying = false })
        }
        // 右边一条窄栏（约屏宽一成）：五个按钮竖排，图标在上字在下，每个仍有老人好按的高度。
        SlimRail(controller, onSave = save, onExit = { confirmExit = true }, onReply = { replying = true }, onRepeat = { flipped = "请再说一遍" })
    } else Column(pageInsets, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 顶部一块玻璃面板：三个大按钮 + 状态与音量，按钮同高同宽同间距。
        CaptionPanel(Modifier.fillMaxWidth()) {
            TopControls(controller, onSave = save, onExit = { confirmExit = true })
        }
        // 提示条最多占屏高约三成，多了就在这一块里滚动：字幕区和底部按钮永远留得住。
        Column(Modifier.fillMaxWidth().heightIn(max = (configuration.screenHeightDp * .3f).dp).verticalScroll(rememberScrollState())) {
            banners()
        }
        transcript(Modifier.weight(1f).fillMaxWidth())
        // 横竖屏同一条规则：只有一个声音指示（状态 + 声音丝带），在字幕下面单独一行，不放进按钮面板。
        RailStatus(controller) { ribbon(it) }
        CaptionPanel(Modifier.fillMaxWidth(), padding = 10.dp) {
            BottomButtons(onReply = { replying = true }, onRepeat = { flipped = "请再说一遍" })
        }
    }

    menuSpeaker?.let { speaker ->
        SpeakerMenu(speaker, controller, onDismiss = { menuSpeaker = null })
    }
    reassignTurn?.let { turn ->
        ReassignDialog(turn, controller, onDismiss = { reassignTurn = null })
    }
    if (confirmExit) BigChoiceDialog("真的退出字幕吗？", "退出", "继续听", onYes = { confirmExit = false; controller.exit(); onExit() }, onNo = { confirmExit = false })
    if (replying && !sideRail) ReplyDialog(controller, fontSp, onDismiss = { replying = false })
    // 横屏要自己写字：要弹出键盘，才打开整页回话。
    if (writing) ReplyDialog(controller, fontSp, onDismiss = { writing = false })
    flipped?.let { text -> FlippedCard(text) { flipped = null } }
    if (saving) SaveDialog(controller, onDismiss = { saving = false })
}

/** 保存：写好这次的文件并告诉在哪；复制全部、分享给别的应用。 */
@Composable
private fun SaveDialog(controller: CaptionController, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val where = remember { controller.saveTranscript() }
    var history by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = captionDialogColor(), contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("字幕记录", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(if (where == null) "这次还没有字幕。" else "这次的字幕已保存在“$where”，边听边更新，一次只有这一个文件。" +
                    if (CaptionAutoSave.keepsThreeDays(context)) "字幕记录保留 3 天。" else "",
                    fontSize = 18.sp, lineHeight = 26.sp)
                if (where != null) {
                    CaptionButton("复制全部", Modifier.fillMaxWidth()) {
                        if (controller.copyAll()) android.widget.Toast.makeText(context, "已复制全部字幕", android.widget.Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                    CaptionButton("分享", Modifier.fillMaxWidth()) {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, controller.transcriptText())
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, "分享字幕")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        onDismiss()
                    }
                }
                CaptionButton("搜索字幕", Modifier.fillMaxWidth()) { searching = true }
                CaptionButton("以前的字幕", Modifier.fillMaxWidth()) { history = true }
                BigButton("好", Modifier.fillMaxWidth(), outlined = true, onClick = onDismiss)
            }
        }
    }
    if (history) HistoryDialog(controller, onDismiss = { history = false })
    if (searching) SearchDialog(controller, onDismiss = { searching = false })
}

/** 搜索字幕：这次和以前保存的字幕里含这个词的句子，新的在上；点一句打开那份记录。 */
@Composable
private fun SearchDialog(controller: CaptionController, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Pair<SavedCaption?, String>>>(emptyList()) }
    var open by remember { mutableStateOf<SavedCaption?>(null) }
    val clock = remember { SimpleDateFormat("M月d日", Locale.getDefault()) }
    LaunchedEffect(query) {
        kotlinx.coroutines.delay(250)
        hits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { controller.search(query) }
    }
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f), shape = RoundedCornerShape(28.dp), color = captionDialogColor(),
            contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("搜索字幕", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                androidx.compose.material3.OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("要找的词", fontSize = 19.sp) }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),
                    modifier = Modifier.fillMaxWidth())
                if (query.isNotBlank() && hits.isEmpty()) Text("没有找到。", fontSize = 18.sp)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(hits.size) { i ->
                        val (saved, line) = hits[i]
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .combinedClickableNoRipple { if (saved != null) open = saved }.padding(12.dp)) {
                            Text(if (saved == null) "这次" else clock.format(Date(saved.modifiedMs)), fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(highlight(line, query), fontSize = 19.sp, lineHeight = 26.sp)
                        }
                    }
                }
                BigButton("返回", Modifier.fillMaxWidth(), outlined = true, onClick = onDismiss)
            }
        }
    }
    open?.let { f -> SavedCaptionDialog(controller, f, onDismiss = { open = null }) }
}

/** The search word in bold accent inside a found line. */
@Composable
private fun highlight(line: String, query: String): androidx.compose.ui.text.AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    return androidx.compose.ui.text.buildAnnotatedString {
        val q = query.trim()
        var at = 0
        while (q.isNotEmpty()) {
            val i = line.indexOf(q, at, ignoreCase = true)
            if (i < 0) break
            append(line.substring(at, i))
            pushStyle(androidx.compose.ui.text.SpanStyle(color = accent, fontWeight = FontWeight.Bold))
            append(line.substring(i, i + q.length))
            pop()
            at = i + q.length
        }
        append(line.substring(at))
    }
}

/** 以前的字幕：一个简单的列表，新的在上；点开看全文，可以选字、复制全部、分享。 */
@Composable
private fun HistoryDialog(controller: CaptionController, onDismiss: () -> Unit) {
    val files = remember { controller.savedCaptions() }
    var open by remember { mutableStateOf<SavedCaption?>(null) }
    val clock = remember { SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = captionDialogColor(), contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("以前的字幕", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (files.isEmpty()) Text("还没有保存过的字幕。", fontSize = 18.sp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files.size) { i ->
                        val f = files[i]
                        CaptionButton("${clock.format(Date(f.modifiedMs))}　${(f.sizeBytes + 1023) / 1024} KB", Modifier.fillMaxWidth()) { open = f }
                    }
                }
                BigButton("返回", Modifier.fillMaxWidth(), outlined = true, onClick = onDismiss)
            }
        }
    }
    open?.let { f -> SavedCaptionDialog(controller, f, onDismiss = { open = null }) }
}

@Composable
private fun SavedCaptionDialog(controller: CaptionController, saved: SavedCaption, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val text = remember(saved) { controller.readSaved(saved) }
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f), shape = RoundedCornerShape(28.dp), color = captionDialogColor(),
            contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(saved.name.removeSuffix(".txt"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                    Text(text, fontSize = 19.sp, lineHeight = 28.sp, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptionButton("复制全部", Modifier.weight(1f)) {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("字幕", text))
                        android.widget.Toast.makeText(context, "已复制全部字幕", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    CaptionButton("分享", Modifier.weight(1f)) {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, text)
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, "分享字幕")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    CaptionButton("返回", Modifier.weight(1f), onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
internal fun BigButton(text: String, modifier: Modifier = Modifier, outlined: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    // Labels never wrap or get cut to one character on a phone: one line, ellipsis last.
    // 统一成字幕页的玻璃按钮：outlined = 普通，否则 = 主操作（主题强调色容器）。
    CaptionButton(text, modifier, icon = icon, emphasis = if (outlined) CaptionEmphasis.Normal else CaptionEmphasis.Primary, onClick = onClick)
}

@Composable
private fun TopControls(controller: CaptionController, onSave: () -> Unit, onExit: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Phone width: four equal buttons, icon above the word; tablet: font on the left, the rest on the right.
        val narrow = maxWidth < 600.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (narrow) 8.dp else CaptionStyle.Gap)) {
            val share = if (narrow) Modifier.weight(1f) else Modifier.width(160.dp)
            @Composable fun button(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, emphasis: CaptionEmphasis = CaptionEmphasis.Normal, onClick: () -> Unit) =
                if (narrow) CaptionButton(text, share, icon = icon, emphasis = emphasis, height = 64.dp, fontSize = 15.sp, stacked = true, onClick = onClick)
                else CaptionButton(text, share, icon = icon, emphasis = emphasis, onClick = onClick)
            button("字号" + CaptionController.FONT_LABEL[controller.fontLevel], Icons.Rounded.FormatSize) { controller.cycleFont() }
            if (!narrow) Spacer(Modifier.weight(1f))
            when (controller.status) {
                CaptionController.Status.Paused -> button("继续", Icons.Rounded.PlayArrow, CaptionEmphasis.Primary) { controller.resume() }
                else -> button("暂停", Icons.Rounded.Pause) { controller.pause() }
            }
            button("记录", Icons.Rounded.Save, onClick = onSave)
            button("退出", Icons.Rounded.Close, onClick = onExit)
        }
    }
}

/** 横屏侧栏：同样三个按钮，竖着排、等宽。 */
@Composable
private fun SlimRail(controller: CaptionController, onSave: () -> Unit, onExit: () -> Unit, onReply: () -> Unit, onRepeat: () -> Unit) {
    Column(Modifier.width(RAIL_WIDTH).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val each = Modifier.fillMaxWidth().weight(1f)
        CaptionButton("字号" + CaptionController.FONT_LABEL[controller.fontLevel], each, icon = Icons.Rounded.FormatSize,
            height = RAIL_BUTTON, fontSize = 15.sp, stacked = true) { controller.cycleFont() }
        when (controller.status) {
            CaptionController.Status.Paused -> CaptionButton("继续", each, icon = Icons.Rounded.PlayArrow, emphasis = CaptionEmphasis.Primary,
                height = RAIL_BUTTON, fontSize = 15.sp, stacked = true) { controller.resume() }
            else -> CaptionButton("暂停", each, icon = Icons.Rounded.Pause,
                height = RAIL_BUTTON, fontSize = 15.sp, stacked = true) { controller.pause() }
        }
        CaptionButton("快捷回复", each, icon = Icons.Rounded.Forum, emphasis = CaptionEmphasis.Primary,
            height = RAIL_BUTTON, fontSize = 15.sp, stacked = true, onClick = onReply)
        CaptionButton("再说一遍", each, icon = Icons.Rounded.Replay, height = RAIL_BUTTON, fontSize = 15.sp, stacked = true, onClick = onRepeat)
        CaptionButton("记录", each, icon = Icons.Rounded.Save, height = RAIL_BUTTON, fontSize = 15.sp, stacked = true, onClick = onSave)
        CaptionButton("退出", each, icon = Icons.Rounded.Close, height = RAIL_BUTTON, fontSize = 15.sp, stacked = true, onClick = onExit)
    }
}

/** The status line itself; [language] null hides the language button (one language offered). */
@Composable
internal fun StatusRow(language: String?, dotColor: Color, text: String, onPickLanguage: () -> Unit, ribbon: @Composable (Dp) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // 听哪种语言：放在状态这一行最前面，一眼看得到、一点就换；字幕区和按钮都不挤。
        if (language != null) {
            CaptionButton("$language ▾", height = 40.dp, fontSize = 17.sp, onClick = onPickLanguage)
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.size(12.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) { ribbon(40.dp) }
    }
}

/** Landscape status: one line under the captions, the sound ribbon taking the rest of it. */
@Composable
private fun RailStatus(controller: CaptionController, ribbon: @Composable (Dp) -> Unit) {
    val (dotColor, text) = statusOf(controller)
    var choosing by remember { mutableStateOf(false) }
    StatusRow(controller.language.label.takeIf { CaptionLanguage.supported.size > 1 }, dotColor, text,
        onPickLanguage = { choosing = true }, ribbon = ribbon)
    if (choosing) LanguageDialog(controller.language, missing = { controller.missingModel(it)?.second },
        onPick = { picked ->
            controller.chooseLanguage(picked)
            // 没下载的语言：在应用里直接下（和模型管理同一个下载器），下完自动开始听。
            if (controller.missingModel(picked) != null) controller.downloadLanguage(picked)
            choosing = false
        }, onDismiss = { choosing = false })
}

/** 字幕语言：三个整行大按钮，当前的那个打勾；没下载的模型写明要下多大。 */
@Composable
private fun LanguageDialog(current: CaptionLanguage, missing: (CaptionLanguage) -> String?, onPick: (CaptionLanguage) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) { LanguagePickerCard(current, missing, onPick, onDismiss) }
}

/** The language picker's card, on its own so it can be drawn without a dialog window. */
@Composable
internal fun LanguagePickerCard(current: CaptionLanguage, missing: (CaptionLanguage) -> String?, onPick: (CaptionLanguage) -> Unit, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = captionDialogColor(), contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("字幕听哪种语言？", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            for (language in CaptionLanguage.supported) {
                val size = missing(language)
                val label = (if (language == current) "✓ " else "") + language.label + (size?.let { "（先下载 $it）" } ?: "")
                CaptionButton(label, Modifier.fillMaxWidth(),
                    emphasis = if (language == current) CaptionEmphasis.Primary else CaptionEmphasis.Normal) { onPick(language) }
            }
            BigButton("取消", Modifier.fillMaxWidth(), outlined = true, onClick = onDismiss)
        }
    }
}

private val RAIL_WIDTH = 104.dp
private val RAIL_BUTTON = 48.dp

@Composable
private fun statusOf(controller: CaptionController) = when {
    controller.micTaken -> MaterialTheme.colorScheme.error to "麦克风被别的应用占着（比如带声音的录屏），这会儿听不到"
    controller.micMuted -> MaterialTheme.colorScheme.error to "手机的麦克风被关了，请家人看一下"
    controller.status == CaptionController.Status.Listening -> MaterialTheme.colorScheme.primary to "正在听"
    controller.status == CaptionController.Status.Starting -> MaterialTheme.colorScheme.tertiary to controller.message.ifEmpty { "正在准备…" }
    controller.status == CaptionController.Status.Paused -> MaterialTheme.colorScheme.outline to "已暂停"
    controller.status == CaptionController.Status.Held -> MaterialTheme.colorScheme.outline to controller.message
    controller.status == CaptionController.Status.Error -> MaterialTheme.colorScheme.error to controller.message
    else -> MaterialTheme.colorScheme.outline to ""
}

@Composable
internal fun ModelBanner(message: String, action: String, state: ModelDownloadState, onDismiss: (() -> Unit)? = null, onDownload: () -> Unit) {
    // Same container as the settings pages (surfaceContainerLow, 12 dp, visual material).
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.visualMaterial(VisualStyles.current, 12.dp, level = MaterialLevel.RAISED).padding(16.dp)) {
            // 横屏窄栏旁的字幕区也约 530 dp 宽：仍放一行，免得按钮被限高区裁掉。
            val narrow = maxWidth < 440.dp
            val text: @Composable () -> Unit = {
                Text(message, style = MaterialTheme.typography.bodyLarge, fontSize = 18.sp)
                when (state) {
                    is ModelDownloadState.Downloading -> LinearProgressIndicator(progress = { state.progress }, Modifier.fillMaxWidth().padding(top = 6.dp))
                    is ModelDownloadState.Error -> Text("下载失败：${state.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> {}
                }
            }
            // 可关闭的提示：主按钮旁边一个“不用了”，同高。
            if (narrow) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                text()
                if (state !is ModelDownloadState.Downloading) {
                    if (onDismiss == null) BigButton(action, Modifier.fillMaxWidth(), onClick = onDownload)
                    else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton(action, Modifier.weight(1f), onClick = onDownload)
                        BigButton("不用了", outlined = true, onClick = onDismiss)
                    }
                }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { text() }
                if (state !is ModelDownloadState.Downloading) BigButton(action, onClick = onDownload)
                if (onDismiss != null) BigButton("不用了", outlined = true, onClick = onDismiss)
            }
        }
    }
}

/** One row per speaker; time flows left→right, the newest turn sits at the right edge. */
/** One row of the transcript: consecutive sentences of one speaker, or the sentence still being spoken. */
class Turn(val key: Long, val speaker: SpeakerUi?, val segments: List<SegmentUi>) {
    val text: String get() = segments.joinToString("") { it.text }
    val live: Boolean get() = segments.any { !it.confirmed }
}

/** Same speaker, less than this apart → one row; the unlabeled tail is its own live row. */
private const val MERGE_GAP_MS = 10_000L

private fun buildTurns(controller: CaptionController): List<Turn> {
    val turns = ArrayList<Turn>()
    var current = ArrayList<SegmentUi>()
    var speaker: SpeakerUi? = null
    var lastWall = 0L
    fun flush() {
        if (current.isNotEmpty()) turns.add(Turn(current.first().id, speaker, current))
        current = ArrayList()
    }
    for (line in controller.segments) {
        if (line.text.isEmpty()) continue
        val who = line.speakerId?.let { id -> controller.speakers.firstOrNull { it.id == id } }
        if (who?.ignored == true) continue
        val same = current.isNotEmpty() && who != null && speaker?.id == who.id && line.wallMs - lastWall < MERGE_GAP_MS
        if (!same) { flush(); speaker = who }
        current.add(line)
        lastWall = line.wallMs
    }
    flush()
    return turns
}

/** Vertical transcript using the full width: newest row at the bottom, followed automatically. */
@Composable
private fun Transcript(controller: CaptionController, fontSp: Int, onSpeakerTap: (SpeakerUi) -> Unit, onTurnLongPress: (Turn) -> Unit) {
    val turns = buildTurns(controller)
    val hidden = controller.segments.count { line -> line.text.isNotEmpty() && controller.speakers.any { it.id == line.speakerId && it.ignored } }
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                follow = last == null || last.index >= info.totalItemsCount - 1
            }
        }
    }
    val tail = turns.lastOrNull()
    val itemCount = turns.size + if (hidden > 0) 1 else 0
    LaunchedEffect(itemCount, tail?.text, follow) {
        if (follow && itemCount > 0) listState.scrollToItem(itemCount - 1, Int.MAX_VALUE / 2)
    }
    Box(Modifier.fillMaxSize()) {
        if (turns.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("等对方说话，字会出现在这里。", fontSize = fontSp.sp, lineHeight = (fontSp * 1.3f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hidden > 0) item(key = "hidden") { Text("（电视声 ×$hidden 已折叠）", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(turns.size, key = { turns[it].key }) { index ->
                TurnRow(turns[index], fontSp, onSpeakerTap, onTurnLongPress)
            }
        }
        if (!follow && turns.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { BigButton("回到最新") { follow = true } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TurnRow(turn: Turn, fontSp: Int, onSpeakerTap: (SpeakerUi) -> Unit, onLongPress: (Turn) -> Unit) {
    val speaker = turn.speaker
    val color = speaker?.let { speakerColor(it.colorIndex) } ?: Color.Gray
    val time = remember(turn.key) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(turn.segments.first().wallMs)) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = .10f))
        .combinedClickable(onClick = {}, onLongClick = { if (speaker != null) onLongPress(turn) })) {
        Box(Modifier.width(10.dp).fillMaxHeight().background(color))
        // Name column: the whole block is the tap target for "这是谁？".
        Column(Modifier.width(150.dp).fillMaxHeight().heightIn(min = 72.dp)
            .combinedClickable(onClick = { speaker?.let(onSpeakerTap) }).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(speaker?.name ?: "正在说", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = if (speaker == null) Color.Gray else Color.Unspecified, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(time, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(turn.text + if (turn.live) "…" else "", fontSize = fontSp.sp, lineHeight = (fontSp * 1.3f).sp,
            modifier = Modifier.weight(1f).padding(top = 8.dp, bottom = 10.dp, end = 14.dp))
    }
}


@Composable
private fun BottomButtons(onReply: () -> Unit, onRepeat: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CaptionStyle.Gap)) {
        CaptionButton("快捷回复", Modifier.weight(1f), icon = Icons.Rounded.Forum,
            emphasis = CaptionEmphasis.Primary, height = CaptionStyle.PrimaryHeight, onClick = onReply)
        CaptionButton("请再说一遍", Modifier.weight(1f), icon = Icons.Rounded.Replay,
            height = CaptionStyle.PrimaryHeight, onClick = onRepeat)
    }
}

@Composable
private fun PausedOverlay(onResume: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .78f)), contentAlignment = Alignment.Center) {
        CaptionButton("已暂停，点这里继续", Modifier.fillMaxWidth(.8f), icon = Icons.Rounded.PlayArrow,
            emphasis = CaptionEmphasis.Primary, height = 110.dp, fontSize = 28.sp, maxLines = 2, onClick = onResume)
    }
}

/**
 * Speech model still loading: the same waiting page the keyboard shows while its dictionary loads
 * (title, thin progress bar, what is happening, that it continues by itself). Appears only when the
 * wait is noticeable, so a warm restart does not flash it.
 */
@Composable
private fun LoadingOverlay(message: String) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(300); visible = true }
    if (!visible) return
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 1f)).clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("正在加载语音模型", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.width(180.dp), color = MaterialTheme.colorScheme.onSurface,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
            Spacer(Modifier.height(10.dp))
            Text(message.ifEmpty { "正在初始化..." }, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Text("首次打开或更新后需要一点时间，加载完成后自动开始显示字幕",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onRestart: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .78f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(message, fontSize = 24.sp, modifier = Modifier.padding(horizontal = 24.dp))
            CaptionButton("重新开始", Modifier.fillMaxWidth(.6f), icon = Icons.Rounded.Replay,
                emphasis = CaptionEmphasis.Primary, height = 90.dp, fontSize = 28.sp, onClick = onRestart)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakerMenu(speaker: SpeakerUi, controller: CaptionController, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(if (speaker.named) speaker.name else "") }
    // 起了名字就默认记住声音（只存在这部手机里），下次打开字幕直接认出来。
    var remember by remember { mutableStateOf(true) }
    val others = controller.speakers.filter { it.id != speaker.id && it.id != CaptionController.REPLY_SPEAKER }
    fun name(value: String) { controller.rename(speaker.id, value, remember); onDismiss() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.9f).padding(16.dp), shape = RoundedCornerShape(28.dp), color = captionDialogColor(), contentColor = MaterialTheme.colorScheme.onSurface) {
            // 横屏高度不够时整页可滚动，确认按钮永远够得着。
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("这是谁？", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                // 自己写名字：一个真正的输入框，“确认”在同一排，和下面的名字标签一眼分得开。
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(custom, { custom = it }, Modifier.weight(1f), singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 24.sp),
                        placeholder = { Text("写名字，比如：大孙子", fontSize = 20.sp) },
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (custom.isNotBlank()) name(custom.trim()) }),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done))
                    CaptionButton("确认", emphasis = CaptionEmphasis.Primary, onClick = { if (custom.isNotBlank()) name(custom.trim()) })
                }
                Text("或者点一个：", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Existing names first: picking one means "same person", and the rows merge.
                    for (other in others.filter { it.named && !it.ignored }) {
                        BigButton(other.name) { controller.mergeInto(speaker.id, other.id); onDismiss() }
                    }
                    for (preset in CaptionController.PRESET_NAMES) {
                        if (others.any { it.name == preset && it.named }) continue
                        BigButton(preset, outlined = true) { name(if (preset == "这是我") "我" else preset) }
                    }
                    BigButton("这是电视", outlined = true) { controller.markTelevision(speaker.id); onDismiss() }
                }
                // 整行开关（设置页同一写法）：标题 + 一行说明 + 右侧开关。
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("记住这个人的声音", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        Text("下次打开字幕自动认出来；声音特征只存在这部手机里", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(remember, { remember = it })
                }
                if (controller.isRemembered(speaker.id)) {
                    BigButton("忘掉这个人的声音", Modifier.fillMaxWidth(), outlined = true) { controller.forgetVoice(speaker.id); onDismiss() }
                }
                BigButton("关闭", Modifier.fillMaxWidth(), outlined = true, onClick = onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReassignDialog(turn: Turn, controller: CaptionController, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.9f).padding(16.dp), shape = RoundedCornerShape(28.dp), color = captionDialogColor(), contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("这句话是谁说的？", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(turn.text, fontSize = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (speaker in controller.speakers.filter { it.id != CaptionController.REPLY_SPEAKER && !it.ignored }) {
                        BigButton(speaker.name, outlined = speaker.id != turn.speaker?.id) { turn.segments.forEach { controller.reassign(it.id, speaker.id) }; onDismiss() }
                    }
                }
                BigButton("关闭", Modifier.fillMaxWidth(), outlined = true, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun BigChoiceDialog(title: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) {
    Dialog(onDismissRequest = onNo) {
        Surface(shape = RoundedCornerShape(28.dp), color = captionDialogColor(), contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, fontSize = 28.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigButton(yes, Modifier.weight(1f), onClick = onYes)
                    BigButton(no, Modifier.weight(1f), outlined = true, onClick = onNo)
                }
            }
        }
    }
}

/** Shown to the other person: the whole card is upside down so they can read it across the table. */
@Composable
private fun FlippedCard(text: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().captionPageBackground().combinedClickableNoRipple(onDismiss), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 64.sp, lineHeight = 80.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayer { rotationZ = 180f }.padding(32.dp))
            Text("对方看到的是：$text　点一下关闭", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
        }
    }
}

/**
 * Landscape quick replies: a low strip under the captions so they stay in view. Top line: the groups
 * (underlined words, clearly not phrases), "自己写" and the emergency button; second line: the phrases
 * of the chosen group, swiped sideways. A tapped phrase is kept in the conversation and shown big,
 * turned towards the other person, with an upright line saying what they see.
 */
@Composable
private fun ReplyStrip(controller: CaptionController, onShow: (String) -> Unit, onWrite: () -> Unit, onClose: () -> Unit) {
    var group by remember { mutableStateOf(0) }
    val groups = CaptionController.PHRASE_GROUPS
    fun say(phrase: String) { controller.addReply(phrase); onShow(phrase) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val underline = MaterialTheme.colorScheme.onSurface
    CaptionPanel(Modifier.fillMaxWidth(), padding = 8.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                groups.forEachIndexed { index, (title, _) ->
                    val selected = index == group
                    Text(title, fontSize = 19.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { group = index }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .drawWithContent {
                                drawContent()
                                if (selected) drawRect(underline,
                                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 3.dp.toPx()),
                                    size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
                            })
                }
            }
            CaptionButton("自己写", height = 44.dp, fontSize = 17.sp, onClick = onWrite)
            CaptionButton("收起", height = 44.dp, fontSize = 17.sp, onClick = onClose)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 和竖屏一样：紧急求助在“回答”这一组里，不单独占位置。
            if (group == 0) {
                CaptionButton("紧急：打120", icon = Icons.Rounded.Warning, emphasis = CaptionEmphasis.Danger, height = 52.dp, fontSize = 19.sp) {
                    say(CaptionController.EMERGENCY_TEXT)
                }
                CaptionButton("拨号 120", icon = Icons.Rounded.Call, emphasis = CaptionEmphasis.DangerQuiet, height = 52.dp, fontSize = 19.sp) {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:120"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            for (phrase in groups[group].second) CaptionButton(phrase, height = 52.dp, fontSize = 19.sp) { say(phrase) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableNoRipple(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

private const val EMERGENCY_CHOICE = "#emergency" // not a phrase: the 紧急 button in 回答
private const val DIAL_CHOICE = "#dial"

/**
 * Quick replies. The display card shows the chosen words upright for whoever holds the phone; the
 * words turn around for someone across the table when tapped, and an upright line underneath always
 * says what the other person is seeing. Groups are tabs (underlined), phrases are buttons, and the
 * phrase on screen is marked, so the three never look alike.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyDialog(controller: CaptionController, fontSp: Int, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    // The last thing already written into the conversation, so 发送 does not add it twice.
    var sent by remember { mutableStateOf("") }
    // Turning to face the other person is animated (like a payment code being shown across the counter).
    val turn by androidx.compose.animation.core.animateFloatAsState(if (controller.replyFlipped) 180f else 0f,
        androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "flip")
    val context = androidx.compose.ui.platform.LocalContext.current
    val emergency = text == CaptionController.EMERGENCY_TEXT
    // A phrase is shown at once and kept in the conversation for the family.
    fun say(phrase: String) {
        if (phrase == text) { text = ""; return }
        text = phrase; controller.addReply(phrase); sent = phrase
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // 比例：给对方看的大字卡片占剩下的全部高度；其余都是统一的 52 dp 小一号按钮，放在玻璃面板里。
        // 不透明底：玻璃主题下页面本身是透的，回话页不能透出后面的字幕。
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).captionPageBackground()
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
            .padding(horizontal = CaptionStyle.Edge, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("快捷回复", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            // The display card: turned the way the user last left it (remembered), upright at first.
            CaptionPanel(Modifier.weight(1f).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The holder can always read what the other person sees: it is the header itself,
                    // so the upside-down text keeps the card's whole height.
                    Text(when {
                        controller.replyFlipped && text.isNotEmpty() -> "对方看到：$text"
                        controller.replyFlipped -> "给对面的人看（已倒过来）"
                        text.isNotEmpty() -> "点一下大字，倒过来给对面看"
                        else -> "屏幕上显示"
                    }, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))

                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(CaptionStyle.Corner))
                    .clickable(enabled = text.isNotEmpty()) { controller.turnReplyCard(!controller.replyFlipped) }
                    .background(if (emergency) MaterialTheme.colorScheme.error.copy(alpha = .9f) else Color.Transparent),
                    contentAlignment = Alignment.Center) {
                    // 卡片矮时（小屏、横屏）字跟着缩，至少两行写得下，不被切掉。
                    val cardSp = (maxHeight.value / 2.6f).coerceIn(24f, 56f)
                    Text(text.ifEmpty { "点下面的话，或者自己写" }, fontSize = cardSp.sp, lineHeight = (cardSp * 1.25f).sp, fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = when {
                            text.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                            emergency -> MaterialTheme.colorScheme.onError
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.graphicsLayer { rotationZ = turn }.padding(8.dp))
                }
            }
            // 输入框、分类标签、常用的话、清空/保存同在一块玻璃面板里，颜色都取自键盘主题。
            CaptionPanel(Modifier.fillMaxWidth(), padding = 10.dp) {
                androidx.compose.material3.TextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp),
                    placeholder = { Text("自己写（可以用手写键盘）", fontSize = 19.sp) }, shape = RoundedCornerShape(CaptionStyle.Corner),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                // Groups as underlined tabs: clearly not phrases. 下面的话也可以左右滑动换组，和标签同步。
                val groups = CaptionController.PHRASE_GROUPS
                val pager = androidx.compose.foundation.pager.rememberPagerState { groups.size }
                val pageScope = androidx.compose.runtime.rememberCoroutineScope()
                androidx.compose.material3.PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 0.dp,
                    containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, divider = {}) {
                    groups.forEachIndexed { index, (title, _) ->
                        androidx.compose.material3.Tab(selected = index == pager.currentPage,
                            onClick = { pageScope.launch { pager.animateScrollToPage(index) } },
                            selectedContentColor = MaterialTheme.colorScheme.onSurface,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = { Text(title, fontSize = 19.sp, fontWeight = if (index == pager.currentPage) FontWeight.Bold else FontWeight.Normal) },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                // 等宽网格：每格同宽同高，长的话换两行而不是把按钮撑宽，排起来整齐。
                // 网格约两行半高，多了在格子里滚：上面给对方看的大字卡片始终是页面上最大的一块。
                val gridMax = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .2f).dp.coerceIn(116.dp, 200.dp)
                // 灵敏度：手指横向拖过三分之一页才翻（默认一半），轻扫要够快才算；竖向滚动和点按不受影响，
                // 因为翻页只在横向移动超过系统滑动阈值后才接管。
                androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxWidth().heightIn(max = gridMax),
                    pageSpacing = 16.dp, beyondViewportPageCount = 1,
                    flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(pager, snapPositionalThreshold = .33f),
                    verticalAlignment = Alignment.Top) { page ->
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        // 回答组里带上紧急求助：不再单独占一排屏幕。
                        val phrases = if (page == 0) listOf(EMERGENCY_CHOICE, DIAL_CHOICE) + groups[page].second else groups[page].second
                        // 这一组里有长句就少分一列，两行内写得下。
                        val long = groups[page].second.any { it.length > 5 }
                        val columns = (if (maxWidth >= 600.dp) 4 else 3) - if (long) 1 else 0
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (row in phrases.chunked(columns)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (phrase in row) {
                                        // The phrase on screen is filled and ticked; the rest are plain.
                                        when (phrase) {
                                            // 和其他话同样宽的格子里要写得下：不放图标，红底就是紧急。
                                            EMERGENCY_CHOICE -> CaptionButton("紧急求助", Modifier.weight(1f),
                                                emphasis = CaptionEmphasis.Danger, height = 52.dp, fontSize = 17.sp) {
                                                if (text != CaptionController.EMERGENCY_TEXT) { say(CaptionController.EMERGENCY_TEXT); controller.turnReplyCard(true) }
                                                else say(CaptionController.EMERGENCY_TEXT)
                                            }
                                            DIAL_CHOICE -> CaptionButton("拨打120", Modifier.weight(1f),
                                                emphasis = CaptionEmphasis.DangerQuiet, height = 52.dp, fontSize = 17.sp) {
                                                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:120"))
                                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                            }
                                            // The phrase on screen is filled and ticked; tap it again to take it away.
                                            else -> CaptionButton(if (phrase == text) "✓ $phrase" else phrase, Modifier.weight(1f), height = 52.dp,
                                                fontSize = 18.sp, maxLines = 2,
                                                emphasis = if (phrase == text) CaptionEmphasis.Primary else CaptionEmphasis.Normal) { say(phrase) }
                                        }
                                    }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptionButton("返回", Modifier.weight(1f), height = 52.dp, fontSize = 19.sp, onClick = onDismiss)
                    // 发送：写进对话（点过的常用句已经记过，不重复），然后回到字幕；识别一直在跑。
                    CaptionButton("发送", Modifier.weight(1f), height = 52.dp, fontSize = 19.sp) {
                        if (text.isNotBlank() && text != sent) controller.addReply(text)
                        onDismiss()
                    }
                }
            }
        }
    }
}
