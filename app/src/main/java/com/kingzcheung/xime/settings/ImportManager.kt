package com.kingzcheung.xime.settings

import android.content.Context
import android.net.Uri
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一的导入入口。
 *
 * 设计原则：**路由永远按「文件名 / 内容类型」集中在这里决定**，而不是由调用方传入的
 * "方向"参数决定。这样无论是文件选择器还是分享，都汇聚到
 * 同一个路由核心，结构上不可能出现各入口路由逻辑不同步的问题。
 *
 * 调用方（各页面）只需要关心它从哪个入口触发导入，并对返回的 [ImportResult] 给出对应的
 * 成功文案与刷新动作即可。
 */
object ImportManager {

    sealed class ImportResult {
        /**
         * 方案 / 词典 / 背景图等，走 [SchemaManager.saveImportedFile] 的落地结果。
         * 方案压缩包导入后直接安装；和已有文件同名时不覆盖，[pendingPackageId] 留给页面问用户。
         * [message] 是给用户看的一句结果。
         */
        data class Content(
            val success: Boolean,
            val installedDirect: Boolean,
            val pendingPackageId: String? = null,
            val pendingName: String = "",
            val message: String = if (success) "导入成功" else "导入失败",
        ) : ImportResult()

        /** 插件（.xipk）安装结果。文件导入默认关闭，要用户在插件页打开。 */
        data class Plugin(val pluginInfo: PluginInfo?) : ImportResult()

        /** 插件还没装。确认后用 [uri] 再调用 [import]，并带 installPlugin。 */
        data class NeedsPluginConfirmation(val displayName: String, val uri: Uri) : ImportResult()

        /** 方案包里有 .lua。还没装进 rime；确认后用 [packageId] 或 [uri] 再装一次。 */
        data class NeedsScriptConfirmation(
            val displayName: String,
            val scripts: List<String>,
            val packageId: String? = null,
            val uri: Uri? = null,
        ) : ImportResult()

        data class Failed(val reason: String) : ImportResult()
        data class Unsupported(val fileName: String) : ImportResult()
    }

    data class ImportPreview(
        val displayName: String,
        val plugin: Boolean = false,
        val image: Boolean = false,
        val scripts: List<String> = emptyList(),
    ) {
        /** 分享进来的文件一律要确认；应用内选择的普通文件沿用原来的说明。带脚本或插件时必须再确认。 */
        fun confirmationText(fromShare: Boolean): String? = when {
            plugin -> "安装插件「$displayName」？\n安装后默认关闭，不会看到你的输入。在插件页打开之后，它才能收到上屏文字。"
            scripts.isNotEmpty() -> "「$displayName」包含输入法脚本：\n${scripts.take(8).joinToString("\n")}" +
                (if (scripts.size > 8) "\n…" else "") +
                "\n这些脚本在输入法进程里运行，可以读写文件。确认后才会安装。"
            fromShare -> "要导入「$displayName」吗？"
            else -> null
        }
    }

    /** 判断文件名是否为插件包（仅 .xipk）。 */
    fun isPluginFile(name: String): Boolean =
        name.endsWith(".xipk", ignoreCase = true)

    /** 只看文件名和归档目录，不写入 rime。 */
    suspend fun preview(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(context, uri) ?: return@withContext ImportResult.Failed("无法识别文件名")
        val name = SchemaManager.sanitizeDisplayName(displayName)
        if (SchemaManager.isProtectedImportName(name)) return@withContext ImportResult.Failed("不能覆盖系统配置 $name")
        val scripts = if (!isPluginFile(name) && SchemaManager.isArchive(name)) peekScripts(context, uri) else
            if (SchemaManager.isSchemaScript(name)) listOf(name) else emptyList()
        if (scripts is Peek && scripts.failed != null) return@withContext ImportResult.Failed(scripts.failed)
        ImportResult.NeedsScriptConfirmation(
            displayName = name,
            scripts = (scripts as? Peek)?.paths.orEmpty(),
            uri = uri,
        ).let { staged ->
            // preview 复用确认结构，但 packageId 为空表示还没落盘。调用方用 confirmationText 决定要不要问。
            staged
        }
    }

    fun previewOf(result: ImportResult.NeedsScriptConfirmation): ImportPreview = ImportPreview(
        displayName = result.displayName,
        plugin = isPluginFile(result.displayName),
        image = SchemaManager.isImage(result.displayName),
        scripts = result.scripts,
    )

    private class Peek(val paths: List<String>, val failed: String?)

    private fun peekScripts(context: Context, uri: Uri): Peek {
        val temp = java.io.File.createTempFile("import_peek_", ".bin", context.cacheDir)
        return try {
            val stream = openStream(context, uri) ?: return Peek(emptyList(), "无法读取文件")
            stream.use { input -> temp.outputStream().use { output -> input.copyLimited(output, 0) } }
            temp.rejectIfCompressedTooLarge()
            Peek(SchemaManager.scriptPaths(temp), null)
        } catch (e: Exception) {
            Peek(emptyList(), e.message ?: "无法读取方案包")
        } finally {
            temp.delete()
        }
    }

    /** 从 URI 导入（文件选择器、分享等）。按文件名自动路由。[allowScripts] 为真才把 .lua 装进 rime。 */
    suspend fun import(context: Context, uri: Uri, allowScripts: Boolean = false, installPlugin: Boolean = false): ImportResult = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(context, uri)
            ?: return@withContext ImportResult.Failed("无法识别文件名")
        val name = SchemaManager.sanitizeDisplayName(displayName)
        if (SchemaManager.isProtectedImportName(name)) return@withContext ImportResult.Failed("不能覆盖系统配置 $name")

        if (isPluginFile(name) && !installPlugin) {
            ImportResult.NeedsPluginConfirmation(name, uri)
        } else if (isPluginFile(name)) {
            when (val r = PluginManager.installerManager.installPluginFromUri(uri)) {
                is InstallerManager.InstallResult.Success -> ImportResult.Plugin(r.pluginInfo)
                is InstallerManager.InstallResult.Failure -> ImportResult.Failed(r.reason)
            }
        } else if (SchemaManager.isSchemaScript(name) && !allowScripts) {
            ImportResult.NeedsScriptConfirmation(name, listOf(name), uri = uri)
        } else {
            val stream = openStream(context, uri)
            if (stream == null) {
                ImportResult.Failed("无法读取文件")
            } else {
                val r = SchemaManager.saveImportedFile(context, name, stream, allowScripts = allowScripts)
                val packageId = r.packageId
                if (r.success && packageId != null) installArchive(context, packageId, archiveTitle(name), allowScripts)
                else if (!r.success && SchemaManager.isSchemaScript(name))
                    ImportResult.NeedsScriptConfirmation(name, listOf(name), uri = uri)
                else ImportResult.Content(r.success, r.installedDirect,
                    message = if (r.success && r.installedDirect) "已导入，点「应用方案更改」生效" else if (r.success) "导入成功" else "导入失败")
            }
        }
    }

    private fun openStream(context: Context, uri: Uri): java.io.InputStream? = when (uri.scheme) {
        "file" -> java.io.FileInputStream(uri.path!!)
        else -> context.contentResolver.openInputStream(uri)
    }

    /** 用户确认脚本后，安装已经放在 market 里的包。 */
    suspend fun installPrepared(context: Context, packageId: String, displayName: String, allowScripts: Boolean = true): ImportResult =
        installArchive(context, packageId, displayName, allowScripts)

    /** 导入的方案包直接安装（并打开其中的主方案）；有同名文件时留着等确认，装不上就删掉存下的包。 */
    private suspend fun installArchive(context: Context, packageId: String, title: String, allowScripts: Boolean): ImportResult {
        val install = SchemaManager.installPackageFromMarketDir(
            context, packageId, displayName = title, allowScripts = allowScripts)
        if (!install.success && install.scriptPaths.isNotEmpty()) {
            return ImportResult.NeedsScriptConfirmation(title, install.scriptPaths, packageId = packageId)
        }
        return finishArchive(context, install, packageId, title)
    }

    private fun finishArchive(context: Context, install: SchemaManager.InstallFromDirResult, packageId: String, title: String): ImportResult.Content {
        return when {
            install.success -> ImportResult.Content(true, installedDirect = true, message = buildString {
                append("已安装「$title」，点「应用方案更改」生效")
                if (install.parseFailures.isNotEmpty()) append("\n" + install.parseFailures.joinToString("\n"))
            })
            install.conflicts.isNotEmpty() -> ImportResult.Content(true, installedDirect = false,
                pendingPackageId = packageId, pendingName = title,
                message = "「$title」里有和已装方案同名的文件，确认后再安装")
            else -> {
                runCatching { SchemaManager.getMarketDir(context, packageId).deleteRecursively() }
                ImportResult.Content(false, installedDirect = false,
                    message = "导入失败：${install.failureReason ?: "安装失败"}")
            }
        }
    }

    /** 压缩包名去掉扩展名，作为方案包的显示名。 */
    internal fun archiveTitle(name: String): String =
        Regex("""\.(zip|tar\.gz|tgz)$""", RegexOption.IGNORE_CASE).replace(name, "").ifBlank { name }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment
    }
}
