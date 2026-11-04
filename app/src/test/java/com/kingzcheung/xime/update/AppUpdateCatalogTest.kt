package com.kingzcheung.xime.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCatalogTest {

    private fun asset(name: String, size: Long = 100, digest: String? = "sha256:ABCDEF") =
        """{"name":"$name","size":$size,"browser_download_url":"https://github.com/Cyletix/CyIME/releases/download/x/$name"${digest?.let { ",\"digest\":\"$it\"" } ?: ""}}"""

    private fun release(tag: String, vararg assets: String, draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"https://github.com/Cyletix/CyIME/releases/tag/$tag","assets":[${assets.joinToString(",")}]}"""

    // Same mix as the real list: a draft, a Windows release, the models release and older Android releases.
    private val releases = "[" + listOf(
        release("1.15.6", asset("Lime-1.15.6-arm64-v8a.apk"), asset("Lime-1.15.6-full-arm64-v8a.apk"), draft = true),
        release("windows-2026.10.30", asset("Lime-Windows-Setup-2026.10.30-258D9681.exe")),
        release("1.14.156", asset("Lime-1.14.156-arm64-v8a.apk", 121031364), asset("Lime-1.14.156-full-arm64-v8a.apk", 708584112)),
        release("models", asset("cyime_gloss-v12.tsv")),
        release("1.14.72", asset("Lime-1.14.72-arm64-v8a.apk"), asset("Lime-1.14.73-full-arm64-v8a.apk")),
        release("1.20.0", asset("Lime-1.20.0-arm64-v8a.apk"), prerelease = true),
    ).joinToString(",") + "]"

    @Test
    fun picksNewestPublishedAndroidRelease() {
        val latest = AppUpdateCatalog.latest(releases, full = false, abis = listOf("arm64-v8a", "armeabi-v7a"))!!
        assertEquals("1.14.156", latest.version)
        assertEquals("Lime-1.14.156-arm64-v8a.apk", latest.asset!!.name)
        assertEquals(121031364L, latest.asset!!.sizeBytes)
        assertEquals("abcdef", latest.asset!!.sha256)
        assertEquals("https://github.com/Cyletix/CyIME/releases/tag/1.14.156", latest.pageUrl)
    }

    @Test
    fun fullEditionGetsFullApk() {
        val latest = AppUpdateCatalog.latest(releases, full = true, abis = listOf("arm64-v8a"))!!
        assertEquals("Lime-1.14.156-full-arm64-v8a.apk", latest.asset!!.name)
    }

    @Test
    fun noMatchingAbiLeavesOnlyThePage() {
        val latest = AppUpdateCatalog.latest(releases, full = false, abis = listOf("x86_64"))!!
        assertEquals("1.14.156", latest.version)
        assertNull(latest.asset)
    }

    @Test
    fun universalIsTheFallback() {
        val list = "[" + release("1.3.12", asset("Lime-1.3.12-arm64-v8a.apk"), asset("Lime-1.3.12-universal.apk")) + "]"
        assertEquals("Lime-1.3.12-universal.apk", AppUpdateCatalog.latest(list, false, listOf("x86_64"))!!.asset!!.name)
    }

    @Test
    fun debugApksAreNotOffered() {
        val list = "[" + release("1.16.1", asset("Lime-1.16.1-debug-arm64-v8a.apk")) + "]"
        assertNull(AppUpdateCatalog.latest(list, false, listOf("arm64-v8a"))!!.asset)
    }

    @Test
    fun missingDigestIsNotInstalledFromTheApp() {
        val list = "[" + release("1.16.1", asset("Lime-1.16.1-arm64-v8a.apk", digest = null)) + "]"
        assertEquals("1.16.1", AppUpdateCatalog.latest(list, false, listOf("arm64-v8a"))!!.version)
        assertNull(AppUpdateCatalog.latest(list, false, listOf("arm64-v8a"))!!.asset)
    }

    @Test
    fun downloadStaysOnGithubHttps() {
        assertTrue(AppUpdateCatalog.downloadAllowed("https://github.com/baizhu945/Lime/releases/download/1/Lime.apk"))
        assertTrue(AppUpdateCatalog.downloadAllowed("https://objects.githubusercontent.com/a"))
        assertFalse(AppUpdateCatalog.downloadAllowed("http://github.com/a.apk"))
        assertFalse(AppUpdateCatalog.downloadAllowed("https://evil.example/Lime.apk"))
    }

    @Test
    fun noReleasesAtAll() {
        assertNull(AppUpdateCatalog.latest("[]", false, listOf("arm64-v8a")))
        assertNull(AppUpdateCatalog.latest("""{"message":"Not Found"}""", false, listOf("arm64-v8a")))
    }

    @Test
    fun twoPartVersionTagsAreAndroidReleases() {
        val list = "[" + release("v2.00", asset("Lime-2.00-arm64-v8a-signed.apk", 233707825)) + "]"
        val latest = AppUpdateCatalog.latest(list, false, listOf("arm64-v8a"))!!
        assertEquals("2.00", latest.version)
        assertEquals("Lime-2.00-arm64-v8a-signed.apk", latest.asset!!.name)
        assertTrue(AppUpdateCatalog.isNewer("2.00", "1.15.37"))
    }

    @Test
    fun signedApkNameMatchesTheArm64Asset() {
        val list = "[" + release("v2.00.01", asset("Lime-2.00.01-arm64-v8a-signed.apk")) + "]"
        val latest = AppUpdateCatalog.latest(list, false, listOf("arm64-v8a"))!!
        assertEquals("Lime-2.00.01-arm64-v8a-signed.apk", latest.asset!!.name)
    }

    @Test
    fun versionsCompareNumerically() {
        assertTrue(AppUpdateCatalog.isNewer("1.14.156", "1.14.95"))
        assertTrue(AppUpdateCatalog.isNewer("1.15.0", "1.14.156"))
        assertFalse(AppUpdateCatalog.isNewer("1.14.156", "1.15.10"))
        assertFalse(AppUpdateCatalog.isNewer("1.15.10", "1.15.10"))
        assertTrue(AppUpdateCatalog.isNewer("1.15.11", "1.15.10-nightly"))
        assertFalse(AppUpdateCatalog.isNewer("models", "1.0.0"))
    }

    @Test
    fun apkFileVersion() {
        assertEquals("1.14.156", AppUpdateCatalog.versionOfApk("Lime-1.14.156-full-arm64-v8a.apk"))
        assertEquals("1.14.156", AppUpdateCatalog.versionOfApk("Lime-1.14.156-arm64-v8a.apk.part"))
        assertNull(AppUpdateCatalog.versionOfApk("other.apk"))
    }
}
