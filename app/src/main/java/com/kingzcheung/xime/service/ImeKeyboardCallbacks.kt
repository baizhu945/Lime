package com.kingzcheung.xime.service

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.rime.RimeProcessResult
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.KeyboardHeightProfiles
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.util.FileLogger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps sub-dp motion until the next whole offset, without accumulating debt at an edge. */
internal class FloatingDragAxis {
    private var remainder = 0f
    private var lastPosition: Int? = null

    fun move(current: Int, delta: Float, minimum: Int, maximum: Int): Int {
        if (lastPosition != current) remainder = 0f  // external reset / cancel / geometry change
        val position = (current + remainder + delta).coerceIn(minimum.toFloat(), maximum.toFloat())
        val next = position.roundToInt()
        remainder = position - next
        lastPosition = next
        return next
    }

    fun reset() { remainder = 0f; lastPosition = null }
}

/**
 * 构建键盘回调集合（KeyboardCallbacks）。
 *
 * 所有回调直接操作服务层状态与方法；service 内部成员对同模块可见（internal）。
 * 与原始实现在 onCreateInputView 内联构建的行为完全一致：
 * 浮动模式或屏幕几何变化时重建，拖动边界不能使用切换前的窗口高度。
 */
@Composable
internal fun rememberImeKeyboardCallbacks(
    service: XimeInputMethodService,
    floatingMinY: Int,
    state: InputUIState,
    effectiveScreenH: Int,
): KeyboardCallbacks {
    val view = LocalView.current
    val preeditEditor = remember(service) { ImePreeditEditor(service) }
    val syllableSelector = remember(service) { ImePinyinSyllableSelector(service) }
    return remember(floatingMinY, state.isFloatingMode, effectiveScreenH, state.inputSessionId, state.showKeyboardResize) {
        val floatingDragX = FloatingDragAxis()
        val floatingDragY = FloatingDragAxis()
        KeyboardCallbacks(
            onLetterNeighbors = { schema, encoded -> service.keyRouter.letterNeighbors = schema to encoded },
            onGlideWord = service.keyRouter::commitGlideWord,
            onGlideCode = service.keyRouter::typeGlideCode,
            inputAdmissionTicket = { if (service.keyRouter.hasPendingCandidateCommit) null else service.inputReadiness.ticket() },
            captureInputContext = service.keyRouter::captureInputContext,
            t9LeftContext = {
                if (service.candidateState.value.inputText.isNotEmpty() || service.t9PartialSegments.isNotEmpty() ||
                    service.isSecretEditor()) null
                else runCatching { service.currentInputConnection?.getTextBeforeCursor(16, 0)?.toString() }.getOrNull()
            },
            inputCommands = service.inputCommands,
            onT9UnconsumedDelete = service.keyRouter::processDeleteKey,
            onRetryRejectedCommit = service.keyRouter::retryRejectedCommit,
            onCancelRejectedCommit = service.keyRouter::cancelRejectedCommit,
            onOpenPreeditEditor = preeditEditor::open,
            onLivePreeditEdit = preeditEditor::edit,
            onRequestPinyinSyllables = syllableSelector::request,
            onSelectPinyinSyllable = syllableSelector::select,
            onKeyPress = keyPress@{ key, isShifted ->
                if (service.inputReadiness.ticket() == null) return@keyPress
                com.kingzcheung.xime.util.InputLatencyTrace.beginSimple()
                if (service.keyRouter.usesDirectFieldInput) {
                    service.keyRouter.handleKeyPress(key, isShifted)
                    return@keyPress
                }
                if (service.keyboardCallbacks?.onPreeditKeyInput?.invoke(key) != true) {
                    val numberPanel = service.keyboardViewModel.keyboardState.value is com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.Number
                    val separator = key == "'" && service.candidateState.value.isComposing && !service.uiState.value.isAsciiMode
                    // 微软双拼的 ing 在分号上：分号键交给按键队列，打字中送进 Rime 组码，没在打字时照旧按标点上屏（见 ImeKeyRouter）。
                    val semicolonCode = key == ";" && service.keyRouter.semicolonIsCode()
                    // Only momentary soft Shift is a literal Latin chord. Keep one-shot/caps
                    // in Chinese composition, and let the internal preedit editor intercept first.
                    val heldLatin = service.keyboardViewModel.shiftMode.value == com.kingzcheung.xime.viewmodel.ShiftMode.HELD &&
                        service.uiState.value.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE &&
                        key.length == 1 && key[0] in 'A'..'Z'
                    if (heldLatin) {
                        service.textCommit.commitLiteralText(key, preserveWidth = true)
                    } else if (!numberPanel && !separator && !semicolonCode && isLiteralPunctuation(key)) {
                        service.textCommit.commitLiteralText(key)
                    } else {
                        val mergedKey = com.kingzcheung.xime.rime.MergedLetterInput.isGroup(key,
                            !service.uiState.value.isAsciiMode &&
                            service.uiState.value.inputProfile.layout == com.kingzcheung.xime.settings.InputLayout.MERGED14)
                        service.keyRouter.handleKeyPress(if (mergedKey) key.take(1) else key, isShifted,
                            mergedKey = mergedKey)
                    }
                }
            },
            onJapaneseKanaAction = kana@{ action ->
                if (service.inputReadiness.ticket() == null) return@kana
                val input = (action as? com.kingzcheung.xime.ui.keyboard.JapaneseKanaAction.Input)?.romaji
                if (input in listOf(",", ".", "?", "!", "/", "(", ")")) {
                    val punctuation = when (input) { "," -> "、"; "." -> "。"; "/" -> "・"; else -> input!! }
                    service.textCommit.commitLiteralText(punctuation)
                } else service.schemaController.handleJapaneseKanaAction(action)
            },
            onKeyPressDown = { key ->
                if (key == "delete") service.predictionManager.cancelForDeletePress()
                else service.predictionManager.invalidatePendingPredictions()
                service.feedbackManager.performKeyPressDownEffect(key, view)
            },
            onKeyRelease = { key ->
                service.feedbackManager.hapticFeedback(view, keyUp = true, type = KeyFeedbackType.fromKey(key))
            },
            onCandidateSelect = { index ->
                service.keyRouter.selectCandidate(index)
            },
            onNextCandidate = service.keyRouter::highlightNextCandidate,
            onPreviousCandidate = service.keyRouter::highlightPreviousCandidate,
            isCandidateSnapshotCurrent = { snapshot -> snapshot.hasSameSelectionSource(service.candidateState.value) },
            onCandidateDelete = { index ->
                service.keyRouter.deleteCandidate(index)
            },
            onAssociationSelect = { index ->
                service.feedbackManager.performKeyPressEffect(view = view)
                service.keyRouter.selectAssociation(index)
            },
            onClearAssociation = {
                // 清空英文联想候选，并结束英文输入态：pendingEnglish 清空后，
                // 后续刷新不再触发联想异步回填（否则"清了又冒出来"，见
                // ImeSessionController/updateUIWithResult 的在途回填校验）。
                service.candidateState.value = service.candidateState.value.copy(
                    associationCandidates = emptyList(),
                    pendingEnglishText = ""
                )
            },
            onToggleDarkMode = { service.toggleDarkMode() },
            onClipboard = {},
            onDismissClipboardPreview = {
                service.candidateState.value.clipboardImage?.let { service.clipboardManager.images.dismissPreview(it.uri) }
                if (service.candidateState.value.isShowingRecentClipboard) {
                    // 关掉过的短信验证码不再在下次弹出键盘时出现；系统剪贴板里照旧留到 10 分钟。
                    if (service.candidateState.value.smsVerificationCode != null) service.clipboardManager.dismissVerificationCode()
                    service.candidateState.value = service.candidateState.value.copy(
                        candidates = emptyList(),
                        candidateComments = emptyList(),
                        isShowingRecentClipboard = false,
                        clipboardImage = null,
                    )
                }
            },
            onClipboardSelect = { text -> service.textCommit.selectClipboardItem(text) },
            onClipboardRecordCommitted = { service.feedbackManager.playClipboardCommitSound() },
            onClipboardImageSelect = { image ->
                service.serviceScope.launch {
                    try {
                        when (service.textCommit.pasteClipboardImage(image)) {
                            false -> service.keyboardViewModel.showOverlay(OverlayRoute.Clipboard(2))
                            null -> android.widget.Toast.makeText(service, "请先选中支持图片的输入框", android.widget.Toast.LENGTH_SHORT).show()
                            true -> service.clipboardManager.images.clearPasteFailure()
                        }
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(service, "图片无法读取，请重新选择（最大 32 MiB）", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onExpandClipboardImages = {
                service.uiState.value = service.uiState.value.copy(clipboardImagesExpanded = true)
                service.keyboardViewModel.showOverlay(OverlayRoute.Clipboard(2))
            },
            onCollapseClipboardImages = {
                service.uiState.value = service.uiState.value.copy(clipboardImagesExpanded = false)
            },
            onClipboardImageSystemPaste = {
                val failure = service.clipboardManager.images.pasteFailure.value
                if (failure?.packageName == service.currentInputEditorInfo?.packageName) {
                    service.dismissPredictionsForPaste()
                    val pasted = runCatching { service.currentInputConnection?.performContextMenuAction(android.R.id.paste) == true }.getOrDefault(false)
                    if (pasted) service.clipboardManager.images.clearPasteFailure()
                    if (!pasted) android.widget.Toast.makeText(service, "此输入框不支持系统图片粘贴，请使用分享图片", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onClipboardImageShare = { image, targetPackage ->
                val uri = android.net.Uri.parse(image.uri)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = image.mimeType
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newUri(service.contentResolver, "图片", uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (!targetPackage.isNullOrBlank()) {
                    val targeted = android.content.Intent(send).setPackage(targetPackage)
                    if (targeted.resolveActivity(service.packageManager) != null) send.setPackage(targetPackage)
                }
                runCatching {
                    service.startActivity(android.content.Intent.createChooser(send, "分享图片").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { android.widget.Toast.makeText(service, "没有可接收图片的应用", android.widget.Toast.LENGTH_SHORT).show() }
            },
            onClipboardPullRemote = { service.clipboardSyncBridge?.pullOnce() },
            onCommitText = { text -> if (service.inputReadiness.ticket() != null) service.textCommit.commitLiteralText(text) },
            onCommitExactText = { text -> if (service.inputReadiness.ticket() != null) service.textCommit.commitLiteralText(text, preserveWidth = true) },
            onDeleteText = { count -> service.textCommit.deleteClipboardChars(count) },
            onKeyboardResize = {
                service.keyboardViewModel.closeOverlay()
                val config = service.resources.configuration
                val s = service.uiState.value
                val currentSize = service.currentResolvedKeyboardSize ?: service.resolveKeyboardSize(
                    config.screenWidthDp, effectiveScreenH,
                    if (s.isFloatingMode) floatingMinY else s.keyboardBottomPaddingDp + floatingMinY, s,
                )
                // The displayed base size excludes expanded clipboard/tool panels and already
                // incorporates automatic defaults; raw height preferences do not describe it.
                val currentHeight = currentSize.height
                val currentWidth = currentSize.width
                service.uiState.value = service.uiState.value.copy(
                    showKeyboardResize = true,
                    resizePreviewHeightDp = currentHeight,
                    resizePreviewWidthDp = currentWidth,
                    fixedWidthDp = if (s.isFloatingMode) s.fixedWidthDp else currentWidth,
                    resizeInitialFloating = service.uiState.value.isFloatingMode,
                    resizeInitialSplit = SettingsPreferences.isSplitKeyboardEnabled(service),
                    resizeInitialWidthDp = currentWidth,
                    resizeInitialX = service.uiState.value.floatingOffsetX,
                    resizeInitialY = service.uiState.value.floatingOffsetY,
                )
            },
            onReloadConfig = { service.schemaController.reloadConfig() },
            onSettings = { service.schemaController.openSettings() },
            onSettingsPage = { service.schemaController.openSettings(it) },
            onSwitchSchema = { schemaId -> if (service.inputReadiness.ticket() != null) service.schemaController.switchSchema(schemaId) },
            onHandwritingExpand = { expanded -> service.uiState.value = service.uiState.value.copy(handwritingExpanded = expanded) },
            onHandwritingToggle = { service.schemaController.toggleHandwriting() },
            onToggleSchemaSwitch = { sw -> service.sessionController.toggleSchemaSwitch(sw) },
            onHideKeyboard = { service.hideKeyboard() },
            onSwitchKeyboard = {
                val imm = service.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                @Suppress("DEPRECATION")
                imm.showInputMethodPicker()
            },
            onToolbarEditingAction = { action -> service.schemaController.handleToolbarEditingAction(action) },
            onCommitImage = { imagePath ->
                val success = service.textCommit.commitImage(imagePath)
                if (!success) {
                    android.widget.Toast.makeText(
                        service,
                        "发送失败，已复制到剪贴板",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    service.clipboardManager.copyImageToSystemClipboard(imagePath)
                }
            },
            onVoiceModeChange = { enabled ->
                if (!enabled) {
                    // 长按抬手：结束语音会话（提交当前已识别文本并停止识别）
                    service.endVoiceSession()
                } else if (!service.uiState.value.isVoiceMode && !service.uiState.value.isSttEnabled) {
                    // 「语音转文本」总开关关着：屏幕键盘也不开麦，与物理键盘 Alt+H 同一句提示。
                    android.widget.Toast.makeText(service, "请先在设置中启用语音输入", android.widget.Toast.LENGTH_SHORT).show()
                } else if (!service.uiState.value.isVoiceMode && !service.editorInputPolicy.locksNativeInput) {
                    service.uiState.value = service.uiState.value.copy(
                        isVoiceMode = true,
                        voiceSticky = false,
                        voiceButtonState = VoiceButtonState(bottomActive = true),
                        voiceRecognizedText = ""
                    )
                    service.keyboardViewModel.enterVoice()
                    service.feedbackManager.performVibration()
                    service.isTrackingVoiceButtons = true
                    service.keyboardContainer.enableVoiceButtonTracking()
                    service.voiceRecordingStarted = true
                    // 立即提前启动麦克风录音（等 150ms 的话模型常驻时加载极快，
                    // preStarted 可能还没创建好就被 startRecording 跳过，导致开头丢失）
                    service.voiceRecognitionHandler.startDelayedPreStart(0)
                    service.voiceRecognitionHandler.startRecognition()
                }
            },
            onVoiceStickyToggle = {
                val state = service.uiState.value
                if (state.isVoiceMode && state.voiceSticky) {
                    // 再次点击麦克风：停止录音并提交转录。
                    service.endVoiceSession()
                } else if (!state.isVoiceMode && !state.isSttEnabled) {
                    // 「语音转文本」总开关关着：工具栏麦克风和长按空格说话都不开麦（原来只有物理键盘 Alt+H 认这个开关）。
                    android.widget.Toast.makeText(service, "请先在设置中启用语音输入", android.widget.Toast.LENGTH_SHORT).show()
                } else if (!state.isVoiceMode && !service.editorInputPolicy.locksNativeInput) {
                    // 工具栏独立语音组件，不占用空格或改变键盘页面。
                    service.feedbackManager.playKeySound("voice_start")
                    service.uiState.value = service.uiState.value.copy(
                        isVoiceMode = true,
                        voiceSticky = true,
                        voiceButtonState = VoiceButtonState(bottomActive = true),
                        voiceRecognizedText = ""
                    )
                    service.voiceRecordingStarted = true
                    service.voiceRecognitionHandler.startDelayedPreStart(0)
                    service.voiceRecognitionHandler.startRecognition()
                }
            },
            onVoiceHoldEnd = {
                // 按住空格说话松手：结束并上屏。只收尾工具栏同一条语音路径，没开起来（输入框不许语音等）时不开麦。
                val state = service.uiState.value
                if (state.isVoiceMode && state.voiceSticky) service.endVoiceSession()
            },
            onPageDown = { service.keyRouter.pageDown() },
            onPageUp = { service.keyRouter.pageUp() },
            onGlobalCandidateSelect = { globalIndex ->
                service.keyRouter.selectCandidateGlobal(globalIndex)
            },
            onGlobalCandidateDelete = { globalIndex ->
                service.keyRouter.deleteCandidateGlobal(globalIndex)
            },
            onRequestExpandedCandidates = {
                // Expanded candidates have global indices; leave the collapsed page's focus behind.
                service.candidateState.value = service.candidateState.value.copy(candidateFocus = null)
                service.refreshExpandedCandidates()
            },
            onExtendCandidateStrip = { service.extendCandidateStrip() },
            onCursorMove = { direction ->
                if (service.keyboardCallbacks?.onPreeditCursorMove?.invoke("preedit_cursor:$direction") == true) {
                    service.feedbackManager.cursorStepFeedback(view)
                } else service.schemaController.moveEditorCursor(direction, spaceCursorSelecting(service)) {
                    service.feedbackManager.cursorStepFeedback(view)
                }
            },
            onCursorMoveVertical = { steps ->
                val edge = if (steps < 0) "start" else "end"
                if (service.keyboardCallbacks?.onPreeditCursorMove?.invoke("preedit_cursor:$edge") != true)
                    service.schemaController.moveEditorCursorVertical(steps, spaceCursorSelecting(service))
            },
            onGestureAction = { action, value ->
                action.execute(service, value)
            },
            onUpdateToolbarButtons = { buttons ->
                SettingsPreferences.setToolbarButtons(service, buttons)
                service.uiState.value = service.uiState.value.copy(toolbarButtons = buttons)
            },
            onUpdateQuickActions = { layout ->
                SettingsPreferences.setQuickActions(service, layout)
                service.uiState.value = service.uiState.value.copy(
                    toolbarButtons = layout.toolbar, menuButtons = layout.menu)
            },
            onOpenToolPanel = { pluginId -> service.openToolPanel(pluginId) },
            onToolPanelClose = { service.closeToolPanel() },
            onToolPanelItemClick = { item -> service.commitToolPanelItem(item.text) },
            onToolPanelAction = { actionId -> service.dispatchToolPanelAction(actionId) },
            onClipboardSearchOpen = { service.openClipboardSearch() },
            onClipboardSearchClose = { service.closeClipboardSearch(openBoard = true) },
            onClipboardSearchSubmit = { service.submitClipboardSearch() },
            onClipboardSearchSelect = { text -> service.selectClipboardSearchResult(text) },
            onClipboardSearchImageSelect = { image ->
                service.feedbackManager.playClipboardCommitSound()
                // Leave search first: an image never goes into the search field.
                service.closeClipboardSearch(openBoard = false)
                service.keyboardCallbacks?.onClipboardImageSelect?.invoke(image)
            },
            onToolPanelFocusChange = { focused ->
                val ui = service.uiState.value
                service.uiState.value = ui.copy(toolPanelInputFocused = ui.toolPanelFocusAfter(focused))
            },
            onKeyboardModeChange = { chineseMode ->
                if (service.isChineseMode != chineseMode) {
                    service.isChineseMode = chineseMode
                    if (!chineseMode) {
                        service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList())
                    }
                }
            },
            onDismissDeploying = { service.hideKeyboard() },
            onFloatingModeChange = { enabled -> service.schemaController.toggleFloatingMode(enabled, floatingMinY, persist = !service.uiState.value.showKeyboardResize) },
            onFloatingKeyboardDrag = { dx, dy ->
                val s = service.uiState.value
                val density = service.resources.displayMetrics.density
                val screenW = (service.keyboardContainer.width / density).roundToInt().takeIf { it > 0 }
                    ?: service.resources.configuration.screenWidthDp
                val screenH = (service.keyboardContainer.height / density).roundToInt().takeIf { it > 0 }
                    ?: effectiveScreenH
                val cardWidth = service.currentFloatingCardWidthDp.takeIf { it > 0 }
                    ?: s.floatingWidthDp.takeIf { it > 0 } ?: screenW
                val halfMargin = ((screenW - cardWidth) / 2f).roundToInt().coerceAtLeast(0)
                val actualCardH = if (service.currentFloatingCardHeightDp > 0) service.currentFloatingCardHeightDp else service.currentEffectiveKeyboardHeight
                // 转着的卡片与界面用同一套旋转限位：顶部条和拖动按钮在屏内，底角可以略出屏。
                val rotated = com.kingzcheung.xime.ui.keyboard.floatingRotatedOffsetBounds(screenW.toFloat(), screenH.toFloat(),
                    cardWidth.toFloat(), actualCardH.toFloat(), s.floatingRotation, floatingMinY.toFloat())
                    .takeIf { com.kingzcheung.xime.ui.keyboard.isFloatingRotated(s.floatingRotation) && it.feasible }
                val maxOffsetY = (screenH - actualCardH).coerceAtLeast(floatingMinY)
                val newX = if (rotated != null) rotated.clampX(s.floatingOffsetX + dx).roundToInt()
                    else floatingDragX.move(s.floatingOffsetX.coerceIn(-halfMargin, halfMargin), dx, -halfMargin, halfMargin)
                val newY = if (rotated != null) rotated.clampY(s.floatingOffsetY + dy).roundToInt()
                    else floatingDragY.move(s.floatingOffsetY.coerceIn(floatingMinY, maxOffsetY), dy, floatingMinY, maxOffsetY)
                service.uiState.value = s.copy(
                    floatingOffsetX = newX,
                    floatingOffsetY = newY,
                )
            },
            onFloatingKeyboardRotate = { degrees ->
                val s = service.uiState.value
                val angle = degrees.coerceIn(-90f, 90f)
                service.uiState.value = s.copy(floatingRotation = angle)
                // 只在调节界面点确认时调用，直接保存。
                val isLandscape = service.resources.configuration.screenWidthDp > service.resources.configuration.screenHeightDp
                SettingsPreferences.setFloatingRotation(service, angle, isLandscape)
                // 卡片矩形可能没变，触摸区却要按新角度重算。
                service.keyboardContainer.requestLayout()
            },
            onFloatingKeyboardResize = { widthDp, heightDp, offsetXDp, bottomOffsetDp ->
                // 拖亮着的角标松手：和调节界面确认悬浮尺寸同样保存（高度按悬浮高度范围存，宽度和位置按横竖屏存）。
                // 分体两半贴边占满宽度，不覆盖非分体时的宽度和水平位置。
                val s = service.uiState.value
                val isLandscape = service.resources.configuration.screenWidthDp > service.resources.configuration.screenHeightDp
                val split = service.splitKeyboardActive(s)
                val savedHeight = KeyboardHeightProfiles.save(service, true, isLandscape, heightDp, effectiveScreenH)
                val width = if (split) s.floatingWidthDp else widthDp
                val offsetX = if (split) s.floatingOffsetX else offsetXDp
                if (!split) {
                    SettingsPreferences.setFloatingWidthDp(service, width, isLandscape)
                    SettingsPreferences.setFloatingOffsetX(service, offsetX, isLandscape)
                }
                SettingsPreferences.setFloatingOffsetY(service, bottomOffsetDp, isLandscape)
                service.uiState.value = service.uiState.value.copy(
                    keyboardHeightDp = savedHeight,
                    floatingWidthDp = width,
                    floatingOffsetX = offsetX,
                    floatingOffsetY = bottomOffsetDp,
                )
                service.refreshKeyboardGeometry()
            },
            onFloatingKeyboardDragEnd = {
                floatingDragX.reset()
                floatingDragY.reset()
                val s = service.uiState.value
                val isLandscape = service.resources.configuration.screenWidthDp > service.resources.configuration.screenHeightDp
                if (!s.showKeyboardResize) {
                    SettingsPreferences.setFloatingOffsetX(service, s.floatingOffsetX, isLandscape)
                    SettingsPreferences.setFloatingOffsetY(service, s.floatingOffsetY, isLandscape)
                }
            },
            onT9ReplaceFullPinyin = { pinyin ->
                service.serviceScope.launch(service.keyProcessingDispatcher) {
                    when {
                        pinyin == T9InputController.CLEAR_COMPOSITION_ONLY -> {
                            service.rimeEngine.clearComposition()
                        }
                        pinyin == T9InputController.CLEAR_ALL -> {
                            service.t9PartialSegments.clear()
                            service.rimeEngine.endQueuedComposition(t9 = true)
                        }
                        pinyin.isEmpty() -> {
                            service.rimeEngine.clearComposition()
                        }
                        else -> {
                            service.rimeEngine.setInput(pinyin)
                        }
                    }
                    val composition = service.rimeEngine.getComposition()
                    withContext(Dispatchers.Main) {
                        service.mainHandler.post { service.sessionController.applyComposition(composition) }
                    }
                }
            },
            onT9RightCommitUndone = { count ->
                // 右侧候选的 partial commit 只存在于输入法状态的 t9PartialSegments：
                // 候选栏模式只在候选栏显示，输入框模式写在 composing 区里，**都没有写入正文**。
                // 历史实现把 C++ 的"撤销段数"当成正文字符数调用 deleteBeforeCursor(count)，
                // 直接把光标前的正文删掉（2026-09-25 真机实证：选词后退格删掉正文）。
                if (TerminalEditor.inputTextLocation(service)
                    == SettingsPreferences.INPUT_TEXT_INPUT_BOX) {
                    service.endComposingInputBox()
                }
                // 只回滚输入法自己的段状态：count 是段数，不是字符数。
                val removed = service.t9PartialSegments.rollbackPartialSegments(count)
                // Partial selections have not been learned. Undo must never delete a user phrase.
                if (removed.isNotEmpty()) {
                    service.uiState.value = service.uiState.value.copy(
                        t9RightCandidateSelectedCount =
                            (service.uiState.value.t9RightCandidateSelectedCount - removed.size).coerceAtLeast(0L),
                        t9SelectedCandidatePinyin = ""
                    )
                }
            },
            onT9RefreshComposition = { composition, injections ->
                // composition 由 T9 控制器在 flush 后一次取回并传入，
                // 避免在此再次 getComposition 造成重复 JNI 往返。
                // 控制器已在 Main 执行并校验刷新代际；二次 post 会让旧快照越过 literal 复位。
                service.sessionController.applyComposition(composition, emptyList(), injections)
            },
            onTCandidateTransform = { result ->
                // T9 后台线程（t9Dispatcher）调用：同步等插件至多 15ms，主线程零等待
                service.candidateTransform.transformForT9(result)
            },
            onT9SwitchAway = {
                service.keyRouter.postRimeJob {
                    service.sessionController.commitFirstCandidateAndClearT9()
                }
            },
            onCommitCandidateBeforeModeChange = {
                service.keyRouter.postRimeJob {
                    service.textCommit.commitCompositionAndLiteral()
                }
            },
            onCommitTypedBeforeModeChange = {
                service.keyRouter.postRimeJob {
                    service.textCommit.commitCompositionAndLiteral(asTyped = true)
                }
            },
            onShowQuickSendForm = {
                // 从 Overlay 页面（剪贴板面板）打开表单时必须退出 Overlay：
                // Overlay 渲染为全屏 clickable Box（吞点击），若不退出，表单会被
                // 盖在其下——可见但关闭按钮等点击全部失效（openQuickSend 统一处理）。
                // The floating editor reuses IME input without adding height to the keyboard.
                service.openQuickSend()
            },
            // 同 onShowQuickSendForm：编辑入口在剪贴板面板内，先退出 Overlay 防表单被盖
            onQuickSendEditItem = { id, text, code -> service.openQuickSend(itemId = id, text = text, code = code) },
            onReviewOcrText = { text -> service.openQuickSend(text = text, ocr = true) },
            onHideQuickSendForm = {
                android.util.Log.d("QuickSendForm", "onHideQuickSendForm invoked")
                service.closeQuickSend()
                service.keyboardViewModel.showOverlay(OverlayRoute.Clipboard(0))
            },
            // 关闭表单时由 closeQuickSend 统一还原回车键。
            onQuickSendFormFocusChange = { focused: Boolean ->
                service.uiState.value = QuickSendTransitions.textFocus(service.uiState.value, focused)
            },
            onQuickSendCodeFocusChange = { focused: Boolean ->
                service.uiState.value = QuickSendTransitions.codeFocus(service.uiState.value, focused)
            },
        )
    }
}

/** 空格移光标时按 Shift 扩选；扩选算用掉了 Shift：按住的松开后不切成单次大写，单次大写随即取消。 */
private fun spaceCursorSelecting(service: XimeInputMethodService): Boolean =
    com.kingzcheung.xime.ui.keyboard.SpaceCursorShift.selecting(service.keyboardViewModel.shiftMode.value).also {
        if (it) service.keyboardViewModel.onCharacterTyped()
    }
