package com.kingzcheung.xime.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.SystemUpdate
import androidx.compose.material.icons.twotone.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.update.AppRelease
import com.kingzcheung.xime.update.AppUpdateState
import com.kingzcheung.xime.update.AppUpdater

private val editionName: String get() = if (BuildConfig.BUNDLED_MODELS) "完整版" else "轻量版"

/** 设置首页要提示的新版本：查到了、正在下或已下好但还没装。 */
internal fun pendingUpdate(state: AppUpdateState): AppRelease? = when (state) {
    is AppUpdateState.Available -> state.release
    is AppUpdateState.Downloading -> state.release
    is AppUpdateState.ReadyToInstall -> state.release
    else -> null
}

private fun updateSubtitle(state: AppUpdateState): String {
    val current = BuildConfig.VERSION_NAME
    return when (state) {
        AppUpdateState.Idle -> "现在是 $current，点一下查 GitHub 上的最新正式版"
        AppUpdateState.Checking -> "正在查 GitHub…"
        is AppUpdateState.UpToDate -> when (state.latest) {
            null -> "GitHub 上还没有正式版"
            else -> "已是最新：现在是 $current，GitHub 上最新正式版是 ${state.latest}"
        }
        is AppUpdateState.Available -> state.release.asset?.let { asset ->
            "${state.release.version} $editionName ${formatModelMegabytes(asset.sizeBytes)}，点一下下载"
        } ?: if (AppUpdater.privateBuild) "${state.release.version} 已发布；自用版不在这里一键更新（会换掉个人模型），点一下打开发布页"
        else "${state.release.version} 没有适合这台设备的安装包，点一下打开发布页"
        is AppUpdateState.Downloading -> {
            val total = if (state.total > 0) " / ${formatModelMegabytes(state.total)}" else ""
            "正在下载 ${state.release.version}：${formatModelMegabytes(state.downloaded)}$total，点一下暂停"
        }
        is AppUpdateState.ReadyToInstall -> "${state.release.version} 已下载，点一下安装；安装时键盘会关掉再打开"
        is AppUpdateState.Failed -> "${state.message}，点一下重试"
    }
}

private fun updateStatus(state: AppUpdateState): Pair<String, SettingsStatusTone>? = when (state) {
    AppUpdateState.Idle -> null
    AppUpdateState.Checking -> "检查中" to SettingsStatusTone.NEUTRAL
    is AppUpdateState.UpToDate -> "已是最新" to SettingsStatusTone.OK
    is AppUpdateState.Available -> "新版本" to SettingsStatusTone.OK
    is AppUpdateState.Downloading ->
        (if (state.total > 0) "${(state.downloaded * 100 / state.total).coerceIn(0, 100)}%" else "下载中") to SettingsStatusTone.NEUTRAL
    is AppUpdateState.ReadyToInstall -> "可安装" to SettingsStatusTone.OK
    is AppUpdateState.Failed -> "失败" to SettingsStatusTone.WARNING
}

/** 关于页「版本更新」：手动检查更新随时可点；自动检查默认开。 */
@Composable
internal fun AppUpdateSection() {
    if (!AppUpdater.supported) return
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val state by AppUpdater.state.collectAsState()
    var autoCheck by remember { mutableStateOf(AppUpdater.isAutoCheckEnabled(context)) }
    var meteredConfirm by remember { mutableStateOf<AppRelease?>(null) }

    fun startDownload(release: AppRelease) {
        if (release.asset == null) uriHandler.openUri(release.pageUrl)
        else if (AppUpdater.isMeteredNetwork(context)) meteredConfirm = release
        else AppUpdater.download(context, release)
    }

    SettingsSection(title = "版本更新") {
        val status = updateStatus(state)
        SettingsItem(
            icon = Icons.TwoTone.SystemUpdate,
            title = "检查更新",
            subtitle = updateSubtitle(state),
            status = status?.first,
            statusTone = status?.second ?: SettingsStatusTone.NEUTRAL,
            onClick = {
                when (val current = state) {
                    AppUpdateState.Checking -> Unit
                    is AppUpdateState.Available -> startDownload(current.release)
                    is AppUpdateState.Downloading -> AppUpdater.cancelDownload()
                    is AppUpdateState.ReadyToInstall -> if (!AppUpdater.install(context, current.file)) {
                        Toast.makeText(context, "请允许 Lime 安装应用，回来后再点一次「检查更新」", Toast.LENGTH_LONG).show()
                    }
                    is AppUpdateState.Failed -> current.release?.let { startDownload(it) } ?: AppUpdater.check(context)
                    else -> AppUpdater.check(context)
                }
            },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        SettingsToggleItem(
            icon = Icons.TwoTone.Update,
            title = "自动检查更新",
            subtitle = "打开设置时每天最多查一次 GitHub，有新版本在设置首页提示，不会自己下载",
            checked = autoCheck,
            onCheckedChange = { enabled ->
                autoCheck = enabled
                AppUpdater.setAutoCheckEnabled(context, enabled)
            },
        )
    }

    meteredConfirm?.let { release ->
        AlertDialog(
            onDismissRequest = { meteredConfirm = null },
            title = { Text("用移动数据下载？") },
            text = { Text("现在用的是移动数据，${release.version} $editionName 安装包约 ${formatModelMegabytes(release.asset?.sizeBytes ?: 0L)}。") },
            confirmButton = {
                TextButton(onClick = {
                    meteredConfirm = null
                    AppUpdater.download(context, release)
                }) { Text("下载") }
            },
            dismissButton = {
                TextButton(onClick = { meteredConfirm = null }) { Text("等 Wi-Fi") }
            }
        )
    }
}

/** 设置首页顶上的新版本提示，点了去关于页下载安装。 */
@Composable
internal fun AppUpdateHomeNotice(release: AppRelease, onOpen: () -> Unit) {
    SettingsSection(title = "版本更新") {
        SettingsItem(
            icon = Icons.TwoTone.SystemUpdate,
            title = "Lime ${release.version} 可以更新",
            subtitle = "现在是 ${BuildConfig.VERSION_NAME}，点这里到「关于」下载安装",
            status = "新版本",
            statusTone = SettingsStatusTone.OK,
            onClick = onOpen,
            showArrow = true,
        )
    }
}
