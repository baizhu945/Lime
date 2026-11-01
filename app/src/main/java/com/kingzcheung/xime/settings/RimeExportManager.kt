package com.kingzcheung.xime.settings

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportMode(val label: String) {
    CONFIG_ONLY("仅配置文件"),
    FULL_BACKUP("完整备份")
}

data class ExportResult(
    val uri: Uri?,
    val fileName: String,
    val savedToDownloads: Boolean
)

/** 没能存进「下载」文件夹：备份包留在应用里的 [kept]，可以分享出去；不能当成导出成功。 */
class ExportSaveException(val kept: File) : Exception("没能保存到「下载」文件夹")

object RimeExportManager {

    private const val TAG = "RimeExportManager"

    fun shareSingleFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = inferMimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(null, uri)
        }
        val chooser = Intent.createChooser(intent, "分享文件")
        context.startActivity(chooser)
    }

    fun exportArchive(context: Context, mode: ExportMode): Result<ExportResult> {
        try {
            val (fileName, zipBytes) = buildArchive(context, mode).getOrElse { return Result.failure(it) }
            val tempZip = File(context.cacheDir, fileName)
            tempZip.writeBytes(zipBytes)
            val savedToDownloads = saveToDownloads(context, tempZip, fileName)
            settleTempArchive(tempZip, savedToDownloads, File(context.filesDir, "exports"))
                ?.let { return Result.failure(it) }

            return Result.success(ExportResult(resolveDownloadsUri(context, fileName), fileName, savedToDownloads = true))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "exportArchive failed", e)
            return Result.failure(e)
        }
    }

    /**
     * 生成备份包字节流（不落盘、不保存到 Downloads），供文件管理导出与本地备份（BackupManager）共用。
     *
     * 包内容（v1 格式，`_xime_backup/` 前缀外均为 rime 目录相对路径）：
     * - rime 目录文件（含 userdb / t9_digit.userdb 自造词，过滤规则见 [shouldInclude]）
     * - 设置项（两种模式都含；插件、插件配置与注册表不打包，配置里可能有密码）
     *
     * @return Pair(文件名, zip 字节流)
     */
    fun buildArchive(context: Context, mode: ExportMode): Result<Pair<String, ByteArray>> {
        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val fileName = "Lime配置-$dateStr.zip"
            val rimeDir = File(context.filesDir, "rime")
            if (!rimeDir.exists()) {
                return Result.failure(Exception("Rime 目录不存在"))
            }

            val bos = java.io.ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                rimeDir.walkTopDown().forEach { file ->
                    if (file.isDirectory) return@forEach
                    val relativePath = file.relativeTo(rimeDir).path.replace('\\', '/')
                    if (!shouldInclude(relativePath, mode)) return@forEach
                    zos.putNextEntry(ZipEntry(relativePath))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                BackupManager.collectMetaEntries(context).forEach { (entryName, bytes) ->
                    zos.putNextEntry(ZipEntry(entryName))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }

            val bytes = bos.toByteArray()
            if (bytes.isEmpty()) {
                return Result.failure(Exception("没有可导出的文件"))
            }
            return Result.success(fileName to bytes)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "buildArchive failed", e)
            return Result.failure(e)
        }
    }

    /** 存「下载」之后：成功就删掉临时包；失败把它挪进 [keepDir] 留着，返回要报给用户的失败。 */
    internal fun settleTempArchive(tempZip: File, saved: Boolean, keepDir: File): ExportSaveException? {
        if (saved) { tempZip.delete(); return null }
        keepDir.mkdirs()
        val kept = File(keepDir, tempZip.name)
        tempZip.copyTo(kept, overwrite = true)
        tempZip.delete()
        return ExportSaveException(kept)
    }

    private fun saveToDownloads(context: Context, zipFile: File, fileName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri == null) return false
                // 打不开或写到一半出错：删掉这个空条目，如实报失败
                val written = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        zipFile.inputStream().use { it.copyTo(output) }
                    } != null
                }.getOrElse { android.util.Log.e(TAG, "saveToDownloads write failed", it); false }
                if (!written) runCatching { context.contentResolver.delete(uri, null, null) }
                written
            } else {
                @Suppress("DEPRECATION")
                val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!destDir.exists()) destDir.mkdirs()
                val dest = File(destDir, fileName)
                zipFile.copyTo(dest, overwrite = true)
                true
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "saveToDownloads failed", e)
            false
        }
    }

    private fun resolveDownloadsUri(context: Context, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(fileName)
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    return Uri.withAppendedPath(collection, id.toString())
                }
            }
        }
        return null
    }

    private fun shouldInclude(relativePath: String, mode: ExportMode): Boolean {
        if (mode == ExportMode.FULL_BACKUP) return true
        return when {
            relativePath.startsWith("build/") -> false
            relativePath.startsWith("opencc/") -> true
            relativePath.endsWith(".bin") -> false
            relativePath.endsWith(".gram") -> false
            relativePath.endsWith(".db") -> false
            relativePath.endsWith(".db-wal") -> false
            relativePath.endsWith(".db-shm") -> false
            else -> true
        }
    }

    private fun inferMimeType(file: File): String {
        return when {
            file.name.endsWith(".yaml") -> "text/vnd.yaml"
            file.name.endsWith(".txt") -> "text/plain"
            file.name.endsWith(".bin") -> "application/octet-stream"
            file.name.endsWith(".gram") -> "application/octet-stream"
            file.name.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
