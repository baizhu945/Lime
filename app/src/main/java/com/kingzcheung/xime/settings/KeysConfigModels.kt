package com.kingzcheung.xime.settings

import android.util.Log
import com.charleskorn.kaml.YamlContentPolymorphicSerializer
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.kingzcheung.xime.keyboard.GestureAction
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

// ── 键盘手势配置 ──

enum class DisplayMode(val value: String) {
    KEY("key"), BUBBLE("bubble"), BOTH("both");

    companion object {
        fun fromValue(value: String): DisplayMode = entries.firstOrNull { it.value == value } ?: BOTH
    }
}

/** 按键布局模式，从 xime.yaml keyboard.button_layout 加载。 */
enum class ButtonLayout(val value: String) {
    STANDARD("standard"),
    COMPACT("compact");

    companion object {
        fun fromValue(value: String): ButtonLayout =
            entries.firstOrNull { it.value == value } ?: STANDARD
    }
}

/** Xime actions with no CyIME GestureAction equivalent; represented but not executed here. */
enum class XimeAction(val value: String) {
    SEND_RIME("send_rime"), VOICE("voice"), REPEAT_SPACE("repeat_space");

    companion object {
        fun fromValue(value: String): XimeAction? = entries.firstOrNull { it.value == value }
    }
}

data class GestureDef(
    val label: String = "",
    /** 多行标签（YAML 数组格式），每行作为独立字符串，用于多行显示 */
    val labels: List<String> = emptyList(),
    val action: GestureAction? = GestureAction.COMMIT,
    val value: String = "",
    val icon: String = "",
    val display: DisplayMode = DisplayMode.BOTH,
    /** CyIME 尚不认识的 action 名称，仅保留供后续适配；现有路由不执行它。 */
    val unsupportedAction: String? = null,
    /** Xime's display hint; missing or invalid values use its default, true. */
    val bubble: Boolean = true,
    /** A tap with no explicit action has Xime SEND_RIME semantics, without changing CyIME's action.
     *  放在类体内：不参与 data class 相等，避免同一按键经不同加载路径后比较不等。 */
) {
    var implicitXimeTap: Boolean = false

    val ximeAction: XimeAction?
        get() = unsupportedAction?.let(XimeAction::fromValue)
            ?: if (implicitXimeTap && action == GestureAction.COMMIT) XimeAction.SEND_RIME else null
}

/**
 * 把滑动手势定义转换为布局层回调（九键/笔画布局共用，26 键在 KeyboardLayout 内联处理）。
 *
 * - COMMIT 走 [onCommitText]：落点由布局决定（九键直接上屏数字——T9 模式 onKeyPress(数字)
 *   会进拼音数字码组合，笔画则沿用按键路由保持原语义）；
 * - NONE 视为未绑定；
 * - 其余动作（复制/粘贴/光标移动/面板切换等）走 [onGestureAction]（UI 拦截层 → GestureAction.execute）。
 */
internal fun swipeHandlerFor(
    def: GestureDef?,
    onCommitText: (String) -> Unit,
    onGestureAction: ((GestureAction, String) -> Unit)?,
): (() -> Unit)? {
    val action = def?.action ?: return null
    if (action == GestureAction.NONE) return null
    val value = def.value.ifEmpty { def.label }
    return {
        if (action == GestureAction.COMMIT) onCommitText(value)
        else onGestureAction?.invoke(action, value)
    }
}

data class LongPressConfig(
    val display: String = "key", // "key"（默认）显示在按键上, "bubble" 气泡弹出
    val values: List<GestureDef> = emptyList(),
    /** Xime's direct action form, kept separate so legacy values consumers remain unchanged. */
    val directAction: GestureDef? = null,
)

data class KeyGestureConfig(
    val tap: GestureDef? = null,
    val swipeUp: GestureDef? = null,
    val swipeDown: GestureDef? = null,
    val longPress: LongPressConfig? = null,
    val width: Float? = null,
    val doubleTap: GestureDef? = null,
    val swipeLeft: GestureDef? = null,
    val swipeRight: GestureDef? = null,
)

enum class RowAlign(val value: String) {
    UNIFORM("uniform"), STRETCH("stretch");

    companion object {
        fun fromValue(value: String): RowAlign? =
            entries.firstOrNull { it.value.equals(value.trim(), ignoreCase = true) }
    }
}

data class KeyboardRow(val keys: List<String>, val align: RowAlign? = null, val width: Float? = null)

data class KeyboardRowsLayout(
    val rows: List<KeyboardRow>,
    val columns: Int? = null,
    val rowAlign: RowAlign = RowAlign.UNIFORM,
)

data class KeyboardConfig(
    val keys: Map<String, KeyGestureConfig> = emptyMap(),
)

/**
 * 键盘行布局配置，从 xime.yaml keyboard.<section>.layout 加载。
 * rows[i] 为第 i 行的按键 ID 列表。
 */
data class KeyboardLayoutConfig(
    val rows: List<List<String>> = emptyList(),
)

/**
 * 键盘阴影配置，从 xime.yaml keyboard.shadow 加载。
 */
data class KeyboardShadowConfig(
    val enabled: Boolean = true,
    val elevation: Int = 1,
    val shapeRadius: Int = 8,
)

/**
 * 单个键盘的间距覆盖配置。
 */
data class KeyboardSpacingConfig(
    val spacingX: Float? = null,
    val spacingY: Float? = null,
)

/**
 * 键盘按键配置，从 xime.yaml keyboard.key 加载。
 */
data class KeyboardKeyConfig(
    val cornerRadius: Int = 8,
    /** 按键横向间距（dp），未配置时各布局使用自身默认值 */
    val spacingX: Float? = null,
    /** 按键竖向间距（dp），未配置时各布局使用自身默认值 */
    val spacingY: Float? = null,
    /** 各键盘单独覆盖的间距，key 为键盘名（qwerty/t9/number/stroke/symbol） */
    val spacingOverrides: Map<String, KeyboardSpacingConfig> = emptyMap(),
) {
    /**
     * 获取指定键盘的有效间距（覆盖值优先，回退全局值）。
     * @return Pair(横向, 竖向)，null 表示未配置
     */
    fun spacingFor(keyboard: String): Pair<Float?, Float?> {
        val override = spacingOverrides[keyboard]
        val sx = override?.spacingX ?: spacingX
        val sy = override?.spacingY ?: spacingY
        return sx to sy
    }
}

/**
 * 键盘颜色配置，从 xime.yaml keyboard.colors 加载。
 * 所有颜色值为 0xRRGGBB 格式（不含 alpha）。
 */
/** 全局键盘颜色后备，背景色已由 color_schemes 的 keyboard_background 接管。 */
data class KeyboardColorsConfig(
    val keyBgColor: Long = 0xFFFFFF,
    val keyBgColorDark: Long = 0x4A4A4A,
    val specialKeyBgColor: Long? = null,
    val specialKeyBgColorDark: Long? = null,
    val keyTextColor: Long = 0x202124,
    val keyTextColorDark: Long = 0xE8EAED,
    val candidateTextColor: Long = 0x1A73E8,
    val candidateTextColorDark: Long = 0x8AB4F8,
) {
    companion object {
        /** 键盘背景后备色，仅当 color_schemes 无 keyboard_background 时使用。 */
        const val FALLBACK_BG_LIGHT: Long = 0xE3E4E8
        const val FALLBACK_BG_DARK: Long = 0x202020
    }
}

/**
 * 从 YAML node 解析 KeyboardConfig。
 *
 * 支持两种格式：
 * - 字符串 `"q"` → 等价于 GestureDef(label="q", action="commit", value="q")
 * - 对象 `{ label: "复制", action: "copy" }` → 完整定义
 */
internal fun parseKeyboardConfig(raw: com.charleskorn.kaml.YamlMap?): KeyboardConfig? {
    if (raw == null) return null
    val keysNode = raw["keys"] as? com.charleskorn.kaml.YamlMap ?: return KeyboardConfig()
    val keys = mutableMapOf<String, KeyGestureConfig>()
    for ((keyNode, valueNode) in keysNode.entries) {
        val key = (keyNode as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
        val gestureMap = valueNode as? com.charleskorn.kaml.YamlMap ?: continue
        keys[key] = parseKeyGestureConfig(gestureMap)
    }
    return KeyboardConfig(keys)
}

internal fun parseKeyGestureConfig(map: com.charleskorn.kaml.YamlMap): KeyGestureConfig {
    var tap: GestureDef? = null
    var swipeUp: GestureDef? = null
    var swipeDown: GestureDef? = null
    var longPress: LongPressConfig? = null
    var width: Float? = null
    var doubleTap: GestureDef? = null
    var swipeLeft: GestureDef? = null
    var swipeRight: GestureDef? = null
    for ((kNode, vNode) in map.entries) {
        val name = (kNode as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
        when (name) {
            "tap" -> tap = parseGestureNode(vNode, isTap = true)
            "swipe_up" -> swipeUp = parseGestureNode(vNode)
            "swipe_down" -> swipeDown = parseGestureNode(vNode)
            "long_press" -> longPress = parseLongPress(vNode)
            "width" -> width = (vNode as? YamlScalar)?.content?.trim()?.toFloatOrNull()
                ?.takeIf { it.isFinite() && it > 0f }
            "double_tap" -> if (vNode is YamlScalar || vNode is YamlMap) doubleTap = parseGestureNode(vNode)
            "swipe_left" -> if (vNode is YamlScalar || vNode is YamlMap) swipeLeft = parseGestureNode(vNode)
            "swipe_right" -> if (vNode is YamlScalar || vNode is YamlMap) swipeRight = parseGestureNode(vNode)
        }
    }
    return KeyGestureConfig(tap, swipeUp, swipeDown, longPress, width, doubleTap, swipeLeft, swipeRight)
}

/**
 * 解析 long_press，支持两种格式：
 *   新格式（推荐）：{ display: "bubble", values: ["q", "Q"] }
 *   旧格式（兼容）：["q", "Q"]
 */
internal fun parseLongPress(node: com.charleskorn.kaml.YamlNode): LongPressConfig? {
    // 旧格式：纯数组 → 默认 display="key"
    if (node is YamlList) {
        val values = node.items.map { parseGestureNode(it) }.take(10)
        return LongPressConfig(display = "key", values = values)
    }
    // 新格式：对象 { display, values }
    if (node is com.charleskorn.kaml.YamlMap) {
        var display = "key"
        var values: List<GestureDef> = emptyList()
        var hasValues = false
        var hasDirectAction = false
        for ((k, v) in node.entries) {
            val key = (k as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
            when (key) {
                "display" -> display = (v as? com.charleskorn.kaml.YamlScalar)?.content ?: "key"
                "values" -> {
                    hasValues = true
                    if (v is YamlList) values = v.items.map { parseGestureNode(it) }.take(10)
                }
                "action", "value", "label", "icon", "use" -> hasDirectAction = true
            }
        }
        val direct = if (!hasValues && hasDirectAction) parseGestureNode(node) else null
        return LongPressConfig(display = display, values = values, directAction = direct)
    }
    return null
}

internal fun parseGestureNode(node: com.charleskorn.kaml.YamlNode, isTap: Boolean = false): GestureDef {
    // 字符串 → commit
    if (node is com.charleskorn.kaml.YamlScalar) {
        val text = node.content
        val icon = if (text.startsWith("@")) text.removePrefix("@") else ""
        val cleanLabel = if (icon.isNotEmpty()) "" else text
        return GestureDef(label = cleanLabel, action = GestureAction.COMMIT, value = text, icon = icon)
            .also { it.implicitXimeTap = isTap }
    }
    // 映射 → 完整定义
    if (node is com.charleskorn.kaml.YamlMap) {
        var label = ""
        var labels: List<String> = emptyList()
        var action: GestureAction? = GestureAction.COMMIT
        var unsupportedAction: String? = null
        var hasExplicitAction = false
        var bubble = true
        var value = ""
        var display = "key"
        for ((k, v) in node.entries) {
            val key = (k as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
            when (key) {
                "label" -> {
                    if (v is YamlList) {
                        labels = v.items.mapNotNull { (it as? YamlScalar)?.content }
                        label = labels.joinToString("\n")
                    } else {
                        val vStr = (v as? YamlScalar)?.content ?: continue
                        label = vStr
                    }
                }
                "action" -> {
                    hasExplicitAction = true
                    val vStr = (v as? YamlScalar)?.content ?: continue
                    if (vStr == "null") {
                        action = null
                    } else {
                        action = GestureAction.fromValue(vStr)
                        unsupportedAction = if (action == null) vStr else null
                        if (action == null) {
                            Log.w("KeysConfigHelper", "手势配置包含未知 action: \"$vStr\"，该 tap/滑动将不生效")
                        }
                    }
                }
                "value" -> {
                    val vStr = (v as? YamlScalar)?.content ?: continue
                    value = vStr
                }
                "display" -> {
                    val vStr = (v as? YamlScalar)?.content ?: continue
                    display = vStr
                }
                "bubble" -> bubble = (v as? YamlScalar)?.content?.toBooleanStrictOrNull() ?: true
            }
        }
        val icon = if (label.startsWith("@")) label.removePrefix("@") else ""
        val cleanLabel = if (icon.isNotEmpty()) "" else label
        return GestureDef(label = cleanLabel, labels = labels, action = action, value = value, icon = icon,
            display = DisplayMode.fromValue(display), unsupportedAction = unsupportedAction, bubble = bubble)
            .also { it.implicitXimeTap = isTap && !hasExplicitAction }
    }
    return GestureDef()
}

// ── 原有配置类 ──

/**
 * 背景配置，支持纯色/渐变/图片三种类型。
 *
 * YAML 示例：
 * ```yaml
 * # 纯色
 * keyboard_background:
 *   type: solid
 *   color: 0x8F73E2
 *   color_dark: 0x4A3F7A   # 可选，不指定则自动暗化
 *
 * # 渐变
 * keyboard_background:
 *   type: gradient
 *   colors: [0x8F73E2, 0xE8DEF8]        # 亮色渐变断点
 *   colors_dark: [0x4A3F7A, 0x2D2040]    # 暗色渐变断点（可选）
 *   angle: 135  # 角度制，0=左→右，90=下→上，180=右→左，270=上→下
 *
 * # 图片
 * keyboard_background:
 *   type: image
 *   src: "themes/lavender_bg.png"          # 相对于 assets/
 *   src_dark: "themes/lavender_bg_dark.png" # 暗色变体（可选）
 *   fit: cover  # cover | contain | fill | fit_width | fit_height | none
 * ```
 */
@Serializable
data class BackgroundConfig(
    val type: String = "solid",
    // solid
    val color: Long? = null,
    @SerialName("color_dark")
    val colorDark: Long? = null,
    // gradient
    val colors: List<Long>? = null,
    @SerialName("colors_dark")
    val colorsDark: List<Long>? = null,
    val angle: Int? = null,
    // image
    val src: String? = null,
    @SerialName("src_dark")
    val srcDark: String? = null,
    val fit: String? = null,
    /** 图片在 [fit] 的基准大小上再放大的倍数（cover 至少 1，contain 可小于 1），未配置为 1。 */
    val zoom: Float? = null,
    /** 图片在可移动范围里的横向位置：0 左边对齐，1 右边对齐，未配置居中。 */
    @SerialName("align_x")
    val alignX: Float? = null,
    /** 纵向位置：0 上边对齐，1 下边对齐，未配置居中。 */
    @SerialName("align_y")
    val alignY: Float? = null,
    /** 图片背景遮罩：半透明黑色覆盖层降低背景亮度（0~1，如 0.35 表示压暗 35%）。 */
    @SerialName("overlay_alpha")
    val overlayAlpha: Float? = null,
    /** 暗色模式下的遮罩强度，未配置时沿用 [overlayAlpha]。 */
    @SerialName("overlay_alpha_dark")
    val overlayAlphaDark: Float? = null,
    /** 遮罩颜色（0xRRGGBB），未配置时为黑色；浅色主题用同色调浅色遮罩提亮图片。 */
    @SerialName("overlay_color")
    val overlayColor: Long? = null,
    /** 暗色模式下的遮罩颜色，未配置时沿用 [overlayColor]。 */
    @SerialName("overlay_color_dark")
    val overlayColorDark: Long? = null,
)

@Serializable
data class ColorSchemeEntry(
    val name: String = "",
    @SerialName("primary_color")
    val primaryColor: Long = 0,
    @SerialName("keyboard_bg_color")
    val keyboardBgColor: Long? = null,
    @SerialName("key_bg_color")
    val keyBgColor: Long? = null,
    @SerialName("key_bg_color_dark")
    val keyBgColorDark: Long? = null,
    @SerialName("special_key_bg_color")
    val specialKeyBgColor: Long? = null,
    @SerialName("special_key_bg_color_dark")
    val specialKeyBgColorDark: Long? = null,
    @SerialName("enter_key_bg_color")
    val enterKeyBgColor: Long? = null,
    @SerialName("enter_key_bg_color_dark")
    val enterKeyBgColorDark: Long? = null,
    @SerialName("special_key_text_color")
    val specialKeyTextColor: Long? = null,
    @SerialName("special_key_text_color_dark")
    val specialKeyTextColorDark: Long? = null,
    @SerialName("candidate_bar_bg_color")
    val candidateBarBgColor: Long? = null,
    @SerialName("key_text_color")
    val keyTextColor: Long? = null,
    @SerialName("key_text_color_dark")
    val keyTextColorDark: Long? = null,
    @SerialName("candidate_text_color")
    val candidateTextColor: Long? = null,
    @SerialName("candidate_text_color_dark")
    val candidateTextColorDark: Long? = null,
    @SerialName("candidate_selected_text_color")
    val candidateSelectedTextColor: Long? = null,
    @SerialName("candidate_selected_text_color_dark")
    val candidateSelectedTextColorDark: Long? = null,
    @SerialName("keyboard_background")
    val keyboardBackground: BackgroundConfig? = null,
    @SerialName("key_background")
    val keyBackground: BackgroundConfig? = null,
    @SerialName("candidate_bar_background")
    val candidateBarBackground: BackgroundConfig? = null,
    /** Material You 动态配色：忽略静态颜色字段，运行时从壁纸调色板取色（仅 Android 12+）。 */
    @SerialName("dynamic_color")
    val dynamicColor: Boolean = false,
)

@Serializable
data class MetadataConfig(
    @SerialName("app_name")
    val appName: String = "Lime",
    @SerialName("app_version")
    val appVersion: String = "",
    @SerialName("platform")
    val platform: String = "android",
    @SerialName("config_version")
    val configVersion: Int = 1,
    @SerialName("generator")
    val generator: String = "",
    @SerialName("modified_time")
    val modifiedTime: String = "",
)

@Serializable
data class ColorSchemeModeConfig(
    @SerialName("light")
    val light: String? = null,
    @SerialName("dark")
    val dark: String? = null,
)

/** 兼容 color_scheme 的两种写法：对象（{light, dark}）或标量字符串。 */
@OptIn(ExperimentalSerializationApi::class)
object ColorSchemeModeConfigSerializer :
    YamlContentPolymorphicSerializer<ColorSchemeModeConfig>(ColorSchemeModeConfig::class) {

    override fun selectDeserializer(node: YamlNode): DeserializationStrategy<ColorSchemeModeConfig> {
        // 标量写法（config generator 输出）：color_scheme: mu_shan_zi → light 使用该值。
        return if (node is YamlScalar) ScalarColorSchemeDeserializer else ColorSchemeModeConfig.serializer()
    }

    /** 把单个字符串的 color_scheme 反序列化为 light=该字符串。 */
    private object ScalarColorSchemeDeserializer : KSerializer<ColorSchemeModeConfig> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("ScalarColorScheme", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): ColorSchemeModeConfig =
            ColorSchemeModeConfig(light = decoder.decodeString())

        override fun serialize(encoder: Encoder, value: ColorSchemeModeConfig) {
            encoder.encodeString(value.light ?: "")
        }
    }
}

/** 字体配置，从 xime.yaml keyboard.fonts 节点加载。字体文件放在 rime/fonts/ 目录下。 */
data class KeyboardFontConfig(
    val keyFont: String = "",
    val keyLabelFont: String = "",
    val candidateFont: String = "",
    val commentFont: String = "",
)

/**
 * 九键（T9）键盘配置，从 xime.yaml keyboard.t9 加载。
 */
data class KeyboardT9Config(
    /** 左侧快捷符号栏（空闲态），列表长度不限，超过 4 个时键盘侧滚动显示。 */
    val sideSymbols: List<String>? = null,
)

/**
 * 笔画键盘配置，从 xime.yaml keyboard.stroke 加载。
 */
data class KeyboardStrokeConfig(
    /** 左侧快捷符号列，列表长度不限，超过 3 个时键盘侧滚动显示。 */
    val sideSymbols: List<String>? = null,
)

/**
 * 部分配置：仅包含 YAML 中显式配置的字段，null = 未配置。
 * 用于 custom → builtIn → 代码默认值的字段级一路 fallback 合并。
 */
internal data class KeyboardFontPartial(
    val keyFont: String? = null,
    val keyLabelFont: String? = null,
    val candidateFont: String? = null,
    val commentFont: String? = null,
)

internal data class KeyboardColorsPartial(
    val keyBgColor: Long? = null,
    val keyBgColorDark: Long? = null,
    val specialKeyBgColor: Long? = null,
    val specialKeyBgColorDark: Long? = null,
    val keyTextColor: Long? = null,
    val keyTextColorDark: Long? = null,
    val candidateTextColor: Long? = null,
    val candidateTextColorDark: Long? = null,
)

internal data class KeyboardShadowPartial(
    val enabled: Boolean? = null,
    val elevation: Int? = null,
    val shapeRadius: Int? = null,
)

internal data class KeyboardKeyPartial(
    val cornerRadius: Int? = null,
    val spacingX: Float? = null,
    val spacingY: Float? = null,
    val spacingOverrides: Map<String, KeyboardSpacingConfig>? = null,
)

internal data class KeyboardT9Partial(
    val sideSymbols: List<String>? = null,
)

internal data class KeyboardStrokePartial(
    val sideSymbols: List<String>? = null,
)

/** 字段级一路 fallback：custom → builtIn → 代码默认值。 */
internal inline fun <T> tiered(custom: T?, builtIn: T?, default: T): T = custom ?: builtIn ?: default

/** 字符串字段的 fallback：空白视为未配置，路由到下一级。 */
internal fun tieredText(custom: String?, builtIn: String?, default: String): String =
    custom?.takeIf { it.isNotBlank() }
        ?: builtIn?.takeIf { it.isNotBlank() }
        ?: default

/**
 * kaml 0.104 的 [YamlMap.get] 是 reified 泛型取值，值类型与期望类型不符时抛
 * IncorrectTypeException（如 `fonts:` 段整体被注释时值为 YamlNull，`["fonts"] as? YamlMap`
 * 会在 get 内部直接抛异常，`as?` 无机会返回 null）。
 * 统一经 YamlNode 取值后再做真正的安全转换：段缺失/为 null/类型不符时返回 null 而非抛异常。
 */
internal inline fun <reified T : YamlNode> YamlMap.opt(key: String): T? =
    get<YamlNode>(key) as? T

@Serializable
data class StyleConfig(
    @SerialName("color_scheme")
    @Serializable(with = ColorSchemeModeConfigSerializer::class)
    val colorScheme: ColorSchemeModeConfig? = null,
    @SerialName("dark_mode")
    val darkMode: Int? = null,
    @SerialName("key_font")
    val keyFont: String? = null,
    @SerialName("key_label_font")
    val keyLabelFont: String? = null,
    @SerialName("candidate_font")
    val candidateFont: String? = null,
    @SerialName("comment_font")
    val commentFont: String? = null,
)

@Serializable
data class XimeConfig(
    @SerialName("xime_index")
    val ximeIndex: XimeIndexConfig? = null,
    @SerialName("color_schemes")
    val colorSchemes: Map<String, ColorSchemeEntry>? = null,
    @SerialName("style")
    val style: StyleConfig? = null,
    @SerialName("metadata")
    val metadata: MetadataConfig? = null,
)

/** CyIME 自己的商店目录（仓库 store/），与 assets/xime.yaml 的 xime_index.base_urls 保持一致。 */
internal val CYIME_STORE_BASE_URLS = listOf(
    "https://cdn.jsdelivr.net/gh/Cyletix/CyIME@main/store/",
    "https://fastly.jsdelivr.net/gh/Cyletix/CyIME@main/store/",
    "https://raw.githubusercontent.com/Cyletix/CyIME/main/store/",
)

@Serializable
data class XimeIndexConfig(
    @SerialName("base_urls")
    val baseUrls: List<String> = CYIME_STORE_BASE_URLS
)

data class KeysConfig(
    val swipeUp: Map<String, String> = emptyMap(),
    val swipeDownEnglish: Map<String, String> = emptyMap()
)

