package com.kingzcheung.xime.clipboard.sync

import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.plugin.core.api.ClipboardSyncPlugin
import com.kingzcheung.xime.plugin.core.model.PluginContext
import com.kingzcheung.xime.plugin.core.lua.ws.localCleartextOnly
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.cert.X509Certificate
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Trust is scoped to one exact leaf certificate, checked during the TLS handshake. */
class WindowsSecureTransport internal constructor(
    private val exchange: (WindowsPeer, String, JSONObject?, Boolean, String?) -> Pair<Int, JSONObject?>,
    private val peer: () -> WindowsPeer?,
) : ClipboardSyncPlugin {
    constructor(peer: () -> WindowsPeer?) : this(Companion::request, peer)
    private var acceptedHash: String? = null
    private var identity: String? = null
    var sendAllowed: Boolean = true
        private set
    var receiveAllowed: Boolean = true
        private set
    private var permissionIdentity: String? = null
    private fun permissions(p: WindowsPeer, body: JSONObject?) {
        if (body?.optInt("v") != 2) return
        permissionIdentity = p.id + p.fingerprint + p.token
        // Server directions are relative to the computer.
        sendAllowed = body.optBoolean("receive_enabled", true)
        receiveAllowed = body.optBoolean("send_enabled", true)
    }
    class SyncPaused : IllegalStateException("电脑已暂停此方向的剪贴板同步")
    class TransferFailure(val status: Int) : IllegalStateException(when (status) {
        401 -> "电脑已撤销配对，请重新连接"
        403 -> "电脑已暂停此方向的剪贴板同步"
        409 -> "电脑刚复制了新内容，稍后重试"
        413 -> "文字过长，最多同步 64 KiB"
        400, 415 -> "两端剪贴板格式不匹配，请更新 Lime"
        503 -> "电脑剪贴板正被占用，稍后重试"
        else -> "电脑暂时无法同步（$status）"
    })
    override fun onLoad(context: PluginContext) {}
    override fun onUnload() { acceptedHash = null }
    companion object {
        fun request(peer: WindowsPeer, path: String, body: JSONObject? = null, token: Boolean = false, etag: String? = null): Pair<Int, JSONObject?> {
            val origin = WindowsPairingProtocol.localOrigin(peer.origin)
            val pin = WindowsPairingProtocol.fingerprint(peer.fingerprint)
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("不接受客户端证书")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.isEmpty() || ClipboardProfile.sha256Hex(chain[0].encoded) != pin) throw CertificateException("电脑证书已变化，请重新配对")
                    chain[0].checkValidity()
                }
            }
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            // 电脑发放新凭据前要等手头那次剪贴板读写做完，发放即作废旧凭据；配对请求多等一会儿，
            // 免得电脑已经换了凭据、这边却因为超时没收到，旧凭据从此 401。
            val pairing = path.startsWith("/api/pair")
            val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).sslSocketFactory(tls.socketFactory, trust)
                .localCleartextOnly()
                .hostnameVerifier { _, session ->
                    runCatching { ClipboardProfile.sha256Hex(session.peerCertificates[0].encoded) == pin }.getOrDefault(false)
                }.followRedirects(false).followSslRedirects(false)
                .connectTimeout(4, TimeUnit.SECONDS).readTimeout(if (pairing) 20L else 4L, TimeUnit.SECONDS)
                .callTimeout(if (pairing) 25L else 6L, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(origin + path).header("Accept", "application/json")
            if (token) { require(peer.token.matches(Regex("[A-Za-z0-9_-]{43}"))); request.header("Authorization", "Bearer ${peer.token}") }
            if (etag != null) request.header("If-None-Match", "\"$etag\"")
            if (body != null) {
                val payload = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                if (path == "/api/clipboard") request.put(payload) else request.post(payload)
            }
            try {
                return client.newCall(request.build()).execute().use { response ->
                    val source = response.body?.source()
                    val buffer = okio.Buffer()
                    if (source != null) {
                        while (buffer.size <= 262144 && source.read(buffer, minOf(8192, 262145 - buffer.size)) != -1L) { }
                        require(buffer.size <= 262144) { "电脑响应过大" }
                    }
                    val text = buffer.readUtf8()
                    response.code to if (text.isBlank()) null else JSONObject(text)
                }
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }
    private fun current(): WindowsPeer = requireNotNull(peer()) { "请先连接电脑" }.also {
        val key = it.id + it.fingerprint + it.token
        if (identity != key) { identity = key; acceptedHash = null }
        if (permissionIdentity != key) { permissionIdentity = key; sendAllowed = true; receiveAllowed = true }
    }
    override suspend fun pull(): ClipboardProfile? {
        val p = current()
        val etag = acceptedHash
        val (status, json) = withContext(Dispatchers.IO) {
            exchange(p, "/api/clipboard", null, true, etag)
        }
        check(peer() == p) { "连接已更改" }
        if (status == 403 && json?.optBoolean("sync_paused") == true) { permissions(p, json); return null }
        if (status == 204) { acceptedHash = null; return null }
        if (status == 304) return null
        if (status != 200) throw TransferFailure(status)
        return WindowsPairingProtocol.profile(requireNotNull(json))
    }
    override suspend fun acknowledgePull(hash: String) { acceptedHash = hash }
    override suspend fun push(profile: ClipboardProfile): Boolean {
        val p = current()
        val json = JSONObject().put("type", "text").put("text", profile.text).put("hash", profile.hash)
            .put("size", profile.size).put("has_data", false).put("data_name", JSONObject.NULL).put("source", "CyIME-Android")
        WindowsPairingProtocol.profile(json)
        val (status, response) = withContext(Dispatchers.IO) {
            exchange(p, "/api/clipboard", json, true, null)
        }
        if (peer() == p && status == 403 && response?.optBoolean("sync_paused") == true) {
            permissions(p, response); throw SyncPaused()
        }
        check(peer() == p) { "连接已更改" }
        if (status != 200) throw TransferFailure(status)
        check(WindowsPairingProtocol.profile(requireNotNull(response)).hash == profile.hash) { "电脑未确认收到相同文字" }
        acceptedHash = profile.hash
        return true
    }
    override suspend fun testConnection(): String? = try {
        val p = requireNotNull(peer()) { "请先连接电脑" }
        val (status, body) = withContext(Dispatchers.IO) { exchange(p, "/api/status", null, true, null) }
        check(peer() == p) { "连接已更改" }
        check(status == 200 && body?.optInt("v") == 2) {
            if (status == 404) "电脑端需要更新，当前版本没有连接状态接口"
            else if (status == 401) "电脑已撤销配对，请重新连接"
            else "电脑暂时无法连接（$status）"
        }
        permissions(p, body)
        null
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { e.message ?: "无法连接电脑" }
}
