package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlContentPolymorphicSerializer
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.keyboard.GestureAction
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object KeysConfigHelper {
    private const val TAG = "KeysConfigHelper"
    private const val XIME_CONFIG_FILE = "xime.yaml"
    private const val XIME_CUSTOM_CONFIG_FILE = "xime.custom.yaml"

    /** 九键左侧快捷符号栏内置默认值：最常用的中文标点，按使用频率排，括号只留一对（用户 10-31）。 */
    /** 都取自常用符号面板（commonSymbolsFor 中文）；超过 4 个时侧栏可滑动。 */
    val DEFAULT_T9_SIDE_SYMBOLS: List<String> = listOf("，", "。", "？", "！", "…", "～", "、", "：",
        "“", "”", "；", "（", "）")

    /** 笔画键盘左侧符号列内置默认（与历史硬编码一致）。 */
    val DEFAULT_STROKE_SIDE_SYMBOLS: List<String> = listOf("。", "？", "！", "~")
    
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    
    private var config: KeysConfig = KeysConfig(
        swipeUp = getDefaultSwipeUp(),
        swipeDownEnglish = getDefaultSwipeDownEnglish()
    )

    // 手势配置缓存（mutableStateOf 让 Compose 直接观察变更）
    // 中文键盘（qwerty）手势配置缓存
    private val _keyGestureConfig = mutableStateOf<Map<String, KeyGestureConfig>>(emptyMap())
    val keyGestureConfig: Map<String, KeyGestureConfig> get() = _keyGestureConfig.value
    
    // 英文键盘（qwerty_en）手势配置缓存
    private val _keyGestureConfigEn = mutableStateOf<Map<String, KeyGestureConfig>>(emptyMap())
    val keyGestureConfigEn: Map<String, KeyGestureConfig> get() = _keyGestureConfigEn.value
    private var _keyGestureConfigEnBase: Map<String, KeyGestureConfig> = emptyMap()
    
    // 键盘颜色配置缓存
    private var keyboardColorsConfig: KeyboardColorsConfig = KeyboardColorsConfig()
    
    // 键盘阴影配置缓存
    private var keyboardShadowConfig: KeyboardShadowConfig = KeyboardShadowConfig()

    // 键盘按键配置缓存
    private var keyboardKeyConfig: KeyboardKeyConfig = KeyboardKeyConfig()

    // 九键（T9）键盘配置缓存
    private var keyboardT9Config: KeyboardT9Config = KeyboardT9Config(sideSymbols = DEFAULT_T9_SIDE_SYMBOLS)
    private var keyboardStrokeConfig: KeyboardStrokeConfig = KeyboardStrokeConfig(sideSymbols = DEFAULT_STROKE_SIDE_SYMBOLS)

    // 字体配置缓存
    private var keyboardFontConfig: KeyboardFontConfig = KeyboardFontConfig()
    fun getKeyboardFontConfig(): KeyboardFontConfig = keyboardFontConfig
    
    // 按键布局模式缓存（中文 qwerty / 英文 qwerty_en）
    private var _buttonLayoutZh: ButtonLayout = ButtonLayout.STANDARD
    private var _buttonLayoutEn: ButtonLayout = ButtonLayout.STANDARD
    fun getButtonLayout(isAsciiMode: Boolean): ButtonLayout =
        if (isAsciiMode) _buttonLayoutEn else _buttonLayoutZh

    // 键盘行布局默认值
    private val DEFAULT_ZH_ROWS: List<List<String>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )
    private val DEFAULT_EN_ROWS: List<List<String>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )

    // 键盘行布局缓存（中文 / 英文）
    private var _zhRows: List<List<String>> = DEFAULT_ZH_ROWS
    private var _enRows: List<List<String>> = DEFAULT_EN_ROWS
    private var _enRowsBase: List<List<String>> = DEFAULT_EN_ROWS

    // 标准 26 键的基线缓存（合并键布局切换退出时恢复）
    private var _zhRowsLayout = KeyboardRowsLayout(DEFAULT_ZH_ROWS.map { KeyboardRow(it) })
    private var _enRowsLayout = KeyboardRowsLayout(DEFAULT_EN_ROWS.map { KeyboardRow(it) })

    /** 原始 qwerty/qwerty_en 配置；不包含方案或用户字母排列的运行时替换。 */
    fun getRowsLayout(isAsciiMode: Boolean): KeyboardRowsLayout =
        if (isAsciiMode) _enRowsLayout else _zhRowsLayout

    private var _zhRowsBase: List<List<String>> = DEFAULT_ZH_ROWS
    private var _keyGestureConfigZhBase: Map<String, KeyGestureConfig> = emptyMap()

    // 合并键布局缓存：section（qwerty_14/17/18 及 custom 新增）→ 行布局 / 手势配置
    private var _mergedRows: Map<String, List<List<String>>> = emptyMap()
    private var _mergedGestureConfigs: Map<String, Map<String, KeyGestureConfig>> = emptyMap()
    private var _activeMergedSection: String? = null
    private var _appliedLayoutSchemaId: String? = null
    private var _activeSchemaId: String = ""

    // 合并键绑定缓存：schemaId → 键盘 section（xime.yaml keyboard.<section>.schemas 声明）
    private var _schemaSectionBindings: Map<String, String> = emptyMap()
    @Volatile internal var hasLoadedLayoutBindings: Boolean = false
        private set

    /**
     * 代码布局 section：由专属组件渲染（T9KeyboardLayout / StrokeKeyboardLayout /
     * HandwritingKeyboardLayout），无 layout.rows。schemas 绑定到这些 section 的方案
     * 走 [codeLayoutForSchema] 查询，不进入合并键行布局缓存
     * （[mergedSectionForSchema] 对其返回 null）。
     */
    internal val CODE_LAYOUT_SECTIONS = setOf("t9", "stroke", "handwriting", "japanese_kana")

    // 九键/笔画手势配置缓存（keyboard.t9.keys / keyboard.stroke.keys，custom 键级覆盖）。
    // 键 id 不做大小写归一：九键为数字字符串 "1"~"9"，笔画为键面标签（一/丨/丿/丶/乛 等）。
    private var _t9GestureConfigs: Map<String, KeyGestureConfig> = emptyMap()
    private var _strokeGestureConfigs: Map<String, KeyGestureConfig> = emptyMap()

    /** 合并键方案（pinyin_14jian 等）对应的 xime.yaml 键盘 section，非合并键方案返回 null。 */
    internal fun mergedSectionForSchema(schemaId: String): String? =
        resolveMergedSection(schemaId, _schemaSectionBindings) ?: generatedSection(schemaId)

    /**
     * 绑定解析：仅认 schemas 声明（xime.yaml / xime.custom.yaml keyboard.<section>.schemas），
     * 未声明的方案一律全键盘（26 键），不做 id 关键字猜测。
     * 代码布局 section（t9/stroke）不是行数据布局，不作为合并键解析结果。
     */
    internal fun resolveMergedSection(schemaId: String, bindings: Map<String, String>): String? =
        bindings[schemaId]?.takeIf { it !in CODE_LAYOUT_SECTIONS }

    /** 查询方案声明的键盘 section（任意类型，含代码布局），未声明返回 null。 */
    fun boundSectionForSchema(schemaId: String): String? = _schemaSectionBindings[schemaId]
        ?: "t9".takeIf { DoublePinyinSchemes.isT9(schemaId) } ?: generatedSection(schemaId)

    /**
     * 由键位表生成的双拼 14 键版本不写进 xime.yaml，用 14 键小鹤双拼的布局；用户在 xime.custom.yaml
     * 给它声明了别的布局时以声明为准（上面先查绑定）。
     */
    private fun generatedSection(schemaId: String): String? =
        _schemaSectionBindings[DoublePinyinScheme.k14Id("double_pinyin_flypy")]?.takeIf { DoublePinyinSchemes.isK14(schemaId) }

    /** 查询方案绑定的代码布局 section（t9 九键 / stroke 笔画），未绑定返回 null。 */
    fun codeLayoutForSchema(schemaId: String): String? =
        boundSectionForSchema(schemaId)?.takeIf { it in CODE_LAYOUT_SECTIONS }

    /**
     * 仅供单测：清空方案绑定缓存。
     *
     * 绑定只在 [loadConfig] 里由 xime.yaml / xime.custom.yaml 写入，是进程级状态；
     * 其他用例（如 [CyimeResearchPresetTest]）会加载绑定，若先于"未声明即不识别"的断言执行，
     * 断言就会随用例执行顺序变红。需要"未加载绑定"场景的用例先调用本方法。
     */
    internal fun clearSchemaBindingsForTest() {
        _schemaSectionBindings = emptyMap()
        hasLoadedLayoutBindings = false
    }

    /**
     * 按当前方案切换中文行布局与手势缓存（合并键布局 ↔ 标准 26 键）。
     * 在 KeyboardViewModel.dispatch/resetKeyboard 收到 schemaId 时调用；
     * 英文键盘不受影响（合并键布局仅中文拼音方案使用）。
     */
    fun setActiveKeyboardSchema(schemaId: String) {
        _activeSchemaId = schemaId
        val section = mergedSectionForSchema(schemaId)
        if (schemaId == _appliedLayoutSchemaId && section == _activeMergedSection) return
        _appliedLayoutSchemaId = schemaId
        _activeMergedSection = section
        // 自建布局的拼音方案，或双拼在自建合并键布局上的版本：按那个布局的键和手势。
        val custom = CustomKeyboardLayouts.forSchema(schemaId)
        _zhRows = getKeyRows(schemaId, false)
        if (custom != null) {
            _keyGestureConfig.value = custom.gestures(_keyGestureConfigZhBase)
        } else if (section != null) {
            // Japanese romaji types on the arrangement chosen for Japanese; its own section keeps the swipes.
            val arranged = letterLayout(schemaId, false)
            val gestures = _mergedGestureConfigs[section] ?: emptyMap()
            _keyGestureConfig.value = arranged?.gestures(gestures) ?: gestures
        } else {
            // Double pinyin and code tables type on the arrangement chosen for them (QWJRTK, Colemak, the user's own …); pinyin has none.
            val arranged = letterLayout(schemaId, false)
            _keyGestureConfig.value = arranged?.gestures(_keyGestureConfigZhBase) ?: _keyGestureConfigZhBase
        }
    }

    /**
     * 「ing 放在 Shift 键上」（默认关，[com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.doublePinyinSemicolonOnShift]）：
     * 开着时不补第二排的分号键，L 留在最右边，分号由打字中的 Shift 键送出（KeyboardLayout）。
     */
    @Volatile private var semicolonOnShift = false

    /** 开关变了就重排当前键盘；没变什么都不做。 */
    fun setSemicolonOnShift(on: Boolean) {
        if (semicolonOnShift == on) return
        semicolonOnShift = on
        applyLetterLayouts()
    }

    /**
     * ing 在分号上的双拼（微软双拼）：排列里没有分号键时在第二排末尾补一个，第二排正好排满十格；
     * 排不下（第二排已满、蜂窝排列）就保持原样，换个带分号的排列再打。分号键按下时送进 Rime，由方案组成编码。
     */
    internal fun withSemicolonKey(rows: List<List<String>>): List<List<String>> =
        if (rows.size != 3 || rows.any { ";" in it } || rows[1].sumOf { it.length } != 9) rows
        else rows.mapIndexed { index, row -> if (index == 1) row + ";" else row }

    /**
     * The letter layout the keyboard is typing on: a Chinese layout schema, or the arrangement chosen
     * for English / Japanese romaji / double pinyin ([LetterLayouts]). null is the plain 26/14 keys of the section.
     */
    fun letterLayout(schemaId: String, isAsciiMode: Boolean): CustomKeyboardLayout? =
        CustomKeyboardLayouts.find(InputProfiles.current(schemaId, isAsciiMode).layout.id)

    /** Re-apply the per-language arrangements after a choice changes. */
    fun applyLetterLayouts() {
        applyEnglishLetterLayout()
        _appliedLayoutSchemaId = null
        setActiveKeyboardSchema(_activeSchemaId)
        _configVersion.value++
    }

    private fun applyEnglishLetterLayout() {
        val english = LetterLayouts.chosen(InputLanguage.ENGLISH)
        _enRows = english?.typingRows() ?: _enRowsBase
        _keyGestureConfigEn.value = english?.gestures(_keyGestureConfigEnBase) ?: _keyGestureConfigEnBase
    }

    private val CONTROL_ROW_IDS = setOf(
        "shift", "delete", "mode_change", "space", "earth", "enter", "comma", "period", "abc", "number", "symbol",
    )

    /** 获取键盘行布局，每个子 List 为一行的按键 ID 列表，索引 0=第一行。
     *  合并键布局中合并键的 ID 为组内字母拼接（如 "qw"），渲染层按 ID 长度分配键宽。 */
    fun getKeyRows(isAsciiMode: Boolean): List<List<String>> =
        // 兼容旧 API：只给字母行。控制行（shift/delete/?123/空格/地球/回车）由渲染层单独负责，
        // 配置里声明的控制行通过 getRowsLayout 读取。
        (if (isAsciiMode) _enRows else _zhRows).map { row ->
            row.filterNot { it in CONTROL_ROW_IDS }
        }.filter { it.isNotEmpty() }

    /** A rendered frame can arrive before the active gesture cache has switched schemes. */
    fun getKeyRows(schemaId: String, isAsciiMode: Boolean): List<List<String>> {
        if (isAsciiMode) return _enRows
        CustomKeyboardLayouts.forSchema(schemaId)?.let { return it.typingRows() }
        val arranged = letterLayout(schemaId, false)?.typingRows()
        val section = mergedSectionForSchema(schemaId)
        if (section != null) return arranged ?: _mergedRows[section] ?: DEFAULT_ZH_ROWS
        val rows = arranged ?: _zhRowsBase
        return if (!semicolonOnShift && DoublePinyinSchemes.find(schemaId)?.usesSemicolon == true)
            withSemicolonKey(rows) else rows
    }

    /** Read a thumbnail without changing the active keyboard or its gesture state. */
    fun previewKeyRows(layout: InputLayout, language: InputLanguage): List<List<String>> =
        CustomKeyboardLayouts.find(layout.id)?.typingRows() ?: when {
            layout.kind == LayoutKind.MERGED || layout.kind == LayoutKind.ZHUYIN -> _mergedRows[layout.id].orEmpty()
            // 有自己排列的字母键盘（西班牙语多一个 ñ）按它自己的行画。
            // 配置里的字母 section 含 shift/delete/?123 等控制键；预览必须先
            // 去掉这些多字符动作，否则控制行会被当成超宽字母行拉长整张缩略图。
            layout.kind == LayoutKind.ALPHABETIC -> {
                val source = _mergedRows[layout.id] ?: if (language == InputLanguage.ENGLISH) _enRowsBase else _zhRowsBase
                val letters = source.map { row -> row.filter { it.length == 1 } }.filter { it.isNotEmpty() }
                if (com.kingzcheung.xime.ui.keyboard.letterKeyboardFrame(letters) != null) letters else source
            }
            else -> emptyList()
        }
    
    /** 配置版本号，每次 loadConfig 时递增，用于 Compose 感知配置变更。 */
    private val _configVersion = MutableStateFlow(0)
    val configVersion: StateFlow<Int> = _configVersion.asStateFlow()
    
    private var mergedConfigCache: XimeConfig? = null
    private var mergedConfigVersion = 0
    
    // The same two YAML documents are read by many section parsers. Share their
    // immutable syntax trees only within one load; every reload sees fresh text.
    // Thread-local scope avoids retaining user configuration or mixing concurrent loads.
    private val loadYamlNodes = ThreadLocal<MutableMap<String, YamlNode>>()
    private fun keyboardYamlNode(text: String): YamlNode {
        val nodes = loadYamlNodes.get()
        return if (nodes == null) yaml.parseToYamlNode(text)
        else nodes.getOrPut(text) { yaml.parseToYamlNode(text) }
    }

    fun loadConfig(context: Context): KeysConfig {
        val previousNodes = loadYamlNodes.get()
        loadYamlNodes.set(mutableMapOf())
        try { loadXimeConfig(context) }
        finally {
            if (previousNodes == null) loadYamlNodes.remove() else loadYamlNodes.set(previousNodes)
        }
        config = config.copy(
            swipeUp = getDefaultSwipeUp(),
            swipeDownEnglish = getDefaultSwipeDownEnglish()
        )
        return config
    }
    
    private var symbolOverridesZh: Map<String, String> = emptyMap()
    private var symbolOverridesEn: Map<String, String> = emptyMap()
    private var symbolCustomZh: Map<String, KeyGestureConfig> = emptyMap()
    private var symbolCustomEn: Map<String, KeyGestureConfig> = emptyMap()
    private var symbolOverridesK14: Map<String, String> = emptyMap()
    /** 自建双字母布局各自的角标修改，按布局 id。 */
    private var symbolOverridesCustom: Map<String, Map<String, String>> = emptyMap()

    private fun loadXimeConfig(context: Context) {
        try {
            val customLayouts = CustomKeyboardLayouts.load(context)
            LetterLayouts.load(context)
            semicolonOnShift = SettingsPreferences.getPrefsPublic(context).getBoolean(
                com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.DOUBLE_PINYIN_SEMICOLON_ON_SHIFT_KEY, false)
            val symbolCustomText = readCustomText(context)
            symbolCustomZh = symbolCustomText?.let { parseKeyboardYamlSection(it, "qwerty") } ?: emptyMap()
            symbolCustomEn = symbolCustomText?.let { parseKeyboardYamlSection(it, "qwerty_en") } ?: emptyMap()
            runCatching { QwertySwipeSymbols.preserveLegacyLayout(context, symbolCustomZh) }
                .onFailure { Log.e(TAG, "无法保存旧布局", it) }
            runCatching { QwertySwipeSymbols.adoptFixedPreset(context, symbolCustomZh) }
                .onFailure { Log.e(TAG, "无法保存10-12布局", it) }
            symbolOverridesZh = QwertySwipeSymbols.load(context, false)
            // 英文26键有自己的半角预设，只用英文页的修改。
            symbolOverridesEn = QwertySwipeSymbols.load(context, true)
            symbolOverridesK14 = FourteenKeySymbols.load(context)
            symbolOverridesCustom = customLayouts.filter(CustomLayoutSymbols::editable)
                .associate { it.id to CustomLayoutSymbols.load(context, it) }
            // 键盘手势（从原始 YAML 手动解析）
            val parsed = parseKeyboardFromAssets(context)
            _keyGestureConfigZhBase = parsed?.first ?: emptyMap()
            _keyGestureConfigEnBase = parsed?.second ?: emptyMap()
            // 键盘颜色（从原始 YAML 手动解析）
            keyboardColorsConfig = parseKeyboardColorsFromAssets(context)
            // 键盘阴影（从原始 YAML 手动解析）
            keyboardShadowConfig = parseKeyboardShadowFromAssets(context)
            // 键盘按键（从原始 YAML 手动解析）
            keyboardKeyConfig = parseKeyboardKeyFromAssets(context)
            // 九键键盘配置（从原始 YAML 手动解析）
            keyboardT9Config = parseKeyboardT9FromAssets(context)
            // 笔画键盘配置（从原始 YAML 手动解析）
            keyboardStrokeConfig = parseKeyboardStrokeFromAssets(context)
            // 字体配置（从原始 YAML 手动解析）
            keyboardFontConfig = parseKeyboardFontsFromAssets(context)
            com.kingzcheung.xime.ui.keyboard.AppFonts.loadCustomFonts(keyboardFontConfig)
            // 按键布局（从原始 YAML 手动解析，中英文分开）
            val parsedLayouts = parseButtonLayoutFromAssets(context)
            _buttonLayoutZh = parsedLayouts.first
            _buttonLayoutEn = parsedLayouts.second
            // 键盘行布局
            val parsedRows = parseKeyboardLayoutFromAssets(context)
            _zhRowsBase = parsedRows.first.first
            _enRowsBase = parsedRows.first.second
            _zhRowsLayout = parsedRows.second.first
            _enRowsLayout = parsedRows.second.second
            applyEnglishLetterLayout()
            // 合并键绑定（keyboard.<section>.schemas，custom 覆盖 builtIn 同名方案的绑定）
            val builtInBindings = readAssetText(context, XIME_CONFIG_FILE)
                ?.let { parseSchemaBindingsYamlText(it) } ?: emptyMap()
            val customBindings = readCustomText(context)
                ?.let { parseSchemaBindingsYamlText(it) } ?: emptyMap()
            _schemaSectionBindings = builtInBindings + customBindings
            hasLoadedLayoutBindings = true
            // 合并键布局 sections：由 schemas 绑定动态发现（内置 qwerty_14/17/18 + custom 新增）；
            // 代码布局 section（t9/stroke）无行数据，走各自的专属配置解析，不在此加载
            val mergedRowsMap = mutableMapOf<String, List<List<String>>>()
            val mergedGesturesMap = mutableMapOf<String, Map<String, KeyGestureConfig>>()
            for (section in _schemaSectionBindings.values.filter { it !in CODE_LAYOUT_SECTIONS }.distinct()) {
                parseLayoutSection(context, section)?.let { mergedRowsMap[section] = it }
                mergedGesturesMap[section] = parseGesturesSection(context, section)
            }
            _mergedRows = mergedRowsMap
            _mergedGestureConfigs = mergedGesturesMap
            // 九键/笔画手势（keyboard.t9.keys / keyboard.stroke.keys，custom 键级覆盖）
            _t9GestureConfigs = parseGesturesSection(context, "t9")
            _strokeGestureConfigs = parseGesturesSection(context, "stroke")
            // 基线缓存已刷新，先落标准 26 键的行布局/手势，合并键方案再由
            // setActiveKeyboardSchema 覆盖。不能只依赖 setActiveKeyboardSchema：
            // 清除已应用方案标记，保证同一方案重载时也采用最新配置。
            _zhRows = _zhRowsBase
            _keyGestureConfig.value = _keyGestureConfigZhBase
            // 重新应用当前方案对应的合并键布局（上面重置了基线缓存）
            _activeMergedSection = null
            _appliedLayoutSchemaId = null
            setActiveKeyboardSchema(_activeSchemaId)
            // 校验配置版本兼容性
            val merged = try { loadMergedConfig(context) } catch (_: YamlException) { null }
            val meta = merged?.metadata
            if (meta != null && meta.appVersion.isNotBlank()) {
                if (!checkVersionConstraint(BuildConfig.VERSION_NAME, meta.appVersion)) {
                    Log.w(TAG, "Config requires app_version ${meta.appVersion}, current is ${BuildConfig.VERSION_NAME}")
                }
            }
            _configVersion.value++
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load xime config", e)
        }
    }

    private fun checkVersionConstraint(current: String, constraint: String): Boolean {
        val operator = when {
            constraint.startsWith(">=") -> constraint.substring(0, 2)
            constraint.startsWith("<=") -> constraint.substring(0, 2)
            constraint.startsWith(">") -> constraint.substring(0, 1)
            constraint.startsWith("<") -> constraint.substring(0, 1)
            constraint.startsWith("^") -> constraint.substring(0, 1)
            constraint.startsWith("~") -> constraint.substring(0, 1)
            else -> return true
        }
        val targetVerStr = constraint.removePrefix(operator).trim('"', '\'', ' ')
        val targetParts = targetVerStr.split('.').mapNotNull { it.toIntOrNull() }
        val currentParts = current.split('.').mapNotNull { it.toIntOrNull() }
        if (targetParts.size < 2 || currentParts.size < 2) return true
        val t = Triple(targetParts.getOrElse(0) { 0 }, targetParts.getOrElse(1) { 0 }, targetParts.getOrElse(2) { 0 })
        val c = Triple(currentParts[0], currentParts.getOrElse(1) { 0 }, currentParts.getOrElse(2) { 0 })
        val cmp = compareVersions(c, t)
        return when (operator) {
            ">=" -> cmp >= 0
            "<=" -> cmp <= 0
            ">"  -> cmp > 0
            "<"  -> cmp < 0
            "^"  -> c.first == t.first && (c.first != 0 || cmp >= 0)
            "~"  -> c.first == t.first && c.second >= t.second
            else -> true
        }
    }

    private fun compareVersions(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        return when {
            a.first != b.first -> a.first.compareTo(b.first)
            a.second != b.second -> a.second.compareTo(b.second)
            else -> a.third.compareTo(b.third)
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘手势配置。 */
    private fun parseKeyboardFromAssets(context: Context): Pair<Map<String, KeyGestureConfig>, Map<String, KeyGestureConfig>>? {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return null
        val defaultZh = parseKeyboardYamlSection(defaultText, "qwerty") ?: return null
        val defaultEn = parseKeyboardYamlSection(defaultText, "qwerty_en") ?: emptyMap()
        // 支持两种来源：files/rime/（浏览器导入）或 assets/（内置），自动 fallback
        val userData = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
        val customZh: Map<String, KeyGestureConfig>?
        val customEn: Map<String, KeyGestureConfig>?
        if (userData != null) {
            customZh = parseKeyboardYamlSection(userData, "qwerty")
            customEn = parseKeyboardYamlSection(userData, "qwerty_en")
        } else {
            val assetText = readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
            customZh = assetText?.let { parseKeyboardYamlSection(it, "qwerty") }
            customEn = assetText?.let { parseKeyboardYamlSection(it, "qwerty_en") }
        }
        return Pair(mergeGestureConfigs(defaultZh, customZh), mergeGestureConfigs(defaultEn, customEn))
    }

    /** 与 Xime 一样按键覆盖，不将自定义键的单个槽位与内置键深合并。 */
    internal fun mergeGestureConfigs(
        builtIn: Map<String, KeyGestureConfig>, custom: Map<String, KeyGestureConfig>?,
    ): Map<String, KeyGestureConfig> = if (custom == null) builtIn else builtIn + custom

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘颜色配置（字段级一路 fallback）。 */
    private fun parseKeyboardColorsFromAssets(context: Context): KeyboardColorsConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardColorsYamlText(it) }
        val custom = readCustomText(context)?.let { parseKeyboardColorsYamlText(it) }
        return mergeColorsConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并颜色配置：custom → builtIn → 代码默认值。 */
    internal fun mergeColorsConfigs(custom: KeyboardColorsPartial?, builtIn: KeyboardColorsPartial?): KeyboardColorsConfig =
        KeyboardColorsConfig(
            keyBgColor = tiered(custom?.keyBgColor, builtIn?.keyBgColor, 0xFFFFFF),
            keyBgColorDark = tiered(custom?.keyBgColorDark, builtIn?.keyBgColorDark, 0x4A4A4A),
            specialKeyBgColor = tiered(custom?.specialKeyBgColor, builtIn?.specialKeyBgColor, null),
            specialKeyBgColorDark = tiered(custom?.specialKeyBgColorDark, builtIn?.specialKeyBgColorDark, null),
            keyTextColor = tiered(custom?.keyTextColor, builtIn?.keyTextColor, 0x202124),
            keyTextColorDark = tiered(custom?.keyTextColorDark, builtIn?.keyTextColorDark, 0xE8EAED),
            candidateTextColor = tiered(custom?.candidateTextColor, builtIn?.candidateTextColor, 0x1A73E8),
            candidateTextColorDark = tiered(custom?.candidateTextColorDark, builtIn?.candidateTextColorDark, 0x8AB4F8),
        )

    /** 从 YAML 文本中提取 keyboard.colors 段（仅显式字段非 null）。 */
    internal fun parseKeyboardColorsYamlText(yamlText: String): KeyboardColorsPartial? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val colorsNode = keyboardNode.opt<YamlMap>("colors") ?: return null
            var kBg: Long? = null
            var kBgDark: Long? = null
            var spKeyBg: Long? = null
            var spKeyBgDark: Long? = null
            var kTxt: Long? = null
            var kTxtDark: Long? = null
            var candTxt: Long? = null
            var candTxtDark: Long? = null
            for ((kNode, vNode) in colorsNode.entries) {
                val key = (kNode as? YamlScalar)?.content ?: continue
                val value = (vNode as? YamlScalar)?.content ?: continue
                val hex = value.removePrefix("0x").toLongOrNull(16) ?: continue
                when (key) {
                    "key_bg_color" -> kBg = hex
                    "key_bg_color_dark" -> kBgDark = hex
                    "special_key_bg_color" -> spKeyBg = hex
                    "special_key_bg_color_dark" -> spKeyBgDark = hex
                    "key_text_color" -> kTxt = hex
                    "key_text_color_dark" -> kTxtDark = hex
                    "candidate_text_color" -> candTxt = hex
                    "candidate_text_color_dark" -> candTxtDark = hex
                }
            }
            KeyboardColorsPartial(
                keyBgColor = kBg,
                keyBgColorDark = kBgDark,
                specialKeyBgColor = spKeyBg,
                specialKeyBgColorDark = spKeyBgDark,
                keyTextColor = kTxt,
                keyTextColorDark = kTxtDark,
                candidateTextColor = candTxt,
                candidateTextColorDark = candTxtDark,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard colors config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘阴影配置（字段级一路 fallback）。 */
    private fun parseKeyboardShadowFromAssets(context: Context): KeyboardShadowConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardShadowYamlText(it) }
        val custom = readCustomText(context)?.let { parseKeyboardShadowYamlText(it) }
        return mergeShadowConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并阴影配置：custom → builtIn → 代码默认值。 */
    internal fun mergeShadowConfigs(custom: KeyboardShadowPartial?, builtIn: KeyboardShadowPartial?): KeyboardShadowConfig =
        KeyboardShadowConfig(
            enabled = tiered(custom?.enabled, builtIn?.enabled, true),
            elevation = tiered(custom?.elevation, builtIn?.elevation, 1),
            shapeRadius = tiered(custom?.shapeRadius, builtIn?.shapeRadius, 8),
        )

    /** 从 YAML 文本中提取 keyboard.shadow 段（仅显式字段非 null）。 */
    internal fun parseKeyboardShadowYamlText(yamlText: String): KeyboardShadowPartial? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val shadowNode = keyboardNode.opt<YamlMap>("shadow") ?: return null
            var enabled: Boolean? = null
            var elevation: Int? = null
            var shapeRadius: Int? = null
            for ((kNode, vNode) in shadowNode.entries) {
                val key = (kNode as? YamlScalar)?.content ?: continue
                val value = (vNode as? YamlScalar)?.content ?: continue
                when (key) {
                    "enabled" -> enabled = value.toBooleanStrictOrNull() ?: continue
                    "elevation" -> elevation = value.toIntOrNull() ?: continue
                    "shape_radius" -> shapeRadius = value.toIntOrNull() ?: continue
                }
            }
            KeyboardShadowPartial(enabled = enabled, elevation = elevation, shapeRadius = shapeRadius)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard shadow config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘按键配置（字段级一路 fallback）。 */
    private fun parseKeyboardKeyFromAssets(context: Context): KeyboardKeyConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardKeyYamlPartial(it) }
        val custom = readCustomText(context)?.let { parseKeyboardKeyYamlPartial(it) }
        return mergeKeyConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并按键配置：custom → builtIn → 代码默认值。 */
    internal fun mergeKeyConfigs(custom: KeyboardKeyPartial?, builtIn: KeyboardKeyPartial?): KeyboardKeyConfig =
        KeyboardKeyConfig(
            cornerRadius = tiered(custom?.cornerRadius, builtIn?.cornerRadius, 8),
            spacingX = tiered(custom?.spacingX, builtIn?.spacingX, null),
            spacingY = tiered(custom?.spacingY, builtIn?.spacingY, null),
            spacingOverrides = mergeSpacingOverrides(custom?.spacingOverrides, builtIn?.spacingOverrides),
        )

    /** 按键盘名合并间距覆盖：同名项字段级 fallback（custom 非空字段覆盖 builtIn）。 */
    internal fun mergeSpacingOverrides(
        custom: Map<String, KeyboardSpacingConfig>?,
        builtIn: Map<String, KeyboardSpacingConfig>?,
    ): Map<String, KeyboardSpacingConfig> {
        if (custom == null) return builtIn ?: emptyMap()
        if (builtIn == null) return custom
        val result = LinkedHashMap<String, KeyboardSpacingConfig>(custom)
        for ((key, builtInValue) in builtIn) {
            val customValue = result[key]
            result[key] = if (customValue == null) {
                builtInValue
            } else {
                KeyboardSpacingConfig(
                    spacingX = customValue.spacingX ?: builtInValue.spacingX,
                    spacingY = customValue.spacingY ?: builtInValue.spacingY,
                )
            }
        }
        return result
    }

    /**
     * 兼容测试入口：从 YAML 文本中提取 keyboard.key 段并填充代码默认值。
     * > 合并（custom → builtIn → 默认）请使用 [parseKeyboardKeyYamlPartial]。
     */
    internal fun parseKeyboardKeyYamlText(yamlText: String): KeyboardKeyConfig? {
        val partial = parseKeyboardKeyYamlPartial(yamlText) ?: return null
        return KeyboardKeyConfig(
            cornerRadius = partial.cornerRadius ?: 8,
            spacingX = partial.spacingX,
            spacingY = partial.spacingY,
            spacingOverrides = partial.spacingOverrides ?: emptyMap(),
        )
    }

    /** 从 YAML 文本中提取 keyboard.key 段（仅显式字段非 null）。 */
    private fun parseKeyboardKeyYamlPartial(yamlText: String): KeyboardKeyPartial? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            var cornerRadius: Int? = null
            var spacingX: Float? = null
            var spacingY: Float? = null
            val spacingOverrides = mutableMapOf<String, KeyboardSpacingConfig>()
            // 优先读取 key.corner_radius
            val keyNode = keyboardNode.opt<YamlMap>("key")
            if (keyNode != null) {
                for ((kNode, vNode) in keyNode.entries) {
                    val key = (kNode as? YamlScalar)?.content ?: continue
                    if (key == "corner_radius") {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        cornerRadius = value.toIntOrNull()
                    } else if (key == "spacing_x") {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        spacingX = value.toFloatOrNull()
                    } else if (key == "spacing_y") {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        spacingY = value.toFloatOrNull()
                    } else if (vNode is YamlMap) {
                        // 键盘级间距覆盖：keyboard.key.<键盘名>.spacing_x/spacing_y
                        var overrideX: Float? = null
                        var overrideY: Float? = null
                        for ((skNode, svNode) in vNode.entries) {
                            val skey = (skNode as? YamlScalar)?.content ?: continue
                            val svalue = (svNode as? YamlScalar)?.content ?: continue
                            if (skey == "spacing_x") {
                                overrideX = svalue.toFloatOrNull()
                            } else if (skey == "spacing_y") {
                                overrideY = svalue.toFloatOrNull()
                            }
                        }
                        spacingOverrides[key] = KeyboardSpacingConfig(overrideX, overrideY)
                    }
                }
            }
            // 未设置 key.corner_radius 时，回退读取 shadow.shape_radius
            if (cornerRadius == null) {
                val shadowNode = keyboardNode.opt<YamlMap>("shadow")
                if (shadowNode != null) {
                    for ((kNode, vNode) in shadowNode.entries) {
                        val key = (kNode as? YamlScalar)?.content ?: continue
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        if (key == "shape_radius") {
                            cornerRadius = value.toIntOrNull()
                        }
                    }
                }
            }
            KeyboardKeyPartial(
                cornerRadius = cornerRadius,
                spacingX = spacingX,
                spacingY = spacingY,
                spacingOverrides = spacingOverrides.ifEmpty { null },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard key config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析九键键盘配置。 */
    private fun parseKeyboardT9FromAssets(context: Context): KeyboardT9Config {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardT9YamlPartial(it) }
        val custom = readCustomText(context)?.let { parseKeyboardT9YamlPartial(it) }
        return mergeT9Configs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并九键配置：custom → builtIn → 代码默认值。 */
    internal fun mergeT9Configs(custom: KeyboardT9Partial?, builtIn: KeyboardT9Partial?): KeyboardT9Config =
        KeyboardT9Config(
            sideSymbols = tiered(custom?.sideSymbols?.takeIf { it.isNotEmpty() },
                builtIn?.sideSymbols?.takeIf { it.isNotEmpty() }, DEFAULT_T9_SIDE_SYMBOLS),
        )

    /** 从 YAML 文本中提取 keyboard.t9 段（仅显式字段非 null）。 */
    internal fun parseKeyboardT9YamlPartial(yamlText: String): KeyboardT9Partial? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val t9Node = keyboardNode.opt<YamlMap>("t9") ?: return null
            val sideSymbols = t9Node.opt<YamlList>("side_symbols")
                ?.items?.mapNotNull { (it as? YamlScalar)?.content }
            KeyboardT9Partial(sideSymbols = sideSymbols?.ifEmpty { null })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard t9 config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析笔画键盘配置。 */
    private fun parseKeyboardStrokeFromAssets(context: Context): KeyboardStrokeConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardStrokeYamlPartial(it) }
        val custom = readCustomText(context)?.let { parseKeyboardStrokeYamlPartial(it) }
        return mergeStrokeConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并笔画配置：custom → builtIn → 代码默认值。 */
    internal fun mergeStrokeConfigs(custom: KeyboardStrokePartial?, builtIn: KeyboardStrokePartial?): KeyboardStrokeConfig =
        KeyboardStrokeConfig(
            sideSymbols = tiered(custom?.sideSymbols?.takeIf { it.isNotEmpty() },
                builtIn?.sideSymbols?.takeIf { it.isNotEmpty() }, DEFAULT_STROKE_SIDE_SYMBOLS),
        )

    /** 从 YAML 文本中提取 keyboard.stroke 段（仅显式字段非 null）。 */
    internal fun parseKeyboardStrokeYamlPartial(yamlText: String): KeyboardStrokePartial? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val strokeNode = keyboardNode.opt<YamlMap>("stroke") ?: return null
            val sideSymbols = strokeNode.opt<YamlList>("side_symbols")
                ?.items?.mapNotNull { (it as? YamlScalar)?.content }
            KeyboardStrokePartial(sideSymbols = sideSymbols?.ifEmpty { null })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard stroke config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析字体配置（字段级一路 fallback）。 */
    private fun parseKeyboardFontsFromAssets(context: Context): KeyboardFontConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardFontsYamlText(it) }
        val custom = readCustomText(context)?.let { parseKeyboardFontsYamlText(it) }
        return mergeFontConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并字体配置：custom → builtIn → 代码默认值（空字符串视为未配置）。 */
    internal fun mergeFontConfigs(custom: KeyboardFontPartial?, builtIn: KeyboardFontPartial?): KeyboardFontConfig =
        KeyboardFontConfig(
            keyFont = tieredText(custom?.keyFont, builtIn?.keyFont, ""),
            keyLabelFont = tieredText(custom?.keyLabelFont, builtIn?.keyLabelFont, ""),
            candidateFont = tieredText(custom?.candidateFont, builtIn?.candidateFont, ""),
            commentFont = tieredText(custom?.commentFont, builtIn?.commentFont, ""),
        )

    /** 从 YAML 文本中提取 keyboard.fonts 字体配置段（仅显式字段非 null）。 */
    internal fun parseKeyboardFontsYamlText(yamlText: String): KeyboardFontPartial? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val fontsNode = keyboardNode.opt<YamlMap>("fonts") ?: return null
            var keyFont: String? = null
            var keyLabelFont: String? = null
            var candidateFont: String? = null
            var commentFont: String? = null
            for ((kNode, vNode) in fontsNode.entries) {
                val key = (kNode as? YamlScalar)?.content ?: continue
                val value = (vNode as? YamlScalar)?.content ?: continue
                when (key) {
                    "key_font" -> keyFont = value
                    "key_label_font" -> keyLabelFont = value
                    "candidate_font" -> candidateFont = value
                    "comment_font" -> commentFont = value
                }
            }
            KeyboardFontPartial(
                keyFont = keyFont,
                keyLabelFont = keyLabelFont,
                candidateFont = candidateFont,
                commentFont = commentFont,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard fonts config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析按键布局模式（中英文分开）。 */
    private fun parseButtonLayoutFromAssets(context: Context): Pair<ButtonLayout, ButtonLayout> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return Pair(ButtonLayout.STANDARD, ButtonLayout.STANDARD)
        val defaultZh = parseButtonLayoutYamlText(defaultText, "qwerty")
        val defaultEn = parseButtonLayoutYamlText(defaultText, "qwerty_en")
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        val customZh = customText?.let { parseButtonLayoutYamlText(it, "qwerty") }
        val customEn = customText?.let { parseButtonLayoutYamlText(it, "qwerty_en") }
        val result = Pair(
            customZh ?: defaultZh ?: ButtonLayout.STANDARD,
            customEn ?: defaultEn ?: ButtonLayout.STANDARD,
        )
        return result
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘行布局。 */
    private fun parseKeyboardLayoutFromAssets(context: Context): Pair<Pair<List<List<String>>, List<List<String>>>, Pair<KeyboardRowsLayout, KeyboardRowsLayout>> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return Pair(
            Pair(DEFAULT_ZH_ROWS, DEFAULT_EN_ROWS),
            Pair(KeyboardRowsLayout(DEFAULT_ZH_ROWS.map { KeyboardRow(it) }),
                KeyboardRowsLayout(DEFAULT_EN_ROWS.map { KeyboardRow(it) })),
        )

        val defaultZh = parseKeyboardLayoutYamlText(defaultText, "qwerty")
        val defaultEn = parseKeyboardLayoutYamlText(defaultText, "qwerty_en")

        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)

        val customZh = customText?.let { parseKeyboardLayoutYamlText(it, "qwerty") }
        val customEn = customText?.let { parseKeyboardLayoutYamlText(it, "qwerty_en") }

        return Pair(
            Pair(customZh ?: defaultZh ?: DEFAULT_ZH_ROWS, customEn ?: defaultEn ?: DEFAULT_EN_ROWS),
            Pair(mergeRowsLayout(defaultText, customText, "qwerty", DEFAULT_ZH_ROWS),
                mergeRowsLayout(defaultText, customText, "qwerty_en", DEFAULT_EN_ROWS)),
        )
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析指定 section 的键盘行布局（custom 整段覆盖 built-in）。 */
    private fun parseLayoutSection(context: Context, section: String): List<List<String>>? {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE)
        val default = defaultText?.let { parseKeyboardLayoutYamlText(it, section) }
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        val custom = customText?.let { parseKeyboardLayoutYamlText(it, section) }
        return custom ?: default
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析指定 section 的手势配置（custom 键级覆盖 built-in）。 */
    private fun parseGesturesSection(context: Context, section: String): Map<String, KeyGestureConfig> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE)
        val default = defaultText?.let { parseKeyboardYamlSection(it, section) } ?: emptyMap()
        val userData = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
        val custom = (userData ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE))
            ?.let { parseKeyboardYamlSection(it, section) }
        return if (custom != null) default + custom else default
    }

    /**
     * 从 YAML 文本提取合并键绑定：keyboard.<section>.schemas 列出的每个方案 id 映射到该 section。
     * 无 schemas 声明的 section 忽略；同一方案 id 在多个 section 声明时以后出现的为准。
     */
    internal fun parseSchemaBindingsYamlText(yamlText: String): Map<String, String> {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return emptyMap()
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return emptyMap()
            val bindings = mutableMapOf<String, String>()
            for ((kNode, vNode) in keyboardNode.entries) {
                val section = (kNode as? YamlScalar)?.content ?: continue
                val schemas = (vNode as? YamlMap)?.opt<YamlList>("schemas") ?: continue
                for (item in schemas.items) {
                    val schemaId = (item as? YamlScalar)?.content?.trim().orEmpty()
                    if (schemaId.isNotEmpty()) bindings[schemaId] = section
                }
            }
            bindings
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse schema bindings", e)
            emptyMap()
        }
    }

    /**
     * 从 YAML 文本中提取 keyboard.<section>.layout.rows。
     * 行元素支持嵌套子数组表示合并键：[[q, w], [e, r], t] → ["qw", "er", "t"]，
     * 合并键 ID 为组内字母拼接，渲染层按 ID 长度分配键宽。
     */
    internal fun parseKeyboardLayoutYamlText(yamlText: String, section: String): List<List<String>>? =
        parseKeyboardRowsYamlText(yamlText, section, includeObjects = false)?.map { it.keys }

    private fun layoutNodeOf(yamlText: String, section: String): YamlMap? =
        (keyboardYamlNode(yamlText) as? YamlMap)?.opt<YamlMap>("keyboard")
            ?.opt<YamlMap>(section)?.opt<YamlMap>("layout")

    /** 使用同一 YAML 节点解析器；旧行 API 继续忽略以前不支持的对象行。 */
    internal fun parseKeyboardRowsYamlText(
        yamlText: String, section: String, includeObjects: Boolean = true,
    ): List<KeyboardRow>? = try {
        val rows = layoutNodeOf(yamlText, section)?.opt<YamlList>("rows")
        rows?.items?.mapNotNull { node ->
            val row = when (node) {
                is YamlList -> KeyboardRow(rowListToKeyIds(node))
                is YamlScalar -> KeyboardRow(scalarRowToKeyIds(node.content))
                is YamlMap -> if (includeObjects) {
                    val keysNode = node.entries.entries.firstOrNull { it.key.content == "keys" }?.value
                    val keys = when (keysNode) {
                        is YamlList -> rowListToKeyIds(keysNode)
                        is YamlScalar -> scalarRowToKeyIds(keysNode.content)
                        else -> emptyList()
                    }
                    val align = node.opt<YamlScalar>("align")?.content?.let(RowAlign::fromValue)
                    val width = node.opt<YamlScalar>("width")?.content?.trim()?.toFloatOrNull()
                        ?.takeIf { it.isFinite() && it > 0f && it <= 1f }
                    KeyboardRow(keys, align, width)
                } else null
                else -> null
            }
            row?.takeIf { it.keys.isNotEmpty() }
        }?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse keyboard layout config", e)
        null
    }

    internal fun mergeRowsLayout(
        defaultText: String, customText: String?, section: String,
        defaultRows: List<List<String>> = emptyList(),
    ): KeyboardRowsLayout {
        fun layout(text: String?): YamlMap? = try {
            text?.let { layoutNodeOf(it, section) }
        } catch (_: Exception) { null }
        val builtIn = layout(defaultText)
        val custom = layout(customText)
        fun columns(node: YamlMap?): Int? = node?.opt<YamlScalar>("columns")?.content
            ?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        fun align(node: YamlMap?): RowAlign? = node?.opt<YamlScalar>("row_align")?.content
            ?.let(RowAlign::fromValue)
        return KeyboardRowsLayout(
            rows = customText?.let { parseKeyboardRowsYamlText(it, section) }
                ?: parseKeyboardRowsYamlText(defaultText, section) ?: defaultRows.map { KeyboardRow(it) },
            columns = columns(custom) ?: columns(builtIn),
            rowAlign = align(custom) ?: align(builtIn) ?: RowAlign.UNIFORM,
        )
    }

    /** 行节点 → 按键 ID 列表：子数组为合并键（组内字母拼接为 ID），标量为单字母键。 */
    private fun rowListToKeyIds(rowList: YamlList): List<String> =
        rowList.items.mapNotNull { item ->
            when (item) {
                is YamlScalar -> item.content.takeIf { it.isNotBlank() }
                is YamlList -> item.items
                    .mapNotNull { (it as? YamlScalar)?.content }
                    .filter { it.isNotBlank() }
                    .joinToString("")
                    .takeIf { it.isNotEmpty() }
                else -> null
            }
        }

    /**
     * 标量行兜底：YAML 中以普通字母开头的行（如 "- z, [x, c], v"）不会成为 flow 序列，
     * 而是整行解析为单个标量。此处手动拆分：逗号为键分隔，方括号内为合并键组。
     */
    private fun scalarRowToKeyIds(content: String): List<String> {
        val text = content.trim()
        if (text.isEmpty()) return emptyList()
        if (!text.contains(',') && !text.contains('[')) return listOf(text)
        val items = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        fun flushItem() {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) items.add(t)
            sb.clear()
        }
        for (ch in text) {
            when {
                ch == '[' -> { depth++; if (depth == 1) sb.clear() else sb.append(ch) }
                ch == ']' -> {
                    depth--
                    if (depth == 0) {
                        flushItem()
                    } else if (depth < 0) {
                        depth = 0
                    } else {
                        sb.append(ch)
                    }
                }
                ch == ',' && depth == 0 -> flushItem()
                ch == ',' && depth > 0 -> {} // 组内逗号仅分隔字母，拼接时丢弃
                ch == ' ' && depth > 0 -> {} // 组内空格一并丢弃（[x, c] → xc）
                else -> sb.append(ch)
            }
        }
        flushItem()
        return items
    }

    /** 从 YAML 文本中提取 keyboard.<section>.button_layout。 */
    private fun parseButtonLayoutYamlText(yamlText: String, section: String): ButtonLayout? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val layoutNode = sectionNode.opt<YamlScalar>("button_layout") ?: return null
            ButtonLayout.fromValue(layoutNode.content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse button layout config", e)
            null
        }
    }

    /** 从 YAML 文本中提取 keyboard.<section>.keys 段。 */
    internal fun parseKeyboardYamlSection(yamlText: String, section: String): Map<String, KeyGestureConfig>? {
        return try {
            val root = keyboardYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val keysNode = sectionNode.opt<YamlMap>("keys") ?: return null
            val result = mutableMapOf<String, KeyGestureConfig>()
            for ((kNode, vNode) in keysNode.entries) {
                val key = (kNode as? YamlScalar)?.content ?: continue
                val gestureMap = vNode as? YamlMap ?: continue
                result[key] = parseKeyGestureConfig(gestureMap)
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard gesture section", e)
            null
        }
    }

    private fun loadMergedConfig(context: Context): XimeConfig {
        val currentVersion = _configVersion.value
        if (mergedConfigCache != null && mergedConfigVersion == currentVersion) {
            return mergedConfigCache!!
        }
        val default = parseConfig(readAssetText(context, XIME_CONFIG_FILE))
        val custom = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?.let { parseConfig(it) }
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
                ?.let { parseConfig(it) }
        val config = mergeConfig(default, custom)
        mergedConfigCache = config
        mergedConfigVersion = currentVersion
        return config
    }

    private fun parseConfig(content: String?): XimeConfig? {
        if (content == null) return null
        return try {
            yaml.decodeFromString(XimeConfig.serializer(), content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse xime config", e)
            null
        }
    }

    private fun mergeConfig(default: XimeConfig?, custom: XimeConfig?): XimeConfig {
        if (custom == null) return default ?: XimeConfig()
        if (default == null) return custom
        return XimeConfig(
            ximeIndex = custom.ximeIndex ?: default.ximeIndex,
            // 合并而非替换：custom 覆盖同名字题，其余保留内置主题，
            // 这样用户只需在 xime.custom.yaml 中添加自定义背景主题而不会丢失内置主题。
            colorSchemes = mergeColorSchemes(default.colorSchemes, custom.colorSchemes),
            style = mergeStyle(default.style, custom.style),
            metadata = custom.metadata ?: default.metadata,
        )
    }

    /** style 字段级深合并：custom 只覆盖显式配置的字段。 */
    private fun mergeStyle(default: StyleConfig?, custom: StyleConfig?): StyleConfig? {
        if (custom == null) return default
        if (default == null) return custom
        return StyleConfig(
            colorScheme = mergeColorSchemeMode(default.colorScheme, custom.colorScheme),
            darkMode = custom.darkMode ?: default.darkMode,
        )
    }

    /** color_scheme 的 light/dark 字段级合并：custom 只覆盖显式配置的模式。 */
    private fun mergeColorSchemeMode(
        default: ColorSchemeModeConfig?,
        custom: ColorSchemeModeConfig?,
    ): ColorSchemeModeConfig? {
        if (custom == null) return default
        if (default == null) return custom
        return ColorSchemeModeConfig(
            light = custom.light ?: default.light,
            dark = custom.dark ?: default.dark,
        )
    }

    /** 仅供单元测试验证 style 合并逻辑。 */
    internal fun mergeStyleForTest(default: StyleConfig?, custom: StyleConfig?): StyleConfig? =
        mergeStyle(default, custom)

    private fun mergeColorSchemes(
        default: Map<String, ColorSchemeEntry>?,
        custom: Map<String, ColorSchemeEntry>?,
    ): Map<String, ColorSchemeEntry>? {
        if (custom == null) return default
        if (default == null) return custom
        // 字段级深合并：custom 只覆盖显式配置的字段，同名主题其余字段保留内置值。
        // 例如 custom 只写 primary_color 时，内置主题的 keyboard_background 不会被清掉。
        val result = default.toMutableMap()
        for ((id, customEntry) in custom) {
            val base = default[id] ?: run {
                result[id] = customEntry
                continue
            }
            result[id] = base.copy(
                name = customEntry.name.ifEmpty { base.name },
                primaryColor = customEntry.primaryColor.takeIf { it != 0L } ?: base.primaryColor,
                keyboardBgColor = customEntry.keyboardBgColor ?: base.keyboardBgColor,
                keyBgColor = customEntry.keyBgColor ?: base.keyBgColor,
                keyBgColorDark = customEntry.keyBgColorDark ?: base.keyBgColorDark,
                specialKeyBgColor = customEntry.specialKeyBgColor ?: base.specialKeyBgColor,
                specialKeyBgColorDark = customEntry.specialKeyBgColorDark ?: base.specialKeyBgColorDark,
                enterKeyBgColor = customEntry.enterKeyBgColor ?: base.enterKeyBgColor,
                enterKeyBgColorDark = customEntry.enterKeyBgColorDark ?: base.enterKeyBgColorDark,
                specialKeyTextColor = customEntry.specialKeyTextColor ?: base.specialKeyTextColor,
                specialKeyTextColorDark = customEntry.specialKeyTextColorDark ?: base.specialKeyTextColorDark,
                candidateBarBgColor = customEntry.candidateBarBgColor ?: base.candidateBarBgColor,
                keyTextColor = customEntry.keyTextColor ?: base.keyTextColor,
                keyTextColorDark = customEntry.keyTextColorDark ?: base.keyTextColorDark,
                candidateTextColor = customEntry.candidateTextColor ?: base.candidateTextColor,
                candidateTextColorDark = customEntry.candidateTextColorDark ?: base.candidateTextColorDark,
                candidateSelectedTextColor = customEntry.candidateSelectedTextColor ?: base.candidateSelectedTextColor,
                candidateSelectedTextColorDark = customEntry.candidateSelectedTextColorDark ?: base.candidateSelectedTextColorDark,
                keyboardBackground = customEntry.keyboardBackground ?: base.keyboardBackground,
                keyBackground = customEntry.keyBackground ?: base.keyBackground,
                candidateBarBackground = customEntry.candidateBarBackground ?: base.candidateBarBackground,
                dynamicColor = customEntry.dynamicColor || base.dynamicColor,
            )
        }
        return result
    }

    private fun readAssetText(context: Context, fileName: String): String? {
        return try {
            val inputStream = context.assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()
            inputStream.close()
            content
        } catch (e: Exception) {
            null
        }
    }

    /** 从用户数据目录 (context.filesDir/rime/) 读取文件。 */
    private fun readUserDataText(context: Context, fileName: String): String? {
        val file = File(context.filesDir, "rime/$fileName")
        if (!file.exists()) return null
        return try {
            val text = file.readText().trimStart('\uFEFF')
            text
        } catch (e: Exception) {
            Log.w(TAG, "readUserDataText: failed", e)
            null
        }
    }

    /** 读取自定义配置文本：优先用户数据目录（浏览器导入），回退 assets 内置。 */
    private fun readCustomText(context: Context): String? =
        readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)

    fun loadXimeIndexConfig(context: Context): XimeIndexConfig {
        val merged = loadMergedConfig(context)
        return merged.ximeIndex ?: XimeIndexConfig()
    }

    /** 从 xime.yaml 加载 color_schemes 配置。 */
    fun loadColorSchemes(context: Context): Map<String, ColorSchemeEntry> {
        val merged = loadMergedConfig(context)
        return merged.colorSchemes ?: emptyMap()
    }

    /** 从 xime.yaml 加载默认主题 ID（style.color_scheme 的 light 字段）。 */
    fun loadDefaultThemeId(context: Context): String {
        val merged = loadMergedConfig(context)
        return merged.style?.colorScheme?.light ?: com.kingzcheung.xime.ui.theme.PureBlackTheme.ID
    }

    /** 根据显示模式加载对应的默认主题 ID。 */
    fun loadThemeIdForMode(context: Context, isDark: Boolean): String {
        val merged = loadMergedConfig(context)
        val cs = merged.style?.colorScheme ?: return com.kingzcheung.xime.ui.theme.PureBlackTheme.ID
        return if (isDark) (cs.dark ?: cs.light ?: com.kingzcheung.xime.ui.theme.PureBlackTheme.ID)
               else cs.light ?: com.kingzcheung.xime.ui.theme.PureBlackTheme.ID
    }

    /** 从 xime.yaml 加载默认显示模式（style.dark_mode）。 */
    fun loadDefaultDarkMode(context: Context): Int {
        val merged = loadMergedConfig(context)
        return merged.style?.darkMode ?: 1
    }

    // ── 新公开 API ──

    /** 获取键盘颜色配置（从 xime.yaml keyboard.colors 加载）。 */
    fun getKeyboardColors(): KeyboardColorsConfig = keyboardColorsConfig

    /** 获取键盘阴影配置（从 xime.yaml keyboard.shadow 加载）。 */
    fun getKeyboardShadow(): KeyboardShadowConfig = keyboardShadowConfig

    /** 获取键盘按键配置（从 xime.yaml keyboard.key 加载）；圆角按主题里选的按键形状收小。 */
    fun getKeyboardKeyConfig(): KeyboardKeyConfig = keyboardKeyConfig.let { config ->
        val radius = com.kingzcheung.xime.ui.theme.KeyShapes.current.radius(config.cornerRadius)
        if (radius == config.cornerRadius) config else config.copy(cornerRadius = radius)
    }

    /** xime.yaml 配置的按键圆角，未按按键形状收小；设置页预览各形状用。 */
    fun configuredKeyCornerRadius(): Int = keyboardKeyConfig.cornerRadius

    /** 获取九键左侧快捷符号栏配置（xime.custom.yaml → xime.yaml → 内置默认值）。 */
    fun getT9SideSymbols(): List<String> =
        keyboardT9Config.sideSymbols ?: DEFAULT_T9_SIDE_SYMBOLS

    /** 获取九键数字键手势配置（xime.yaml keyboard.t9.keys，custom 键级覆盖）。
     *  键 id 为数字字符串 "1"~"9"；无配置返回 null（布局不启用滑动）。 */
    fun getT9KeyGesture(key: String): KeyGestureConfig? = _t9GestureConfigs[key]

    /** 获取笔画左侧快捷符号列（keyboard.stroke.side_symbols，可自定义）。 */
    fun getStrokeSideSymbols(): List<String> =
        keyboardStrokeConfig.sideSymbols ?: DEFAULT_STROKE_SIDE_SYMBOLS

    /** 获取笔画键手势配置（xime.yaml keyboard.stroke.keys，custom 键级覆盖）。
     *  键 id 为键面标签：一/丨/丿/丶/乛、*、分词、，、英。 */
    fun getStrokeKeyGesture(key: String): KeyGestureConfig? = _strokeGestureConfigs[key]

    internal fun qwertySymbolEditorValues(context: Context, english: Boolean): Map<String, String> {
        val overrides = QwertySwipeSymbols.load(context, english)
        val custom = if (english) symbolCustomEn else symbolCustomZh
        return buildMap {
            for (key in QwertySwipeSymbols.keys.map(Char::toString)) {
                val gesture = QwertySwipeSymbols.apply(key, null, overrides, custom[key], english)!!
                put("up.$key", gesture.swipeUp!!.value.ifEmpty { gesture.swipeUp.label })
                put("down.$key", gesture.swipeDown!!.value.ifEmpty { gesture.swipeDown.label })
            }
        }
    }

    internal fun customLayoutSymbolEditorValues(context: Context, layout: CustomKeyboardLayout): Map<String, String> {
        val overrides = CustomLayoutSymbols.load(context, layout)
        val keys = CustomLayoutSymbols.keys(layout)
        return buildMap {
            for (key in keys) {
                // 和键盘上一样：自建布局的手势表按旧位置填过 swipe，不算用户写的，只看预设和这里的修改。
                val gesture = MergedKeySymbols.apply(keys, key, null, overrides)!!
                put("up.$key", gesture.swipeUp!!.value)
                put("down.$key", gesture.swipeDown!!.value)
            }
        }
    }

    internal fun fourteenSymbolEditorValues(context: Context): Map<String, String> {
        val overrides = FourteenKeySymbols.load(context)
        val custom = _mergedGestureConfigs[InputLayout.MERGED14.id].orEmpty()
        return buildMap {
            for (key in FourteenKeySymbols.keys) {
                val gesture = FourteenKeySymbols.apply(key, custom[key], overrides)!!
                put("up.$key", gesture.swipeUp!!.value.ifEmpty { gesture.swipeUp.label })
                put("down.$key", gesture.swipeDown!!.value.ifEmpty { gesture.swipeDown.label })
            }
        }
    }

    /**
     * 西班牙语键盘：长按元音和 n 总是弹出 á é í ó ú ü ñ（打西语离不开重音），不受「长按弹出字母气泡」开关影响；
     * 在它上面切到英文时照英文键盘的规则。
     */
    fun accentLongPress(isAsciiMode: Boolean): Boolean =
        !isAsciiMode && InputLanguage.forSchema(_activeSchemaId) == InputLanguage.SPANISH

    /** 获取某个按键的手势配置。 */
    fun getKeyGesture(key: String): KeyGestureConfig? = getKeyGesture(key, false)

    /** 根据输入模式获取某个按键的手势配置。 */
    fun getKeyGesture(key: String, isAsciiMode: Boolean): KeyGestureConfig? {
        // 每次组合都会对每个键反复取手势（角标/上滑/长按各一次），
        // 而计算里含 InputProfiles.current + 布局查找。按配置版本与当前方案缓存，避免每帧重复计算。
        val cfg = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val stamp = _configVersion.value * 31L + _activeSchemaId.hashCode() +
            (if (isAsciiMode) 1 else 0) + System.identityHashCode(cfg).toLong() * 1_000_003L
        if (stamp != gestureCacheStamp) {
            gestureCache.clear()
            gestureCacheStamp = stamp
        }
        val normalized = key.lowercase()
        if (gestureCache.containsKey(normalized)) return gestureCache[normalized]
        return computeKeyGesture(normalized, isAsciiMode).also { gestureCache[normalized] = it }
    }

    private var gestureCacheStamp = Long.MIN_VALUE
    private val gestureCache = HashMap<String, KeyGestureConfig?>()

    private fun computeKeyGesture(normalized: String, isAsciiMode: Boolean): KeyGestureConfig? {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val profile = InputProfiles.current(_activeSchemaId, isAsciiMode)
        if (profile.language !in setOf(InputLanguage.CHINESE, InputLanguage.ENGLISH, InputLanguage.SPANISH)) return config[normalized]
        // 西班牙语键盘用英文键盘的角标（同一位置同一个半角符号）；多出来的 ñ 键不在 26 格里，不带角标。
        val english = isAsciiMode || profile.language == InputLanguage.SPANISH
        val custom = letterLayout(_activeSchemaId, isAsciiMode)
        // 所有字母布局的角标都由下面两条规则出，不再各布局各配一套（用户 10-30：模式太多难免疏忽，统一）。
        return when (profile.layout.kind) {
            // 单字母键：角标跟着位置走。换了字母排列或用自建布局，同一格还是 26 键那一格的上下角标，符号设置页改的也是这一格。
            LayoutKind.ALPHABETIC -> {
                // 排列里的分号键本身就是符号，不带角标；它占的那一格给了被挤开的字母（见 positionLetter）。
                if (custom != null && normalized == ";" && custom.hasSemicolon())
                    return config[normalized]?.copy(swipeUp = null, swipeDown = null)
                val owner = custom?.positionLetter(normalized) ?: normalized
                QwertySwipeSymbols.apply(owner, config[normalized], if (english) symbolOverridesEn else symbolOverridesZh,
                    (if (english) symbolCustomEn else symbolCustomZh)[owner], english)
            }
            // 合并键（14/17/18 键、自建双字母布局）：按键的读顺序用同一张表（MergedKeySymbols），14 键可在设置页改。
            // Shift 或分体把双字母键拆成单字母时，两个字母都沿用原来那个键的角标，符号位置不跟着变。
            LayoutKind.MERGED -> {
                if (profile.language != InputLanguage.CHINESE) return config[normalized]
                val fourteen = profile.layout == InputLayout.MERGED14
                val keys = if (fourteen) FourteenKeySymbols.keys
                    else _zhRows.flatten().filter { cell -> cell.isNotEmpty() && cell.all { it in 'a'..'z' } }
                val owner = MergedKeySymbols.ownerOf(keys, normalized)
                if (owner !in keys) return config[normalized]
                // 自建布局的手势表按旧的位置填过 swipe，不算用户写的；内置 14/17/18 键只认 xime.custom.yaml 写的。
                val configured = config[owner]?.let { if (custom != null) it.copy(swipeUp = null, swipeDown = null) else it }
                // 14 键和自建布局在符号设置页改的；17/18 键没有设置页。
                val overrides = if (fourteen) symbolOverridesK14 else custom?.let { symbolOverridesCustom[it.id] }.orEmpty()
                val symbols = MergedKeySymbols.apply(keys, owner, configured, overrides)
                if (owner == normalized) symbols
                else (config[normalized] ?: KeyGestureConfig()).copy(swipeUp = symbols?.swipeUp, swipeDown = symbols?.swipeDown)
            }
            else -> config[normalized]
        }
    }

    fun getKeyDisplayLabel(key: String, isAsciiMode: Boolean = false, isShifted: Boolean = false): String {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val label = config[key.lowercase()]?.tap?.label
        return keyLabelWithCase(label?.takeIf { it.isNotEmpty() } ?: key, isShifted)
    }

    fun getKeyCommitValue(key: String, isAsciiMode: Boolean = false): String {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val value = config[key.lowercase()]?.tap?.value
        return value?.takeIf { it.isNotEmpty() }
            // 合并键 ID（如 "qw"）无手势配置时回退为代表字母（组内第一个字母）
            ?: key.takeIf { it.length > 1 }?.first()?.toString()
            ?: key
    }

    /** 获取某个按键指定手势的显示标签。 */
    fun getGestureLabel(key: String, gesture: String, isAsciiMode: Boolean = false): String? {
        val kc = getKeyGesture(key, isAsciiMode) ?: return null
        return when (gesture) {
            "tap" -> kc.tap?.label
            "swipe_up" -> kc.swipeUp?.label
            "swipe_down" -> kc.swipeDown?.label
            "long_press" -> kc.longPress?.values?.firstOrNull()?.label
            else -> null
        }
    }

    // ── 旧公开 API（兼容） ──
    
    fun getConfig(): KeysConfig = config
    
    fun getSwipeUpText(key: String, isAsciiMode: Boolean = false): String? {
        val gesture = getKeyGesture(key, isAsciiMode)?.swipeUp
        if (gesture != null) {
            if (gesture.value.isNotEmpty()) return gesture.value
            if (gesture.label.isNotEmpty()) return gesture.label
            return ""
        }
        return config.swipeUp[key.lowercase()]
    }

    fun getSwipeUpAction(key: String, isAsciiMode: Boolean = false): GestureAction? {
        return getKeyGesture(key, isAsciiMode)?.swipeUp?.action
    }

    /** 获取上滑显示文本（优先 label，fallback value） */
    fun getSwipeUpLabel(key: String, isAsciiMode: Boolean = false): String? {
        val gesture = getKeyGesture(key, isAsciiMode)?.swipeUp
        if (gesture != null) {
            if (gesture.label.isNotEmpty()) return gesture.label
            if (gesture.value.isNotEmpty()) return gesture.value
            return ""
        }
        return config.swipeUp[key.lowercase()]
    }

    /** 获取上滑提交值（优先 value，fallback label） */
    fun getSwipeUpCommitValue(key: String, isAsciiMode: Boolean = false): String? {
        val gesture = getKeyGesture(key, isAsciiMode)?.swipeUp
        if (gesture != null) {
            if (gesture.value.isNotEmpty()) return gesture.value
            if (gesture.label.isNotEmpty()) return gesture.label
            return ""
        }
        return config.swipeUp[key.lowercase()]
    }
    
    fun getSwipeDownEnglishText(key: String, isAsciiMode: Boolean = false): String? {
        val fromYaml = getKeyGesture(key, isAsciiMode)?.swipeDown?.label
        if (fromYaml != null) return fromYaml
        return config.swipeDownEnglish[key.lowercase()]
    }

    /** 获取下滑动作类型 */
    fun getSwipeDownAction(key: String, isAsciiMode: Boolean = false): GestureAction? {
        return getKeyGesture(key, isAsciiMode)?.swipeDown?.action
    }

    /** 获取下滑显示位置：key（按键上）或 bubble（气泡） */
    fun getSwipeDownDisplay(key: String, isAsciiMode: Boolean = false): DisplayMode {
        return getKeyGesture(key, isAsciiMode)?.swipeDown?.display ?: DisplayMode.BOTH
    }

    /** 获取上滑显示位置：key（按键上）或 bubble（气泡） */
    fun getSwipeUpDisplay(key: String, isAsciiMode: Boolean = false): DisplayMode {
        return getKeyGesture(key, isAsciiMode)?.swipeUp?.display ?: DisplayMode.BOTH
    }

    private fun getDefaultSwipeUp(): Map<String, String> = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
        "a" to "!", "s" to "@", "d" to "#", "f" to "$", "g" to "%",
        "h" to "^", "j" to "&", "k" to "(", "l" to ")",
        "z" to "|", "x" to "*", "c" to "\\", "v" to "?", "b" to "_",
        "n" to "-", "m" to "+"
    )
    
    private fun getDefaultSwipeDownEnglish(): Map<String, String> = mapOf(
        "q" to "Q", "w" to "W", "e" to "E", "r" to "R", "t" to "T",
        "y" to "Y", "u" to "U", "i" to "I", "o" to "O", "p" to "P",
        "a" to "A", "s" to "S", "d" to "D", "f" to "F", "g" to "G",
        "h" to "H", "j" to "J", "k" to "K", "l" to "L",
        "z" to "Z", "x" to "X", "c" to "C", "v" to "V", "b" to "B",
        "n" to "N", "m" to "M"
    )
    

}
