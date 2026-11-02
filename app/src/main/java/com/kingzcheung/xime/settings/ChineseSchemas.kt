package com.kingzcheung.xime.settings

import android.content.Context
import java.io.File
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/** 统一中文词库；标准 Rime ID 不变，现有市场版本和个人补丁优先。 */
object ChineseSchemas {
    val ids = listOf("t9_pinyin", "rime_ice", "pinyin_14jian", "double_pinyin_flypy", "double_pinyin_flypy_14jian") + BuiltinLetterLayouts.ids
    private const val ADDED = "cyime_chinese_defaults_v1"
    private const val DOUBLE_PINYIN_ADDED = "cyime_double_pinyin_defaults_v1"

    /** Only known, unedited predecessor bytes may migrate; personal/market ownership wins above. */
    internal fun isPreviousMergedBuiltin(path: String, bytes: ByteArray): Boolean {
        val expected = when (path) {
            "pinyin_14jian.schema.yaml" -> "8c4006fcc6ef623384aa0f79335d548f14a1603a36cc8668cea0ec3e76f6a8da"
            "double_pinyin_flypy_14jian.schema.yaml" -> "fbae7fee582beda5e9ce16c9da53f5e5505db96ef9a83460043aec3a4025effa"
            else -> return false
        }
        val normalized = bytes.toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray()
        return java.security.MessageDigest.getInstance("SHA-256").digest(normalized)
            .joinToString("") { "%02x".format(it) } == expected
    }

    private const val MAX_MIGRATION_FILE_BYTES = 256 * 1024L

    /** 大词库只补缺失文件；启动时不解压、更不读入内存比较已有词库。 */
    internal fun isSmallMigrationCandidate(relative: String, size: Long): Boolean =
        size <= MAX_MIGRATION_FILE_BYTES &&
            (relative.endsWith(".schema.yaml") || relative.endsWith(".lua") ||
                relative in setOf("symbols_v.yaml", "symbols_caps_v.yaml"))

    private fun readSmallAsset(context: Context, path: String): ByteArray? = runCatching {
        context.assets.open(path).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_MIGRATION_FILE_BYTES) return@use null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }.getOrNull()

    @Synchronized
    fun installAssets(context: Context, target: File) {
        target.mkdirs()
        val registryFile = SchemaManifestManager.getRegistryFile(context)
        val registry = if (registryFile.isFile) runCatching { JSONObject(registryFile.readText()) }.getOrNull() else null
        fun isMarketOwned(relative: String): Boolean {
            // 已登记的市场/个人文件优先，即使它碰巧与旧内置版本内容一致也不替换。
            val owners = registry?.optJSONObject("files")?.optJSONObject(relative)?.optJSONArray("claimedBy") ?: return false
            return (0 until owners.length()).any { owners.optString(it) != "builtin" }
        }
        fun install(directory: String, relative: String = "") {
            val path = if (relative.isEmpty()) directory else "$directory/$relative"
            val children = context.assets.list(path).orEmpty()
            if (children.isNotEmpty()) {
                children.forEach { install(directory, if (relative.isEmpty()) it else "$relative/$it") }
                return
            }
            // 许可随 APK 分发，不作为 Rime 配置安装。
            if (relative.endsWith(".md") || relative == "LICENSE") return
            if (com.kingzcheung.xime.model.CandidateModelAssets.isDisabledAsset(context, relative)) return
            val destination = File(target, relative)
            var replacement: ByteArray? = null
            if (destination.exists()) {
                if (!destination.isFile || !isSmallMigrationCandidate(relative, destination.length()) || isMarketOwned(relative)) return
                val bytes = readSmallAsset(context, path) ?: return
                val current = destination.readBytes()
                if (current.contentEquals(bytes)) return
                val oldBuiltin = readSmallAsset(context, "rime/$relative")
                val old14 = if (relative == "pinyin_14jian.schema.yaml")
                    bytes.toString(Charsets.UTF_8).replace("dictionary: rime_ice", "dictionary: pinyin_simp").toByteArray() else null
                // 仅迁移字节完全匹配的旧内置小配置，不以方案名判断用户文件。
                if (oldBuiltin?.contentEquals(current) != true && old14?.contentEquals(current) != true &&
                    !isPreviousMergedBuiltin(relative, current)) return
                replacement = bytes
            }
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, "${destination.name}.installing")
            try {
                temporary.outputStream().use { output ->
                    val bytes = replacement
                    if (bytes != null) output.write(bytes)
                    else context.assets.open(path).use { input -> input.copyTo(output) }
                }
                java.nio.file.Files.move(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                if (replacement != null) SchemaManifestManager.recordBuiltinReplacement(context, relative, destination)
            } finally { temporary.delete() }
        }
        install("rime_ice")
        install("rime_chinese")
    }

    /** Offer the experimental layouts without enabling them or rewriting user schemas. */
    @Synchronized
    fun installOptionalLayoutSchemas(context: Context, target: File) {
        target.mkdirs()
        for (id in BuiltinLetterLayouts.ids) {
            val destination = File(target, "$id.schema.yaml")
            if (destination.isFile) {
                val old = destination.readText()
                val updated = EnglishCandidatePolicy.install(old)
                if (updated == old) continue
                val backup = File(context.filesDir, "rime-upgrade-backups/english-policy/${destination.name}")
                if (!backup.exists()) { backup.parentFile!!.mkdirs(); backup.writeText(old) }
                val pending = File(target, "$id.schema.yaml.installing")
                try {
                    pending.writeText(updated)
                    java.nio.file.Files.move(pending.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } finally { pending.delete() }
                continue
            }
            if (destination.exists()) continue
            val pending = File(target, "$id.schema.yaml.installing")
            try {
                context.assets.open("rime_ice/$id.schema.yaml").use { input ->
                    pending.outputStream().use { input.copyTo(it) }
                }
                java.nio.file.Files.move(pending.toPath(), destination.toPath())
            } finally { pending.delete() }
        }
    }

    /** 一次整理旧默认和重复模式，之后用户的启停选择继续有效。 */
    @Synchronized
    fun addOnFirstUpgrade(context: Context, enabled: List<String>): List<String> =
        addDoublePinyinDefaults(context, addChineseDefaults(context, enabled))

    /** 内置双拼新装和升级都补进方案栏一次；之后在双拼方案页隐藏仍有效。 */
    private fun addDoublePinyinDefaults(context: Context, enabled: List<String>): List<String> {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        if (prefs.getBoolean(DOUBLE_PINYIN_ADDED, false) || !prefs.getBoolean(ADDED, false)) return enabled
        val available = SchemaManager.getRimeDir(context).listFiles().orEmpty()
            .filter { it.name.endsWith(".schema.yaml") }.map { it.name.removeSuffix(".schema.yaml") }.toSet()
        val updated = CyimeInputDefaults.withDoublePinyin(enabled, available) ?: return enabled
        if (updated != enabled) SchemaManager.setEnabledSchemas(context, updated)
        prefs.edit().putBoolean(DOUBLE_PINYIN_ADDED, true).apply()
        return updated
    }

    private fun addChineseDefaults(context: Context, enabled: List<String>): List<String> {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val available = SchemaManager.getRimeDir(context).listFiles().orEmpty()
            .filter { it.name.endsWith(".schema.yaml") }.map { it.name.removeSuffix(".schema.yaml") }.toSet()
        val normalized = CyimeInputDefaults.canonicalIds(enabled, available)
        // An early UI read must not consume the migration before bundled files are installed.
        if (!available.containsAll(CyimeInputDefaults.recommended)) return normalized
        if (prefs.getBoolean(ADDED, false)) {
            // Persist canonical IDs too: a stale custom.yaml patch otherwise overrides default.yaml at deploy time.
            val repaired = CyimeInputDefaults.withCompanions(
                normalized.ifEmpty { CyimeInputDefaults.recommended.filter { it in available } }, available)
            if (repaired != enabled) SchemaManager.setEnabledSchemas(context, repaired)
            return repaired
        }
        val updated = CyimeInputDefaults.withCompanions((normalized.filterNot { it in CyimeInputDefaults.legacyDefaults } +
            CyimeInputDefaults.firstDefaults(isFreshInstall(context)).filter { it in available }).distinct(), available)
        if (updated != enabled) SchemaManager.setEnabledSchemas(context, updated)
        val current = SettingsPreferences.getCurrentSchema(context)
        val selected = CyimeInputDefaults.canonicalIds(listOf(current), available).firstOrNull()
        if (selected == null || selected !in updated) SettingsPreferences.setCurrentSchema(context, updated.firstOrNull() ?: "t9_pinyin")
        else if (selected != current) SettingsPreferences.setCurrentSchema(context, selected)
        prefs.edit().putBoolean(ADDED, true).apply()
        return updated
    }

    /** 装上以后没更新过：首次安装时间等于最后更新时间。升级（覆盖安装）的人这两个时间不同。 */
    private fun isFreshInstall(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(false)

    fun displayName(id: String, original: String): String = CustomKeyboardLayouts.find(id)?.name ?: when (id) {
        "rime_ice" -> "中文26键"
        QwjrtkLayout.ID -> "QWJRTK（双拇指）"
        Cyletix10Layout.ID -> "Cyletix10（实验）"
        "pinyin_simp" -> "旧版简体拼音"
        "t9_pinyin", "t9" -> "中文九键"
        "pinyin_14jian" -> "中文14键"
        else -> original
    }

    /**
     * 切换方案当帧就要显示的空格键名称。已知列表里的名字优先；没有时用内置名，
     * 避免空格先留着上一种语言的名字（英文切日语时闪一下「中文26键」）。
     */
    fun immediateSchemaName(schemaId: String, knownName: String? = null): String {
        val named = displayName(schemaId, knownName.orEmpty())
        if (named.isNotBlank()) return named
        val profile = InputProfiles.describe(schemaId)
        if (profile.language == InputLanguage.UNSPECIFIED || profile.language == InputLanguage.ENGLISH) return schemaId
        return profile.language.displayName + profile.layout.displayName
    }
}
