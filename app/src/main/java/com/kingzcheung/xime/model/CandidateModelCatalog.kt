package com.kingzcheung.xime.model

/** Optional scoring resources; ordinary Chinese dictionaries do not depend on these downloads. */
object CandidateModelCatalog {
    const val GRAMMAR = "t9-grammar"
    const val SENTENCE = "t9-sentence"
    const val NGRAM = "t9-ngram"
    const val NGRAM_SMALL = "t9-ngram-small"
    private const val SOURCE = "https://raw.githubusercontent.com/Cyletix/CyIME/6cc85b1c08a90a68eca0bd8d018fdb33e7e3d791/app/src/main/assets/rime_chinese/"
    val models = listOf(
        // 引擎按方案的 t9/ngram_model 在 rime 用户目录找 t9_ngram.lm；文件名必须保持 .lm。
        // 完整版与精简版写同一个文件名，装哪个九键就用哪个；紧凑格式（c3）内容与原 370 MB 文件相同，旧文件仍可读。
        ModelInfo(NGRAM, "九键词组模型（完整，203 MB）", "让九键按整句常用说法组词和排序，安装后重新启动输入法生效；不下载时九键使用旧算法。上游 CyIME 训练，默认推荐。", ModelCategory.CANDIDATE,
            versions = listOf(ModelVersion(version = "v3-ls-tri-c3", size = "202.9 MB", files = listOf(
                ModelFile("t9_ngram.lm", "https://github.com/Cyletix/CyIME/releases/download/models/t9_ngram-v3-ls-tri-c3.lm",
                    "11cee2cbd87f42cb18ddcd2b38ac2c2ef9e4a778c64da68f8c6f615e4050c867", 212706156L,
                    mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "t9_ngram-v3-ls-tri-c3.lm")),
            )),
            // 旧版 370 MB 格式：只用来认出老版本留下的文件，让它在模型管理里可见、可删、可被新版替换；不作为下载版本。
            ModelVersion(version = "v3-ls-tri", retired = true, changelog = "旧格式，内容与新版相同；可删除后改装新版以节省空间。", size = "350.6 MB", files = listOf(
                ModelFile("t9_ngram.lm", "https://github.com/Cyletix/CyIME/releases/download/models/t9_ngram-v3-ls-tri.lm",
                    "766c21385a6d3e5ad22a0367373531d0c7bed4bc31074a5265da8ad1a3d46f12", 367598664L),
            )))),
        ModelInfo(NGRAM_SMALL, "九键词组模型（精简，105 MB）", "完整版剪枝后的小一半版本，整句首选约低 3 个百分点；和完整版二选一，安装后重新启动输入法生效。上游 CyIME 训练。", ModelCategory.CANDIDATE,
            versions = listOf(ModelVersion(version = "v4-ls-p5e-8-c3", size = "104.6 MB", files = listOf(
                ModelFile("t9_ngram.lm", "https://github.com/Cyletix/CyIME/releases/download/models/t9_ngram-v4-ls-p5e-8-c3.lm",
                    "7d346a1fa2b332bdd764d1b91bc8002526cb7d563fea091f6b861279be025c4e", 109685894L,
                    mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "t9_ngram-v4-ls-p5e-8-c3.lm")),
            )))),
        // 九键词组模型在全部回放测试集上都比它准（t9-ngram-handover-20261010.md），体积不到它的三分之一：不再提供下载。
        // 条目保留：认出已下载的文件；没装词组模型的人它仍在起作用，装了词组模型后由 dropSupersededGrammar 删掉。
        ModelInfo(GRAMMAR, "九键语法增强（已停用）", "已停用：九键词组模型更准、更小，请改装词组模型；装了词组模型后它不再起作用，会自动删除。来源：RIME-LMDG，CC-BY-4.0。", ModelCategory.CANDIDATE,
            versions = listOf(ModelVersion(version = "lts-e34500ab", retired = true, size = "398.7 MB", files = listOf(
                // 上游 2026-10-30 前又替换了 LTS 资产（仍是 Rime::Grammar/1.0 格式，418,078,764 字节），资产 650124920 那份已下不到。
                // 先走上游固定文件名，上游再换文件时哈希对不上，就改用 CyIME Release 里转存的这一份。
                ModelFile("wanxiang-lts-zh-hans.gram", "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram",
                    "e34500ab44626dc039a0641792ecba98ab63307646eb3d29186be56608ffe094", 418078764L,
                    mirrors = listOf("https://github.com/Cyletix/CyIME/releases/download/models/wanxiang-lts-zh-hans-e34500ab.gram")),
            )),
            ModelVersion(version = "lts-650124920", retired = true, size = "380.0 MB", files = listOf(
                // 上游 2026-10-17 替换了 LTS 资产（同为 Rime::Grammar/1.0 格式），旧资产 602588267 已 404。
                ModelFile("wanxiang-lts-zh-hans.gram", "https://api.github.com/repos/amzxyz/RIME-LMDG/releases/assets/650124920",
                    "1849e5e4acbdbfaf41fceeb86b739d5d0c85d574bd8a688562cba1e6beb78488", 398505004L),
            )),
            // 旧版：上游已下架，只用来认出设备上已有的文件并允许删除。
            ModelVersion(version = "lts-602588267", retired = true, size = "390.4 MB", files = listOf(
                ModelFile("wanxiang-lts-zh-hans.gram", "https://api.github.com/repos/amzxyz/RIME-LMDG/releases/assets/602588267",
                    "873cbbb359fcf4df8b200183683ddc8be7b321eac4c864f8d2c7fc3136d4279f", 409412652L),
            )))),
        // 九键方案已不再加载这个模型；条目保留，让已下载的用户能在模型管理里删除它。
        ModelInfo(SENTENCE, "九键长句增强（已停用）", "已停用：实测对首选提升很小，却拖慢打字并在停顿后改动候选；现在不参与排序，已下载的可删除以释放空间。来源：UER-py，Apache-2.0。", ModelCategory.CANDIDATE,
            versions = listOf(ModelVersion(version = "uer-int8-v1", retired = true, size = "33.8 MB", files = listOf(
                ModelFile("t9_sentence.vocab", SOURCE + "t9_sentence.vocab",
                    "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c", 109540L),
                ModelFile("t9_sentence.onnx", SOURCE + "t9_sentence.onnx",
                    "159576a96e282f929f5726d53bae5322dc41235d3deef40e806297d9e69ca4fc", 35300003L),
            )))),
    )
}
