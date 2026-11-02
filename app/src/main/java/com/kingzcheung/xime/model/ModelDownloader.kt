package com.kingzcheung.xime.model

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.kingzcheung.xime.plugin.core.lua.ws.localCleartextOnly
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** GitHub's immutable release-asset endpoint otherwise returns JSON rather than model bytes. */
internal fun modelDownloadRequest(url: String): Request.Builder {
    val builder = Request.Builder().url(url)
    val parsed = builder.build().url
    if (parsed.host == "api.github.com" &&
        Regex("/repos/[^/]+/[^/]+/releases/assets/[0-9]+").matches(parsed.encodedPath)) {
        builder.header("Accept", "application/octet-stream")
    }
    return builder
}

/** 把下载/安装失败换成用户看得懂、知道下一步做什么的一句话。 */
internal fun modelFailureText(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    val text = chain.mapNotNull { it.message }.joinToString(" ")
    return when {
        chain.any { it is java.net.UnknownHostException || it is java.net.ConnectException ||
            it is java.net.SocketTimeoutException || it is javax.net.ssl.SSLException } ->
            "连不上下载服务器，请检查网络后点重试"
        "ENOSPC" in text || "No space left" in text -> "存储空间不够，清理一些空间后点重试"
        // 这条的 cause 是旧文件的校验失败，要先于“不完整”判断。
        "保留已有不同版本" in text -> "输入法目录里已有一份同名的自定义文件，为免覆盖没有安装；移走它后点重试"
        "校验失败" in text || "不完整" in text -> "下载的文件不完整，请点重试重新下载"
        else -> "下载失败：${chain.firstNotNullOfOrNull { it.message } ?: "未知原因"}。可点重试"
    }
}

object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val CONNECT_TIMEOUT = 30L
    private const val READ_TIMEOUT = 300L
    private const val MAX_RETRIES = 3

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .followRedirects(true)
        .localCleartextOnly()
        .build()

    suspend fun downloadModel(
        context: Context,
        modelInfo: ModelInfo,
        onProgress: (ModelDownloadState) -> Unit,
        version: ModelVersion? = null,
        install: (File, File) -> Boolean = { staging, destination -> installDownloadedModel(staging, destination); true },
    ) = withContext(Dispatchers.IO) {
        FileLogger.i(TAG, "Starting download: ${modelInfo.id}")

        val target = version ?: modelInfo.resolvedVersion()
        if (target == null) {
            onProgress(ModelDownloadState.Error("模型没有可用版本"))
            return@withContext
        }

        val storageDir = getStorageDir(context, modelInfo)
        // 暂存在数据目录里：缓存目录里写出的文件挪进模型目录后，系统仍按缓存统计它，模型会被算成几百 MB 的“缓存”。
        val staging = File(ModelStorage.getModelsRoot(context), ".download-${modelInfo.id}-${UUID.randomUUID()}").apply { mkdirs() }

        try {
            if (target.archiveUrl != null) {
                downloadAndExtractArchive(context, DownloadMirrors.candidates(context, target.archiveUrl, target.archiveMirrors),
                    staging, onProgress, target.sha256)
            } else {
                downloadFiles(context, target.files, staging, onProgress)
            }
            validateDownloadedModel(staging, target)
            currentCoroutineContext().ensureActive()
            if (!install(staging, storageDir)) {
                onProgress(ModelDownloadState.Idle)
                return@withContext
            }
            FileLogger.i(TAG, "Download complete: ${modelInfo.id}")
            onProgress(ModelDownloadState.Complete)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Download failed: ${modelInfo.id}: ${e.message}", e)
            onProgress(ModelDownloadState.Error(modelFailureText(e)))
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun getStorageDir(context: Context, modelInfo: ModelInfo): File {
        // 统一规则：所有模型一律存 filesDir/models/<id>/
        return ModelStorage.getModelDir(context, modelInfo.id)
    }

    private suspend fun downloadFiles(
        context: Context,
        files: List<ModelFile>,
        targetDir: File,
        onProgress: (ModelDownloadState) -> Unit
    ) {
        val totalFiles = files.size

        files.forEachIndexed { index, file ->
            FileLogger.i(TAG, "Downloading ${file.name} (${index + 1}/$totalFiles)")
            val target = modelDownloadFile(targetDir, file.name)
            val urls = DownloadMirrors.candidates(context, file.downloadUrl, file.mirrors)
            if (urls.isEmpty()) throw IOException("缺少下载地址：${file.name}")
            var lastError: Exception? = null
            for (url in urls) {
                currentCoroutineContext().ensureActive()
                try {
                    downloadSingleFile(url, target) { fileProgress ->
                        val overall = (index.toFloat() + fileProgress) / totalFiles
                        onProgress(ModelDownloadState.Downloading(overall, 0, -1))
                    }
                    // 换线路的前提是内容一致：每条线路下载完都按目录哈希校验。
                    verifyModelDigest(target, file.sha256)
                    lastError = null
                    break
                } catch (e: CancellationException) {
                    target.delete()
                    throw e
                } catch (e: Exception) {
                    FileLogger.w(TAG, "${file.name} 从 $url 下载失败，尝试下一条线路：${e.message}")
                    target.delete()
                    lastError = e
                }
            }
            lastError?.let { throw it }
        }
    }

    private suspend fun downloadSingleFile(
        url: String,
        targetFile: File,
        onProgress: (Float) -> Unit = {}
    ) {
        val request = modelDownloadRequest(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}")
        }

        val body = response.body ?: throw IOException("Response body is null")
        val totalBytes = body.contentLength()
        var downloadedBytes = 0L

        targetFile.parentFile?.mkdirs()
        body.byteStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        onProgress(downloadedBytes.toFloat() / totalBytes.toFloat())
                    }
                }
            }
        }

        if (totalBytes > 0 && downloadedBytes != totalBytes) {
            targetFile.delete()
            throw IOException("Download incomplete: $downloadedBytes/$totalBytes bytes")
        }

        FileLogger.i(TAG, "Downloaded ${targetFile.name}: $downloadedBytes bytes")
    }

    private suspend fun downloadArchive(
        url: String,
        tmpFile: File,
        onProgress: (ModelDownloadState) -> Unit
    ) {
        val request = modelDownloadRequest(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}")
        }

        val body = response.body ?: throw IOException("Response body is null")
        val totalBytes = body.contentLength()
        var downloadedBytes = 0L

        tmpFile.parentFile?.mkdirs()
        body.byteStream().use { input ->
            FileOutputStream(tmpFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        val progress = downloadedBytes.toFloat() / totalBytes.toFloat()
                        onProgress(ModelDownloadState.Downloading(progress, downloadedBytes, totalBytes))
                    }
                }
            }
        }

        if (totalBytes > 0 && downloadedBytes != totalBytes) {
            throw IOException("Download incomplete: $downloadedBytes/$totalBytes bytes")
        }
        if (downloadedBytes == 0L) {
            throw IOException("Downloaded file is empty")
        }

        FileLogger.i(TAG, "Archive downloaded: $downloadedBytes bytes")
    }

    private suspend fun downloadAndExtractArchive(
        context: Context,
        archiveUrls: List<String>,
        targetDir: File,
        onProgress: (ModelDownloadState) -> Unit,
        sha256: String = "",
    ) {
        if (archiveUrls.isEmpty()) throw IOException("缺少下载地址")
        val tmpFile = File(context.cacheDir, "${archiveUrls.first().hashCode()}.tar.bz2")

        // 每轮先试偏好线路，失败换下一条；重试次数不少于线路数，保证每条至少试一次。
        val attempts = maxOf(MAX_RETRIES, archiveUrls.size)
        for (attempt in 1..attempts) {
            val archiveUrl = archiveUrls[(attempt - 1) % archiveUrls.size]
            try {
                if (attempt > 1) {
                    tmpFile.delete()
                    onProgress(ModelDownloadState.Downloading(0f, 0, -1))
                }

                downloadArchive(archiveUrl, tmpFile, onProgress)
                verifyModelDigest(tmpFile, sha256)
                extractTarBz2(tmpFile, targetDir)
                tmpFile.delete()
                return
            } catch (e: CancellationException) {
                tmpFile.delete()
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "Attempt $attempt/$attempts ($archiveUrl) failed: ${e.message}")
                tmpFile.delete()
                if (attempt < attempts) {
                    val delayMs = 1000L * (1L shl (attempt - 1))
                    delay(delayMs)
                } else {
                    throw e
                }
            }
        }
    }

    internal fun extractTarBz2(archiveFile: File, targetDir: File) {
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis, 65536).use { bis ->
                BZip2CompressorInputStream(bis).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        var entry = tarIn.nextEntry
                        while (entry != null) {
                            val rawName = entry.name
                            val parts = rawName.split("/", limit = 2)
                            val entryName = if (parts.size > 1) parts[1] else rawName

                            if (entryName.isNotEmpty() && !entry.isDirectory) {
                                if (!entry.isFile) throw IOException("不支持的模型归档条目")
                                val outputFile = modelDownloadFile(targetDir, entryName)
                                outputFile.parentFile?.mkdirs()
                                FileOutputStream(outputFile).use { out ->
                                    val buffer = ByteArray(8192)
                                    var len: Int
                                    while (tarIn.read(buffer).also { len = it } != -1) {
                                        out.write(buffer, 0, len)
                                    }
                                }
                            }
                            entry = tarIn.nextEntry
                        }
                    }
                }
            }
        }
    }

    suspend fun getDownloadSize(modelInfo: ModelInfo, version: ModelVersion? = null): Long {
        val target = version ?: modelInfo.resolvedVersion()
        val url = target?.archiveUrl ?: target?.files?.firstOrNull()?.downloadUrl ?: return -1
        return try {
            val request = modelDownloadRequest(url).head().build()
            val response = client.newCall(request).execute()
            response.body?.contentLength() ?: -1
        } catch (e: Exception) {
            -1
        }
    }
}
