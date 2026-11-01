package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

/** 鸣谢里的一项：名称、作者（不清楚时留空）、用在哪里、许可证（设计参考没有许可证），点开是项目主页。 */
data class CreditItem(
    val name: String,
    val author: String,
    val use: String,
    val license: String?,
    val url: String,
)

/**
 * 设置 › 关于 › 鸣谢：只列主要的上游、词库、数据、模型和设计参考。
 * 完整清单（每个依赖、语料及其许可证）在 README 的「鸣谢」，两边改一处要同步另一处。
 */
object Credits {
    const val README_URL = "https://github.com/Cyletix/CyIME#%E9%B8%A3%E8%B0%A2" // README 的「鸣谢」一节

    val groups: List<Pair<String, List<CreditItem>>> = listOf(
        "上游" to listOf(
            CreditItem("Xime", "Kingz Cheung（kingzcheung）及贡献者", "Lime 的键盘设计来自它；CyIME 也由它 fork", "GPL-3.0",
                "https://github.com/ximeiorg/Xime"),
            CreditItem("Xime 手写与联想模型", "Xime 项目", "中文手写识别、智能联想",
                "MIT、Apache-2.0、CC BY-NC-SA 4.0", "https://www.modelscope.cn/models/bikeand/ochwpro"),
        ),
        "输入引擎" to listOf(
            CreditItem("Rime（librime）", "佛振及 Rime 社区", "输入引擎", "BSD-3-Clause", "https://github.com/rime/librime"),
            CreditItem("OpenCC", "BYVoid", "简繁转换", "Apache-2.0", "https://github.com/BYVoid/OpenCC"),
            CreditItem("sherpa-onnx", "k2-fsa", "本地语音识别", "Apache-2.0", "https://github.com/k2-fsa/sherpa-onnx"),
            CreditItem("ONNX Runtime", "Microsoft", "手写、联想、识字的模型推理", "MIT",
                "https://github.com/microsoft/onnxruntime"),
        ),
        "词库与数据" to listOf(
            CreditItem("雾凇拼音", "iDvel", "拼音词库和方案", "GPL-3.0", "https://github.com/iDvel/rime-ice"),
            CreditItem("万象语言模型 RIME-LMDG", "amzxyz", "九键语法增强", "CC BY 4.0", "https://github.com/amzxyz/RIME-LMDG"),
            CreditItem("LCCC 中文对话语料", "清华大学 CoAI 小组", "训练九键词组模型和联想", "MIT",
                "https://github.com/thu-coai/CDial-GPT"),
            CreditItem("rime-jaroomaji", "lazy fox chan", "日语方案和词库", "Apache-2.0",
                "https://github.com/lazyfoxchan/rime-jaroomaji"),
            CreditItem("倉頡五代 Cangjie5", "Jackchows", "仓颉码表", "MIT", "https://github.com/Jackchows/Cangjie5"),
            CreditItem("rime-stroke", "Rime", "笔画方案", "LGPL-3.0", "https://github.com/rime/rime-stroke"),
            CreditItem("小麦注音 McBopomofo", "OpenVanilla", "台湾词和读音", "MIT", "https://github.com/openvanilla/McBopomofo"),
            CreditItem("ECDICT、CC-CEDICT", "skywind3000；MDBG", "候选中英释义", "MIT、CC BY-SA 4.0",
                "https://github.com/skywind3000/ECDICT"),
        ),
        "模型" to listOf(
            CreditItem("SenseVoice", "阿里巴巴 FunAudioLLM", "语音识别", "FunASR 模型许可",
                "https://github.com/FunAudioLLM/SenseVoice"),
            CreditItem("Paraformer", "阿里巴巴达摩院 FunASR", "边说边出字的语音识别", "Apache-2.0",
                "https://github.com/modelscope/FunASR"),
            CreditItem("3D-Speaker CAM++", "阿里巴巴 3D-Speaker", "字幕区分说话人", "Apache-2.0",
                "https://github.com/modelscope/3D-Speaker"),
            CreditItem("PP-OCRv5", "PaddlePaddle；RapidAI", "拍照识字", "Apache-2.0", "https://github.com/PaddlePaddle/PaddleOCR"),
        ),
        "设计参考" to listOf(
            CreditItem("三星键盘", "Samsung", "键盘交互和外观", null, "https://www.samsung.com/"),
            CreditItem("Gboard", "Google", "键盘交互和外观", null,
                "https://play.google.com/store/apps/details?id=com.google.android.inputmethod.latin"),
            CreditItem("Typewise", "", "蜂窝六边形布局", null, "https://www.typewise.app/"),
            CreditItem("首道双拼", "", "首道双拼键位", null, "https://sspai.com/post/108949"),
            CreditItem("小鹤双拼", "小鹤音形", "小鹤双拼键位", null, "https://flypy.cc/"),
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsContent(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("鸣谢") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).settingsVerticalScroll(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Credits.groups.forEach { (title, items) ->
                SettingsSection(title = title) {
                    items.forEachIndexed { index, item ->
                        if (index > 0) SettingsRowDivider()
                        val detail = listOfNotNull(item.author, item.use, item.license).filter { it.isNotBlank() }.joinToString(" · ")
                        SettingsListRow(item.name, detail, Modifier.clickable { uriHandler.openUri(item.url) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 12.dp).size(18.dp))
                        }
                    }
                }
            }
            Column {
                SettingsSection(title = "完整清单") {
                    SettingsListRow("GitHub 上的 README「鸣谢」", "每个依赖、语料、模型的作者、用途和许可证",
                        Modifier.clickable { uriHandler.openUri(Credits.README_URL) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp).size(18.dp))
                    }
                }
                SettingsNote("点一项打开它的主页。")
            }
        }
    }
}
