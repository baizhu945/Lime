package com.kingzcheung.xime.settings

/**
 * Audited Rime data packages use Rime components, not Xime's application API.
 * Keep their exact source/hash here: CyIME's release number cannot satisfy an
 * unrelated Xime 2.x requirement, and must not pretend to support every such package.
 */
internal object CuratedRimeSchemes {
    private val wubi86 = MarketScheme(
        id = "wubi86",
        name = "五笔86（Rime 官方）",
        author = "Rime / 王永民",
        description = "Rime 官方五笔86，支持拼音反查；仅下载本方案和词库，基础依赖已随 Lime 提供。",
        type = "remote",
        tags = listOf("五笔", "形码", "拼音"),
        dependencies = listOf("pinyin_simp", "symbols"),
        homepage = "https://github.com/rime/rime-wubi",
        license = "LGPL-3.0",
        currentVersion = "152a0d3",
        versions = listOf(SchemeVersion(
            version = "152a0d3",
            changelog = "固定 Rime 官方提交；仅下载五笔86方案和词库，不带其他方案或应用设置。",
            downloadUrls = listOf(
                DownloadItem(
                    url = "https://raw.githubusercontent.com/rime/rime-wubi/152a0d3f3efe40cae216d1e3b338242446848d07/wubi86.schema.yaml",
                    sha256 = "cdb5aac1a9aa071552d5fdffdfe5a6618b429b19358b8b1e003130e835d5a166",
                    size = "0.0015 MB",
                ),
                DownloadItem(
                    url = "https://raw.githubusercontent.com/rime/rime-wubi/152a0d3f3efe40cae216d1e3b338242446848d07/wubi86.dict.yaml",
                    sha256 = "f833d86b72341fe82e069a425b6625f29ef85f1bc0f34f6fb7975fe514888b5a",
                    size = "2.18 MB",
                ),
            ),
        )),
    )
    private val wubi98 = MarketScheme(
        id = "wubi98",
        name = "五笔98",
        author = "Rime / 王永民",
        description = "五笔98字形方案，支持繁体字；独立方案和词库。",
        type = "remote",
        tags = listOf("五笔", "形码", "繁体"),
        dependencies = listOf("pinyin_simp", "symbols"),
        homepage = "https://github.com/cz-archive/rime-wubi98",
        license = "GPL-3.0",
        currentVersion = "1.0.0",
        versions = listOf(SchemeVersion(
            version = "1.0.0",
            downloadUrls = listOf(DownloadItem(
                url = "https://github.com/cz-archive/rime-wubi98/archive/refs/tags/1.0.0.tar.gz",
                sha256 = "7d105524b81373c7fef0cbfd197fa3c236bcb4264b1935c5e384bacb10fece53",
                size = "0.88 MB",
            )),
        )),
    )

    /** The reviewed entries are visible first, even when the remote index is unavailable. */
    fun merge(remote: List<MarketScheme>): List<MarketScheme> =
        (listOf(wubi86, wubi98) + remote).distinctBy { it.id }

    /** Choose the advertised scheme, not a newly discovered mixed-input variant. */
    fun preferredSchema(packageId: String, availableSchemaIds: Set<String>): String? =
        packageId.takeIf { it in setOf(wubi86.id, wubi98.id) && it in availableSchemaIds }
}
