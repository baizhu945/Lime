package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.KeyShapes
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import com.kingzcheung.xime.handwriting.SPenSounds
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.handwriting.EnglishWordRecognizer
import com.kingzcheung.xime.handwriting.EnglishWordSpacing
import com.kingzcheung.xime.handwriting.FormulaRecognizer
import com.kingzcheung.xime.handwriting.HandwritingCandidate
import com.kingzcheung.xime.handwriting.HandwritingEngine
import com.kingzcheung.xime.handwriting.HandwritingTemplates
import com.kingzcheung.xime.handwriting.HandwritingLanguages
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.handwriting.HandwritingStrokeFx
import com.kingzcheung.xime.handwriting.OverlappedHandwritingRecognizer
import com.kingzcheung.xime.handwriting.renderStrokes
import com.kingzcheung.xime.handwriting.rememberHandwritingInk
import com.kingzcheung.xime.handwriting.rememberSPenInk
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 并排书写（日语、英文）一次最多切出的字数：一个单词、一小句。 */
private const val SIDE_BY_SIDE_MAX_CHARS = 12

/** 手写板左上角的当前语言提示。 */
internal fun handwritingLanguageHint(language: InputLanguage, useTemplates: Boolean, preparing: Boolean, missingKana: Boolean): String = when {
    preparing -> "${language.displayName}手写 · 正在准备（只在第一次较慢）…"
    // 手写训练页只在自用测试包里有，公开包不指向它。
    language == InputLanguage.JAPANESE && missingKana ->
        if (com.kingzcheung.xime.BuildConfig.DEV_TOOLS || com.kingzcheung.xime.BuildConfig.DEBUG)
            "日语手写 · 假名模板没读到，去 设置 → 手写训练模式 导入样本备份" else "日语手写 · 假名模板没读到"
    language == InputLanguage.JAPANESE -> "日语手写 · 假名＋漢字"
    language == InputLanguage.ENGLISH && useTemplates -> "英文手写 · 边写边学"
    else -> "${language.displayName}手写"
}

/**
 * 当前语言做成与公式按钮（关闭时）同样的实心小胶囊：主题功能键底色（不透明）＋功能键文字色，
 * 任何主题、玻璃和壁纸上都看得清。左上角，右侧让出公式按钮。
 */
@Composable
internal fun HandwritingLanguageHint(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    // 不用 Surface：它会挡住落笔；这里只是显示，笔从胶囊上也能写下去
    Text(text, color = foreground, fontSize = 13.sp,
        modifier = modifier.padding(start = 8.dp, top = 8.dp, end = 96.dp)
            .background(background.copy(alpha = 1f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp).testTag("handwriting-language"))
}

@Composable
internal fun HandwritingUnavailable(language: InputLanguage, modifier: Modifier, onExit: () -> Unit,
    exitLabel: String = "返回键盘", message: String = HandwritingLanguages.unavailableMessage(language)) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("handwriting-unavailable"),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.onSurface)
        TextButton(onClick = onExit) { Text(exitLabel) }
    }
}

@Composable
fun HandwritingKeyboardLayout(
    onKeyPress: (String) -> Unit = {},
    onNewCharacter: (() -> Unit)? = null,
    onRecognition: ((List<OverlappedHandwritingRecognizer.Segment>) -> Unit)? = null,
    onButtonFeedback: ((String) -> Unit)? = null,
    keyTextColor: Color = Color(0xFF333333),
    keyBackgroundColor: Color = Color(0xFFE0E0E0),
    specialKeyBackgroundColor: Color = Color(0xFFD0D0D0),
    bottomPaddingDp: Int = 18,
    modifier: Modifier = Modifier,
    clearSignal: Int = 0,
    sessionKey: Long = 0,
    specialKeyTextColor: Color = Color.White,
    expanded: Boolean = false,
    expandedControlsWidthDp: Int = 0,
    expandedControlsOffsetX: Int = 0,
    panelBackgroundColor: Color = Color.Transparent,
    expandedCandidateBar: @Composable () -> Unit = {},
    language: InputLanguage = InputLanguage.CHINESE,
    onUnsupportedExit: () -> Unit = {},
    unsupportedExitLabel: String = "返回键盘",
    floating: Boolean = false,
) {
    val sideSymbols = LocalKeyboardInputPreferences.current.symbolsFor(SideSymbolTarget.HANDWRITING)
    val rowSymbols = LocalKeyboardInputPreferences.current.symbolsFor(SideSymbolTarget.HANDWRITING_ROW)
    // 假名、英文字母：有公式模型包时走“边用边学”的模板比对（HandwritingTemplates），日语手写也因此可用
    val templateScript = HandwritingTemplates.Script.forLanguage(language)
        ?.takeIf { it.available(LocalContext.current) }
    val recognitionLanguage = HandwritingLanguages.recognitionLanguageForKeyboard(language)
        ?: if (templateScript != null) language else null
    if (recognitionLanguage == null) {
        // 日语手写靠公式模型算特征来比对假名：缺的是这个模型时，直接说去哪下载。
        val missingModel = HandwritingTemplates.Script.forLanguage(language) != null
        HandwritingUnavailable(language, modifier, onUnsupportedExit, unsupportedExitLabel,
            message = if (missingModel) "${language.displayName}手写需要先在 设置 → 模型管理 下载「公式手写模型」"
                else HandwritingLanguages.unavailableMessage(language))
        return
    }
    val configVersion by KeysConfigHelper.configVersion.collectAsState()
    val keyConfig = remember(configVersion, KeyShapes.current) { KeysConfigHelper.getKeyboardKeyConfig() }
    val spacing = keyConfig.spacingFor("qwerty")
    // Measure gaps against the same four-row QWERTY reference. The handwriting
    // controls below still divide the actual body into five equal rows.
    KeyboardKeySpacingScope(modifier, verticalInset = (8 + bottomPaddingDp).dp,
        allowShrink = floating, applyGutter = !expanded, growthSpacing = spacing) { bodyModifier ->
    CompositionLocalProvider(
        LocalKeyCornerRadius provides keyConfig.cornerRadius.dp,
        LocalKeyVisualPadding provides PaddingValues(
            horizontal = spacing.first?.dp ?: 2.dp, vertical = spacing.second?.dp ?: 2.dp),
    ) {
    val context = LocalContext.current
    var normalControlHeight by remember { mutableFloatStateOf(52f) }
    var normalControlScale by remember { mutableStateOf<Float?>(null) }
    var normalControlMetrics by remember { mutableStateOf<KeyVisualMetrics?>(null) }
    var normalControlGutter by remember { mutableFloatStateOf(8f) }
    val currentContentScale = LocalKeyboardKeyContentScale.current
    val currentVisualMetrics = LocalKeyboardKeyVisualMetrics.current
    val inkColor = rememberHandwritingInk(panelBackgroundColor)
    val penInkColor = rememberSPenInk(panelBackgroundColor)
    val scope = rememberCoroutineScope()
    val keyAction by rememberUpdatedState(onKeyPress)
    val newCharacter by rememberUpdatedState(onNewCharacter)
    val result by rememberUpdatedState(onRecognition)
    val feedback by rememberUpdatedState(onButtonFeedback)
    // The rows sit on the keyboard's own background (gradient, image or glass); a solid panel colour
    // here showed as a dark band around the bottom row on gradient themes.
    val functionRowBackground = Color.Transparent
    val pauseMs by rememberUpdatedState((LocalKeyboardInputPreferences.current.handwritingPauseSeconds * 1000).toLong())
    val recognizeWhileWriting by rememberUpdatedState(LocalKeyboardInputPreferences.current.handwritingRecognizeWhileWriting)
    val recognitionMutex = remember { Mutex() }
    val stylusPresence = remember { StylusPresence() }
    val eraserRadiusPx = with(LocalDensity.current) { com.kingzcheung.xime.handwriting.PenEraser.Radius.toPx() }
    // 三星设备上用系统自带的 S Pen 书写/橡皮声，满音量对应约 1 dp/ms 的笔速。
    val penSoundFullSpeed = with(LocalDensity.current) { 1.dp.toPx() }
    val penSounds = remember(context) { SPenSounds(context.applicationContext) }
    LaunchedEffect(penSounds) { withContext(Dispatchers.IO) { penSounds.load() } }
    DisposableEffect(penSounds) { onDispose { penSounds.release() } }
    // 公式模式：只有电脑上训练的公式模型包放到了设备上才显示开关（见 FormulaRecognizer）
    val formulaPrefs = remember(context) { context.getSharedPreferences("handwriting", android.content.Context.MODE_PRIVATE) }
    // 公式只在英文手写里提供：中文、日语手写不显示公式按钮，也不沿用上次的公式模式。
    val formulaAvailable = remember(context, language) { language == InputLanguage.ENGLISH && FormulaRecognizer.isAvailable(context) }
    // 英文手写里总显示公式按钮；还没有公式模型时点它走模型管理的下载
    val formulaShown = language == InputLanguage.ENGLISH
    var formulaMode by remember(formulaAvailable) { mutableStateOf(formulaAvailable && formulaPrefs.getBoolean("formula_mode", false)) }
    val formulaActive by rememberUpdatedState(formulaMode && formulaAvailable)
    val delimiter by rememberUpdatedState(LocalKeyboardInputPreferences.current.formulaDelimiter)
    // 模板库：第一次打开时把训练页写过的假名 / 字母算成模板（后台），之后每次选候选都会追加
    var templateCount by remember(templateScript) {
        mutableIntStateOf(templateScript?.let { HandwritingTemplates.count(context, it) } ?: 0)
    }
    // 只在后台读样本那一会儿显示“正在读取”；读完仍没有模板就直接说缺什么，不一直转圈。
    var importingTemplates by remember(templateScript) { mutableStateOf(templateScript != null) }
    LaunchedEffect(templateScript) {
        val script = templateScript ?: return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                runCatching { HandwritingTemplates.importTrainingSamples(context, script) }
                runCatching { HandwritingTemplates.ensureDefaults(context, script) }
            }
            templateCount = HandwritingTemplates.count(context, script)
        } finally { importingTemplates = false }
    }
    val useTemplates by rememberUpdatedState(templateScript != null && templateCount > 0)
    // 公式模式下首选候选的渲染预览（LaTeX → KaTeX），板上没笔迹时不显示
    var formulaPreview by remember { mutableStateOf("") }
    // 英文：停顿后接着写的新词前补空格、一段里写了几个词时分开认（整词模型不出空格）
    val wordSpacing = remember(sessionKey, language) { EnglishWordSpacing() }
    fun spaced(segments: List<OverlappedHandwritingRecognizer.Segment>, startsWord: Boolean) =
        if (!startsWord || segments.isEmpty()) segments
        else listOf(segments[0].copy(candidates = segments[0].candidates.map { it.copy(char = " " + it.char) })) + segments.drop(1)
    val session = remember(scope, sessionKey, language) {
        HandwritingInputSession(scope, { pauseMs }, recognize = { strokes ->
            if (formulaActive) recognitionMutex.withLock {
                // 公式：整段笔迹一次识别成 LaTeX，候选按设置的标记上屏；首选另外渲染成公式预览（候选栏仍是纯文本）
                val results = withContext(Dispatchers.Default) {
                    FormulaRecognizer.recognize(context, strokes.map { stroke -> stroke.map { it.x to it.y } })
                }
                formulaPreview = results.firstOrNull()?.first.orEmpty()
                if (results.isEmpty()) emptyList()
                else listOf(OverlappedHandwritingRecognizer.Segment(0, strokes.size,
                    results.map { (latex, score) -> HandwritingCandidate(delimiter.wrap(latex), score) }, replacesPending = true))
            } else if (useTemplates) recognitionMutex.withLock {
                // 假名 / 漢字 / 字母：一次可以写多个字，和中文一样按停顿与笔画切分，每段和模板比，最像的几个当候选
                withContext(Dispatchers.Default) {
                    val script = templateScript ?: return@withContext emptyList()
                    val points = strokes.map { stroke -> stroke.map { it.x to it.y } }
                    val chunk = if (script == HandwritingTemplates.Script.LATIN) wordSpacing.next(points) else null
                    val gaps = HandwritingStrokeFx.windowGaps(strokes).map { it * OverlappedHandwritingRecognizer.GAP_SPLIT_MS / pauseMs }
                    // 字母最多 4 笔；日语有漢字，沿用中文的上限
                    // 日语、英文习惯从左往右并排写：位置空隙也当换字信号
                    val recognizer = if (script == HandwritingTemplates.Script.LATIN)
                        OverlappedHandwritingRecognizer(maxSegments = SIDE_BY_SIDE_MAX_CHARS, maxStrokesPerSegment = 4, sideBySide = true, lettersApart = true)
                        else OverlappedHandwritingRecognizer(maxSegments = SIDE_BY_SIDE_MAX_CHARS, sideBySide = true)
                    val segments = recognizer.recognize(points, gaps, predictFn = { seg, k ->
                        // 接着落笔、删除或切走时这次识别已作废：下一段推理前就停，不再占着锁让下一个字排队
                        ensureActive()
                        // 模板分数 (cos+1)/2 换回余弦，乱切的段明显更低，切分才分得开
                        HandwritingTemplates.recognize(context, script, seg, k, remember = false)
                            .map { (text, score) -> HandwritingCandidate(text, (2f * score - 1f).coerceAtLeast(0.0001f)) }
                    }).segments
                    // 英文整词模型在时：整段笔迹一次认成单词放在前面，逐个字母切出来的结果拼成一个词排在后面（数字只有它能出）
                    val words = if (chunk != null && EnglishWordRecognizer.isAvailable(context))
                        EnglishWordRecognizer.recognizeWords(context, points, chunk.words) else emptyList()
                    if (words.isNotEmpty()) {
                        val spelled = segments.joinToString("") { it.candidates.firstOrNull()?.char.orEmpty() }
                        val candidates = (words.map { (word, score) -> HandwritingCandidate(word, score) } +
                            listOfNotNull(spelled.takeIf { it.isNotEmpty() }?.let { HandwritingCandidate(it, 0f) }))
                            .distinctBy { it.char }
                        HandwritingTemplates.rememberForLearning(context, script, null)
                        return@withContext spaced(listOf(OverlappedHandwritingRecognizer.Segment(0, strokes.size, candidates)), chunk?.startsWord == true)
                    }
                    HandwritingTemplates.rememberForLearning(context, script, points.takeIf { segments.size == 1 })
                    spaced(segments, chunk?.startsWord == true)
                }
            } else {
                // 只传不可变快照；每次创建新窗口，取消的本地推理不会污染下一字缓存。
                val gaps = HandwritingStrokeFx.windowGaps(strokes).map { it * OverlappedHandwritingRecognizer.GAP_SPLIT_MS / pauseMs }
                recognitionMutex.withLock {
                    withContext(Dispatchers.Default) {
                        // 英文（内置模型里的字母）也按并排书写切分；中文保持叠写习惯
                        val points = strokes.map { stroke -> stroke.map { it.x to it.y } }
                        val startsWord = recognitionLanguage == InputLanguage.ENGLISH && wordSpacing.next(points).startsWord
                        spaced((if (recognitionLanguage == InputLanguage.CHINESE) OverlappedHandwritingRecognizer()
                            else OverlappedHandwritingRecognizer(maxSegments = SIDE_BY_SIDE_MAX_CHARS, sideBySide = true,
                                maxStrokesPerSegment = if (recognitionLanguage == InputLanguage.ENGLISH) 4
                                    else OverlappedHandwritingRecognizer.DEFAULT_MAX_STROKES_PER_SEGMENT,
                                lettersApart = recognitionLanguage == InputLanguage.ENGLISH,
                                letterCost = OverlappedHandwritingRecognizer.MODEL_LETTER_COST)).recognize(
                            points, gaps,
                            // 作废的识别在下一段推理前就停（见上），不会跑完整个切分再放锁
                            predictFn = { seg, topK -> ensureActive(); HandwritingEngine.predict(seg, topK, recognitionLanguage) },
                        ).segments, startsWord)
                    }
                }
            }
        }, onNewCharacter = { newCharacter?.invoke() },
            // 按键（空格、回车、标点…）在本段笔迹认完之后才执行：从这里起下一段不再补空格
            onRecognition = { result?.invoke(it) }, onKey = { wordSpacing.reset(); keyAction(it) }, keepInk = { formulaActive },
            // 边写边识别只给中文、日语：英文要按停顿分词（有状态），公式整段重认，都不能提前认
            recognizeEarly = { recognizeWhileWriting && !formulaActive &&
                (recognitionLanguage == InputLanguage.CHINESE || recognitionLanguage == InputLanguage.JAPANESE) })
    }
    val modelDownloaded by remember {
        ModelManager.downloadStates.map { it["ochwpro"] is ModelDownloadState.Complete }.distinctUntilChanged()
    }.collectAsState(ModelManager.downloadStates.value["ochwpro"] is ModelDownloadState.Complete)
    LaunchedEffect(modelDownloaded, language) { withContext(Dispatchers.IO) { HandwritingEngine.initialize(context, recognitionLanguage) } }
    LaunchedEffect(clearSignal, session) { session.clear() }
    DisposableEffect(session) { onDispose { session.clear() } }

    fun press(action: String) {
        feedback?.invoke(action)
        session.press(action)
    }

    // Keep QWERTY's content edges and bottom inset, with five handwriting rows.
    BoxWithConstraints(bodyModifier.fillMaxSize().testTag("handwriting-panel")
        .padding(bottom = (8 + bottomPaddingDp).dp)) {
        // Fullscreen applies to ink only. Keep the footer in the user's normal keyboard bounds.
        val controlsWidth = if (expanded && expandedControlsWidthDp > 0)
            (expandedControlsWidthDp.dp - normalControlGutter.dp * 2).coerceIn(1.dp, maxWidth.coerceAtLeast(1.dp)) else maxWidth
        val travel = ((maxWidth - controlsWidth) / 2).value.coerceAtLeast(0f)
        val controlsOffset = if (expanded) expandedControlsOffsetX.toFloat().coerceIn(-travel, travel).dp else 0.dp
        val bottom = QwertyBottomRowWeights.Standard
        val ascii = language == InputLanguage.ENGLISH
        fun configuredWeight(id: String, fallback: Float) =
            KeysConfigHelper.getKeyGesture(id, ascii)?.width ?: fallback
        // Keep handwriting's original symbol/number controls and user-adjusted widths.
        // The main ?123 footer can redistribute its mode/comma/period slots independently.
        val modeWeight = configuredWeight("mode_change", bottom.mode) * 2f
        val symbolWeight = modeWeight / 2f
        val numberWeight = modeWeight / 2f
        val expandWeight = configuredWeight("comma", bottom.punctuation)
        val spaceWeight = configuredWeight("space", bottom.space)
        val languageWeight = configuredWeight("earth", bottom.language)
        val enterWeight = configuredWeight("enter", bottom.enter)
        val bottomTotal = symbolWeight + numberWeight + expandWeight + spaceWeight + languageWeight + enterWeight
        val functionKeyWidth = controlsWidth * (enterWeight / bottomTotal)
        val footerHeight = if (expanded) normalControlHeight.dp.coerceAtMost(maxHeight / 3) else maxHeight / 5
        if (!expanded) SideEffect {
            normalControlHeight = footerHeight.value
            normalControlScale = currentContentScale
            normalControlMetrics = currentVisualMetrics
            normalControlGutter = currentVisualMetrics.gutterX
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
            // 公式预览只是叠在画板顶部的显示内容，放在画布下层：整块区域都能落笔，笔迹画在预览之上。
            if (formulaActive) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(52.dp)
                .padding(horizontal = 96.dp).testTag("handwriting-formula-preview"), contentAlignment = Alignment.Center) {
                if (session.hasInk && formulaPreview.isNotEmpty())
                    com.kingzcheung.xime.ui.settings.KatexView(formulaPreview, Modifier.fillMaxSize(), color = keyTextColor.copy(alpha = 0.7f))
                else Text("写完停笔，这里显示公式预览", color = keyTextColor.copy(alpha = 0.4f), fontSize = 12.sp)
            }
            Canvas(Modifier.fillMaxSize().clipToBounds().testTag("handwriting-canvas")
                .semantics { contentDescription = "手写区域"; stateDescription = session.phase.description }
                .handwritingInk(session, stylusPresence, eraserRadiusPx,
                    onErased = { if (!penSounds.active) feedback?.invoke("handwriting_erase") },
                    onPenMotion = { erasing, speed -> penSounds.onMotion(erasing, speed, penSoundFullSpeed) },
                    onPenLifted = { penSounds.stop() })) {
                renderStrokes(session.strokes + listOfNotNull(session.currentStroke.takeIf { it.isNotEmpty() }), emptyList(), inkColor, transparentPaper = expanded, penColor = penInkColor)
            }
            // 一直显示正在写的语言（用户 10-21：除了公式按钮完全看不出在写什么语言）
            HandwritingLanguageHint(handwritingLanguageHint(language, useTemplates,
                    preparing = templateScript != null && importingTemplates,
                    missingKana = templateScript == HandwritingTemplates.Script.KANA && !useTemplates),
                specialKeyBackgroundColor, specialKeyTextColor, Modifier.align(Alignment.TopStart))
            // 公式开关是浮在画板右上角的实心小按钮，不用对钩：开启时强调色填满、文字在黑白里取反差大的；
            // 关闭时用主题功能键的底色（强制不透明）和文字色，不加描边。
            if (formulaShown) Surface(
                onClick = {
                    if (!formulaAvailable) {
                        context.startActivity(com.kingzcheung.xime.service.settingsActivityIntent(context, "model_management"))
                        return@Surface
                    }
                    session.clear()
                    keyAction("clear_composition")
                    formulaMode = !formulaMode
                    formulaPreview = ""
                    formulaPrefs.edit().putBoolean("formula_mode", formulaMode).apply()
                    if (!formulaMode) FormulaRecognizer.release()
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("handwriting-formula-toggle")
                    .semantics { stateDescription = if (formulaMode) "公式模式已开" else "公式模式已关" },
                shape = RoundedCornerShape(50),
                color = if (formulaMode) MaterialTheme.colorScheme.primary.copy(alpha = 1f) else specialKeyBackgroundColor.copy(alpha = 1f),
                contentColor = if (formulaMode) {
                    if (MaterialTheme.colorScheme.primary.luminance() > 0.179f) Color.Black else Color.White
                } else specialKeyTextColor,
                shadowElevation = if (formulaMode) 2.dp else 0.dp,
            ) {
                Text("公式", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
                }
                if (!expanded) Column(Modifier.width(functionKeyWidth).fillMaxHeight().testTag("handwriting-side-keys")) {
                    // 「按键符号」的手写侧栏，三格。
                    (listOf("delete") + SideSymbolTarget.HANDWRITING.resolve(sideSymbols))
                        .distinct().forEach { action ->
                        HandwritingFunctionKey(action, { press(action) },
                            if (action == "delete") specialKeyBackgroundColor else keyBackgroundColor,
                            if (action == "delete") specialKeyTextColor else keyTextColor,
                            Modifier.weight(1f).fillMaxWidth(), onClear = { press("clear_all") })
                    }
                }
            }
            val reportGlassRegion = HandwritingGlassRegion.reporter
            androidx.compose.runtime.DisposableEffect(expanded, reportGlassRegion) {
                if (!expanded) reportGlassRegion?.invoke(null)
                onDispose { reportGlassRegion?.invoke(null) }
            }
            Box(Modifier.fillMaxWidth()) {
            Column(Modifier.align(Alignment.TopCenter).absoluteOffset(x = controlsOffset).width(controlsWidth)
                // Fullscreen ink stays transparent; only the saved footer bounds receive the keyboard material.
                .then(if (expanded) Modifier.keyboardPanelBackground(panelBackgroundColor)
                    .onGloballyPositioned { coords ->
                        val pos = coords.positionInWindow()
                        reportGlassRegion?.invoke(android.graphics.Rect(kotlin.math.round(pos.x).toInt(), kotlin.math.round(pos.y).toInt(),
                            kotlin.math.round(pos.x + coords.size.width).toInt(), kotlin.math.round(pos.y + coords.size.height).toInt()))
                    } else Modifier)
                .testTag("handwriting-controls")) {
            CompositionLocalProvider(LocalKeyboardKeyVisualMetrics provides
                if (expanded) normalControlMetrics ?: currentVisualMetrics else currentVisualMetrics,
                LocalKeyboardKeyContentScale provides if (expanded) normalControlScale ?: currentContentScale else currentContentScale) {
            if (expanded) Box(Modifier.fillMaxWidth()) { expandedCandidateBar() }
            if (expanded) {
                Row(Modifier.fillMaxWidth().height(footerHeight).background(functionRowBackground)
                    .testTag("handwriting-symbol-row")) {
                    SideSymbolTarget.HANDWRITING_ROW.resolve(rowSymbols)
                        .filterNot { it == "delete" }.forEach { action ->
                        HandwritingFunctionKey(action, { press(action) }, keyBackgroundColor, keyTextColor,
                            Modifier.weight(1f).fillMaxHeight())
                    }
                    HandwritingFunctionKey("delete", { press("delete") }, specialKeyBackgroundColor,
                        specialKeyTextColor, Modifier.width(functionKeyWidth).fillMaxHeight(), onClear = { press("clear_all") })
                }
            }
            Row(Modifier.fillMaxWidth().height(footerHeight).background(functionRowBackground)
                .testTag("handwriting-bottom-row")) {
                // Retain the original punctuation-slot footprint for the expand control;
                // adding a period to the main keyboard must not narrow handwriting controls.
                val keys = listOf("symbol" to symbolWeight, "number" to numberWeight,
                    (if (expanded) "collapse" else "expand") to expandWeight,
                    "space" to spaceWeight, "ime_switch" to languageWeight, "enter" to enterWeight)
                val navigation = LocalTextModeNavigationGeometry.current
                keys.forEach { (action, weight) ->
                    val special = action != "space"
                    val slot = when (action) { "symbol" -> 1; "number" -> 2; else -> null }
                    val trackSlot = if (slot != null && navigation != null) Modifier.onGloballyPositioned {
                        navigation.record(slot, it.boundsInRoot())
                    } else Modifier
                    HandwritingFunctionKey(action, { press(action) },
                        if (special) specialKeyBackgroundColor else keyBackgroundColor,
                        if (special) specialKeyTextColor else keyTextColor,
                        Modifier.weight(weight).fillMaxHeight().then(trackSlot))
                }
            }
            }
            } // constrained controls
            }
        }
    }
    }
    }
}

@Composable
private fun HandwritingFunctionKey(action: String, onClick: () -> Unit, background: Color, foreground: Color, modifier: Modifier,
    onClear: (() -> Unit)? = null) {
    val label = when (action) {
        "delete" -> "删除"; "enter" -> "回车"; "space" -> "空格"
        "symbol" -> "!@#"; "number" -> "123"; "ime_switch" -> "语言切换"
        "expand" -> "全屏手写"; "collapse" -> "收起全屏手写"; else -> punctuationKeyLabel(action)
    }
    val enter = LocalEnterKeyColors.current.takeIf { action == "enter" }
    val keyBackground = enter?.background ?: background
    val keyForeground = enter?.foreground ?: foreground
    if (action == "delete") {
        SwipeableIconKeyButton(
            icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
            onClick = onClick, onLongClick = onClick, onSwipeUp = onClear,
            followSymbolSwipeDirection = true,
            upFeedback = KeyFlickFeedback.DELETE,
            swipeText = "清空", swipeUpLabel = "清空",
            backgroundColor = keyBackground, iconColor = keyForeground,
            modifier = modifier.semantics { contentDescription = label }.testTag("handwriting-key:delete"),
        )
        return
    }
    val keyModifier = modifier.semantics {
        contentDescription = label
        onClick { onClick(); true }
    }.testTag("handwriting-key:$action")
    if (action == "ime_switch") {
        // 与其他键盘同一个语言键：点按切换，长按弹出语言菜单。
        LanguageKeyButton(icon = rememberVectorPainter(Icons.Default.Language), onClick = onClick,
            backgroundColor = keyBackground, iconColor = keyForeground, modifier = keyModifier)
        return
    }
    val icon = when (action) {
        "enter" -> Icons.AutoMirrored.Filled.KeyboardReturn
        "space" -> Icons.Default.SpaceBar
        "ime_switch" -> Icons.Default.Language
        "expand" -> Icons.Default.OpenInFull
        "collapse" -> Icons.Default.CloseFullscreen
        else -> null
    }
    if (icon != null) IconKeyButton(rememberVectorPainter(icon), onClick,
        keyBackground, keyForeground, keyModifier)
    else CompositionLocalProvider(LocalKeyboardPunctuation provides null) {
        // Labels are resolved above; mode labels must stay !@#/123 like KeyboardModeKey.
        KeyButton(label, onClick, keyBackground, keyForeground, keyModifier,
            fontSize = (if (action == "symbol" || action == "number") 16 else 18).sp)
    }
}
