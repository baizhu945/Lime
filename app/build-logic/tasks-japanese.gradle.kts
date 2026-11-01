import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

// Fixed, unmodified upstream dictionary data; APKs still contain the full offline files.
val japaneseDataRevision = "3e14573828668721ec1eabdc56546ddcca8277e2"
val japaneseDataHashes = mapOf(
    "jaroomaji.mozc.dict.yaml" to "5bfcd8c81e89d4c6d4dfed6468988f51f4d1ce1537c14fbb9110dcc880f8209a",
    "jaroomaji.jmdict.dict.yaml" to "29ff82d7c1a7dd4d96eb3e8a1bb9e262f0add6e56668bdcf1b90f3daed52b50d",
    "jaroomaji.kanjidic2.dict.yaml" to "6433cd8c4706f285e18e7b59b0e375e905a31f98001135ce8cb8d6a2c0afaf30",
    "jaroomaji.mozcemoji.dict.yaml" to "f48c7e91207a5a9058cd9ffac387a80437b2ac7b2ad871ad824fb11b72c96aca",
)
val japaneseAssetRoot = layout.buildDirectory.dir("generated/japanese-assets/rime_japanese")
fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
val prepareJapaneseDictionaries by tasks.registering {
    group = "build setup"
    description = "Fetch and verify pinned Japanese dictionaries for offline APK assets"
    inputs.property("revision", japaneseDataRevision)
    inputs.property("checksums", japaneseDataHashes)
    val targetRoot = japaneseAssetRoot.get().asFile
    // 本地缓存：联网不可用时用同一哈希的副本，避免每次都依赖网络。
    val cacheRoot = rootProject.file("build/japanese-dict-cache")
    inputs.files(japaneseDataHashes.keys.map { File(cacheRoot, it) })
    outputs.files(japaneseDataHashes.keys.map { File(targetRoot, it) })
    outputs.upToDateWhen {
        japaneseDataHashes.all { (name, hash) -> File(targetRoot, name).let { it.isFile && sha256(it) == hash } }
    }
    doLast {
        targetRoot.mkdirs()
        japaneseDataHashes.forEach { (name, hash) ->
            val target = File(targetRoot, name)
            if (target.isFile && sha256(target) == hash) return@forEach
            val cached = File(cacheRoot, name)
            if (cached.isFile && sha256(cached) == hash) {
                logger.lifecycle("Using cached pinned Japanese dictionary: $name")
                java.nio.file.Files.copy(cached.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                return@forEach
            }
            check(!gradle.startParameter.isOffline) { "Missing Japanese data: $name; run prepareJapaneseDictionaries once online." }
            val url = "https://raw.githubusercontent.com/lazyfoxchan/rime-jaroomaji/$japaneseDataRevision/$name"
            logger.lifecycle("Downloading pinned Japanese dictionary: $name")
            val pending = File(targetRoot, "$name.download")
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 600_000
            try {
                check(connection.responseCode == 200) { "Japanese dictionary HTTP ${connection.responseCode}: $name" }
                connection.inputStream.use { input -> pending.outputStream().use { input.copyTo(it) } }
                check(sha256(pending) == hash) { "Japanese dictionary SHA-256 mismatch: $name" }
                java.nio.file.Files.move(pending.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally {
                connection.disconnect()
                pending.delete()
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareJapaneseDictionaries) }
