package com.kingzcheung.xime.update

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
    /** Lime 没有公开的发布仓库：关闭在线更新，避免把上游 CyIME 的安装包装到 Lime 上。 */
    const val ENABLED = false
    const val RELEASES_API = ""
    const val RELEASES_PAGE = ""

    private val androidTag = Regex("""^v?(\d+\.\d+\.\d+)$""")
    // CyIME-1.14.156-arm64-v8a.apk / CyIME-1.14.156-full-arm64-v8a.apk（1.14.72 里的完整版文件名是 1.14.73）。
    private val apkName = Regex("""^CyIME-\d+(?:\.\d+)*(-full)?-(.+)\.apk$""")
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
                val sha = asset.text("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
                match.groupValues[2] to AppReleaseAsset(name, url, asset.number("size") ?: 0L, sha)
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
    fun versionOfApk(fileName: String): String? =
        Regex("""^CyIME-(\d+(?:\.\d+)*)""").find(fileName)?.groupValues?.get(1)
}
