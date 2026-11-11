package com.kingzcheung.xime.service

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.kingzcheung.xime.MainActivity
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.ui.keyboard.JapaneseKanaAction
import com.kingzcheung.xime.ui.keyboard.japaneseKanaRomaji
import com.kingzcheung.xime.rime.RimeProcessResult
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputMode
import com.kingzcheung.xime.settings.InputModes
import com.kingzcheung.xime.settings.InputProfileSelection
import com.kingzcheung.xime.settings.LanguagePreferences
import com.kingzcheung.xime.settings.LanguageSwitchPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reuse the settings activity; onNewIntent handles explicit destinations without recreating it. */
internal fun settingsActivityIntent(context: android.content.Context, route: String? = null) =
    Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        route?.let { putExtra("open_fragment", it) }
    }

/**
 * 方案管理与输入模式切换。
 *
 * 承载方案切换（switchSchema/applyPageSizeSetting）、部署（reloadConfig/deploy/deploySchema/downloadSchema）、
 * 中英切换（switchInputMethod）、工具栏编辑动作、键盘高度与浮动模式调整。
 * 共享状态通过 service 引用访问。
 */
internal class ImeSchemaController(private val service: XimeInputMethodService) {
    private fun currentLanguage(): InputLanguage = if (service.rimeEngine.isAsciiMode()) InputLanguage.ENGLISH
        else InputModes.languageOf(service.rimeEngine.getCurrentSchema(), service.uiState.value.schemas)

    /** 手写面板（临时手写或手写方案页）此刻是否开着。 */
    private fun handwritingOpen(): Boolean = service.keyboardViewModel.let {
        it.hasTemporaryHandwriting || it.page.value == com.kingzcheung.xime.keyboard.KeyboardPage.Main(com.kingzcheung.xime.keyboard.MainType.HANDWRITING)
    }

    /** 手写打开时切换了语言：新语言也能手写就重新打开手写（换成新语言的识别），否则留在该语言的键盘。 */
    private fun reopenHandwriting(wasOpen: Boolean, language: InputLanguage) {
        if (wasOpen && com.kingzcheung.xime.handwriting.HandwritingLanguages.keepsHandwritingOnSwitch(language,
                com.kingzcheung.xime.handwriting.HandwritingEngine.hasModel(service, InputLanguage.CHINESE)))
            service.keyboardViewModel.enterTemporaryHandwriting()
    }

    internal suspend fun switchInputMethod(targetLanguage: InputLanguage? = null,
        useLanguageKeyPreference: Boolean = false,
        languageSwitchMode: com.kingzcheung.xime.settings.LanguageSwitchMode? = null,
        keepCurrentSchema: Boolean = false): Boolean {
        if (service.editorInputPolicy.locksNativeInput) return false
        val fromLanguage = currentLanguage()
        val currentSchema = service.rimeEngine.getCurrentSchema()
        val nativeLanguage = InputModes.languageOf(currentSchema, service.uiState.value.schemas)
        val entries = InputModes.available(service.uiState.value.schemas)
        val enabled = LanguagePreferences.enabled(service)
        val available = (entries.filter { it.profile.mode == InputMode.KEYBOARD }.map { it.profile.language } +
            listOfNotNull(nativeLanguage.takeIf { currentSchema.isNotBlank() })).filter { it in enabled }.toSet()
        // Schema-options retain explicit ASCII behavior. The language key reads its preference;
        // physical shortcuts may override this one request without changing that preference.
        val target = targetLanguage ?: if (useLanguageKeyPreference)
            LanguageSwitchPreferences.target(fromLanguage, nativeLanguage, LanguageSwitchPreferences.forRequest(
                LanguageSwitchPreferences.read(service), languageSwitchMode),
                InputModes.languageOrder(service), available)
        else if (service.rimeEngine.isAsciiMode()) nativeLanguage else InputLanguage.ENGLISH
        if (target == null || target !in available) return false
        val targetSchema = if (keepCurrentSchema || target == InputLanguage.ENGLISH) currentSchema else {
            val localChoice = service.editorInputPolicy.schemaOverride?.takeIf { id ->
                entries.any { it.schemaId == id && it.profile.language == target }
            }
            val selected = localChoice ?: InputModes.selectedProfiles(service)[target]
                ?: InputModes.rememberedModes(service, entries)[target]
            InputProfileSelection.preferred(entries, target, selected, currentSchema)?.schemaId
                ?: currentSchema.takeIf { nativeLanguage == target }
                ?: return false
        }
        val changesSchema = targetSchema != currentSchema
        if (changesSchema && service.japaneseInputController.commit() && service.keyRouter.hasPendingCandidateCommit) return false
        val candState = service.candidateState.value
        val pendingEnglish = candState.pendingEnglishText
        FileLogger.i(XimeInputMethodService.TAG, "switchInputMethod: start, pendingEnglish='${if (pendingEnglish.isEmpty()) '-' else pendingEnglish}', isComposing=${candState.isComposing}, candidates=${candState.candidates.size}")
        if (pendingEnglish.isNotEmpty()) {
            // 英文直接上屏模式：编码字符已逐字落盘，切模式只需结束本轮输入（清状态），
            // 不可再 commitText 否则会重复输出整个词。
            withContext(Dispatchers.Main) {
                service.candidateState.value = service.candidateState.value.copy(
                    pendingEnglishText = "",
                    associationCandidates = emptyList()
                )
            }
        } else if (candState.isComposing || service.rimeEngine.getInput().isNotEmpty() ||
            service.t9PartialSegments.isNotEmpty()) {
            // 打拼音时切语言：字母原样上屏，不上屏首选（Gboard 规则，用户 2026-10-24）。
            if (!service.textCommit.commitCompositionAndLiteral(asTyped = true)) return false
        }
        if (changesSchema) {
            // Selecting a first T9/native candidate may only consume a prefix. Preserve the
            // complete remaining preview before replacing its engine, using the normal checked
            // commit path; a rejected editor commit must leave the original language intact.
            if (service.keyRouter.hasPendingCandidateCommit) return false
            val partial = service.t9PartialSegments.joinToString("") { it.text }
            val remainder = service.rimeEngine.conversionPreview().ifEmpty { service.rimeEngine.getInput() }
            if (partial.isNotEmpty() || remainder.isNotEmpty()) {
                service.keyRouter.commitSelectedText(partial + remainder)
                if (service.keyRouter.hasPendingCandidateCommit) return false
            }
        }
        // All engine changes remain in the key-processing FIFO, including native-language pairs.
        if (fromLanguage != InputLanguage.ENGLISH && !service.editorInputPolicy.scoped) {
            InputModes.rememberMode(service, currentSchema, nativeLanguage)
        }
        if (changesSchema) {
            applyPageSizeSetting(targetSchema)
            if (!service.rimeEngine.switchSchema(targetSchema)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(service, "所选方案尚未就绪，请检查输入资源", Toast.LENGTH_SHORT).show()
                }
                return false
            }
        }
        val targetAscii = target == InputLanguage.ENGLISH
        if (service.rimeEngine.isAsciiMode() != targetAscii && !service.rimeEngine.toggleAsciiMode()) {
            FileLogger.e(XimeInputMethodService.TAG, "switchInputMethod: engine unavailable")
            withContext(Dispatchers.Main) { Toast.makeText(service, "输入法引擎不可用，请稍后再试", Toast.LENGTH_SHORT).show() }
            return false
        }
        if (!service.rimeEngine.isAsciiMode()) {
            // 回到中文/日文：按用户的「全角／半角」选择回写标点宽度。
            // 过去只恢复 full_shape（且取自 user.yaml），与菜单显示用的是两套状态，
            // 于是出现"显示半角、实际输出全角"。
            service.sessionController.applyPunctuationWidth(service.rimeEngine.isAsciiMode())
        }
        service.editorInputPolicy.choose(service.rimeEngine.isAsciiMode(), targetSchema)
        service.sessionController.persistSchemaOption("ascii_mode", service.rimeEngine.isAsciiMode())
        withContext(Dispatchers.Main) {
            // 显式同步 uiState.isAsciiMode（权威源 = rime 引擎状态），
            // 不依赖 updateUI 链路异步回写，避免键盘 UI 与 rime 状态脱钩。
            val ascii = service.rimeEngine.isAsciiMode()
            val schemaIdNow = service.rimeEngine.getCurrentSchema()
            FileLogger.i(XimeInputMethodService.TAG, "switchInputMethod: rime ascii=$ascii, ui before=${service.uiState.value.isAsciiMode}")
            // 空格上的方案名和方案 id 同一帧写上。只改 ascii 时名字还是上一种语言，英文切日语会先闪「中文26键」。
            val knownName = service.uiState.value.schemas.firstOrNull { it.schemaId == schemaIdNow }?.name
            service.uiState.value = service.uiState.value.copy(isAsciiMode = ascii,
                currentSchemaId = schemaIdNow,
                schemaName = com.kingzcheung.xime.settings.ChineseSchemas.immediateSchemaName(schemaIdNow, knownName))
            val keepHandwriting = changesSchema && handwritingOpen()
            if (changesSchema) {
                com.kingzcheung.xime.handwriting.HandwritingEngine.release()
                val model = service.keyboardViewModel
                model.discardTemporaryHandwriting()
                model.asciiStateMachine.reset()
                model.switchMain(com.kingzcheung.xime.keyboard.MainType.FULL)
                // All pending text was accepted above. Reset only the old T9 display/selection
                // state here, never queue an engine clear after the new schema is installed.
                service.keyboardCallbacks?.onT9CompositionCleared?.invoke()
                service.t9PartialSegments.clear()
                service.uiState.value = service.uiState.value.copy(
                    t9RightCandidateSelectedCount = 0, t9SelectedCandidatePinyin = "")
                service.sessionController.updateSchemaName()
            }
            if (!service.editorInputPolicy.scoped) SettingsPreferences.setCurrentSchema(service, service.rimeEngine.getCurrentSchema(),
                com.kingzcheung.xime.settings.InputModes.languageOf(service.rimeEngine.getCurrentSchema(), service.uiState.value.schemas))
            service.updateUI()
            // 主线程直接权威下发键盘布局切换（与 rime 状态一致），
            // 不依赖 Compose LaunchedEffect 侦测 uiState 后再异步 dispatch（部分机型调度延迟导致 UI 不更新）。
            val schemaId = service.rimeEngine.getCurrentSchema()
            service.keyboardViewModel.dispatch(
                com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(ascii, schemaId,
                    explicitLanguageChoice = !keepCurrentSchema)
            )
            reopenHandwriting(keepHandwriting, target)
        }
        if (changesSchema) service.japaneseInputController.cancel()
        return true
    }
    
    /**
     * 设置页存了自定义布局：重新部署并切回当前方案。
     * 不挂在某个输入框上（与自定义短语同样做法）：部署要几秒，期间设置页换了焦点或键盘收起都会换一代输入会话，
     * 挂着旧会话的任务一碰引擎就被当作过期命令取消，提示「应用失败：Expired input command」（2026-10-31 平板）。
     */
    internal fun applyCustomLayouts() {
        service.inputCommands.submit(kind = "deploy") {
            val prefs = SettingsPreferences.getPrefsPublic(service)
            try {
                KeysConfigHelper.loadConfig(service)
                // 布局、双拼键位改过，方案的拼写规则就变了：模糊音补丁里抄的那份规则跟着重写，免得盖住新键位。
                runCatching { com.kingzcheung.xime.settings.SpellingPatches.sync(service) }
                    .onFailure { FileLogger.e(XimeInputMethodService.TAG, "Failed to sync spelling patches", it) }
                check(RimeConfigHelper.ensureDeployment(service)) { "方案部署失败" }
                val id = SettingsPreferences.getCurrentSchema(service)
                check(service.rimeEngine.switchSchema(id)) { "无法恢复当前输入方案" }
                withContext(Dispatchers.Main) {
                    service.sessionController.updateSchemaName()
                    service.updateUI()
                    prefs.edit().putString(com.kingzcheung.xime.settings.CustomKeyboardLayouts.STATUS, "布局已应用，可从中文输入模式选择").apply()
                }
            } catch (e: Exception) {
                prefs.edit().putString(com.kingzcheung.xime.settings.CustomKeyboardLayouts.STATUS, "已保存，但应用失败：${e.message}；可在「已安装的方案」点「应用方案更改」重试").apply()
            }
        }
    }

    /** 模糊音等拼写补丁已由设置页写好：增量部署改过的方案，再切回当前方案让会话用上新规则。 */
    internal fun applySpellingPatches() {
        // 同 [applyCustomLayouts]：不挂在输入框上，部署中换焦点不会让它半途作废。
        service.inputCommands.submit(kind = "deploy") {
            try {
                check(RimeConfigHelper.ensureDeployment(service)) { "方案部署失败" }
                val id = SettingsPreferences.getCurrentSchema(service)
                check(service.rimeEngine.switchSchema(id)) { "无法恢复当前输入方案" }
                withContext(Dispatchers.Main) {
                    service.sessionController.updateSchemaName()
                    service.updateUI()
                }
            } catch (e: Exception) {
                FileLogger.e(XimeInputMethodService.TAG, "Failed to apply spelling patches", e)
            }
        }
    }

    internal fun reloadConfig() {
        
        service.mainHandler.post {
            service.requestHideSelf(0)
            android.widget.Toast.makeText(service, "方案部署中...", android.widget.Toast.LENGTH_SHORT).show()
        }
        
        // 部署投递到 key-processing 队列：与按键/切换同队列串行执行，
        // 部署期间输入/切换操作排队等待，完成后自动恢复，
        // 不再因 rimeLock 被部署占用而失败或静默丢弃。
        service.keyRouter.postRimeJob {
            try {
                KeysConfigHelper.loadConfig(service)
                // 重新加载配色方案（用户可能在 xime.custom.yaml 中修改了 color_schemes）
                KeyboardThemes.reload(service)
                
                check(RimeConfigHelper.redeploy(service)) { "方案部署失败，输入会话未就绪" }

                // 部署完成后重新加载配置（Rime 可能在部署过程中改写文件）
                KeysConfigHelper.loadConfig(service)
                KeyboardThemes.reload(service)
                
                val availableSchemas = service.rimeEngine.getAvailableSchemas()
                
                val savedSchema = SettingsPreferences.getCurrentSchema(service)
                if (savedSchema in availableSchemas) {
                    applyPageSizeSetting(savedSchema)
                    service.rimeEngine.switchSchema(savedSchema)
                } else {
                    FileLogger.w(XimeInputMethodService.TAG, "Schema $savedSchema not found in available schemas")
                }
                
                // 直接在 key-processing 线程同步读取 name，避免嵌套协程的时序问题
                val currentSchemaId = service.rimeEngine.getCurrentSchema()
                val schemaName = SchemaManager.getSchemaDisplayName(
                    service, currentSchemaId
                ) ?: currentSchemaId

                val problem = RimeConfigHelper.deploymentProblem(service)
                withContext(Dispatchers.Main) {
                    service.uiState.value = service.uiState.value.copy(
                        schemaName = schemaName,
                        currentSchemaId = currentSchemaId,
                    )
                    service.updateUI()
                    android.widget.Toast.makeText(service, if (problem.isEmpty()) "方案部署完成"
                        else "方案部署完成，有方案没编译成功，详情见「已安装的方案」", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                FileLogger.e(XimeInputMethodService.TAG, "Failed to reload config", e)
                if (RimeConfigHelper.deploymentProblem(service).isEmpty()) RimeConfigHelper.recordFailure(service, e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(service, "方案部署失败，到「已安装的方案」点「应用方案更改」可看原因", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    internal fun openSettings(route: String? = null) {
        // The keyboard is already editing CyIME's own settings test field.
        // A generic Settings tap should not even relaunch the activity or disturb focus.
        if (route == null && service.currentInputEditorInfo?.packageName == service.packageName) return
        try {
            service.startActivity(settingsActivityIntent(service, route))
        } catch (e: Exception) {
            FileLogger.e(XimeInputMethodService.TAG, "Failed to open settings", e)
        }
    }
    
    private val editorCursor = EditorCursor()
    private val pendingCursorSteps = PendingCursorSteps()
    private val japaneseKanaComposer = JapaneseKanaComposer()
    private var japaneseKanaSession = Long.MIN_VALUE

    internal fun resetEditorSelection() = editorCursor.resetSelection()

    internal fun handleJapaneseKanaAction(action: JapaneseKanaAction) {
        if (service.editorInputPolicy.locksNativeInput) return
        val original = service.currentInputConnection ?: return
        val inputSession = service.uiState.value.inputSessionId
        val profileId = service.uiState.value.currentSchemaId
        service.keyRouter.postRimeJob {
            if (service.currentInputConnection !== original || service.uiState.value.inputSessionId != inputSession ||
                service.rimeEngine.getCurrentSchema() != profileId || service.rimeEngine.isAsciiMode()) return@postRimeJob
            val profile = com.kingzcheung.xime.settings.InputProfiles.current(profileId)
            if (profile.scheme != com.kingzcheung.xime.settings.InputScheme.KANA ||
                profile.layout.kind != com.kingzcheung.xime.settings.LayoutKind.KANA_KEYPAD) return@postRimeJob
            if (japaneseKanaSession != inputSession) {
                japaneseKanaComposer.reset()
                japaneseKanaSession = inputSession
            }
            service.japaneseInputController.prepareKana(action == JapaneseKanaAction.Modify)
            val before = service.rimeEngine.getInput()
            var result: RimeProcessResult? = null
            val committed = StringBuilder()
            when (action) {
                is JapaneseKanaAction.Input -> {
                    if (action.romaji !in japaneseKanaRomaji) return@postRimeJob
                    var allProcessed = true
                    for (char in action.romaji) {
                        val next = service.rimeEngine.processQueuedKeyAndGetResult(char.code, 0)
                        result = next
                        committed.append(next.committedText)
                        if (!next.processed) { allProcessed = false; break }
                    }
                    if (allProcessed) japaneseKanaComposer.recordInput(before, action.romaji, result?.inputText.orEmpty(), committed.isNotEmpty())
                    else japaneseKanaComposer.reset()
                }
                JapaneseKanaAction.Modify -> {
                    if (japaneseKanaComposer.replacement(before) == null && before.isNotEmpty()) {
                        val boundary = service.rimeEngine.previousJapaneseBoundary(before)
                        japaneseKanaComposer.recordInput(before.take(boundary), before.drop(boundary), before, false)
                    }
                    val replacement = japaneseKanaComposer.replacement(before) ?: return@postRimeJob
                    // 整体改写引擎中的未提交编码，不发送退格，绝不删除宿主里已经上屏的字。
                    if (!service.rimeEngine.setInput(replacement.input)) return@postRimeJob
                    result = service.rimeEngine.getProcessResult(true)
                    committed.append(result.committedText)
                    if (committed.isEmpty()) japaneseKanaComposer.recordReplacement(replacement, result.inputText)
                    else japaneseKanaComposer.reset()
                }
            }
            val finalResult = result ?: return@postRimeJob
            val transformed = service.candidateTransform.transformFor(finalResult)
            withContext(Dispatchers.Main) {
                if (service.currentInputConnection !== original || service.uiState.value.inputSessionId != inputSession ||
                    service.uiState.value.currentSchemaId != profileId) return@withContext
                if (committed.isNotEmpty()) service.commitText(committed.toString())
                service.sessionController.updateUIWithResult(
                    transformed?.let { finalResult.copy(candidates = it.candidates.toTypedArray()) } ?: finalResult,
                    transformed?.actions ?: emptyList(),
                )
            }
        }
    }

    /**
     * [preeditKey] 标记手势类光标移动（键盘滑动、空格滑动）。手势按下时界面可能还没认出组合
     * （九键解码慢时常见），排队执行时却已在拼音：此时与按下时就在拼音一样转为编码内移动，
     * 编码内移动不可用时放弃这一步——绝不把未完成的拼音定格进输入框，也不清空 Rime。
     * 工具栏等明确的编辑命令不传 [preeditKey]，沿用原行为。
     */
    // 内部输入框每次新建连接会让 EditorCursor 当成换了输入框而丢掉选择锚点，同一输入框复用一个连接。
    private var internalConnection: Pair<android.widget.EditText, InputConnection>? = null

    private fun internalConnectionFor(editor: android.widget.EditText): InputConnection? =
        internalConnection?.takeIf { it.first === editor }?.second
            ?: editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())?.also { internalConnection = editor to it }

    private fun editCursor(finishComposition: Boolean = true, preeditKey: (() -> String?)? = null, action: (InputConnection) -> Unit) {
        val target = service.uiState.value.inputTarget
        if (target.isInternal) {
            val editor = target.editText() ?: return // Never fall back to the host when an internal editor owns input.
            val connection = internalConnectionFor(editor) ?: return
            editorCursor.observeSelection(connection, editor.selectionStart, editor.selectionEnd)
            action(connection)
            return
        }
        internalConnection = null
        val original = service.currentInputConnection ?: return
        val inputSession = service.uiState.value.inputSessionId
        service.keyRouter.postRimeJob {
            withContext(Dispatchers.Main) {
                // 焦点/会话检查与清理必须在同一个主线程任务内，中间不可挂起，
                // 否则队列里旧输入框的编辑操作可能清掉新输入框的组合串。
                if (service.currentInputConnection !== original || service.uiState.value.inputSessionId != inputSession ||
                    service.uiState.value.inputTarget.isInternal) {
                    return@withContext
                }
                val composing = service.candidateState.value.let { it.isComposing || it.inputText.isNotEmpty() }
                if (preeditKey != null && composing) {
                    preeditKey()?.let { service.keyboardCallbacks?.onPreeditCursorMove?.invoke(it) }
                    return@withContext
                }
                val preserveComposition = finishComposition &&
                    TerminalEditor.inputTextLocation(service) == SettingsPreferences.INPUT_TEXT_INPUT_BOX &&
                    service.candidateState.value.isComposing
                if (preserveComposition) {
                    service.finishComposingInputBoxKeepingText()
                    service.rimeEngine.endComposition(isT9Schema(service.uiState.value.currentSchemaId))
                }
                action(original)
                if (preserveComposition) service.updateUI()
            }
        }
    }

    /**
     * 空格上下滑、实体键盘上下键之外的“上/下”：与编辑面板的上下键同一规则，多行框按宿主排版的视觉行，
     * 单行框只在文本内移动（审计 F11：以前自动折行的长段落一下跳到段首/段尾）。
     */
    internal fun moveEditorCursorVertical(steps: Int, selecting: Boolean = false) {
        // Internal drafts use a temporary InputConnection that does not reliably
        // dispatch synthetic DPAD keys; move through their text lines instead.
        val internal = service.uiState.value.inputTarget.isInternal
        editCursor(preeditKey = { "preedit_cursor:${if (steps < 0) "start" else "end"}" }) { connection ->
            if (service.isTerminalEditor()) {
                // 终端里上下键就是翻命令历史，原样发出去。
                TerminalEditor.sendKeys(connection, if (steps < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, abs(steps))
                return@editCursor
            }
            val multiLine = editorIsMultiLine() && !internal
            repeat(abs(steps).coerceAtMost(1000)) {
                if (!editorCursor.moveLine(connection, steps.sign, selecting, multiLine)) return@editCursor
            }
        }
    }

    internal fun observeEditorSelection(start: Int, end: Int) {
        service.currentInputConnection?.let { editorCursor.observeSelection(it, start, end) }
    }

    /** Main thread, called inside the existing key FIFO; never enqueue a second cursor job. */
    internal fun moveHardwareCursor(event: KeyEvent) {
        val target = service.uiState.value.inputTarget
        val internalEditor = target.editText()
        val ic = if (target.isInternal) {
            internalEditor?.let(::internalConnectionFor) ?: return
        } else service.currentInputConnection ?: return
        if (internalEditor != null) editorCursor.observeSelection(ic, internalEditor.selectionStart, internalEditor.selectionEnd)
        // 终端读不到文字，下面的文内移动永远原地不动；方向键连同 Shift/Ctrl 交给终端自己处理。
        if (!target.isInternal && service.isTerminalEditor()) return TerminalEditor.forward(ic, event)
        editorCursor.movePhysical(ic, event.keyCode, event.isShiftPressed,
            byWord = event.isCtrlPressed, toBoundary = event.isAltPressed || event.isMetaPressed)
    }

    /**
     * 拖动移光标（长按空格、键盘区滑动）的每一格。每格仍排一个队列任务，但轮到的任务把攒下的格子一次做完，
     * 见 [PendingCursorSteps]：宿主回话慢时不再一格等一格（用户 10-31：长按空格滑动延迟很大）。
     */
    internal fun moveEditorCursor(steps: Int, selecting: Boolean = false, onStep: () -> Unit = {}) {
        if (steps == 0) return
        val session = service.uiState.value.inputSessionId
        pendingCursorSteps.add(steps, selecting, session)
        editCursor(preeditKey = { pendingCursorSteps.drainNet(session)?.let { "preedit_cursor:$it" } }) { ic ->
            for (run in pendingCursorSteps.drain(session)) {
                if (service.isTerminalEditor()) TerminalEditor.sendKeys(ic,
                    if (run.steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT, abs(run.steps), onStep)
                else editorCursor.moveWithFeedback(ic, run.steps, run.selecting, onStep)
            }
        }
    }

    /** 已在按键队列中，不能再次排队，否则长按方向键会积压到后续操作之后。 */
    internal suspend fun moveJapaneseCursorFromKeyQueue(steps: Int) {
        val original = service.currentInputConnection ?: return
        val owner = service.uiState.value.inputSessionId
        withContext(Dispatchers.Main) {
            if (service.currentInputConnection === original && service.uiState.value.inputSessionId == owner) {
                editorCursor.move(original, steps)
            }
        }
    }

    // 编辑面板的所有动作与选择、移光标同一个按键队列、同一个输入框，按点的顺序执行（审计 F05：
    // 以前全选/复制/剪切/粘贴/撤销直接作用于当前连接，排在前面的扩选还没执行就剪了旧选区）。
    internal fun handleToolbarEditingAction(action: String) {
        if (service.currentInputConnection == null) return
        if (service.isTerminalEditor()) {
            val key = TerminalEditor.keyForEditingAction(action)
            when {
                key != null -> editCursor { TerminalEditor.sendKeys(it, key) }
                action == "paste" -> editCursor { service.pasteIntoTerminal() }
                // 复制交给宿主：有自己选区的终端会处理，不处理也不出错。撤销（Ctrl+Z 会挂起前台程序）、
                // 剪切、全选和选择模式在终端里没有意义，不发任何东西。
                action == "copy" -> editCursor { it.performContextMenuAction(android.R.id.copy) }
            }
            return
        }
        when (action) {
            "undo", "redo" -> editCursor { service.sendUndoShortcut(it, redo = action == "redo") }
            "select_all" -> editCursor { it.performContextMenuAction(android.R.id.selectAll) }
            "copy" -> editCursor { it.performContextMenuAction(android.R.id.copy) }
            "cut" -> editCursor { it.performContextMenuAction(android.R.id.cut) }
            "paste" -> editCursor {
                service.dismissPredictionsForPaste()
                it.performContextMenuAction(android.R.id.paste)
            }
            "select_begin" -> editCursor { editorCursor.beginSelection(it) }
            "select_end" -> editCursor(finishComposition = false) { editorCursor.endSelection(it) }
            "select_reset" -> editCursor(finishComposition = false) { editorCursor.resetSelection() }
            "home", "end", "select_paragraph_start", "select_paragraph_end" -> editCursor {
                editorCursor.moveToParagraphBoundary(it, toEnd = action.endsWith("end"), selecting = action.startsWith("select_"))
            }
            "arrow_left", "arrow_right", "select_arrow_left", "select_arrow_right" -> editCursor {
                editorCursor.move(it, if (action.endsWith("left")) -1 else 1, action.startsWith("select_"))
            }
            "line_start", "line_end", "select_line_start", "select_line_end" -> editCursor {
                editorCursor.moveToLineBoundary(it, toEnd = action.endsWith("end"), selecting = action.startsWith("select_"))
            }
            "arrow_up", "arrow_down", "select_arrow_up", "select_arrow_down" -> editCursor {
                editorCursor.moveLine(it, if (action.endsWith("up")) -1 else 1, action.startsWith("select_"), editorIsMultiLine())
            }
        }
    }

    /** 只有声明多行的文本框才交给宿主按视觉行移动；单行框里宿主的上下键可能移走焦点。 */
    private fun editorIsMultiLine(): Boolean {
        val target = service.uiState.value.inputTarget
        val type = if (target.isInternal) target.editText()?.inputType else service.currentInputEditorInfo?.inputType
        return type != null && type and android.text.InputType.TYPE_MASK_CLASS == android.text.InputType.TYPE_CLASS_TEXT &&
            type and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
    }

    internal fun applyPageSizeSetting(schemaId: String) {
        val userPageSize = SettingsPreferences.getPageSize(service)
        if (userPageSize > 0) {
            service.rimeEngine.setPageSize(schemaId, userPageSize)
        }
    }

    internal fun toggleHandwriting() {
        if (service.editorInputPolicy.locksNativeInput) return
        val model = service.keyboardViewModel
        if (model.exitTemporaryHandwriting()) {
            com.kingzcheung.xime.handwriting.HandwritingEngine.release()
            return
        }
        if (model.page.value == com.kingzcheung.xime.keyboard.KeyboardPage.Main(com.kingzcheung.xime.keyboard.MainType.HANDWRITING)) {
            switchSchema(service.previousSchemaId.ifEmpty { service.rimeEngine.getCurrentSchema() }.ifEmpty { "pinyin_simp" })
            return
        }
        val language = service.uiState.value.inputProfile.language
        // 中文、英文、日语都由内置汉字手写模型服务（英文取其中字母，假名拿它的输出比对模板），不需要公式模型
        val templateHandwriting = com.kingzcheung.xime.handwriting.HandwritingTemplates.Script.forLanguage(language)
            ?.available(service) == true
        val recognitionLanguage = com.kingzcheung.xime.handwriting.HandwritingLanguages.recognitionLanguageForKeyboard(language)
        if (recognitionLanguage == null && !templateHandwriting) {
            Toast.makeText(service, com.kingzcheung.xime.handwriting.HandwritingLanguages.unavailableMessage(language), Toast.LENGTH_SHORT).show()
            return
        }
        if (!templateHandwriting && !com.kingzcheung.xime.handwriting.HandwritingEngine.hasModel(service, recognitionLanguage!!)) {
            Toast.makeText(service, "请先下载手写模型", Toast.LENGTH_SHORT).show()
            service.startActivity(settingsActivityIntent(service, "model_management"))
            return
        }
        service.editorInputPolicy.choose(false)
        model.enterTemporaryHandwriting()
    }

    internal fun switchSchema(schemaId: String) {
        if (service.editorInputPolicy.locksNativeInput) return
        if (schemaId == com.kingzcheung.xime.settings.InputModes.ENGLISH) {
            service.keyRouter.postRimeJob {
                if (!service.rimeEngine.isAsciiMode() && !switchInputMethod(targetLanguage = InputLanguage.ENGLISH)) return@postRimeJob
                val engineSchema = service.rimeEngine.getCurrentSchema()
                service.editorInputPolicy.choose(true, engineSchema)
                service.sessionController.persistSchemaOption("ascii_mode", true)
                withContext(Dispatchers.Main) {
                    val keepHandwriting = handwritingOpen()
                    com.kingzcheung.xime.handwriting.HandwritingEngine.release()
                    service.keyboardViewModel.discardTemporaryHandwriting()
                    service.keyboardViewModel.asciiStateMachine.reset()
                    service.keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.FULL)
                    service.keyboardViewModel.dispatch(com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(true, engineSchema))
                    reopenHandwriting(keepHandwriting, InputLanguage.ENGLISH)
                    if (engineSchema.isNotEmpty() && !service.editorInputPolicy.scoped) SettingsPreferences.setCurrentSchema(service, engineSchema,
                        com.kingzcheung.xime.settings.InputModes.languageOf(engineSchema, service.uiState.value.schemas))
                    service.uiState.value = service.uiState.value.copy(isAsciiMode = true)
                    service.sessionController.updateSchemaName()
                }
            }
            return
        }
        if (com.kingzcheung.xime.settings.InputModes.languageOf(schemaId, service.uiState.value.schemas) !in
            com.kingzcheung.xime.settings.LanguagePreferences.enabled(service)) return
        if (isHandwritingSchema(schemaId)) {
            // 检查手写模型文件是否已下载
            if (!com.kingzcheung.xime.handwriting.HandwritingEngine.hasModel(service)) {
                FileLogger.w(XimeInputMethodService.TAG, "Handwriting model not found, redirecting to download")
                android.widget.Toast.makeText(
                    service, "请先下载手写模型", android.widget.Toast.LENGTH_LONG
                ).show()
                service.startActivity(settingsActivityIntent(service, "model_management"))
                return
            }
            service.keyboardViewModel.discardTemporaryHandwriting()
            service.previousSchemaId = service.rimeEngine.getCurrentSchema()
            if (!service.editorInputPolicy.scoped) SettingsPreferences.setCurrentSchema(service, schemaId,
                        com.kingzcheung.xime.settings.InputModes.languageOf(schemaId, service.uiState.value.schemas))
            service.editorInputPolicy.choose(false, schemaId)
            service.keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.HANDWRITING)
            // 手写模型按"用键盘时加载"管理：切到手写方案即展示手写键盘，
            // 由 HandwritingKeyboardLayout 创建时（LaunchedEffect）负责加载，
            // 此处不重复加载（避免与布局初始化并发双 bind/load）
            service.sessionController.updateSchemaName()
            return
        }
        // 显式选择语言方案与普通按键串行，成功后一次性更新引擎、页面和模式记忆。
        service.keyRouter.postRimeJob {
            try {
                val targetLanguage = InputModes.languageOf(schemaId, service.uiState.value.schemas)
                if (targetLanguage !in LanguagePreferences.enabled(service)) return@postRimeJob
                applyPageSizeSetting(schemaId)
                if (!service.rimeEngine.switchSchema(schemaId)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(service, if (service.rimeEngine.isMaintaining())
                            "词库部署中，请稍后再切换方案" else "方案未部署，请在「已安装的方案」点「应用方案更改」后再试", Toast.LENGTH_SHORT).show()
                    }
                    return@postRimeJob
                }
                // 英文是独立入口；选中中文/日语方案必须退出此前遗留的 ASCII 模式。
                service.rimeEngine.setOption("ascii_mode", false)
                service.editorInputPolicy.choose(false, schemaId)
                service.sessionController.persistSchemaOption("ascii_mode", false)
                withContext(Dispatchers.Main) {
                    val keepHandwriting = handwritingOpen()
                    com.kingzcheung.xime.handwriting.HandwritingEngine.release()
                    val model = service.keyboardViewModel
                    model.discardTemporaryHandwriting()
                    model.asciiStateMachine.reset()
                    if (!service.editorInputPolicy.scoped) SettingsPreferences.setCurrentSchema(service, schemaId,
                        com.kingzcheung.xime.settings.InputModes.languageOf(schemaId, service.uiState.value.schemas))
                    service.uiState.value.schemas.firstOrNull { it.schemaId == schemaId && !service.editorInputPolicy.scoped }?.let {
                        com.kingzcheung.xime.settings.InputModes.selectProfile(service, it)
                    }
                    val knownName = service.uiState.value.schemas.firstOrNull { it.schemaId == schemaId }?.name
                    service.uiState.value = service.uiState.value.copy(isAsciiMode = false, currentSchemaId = schemaId,
                        schemaName = com.kingzcheung.xime.settings.ChineseSchemas.immediateSchemaName(schemaId, knownName))
                    model.switchMain(com.kingzcheung.xime.keyboard.MainType.FULL)
                    model.dispatch(com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(false, schemaId))
                    reopenHandwriting(keepHandwriting, targetLanguage)
                    service.sessionController.updateSchemaName()
                    service.updateUI()
                    Toast.makeText(service, "已切换输入方案", Toast.LENGTH_SHORT).show()
                }
                // 显示状态同步后再回写标点宽度：此处 uiState 可能还残留英文态，
                // 显式 asciiMode=false，保证中文/日文方案按用户选择输出标点。
                service.sessionController.applyPunctuationWidth(asciiMode = false)
            } catch (e: Exception) {
                FileLogger.e(XimeInputMethodService.TAG, "Failed to switch schema", e)
            }
        }
    }
    
    private fun downloadSchema(schemaId: String) {
        service.serviceScope.launch(Dispatchers.IO) {
            service.notifyDeploymentStatus(true, "正在下载 $schemaId...")
            
            val success = SchemaConfigHelper.downloadSchema(service, schemaId)
            
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(service, "$schemaId 下载成功，请在「已安装的方案」点「应用方案更改」", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(service, "$schemaId 下载失败", Toast.LENGTH_SHORT).show()
                }
                service.notifyDeploymentStatus(false, "")
            }
        }
    }
    
    private fun updateKeyboardHeightPreview(heightDp: Int) {
        service.keyboardContainer.updateHeight(heightDp)
    }
    
    internal fun setKeyboardHeight(heightDp: Int) {
        val isLandscape = service.resources.configuration.screenWidthDp > service.resources.configuration.screenHeightDp
        SettingsPreferences.setKeyboardHeightDp(service, heightDp, isLandscape)
        service.uiState.value = service.uiState.value.copy(keyboardHeightDp = heightDp)
        Toast.makeText(service, "键盘高度已调整", Toast.LENGTH_SHORT).show()
    }

    internal fun toggleFloatingMode(enabled: Boolean, navBarDp: Int = 0, persist: Boolean = true) {
        service.restoreKeyboardDisplayMode(enabled, navBarDp, persist)
    }

}
