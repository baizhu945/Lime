package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.visualMaterial

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.WidthFull
import androidx.compose.material.icons.twotone.WidthNormal
import androidx.compose.material.icons.twotone.BrightnessAuto
import androidx.compose.material.icons.twotone.DarkMode
import androidx.compose.material.icons.twotone.Keyboard
import androidx.compose.material.icons.twotone.LightMode
import androidx.compose.material.icons.twotone.Padding
import androidx.compose.material.icons.twotone.Quickreply
import androidx.compose.material.icons.twotone.Rotate90DegreesCcw
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.SettingsOverscan
import androidx.compose.material.icons.twotone.Palette
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.Mic
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material.icons.filled.DocumentScanner
import com.kingzcheung.xime.ui.settings.SettingsRoutes
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.luminance
import com.kingzcheung.xime.keyboard.ToolbarAction
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.viewmodel.SchemaSwitchUiState

internal fun menuAppearanceLabel(mode: Int): String = when (mode) {
    0 -> "浅色模式"
    1 -> "深色模式"
    else -> "跟随系统"
}

data class MenuItem(
    val icon: Painter? = null,
    val label: String,
    val action: () -> Unit,
    val textIcon: String? = null,
    val currentState: String? = null,
    val id: String = label,
    /** 非空即开关项：开启时反色填充。 */
    val selected: Boolean? = null,
    val toolbarItem: ToolbarButtonItem? = null,
    /** 长按打开设置里的对应项；没有对应设置时为 null，长按不响应。 */
    val onLongClick: (() -> Unit)? = null,
)

data class MenuBarState(
    val isVisible: Boolean,
    val isDarkTheme: Boolean,
    val darkMode: Int = 2,
    val backgroundColor: Color,
    val keyBgColor: Color = Color.White,
    val keyTextColor: Color = Color(0xFF202124),
    val isFloatingMode: Boolean = false,
    val schemaSwitches: List<SchemaSwitchUiState> = emptyList(),
    /** 英文（ASCII）模式下标点固定半角，全角／半角入口不适用。 */
    val isAsciiMode: Boolean = false,
)

/** 菜单与工具栏共用一组快捷入口；开关项开启时反色填充，点一下即切换且菜单保持打开。 */
@Composable
fun MenuBar(
    state: MenuBarState,
    actions: List<ToolbarAction>,
    modifier: Modifier = Modifier
) {
    if (!state.isVisible) return
    val textColor = state.keyTextColor
    val configuration = LocalConfiguration.current
    val isLandscape = !state.isFloatingMode && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val menuItems = actions.map { action ->
        val toggle = (action.item as? ToolbarButtonItem.Builtin)?.button in ToolbarButton.TOGGLES
        MenuItem(label = action.item.stateLabel(action.active), action = action.onClick, id = action.item.id,
            currentState = if ((action.item as? ToolbarButtonItem.Builtin)?.button == ToolbarButton.FULL_SHAPE)
                action.item.stateLabel(action.active)
                else if (toggle) (if (action.active) "已开启" else "已关闭") else null,
            selected = if (toggle) action.active else null, toolbarItem = action.item, onLongClick = action.onLongClick)
    }
    BoxWithConstraints(modifier.fillMaxWidth().keyboardPanelBackground(state.backgroundColor)) {
        val cellWidth = (maxWidth - 32.dp - 36.dp) / 4
        // Xime 的两行正方形网格放不下时保留 CyIME 的自适应分页。
        val useXimeGrid = !isLandscape && cellWidth >= 64.dp &&
            maxHeight >= cellWidth * 2 + 12.dp + 24.dp &&
            androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f
        if (useXimeGrid && menuItems.isNotEmpty()) {
            val pages = menuItems.chunked(8)
            val pagerState = rememberPagerState(pageCount = { pages.size })
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceAround) {
                HorizontalPager(pagerState, modifier = Modifier.fillMaxWidth().testTag("menu-pages")) { page ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(2) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                repeat(4) { column ->
                                    val item = pages[page].getOrNull(row * 4 + column)
                                    val cell = Modifier.weight(1f).aspectRatio(1f)
                                    if (item == null) Spacer(cell) else MenuItemButton(
                                        item, state.keyBgColor, textColor,
                                        cell.testTag("menu-item:${item.id}"), ximeStyle = true)
                                }
                            }
                        }
                    }
                }
                if (pages.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        repeat(pages.size) { index ->
                            Box(Modifier.size(if (index == pagerState.currentPage) 8.dp else 6.dp)
                                .clip(CircleShape).background(if (index == pagerState.currentPage) textColor
                                else textColor.copy(alpha = 0.3f)))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            KeyboardPanelGrid(menuItems, isLandscape, textColor, "menu-pages",
                Modifier.fillMaxSize(), preferSinglePage = true) { item, cellModifier ->
                MenuItemButton(item, state.keyBgColor, textColor,
                    cellModifier.testTag("menu-item:${item.id}"), isLandscape)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MenuItemButton(
    item: MenuItem,
    bgColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
    ximeStyle: Boolean = false
) {
    val on = item.selected == true
    // 开启的开关用文字色作底、取反色作字，任何主题（含玻璃半透明键帽）下都能一眼看出。
    val fill = if (on) textColor else bgColor
    val content = if (on) (if (textColor.luminance() > .5f) Color.Black else Color.White) else textColor
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .visualMaterial(VisualStyles.current, 12.dp, level = com.kingzcheung.xime.ui.theme.MaterialLevel.RAISED)
            .combinedClickable(onLongClick = item.onLongClick) { item.action() }
            .semantics {
                item.currentState?.let { stateDescription = it }
                item.selected?.let { selected = it }
            }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val ximeIcon = if (ximeStyle) when ((item.toolbarItem as? ToolbarButtonItem.Builtin)?.button) {
            ToolbarButton.SCHEMA -> Icons.TwoTone.Keyboard
            ToolbarButton.FLOAT -> Icons.TwoTone.SettingsOverscan
            else -> null
        } else null
        if (ximeIcon != null) {
            Icon(imageVector = ximeIcon, contentDescription = item.label,
                tint = content.copy(alpha = 0.7f), modifier = Modifier.size(24.dp))
        } else if (item.toolbarItem != null) {
            ToolbarButtonIcon(item = item.toolbarItem, tint = content.copy(alpha = if (on) 1f else 0.7f),
                modifier = Modifier.size(if (ximeStyle) 24.dp else 26.dp), active = on)
        } else if (item.textIcon != null) {
            Text(
                text = item.textIcon,
                color = content.copy(alpha = 0.7f),
                fontSize = if (ximeStyle) 24.sp else 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        } else if (item.icon != null) {
            Icon(
                painter = item.icon,
                contentDescription = item.label,
                tint = content.copy(alpha = 0.7f),
                modifier = Modifier.size(if (ximeStyle) 24.dp else 26.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = item.label,
            color = content,
            fontSize = if (ximeStyle) 10.sp else 12.sp,
            lineHeight = if (ximeStyle) 12.sp else if (isLandscape) 13.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
