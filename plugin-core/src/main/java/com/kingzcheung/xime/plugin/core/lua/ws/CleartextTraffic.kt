package com.kingzcheung.xime.plugin.core.lua.ws

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress

/**
 * 明文 HTTP/WS 只允许本机、局域网和链路本地地址。
 * Android 的网络安全配置无法按 IP 段放行，所以各 HTTP 客户端在这里再拦一层。
 */
object CleartextTraffic {
    const val BLOCKED = "明文连接只允许本机和局域网"

    val interceptor = Interceptor { chain ->
        val url = chain.request().url
        blockReason(url.scheme, url.host)?.let { throw IOException(it) }
        chain.proceed(chain.request())
    }

    fun blockReason(url: String): String? {
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "ws") return null
        val host = NetworkPolicy.extractHost(url) ?: return "无法解析 URL: $url"
        return blockReason(scheme, host)
    }

    fun blockReason(scheme: String, host: String): String? {
        if (!scheme.equals("http", true) && !scheme.equals("ws", true)) return null
        if (isLocalHost(host)) return null
        return BLOCKED
    }

    /** 只认 localhost 和字面 IP。域名即使解析到内网也不放行，避免被 DNS 重新指向。 */
    fun isLocalHost(host: String): Boolean {
        if (host.equals("localhost", true)) return true
        val literal = host.matches(Regex("^[0-9.]+$")) || host.contains(':')
        if (!literal) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return isLocalAddress(address)
    }

    fun isLocalAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress && !address.isLoopbackAddress) return false
        val bytes = address.address
        return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc)
    }
}

fun OkHttpClient.Builder.localCleartextOnly(): OkHttpClient.Builder =
    addNetworkInterceptor(CleartextTraffic.interceptor)
