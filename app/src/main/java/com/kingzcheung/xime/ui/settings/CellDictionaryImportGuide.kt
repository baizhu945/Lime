package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 「导入词库」的格式说明：怎么导入、认哪几种文件、每种长什么样。内容按 ScelDictionary、TxtCellDictionary、
 * JsonCellDictionary 实际能读的写，改解析规则时这里一起改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CellDictionaryImportGuide(onDismiss: () -> Unit, onImport: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 24.dp).padding(bottom = 16.dp).testTag("cell-dictionary-import-guide"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("导入词库", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            GuideHeading("怎么导入")
            GuideText("1. 先把词库文件存到手机上：搜狗词库网站下载的、微信或 QQ 收到的文件、用数据线从电脑拷过来的都可以。")
            GuideText("2. 点「导入文件」，在文件选择器里找到它。")
            GuideText("3. 导入后这个词库会自动打开，点底部「应用更改」才生效。不用了可以关掉，关掉并应用后可以删除。")

            GuideHeading("支持的格式")
            GuideFormat("搜狗细胞词库（.scel）", "搜狗词库网站下载的文件，直接导入。")
            GuideFormat("文本词库（.txt）",
                "每行一个词。词和拼音用空格、Tab 或逗号隔开，谁在前都行；拼音可以连写，也可以用空格或 ' 隔开。" +
                    "搜狗、QQ 输入法导出的「词 拼音」文本一般可以直接导入。")
            GuideExample("你好 ni hao\nnihao 你好\n'shang'hai 上海\n计算机\tji suan ji\t100\nTeam Spirit")
            GuideText("最后的数字（词频）不读，所有词同一个权重；没有汉字的一行当作英文词，所有键盘的英文候选里都能打出来；" +
                "# 开头的行跳过。文件编码 UTF-8、UTF-16、GBK 都可以。")
            GuideFormat("Rime 词典（.dict.yaml）", "只读文件头「...」下面的词条，每行「词⇥拼音⇥权重」，权重同样不读。")
            GuideFormat("JSON（.json）", "Lime 自己的格式，拼音按字用空格隔开：")
            GuideExample("{\"format\": \"cyime.dictionary.v1\", \"name\": \"我的词库\",\n" +
                " \"language\": \"zh\", \"scheme\": \"pinyin\",\n" +
                " \"entries\": [{\"word\": \"你好\", \"code\": \"ni hao\"}]}")

            GuideHeading("限制")
            GuideText("一个文件最大 16 MB、25 万个词。中文词要 2 到 32 个字，单字和没有拼音的行会跳过，导入后会说跳过了几行。" +
                "只认全拼拼音，五笔等形码词库不能从这里导入。")

            Button(onClick = { onDismiss(); onImport() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                .heightIn(min = 48.dp)) { Text("选择文件导入") }
        }
    }
}

@Composable
private fun GuideHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun GuideText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GuideFormat(name: String, detail: String) {
    Column {
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        GuideText(detail)
    }
}

/** 示例原样显示，等宽、不折行，长行横着滑。 */
@Composable
private fun GuideExample(text: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(text.replace("\t", "    "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp))
    }
}
