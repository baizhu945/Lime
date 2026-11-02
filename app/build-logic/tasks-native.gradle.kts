import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.PathSensitivity

// 使用官方 Maven 预编译 onnxruntime-android AAR（内置 CPU + NNAPI EP），
// 不再从源码自行编译（自行编译需 30-60 分钟且依赖网络/NDK 稳定性）。
val onnxVersion = extensions.getByType<VersionCatalogsExtension>()
    .named("libs").findVersion("onnxruntimeAndroid").get().requiredVersion
val onnxAarUrl = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${onnxVersion}/onnxruntime-android-${onnxVersion}.aar"
// 头文件不在 AAR 内，需从对应版本源码 raw 取（仅需 C API + provider factory 头）
val onnxHeadersBase = "https://raw.githubusercontent.com/microsoft/onnxruntime/v${onnxVersion}/include/onnxruntime"

// GitHub 加速镜像（按顺序尝试；不可用时请替换/删除）
val ghMirrors = listOf(
    "https://ghfast.top",
    "https://gh-proxy.com",
    "https://ghproxy.net",
)

// 纯 Java（HttpURLConnection）实现下载，跨平台且不依赖外部 curl/powershell：
//  - 不依赖构建环境 PATH 中是否有 curl（F-Droid 构建服务器曾因找不到 curl 而构建失败）
//  - 天然规避 Windows 下 curl/schannel TLS 握手失败（SSL error 35）问题
// 单个 URL 下载，支持断点续传与自动重试
fun downloadFile(url: String, target: File, workDir: File, desc: String): Boolean {
    println("Downloading $desc: $url")
    repeat(5) { attempt ->
        try {
            if (downloadOnce(url, target, desc)) {
                if (target.exists() && target.length() == 0L) {
                    target.delete()
                    return false
                }
                println("Downloaded ${target.name} (${target.length()} bytes)")
                return true
            }
        } catch (e: Exception) {
            System.err.println("Download error for $desc: ${e.message}")
        }
        // 失败后清空残留半成品，避免续传污染下一源
        target.delete()
        if (attempt < 4) Thread.sleep(3000)
    }
    return false
}

// 单次下载；返回 true 表示 HTTP 成功且内容已写入，false 表示 HTTP 层失败（如 404/403）
fun downloadOnce(url: String, target: File, desc: String): Boolean {
    val resumeFrom = if (target.exists()) target.length() else 0L
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    try {
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 600_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64)")
        if (resumeFrom > 0) {
            conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        val code = conn.responseCode
        if (code !in 200..299 && code != 206) {
            System.err.println("Download failed for $desc (HTTP $code)")
            return false
        }
        // 服务端支持 Range（206）才追加续传；否则（200）从头覆盖
        val append = code == 206 && resumeFrom > 0
        target.parentFile.mkdirs()
        conn.inputStream.use { input ->
            java.io.FileOutputStream(target, append).use { out ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                }
            }
        }
        return true
    } finally {
        conn.disconnect()
    }
}

// 按顺序尝试多个 URL（镜像优先，最后官方源），成功即停止，失败自动切换到下一个
fun downloadWithMirrors(urls: List<String>, target: File, workDir: File, desc: String): Boolean {
    for (url in urls) {
        // 已完整下载则跳过
        if (target.exists() && target.length() > 0) return true
        if (downloadFile(url, target, workDir, desc)) return true
        // 失败后清空残留半成品，避免续传污染下一源
        target.delete()
    }
    return false
}

fun githubUrls(url: String): List<String> = buildList {
    ghMirrors.forEach { add("${it.trimEnd('/')}/$url") }
    add(url) // 官方源兜底
}

val downloadOnnx by tasks.registering {
    val cppDir = file("src/main/jni/onnxruntime")
    val jniLibsDir = file("src/main/jniLibs")
    val nnapiMarker = file("$buildDir/onnxruntime-nnapi-marker")

    inputs.property("onnxVersion", onnxVersion)
    outputs.dir(cppDir)
    outputs.dir(jniLibsDir)
    outputs.file(nnapiMarker)

    doLast {
        val abis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

        // 每次任务执行都从干净暂存区重新获取，不能相信旧 marker 或旧文件。
        // 失败时移除 marker，使下一次构建必须重试。
        check(!nnapiMarker.exists() || nnapiMarker.delete()) { "Cannot remove ONNX marker: $nnapiMarker" }
        val staging = File(temporaryDir, "onnx-staging")
        check(!staging.exists() || staging.deleteRecursively()) { "Cannot clean ONNX staging: $staging" }
        check(staging.mkdirs()) { "Cannot create ONNX staging: $staging" }

        // 1. 下载官方 AAR（含 4 ABI 的 libonnxruntime.so，内置 CPU + NNAPI EP）
        val aar = File(staging, "onnxruntime-android-${onnxVersion}.aar")
        if (!downloadWithMirrors(listOf(onnxAarUrl), aar, buildDir, "onnxruntime-android AAR")) {
            throw GradleException("Failed to download ONNX Runtime Android AAR: $onnxAarUrl")
        }
        println("AAR downloaded: ${aar.length()} bytes")

        // 2. 解压 AAR（zip），提取 jni/<abi>/libonnxruntime.so
        val aarDir = File(staging, "aar")
        try {
            copy {
                from(zipTree(aar))
                into(aarDir)
            }
        } catch (e: Exception) {
            throw GradleException("Failed to extract AAR: ${e.message}")
        }

        for (abi in abis) {
            val src = File(aarDir, "jni/$abi/libonnxruntime.so")
            if (!src.isFile || src.length() == 0L) throw GradleException("AAR missing jni/$abi/libonnxruntime.so")
        }

        // 下载 core/session/ 目录下所需头文件（c_api.h 依赖 ep_c_api.h、
        // error_code.h 等，需完整覆盖以通过编译），统一提升到 include 顶层供 #include 直接命中。
        val dstHeaders = File(staging, "include")
        dstHeaders.mkdirs()
        val headersToFetch = listOf(
            "core/session/environment.h",
            "core/session/experimental_onnxruntime_cxx_api.h",
            "core/session/experimental_onnxruntime_cxx_inline.h",
            "core/session/onnxruntime_c_api.h",
            "core/session/onnxruntime_cxx_api.h",
            "core/session/onnxruntime_cxx_inline.h",
            "core/session/onnxruntime_env_config_keys.h",
            "core/session/onnxruntime_ep_c_api.h",
            "core/session/onnxruntime_ep_device_ep_metadata_keys.h",
            "core/session/onnxruntime_error_code.h",
            "core/session/onnxruntime_experimental_c_api.h",
            "core/session/onnxruntime_experimental_c_api.inc",
            "core/session/onnxruntime_experimental_cxx_api.h",
            "core/session/onnxruntime_float16.h",
            "core/session/onnxruntime_lite_custom_op.h",
            "core/session/onnxruntime_run_options_config_keys.h",
            "core/session/onnxruntime_session_options_config_keys.h",
        )
        for (rel in headersToFetch) {
            val target = java.io.File(dstHeaders, rel.substringAfterLast("/"))
            val url = "$onnxHeadersBase/$rel"
            if (downloadWithMirrors(githubUrls(url), target, buildDir, "onnxruntime header $rel")) {
                println("Header ok: ${target.name}")
            } else {
                throw GradleException("Failed to fetch ONNX Runtime header $rel: $url")
            }
        }

        // 全部下载并校验后才部署；任何复制失败都不会写入成功 marker。
        val deployedHeaders = File(cppDir, "include")
        check(!deployedHeaders.exists() || deployedHeaders.deleteRecursively()) { "Cannot clean ONNX headers: $deployedHeaders" }
        dstHeaders.copyRecursively(deployedHeaders, overwrite = true)
        for (abi in abis) {
            val src = File(aarDir, "jni/$abi/libonnxruntime.so")
            for (dir in listOf(File(cppDir, "lib/$abi"), File(jniLibsDir, abi))) {
                check(dir.isDirectory || dir.mkdirs()) { "Cannot create $dir" }
                src.copyTo(File(dir, "libonnxruntime.so"), overwrite = true)
            }
        }
        nnapiMarker.parentFile.mkdirs()
        nnapiMarker.writeText(onnxVersion)
        println("ONNX Runtime deployed to: ${abis.joinToString()}")
    }
}

val buildTrie by tasks.registering {
    val inputFile = file("src/main/assets/english.txt")
    val outputFile = file("src/main/assets/english_trie.bin")

    inputs.file(inputFile)
    outputs.file(outputFile)

    doLast {
        val words = inputFile.readLines()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        println("Loaded ${words.size} words from ${inputFile.name}")

        val nodes = mutableListOf<MutableMap<Char, Int>>()
        val nodeWords = mutableListOf<String?>()
        val nodeFreqs = mutableListOf<Int>()
        nodes.add(mutableMapOf())
        nodeWords.add(null)
        nodeFreqs.add(0)

        fun getOrCreateChild(parentIndex: Int, char: Char): Int {
            val existing = nodes[parentIndex][char]
            if (existing != null) return existing

            val newIndex = nodes.size
            nodes.add(mutableMapOf())
            nodeWords.add(null)
            nodeFreqs.add(0)
            nodes[parentIndex][char] = newIndex
            return newIndex
        }

        words.forEachIndexed { lineNum, word ->
            var current = 0
            for (char in word) {
                current = getOrCreateChild(current, char)
            }
            if (nodeWords[current] == null) {
                nodeWords[current] = word
                nodeFreqs[current] = lineNum + 1
            }
        }

        println("Built trie with ${nodes.size} nodes")

        val buffer = java.nio.ByteBuffer.allocate(512 * 1024)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)

        buffer.put("TRIE".toByteArray())
        buffer.put(1)
        buffer.putInt(nodes.size)

        for (i in nodes.indices) {
            val children = nodes[i]
            buffer.put(children.size.toByte())
            for ((char, childIndex) in children) {
                buffer.put(char.code.toByte())
                buffer.putInt(childIndex)
            }

            val word = nodeWords[i]
            buffer.put(if (word != null) 1 else 0)
            if (word != null) {
                val bytes = word.toByteArray(Charsets.UTF_8)
                buffer.put(bytes.size.toByte())
                buffer.put(bytes)
                buffer.putInt(nodeFreqs[i])
            }
        }

        val data = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(data)
        outputFile.writeBytes(data)

        println("Wrote ${data.size} bytes (${data.size / 1024}KB) to ${outputFile.name}")
    }
}

// ── librime native 构建输入保障（修"改了 C++ 代码 Run 不重编"）──
// 1) librime-t9 权威源码在顶层 jni/librime-t9，编译副本 plugins/librime-t9 由
//    Rime.cmake 在 configure 时 file(COPY) 同步。Gradle 感知不到权威源码变化、
//    不重新 configure 时副本就是旧的。改为构建期同步（Copy 增量：源变才拷，
//    拷后副本 mtime 更新，ninja 据此重编对应文件）。
val syncT9Plugin by tasks.registering {
    val authoritative = file("src/main/jni/librime-t9")
    val mirror = file("src/main/jni/librime/plugins/librime-t9")
    inputs.dir(authoritative)
    outputs.dir(mirror)
    doLast {
        copy {
            from(authoritative)
            into(mirror)
            exclude("build_test/**")  // 本地单测构建产物，不入镜像
        }
    }
}

// 2) 把 librime 核心/插件源码目录声明为 native 构建任务的显式输入：
//    AGP 对 CMake 任务的 up-to-date 判断不覆盖 submodule 源文件，
//    缺失该输入时 Android Studio 的 Run 会直接跳过 ninja，
//    改动（尤其 librime/src 核心改动）不会进 APK。
//    声明后：源码一变任务必然执行，ninja 按依赖增量重编，仍然很快。
tasks.configureEach {
    if (name.startsWith("buildCMake")) {
        dependsOn(syncT9Plugin)
        inputs.dir("src/main/jni/librime/src")
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .optional(true)
        inputs.dir("src/main/jni/librime-t9")
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .optional(true)
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadOnnx)
    dependsOn(buildTrie)
}