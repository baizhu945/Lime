package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.net.Uri
import com.kingzcheung.xime.BuildConfig
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 本地备份与恢复：备份包只写到用户用系统文件选择器选定的位置，不经过插件或服务器。
 *
 * 备份包格式（v2，兼容 v1：`_xime_backup/` 前缀外的条目一律视为 rime 目录相对路径）：
 * - `_xime_backup/manifest.json`                    格式版本、应用版本、是否含个人数据
 * - `_xime_backup/settings.json`                    设置项（kime_settings，类型保留，剔除设备状态键）
 * - `_xime_backup/prefs/<name>.json`                其他设置文件（[SETTINGS_PREFS]）
 * - `_xime_backup/files/<path>`                     filesDir 下的设置/学习文件（[isFilesEntry]）
 * - 包根的 rime 文件：`*.custom.yaml`、自定义布局方案、themes/ 背景图；
 *   勾选个人数据时另含 userdb（自造词与调频）、自定义短语与个人词库
 * - v1 的插件条目（plugin_configs/、plugins.xml、plugins/）仍可恢复，本地备份不再生成
 *
 * 恢复走 [BackupRestoreTransaction]：先完整校验再落盘，失败回滚。
 */
object BackupManager {

    private const val TAG = "BackupManager"

    /** 备份包内元数据条目前缀（保留字，rime 目录不应有同名顶层文件）。 */
    const val META_PREFIX = "_xime_backup/"

    const val FORMAT_VERSION = 2

    /** 设置项 SharedPreferences 文件名（SettingsPreferences.PREFS_NAME，此处避免依赖私有常量）。 */
    private const val PREFS_NAME_SETTINGS = "kime_settings"

    /** 除 kime_settings 外随备份保存的设置文件（字幕显示、手写公式模式、模型下载源）。 */
    internal val SETTINGS_PREFS = listOf("caption_mode", "handwriting", "model_download_source")

    /** 只带其中几个键的设置文件：clipboard_board 里还存着固定条目（pins），备份只带卡片行数。 */
    internal val PARTIAL_PREFS = mapOf("clipboard_board" to setOf("card_max_lines"))

    /** 只留 [keys] 里的键（[prefsToJson] 格式）。 */
    internal fun keepKeys(json: String, keys: Set<String>): String {
        val root = org.json.JSONObject(json)
        root.keys().asSequence().toList().filter { it !in keys }.forEach { root.remove(it) }
        return root.toString()
    }

    /** 描述本机部署/安装状态或指向未备份插件的键：导出不写，导入不覆盖（另含 `device_` 前缀的本机硬件判定）。 */
    internal val DEVICE_STATE_KEYS = setOf(
        "deployment_done", "builtin_schemas_merged", "deployment_hash", "rime_assets_version",
        "setup_completed", "installed_market_ids", "stt_online_plugin_id",
        "clipboard_sync_enabled", "clipboard_sync_plugin_id", "backup_plugin_id"
    )

    private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024

    private val BACKUP_SUBDIRS = setOf("learning-profiles", "handwriting_templates", "handwriting_training")

    private val HANDWRITING_FILES = setOf(
        "handwriting_templates/latin.json", "handwriting_templates/kana_och.json", "handwriting_training/samples.jsonl")

    /** filesDir 下允许经 `_xime_backup/files/` 往返的文件。 */
    internal fun isFilesEntry(relative: String, personal: Boolean): Boolean = when {
        relative == "custom-keyboard-layouts.json" -> true
        relative == DoublePinyinSchemes.FILE -> true
        relative == "user_ngram_cache.json" -> personal
        relative.startsWith("learning-profiles/") && relative.endsWith(".json") &&
            relative.count { it == '/' } == 1 -> personal
        // 手写：边写边学的模板和训练页录的样本
        relative in HANDWRITING_FILES -> personal
        else -> false
    }

    /** rime 目录中属于设置（总是导出）的文件。 */
    internal fun isRimeSettingsFile(relative: String): Boolean =
        (!relative.contains('/') && relative.endsWith(".custom.yaml")) ||
            (!relative.contains('/') && relative.startsWith("custom_pinyin_") && relative.endsWith(".schema.yaml")) ||
            relative.startsWith("themes/")

    /** rime 目录中属于个人词库与学习数据的文件。 */
    internal fun isRimePersonalFile(relative: String): Boolean {
        val first = relative.substringBefore('/')
        // leveldb 的锁和运行日志不是数据，恢复时覆盖反而有害
        if (first.endsWith(".userdb")) return relative.substringAfterLast('/') !in setOf("LOCK", "LOG", "LOG.old")
        if (relative.contains('/')) return false
        return relative.endsWith(".userdb.txt") ||
            (relative.startsWith("custom_phrase") && relative.endsWith(".txt")) ||
            relative == EnglishWordMemory.FILE_NAME ||
            // 补充词库生成的 user_cyime_cells_* 由补充词库设置重建，不算个人词库
            (relative.startsWith("user_") && !relative.startsWith("user_cyime_cells_") && relative.endsWith(".dict.yaml"))
    }

    /** 列出本地备份要写入的文件：条目名 → 源文件（纯 JVM，便于单测）。 */
    internal fun localBackupFiles(filesDir: File, includePersonal: Boolean): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()
        val rimeDir = File(filesDir, "rime")
        rimeDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(rimeDir).path.replace('\\', '/')
            if (isRimeSettingsFile(relative) || (includePersonal && isRimePersonalFile(relative))) {
                result += relative to file
            }
        }
        filesDir.walkTopDown()
            .onEnter { it == filesDir || (it.parentFile == filesDir && it.name in BACKUP_SUBDIRS) }
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.relativeTo(filesDir).path.replace('\\', '/')
                if (isFilesEntry(relative, includePersonal)) result += (META_PREFIX + "files/" + relative) to file
            }
        return result.sortedBy { it.first }
    }

    internal fun stripDeviceState(json: String): String {
        val root = org.json.JSONObject(json)
        root.keys().asSequence().toList()
            .filter { it in DEVICE_STATE_KEYS || it.startsWith("device_") }
            .forEach { root.remove(it) }
        return root.toString()
    }

    /** 写本地备份包到 [output]，返回写入的文件数（不含设置 JSON）。 */
    fun writeLocalBackup(context: Context, output: OutputStream, includePersonal: Boolean): Int {
        val files = localBackupFiles(context.filesDir, includePersonal)
        ZipOutputStream(output).use { zos ->
            fun put(name: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            put(META_PREFIX + "manifest.json", org.json.JSONObject()
                .put("app", "CyIME")
                .put("format", FORMAT_VERSION)
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("createdAt", System.currentTimeMillis())
                .put("personalData", includePersonal)
                .toString(2).toByteArray(Charsets.UTF_8))
            val settings = prefsToJson(context.getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE))
            put(META_PREFIX + "settings.json", stripDeviceState(settings).toByteArray(Charsets.UTF_8))
            SETTINGS_PREFS.forEach { name ->
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                if (prefs.all.isNotEmpty()) put(META_PREFIX + "prefs/$name.json", prefsToJson(prefs).toByteArray(Charsets.UTF_8))
            }
            PARTIAL_PREFS.forEach { (name, keys) ->
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                if (prefs.all.keys.any { it in keys })
                    put(META_PREFIX + "prefs/$name.json", keepKeys(prefsToJson(prefs), keys).toByteArray(Charsets.UTF_8))
            }
            files.forEach { (name, file) ->
                zos.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        Log.i(TAG, "Local backup written: files=${files.size} personal=$includePersonal")
        return files.size
    }

    /** 导出到用户选择的文档 Uri。 */
    fun exportTo(context: Context, uri: Uri, includePersonal: Boolean): Result<Int> = runCatching {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
        output.use { writeLocalBackup(context, it, includePersonal) }
    }.onFailure { Log.e(TAG, "exportTo failed", it) }

    /** 读取用户选择的备份包；不是 CyIME 备份时拒绝，避免把任意 zip 解进 rime 目录。 */
    fun readBackup(context: Context, uri: Uri): Result<ByteArray> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                require(total <= MAX_ARCHIVE_BYTES) { "备份文件过大" }
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        } ?: error("无法读取所选文件")
        require(isCyimeBackup(bytes)) { "所选文件不是 Lime 备份" }
        bytes
    }

    internal fun isCyimeBackup(bytes: ByteArray): Boolean = runCatching {
        ZipInputStream(bytes.inputStream()).use { input ->
            generateSequence { input.nextEntry }.any { it.name == META_PREFIX + "settings.json" }
        }
    }.getOrDefault(false)

    /** 恢复后重新部署 Rime 并刷新自定义布局缓存，使设置立即生效。 */
    suspend fun importBackup(context: Context, bytes: ByteArray): Result<Unit> {
        restoreArchive(context, bytes).onFailure { return Result.failure(it) }
        runCatching { CustomKeyboardLayouts.load(context) }
            .onFailure { Log.w(TAG, "reload custom layouts failed", it) }
        // 自定义双拼只备份键位表，方案文件按它重新生成。
        runCatching { DoublePinyinSchemes.install(context, SchemaManager.getRimeDir(context)) }
            .onFailure { Log.w(TAG, "regenerate double pinyin schemes failed", it) }
        val deployed = runCatching { com.kingzcheung.xime.rime.RimeConfigHelper.redeploy(context) }.getOrDefault(false)
        return if (deployed) Result.success(Unit)
        else Result.failure(Exception("设置已恢复，但输入方案重新部署失败，请在方案设置中重新部署"))
    }

    /** 校验 zip 条目路径并解压覆盖（rime 文件 + META 元数据路由），Android 入口。 */
    fun restoreArchive(context: Context, bytes: ByteArray): Result<Unit> {
        val filesDir = context.filesDir
        val store = object : BackupPreferenceStore {
            override fun validate(json: ByteArray) { parsePrefs(json) }
            override fun snapshot(name: String) = prefsToJson(context.getSharedPreferences(name, Context.MODE_PRIVATE)).toByteArray()
            override fun restore(name: String, json: ByteArray, replace: Boolean) {
                // 回滚快照（replace）原样写回；备份内容不覆盖本机部署状态
                val data = when {
                    replace -> json
                    name == PREFS_NAME_SETTINGS -> stripDeviceState(json.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                    // 只认允许的键，备份包写不进固定条目
                    name in PARTIAL_PREFS -> keepKeys(json.toString(Charsets.UTF_8), PARTIAL_PREFS.getValue(name)).toByteArray(Charsets.UTF_8)
                    else -> json
                }
                restorePrefsJson(context.getSharedPreferences(name, Context.MODE_PRIVATE), data, replace)
            }
        }
        return com.kingzcheung.xime.rime.RimeEngine.getInstance().restoreUserData {
            BackupRestoreTransaction.restore(filesDir, store, bytes)
        }.onSuccess {
            Log.i(TAG, "Restore done -> ${filesDir.path}")
        }.onFailure { e ->
            Log.e(TAG, "restoreArchive failed", e)
        }
    }

    // ---- prefs 备份/恢复（JSON，类型保留：b/i/l/f/s/ss） ----

    /** 序列化 prefs 全部键值（插件配置值为密文字符串，原样存取，不在备份链路解密）。 */
    fun prefsToJson(prefs: SharedPreferences): String {
        val root = org.json.JSONObject()
        for ((key, value) in prefs.all) {
            val entry = org.json.JSONObject()
            when (value) {
                is Boolean -> entry.put("t", "b").put("v", value)
                is Int -> entry.put("t", "i").put("v", value)
                is Long -> entry.put("t", "l").put("v", value)
                is Float -> entry.put("t", "f").put("v", value.toDouble())
                is String -> entry.put("t", "s").put("v", value)
                is Set<*> -> entry.put("t", "ss").put("v", org.json.JSONArray(value))
                else -> continue
            }
            root.put(key, entry)
        }
        return root.toString()
    }

    /** 按 [prefsToJson] 的类型标注恢复 prefs（合并写，不删除备份中不存在的新增键）。 */
    internal fun parsePrefs(json: ByteArray): Map<String, Any> {
        val root = org.json.JSONObject(json.toString(Charsets.UTF_8))
        return root.keys().asSequence().associateWith { key ->
            val entry = root.getJSONObject(key)
            when (entry.getString("t")) {
                "b" -> entry.getBoolean("v")
                "i" -> entry.getInt("v")
                "l" -> entry.getLong("v")
                "f" -> entry.getDouble("v").toFloat()
                "s" -> entry.getString("v")
                "ss" -> entry.getJSONArray("v").let { arr ->
                    (0 until arr.length()).mapTo(mutableSetOf()) { arr.getString(it) }
                }
                else -> error("不支持的设置类型: $key")
            }
        }
    }

    fun restorePrefsJson(prefs: SharedPreferences, json: ByteArray, replace: Boolean = false) {
        val values = parsePrefs(json)
        val editor = prefs.edit()
        if (replace) editor.clear()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit()) { "写入设置失败" }
    }

    /** 文件管理导出的设置条目；插件配置可能含密码，插件及其注册表一律不打包。 */
    internal fun collectMetaEntries(context: Context): List<Pair<String, ByteArray>> {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        val settingsPrefs = context.getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE)
        entries += (META_PREFIX + "settings.json") to
            stripDeviceState(prefsToJson(settingsPrefs)).toByteArray(Charsets.UTF_8)
        return entries
    }
}
