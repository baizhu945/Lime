package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import com.kingzcheung.xime.viewmodel.SchemaSwitchUiState

data class InputUIState(
    val rejectedCommitText: String? = null,
    val isAsciiMode: Boolean = false,
    val schemaName: String = "",
    val currentSchemaId: String = "",
    val schemas: List<SchemaInfo> = emptyList(),
    val schemaSwitches: List<SchemaSwitchUiState> = emptyList(),
    val enterKeyText: String = "发送",
    val darkMode: Int = 0,
    val themeId: String = SettingsPreferences.defaultKeyboardTheme,
    val isSttEnabled: Boolean = false,
    val handwritingExpanded: Boolean = false,
    val clipboardImagesExpanded: Boolean = false,
    val keyboardHeightDp: Int = 0,
    val keyboardBottomPaddingDp: Int = 0,
    val keyboardOpacity: Float = 1f,
    val showKeyboardResize: Boolean = false,
    val resizePreviewHeightDp: Int = 0,
    /** 调节中的悬浮宽度；0 = 本次没动宽度（沿用 [floatingWidthDp] 或按高度推导）。 */
    val resizePreviewWidthDp: Int = 0,
    /** 已保存的悬浮宽度（dp）；0 = 未设置，按高度推导（历史行为）。 */
    val floatingWidthDp: Int = 0,
    val fixedWidthDp: Int = 0,
    val fixedOffsetX: Int = 0,
    val resizeInitialFloating: Boolean = false,
    val resizeInitialSplit: Boolean = false,
    val resizeInitialWidthDp: Int = 0,
    val resizeInitialX: Int = 0,
    val resizeInitialY: Int = 0,
    val associationEnabled: Boolean = false,
    val isVoiceMode: Boolean = false,
    val voiceSticky: Boolean = false,
    val voiceButtonState: VoiceButtonState = VoiceButtonState(),
    val voicePluginName: String = "",
    /** 语音出错或改用本地时在键盘上显示的一行提示（有的手机会拦下 Toast）；几秒后自动清掉。 */
    val voiceNotice: String? = null,
    val voiceRecognitionState: RecognitionState = RecognitionState.IDLE,
    val voiceRecognizedText: String = "",
    val voiceAmplitude: Float = 0f,
    /** 语音输入中把键盘收成底部悬浮条（仿 Gboard），语音继续听写。 */
    val voiceCollapsed: Boolean = false,
    val stretchFactor: Float = 1f,
    val isDeploying: Boolean = false,
    val deploymentMessage: String = "",
    val isAddressField: Boolean = false,
    val inputSessionId: Long = 0,
    val t9ResetSignal: Long = 0,
    val swipeCancelEpoch: Long = 0,
    val t9RightCandidateSelectedCount: Long = 0,
    val t9SelectedCandidatePinyin: String = "",
    val toolbarButtons: List<String> = ToolbarButton.DEFAULT_VISIBLE.map { it.id },
    val menuButtons: List<String> = ToolbarButton.DEFAULT_MENU.map { it.id },
    /** 已启用插件声明的工具栏按钮（候选池）。渲染时与内置按钮合并，插件禁用/卸载后自动不显示。 */
    val toolbarPluginButtons: List<ToolbarButtonItem.Plugin> = emptyList(),
    val isCompact: Boolean = false,
    val isFloatingMode: Boolean = false,
    val floatingOffsetX: Int = 0,
    val floatingOffsetY: Int = 0,
    /** 悬浮卡片绕中心的旋转角（度，顺时针为正，-90..90），横竖屏分别保存。 */
    val floatingRotation: Float = 0f,
    /** Current editor's cursor in screen pixels; null until it reports a usable anchor. */
    val hardwareOptions: com.kingzcheung.xime.settings.HardwareKeyboardOptions = com.kingzcheung.xime.settings.HardwareKeyboardOptions(),
    val editorBounds: HardwareCursorAnchor? = null,
    val cursorAnchor: HardwareCursorAnchor? = null,
    val showQuickSendForm: Boolean = false,
    val ocrDraft: Boolean = false,
    val quickSendFormFocused: Boolean = false,
    /** 快捷发送表单内焦点是否在"触发编码"输入框（false=在文本输入框）；决定按键输入路由目标。 */
    val quickSendCodeFocused: Boolean = false,
    val quickSendEditingItemId: Long? = null,
    val quickSendEditingItemText: String = "",
    val quickSendEditingItemCode: String = "",
    /** 通用工具面板是否显示（候选栏上方，与快捷发送同位置）。 */
    val toolPanelVisible: Boolean = false,
    /** 面板输入框是否聚焦（聚焦时按键路由注入面板 EditText）。 */
    val toolPanelInputFocused: Boolean = false,
    /** The clipboard search bar above the keyboard is open; it types through the tool-panel input channel. */
    val clipboardSearchVisible: Boolean = false,
    /** > 0 while clipboard search runs in a minimized floating keyboard because the screen was too short. */
    val clipboardSearchFloatingHeightDp: Int = 0,
    /** 当前打开面板的插件 id。 */
    val toolPanelPluginId: String = "",
    /** 面板标题（插件名）。 */
    val toolPanelTitle: String = "",
    /** 面板输入框预填内容（上下文收集结果）。 */
    val toolPanelPrefillText: String = "",
    /** 面板候选条目（AI 生成结果，点击上屏）。 */
    val toolPanelItems: List<PluginResultItem> = emptyList(),
    /** 面板是否正在生成中（流式生成期间为 true，面板展示 loading）。 */
    val toolPanelLoading: Boolean = false,
    /** 面板请求代际号（防旧结果回填闪动）。 */
    val toolPanelRequestEpoch: Long = 0,
    /** 面板展示模式（manifest display.name）：PASSIVE 时以 Overlay 全屏渲染纯展示 InfoPanel（与表情/符号同级）。 */
    val toolPanelDisplay: String? = null,
    /** passive 纯展示节点树（getPanelState.ui，统一 UiNode 声明式模型）。 */
    val toolPanelUiNodes: List<com.kingzcheung.xime.plugin.core.config.UiNode>? = null,
    val clipboardSyncEnabled: Boolean = false,
) {
    val inputProfile: com.kingzcheung.xime.settings.InputProfile get() {
        val entry = schemas.firstOrNull { it.schemaId == currentSchemaId }
        return com.kingzcheung.xime.settings.InputProfiles.current(currentSchemaId, isAsciiMode, entry?.language, entry?.scheme)
    }
}
