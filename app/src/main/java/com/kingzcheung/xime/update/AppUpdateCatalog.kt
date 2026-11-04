package com.kingzcheung.xime.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** GitHub 上一个已公开的 Android 正式版。 */
data class AppRelease(
    val version: String,
    val tag: String,
    val pageUrl: String,
    /** 这台设备该装的安装包（同版本类型、同架构）；发布里没有时为 null，只能去发布页。 */
    val asset: AppReleaseAsset?,
)

data class AppReleaseAsset(val name: String, val url: String, val sizeBytes: Long, val sha256: String?)

/**
 * 不需要自己的服务器：版本信息直接取 GitHub Releases。
 * 同一个仓库里还有 Windows 版（windows-日期）和模型（models）的发布，只认 x.y.z 标签；草稿和预发布不算。
 */
object AppUpdateCatalog {
    const val ENABLED = true
    /** 只看 Lime 自己的发布，避免把上游 CyIME 的安装包装到 Lime 上。 */
    const val RELEASES_API = "https://api.github.com/repos/baizhu945/Lime/releases?per_page=30"
    const val RELEASES_PAGE = "https://github.com/baizhu945/Lime/releases"

    // 2.00 这类两段版本号也是正式版；早期只认三段，导致 v2.00 被当成非 Android 发布。
    private val androidTag = Regex("""^v?(\d+(?:\.\d+)+)$""")
    // CyIME-1.14.156-arm64-v8a.apk / CyIME-1.14.156-full-arm64-v8a.apk（1.14.72 里的完整版文件名是 1.14.73）。
    private val apkName = Regex("""^Lime-\d+(?:\.\d+)*(-full)?-(.+)\.apk$""")
    private val json = Json { ignoreUnknownKeys = true }

    fun parseVersion(text: String): List<Int> =
        Regex("""^v?(\d+(?:\.\d+)*)""").find(text.trim())?.groupValues?.get(1)
            ?.split('.')?.map { it.toIntOrNull() ?: 0 } ?: emptyList()

    fun compareVersions(a: String, b: String): Int {
        val x = parseVersion(a)
        val y = parseVersion(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val diff = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean =
        parseVersion(candidate).isNotEmpty() && compareVersions(candidate, current) > 0

    /**
     * 发布列表里版本号最大的 Android 正式版。[full] 是否完整版（内置模型），[abis] 设备支持的架构，按偏好排序；
     * 安装包按这两项挑，找不到时退到 universal，再没有就只给发布页。
     */
    fun latest(releasesJson: String, full: Boolean, abis: List<String>): AppRelease? {
        if (!ENABLED) return null
        val releases = json.parseToJsonElement(releasesJson) as? JsonArray ?: return null
        return releases.mapNotNull { (it as? JsonObject)?.let { release -> parseRelease(release, full, abis) } }
            .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
    }

    private fun parseRelease(release: JsonObject, full: Boolean, abis: List<String>): AppRelease? {
        if (release.flag("draft") || release.flag("prerelease")) return null
        val tag = release.text("tag_name") ?: return null
        val version = androidTag.find(tag)?.groupValues?.get(1) ?: return null
        val page = release.text("html_url") ?: "$RELEASES_PAGE/tag/$tag"
        val assets = runCatching { release["assets"]?.jsonArray }.getOrNull().orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { asset ->
                val name = asset.text("name") ?: return@mapNotNull null
                val match = apkName.find(name) ?: return@mapNotNull null
                if ((match.groupValues[1] == "-full") != full) return@mapNotNull null
                val url = asset.text("browser_download_url") ?: return@mapNotNull null
                if (!downloadAllowed(url)) return@mapNotNull null
                val sha = asset.text("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
                if (sha.isNullOrBlank()) return@mapNotNull null
                // 发布包常带 -signed 后缀（Lime-2.00.1-arm64-v8a-signed.apk），架构只取前面的 ABI。
                match.groupValues[2].removeSuffix("-signed") to
                    AppReleaseAsset(name, url, asset.number("size") ?: 0L, sha)
            }
        val chosen = (abis + "universal").firstNotNullOfOrNull { abi -> assets.firstOrNull { it.first == abi }?.second }
        return AppRelease(version, tag, page, chosen)
    }

    private fun JsonObject.text(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.flag(key: String): Boolean =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false

    private fun JsonObject.number(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

    /** 下载目录里留下的安装包名对应的版本；用来清掉已经装上的旧包。 */
    /** 一键下载只接受 GitHub 的 HTTPS 地址。重定向到别的主机时同样拒绝。 */
    fun downloadAllowed(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https") return false
        val host = parsed.host.lowercase()
        return host == "github.com" || host == "api.github.com" || host.endsWith(".githubusercontent.com")
    }

    fun versionOfApk(fileName: String): String? =
        Regex("""^Lime-(\d+(?:\.\d+)*)""").find(fileName)?.groupValues?.get(1)
}
