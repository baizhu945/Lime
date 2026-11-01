package com.kingzcheung.xime.settings

/**
 * 一套双拼键位：zh/ch/sh 各占一个键，每个韵母（按词库里的拼写，如 jue 的 ue、lüe 的 ve）占一个键，
 * 零声母音节各有两键编码。由它生成 Rime 拼写规则和编码显示规则；词库、符号和按键排列沿用小鹤双拼方案
 * （26 键、九键、14 键各一个版本）。
 */
data class DoublePinyinScheme(
    val id: String,
    val name: String,
    val initials: Map<String, Char>,
    val finals: Map<String, Char>,
    val zero: Map<String, String>,
) {
    /** 微软、搜狗双拼把 ing 放在分号键上：Rime 字母表加分号，26 键第二排补一个分号键，不生成九键、14 键版本。 */
    val usesSemicolon: Boolean get() = ';' in finals.values

    /** 音节的双拼编码；表里缺键或不是标准音节时为空。 */
    fun code(syllable: String): String? {
        zero[syllable]?.let { return it }
        val initial = SPECIAL_INITIALS.firstOrNull { syllable.startsWith(it) }
            ?: syllable.take(1).takeIf { it.isNotEmpty() && it[0] in PLAIN_INITIALS } ?: return null
        val key = finals[syllable.substring(initial.length)] ?: return null
        val first = if (initial.length == 2) initials[initial] ?: return null else initial[0]
        return "$first$key"
    }

    /** 同一编码对应多个音节（如首道双拼 dk = den/dia）；音节按常用程度排列，词库负责区分。 */
    fun collisions(): Map<String, List<String>> = SYLLABLES.groupBy { code(it) }
        .filterKeys { it != null }.filterValues { it.size > 1 }.mapKeys { it.key!! }

    /** 不能保存的问题；编码重复只提示，不阻止保存。 */
    fun problems(): List<String> = buildList {
        if (name.isBlank() || name.length > 32) add("方案名称需要 1 至 32 个字")
        if (!id.matches(ID_PATTERN)) add("方案编号无效")
        for (initial in SPECIAL_INITIALS) {
            val key = initials[initial]
            if (key == null || key !in 'a'..'z') add("$initial 还没有按键")
            else if (key in PLAIN_INITIALS) add("$initial 不能用 ${key.uppercaseChar()} 键：${key.uppercaseChar()} 本身是声母")
        }
        if (SPECIAL_INITIALS.mapNotNull { initials[it] }.toSet().size < SPECIAL_INITIALS.size) add("zh、ch、sh 需要三个不同的键")
        FINALS.filter { finals[it]?.let { key -> key in 'a'..'z' || key == ';' } != true }.takeIf { it.isNotEmpty() }
            ?.let { add("这些韵母还没有按键：${it.joinToString("、")}") }
        ZERO_SYLLABLES.filter { zero[it]?.matches(Regex("[a-z]{2}")) != true }.takeIf { it.isNotEmpty() }
            ?.let { add("这些零声母音节需要两个字母的编码：${it.joinToString("、")}") }
    }

    fun valid() = problems().isEmpty()

    /** 键位图：每个键上的翘舌声母和韵母。 */
    fun keyLabels(): Map<Char, Pair<String?, List<String>>> = (('a'..'z') + listOfNotNull(';'.takeIf { usesSemicolon })).associateWith { key ->
        SPECIAL_INITIALS.firstOrNull { initials[it] == key } to FINALS.filter { finals[it] == key }
    }

    /** 方案选择卡片上的示例编码。 */
    fun preview(): String = listOf("shuang", "pin").map { code(it) ?: "?" }.joinToString("'")

    /**
     * 拼写规则：先把零声母音节、韵母、翘舌声母换成占位符（带圈字母），最后统一换回字母，
     * 这样每条规则只匹配原始拼音，不会把前一条的结果再改一次。
     */
    fun algebra(): List<String> = buildList {
        add("erase/^xx$/")
        for (syllable in ZERO_SYLLABLES) add("xform/^$syllable$/${zero.getValue(syllable).upperCircled()}/")
        for (final in FINALS) add("xform/^(zh|ch|sh|[$PLAIN_INITIALS])$final$/\$1${finals.getValue(final).upperCircled()}/")
        for (initial in SPECIAL_INITIALS) add("xform/^$initial/${initials.getValue(initial).upperCircled()}/")
        add(if (usesSemicolon) "xlit/$UPPER_CIRCLED$SEMICOLON_PLACEHOLDER/$LETTERS;/" else "xlit/$UPPER_CIRCLED/$LETTERS/")
    }

    /**
     * 手动分词 '（Shift 键分词、逗号键分词）：双拼两键一字，' 只在单个键后面才有用。
     * a'、o'、e' 是这个零声母字本身（a've 出啊这），其他单个键加 ' 是这个键开头的字（v'vs 出这种，同全拼的简拼）。
     * 拼写里带着 '，只有真打了 ' 才对得上；不打 ' 的输入一个拼写也不多，选词不变。
     * 雾凇的双拼方案特意关着简拼（不打 ' 时 kjx 会被拆成 k'jx），所以只放在 ' 后面。
     * 加在本方案的拼写规则之后，作用在已经换好的双拼编码上；内置小鹤双拼在打包时加同样几条（tasks-chinese.gradle.kts）。
     */
    fun separatorRules(): List<String> =
        listOf("a", "o", "e").map { "derive/^${zero.getValue(it)}$/$it'/" } + "abbrev/^(.).+$/\$1'/"

    /**
     * 编码显示：按两个字母一组换回拼音（同码多音节时显示较常用的那个）；
     * 结果先写成带圈小写字母，避免被后面的规则再次匹配。
     */
    fun preeditFormat(): List<String> = buildList {
        // 编码里的键：字母，ing 在分号键上的方案还有分号。
        val keys = if (usesSemicolon) "[a-z;]" else "[a-z]"
        // 连续输入的字母按两个一组隔开，与 Rime 在音节间插入的分隔符一样处理。
        add("xform/($keys{2})(?=$keys)/\$1 /")
        for (syllable in ZERO_SYLLABLES) add("xform/(^|[ '])${zero.getValue(syllable)}(?=$|[ '])/\$1${syllable.lowerCircled()}/")
        val readings = LinkedHashMap<Pair<Char, Char>, String>()
        for (syllable in SYLLABLES) {
            if (syllable in zero) continue
            val code = code(syllable) ?: continue
            val initial = SPECIAL_INITIALS.firstOrNull { syllable.startsWith(it) } ?: syllable.take(1)
            readings.putIfAbsent(code[0] to code[1], syllable.substring(initial.length))
        }
        readings.entries.groupBy({ it.key.second to it.value }, { it.key.first }).forEach { (keyAndFinal, firsts) ->
            val (key, final) = keyAndFinal
            add("xform/(^|[ '])([${firsts.sorted().joinToString("")}])$key(?=$|[ '])/\$1\$2${final.lowerCircled()}/")
        }
        for (initial in SPECIAL_INITIALS) add("xform/(^|[ '])${initials.getValue(initial)}(?!$keys)/\$1${initial.lowerCircled()}/")
        add("xlit/$LOWER_CIRCLED/$LETTERS/")
        add("xform/(?<=[A-Z])\\s(?=[A-Z])//")
    }

    /**
     * 以已部署的小鹤双拼方案为底，换掉编号、名称、拼写规则、编码显示和中英混输词表；
     * 其余配置（词库、符号、纠错、候选策略）保持一致。
     */
    fun schema(base: String): String {
        require(valid())
        var text = base.replace("\r\n", "\n")
        val header = Regex("(?ms)^schema:\\n.*?(?=^\\S)").find(text) ?: error("小鹤双拼方案缺少 schema 段")
        val schemaBlock = header.value
            .replaceFirst(Regex("(?m)^  schema_id: .*$"), "  schema_id: $id\n  language: zh\n  input_scheme: double_pinyin")
            .replaceFirst(Regex("(?m)^  name: .*$"), Regex.escapeReplacement("  name: ${org.json.JSONObject.quote(name)}"))
            .replaceFirst(Regex("(?m)^  description: \\|\\n(?:    .*\\n)*"),
                "  description: |\n    Lime 双拼方案（由键位表生成），词库、符号与按键排列和小鹤双拼相同。\n")
        check(schemaBlock.contains("  schema_id: $id\n")) { "小鹤双拼方案缺少 schema_id" }
        text = text.replaceRange(header.range, schemaBlock)
        text = replaceList(text, "speller", "algebra", algebra() + separatorRules())
        if (usesSemicolon) {
            // 分号只进字母表、不进 initials：没在打字时按分号仍是标点。
            val alphabet = Regex("(?m)^(  alphabet: )(\"?)([^\\s#\"]+)(\"?)").find(text) ?: error("小鹤双拼方案缺少 alphabet")
            if (';' !in alphabet.groupValues[3]) text = text.replaceRange(alphabet.range,
                alphabet.groupValues[1] + alphabet.groupValues[2] + alphabet.groupValues[3] + ";" + alphabet.groupValues[4])
        }
        text = replaceList(text, "translator", "preedit_format", preeditFormat())
        val prism = Regex("(?m)^  prism: double_pinyin_flypy\\b.*$")
        check(prism.containsMatchIn(text)) { "小鹤双拼方案缺少 prism" }
        text = text.replace(prism, "  prism: $id")
        text = text.replace("user_dict: en_dicts/cn_en_flypy", "user_dict: en_dicts/${cnEnName(id)}")
        return "$GENERATED_MARK$id\n$text"
    }

    /**
     * 九键版本：每个音节的两个双拼键换成所在的数字键（如小鹤 zhong = vs → 87）。
     * 只用 Rime 的拼写运算和词库，不开九键拼音的整句解码与左侧拼音栏（它们按全拼计算）。
     * 词库、学习记录与 26 键双拼共用 rime_ice。
     */
    fun t9Schema(): String {
        require(this == FLYPY || valid())
        val t9 = t9Id(id)
        val algebra = algebra() + T9_DIGITS.map { (letters, digit) -> "derive/[$letters]/$digit/" }
        return """
            |$GENERATED_MARK$t9
            |schema:
            |  schema_id: $t9
            |  language: zh
            |  input_scheme: double_pinyin
            |  name: ${org.json.JSONObject.quote(name + "九键")}
            |  version: "1"
            |  description: |
            |    Lime 双拼方案（由键位表生成）的九键版本：每个字按它的两个双拼键所在的数字键输入。
            |  dependencies:
            |    - cyime_t9_english
            |
            |switches:
            |  - name: ascii_mode
            |    reset: 0
            |    states: [ 中文, 西文 ]
            |  - name: full_shape
            |    states: [ 半角, 全角 ]
            |  - name: ascii_punct
            |    reset: 0
            |    states: [ 。，, ．， ]
            |
            |engine:
            |  processors:
            |    - t9_processor
            |    - ascii_composer
            |    - recognizer
            |    - key_binder
            |    - speller
            |    - punctuator
            |    - selector
            |    - navigator
            |    - express_editor
            |  segmentors:
            |    - ascii_segmentor
            |    - matcher
            |    - abc_segmentor
            |    - punct_segmentor
            |    - fallback_segmentor
            |  translators:
            |    - punct_translator
            |    - script_translator
            |    - t9_english_translator@t9_english
            |  filters:
            |    - cyime_candidate_policy
            |    - t9_filter
            |    - uniquifier
            |
            |speller:
            |  alphabet: zyxwvutsrqponmlkjihgfedcba9876543210
            |  initials: zyxwvutsrqponmlkjihgfedcba9876543210
            |  delimiter: " '"
            |  algebra:
            |${algebra.joinToString("") { "    - \"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n" }}
            |translator:
            |  dictionary: rime_ice
            |  prism: $t9
            |  max_homophones: 32
            |  spelling_hints: 100
            |  always_show_comments: true
            |  comment_format: []
            |
            |t9:
            |  # 左侧拼音栏和整句解码按全拼的数字计算，双拼九键不用；候选上方按双拼两键一字显示拼音。
            |  left_panel_mode: none
            |  double_pinyin: true
            |  isDisplayOriginalPreedit: false
            |
            |cyime:
            |  candidate_policy: true
            |
            |punctuator:
            |  import_preset: default
            |
            |recognizer:
            |  patterns:
            |    punct: "^/([0-9]|10|[A-Za-z]*?)${'$'}"
            |
            |key_binder:
            |  bindings:
            |    - { when: has_menu, accept: 1, send: apostrophe }
            |
            |editor:
            |  bindings:
            |    space: confirm
            |
            |t9_english:
            |  dictionary: cyime_t9_english
            |  enable_completion: false
            |  enable_sentence: false
            |  enable_user_dict: true
            |  initial_quality: 0.2
            |  comment_format: []
            |  digit_suffixes: false
            |  completion_limit: 3
            |""".trimMargin()
    }

    /**
     * 14 键版本：以已部署的 14 键小鹤双拼方案为底，换上本方案的拼写规则。每个字两个键，每一位都可以是
     * 精确字母（侧滑选的字母）或合并键（点按，大写代表字母），与 14 键小鹤双拼相同；词库和学习记录共用 rime_ice。
     */
    fun k14Schema(base: String): String {
        require(valid())
        val k14 = k14Id(id)
        var text = base.replace("\r\n", "\n")
        val header = Regex("(?ms)^schema:\\n.*?(?=^\\S)").find(text) ?: error("14 键小鹤双拼方案缺少 schema 段")
        val schemaBlock = header.value
            .replaceFirst(Regex("(?m)^  schema_id: .*$"), "  schema_id: $k14")
            .replaceFirst(Regex("(?m)^  name: .*$"), Regex.escapeReplacement("  name: ${org.json.JSONObject.quote(name)}"))
            .replaceFirst(Regex("(?m)^  description: \\|\\n(?:    .*\\n)*"),
                "  description: |\n    Lime 双拼方案（由键位表生成）的 14 键版本，词库和按键排列与 14 键小鹤双拼相同。\n")
        check(schemaBlock.contains("  schema_id: $k14\n")) { "14 键小鹤双拼方案缺少 schema_id" }
        text = text.replaceRange(header.range, schemaBlock)
        // 每个字的两位各自可以换成合并键；词库里的单个大写字母音节与合并键的大写编码同形，14 键用不到，先删掉。
        val merged = (0..1).flatMap { position -> K14_GROUPS.map { (letters, key) -> "derive/^(.{$position})[$letters](.*)$/\$1$key\$2/" } }
        text = replaceList(text, "speller", "algebra", listOf("erase/^[A-Z]$/") + algebra() + merged)
        val prism = Regex("(?m)^  prism: double_pinyin_flypy_14jian\\b.*$")
        check(prism.containsMatchIn(text)) { "14 键小鹤双拼方案缺少 prism" }
        text = text.replace(prism, "  prism: $k14")
        return "$GENERATED_MARK$k14\n$text"
    }

    /**
     * 自建合并键布局上的版本（如自己排的 20 键）：以本方案的 26 键配置 [base26] 为底，每个字的两位各自可以是
     * 精确字母（长按气泡选的字母）或合并键（点按送的代表字母，即键上第一个字母），和 14 键版本同一个做法。
     * 合并键的代表字母是小写，和精确字母同形，不能再套 26 键的编码显示（会把 we 键的 w 显示成另一个音节），
     * 屏上编码按首选读音换成本方案的双拼键（同 14 键）。临键纠错按 26 键位置算，合并键上不开。
     */
    fun layoutSchema(base26: String, layoutVariantId: String, layoutName: String, groups: List<String>): String {
        require(this == FLYPY || valid())
        var text = base26.replace("\r\n", "\n").lineSequence().dropWhile { it.startsWith(GENERATED_MARK) || it.startsWith(LEGACY_GENERATED_MARK) }.joinToString("\n")
        val header = Regex("(?ms)^schema:\\n.*?(?=^\\S)").find(text) ?: error("双拼方案缺少 schema 段")
        val schemaBlock = header.value
            .replaceFirst(Regex("(?m)^  schema_id: .*$"), "  schema_id: $layoutVariantId")
            .replaceFirst(Regex("(?m)^  description: \\|\\n(?:    .*\\n)*"), Regex.escapeReplacement(
                "  description: |\n    Lime 双拼方案（由键位表生成）在自建布局「$layoutName」上的版本，词库与 26 键双拼相同。\n"))
        check(schemaBlock.contains("  schema_id: $layoutVariantId\n")) { "双拼方案缺少 schema_id" }
        text = text.replaceRange(header.range, schemaBlock)
        val merged = (0..1).flatMap { position ->
            groups.filter { it.length > 1 }.map { group -> "derive/^(.{$position})[${group.drop(1)}](.*)$/\$1${group[0]}\$2/" }
        }
        // 原有规则一字不动，合并键的派生接在最后（作用在已经换好的双拼编码上）。
        val speller = Regex("(?ms)^speller:\\n.*?(?=^[a-zA-Z_][a-zA-Z_0-9]*:|\\z)").find(text) ?: error("双拼方案缺少 speller 段")
        val algebra = Regex("(?m)^  algebra:[^\\n]*\\n(?:    [^\\n]*\\n)*").find(speller.value) ?: error("双拼方案缺少 algebra")
        val appended = speller.value.replaceRange(algebra.range, algebra.value + merged.joinToString("") { "    - \"$it\"\n" })
        text = text.replaceRange(speller.range, appended)
        val translator = Regex("(?ms)^translator:\\n.*?(?=^[a-zA-Z_][a-zA-Z_0-9]*:|\\z)").find(text) ?: error("双拼方案缺少 translator 段")
        val prism = Regex("(?m)^  prism: \\S+.*$")
        check(prism.containsMatchIn(translator.value)) { "双拼方案缺少 prism" }
        val translatorBlock = translator.value
            .replace(Regex("(?m)^  (enable_correction|cyime_neighbor_correction|spelling_hints|always_show_comments):.*\\n"), "")
            .replace(Regex("(?m)^  preedit_format:[^\\n]*\\n(?:    [^\\n]*\\n)*"), "  preedit_format: []\n")
            .replaceFirst(prism, "  prism: $layoutVariantId\n  spelling_hints: 8\n  always_show_comments: true")
        text = text.replaceRange(translator.range, translatorBlock)
        return "$GENERATED_MARK$layoutVariantId\n$text"
    }

    /** 换掉 [section] 段里 [key] 列表的全部条目（含其间的注释行）。 */
    private fun replaceList(text: String, section: String, key: String, items: List<String>): String {
        val block = Regex("(?ms)^$section:\\n.*?(?=^[a-zA-Z_][a-zA-Z_0-9]*:|\\z)").find(text) ?: error("小鹤双拼方案缺少 $section 段")
        val list = Regex("(?m)^  $key:[^\\n]*\\n(?:    [^\\n]*\\n)*").find(block.value) ?: error("小鹤双拼方案缺少 $key")
        val replaced = "  $key:\n" + items.joinToString("") { "    - \"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n" }
        return text.replaceRange(block.range, block.value.replaceRange(list.range, replaced))
    }

    /**
     * 中英混输词表（如 T恤、B站）：小鹤双拼的词表按字母原样、每个汉字或数字两键的规则拆开，
     * 换成本方案的编码。拆不开的行跳过。
     */
    fun cnEnTable(flypy: String): String = flypy.replace("\r\n", "\n").lines().mapNotNull { line ->
        if (line.startsWith("#@/db_name")) return@mapNotNull "#@/db_name\t${cnEnName(id)}.txt"
        if (line.startsWith("#") || '\t' !in line) return@mapNotNull line
        val (word, code) = line.split('\t', limit = 2)
        val converted = StringBuilder()
        var i = 0
        for (c in word) {
            when {
                c in 'a'..'z' || c in 'A'..'Z' -> {
                    if (i >= code.length || !code[i].equals(c, ignoreCase = true)) return@mapNotNull null
                    converted.append(code[i]); i++
                }
                c in "·.-" -> if (i < code.length && code[i] == c) { converted.append(c); i++ }
                else -> {
                    val pair = code.substring(i, minOf(i + 2, code.length))
                    val syllable = FLYPY_DECODE[pair.lowercase()] ?: return@mapNotNull null
                    val encoded = code(syllable) ?: return@mapNotNull null
                    converted.append(if (pair[0].isUpperCase()) encoded.replaceFirstChar(Char::uppercaseChar) else encoded)
                    i += 2
                }
            }
        }
        if (i != code.length) null else "$word\t$converted"
    }.joinToString("\n")

    companion object {
        const val GENERATED_MARK = "# Lime 双拼方案生成："
        /** 上游 CyIME 生成过的旧文件，仍要能识别并重写，避免重复标题。 */
        private const val LEGACY_GENERATED_MARK = "# CyIME 双拼方案生成："
        const val SHOUDAO_ID = "double_pinyin_shoudao"
        const val CUSTOM_PREFIX = "custom_shuangpin_"
        const val ZIRANMA_ID = "double_pinyin_ziranma"
        const val ZNABC_ID = "double_pinyin_znabc"
        const val JIAJIA_ID = "double_pinyin_jiajia"
        /** 不用 Rime 的 double_pinyin_mspy，免得和导入的同名方案冲突。 */
        const val MICROSOFT_ID = "double_pinyin_microsoft"
        private val ID_PATTERN = Regex("$SHOUDAO_ID|$ZIRANMA_ID|$ZNABC_ID|$JIAJIA_ID|$MICROSOFT_ID|${CUSTOM_PREFIX}[a-f0-9]{32}")
        val SPECIAL_INITIALS = listOf("zh", "ch", "sh")
        const val PLAIN_INITIALS = "bpmfdtnlgkhjqxrzcsyw"
        /** 按词库拼写列出的韵母；jue 的 ue 与 lüe 的 ve、ju 的 u 与 lü 的 v 分开设置。 */
        val FINALS = listOf("a", "o", "e", "i", "u", "v", "ai", "ei", "ui", "ao", "ou", "iu", "ie", "ue", "ve",
            "an", "en", "in", "un", "ang", "eng", "ing", "ong", "ia", "ua", "uo", "iao", "ian", "uan", "iang", "uang",
            "iong", "uai")
        val ZERO_SYLLABLES = listOf("a", "o", "e", "ai", "ei", "ao", "ou", "an", "en", "ang", "eng", "er")
        private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"
        private val UPPER_CIRCLED = String(CharArray(26) { (0x24B6 + it).toChar() })
        private val LOWER_CIRCLED = String(CharArray(26) { (0x24D0 + it).toChar() })
        /** 分号的占位符（带圈字母只有 26 个）。 */
        private const val SEMICOLON_PLACEHOLDER = '\u24EA'
        private fun Char.upperCircled() = if (this == ';') SEMICOLON_PLACEHOLDER else UPPER_CIRCLED[this - 'a']
        private fun String.upperCircled() = map { it.upperCircled() }.joinToString("")
        private fun String.lowerCircled() = map { LOWER_CIRCLED[it - 'a'] }.joinToString("")
        fun cnEnName(id: String) = "cn_en_$id"
        const val T9_SUFFIX = "_t9"
        fun t9Id(id: String) = id + T9_SUFFIX
        /** 14 键版本，与 14 键小鹤双拼 double_pinyin_flypy_14jian 同样的后缀。 */
        const val K14_SUFFIX = "_14jian"
        fun k14Id(id: String) = id + K14_SUFFIX
        /** 自建合并键布局上的版本：双拼方案 id + 布局 id（custom_pinyin_…）。 */
        fun layoutVariantId(id: String, layoutId: String) = "${id}_$layoutId"
        private val LAYOUT_VARIANT = Regex("(.+)_(custom_pinyin_[a-f0-9]{32})")
        /** 拆出 (双拼方案 id, 布局 id)；不是布局版本的 id 为 null。 */
        fun splitLayoutVariant(id: String): Pair<String, String>? =
            LAYOUT_VARIANT.matchEntire(id)?.let { it.groupValues[1] to it.groupValues[2] }
        /** 14 键的合并键（与 xime.yaml qwerty_14 和 14 键小鹤双拼的拼写运算相同）：点按送大写代表字母，l、m 单独一键。 */
        private val K14_GROUPS = listOf("qw" to 'Q', "er" to 'E', "ty" to 'T', "ui" to 'U', "op" to 'O', "as" to 'A',
            "df" to 'D', "gh" to 'G', "jk" to 'J', "zx" to 'Z', "cv" to 'C', "bn" to 'B')
        /** 九键的字母分组，与中文九键 t9_pinyin 的拼写运算相同。 */
        private val T9_DIGITS = listOf("abc" to 2, "def" to 3, "ghi" to 4, "jkl" to 5, "mno" to 6, "pqrs" to 7,
            "tuv" to 8, "wxyz" to 9)

        /** 词库里的音节，按常用程度排列（雾凇拼音 8105 字表的字频合计）。 */
        val SYLLABLES: List<String> = (
        "de shi yi ji wo ta you zai zhi ren li zhe ge zi zhei qi xiang wei di da dao lai jiu zhong shang qing " +
        "shen jian ye xiao ma huo guo na hui shuo mei jing xi he er wu sheng dan yao yu hai fang jin xia en si " +
        "shou hou qu eng jie hao ba yan xing fu kan jia zuo hua dui ran tian yuan dei bu zhang xie chang cheng " +
        "xian fa qian bei zhu zheng ming a lian jiao gong bian an liang chu dian yin ru ci wen xin wang dong tou " +
        "shu bi zhen tong gan duo ying hu wan mian fan ju hen ke guan yang jiang kai ban xue yong jue bao cai le " +
        "feng ting ling fen bai lao suo dang dai fei nan xu bing gao nian gu o mo la shan zhao mu liu ding shai " +
        "zui tai cong shui lu ti que chi quan nv san ya zhan zou rang sui ben gei hang du tu deng huan yue shao " +
        "man guang su ai kou zen han kong luo chen qiang gen chuan zu yun kuai qin gai wai tiao lin lei tan ping " +
        "mi huang gui zao qie se ni e bie zhou chao duan cha hong zong gang qiu long ri xiu zhuan geng bo ku " +
        "zhuang sha biao che meng chong pai tao dou xiong she hei xuan song ze po gou pa rong pi nei men diao " +
        "pian bang suan mao tuo mang te tui kuang hun leng xun wa pao qiao jun mai lan guai shuang lun nao lv min " +
        "can chan ruo lang shei chuang ceng cao pu cuo chou lou pin pang cun pei ha chun dun nu liao nai zhuo " +
        "zhun pan piao nong tang peng re luan tie miao tuan lie shun zan heng cang ce zha juan mou niang kao zhui " +
        "ning qun sun niu ken ao kang reng gua rou sai nin ka huai cui kui shuai fo ou die fou chui lve teng kun " +
        "rao sao cu rui kuo sang mie zun kuan bin gun nuo zhai zeng ruan sa sou sen niao kua zang qiong chai diu " +
        "ne ca za nie pen tun beng shua zei zhua nuan ga zuan jiong run ang cou weng keng cuan pie seng nang nen " +
        "neng yo chuai zhuai chuo nve miu shuan cen pou qia tei me lo cei dia ei nou kei lia den chua biang fiao " +
        "rua"
            ).split(' ')

        val FLYPY = DoublePinyinScheme("double_pinyin_flypy", "小鹤双拼", mapOf("zh" to 'v', "ch" to 'i', "sh" to 'u'),
            mapOf("a" to 'a', "o" to 'o', "e" to 'e', "i" to 'i', "u" to 'u', "v" to 'v', "ai" to 'd', "ei" to 'w',
                "ui" to 'v', "ao" to 'c', "ou" to 'z', "iu" to 'q', "ie" to 'p', "ue" to 't', "ve" to 't', "an" to 'j',
                "en" to 'f', "in" to 'b', "un" to 'y', "ang" to 'h', "eng" to 'g', "ing" to 'k', "ong" to 's', "ia" to 'x',
                "ua" to 'x', "uo" to 'o', "iao" to 'n', "ian" to 'm', "uan" to 'r', "iang" to 'l', "uang" to 'l',
                "iong" to 's', "uai" to 'k'),
            mapOf("a" to "aa", "o" to "oo", "e" to "ee", "ai" to "ai", "ei" to "ei", "ao" to "ao", "ou" to "ou",
                "an" to "an", "en" to "en", "ang" to "ah", "eng" to "eg", "er" to "er"))

        /** 首道双拼：zh→V、ch→I、sh→E，零声母 12 组独立双键（口诀：美尊红票样，娃向团；恳求来年，搜到风怪）。 */
        val SHOUDAO = DoublePinyinScheme(SHOUDAO_ID, "首道双拼", mapOf("zh" to 'v', "ch" to 'i', "sh" to 'e'),
            mapOf("a" to 'a', "o" to 'o', "e" to 'e', "i" to 'i', "u" to 'u', "v" to 'v', "ai" to 'l', "ei" to 'm',
                "ui" to 'v', "ao" to 'd', "ou" to 's', "iu" to 'q', "ie" to 'r', "ue" to 'l', "ve" to 'b', "an" to 'j',
                "en" to 'k', "in" to 'c', "un" to 'z', "ang" to 'y', "eng" to 'f', "ing" to 'g', "ong" to 'h', "ia" to 'k',
                "ua" to 'w', "uo" to 'o', "iao" to 'p', "ian" to 'n', "uan" to 't', "iang" to 'x', "uang" to 'x',
                "iong" to 'h', "uai" to 'g'),
            mapOf("a" to "aa", "o" to "oo", "e" to "ue", "ai" to "ai", "ei" to "ui", "ao" to "ao", "ou" to "ou",
                "an" to "an", "en" to "en", "ang" to "ay", "eng" to "uf", "er" to "er"))

        /** 自然码：zh→V、ch→I、sh→U；零声母取首字母加韵母键（ang→AH、eng→EG），a、o、e 双写。 */
        val ZIRANMA = DoublePinyinScheme(ZIRANMA_ID, "自然码", mapOf("zh" to 'v', "ch" to 'i', "sh" to 'u'),
            mapOf("a" to 'a', "o" to 'o', "e" to 'e', "i" to 'i', "u" to 'u', "v" to 'v', "ai" to 'l', "ei" to 'z',
                "ui" to 'v', "ao" to 'k', "ou" to 'b', "iu" to 'q', "ie" to 'x', "ue" to 't', "ve" to 't', "an" to 'j',
                "en" to 'f', "in" to 'n', "un" to 'p', "ang" to 'h', "eng" to 'g', "ing" to 'y', "ong" to 's', "ia" to 'w',
                "ua" to 'w', "uo" to 'o', "iao" to 'c', "ian" to 'm', "uan" to 'r', "iang" to 'd', "uang" to 'd',
                "iong" to 's', "uai" to 'y'),
            mapOf("a" to "aa", "o" to "oo", "e" to "ee", "ai" to "ai", "ei" to "ei", "ao" to "ao", "ou" to "ou",
                "an" to "an", "en" to "en", "ang" to "ah", "eng" to "eg", "er" to "er"))

        /** 智能ABC双拼：zh→A、ch→E、sh→V；零声母一律以 O 开头再加韵母键（ai→OL、er→OR）。 */
        val ZNABC = DoublePinyinScheme(ZNABC_ID, "智能ABC双拼", mapOf("zh" to 'a', "ch" to 'e', "sh" to 'v'),
            mapOf("a" to 'a', "o" to 'o', "e" to 'e', "i" to 'i', "u" to 'u', "v" to 'v', "ai" to 'l', "ei" to 'q',
                "ui" to 'm', "ao" to 'k', "ou" to 'b', "iu" to 'r', "ie" to 'x', "ue" to 'm', "ve" to 'm', "an" to 'j',
                "en" to 'f', "in" to 'c', "un" to 'n', "ang" to 'h', "eng" to 'g', "ing" to 'y', "ong" to 's', "ia" to 'd',
                "ua" to 'd', "uo" to 'o', "iao" to 'z', "ian" to 'w', "uan" to 'p', "iang" to 't', "uang" to 't',
                "iong" to 's', "uai" to 'c'),
            mapOf("a" to "oa", "o" to "oo", "e" to "oe", "ai" to "ol", "ei" to "oq", "ao" to "ok", "ou" to "ob",
                "an" to "oj", "en" to "of", "ang" to "oh", "eng" to "og", "er" to "or"))

        /** 拼音加加：zh→V、ch→U、sh→I；零声母 a、e 开头的双写首字母再加韵母键（ai→AS、er→EQ），o、ou 打 OO、OP。 */
        val JIAJIA = DoublePinyinScheme(JIAJIA_ID, "拼音加加", mapOf("zh" to 'v', "ch" to 'u', "sh" to 'i'),
            mapOf("a" to 'a', "o" to 'o', "e" to 'e', "i" to 'i', "u" to 'u', "v" to 'v', "ai" to 's', "ei" to 'w',
                "ui" to 'v', "ao" to 'd', "ou" to 'p', "iu" to 'n', "ie" to 'm', "ue" to 'x', "ve" to 'x', "an" to 'f',
                "en" to 'r', "in" to 'l', "un" to 'z', "ang" to 'g', "eng" to 't', "ing" to 'q', "ong" to 'y', "ia" to 'b',
                "ua" to 'b', "uo" to 'o', "iao" to 'k', "ian" to 'j', "uan" to 'c', "iang" to 'h', "uang" to 'h',
                "iong" to 'y', "uai" to 'x'),
            mapOf("a" to "aa", "o" to "oo", "e" to "ee", "ai" to "as", "ei" to "ew", "ao" to "ad", "ou" to "op",
                "an" to "af", "en" to "er", "ang" to "ag", "eng" to "et", "er" to "eq"))

        /** 微软双拼：zh→V、ch→I、sh→U，ing 在分号键；零声母以 O 开头再加韵母键（爱 OL、而 OR），与 Windows 自带的微软拼音一致。 */
        val MICROSOFT = DoublePinyinScheme(MICROSOFT_ID, "微软双拼", mapOf("zh" to 'v', "ch" to 'i', "sh" to 'u'),
            mapOf("a" to 'a', "o" to 'o', "e" to 'e', "i" to 'i', "u" to 'u', "v" to 'y', "ai" to 'l', "ei" to 'z',
                "ui" to 'v', "ao" to 'k', "ou" to 'b', "iu" to 'q', "ie" to 'x', "ue" to 't', "ve" to 't', "an" to 'j',
                "en" to 'f', "in" to 'n', "un" to 'p', "ang" to 'h', "eng" to 'g', "ing" to ';', "ong" to 's', "ia" to 'w',
                "ua" to 'w', "uo" to 'o', "iao" to 'c', "ian" to 'm', "uan" to 'r', "iang" to 'd', "uang" to 'd',
                "iong" to 's', "uai" to 'y'),
            mapOf("a" to "oa", "o" to "oo", "e" to "oe", "ai" to "ol", "ei" to "oz", "ao" to "ok", "ou" to "ob",
                "an" to "oj", "en" to "of", "ang" to "oh", "eng" to "og", "er" to "or"))

        val BUILTINS = listOf(FLYPY, SHOUDAO, ZIRANMA, ZNABC, JIAJIA, MICROSOFT)

        /** 小鹤双拼编码 → 音节；lo 与 luo 同码时取 luo。 */
        private val FLYPY_DECODE: Map<String, String> = HashMap<String, String>().apply {
            for (syllable in SYLLABLES.sortedBy { it.length }) FLYPY.code(syllable)?.let { put(it, syllable) }
        }

        fun fresh(from: DoublePinyinScheme, name: String) = from.copy(
            id = CUSTOM_PREFIX + java.util.UUID.randomUUID().toString().replace("-", ""), name = name)
    }
}
