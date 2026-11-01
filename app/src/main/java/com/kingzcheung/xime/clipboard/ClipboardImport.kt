package com.kingzcheung.xime.clipboard

import org.json.JSONArray
import org.json.JSONObject
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/** One record read from an import file. [timestamp] 0 = the file did not say when it was copied. */
internal data class ImportedClip(
    val text: String,
    val pinned: Boolean = false,
    val code: String = "",
    val timestamp: Long = 0,
    val group: String? = null,
)

/**
 * Reads back the files [ClipboardExport] writes (user 2026-10-31: 只能导出没有再导入). JSON and
 * Markdown keep fixed records, their codes and groups; TXT holds only the text, so its records come
 * back as history. The format is told from the content, not the file name.
 */
internal object ClipboardImport {
    /** Larger files are refused before reading: the whole clipboard holds about 2 MB of text. */
    const val MAX_FILE_BYTES = 16 * 1024 * 1024

    private const val TXT_SEPARATOR = "\n\n----------\n\n"
    private const val MD_TITLE = "# Lime 剪贴板"
    private val FENCE = Regex("^`{3,}$")
    private val GROUP_SUFFIX = Regex("（分组 (.+)）$")
    private val CODE_SUFFIX = Regex("（编码 (.+)）$")

    fun parse(raw: String): List<ImportedClip> {
        val content = normalize(raw)
        val head = content.trimStart()
        if (head.startsWith("{") || head.startsWith("[")) parseJson(head)?.let { return it }
        if (head.startsWith(MD_TITLE)) return parseMarkdown(content)
        return parseText(content)
    }

    /** A BOM goes; a file whose every line break became CRLF (opened on Windows) gets LF back. */
    private fun normalize(raw: String): String {
        val text = raw.removePrefix("﻿")
        val crlf = Regex("\r\n").findAll(text).count()
        return if (crlf > 0 && crlf == text.count { it == '\n' }) text.replace("\r\n", "\n") else text
    }

    private fun parseText(content: String): List<ImportedClip> =
        content.split(TXT_SEPARATOR).filter { it.isNotBlank() }.map { ImportedClip(it) }

    /** Null when this is not JSON at all (a TXT file whose first record starts with a brace). */
    private fun parseJson(content: String): List<ImportedClip>? {
        val array = try {
            if (content.startsWith("[")) JSONArray(content) else JSONObject(content).optJSONArray("items")
        } catch (_: org.json.JSONException) {
            return null
        } ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val text = item.opt("text") as? String ?: return@mapNotNull null
            if (text.isBlank()) return@mapNotNull null
            ImportedClip(
                text = text,
                pinned = item.optBoolean("pinned", false),
                code = (item.opt("code") as? String).orEmpty().trim(),
                timestamp = item.optLong("timestamp", 0).coerceAtLeast(0),
                group = (item.opt("group") as? String)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun parseMarkdown(content: String): List<ImportedClip> {
        val lines = content.split("\n")
        val clips = mutableListOf<ImportedClip>()
        var pinned = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line == "## 固定内容" -> pinned = true
                line == "## 历史记录" -> pinned = false
                line.startsWith("### ") -> {
                    var heading = line.removePrefix("### ").trim()
                    val group = GROUP_SUFFIX.find(heading)?.also { heading = heading.substring(0, it.range.first) }?.groupValues?.get(1)
                    val code = CODE_SUFFIX.find(heading)?.also { heading = heading.substring(0, it.range.first) }?.groupValues?.get(1).orEmpty()
                    var j = i + 1
                    while (j < lines.size && lines[j].isBlank()) j++
                    if (j < lines.size && FENCE.matches(lines[j])) {
                        val fence = lines[j]
                        val start = j + 1
                        var end = start
                        while (end < lines.size && lines[end] != fence) end++
                        val text = lines.subList(start, end).joinToString("\n")
                        if (text.isNotBlank()) clips += ImportedClip(text, pinned, code.trim(), time(heading.trim()), group?.trim())
                        i = end
                    }
                }
            }
            i++
        }
        return clips
    }

    /** Export writes minutes in the phone's locale; 0 when the heading holds no such time. */
    private fun time(text: String): Long {
        for (locale in listOf(Locale.getDefault(), Locale.ROOT)) {
            try {
                return SimpleDateFormat("yyyy-MM-dd HH:mm", locale).apply { isLenient = false }.parse(text)?.time ?: continue
            } catch (_: ParseException) {
            }
        }
        return 0
    }
}

/** What an import did: records added (of them fixed), already there, and not saved for lack of room. */
internal data class ClipboardImportResult(val added: Int, val pinned: Int, val existing: Int, val notSaved: Int) {
    val message: String get() = buildString {
        if (added == 0 && existing == 0 && notSaved == 0) { append("文件里没有能导入的记录"); return@buildString }
        append("已导入 $added 条")
        if (pinned > 0) append("（其中固定 $pinned 条）")
        if (existing > 0) append("，$existing 条已经有了，没再加")
        if (notSaved > 0) append("，还有 $notSaved 条剪贴板放不下，没有导入")
    }
}
