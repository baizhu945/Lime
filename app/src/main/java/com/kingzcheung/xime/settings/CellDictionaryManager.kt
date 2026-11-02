package com.kingzcheung.xime.settings

import android.content.Context
import com.kingzcheung.xime.rime.RimeEngine
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import com.kingzcheung.xime.plugin.core.lua.ws.localCleartextOnly
import okhttp3.Response

object CellDictionaryManager {
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).followSslRedirects(false).localCleartextOnly().build()
    }

    fun store(context: Context) = CellDictionaryStore(File(context.filesDir, "cell-dictionaries"))

    suspend fun download(offer: CellDictionaryOffer): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(offer.downloadUrl)
            .header("Referer", offer.sourceUrl).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        check(it.isSuccessful && it.request.url.isHttps) { "搜狗暂时无法下载（${it.code}），可从来源页下载后导入" }
                        val body = requireNotNull(it.body) { "下载内容为空" }
                        require(body.contentLength() <= ScelDictionary.MAX_BYTES) { "词库超过 16 MB" }
                        body.byteStream().use(ScelDictionary::readBounded)
                    }
                    if (continuation.isActive) continuation.resumeWith(Result.success(bytes))
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    /** Copy bundled dictionaries into the store before the applied set is prepared for Rime. */
    fun syncBuiltins(context: Context) {
        val store = store(context)
        CellDictionaryCatalog.builtins.forEach { builtin ->
            // The packager can leave a built-in dictionary out of an APK; skip it instead of failing deployment.
            val stream = try { context.assets.open(builtin.asset) } catch (_: java.io.FileNotFoundException) { return@forEach }
            val text = stream.bufferedReader().use { it.readText() }
            val english = try {
                context.assets.open(builtin.englishAsset).bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
            } catch (_: java.io.FileNotFoundException) { emptyList() }
            store.installBuiltin(builtin, text, english)
        }
    }

    fun prepare(context: Context, ids: Set<String> = store(context).read().applied,
        english: Boolean = store(context).read().appliedEnglish): List<CellDictionaryRime.Binding> =
        CellDictionaryRime.prepare(SchemaManager.getRimeDir(context), store(context),
            SchemaManager.discoverSchemas(context), ids, english)

    /** Native deployment can report success even when an optional pack failed to compile. */
    fun verify(context: Context, bindings: List<CellDictionaryRime.Binding>): Boolean {
        val enabled = SchemaManager.getEnabledSchemas(context).toSet()
        val engine = RimeEngine.getInstance()
        return bindings.filter { it.schemaId in enabled }.all {
            val table = File(SchemaManager.getRimeDir(context), "build/${it.pack}.table.bin")
            val source = File(SchemaManager.getRimeDir(context), "${it.pack}.dict.yaml")
            it.pack in engine.getSchemaPacks(it.schemaId) && table.isFile && table.length() > 64 &&
                table.lastModified() >= source.lastModified() &&
                table.inputStream().use { input ->
                    val head = ByteArray(12)
                    input.read(head) == head.size && head.toString(Charsets.US_ASCII).startsWith("Rime::Table/")
                }
        }
    }
}
