package com.kingzcheung.xime.clipboard.sync

import android.content.ClipboardManager as AndroidClipboardManager
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.clipboard.VerificationCodeSmsReceiver
import com.kingzcheung.xime.plugin.SecureValueCipher
import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.settings.IncognitoState
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.security.auth.x500.X500Principal

/** A phone or tablet this device lets read and write its clipboard. */
data class HostedDevice(val deviceId: String, val clientId: String, val name: String, val tokenHash: String)

/**
 * Lets another CyIME phone or tablet pair with this device and sync clipboards directly,
 * without a computer. It speaks the same pairing v2 protocol as CyIME for Windows
 * (`/api/pair`, `/api/status`, `/api/clipboard`), so the other device uses its normal
 * computer-connection code and simply saves this device as one more peer.
 *
 * Off by default. While on, it listens on the local network only, pins a device-bound
 * TLS key from Android Keystore, and serves the clipboard only to paired devices with a
 * valid access token. Sensitive or incognito clipboard content is never served.
 */
object DeviceHost {
    private const val TAG = "DeviceHost"
    private const val KEY_ALIAS = "cyime_device_host_tls"
    private const val PREFERRED_PORT = 18741
    private const val OFFER_MS = 120_000L
    private const val MAX_DEVICES = 8
    private const val MAX_BODY = 72 * 1024
    private const val MAX_TEXT = 65536

    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    private val _enabled = MutableStateFlow(false)
    val enabled = _enabled.asStateFlow()
    private val _devices = MutableStateFlow<List<HostedDevice>>(emptyList())
    val devices = _devices.asStateFlow()
    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()
    /** The QR text of the pairing window that is currently open, or null. */
    private val _offer = MutableStateFlow<String?>(null)
    val offer = _offer.asStateFlow()

    private class Offer(val pairingId: String, val secret: String, val deadline: Long) { var failures = 0 }
    private var pending: Offer? = null
    private var server: ServerSocket? = null
    private var acceptor: Job? = null
    private var fingerprint = ""
    private var nsdListener: NsdManager.RegistrationListener? = null
    private val lock = Any()
    private val prefs get() = context.getSharedPreferences("device_host", Context.MODE_PRIVATE)

    fun initialize(value: Context) {
        if (::context.isInitialized) return
        context = value.applicationContext
        _devices.value = runCatching { loadDevices() }.getOrDefault(emptyList())
        _enabled.value = prefs.getBoolean("enabled", false)
        if (_enabled.value) start()
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        _enabled.value = on
        if (on) start() else stop()
    }

    private fun serverId(): String = prefs.getString("server_id", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("server_id", it).commit())
    }

    fun deviceName(): String {
        val name = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        return (name?.takeIf { it.isNotBlank() } ?: Build.MODEL).filterNot { it.isISOControl() }.take(64)
    }

    @Synchronized
    private fun start() {
        if (server != null) return
        try {
            val ssl = SSLContext.getInstance("TLS")
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) createKey()
            val certificate = keyStore.getCertificate(KEY_ALIAS)
            fingerprint = ClipboardProfile.sha256Hex(certificate.encoded)
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
            val chain = arrayOf(certificate as X509Certificate)
            // The Keystore key never leaves secure hardware; TLS signs through it by alias.
            val keys = object : X509ExtendedKeyManager() {
                override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = null
                override fun chooseClientAlias(type: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
                override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = arrayOf(KEY_ALIAS)
                override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = KEY_ALIAS
                override fun chooseEngineServerAlias(type: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = KEY_ALIAS
                override fun getCertificateChain(alias: String?) = chain
                override fun getPrivateKey(alias: String?) = privateKey
            }
            ssl.init(arrayOf(keys), null, random)
            val socket = ssl.serverSocketFactory.createServerSocket() as SSLServerSocket
            socket.reuseAddress = true
            // Keep the same port across restarts so saved peers find this device without discovery.
            val port = prefs.getInt("port", PREFERRED_PORT)
            try { socket.bind(InetSocketAddress(port), 8) } catch (_: Exception) { socket.bind(InetSocketAddress(0), 8) }
            if (socket.localPort != port) prefs.edit().putInt("port", socket.localPort).apply()
            server = socket
            acceptor = scope.launch { accept(socket) }
            register(socket.localPort)
            _status.value = "已开启，同一 Wi-Fi 的其他设备可连接本机"
        } catch (e: Exception) {
            Log.w(TAG, "host start failed (${e.javaClass.simpleName})")
            _status.value = "无法开启本机连接，请稍后重试"
            stop()
        }
    }

    @Synchronized
    fun stop() {
        acceptor?.cancel(); acceptor = null
        runCatching { server?.close() }; server = null
        nsdListener?.let { listener -> runCatching { context.getSystemService(NsdManager::class.java).unregisterService(listener) } }
        nsdListener = null
        closeOffer()
        if (!_enabled.value) _status.value = ""
    }

    private fun createKey() {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=CyIME Device"))
            .setCertificateSerialNumber(BigInteger(64, random).abs().add(BigInteger.ONE))
            .setCertificateNotBefore(Date(now - 86_400_000L))
            .setCertificateNotAfter(Date(now + 30L * 365 * 86_400_000L))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "CyIME-" + serverId().replace("-", "")
            serviceType = "_cyime-clip._tcp."
            this.port = port
            setAttribute("v", "2"); setAttribute("id", serverId()); setAttribute("kind", "android")
            setAttribute("name", deviceName())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "nsd register failed ($code)") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        nsdListener = listener
        runCatching { context.getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    /** Opens a two-minute pairing window and returns the QR text another device scans. */
    fun openOffer(): String? {
        if (server == null) start()
        val socket = server ?: return null
        val endpoints = JSONArray()
        localAddresses().take(8).forEach { endpoints.put("https://$it:${socket.localPort}") }
        if (endpoints.length() == 0) { _status.value = "未连接 Wi-Fi，无法显示二维码"; return null }
        val offer = Offer(UUID.randomUUID().toString(), token(), SystemClock.elapsedRealtime() + OFFER_MS)
        synchronized(lock) { pending = offer }
        val json = JSONObject().put("v", 2).put("server_id", serverId()).put("name", deviceName())
            .put("endpoints", endpoints).put("certificate_sha256", fingerprint)
            .put("pairing_id", offer.pairingId).put("secret", offer.secret)
            .put("expires_at", System.currentTimeMillis() / 1000 + OFFER_MS / 1000)
        val text = "cyime://pair?v=2&data=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        _offer.value = text
        return text
    }

    fun closeOffer() { synchronized(lock) { pending = null }; _offer.value = null }

    fun remove(device: HostedDevice) {
        synchronized(lock) {
            val updated = _devices.value.filterNot { it.deviceId == device.deviceId }
            saveDevices(updated); _devices.value = updated
        }
    }

    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
            .map { it.hostAddress.orEmpty() }.filter { it.isNotEmpty() }.distinct()
    }.getOrDefault(emptyList())

    private fun token(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })
    private fun hash(token: String) = ClipboardProfile.sha256Hex(token.toByteArray(Charsets.UTF_8))

    private fun loadDevices(): List<HostedDevice> {
        val stored = prefs.getString("devices", null) ?: return emptyList()
        val array = JSONArray(requireNotNull(SecureValueCipher.decrypt(stored)))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            HostedDevice(o.getString("device_id"), o.getString("client_id"), o.getString("name"), o.getString("token_hash"))
        }
    }

    private fun saveDevices(list: List<HostedDevice>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("device_id", it.deviceId).put("client_id", it.clientId)
            .put("name", it.name).put("token_hash", it.tokenHash)) }
        check(prefs.edit().putString("devices", SecureValueCipher.encrypt(array.toString())).commit()) { "保存失败" }
    }

    private suspend fun accept(socket: ServerSocket) {
        while (currentCoroutineContext().isActive) {
            val client = try { socket.accept() as SSLSocket } catch (_: Exception) { break }
            scope.launch {
                client.use {
                    runCatching {
                        it.soTimeout = 5_000
                        it.startHandshake()
                        serve(it.inputStream, it.outputStream)
                    }
                }
            }
        }
    }

    private class Response(val code: Int, val body: JSONObject? = null, val etag: String? = null)

    private fun serve(input: InputStream, output: OutputStream) {
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) { write(output, Response(400)); return }
        val headers = lines.drop(1).mapNotNull { line ->
            line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() }
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) { write(output, Response(413)); return }
        val body = ByteArray(length).also { buffer ->
            var read = 0
            while (read < length) { val n = input.read(buffer, read, length - read); if (n < 0) return; read += n }
        }
        val response = runCatching { route(parts[0], parts[1], headers, body) }.getOrElse { Response(400) }
        write(output, response)
    }

    private fun readHead(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var tail = 0
        while (bytes.size() < 8192) {
            val b = input.read()
            if (b < 0) return null
            bytes.write(b)
            tail = (tail shl 8 or b) and 0xffffffff.toInt()
            if (tail == 0x0d0a0d0a) return bytes.toString("UTF-8").removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun write(output: OutputStream, response: Response) {
        val payload = response.body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val reason = when (response.code) { 200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 304 -> "Not Modified"; else -> "Error" }
        val head = buildString {
            append("HTTP/1.1 ${response.code} $reason\r\n")
            append("Cache-Control: no-store\r\nConnection: close\r\n")
            if (payload.isNotEmpty()) append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${payload.size}\r\n")
            response.etag?.let { append("ETag: \"$it\"\r\n") }
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII)); output.write(payload); output.flush()
    }

    private fun route(method: String, path: String, headers: Map<String, String>, body: ByteArray): Response {
        if (method == "POST" && path == "/api/pair") return pair(body)
        val device = authorize(headers["authorization"]) ?: return Response(401)
        return when {
            method == "GET" && path == "/api/status" -> Response(200, JSONObject().put("v", 2).put("connected", true)
                .put("send_enabled", true).put("receive_enabled", true).put("device_id", device.deviceId))
            method == "GET" && path == "/api/clipboard" -> {
                val profile = readClipboard() ?: return Response(204)
                val match = headers["if-none-match"]?.trim('"')
                if (match == profile.hash) Response(304, etag = profile.hash) else Response(200, json(profile), profile.hash)
            }
            method == "PUT" && path == "/api/clipboard" -> {
                val incoming = WindowsPairingProtocol.profile(JSONObject(body.toString(Charsets.UTF_8)))
                if (incoming.size > MAX_TEXT) return Response(413)
                runBlocking(Dispatchers.Main) { ClipboardManager.getInstance(context).copyReceivedToSystemClipboard(incoming.text) }
                Response(200, json(incoming), incoming.hash)
            }
            else -> Response(404)
        }
    }

    private fun json(profile: ClipboardProfile) = JSONObject().put("type", "text").put("text", profile.text)
        .put("hash", profile.hash).put("size", profile.size).put("has_data", false)
        .put("data_name", JSONObject.NULL).put("source", "CyIME-Android")

    /** Only plain, non-sensitive text is served; incognito and password-manager copies stay local. */
    private fun readClipboard(): ClipboardProfile? = runBlocking(Dispatchers.Main) {
        if (IncognitoState.active || SettingsPreferences.isIncognito(context)) return@runBlocking null
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as AndroidClipboardManager
        val clip = try { manager.primaryClip } catch (_: SecurityException) { null } ?: return@runBlocking null
        val description = clip.description
        if (description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true ||
            description?.label == VerificationCodeSmsReceiver.CLIP_LABEL) return@runBlocking null
        val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank() || text.contains('\u0000')) return@runBlocking null
        ClipboardProfile.fromText(text).takeIf { it.size <= MAX_TEXT }
    }

    private fun authorize(header: String?): HostedDevice? {
        val token = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim() ?: return null
        if (!token.matches(Regex("[A-Za-z0-9_-]{43}"))) return null
        val digest = hash(token).toByteArray()
        return _devices.value.firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), digest) }
    }

    private fun pair(body: ByteArray): Response = synchronized(lock) {
        if (body.size > 4096) return Response(413)
        val offer = pending ?: return Response(410)
        if (SystemClock.elapsedRealtime() > offer.deadline) { closeOffer(); return Response(410) }
        val request = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull() ?: return Response(400)
        if (request.optInt("v") != 2 || request.optString("pairing_id") != offer.pairingId) return Response(400)
        if (!MessageDigest.isEqual(request.optString("secret").toByteArray(), offer.secret.toByteArray())) {
            if (++offer.failures >= 10) closeOffer()
            return Response(401)
        }
        val clientId = runCatching { UUID.fromString(request.getString("client_id")).toString() }.getOrNull() ?: return Response(400)
        val name = request.optString("client_name").filterNot { it.isISOControl() }.take(64).ifBlank { "Lime 设备" }
        val existing = _devices.value.firstOrNull { it.clientId == clientId }
        if (existing == null && _devices.value.size >= MAX_DEVICES) return Response(409)
        val token = token()
        // Re-pairing the same client rotates its token and keeps its device id.
        val device = HostedDevice(existing?.deviceId ?: UUID.randomUUID().toString(), clientId, name, hash(token))
        val updated = listOf(device) + _devices.value.filterNot { it.clientId == clientId }
        saveDevices(updated); _devices.value = updated
        closeOffer()
        _status.value = "已连接 $name"
        return Response(201, JSONObject().put("v", 2).put("server_id", serverId()).put("server_name", deviceName())
            .put("device_id", device.deviceId).put("access_token", token))
    }
}
