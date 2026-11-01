package com.kingzcheung.xime.settings

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Reads the two common desktop SCEL variants. Source bytes are data, never YAML or paths. */
object ScelDictionary {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRIES = 250_000
    /** [english]: Latin words of a mixed list (Ame, Team Spirit), offered by the English candidates, not the Chinese pack. */
    data class Parsed(val name: String, val entries: List<DictEntry>, val skipped: Int, val english: List<String> = emptyList())

    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "词库超过 16 MB，请选择较小的词库" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private const val PINYIN_TABLE = 0x1540

    /** Sogou's standard 413-syllable table (the same in every sample file), for files that ship an empty list. */
    private val STANDARD_SYLLABLES: List<String> by lazy {
        (
        "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu ca cai can cang cao " +
        "ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui " +
        "chun chuo ci cong cou cu cuan cui cun cuo da dai dan dang dao de dei den deng di dia dian diao die " +
        "ding diu dong dou du duan dui dun duo e ei en eng er fa fan fang fei fen feng fiao fo fou fu ga gai " +
        "gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo ha hai han hang hao he hei " +
        "hen heng hong hou hu hua huai huan huang hui hun huo ji jia jian jiang jiao jie jin jing jiong jiu " +
        "ju juan jue jun ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo la " +
        "lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lue lun luo " +
        "lv ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu na nai nan nang nao " +
        "ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nue nun nuo nv o ou pa pai pan " +
        "pang pao pei pen peng pi pian piao pie pin ping po pou pu qi qia qian qiang qiao qie qin qing qiong " +
        "qiu qu quan que qun ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo sa sai san sang sao " +
        "se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui " +
        "shun shuo si song sou su suan sui sun suo ta tai tan tang tao te tei teng ti tian tiao tie ting tong " +
        "tou tu tuan tui tun tuo wa wai wan wang wei wen weng wo wu xi xia xian xiang xiao xie xin xing xiong " +
        "xiu xu xuan xue xun ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun za zai zan zang zao " +
        "ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan " +
        "zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo"
        ).split(' ')
    }

    /**
     * Header-driven, so newer files import too (10-31: an 800 KB download failed). The word table starts right
     * after the pinyin list, whose length differs between files (413 syllables, + A-Z, + 0-9); it ends at the
     * header's group count, and newer files follow it with a phrase section and a deleted-word list that must not
     * be read as words. A damaged single record is skipped; only a truncated structure rejects the file.
     */
    fun parse(bytes: ByteArray): Parsed {
        // The magic varies (44 43 53 01, 45 43 53 01, d2 6d 53 01); the first four bytes are always offset 0x1540.
        require(bytes.size in PINYIN_TABLE + 4..MAX_BYTES && bytes.take(4) == listOf<Byte>(0x40, 0x15, 0, 0) &&
            bytes[6] == 0x53.toByte() && bytes[7] == 1.toByte()) {
            "文件不是支持的搜狗 SCEL 词库，可能是下载失败或网页内容"
        }
        fun u16(at: Int): Int {
            require(at >= 0 && at + 2 <= bytes.size) { "词库数据不完整" }
            return (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
        }
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        /** Null when the text is not valid UTF-16: that record is skipped, the file is not rejected. */
        fun decode(start: Int, length: Int): String? {
            require(length % 2 == 0 && start >= 0 && length <= bytes.size - start) { "词库数据不完整" }
            return try {
                Charsets.UTF_16LE.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, length)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) { null }
        }
        // The name field keeps whatever followed its terminator; read up to the first NUL only.
        val nameEnd = (0x130 until 0x338 step 2).firstOrNull { u16(it) == 0 } ?: 0x338
        val name = decode(0x130, nameEnd - 0x130).orEmpty().trim().take(100)

        val listed = u32(PINYIN_TABLE)
        require(listed <= 1000) { "词库拼音表损坏" }
        val syllables = mutableMapOf<Int, String?>()
        var at = PINYIN_TABLE + 4
        repeat(listed.toInt()) {
            val index = u16(at)
            val length = u16(at + 2)
            require(length in 2..32 && index !in syllables) { "词库拼音表损坏" }
            // Newer lists append A-Z (kept as letters, as before) and 0-9 (no pinyin: words using them are skipped).
            val syllable = decode(at + 4, length)?.lowercase()?.replace('ü', 'v')
            syllables[index] = when (syllable) {
                "lue" -> "lve"
                "nue" -> "nve"
                else -> syllable?.takeIf { it.matches(Regex("[a-z]{1,8}")) }
            }
            at += 4 + length
        }
        // An empty list means Sogou's own numbering: 413 syllables, then A-Z, then 0-9.
        if (syllables.isEmpty()) (STANDARD_SYLLABLES + ('a'..'z').map(Char::toString))
            .forEachIndexed { index, syllable -> syllables[index] = syllable }
        /** Null when an index has no pinyin (digits, out of range): the words with this code are skipped. */
        fun code(start: Int, codeBytes: Int): String? {
            val parts = (0 until codeBytes step 2).map { syllables[u16(start + it)] ?: return null }
            return parts.joinToString(" ")
        }

        val entries = linkedMapOf<Pair<String, String>, DictEntry>()
        var total = 0
        var skipped = 0
        fun add(word: String?, code: String?, needsHan: Boolean = false) {
            require(++total <= MAX_ENTRIES) { "词库超过 25 万条，请拆分后导入" }
            // SCEL's extra field is not a calibrated Rime weight. Keep supplemental terms modest.
            if (word == null || code == null || word.isBlank() || word.length > 128 || word.startsWith('#') ||
                word.any { it.isISOControl() } || word.contains('�') ||
                (needsHan && word.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })) {
                skipped++
            } else {
                entries.putIfAbsent(word to code, DictEntry(word, code, 100))
            }
        }

        // Sections after the word table (newer files). An offset that does not fit the file is ignored.
        val phraseCount = u32(0x5C)
        val phraseStart = u32(0x60)
        val phraseEnd = phraseStart + u32(0x64)
        val hasPhrases = phraseCount > 0 && phraseStart >= at && phraseEnd <= bytes.size
        val deletedStart = u32(0x78)
        val wordEnd = listOfNotNull(phraseStart.takeIf { hasPhrases },
            deletedStart.takeIf { u32(0x74) > 0 && it >= at && it <= bytes.size }).minOrNull()?.toInt() ?: bytes.size
        val groups = u32(0x120)
        var group = 0L
        while (at < wordEnd && (groups == 0L || group < groups)) {
            group++
            val count = u16(at)
            val codeBytes = u16(at + 2)
            at += 4
            require(count > 0 && codeBytes > 0 && codeBytes % 2 == 0 && codeBytes <= 256) { "词库词条结构损坏" }
            require(codeBytes <= bytes.size - at) { "词库数据不完整" }
            val code = code(at, codeBytes)
            at += codeBytes
            repeat(count) {
                val length = u16(at)
                val word = decode(at + 2, length)
                at += 2 + length
                val extraLength = u16(at)
                at += 2
                require(extraLength >= 2 && extraLength <= bytes.size - at) { "词库数据不完整" }
                at += extraLength
                add(word, code)
            }
        }

        if (hasPhrases) {
            // Each phrase: 17-byte info, code, word. Pinyin phrases (info[2] == 1) are ordinary words; custom
            // phrases have free-form codes, and phrases without a Chinese character (Airbnb, emoticons) are no
            // pinyin words either: both are skipped.
            at = phraseStart.toInt()
            val end = phraseEnd.toInt()
            for (phrase in 0 until minOf(phraseCount, MAX_ENTRIES.toLong() + 1).toInt()) {
                if (end - at < 21) break
                val pinyin = bytes[at + 2] == 1.toByte()
                at += 17
                val codeBytes = u16(at)
                at += 2
                require(codeBytes <= end - at - 2) { "词库数据不完整" }
                val code = if (pinyin && codeBytes % 2 == 0 && codeBytes in 2..256) code(at, codeBytes) else null
                at += codeBytes
                val length = u16(at)
                at += 2
                require(length <= end - at) { "词库数据不完整" }
                val word = if (length % 2 == 0) decode(at, length) else null
                at += length
                add(word, code, needsHan = true)
            }
        }
        require(entries.isNotEmpty()) { "词库没有可用词条" }
        return Parsed(name, entries.values.toList(), skipped)
    }

    fun toRime(name: String, entries: List<DictEntry>): String {
        require(name.matches(Regex("[a-z0-9_]+")))
        return buildString {
            append("# Imported SCEL dictionary; managed by Lime\n---\nname: $name\nversion: \"1\"\nsort: by_weight\nuse_preset_vocabulary: false\n...\n")
            entries.forEach { append(it.word).append('\t').append(it.code).append("\t100\n") }
        }
    }
}
