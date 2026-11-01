package com.kingzcheung.xime.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    /** [latest] 是 GitHub 上最新的正式版；没有任何正式版时为 null。 */
    data class UpToDate(val latest: String?) : AppUpdateState
    data class Available(val release: AppRelease) : AppUpdateState
    data class Downloading(val release: AppRelease, val downloaded: Long, val total: Long) : AppUpdateState
    data class ReadyToInstall(val release: AppRelease, val file: File) : AppUpdateState
    data class Failed(val message: String, val release: AppRelease?) : AppUpdateState
}

/**
 * 应用内检查更新：查 GitHub Releases，下载同版本类型（轻量版 / 完整版）的安装包，核对哈希和签名后交给系统安装程序。
 * Android 不允许普通应用静默安装，每次都由用户在系统界面里点「安装」。自动检查默认打开（只查不下载），手动关过的保持关。
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val PREFS = "app_update"
    private const val KEY_AUTO = "auto_check"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000
    /** 下载前至少留出的余量，免得把存储塞满。 */
    private const val SPACE_MARGIN = 64L * 1024 * 1024

    /** 老人版是单独打的包，换成公开包会丢掉老人版的界面，不给更新入口。 */
    val supported: Boolean get() = !BuildConfig.ELDER

    /** 自用版：只查不装，见 [fetchLatest]。 */
    val privateBuild: Boolean get() = BuildConfig.DEV_TOOLS

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAutoCheckEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply()
        if (enabled) checkIfDue(context)
    }

    /** 打开设置时调用：清掉已经装上的旧安装包；开了自动检查且离上次超过一天才去查。 */
    fun checkIfDue(context: Context) {
        val app = context.applicationContext
        scope.launch { cleanupInstalled(app) }
        if (!supported || !isAutoCheckEnabled(app)) return
        val last = prefs(app).getLong(KEY_LAST_CHECK, 0L)
        val elapsed = System.currentTimeMillis() - last
        if (elapsed in 0 until AUTO_INTERVAL_MS) return
        check(app, manual = false)
    }

    /** 自动检查失败时不打扰，保持原状态；手动检查把失败原因显示出来。 */
    fun check(context: Context, manual: Boolean = true) {
        if (!AppUpdateCatalog.ENABLED) {
            if (manual) _state.value = AppUpdateState.UpToDate(BuildConfig.VERSION_NAME)
            return
        }
        if (!supported || job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            if (manual) _state.value = AppUpdateState.Checking
            try {
                val latest = fetchLatest()
                prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                _state.value = when {
                    latest != null && AppUpdateCatalog.isNewer(latest.version, BuildConfig.VERSION_NAME) -> AppUpdateState.Available(latest)
                    else -> AppUpdateState.UpToDate(latest?.version)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "检查更新失败：${e.message}")
                if (manual) _state.value = AppUpdateState.Failed(failureText(e), null)
                else prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            }
        }
    }

    private fun fetchLatest(): AppRelease? {
        val request = Request.Builder().url(AppUpdateCatalog.RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "CyIME/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 403 || response.code == 429) throw IOException("rate-limited")
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val latest = AppUpdateCatalog.latest(response.body.string(), BuildConfig.BUNDLED_MODELS, Build.SUPPORTED_ABIS.toList())
            // 自用版（打包器「装到我的设备」）带个人九键模型、CyLM 和私有释义，公开包覆盖上去会把它们换掉：
            // 只提示有新版，不给一键下载安装，点了去发布页。
            return if (privateBuild) latest?.copy(asset = null) else latest
        }
    }

    fun isMeteredNetwork(context: Context): Boolean = try {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        capabilities != null && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: Exception) { false }

    // 缓存目录：不进备份，空间紧张时系统可以清掉，大不了重新下载。
    private fun updatesDir(context: Context) = File(context.cacheDir, "updates")

    /** 下载安装包；断了再点会接着上次的进度下。 */
    fun download(context: Context, release: AppRelease) {
        val asset = release.asset ?: return
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            val dir = updatesDir(app).apply { mkdirs() }
            // 只留这一个版本的文件。
            dir.listFiles()?.filter { it.name != asset.name && it.name != asset.name + ".part" }?.forEach { it.delete() }
            val target = File(dir, asset.name)
            val partial = File(dir, asset.name + ".part")
            try {
                if (!(target.isFile && target.length() == asset.sizeBytes && digestMatches(target, asset.sha256))) {
                    target.delete()
                    val needed = asset.sizeBytes - partial.length().coerceAtMost(asset.sizeBytes) + SPACE_MARGIN
                    if (dir.usableSpace in 0 until needed) throw IOException("ENOSPC")
                    if (asset.sizeBytes > 0 && partial.length() > asset.sizeBytes) partial.delete()
                    // 上次已下完但没校验完时不再请求（越界续传会得到 416）。
                    if (asset.sizeBytes <= 0 || partial.length() < asset.sizeBytes) fetchApk(release, asset, partial)
                    if (asset.sizeBytes > 0 && partial.length() != asset.sizeBytes) throw IOException("不完整")
                    if (!digestMatches(partial, asset.sha256)) {
                        partial.delete()
                        throw IOException("校验失败")
                    }
                    if (!partial.renameTo(target)) throw IOException("无法保存安装包")
                }
                verifyApk(app, target)?.let { problem ->
                    target.delete()
                    _state.value = AppUpdateState.Failed(problem, release)
                    return@launch
                }
                _state.value = AppUpdateState.ReadyToInstall(release, target)
            } catch (e: CancellationException) {
                _state.value = AppUpdateState.Available(release)
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "下载更新失败：${e.message}")
                _state.value = AppUpdateState.Failed(failureText(e), release)
            }
        }
    }

    fun cancelDownload() {
        val current = _state.value
        job?.cancel()
        if (current is AppUpdateState.Downloading) _state.value = AppUpdateState.Available(current.release)
    }

    private suspend fun fetchApk(release: AppRelease, asset: AppReleaseAsset, partial: File) {
        val offset = if (partial.isFile) partial.length() else 0L
        val builder = Request.Builder().url(asset.url).header("User-Agent", "CyIME/${BuildConfig.VERSION_NAME}")
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            // 服务器不认续传时从头下。
            val append = offset > 0 && response.code == 206
            var downloaded = if (append) offset else 0L
            val total = asset.sizeBytes.takeIf { it > 0 } ?: (response.body.contentLength().takeIf { it > 0 }?.plus(downloaded) ?: -1L)
            _state.value = AppUpdateState.Downloading(release, downloaded, total)
            var lastReport = 0L
            response.body.byteStream().use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 200) {
                            lastReport = now
                            _state.value = AppUpdateState.Downloading(release, downloaded, total)
                        }
                    }
                }
            }
        }
    }

    private fun digestMatches(file: File, sha256: String?): Boolean {
        if (sha256.isNullOrBlank()) return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256.lowercase()
    }

    /** 装之前先自己看一遍：是 CyIME、比现在的新、签名一致；否则系统安装程序只会报一句看不懂的冲突。 */
    private fun verifyApk(context: Context, apk: File): String? {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "下载的安装包打不开，请重新下载"
        if (archive.packageName != context.packageName) return "下载的文件不是 Lime 安装包"
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.longVersionCode <= installed.longVersionCode) return "下载的安装包不比现在装的新，不用更新"
        val newSigners = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val oldSigners = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        // 个别系统读不出安装包的签名时交给系统安装程序把关。
        if (!newSigners.isNullOrEmpty() && !oldSigners.isNullOrEmpty() && newSigners != oldSigners) {
            return "安装包的签名和现在装的不一样，不能覆盖安装"
        }
        return null
    }

    /**
     * 交给系统安装程序。还没允许「安装未知应用」时先打开那一页，返回 false；用户允许后回来再点一次。
     */
    fun install(context: Context, file: File): Boolean {
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            _state.value = AppUpdateState.Failed("找不到系统安装程序", (_state.value as? AppUpdateState.ReadyToInstall)?.release)
            false
        }
    }

    /** 装上新版后，下载目录里不比现在新的安装包都删掉。 */
    private fun cleanupInstalled(context: Context) {
        updatesDir(context).listFiles()?.forEach { file ->
            val version = AppUpdateCatalog.versionOfApk(file.name)
            if (version == null || AppUpdateCatalog.compareVersions(version, BuildConfig.VERSION_NAME) <= 0) file.delete()
        }
    }

    private fun failureText(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val text = chain.mapNotNull { it.message }.joinToString(" ")
        return when {
            chain.any { it is java.net.UnknownHostException || it is java.net.ConnectException ||
                it is java.net.SocketTimeoutException || it is javax.net.ssl.SSLException } ->
                "连不上 GitHub，请检查网络后重试，或点上面的「查看最新版本」在浏览器里下载"
            "rate-limited" in text -> "GitHub 暂时限制了查询次数，过一会儿再试"
            "ENOSPC" in text || "No space left" in text -> "存储空间不够，清理一些空间后重试"
            "校验失败" in text || "不完整" in text -> "下载的安装包不完整，请重试"
            else -> "失败：${chain.firstNotNullOfOrNull { it.message } ?: "未知原因"}"
        }
    }
}
