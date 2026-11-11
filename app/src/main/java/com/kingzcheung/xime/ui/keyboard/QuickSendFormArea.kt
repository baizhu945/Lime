package com.kingzcheung.xime.ui.keyboard

import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kingzcheung.xime.clipboard.ClipboardDraftSaveResult
import com.kingzcheung.xime.service.QuickSendFormCodeEditTextHolder
import com.kingzcheung.xime.service.QuickSendFormEditTextHolder

/** A separate editor window above the keyboard; existing IME input still targets the draft. */
@Composable
fun QuickSendFormArea(
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    isFocused: Boolean,
    initialText: String = "",
    initialCode: String = "",
    cardBgColor: Color,
    editingItemId: Long? = null,
    onClose: (text: String, code: String) -> Unit,
    onCancel: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onCodeFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = if (editingItemId != null) "编辑剪贴板" else "新增固定",
    // Clipboard items no longer carry a trigger code; phrases with codes live in 自定义短语.
    showCode: Boolean = false,
    onRetake: (() -> Unit)? = null,
) {
    val glass = LocalKeyboardInputPreferences.current.frostedGlass.enabled
    // Drafts overlay other content, so they need their own opaque theme surface and matching text.
    val popupBackground = if (glass) MaterialTheme.colorScheme.surface.copy(alpha = 1f)
        else if (backgroundColor.alpha > 0f) backgroundColor else cardBgColor
    val popupText = if (glass) MaterialTheme.colorScheme.onSurface.copy(alpha = 1f) else textColor
    val currentOnFocusChange by rememberUpdatedState(onFocusChange)
    val currentOnCodeFocusChange by rememberUpdatedState(onCodeFocusChange)
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx() }
    val safeTop = WindowInsets.statusBars.getTop(density)
    var availableHeight by remember { mutableIntStateOf(0) }
    val position = remember(gap, safeTop) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
            layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            // Position only. Available height is measured from the anchor in
            // onGloballyPositioned: writing state from this layout pass did not
            // reliably re-measure the editor when the keyboard grew.
            return IntOffset(
            (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
                .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - popupContentSize.height - gap)
                .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
            )
        }
    } }
    val maxHeight = if (availableHeight > 0) with(density) { availableHeight.toDp() }
        else (LocalConfiguration.current.screenHeightDp * .45f).dp
    // Short drafts wrap tightly; long drafts scroll only after using the free space above the IME.
    val editorMaxHeight = (maxHeight - (if (title == null) 60.dp else 92.dp) - if (showCode) 44.dp else 0.dp).coerceAtLeast(24.dp)
    // The popup is a sub-window where Android offers no text menu; the editor reports when the
    // footer should show selection actions instead.
    var selectionActions by remember(editingItemId) { mutableStateOf(false) }
    val selectableEditor = remember { arrayOfNulls<PopupSelectableEditText>(1) }
    val save: () -> Unit = {
        val editor = QuickSendFormEditTextHolder.editText
        val text = editor?.text?.toString().orEmpty()
        val code = if (showCode) QuickSendFormCodeEditTextHolder.editText?.text?.toString().orEmpty() else ""
        if (text.isBlank()) {
            editor?.error = ClipboardDraftSaveResult.EMPTY_TEXT.errorMessage
            editor?.requestFocus()
        } else onClose(text, code)
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(0.dp)
        .onGloballyPositioned { coordinates ->
            val above = (coordinates.positionInRoot().y - safeTop - gap * 2).toInt()
            if (above > 0) availableHeight = above
        }
        .testTag("clipboard-editor-anchor")) {
        val width = (maxWidth - 16.dp).coerceAtLeast(1.dp).coerceAtMost(640.dp)
        Popup(popupPositionProvider = position,
            properties = PopupProperties(focusable = false, dismissOnBackPress = false,
                dismissOnClickOutside = false, clippingEnabled = false)) {
            Column(Modifier.width(width).clip(RoundedCornerShape(16.dp))
                // Gradient/image themes paint the theme backdrop like the keyboard panels; a solid
                // theme keeps its colour. Never glass: the editor floats over the app without a
                // blur window, so it stays opaque and readable.
                .keyboardPanelBackground(popupBackground, com.kingzcheung.xime.settings.FrostedGlassConfig(enabled = false))
                .testTag("clipboard-text-editor")
                .padding(12.dp)) {
                if (title != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = popupText.copy(alpha = .75f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "关闭", tint = popupText, modifier = Modifier.size(20.dp))
                    }
                }
                // Only a different record starts a new draft. Focus and theme recompositions must
                // not replace text or move the cursor back to the initial saved content.
                key(editingItemId) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AndroidView(
                            factory = { context ->
                                PopupSelectableEditText(context).apply {
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    showSoftInputOnFocus = false
                                    onSelectionActionsChanged = { selectionActions = it }
                                    selectableEditor[0] = this
                                    hint = if (onRetake == null) "输入剪贴板内容" else ""
                                    textSize = 16f
                                    isSingleLine = false
                                    minLines = 1
                                    isVerticalScrollBarEnabled = true
                                    gravity = Gravity.TOP or Gravity.START
                                    setPadding(12, 8, 12, 8)
                                    setText(initialText)
                                    setSelection(initialText.length)

                                    imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or
                                        EditorInfo.IME_ACTION_NONE

                                    onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                                        currentOnFocusChange(hasFocus)
                                    }
                                    setOnClickListener {
                                        currentOnFocusChange(true)
                                    }
                                    QuickSendFormEditTextHolder.editText = this
                                    if (isFocused) {
                                        post { requestFocus() }
                                    }
                                }
                            },
                            update = { editText ->
                                editText.setTextColor(popupText.toArgb())
                                editText.setHintTextColor(popupText.copy(alpha = 0.4f).toArgb())
                                editText.handleColor = accentColor.toArgb()
                                editText.highlightColor = accentColor.copy(alpha = 0.3f).toArgb()
                            },
                            onReset = null,
                            onRelease = { editText ->
                                if (selectableEditor[0] === editText) selectableEditor[0] = null
                                if (QuickSendFormEditTextHolder.editText === editText) {
                                    QuickSendFormEditTextHolder.editText = null
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = minOf(32.dp, editorMaxHeight), max = editorMaxHeight),
                        )
                        if (showCode) AndroidView(
                            factory = { context ->
                                android.widget.EditText(context).apply {
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    showSoftInputOnFocus = false
                                    hint = "编码（选填，如 dh）"
                                    textSize = 14f
                                    isSingleLine = true
                                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                                    setPadding(12, 4, 12, 4)
                                    setText(initialCode)
                                    setSelection(initialCode.length)

                                    imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or
                                        EditorInfo.IME_ACTION_NEXT

                                    // 焦点回调 + 点击抢焦点：宿主按键输入按焦点路由（文本框 vs 编码框）
                                    onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                                        currentOnCodeFocusChange(hasFocus)
                                    }
                                    setOnClickListener {
                                        requestFocus()
                                        currentOnCodeFocusChange(true)
                                    }
                                    QuickSendFormCodeEditTextHolder.editText = this
                                }
                            },
                            update = { editText ->
                                editText.setTextColor(popupText.toArgb())
                                editText.setHintTextColor(popupText.copy(alpha = 0.4f).toArgb())
                            },
                            onReset = null,
                            onRelease = { editText ->
                                if (QuickSendFormCodeEditTextHolder.editText === editText) {
                                    QuickSendFormCodeEditTextHolder.editText = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    if (selectionActions) {
                        // Same footer height, so the draft never jumps while text is selected.
                        TextButton(onClick = { selectableEditor[0]?.selectAllText() }) { Text("全选", color = popupText) }
                        TextButton(onClick = { selectableEditor[0]?.copySelection() }) { Text("复制", color = popupText) }
                        TextButton(onClick = { selectableEditor[0]?.cutSelection() }) { Text("剪切", color = popupText) }
                        TextButton(onClick = { selectableEditor[0]?.pasteClipboard() }) { Text("粘贴", color = popupText) }
                    } else {
                        onRetake?.let { retake ->
                            TextButton(onClick = retake) { Text("重拍", color = popupText) }
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(onClick = onCancel) { Text("取消", color = popupText) }
                        TextButton(onClick = save) { Text("保存", color = accentColor) }
                    }
                }
            }
        }
    }
}
