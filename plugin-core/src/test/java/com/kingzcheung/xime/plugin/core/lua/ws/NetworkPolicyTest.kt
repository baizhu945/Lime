package com.kingzcheung.xime.plugin.core.lua.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    @Test
    fun `authorization cannot be spoofed by userinfo or parser differences`() {
        for (url in listOf(
            "https://allowed.example:443@blocked.example/resource",
            "https://allowed.example%40blocked.example/",
            "wss://allowed.example@blocked.example/ws",
            "file://allowed.example/path",
        )) {
            assertTrue(url, NetworkPolicy.check(url, emptySet(), listOf("allowed.example"),
                setOf("allowed.example")) != null)
        }
        assertEquals("allowed.example", NetworkPolicy.extractHost("HTTPS://ALLOWED.EXAMPLE:443/a"))
        assertEquals("::1", NetworkPolicy.extractHost("ws://[::1]:8080/ws"))
        assertEquals("xn--bcher-kva.example", NetworkPolicy.extractHost("https://bücher.example/"))
    }

    private val trusted = setOf("dashscope.aliyuncs.com")

    @Test
    fun `cleartext stays on localhost and lan`() {
        assertNull(CleartextTraffic.blockReason("https://api.openai.com/v1/audio"))
        assertNull(CleartextTraffic.blockReason("http://127.0.0.1:18740/api/clipboard"))
        assertNull(CleartextTraffic.blockReason("http://192.168.1.50:18740/"))
        assertNull(CleartextTraffic.blockReason("http://localhost/status"))
        assertEquals(CleartextTraffic.BLOCKED, CleartextTraffic.blockReason("http://example.com/a"))
        assertEquals(CleartextTraffic.BLOCKED, NetworkPolicy.check(
            "http://evil.example/a", setOf("evil.example"), emptyList(), emptySet()))
    }

    @Test
    fun `extractHost parses various urls`() {
        assertEquals("dashscope.aliyuncs.com", NetworkPolicy.extractHost("wss://dashscope.aliyuncs.com/api-ws/v1/inference/"))
        assertEquals("asr.example.com", NetworkPolicy.extractHost("wss://asr.example.com:443/ws"))
        assertEquals("openai.com", NetworkPolicy.extractHost("wss://openai.com?query=1"))
        assertEquals("host", NetworkPolicy.extractHost("ws://host"))
        assertNull(NetworkPolicy.extractHost(""))
        assertNull(NetworkPolicy.extractHost("http://"))
    }

    @Test
    fun `extractHttpHost extracts domain only from http urls`() {
        assertEquals("dav.example.com", NetworkPolicy.extractHttpHost("https://dav.example.com:8080/dav/"))
        assertEquals("api.openai.com", NetworkPolicy.extractHttpHost("https://api.openai.com/v1/chat/completions"))
        assertEquals("192.168.1.50", NetworkPolicy.extractHttpHost("http://192.168.1.50:8080/dav/"))
        assertNull("非 http 协议返回 null", NetworkPolicy.extractHttpHost("wss://dav.example.com/ws"))
        assertNull("空白返回 null", NetworkPolicy.extractHttpHost("  "))
        assertNull("无协议返回 null", NetworkPolicy.extractHttpHost("dav.example.com/path"))
    }

    @Test
    fun `trusted host passes silently`() {
        assertNull(
            NetworkPolicy.check(
                "wss://dashscope.aliyuncs.com/ws",
                trusted, declaredHosts = emptyList(), authorizedHosts = emptySet()
            )
        )
    }

    @Test
    fun `declared and authorized host passes`() {
        assertNull(
            NetworkPolicy.check(
                "wss://asr.example.com/ws",
                trusted,
                declaredHosts = listOf("asr.example.com"),
                authorizedHosts = setOf("asr.example.com")
            )
        )
    }

    @Test
    fun `undeclared host rejected`() {
        val reason = NetworkPolicy.check(
            "wss://evil.example.com/ws",
            trusted, declaredHosts = emptyList(), authorizedHosts = emptySet()
        )
        assertTrue("未声明域名应拒绝", reason != null)
    }

    @Test
    fun `declared but unauthorized host rejected`() {
        val reason = NetworkPolicy.check(
            "wss://asr.example.com/ws",
            trusted,
            declaredHosts = listOf("asr.example.com"),
            authorizedHosts = emptySet()
        )
        assertTrue("已声明但未授权应拒绝", reason != null)
        assertTrue("拒绝原因应含授权提示", reason!!.contains("授权"))
    }

    @Test
    fun `configured custom host authorized passes`() {
        assertNull(
            NetworkPolicy.check(
                "https://my-llm.example.com/v1/chat/completions",
                trusted,
                declaredHosts = emptyList(),
                authorizedHosts = setOf("my-llm.example.com"),
                customHosts = setOf("my-llm.example.com")
            )
        )
    }

    @Test
    fun `configured custom host not authorized rejected`() {
        val reason = NetworkPolicy.check(
            "https://my-llm.example.com/v1/chat/completions",
            trusted,
            declaredHosts = emptyList(),
            authorizedHosts = emptySet(),
            customHosts = setOf("my-llm.example.com")
        )
        assertTrue("已配置但未授权应拒绝", reason != null)
        assertTrue("拒绝原因应含授权提示", reason!!.contains("授权"))
    }

    @Test
    fun `neither declared nor configured host rejected`() {
        val reason = NetworkPolicy.check(
            "https://evil.example.com/v1",
            trusted,
            declaredHosts = emptyList(),
            authorizedHosts = emptySet(),
            customHosts = setOf("my-llm.example.com")
        )
        assertTrue("未声明未配置应拒绝", reason != null)
    }
}
