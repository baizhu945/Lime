package com.kingzcheung.xime.clipboard.sync

import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray

data class WindowsPeer(val id: String, val name: String, val origin: String, val fingerprint: String, val token: String = "")

object WindowsPairingProtocol {
    fun received(peer: WindowsPeer, json: JSONObject): WindowsPeer {
        require(json.getInt("v") == 2 && json.getString("server_id") == peer.id)
        UUID.fromString(json.getString("device_id"))
        val token = json.getString("access_token")
        require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        val name = json.optString("server_name", peer.name).filterNot { it.isISOControl() }.take(64).ifBlank { peer.name }
        return peer.copy(name = name, token = token)
    }

    fun localOrigin(value: String): String {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.path.isNullOrEmpty() || uri.path == "/")
        val host = requireNotNull(uri.host).removePrefix("[").removeSuffix("]")
        require(host.matches(Regex("[0-9.]+")) || host.contains(':')) // No DNS/public endpoint substitution.
        val address = InetAddress.getByName(host)
        val bytes = address.address
        require(address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc))
        require(uri.port in 1..65535)
        return value.trimEnd('/')
    }
    fun fingerprint(value: String): String = value.also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    fun code(pin: String, proof: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest("cyime-nearby-v1\n${fingerprint(pin)}\n$proof".toByteArray(Charsets.UTF_8))
        return ((ByteBuffer.wrap(hash).int.toLong() and 0xffffffffL) % 100000000).toString().padStart(8, '0')
    }
    fun offer(uri: String): JSONObject {
        require(uri.length <= 8192) { "二维码内容过长" }
        val data = when {
            uri.startsWith("cyime://pair?v=2&c=") -> compactOffer(uri.substringAfter("&c="))
            uri.startsWith("cyime://pair?v=2&data=") -> {
                val bytes = Base64.getUrlDecoder().decode(uri.substringAfter("&data="))
                require(bytes.size <= 4096)
                JSONObject(bytes.toString(Charsets.UTF_8))
            }
            else -> error("不是 Lime 电脑配对二维码")
        }
        return data.also {
            require(data.getInt("v") == 2)
            UUID.fromString(data.getString("server_id")); UUID.fromString(data.getString("pairing_id"))
            fingerprint(data.getString("certificate_sha256"))
            require(data.getString("secret").matches(Regex("[A-Za-z0-9_-]{43}")))
            val origins = data.getJSONArray("endpoints")
            require(origins.length() in 1..8)
            repeat(origins.length()) { localOrigin(origins.getString(it)) }
        }
    }

    // Network byte order; the name comes from the authenticated pairing response.
    private fun compactOffer(encoded: String): JSONObject {
        require(encoded.matches(Regex("[A-Za-z0-9_-]+")))
        val bytes = Base64.getUrlDecoder().decode(encoded)
        require(bytes.size in 112..154)
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.get().toInt() == 2)
        val count = buffer.get().toInt() and 255
        require(count in 1..8 && bytes.size == 106 + count * 6)
        val origins = JSONArray()
        repeat(count) {
            val host = (1..4).joinToString(".") { (buffer.get().toInt() and 255).toString() }
            val port = buffer.short.toInt() and 65535
            origins.put(localOrigin("https://$host:$port"))
        }
        fun uuid() = UUID(buffer.long, buffer.long).toString()
        fun take(size: Int) = ByteArray(size).also { buffer.get(it) }
        return JSONObject().put("v", 2).put("endpoints", origins).put("name", "Lime 电脑")
            .put("server_id", uuid())
            .put("certificate_sha256", take(32).joinToString("") { "%02x".format(it.toInt() and 255) })
            .put("pairing_id", uuid()).put("secret", Base64.getUrlEncoder().withoutPadding().encodeToString(take(32)))
            .put("expires_at", buffer.long)
    }
    fun profile(json: JSONObject): ClipboardProfile {
        val text = json.getString("text")
        val profile = ClipboardProfile.fromText(text)
        require(json.getString("type") == "text" && !json.getBoolean("has_data") && json.isNull("data_name"))
        require(text.isNotBlank() && !text.contains('\u0000') && profile.size <= 65536)
        require(profile.hash == json.getString("hash") && profile.size == json.getLong("size"))
        return profile
    }
}
