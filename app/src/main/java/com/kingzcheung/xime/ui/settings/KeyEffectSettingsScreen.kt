package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.settings.KeysConfigHelper
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.CompositionLocalProvider
import com.kingzcheung.xime.ui.keyboard.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.viewmodel.KeyEffectSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyEffectSettingsContent(
    onBack: () -> Unit
) {
    val viewModel: KeyEffectSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("按键效果") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(title = "按键音效", content = {
                    Row(
                        modifier = Modifier.settingsSearchAnchor("启用按键音")
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用按键音",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "按键时播放音效",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.soundEnabled,
                            onCheckedChange = { viewModel.setSoundEnabled(it) }
                        )
                    }

                    if (uiState.soundEnabled) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        // 按键音色：和音量一样是按键音的从属行，按键音关掉时一起隐藏；写入即时生效（FeedbackManager 监听这个键）。
                        KeySoundStyleSetting()
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Column(
                            modifier = Modifier.settingsSearchAnchor("音量大小").padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "音量大小",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${uiState.soundVolume}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Slider(
                                value = uiState.soundVolume.toFloat(),
                                onValueChange = { viewModel.setSoundVolume(it.toInt()) },
                                valueRange = 0f..100f,
                                steps = 19
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        KeySoundCapturableSetting()
                    }
                })
            }

            item {
                SettingsSection(title = "振动反馈", content = {
                    Column(modifier = Modifier.settingsSearchAnchor("触觉反馈模式").padding(16.dp)) {
                        Text(
                            text = "触觉反馈模式",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            // 有人调了半天不知道强弱在哪调（用户转述 10-31）：直接说强弱归谁管。
                            text = "跟随系统：强弱跟手机设置里的触感强度；想自己调强弱，选「启用」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // 卡片高度按字号算：图示两行文字 + 间距 + 卡片内外边距 + 下方标题，大字体下也不裁字。
                        val hapticRowHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
                            HAPTIC_CARD_TEXT_HEIGHT.toDp() + MaterialTheme.typography.bodyMedium.lineHeight.toDp()
                        } + 4.dp + 32.dp + 6.dp + 4.dp
                        Row(
                            modifier = Modifier.fillMaxWidth().height(maxOf(104.dp, hapticRowHeight)),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "following_system" to "跟随系统",
                                "enabled" to "启用",
                                "disabled" to "关闭"
                            ).forEach { (value, label) ->
                                IllustratedChoiceCard(label, uiState.hapticMode == value,
                                    onClick = { viewModel.setHapticMode(value) },
                                    modifier = Modifier.weight(1f), tag = "haptic-mode:$value") {
                                    HapticModeIllustration(value)
                                }
                            }
                        }
                    }

                    if (uiState.hapticMode != "disabled") {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Row(
                            modifier = Modifier.settingsSearchAnchor("按键抬起振动")
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "按键抬起振动",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "手指抬起时触发短振动",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.hapticOnKeyUp,
                                onCheckedChange = { viewModel.setHapticOnKeyUp(it) }
                            )
                        }
                    }

                    if (uiState.hapticMode == "enabled") {
                        // 线性马达点击触感的机型上时长由马达决定，滑条不起作用就不显示。
                        if (!uiState.hapticPrimitives) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Column(modifier = Modifier.settingsSearchAnchor("按键振动时长").padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "按键振动时长",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (uiState.pressDuration == 0) "系统默认" else "${uiState.pressDuration} ms",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Slider(
                                value = uiState.pressDuration.toFloat(),
                                onValueChange = { viewModel.setPressDuration(it.toInt()) },
                                valueRange = 0f..100f,
                                steps = 19
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Column(modifier = Modifier.settingsSearchAnchor("长按振动时长").padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "长按振动时长",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (uiState.longPressDuration == 0) "系统默认" else "${uiState.longPressDuration} ms",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Slider(
                                value = uiState.longPressDuration.toFloat(),
                                onValueChange = { viewModel.setLongPressDuration(it.toInt()) },
                                valueRange = 0f..100f,
                                steps = 19
                            )
                        }
                        }

                        if (uiState.hasAmplitudeControl || uiState.hapticPrimitives) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            Column(modifier = Modifier.settingsSearchAnchor("按键振动振幅").padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "按键振动振幅",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (uiState.pressAmplitude == 0) "系统默认" else "${uiState.pressAmplitude}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Slider(
                                    value = uiState.pressAmplitude.toFloat(),
                                    onValueChange = { viewModel.setPressAmplitude(it.toInt()) },
                                    valueRange = 0f..255f,
                                    steps = 50
                                )
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            Column(modifier = Modifier.settingsSearchAnchor("长按振动振幅").padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "长按振动振幅",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (uiState.longPressAmplitude == 0) "系统默认" else "${uiState.longPressAmplitude}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Slider(
                                    value = uiState.longPressAmplitude.toFloat(),
                                    onValueChange = { viewModel.setLongPressAmplitude(it.toInt()) },
                                    valueRange = 0f..255f,
                                    steps = 50
                                )
                            }
                        }

                        Text(
                            text = if (uiState.hapticPrimitives) "为 0（「系统默认」）时用内置的轻触强度。「长按振动振幅」管按住删除键连删时的每一下，为 0 时和按键振动一样。"
                                else "都为 0（「系统默认」）时用系统默认振动。「长按」两项管按住删除键连删时的每一下，为 0 时和按键振动一样。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        )
                    }
                })
            }
            item {
                KeyVisualEffectSettings(uiState.keyGlowEnabled, uiState.keyAnimationEnabled,
                    viewModel::setKeyGlowEnabled, viewModel::setKeyAnimationEnabled)
            }
        }
    }
}

@Composable
internal fun KeyVisualEffectSettings(
    glowEnabled: Boolean, animationEnabled: Boolean,
    onGlowChange: (Boolean) -> Unit, onAnimationChange: (Boolean) -> Unit,
) {
    SettingsSection(title = "按键视觉效果", content = {
        Column(Modifier.padding(16.dp)) {
            KeyVisualEffectToggle("按键动效", "点按时键帽缩放回弹，可单独开启。",
                animationEnabled, onAnimationChange)
            Spacer(Modifier.height(12.dp))
            KeyVisualEffectToggle("键内炫光", "点按时显示半秒渐变光效，可单独开启。",
                glowEnabled, onGlowChange)
            Spacer(Modifier.height(12.dp))
            Text("首次使用时按设备配置推荐，两项均可自行调整。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("点按下方按键预览", style = MaterialTheme.typography.labelMedium)
            val preview = rememberKeyboardInputPreferences().copy(
                keyGlowEnabled = glowEnabled, keyAnimationEnabled = animationEnabled)
            CompositionLocalProvider(LocalKeyboardInputPreferences provides preview,
                LocalKeyCornerRadius provides KeysConfigHelper.getKeyboardKeyConfig().cornerRadius.dp) {
                Row(Modifier.fillMaxWidth().height(56.dp)) {
                    listOf("A", "あ", "123").forEach { label ->
                        KeyButton(label, {}, MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer, Modifier.weight(1f))
                    }
                    ActionKeyButton("删除", {}, MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer, Modifier.weight(1f))
                }
            }
        }
    })
}

@Composable
private fun KeyVisualEffectToggle(title: String, description: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.settingsSearchAnchor(title, bleed = 16.dp).fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}

/**
 * 录屏时包含按键音：按键音的从属行，按键音关掉时一起隐藏；默认关。
 * 键名与 FeedbackManager.KEY_SOUND_CAPTURABLE 相同，FeedbackManager 监听这个键即时切换。
 */
@Composable
private fun KeySoundCapturableSetting() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context)
    var capturable by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(prefs.getBoolean("key_sound_capturable", false))
    }
    val onChange: (Boolean) -> Unit = {
        capturable = it
        prefs.edit().putBoolean("key_sound_capturable", it).apply()
    }
    Row(Modifier.settingsSearchAnchor("录屏时包含按键音").fillMaxWidth().toggleable(value = capturable, role = Role.Switch, onValueChange = onChange).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("录屏时包含按键音", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text("开启后录屏能录到按键音；按键音改跟随媒体音量，静音和振动模式下仍不出声",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = capturable, onCheckedChange = null)
    }
}

/** 按键音色二选一：默认 / Rain75 紫薇轴（存的值 default|mahjong）；样式和「触觉反馈模式」的卡片一样。 */
@Composable
private fun KeySoundStyleSetting() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context)
    val key = com.kingzcheung.xime.service.FeedbackManager.KEY_SOUND_STYLE
    // 以前选过已删的「清脆」（crisp）按默认显示，和 FeedbackManager 的处理一致。
    var style by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf((prefs.getString(key, "default") ?: "default").takeIf { it == "mahjong" } ?: "default")
    }
    Column(modifier = Modifier.settingsSearchAnchor("按键音色").padding(16.dp)) {
        Text(text = "按键音色", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(text = "换一种按键声音，选好立即生效", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        val rowHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
            // 标题按两行算：「Rain75 紫薇轴」在窄屏要换行才放得下。
            HAPTIC_CARD_TEXT_HEIGHT.toDp() + MaterialTheme.typography.bodyMedium.lineHeight.toDp() * 2
        } + 4.dp + 32.dp + 6.dp + 4.dp
        Row(modifier = Modifier.fillMaxWidth().height(maxOf(104.dp, rowHeight)),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 「清脆」已删（用户 10-21）；实体键盘默认就用 Rain75，写在「默认」卡片的小字里。
            listOf(Triple("default", "默认", "♪" to "实体键盘用 Rain75"), Triple("mahjong", "Rain75 紫薇轴", "▣" to "线性轴")).forEach { (value, label, art) ->
                IllustratedChoiceCard(label, style == value, onClick = {
                    style = value
                    prefs.edit().putString(key, value).apply()
                }, modifier = Modifier.weight(1f), tag = "key-sound-style:$value", titleMaxLines = 2) {
                    val primary = MaterialTheme.colorScheme.primary
                    Text(art.first, fontSize = 20.sp, lineHeight = 24.sp, color = primary)
                    Spacer(Modifier.height(4.dp))
                    Text(art.second, fontSize = 12.sp, lineHeight = 16.sp, color = primary, maxLines = 1)
                }
            }
        }
    }
}
